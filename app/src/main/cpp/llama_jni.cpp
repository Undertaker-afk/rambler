// Minimal llama.cpp JNI bridge: load a GGUF, run greedy completion on a prompt string.
#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>

#include "llama.h"

#define TAG "ramblerllm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr int kCtx = 2048;
constexpr int kBatch = 512;

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
};

bool g_backend_ready = false;

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_rambler_ime_LlamaBridge_nativeLoad(JNIEnv *env, jobject, jstring jpath, jint threads) {
    if (!g_backend_ready) {
        llama_backend_init();
        g_backend_ready = true;
    }
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.use_mmap = true;
    llama_model *model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) {
        LOGE("failed to load model");
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = kCtx;
    cp.n_batch = kBatch;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        LOGE("failed to create context");
        llama_model_free(model);
        return 0;
    }

    auto *e = new Engine{model, ctx, llama_model_get_vocab(model)};
    LOGI("model loaded");
    return reinterpret_cast<jlong>(e);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_rambler_ime_LlamaBridge_nativeGenerate(JNIEnv *env, jobject, jlong handle, jstring jprompt,
                                                jint max_tokens) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e) return nullptr;

    const char *p = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(p);
    env->ReleaseStringUTFChars(jprompt, p);

    // Each request is independent: wipe the KV cache.
    llama_memory_clear(llama_get_memory(e->ctx), true);

    // The prompt already contains the BOS marker, so don't add another.
    int n = -llama_tokenize(e->vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, false, true);
    if (n <= 0) return nullptr;
    std::vector<llama_token> tokens(n);
    if (llama_tokenize(e->vocab, prompt.c_str(), (int32_t) prompt.size(), tokens.data(), n, false, true) < 0)
        return nullptr;
    if (n + max_tokens > kCtx) {
        LOGE("prompt too long: %d tokens", n);
        return nullptr;
    }

    // Prefill in chunks of n_batch.
    for (int i = 0; i < n; i += kBatch) {
        int len = std::min(kBatch, n - i);
        llama_batch b = llama_batch_get_one(tokens.data() + i, len);
        if (llama_decode(e->ctx, b) != 0) {
            LOGE("prefill decode failed");
            return nullptr;
        }
    }

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    std::string out;
    char piece[256];
    for (int i = 0; i < max_tokens; i++) {
        llama_token tok = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, tok)) break;
        int len = llama_token_to_piece(e->vocab, tok, piece, sizeof(piece), 0, false);
        if (len > 0) out.append(piece, len);
        llama_batch b = llama_batch_get_one(&tok, 1);
        if (llama_decode(e->ctx, b) != 0) break;
    }
    llama_sampler_free(smpl);

    jbyteArray arr = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(arr, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_rambler_ime_LlamaBridge_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e) return;
    llama_free(e->ctx);
    llama_model_free(e->model);
    delete e;
}

// Minimal llama.cpp JNI bridge: load a GGUF, run greedy completion on a prompt.
// The static prompt prefix (system prompt + few-shot examples) is evaluated once and its model
// state is saved/restored, so each request only has to process the new user text.
#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "ramblerllm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr int kCtx = 3072;
constexpr int kBatch = 512;

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;

    // Cached state after evaluating `prefix_text`.
    std::string prefix_text;
    int prefix_len = 0;
    std::vector<uint8_t> prefix_state;
};

bool g_backend_ready = false;

std::string to_std(JNIEnv *env, jstring js) {
    const char *c = env->GetStringUTFChars(js, nullptr);
    std::string s(c);
    env->ReleaseStringUTFChars(js, c);
    return s;
}

bool tokenize(const llama_vocab *vocab, const std::string &text, std::vector<llama_token> &out) {
    // Prompts carry their own BOS / special tokens, so add_special=false, parse_special=true.
    int n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, false, true);
    if (n < 0) return false;
    out.resize(n);
    if (n == 0) return true;
    return llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), out.data(), n, false, true) >= 0;
}

bool decode(Engine *e, llama_token *tokens, int n) {
    for (int i = 0; i < n; i += kBatch) {
        int len = std::min(kBatch, n - i);
        llama_batch b = llama_batch_get_one(tokens + i, len);
        if (llama_decode(e->ctx, b) != 0) return false;
    }
    return true;
}

// Brings the context to "prefix evaluated" and returns the prefix length, or -1 on failure.
int prepare_prefix(Engine *e, const std::string &prefix) {
    llama_memory_clear(llama_get_memory(e->ctx), true);

    if (prefix == e->prefix_text && !e->prefix_state.empty()) {
        size_t r = llama_state_seq_set_data(e->ctx, e->prefix_state.data(), e->prefix_state.size(), 0);
        if (r != 0) return e->prefix_len;
        LOGE("prefix state restore failed, recomputing");
        llama_memory_clear(llama_get_memory(e->ctx), true);
    }

    std::vector<llama_token> toks;
    if (!tokenize(e->vocab, prefix, toks) || toks.empty()) return -1;
    if (!decode(e, toks.data(), (int) toks.size())) return -1;

    size_t sz = llama_state_seq_get_size(e->ctx, 0);
    std::vector<uint8_t> buf(sz);
    size_t got = sz ? llama_state_seq_get_data(e->ctx, buf.data(), sz, 0) : 0;
    e->prefix_text = prefix;
    e->prefix_len = (int) toks.size();
    if (got == sz && sz > 0) {
        e->prefix_state = std::move(buf);
    } else {
        e->prefix_state.clear();  // caching unavailable; we'll recompute next time
    }
    return e->prefix_len;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_rambler_ime_LlamaBridge_nativeLoad(JNIEnv *env, jobject, jstring jpath, jint threads) {
    if (!g_backend_ready) {
        llama_backend_init();
        g_backend_ready = true;
    }
    std::string path = to_std(env, jpath);
    llama_model_params mp = llama_model_default_params();
    llama_model *model = llama_model_load_from_file(path.c_str(), mp);
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

    auto *e = new Engine();
    e->model = model;
    e->ctx = ctx;
    e->vocab = llama_model_get_vocab(model);
    LOGI("model loaded");
    return reinterpret_cast<jlong>(e);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_rambler_ime_LlamaBridge_nativeGenerate(JNIEnv *env, jobject, jlong handle, jstring jprefix,
                                                jstring jsuffix, jint max_tokens) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e) return nullptr;

    std::string prefix = to_std(env, jprefix);
    std::string suffix = to_std(env, jsuffix);

    int prefix_len = prepare_prefix(e, prefix);
    if (prefix_len < 0) {
        LOGE("prefix evaluation failed");
        return nullptr;
    }

    std::vector<llama_token> toks;
    if (!tokenize(e->vocab, suffix, toks) || toks.empty()) return nullptr;
    if (prefix_len + (int) toks.size() + max_tokens > kCtx) {
        LOGE("prompt too long: %d + %zu tokens", prefix_len, toks.size());
        return nullptr;
    }
    if (!decode(e, toks.data(), (int) toks.size())) {
        LOGE("suffix decode failed");
        return nullptr;
    }

    // Greedy decoding: this task is mostly faithful copying, so no sampling noise and no
    // repetition penalty (which would punish copying the user's own words).
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

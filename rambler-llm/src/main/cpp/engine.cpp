#include "engine.h"

#include <algorithm>

#include "llama.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ramblerllm", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ramblerllm", __VA_ARGS__)
#else
#include <cstdio>
#define LOGE(...) (fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
#define LOGI(...) (fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
#endif

namespace rambler {
namespace {

bool g_backend_ready = false;

bool tokenize(const llama_vocab *vocab, const std::string &text, std::vector<llama_token> &out) {
    // Prompts carry their own special tokens: add_special=false, parse_special=true.
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

// Brings the context to "prefix evaluated"; returns the prefix length, or -1 on failure.
int prepare_prefix(Engine *e, const std::string &prefix, bool *used_cache) {
    llama_memory_clear(llama_get_memory(e->ctx), true);
    if (used_cache) *used_cache = false;

    if (prefix == e->prefix_text && !e->prefix_state.empty()) {
        size_t r = llama_state_seq_set_data(e->ctx, e->prefix_state.data(), e->prefix_state.size(), 0);
        if (r != 0) {
            if (used_cache) *used_cache = true;
            return e->prefix_len;
        }
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
    if (got == sz && sz > 0) e->prefix_state = std::move(buf);
    else e->prefix_state.clear();  // caching unavailable; recompute next time
    return e->prefix_len;
}

}  // namespace

Engine *load(const char *path, int threads) {
    if (!g_backend_ready) {
        llama_backend_init();
        g_backend_ready = true;
    }
    llama_model_params mp = llama_model_default_params();
    llama_model *model = llama_model_load_from_file(path, mp);
    if (!model) {
        LOGE("failed to load model");
        return nullptr;
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
        return nullptr;
    }
    auto *e = new Engine();
    e->model = model;
    e->ctx = ctx;
    e->vocab = llama_model_get_vocab(model);
    LOGI("model loaded");
    return e;
}

void release(Engine *e) {
    if (!e) return;
    llama_free(e->ctx);
    llama_model_free(e->model);
    delete e;
}

bool generate(Engine *e, const std::string &prefix, const std::string &suffix, int max_tokens,
              std::string &out, bool *used_cache) {
    out.clear();
    int prefix_len = prepare_prefix(e, prefix, used_cache);
    if (prefix_len < 0) {
        LOGE("prefix evaluation failed");
        return false;
    }
    std::vector<llama_token> toks;
    if (!tokenize(e->vocab, suffix, toks) || toks.empty()) return false;
    if (prefix_len + (int) toks.size() + max_tokens > kCtx) {
        LOGE("prompt too long: %d + %zu tokens", prefix_len, toks.size());
        return false;
    }
    if (!decode(e, toks.data(), (int) toks.size())) {
        LOGE("suffix decode failed");
        return false;
    }

    // Greedy: this task is mostly faithful copying, so no sampling noise and no repetition penalty.
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    char piece[256];
    for (int i = 0; i < max_tokens; i++) {
        llama_token tok = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, tok)) break;
        int len = llama_token_to_piece(e->vocab, tok, piece, sizeof(piece), 0, false);
        if (len > 0) out.append(piece, len);
        // Messages are one paragraph; stop at a blank line so the model can't ramble on.
        if (out.size() >= 2 && out.compare(out.size() - 2, 2, "\n\n") == 0) break;
        llama_batch b = llama_batch_get_one(&tok, 1);
        if (llama_decode(e->ctx, b) != 0) break;
    }
    llama_sampler_free(smpl);
    return true;
}

}  // namespace rambler

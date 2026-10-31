// Core llama.cpp logic, independent of JNI so it can be tested on a desktop.
#pragma once
#include <string>
#include <vector>
#include <cstdint>

struct llama_model;
struct llama_context;
struct llama_vocab;

namespace rambler {

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;

    // Model state after evaluating `prefix_text`, restored instead of re-evaluating the prefix.
    std::string prefix_text;
    int prefix_len = 0;
    std::vector<uint8_t> prefix_state;
};

constexpr int kCtx = 3072;
constexpr int kBatch = 512;

Engine *load(const char *path, int threads);
void release(Engine *e);

// Greedy completion for prefix + suffix. The prefix's state is cached between calls.
// Returns false on failure. `used_cache` (optional) reports whether the cached prefix was restored.
bool generate(Engine *e, const std::string &prefix, const std::string &suffix, int max_tokens,
              std::string &out, bool *used_cache = nullptr);

}  // namespace rambler

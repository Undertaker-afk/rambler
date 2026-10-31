package org.futo.inputmethod.rambler

/**
 * A model the user can pick. Every entry must speak ChatML (<|im_start|>role ... <|im_end|>) and be
 * supported by the pinned llama.cpp; the only per-model differences are the BOS token and whether
 * the template needs an empty think block to switch reasoning off.
 */
data class ModelSpec(
    val id: String,
    val name: String,
    val note: String,
    val file: String,
    val url: String,
    /** Approximate download size, for display and as a sanity check. */
    val sizeBytes: Long,
    /** Token the template starts with, or empty if none. */
    val bos: String = "",
    /** Text appended after the assistant tag to disable reasoning, or empty if the model doesn't reason. */
    val thinkingOff: String = "",
) {
    val sizeLabel: String
        get() = if (sizeBytes >= 1_000_000_000L) "%.1f GB".format(sizeBytes / 1e9) else "${sizeBytes / 1_000_000} MB"

    /** A finished download must be at least this big (guards against truncated files). */
    val minBytes: Long get() = (sizeBytes * 0.95).toLong()
}

object Models {
    private const val QWEN_OFF = "<think>\n\n</think>\n\n"

    val QWEN35_2B = ModelSpec(
        id = "qwen3.5-2b",
        name = "Qwen3.5 2B",
        note = "Recommended. Good balance of quality and speed.",
        file = "Qwen3.5-2B-Q4_K_M.gguf",
        url = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf",
        sizeBytes = 1_280_835_840L,
        thinkingOff = QWEN_OFF,
    )

    val all: List<ModelSpec> = listOf(
        ModelSpec(
            id = "qwen3.5-0.8b",
            name = "Qwen3.5 0.8B",
            note = "Smallest and fastest. Makes more mistakes.",
            file = "Qwen3.5-0.8B-Q4_K_M.gguf",
            url = "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf",
            sizeBytes = 532_517_120L,
            thinkingOff = QWEN_OFF,
        ),
        QWEN35_2B,
        ModelSpec(
            id = "qwen3.5-4b",
            name = "Qwen3.5 4B",
            note = "Most accurate here, but large and slower on a phone.",
            file = "Qwen3.5-4B-Q4_K_M.gguf",
            url = "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf",
            sizeBytes = 2_740_937_888L,
            thinkingOff = QWEN_OFF,
        ),
        ModelSpec(
            id = "lfm2.5-1.2b",
            name = "Liquid LFM2.5 1.2B",
            note = "Liquid AI, small and quick.",
            file = "LFM2.5-1.2B-Instruct-Q4_K_M.gguf",
            url = "https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct-GGUF/resolve/main/LFM2.5-1.2B-Instruct-Q4_K_M.gguf",
            sizeBytes = 731_000_000L,
            bos = "<|startoftext|>",
        ),
        ModelSpec(
            id = "lfm2.5-2.6b",
            name = "Liquid LFM2.5 2.6B",
            note = "Liquid AI, larger.",
            file = "LFM2.5-2.6B-Q4_K_M.gguf",
            url = "https://huggingface.co/LiquidAI/LFM2.5-2.6B-GGUF/resolve/main/LFM2.5-2.6B-Q4_K_M.gguf",
            sizeBytes = 1_674_000_000L,
            bos = "<|startoftext|>",
        ),
    )

    val default: ModelSpec = QWEN35_2B

    fun byId(id: String?): ModelSpec = all.firstOrNull { it.id == id } ?: default
}

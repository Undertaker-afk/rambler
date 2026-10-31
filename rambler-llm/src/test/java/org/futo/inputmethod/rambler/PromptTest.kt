package org.futo.inputmethod.rambler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTest {
    private val qwen = Models.QWEN35_2B
    private val lfm = Models.all.first { it.id == "lfm2.5-1.2b" }

    @Test fun qwenPrefixHasNoBosAndEndsAtTurnBoundary() {
        val p = LlmCleaner.buildPrefix(qwen)
        assertTrue(p.startsWith("<|im_start|>system\n"))
        assertTrue(p.endsWith("<|im_end|>\n"))
    }

    @Test fun lfmPrefixStartsWithBosAndHasNoThinkBlock() {
        val p = LlmCleaner.buildPrefix(lfm)
        assertTrue(p.startsWith("<|startoftext|><|im_start|>system\n"))
        assertFalse(p.contains("<think>"))
    }

    @Test fun qwenSuffixDisablesThinking() {
        assertEquals(
            "<|im_start|>user\nhello there<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n",
            LlmCleaner.buildSuffix(qwen, "hello there"),
        )
    }

    @Test fun lfmSuffixHasNothingAfterAssistantTag() {
        assertTrue(LlmCleaner.buildSuffix(lfm, "hi").endsWith("<|im_start|>assistant\n"))
    }

    @Test fun prefixIsStaticSoItCanBeCached() {
        assertFalse(LlmCleaner.buildPrefix(qwen).contains("hello there"))
    }

    @Test fun catalogIsSane() {
        assertEquals(Models.all.size, Models.all.map { it.id }.toSet().size)
        assertTrue(Models.all.contains(Models.default))
        assertEquals(Models.default, Models.byId("does-not-exist"))
        assertTrue(Models.all.all { it.url.startsWith("https://") && it.file.endsWith(".gguf") && it.minBytes > 100_000_000L })
    }
}

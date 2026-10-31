package dev.rambler.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTest {
    @Test fun prefixStartsWithBosAndEndsAtTurnBoundary() {
        assertTrue(LlmCleaner.PREFIX.startsWith("<|startoftext|><|im_start|>system\n"))
        assertTrue(LlmCleaner.PREFIX.endsWith("<|im_end|>\n"))
    }

    @Test fun suffixDisablesThinking() {
        assertEquals(
            "<|im_start|>user\nhello there<|im_end|>\n<|im_start|>assistant\n<think></think>",
            LlmCleaner.buildSuffix("hello there"),
        )
    }

    @Test fun prefixIsStaticSoItCanBeCached() {
        assertFalse(LlmCleaner.PREFIX.contains("hello there"))
    }
}

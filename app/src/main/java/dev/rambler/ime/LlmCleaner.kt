package dev.rambler.ime

import android.content.Context

/**
 * Turns a raw dictated transcript into the text that should be typed, using Liquid AI LFM3 on
 * device via llama.cpp. In one pass the model removes fillers and self-corrections, drops side
 * remarks, and carries out spoken instructions to the keyboard ("add fitting emojis",
 * "translate to German"). Falls back to [RuleBasedCleaner] when the model is unavailable.
 */
class LlmCleaner private constructor(private val context: Context) {

    /** [note] is a short user-facing hint when something couldn't be done. */
    data class Result(val text: String, val note: String? = null)

    private var handle = 0L
    private val lock = Any()

    val isModelReady: Boolean get() = LlamaBridge.available && ModelManager.isDownloaded(context)

    /** Never throws. Call off the main thread. */
    fun process(raw: String): Result {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Result("")

        if (!isModelReady) {
            // No model: basic cleanup of the message part only; commands can't be executed.
            val parsed = CommandParser.parse(trimmed)
            val note = if (parsed.instruction != null) "Voice commands need the model - download it in the Rambler app" else null
            return Result(RuleBasedCleaner.clean(parsed.message), note)
        }

        val fallback = RuleBasedCleaner.clean(CommandParser.parse(trimmed).message)
        return try {
            synchronized(lock) {
                if (handle == 0L) {
                    val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
                    handle = LlamaBridge.nativeLoad(ModelManager.modelFile(context).path, threads)
                }
                if (handle == 0L) return Result(fallback, "Model failed to load - used basic cleanup")

                val maxTokens = (trimmed.length + 64).coerceIn(64, 768)
                val bytes = LlamaBridge.nativeGenerate(handle, PREFIX, buildSuffix(trimmed), maxTokens)
                    ?: return Result(fallback, "Model error - used basic cleanup")
                val out = postProcess(String(bytes, Charsets.UTF_8))
                if (isSane(trimmed, out)) Result(out) else Result(fallback, "Model output rejected - used basic cleanup")
            }
        } catch (t: Throwable) {
            Result(fallback, "Model error - used basic cleanup")
        }
    }

    fun close() = synchronized(lock) {
        if (handle != 0L) LlamaBridge.nativeFree(handle)
        handle = 0L
    }

    private fun postProcess(s: String): String =
        s.substringAfter("</think>").trim().removeSurrounding("\"").trim()

    /** Reject empty output or output wildly longer than the speech (translation/emojis may grow it). */
    private fun isSane(input: String, output: String): Boolean =
        output.isNotEmpty() && output.length <= input.length * 2.5 + 40

    companion object {
        @Volatile private var instance: LlmCleaner? = null
        fun get(context: Context): LlmCleaner =
            instance ?: synchronized(this) {
                instance ?: LlmCleaner(context.applicationContext).also { instance = it }
            }

        private const val SYSTEM = """You are the writing engine of a voice keyboard. The user dictated a message out loud. Produce the exact text that should be typed into the message box.
1. Remove filler sounds and words (um, uh, er, like, you know).
2. If the speaker restarts a sentence or corrects themselves, keep only the final version.
3. Drop speech that is not part of the message: swearing at traffic, talking to someone nearby, thinking aloud.
4. Fix punctuation and capitalisation. Keep the speaker's wording, tone and language.
5. The speaker may give the keyboard an instruction, often addressed to "Rambler", such as "add fitting emojis", "translate to German", "make it shorter" or "make it more formal". Carry out the instruction on the message and do not type the instruction itself. Only treat speech as an instruction when it is clearly addressed to the keyboard or is an editing command for the whole message. Requests addressed to the message's recipient are part of the message.
6. Never answer questions and never add new information.
Output only the final text."""

        // Each example: dictated speech -> text to type. Emoji are escaped to keep source ASCII.
        private val EXAMPLES = listOf(
            "um so I'll be there at uh five no wait six o'clock" to
                "I'll be there at six o'clock.",
            "hey can you pick up milk and oh come on you idiot green light dude anyway and some bread" to
                "Hey, can you pick up milk and some bread?",
            "running late be there in ten minutes rambler add fitting emojis" to
                "Running late, be there in ten minutes \uD83C\uDFC3\u23F1\uFE0F",
            "ich komme heute etwas später rambler translate that to English" to
                "I'll be a little late today.",
            "hey can you translate the contract for me by friday" to
                "Hey, can you translate the contract for me by Friday?",
            "thanks for dinner it was great make that a bit more casual" to
                "Thanks for dinner, it was great!",
        )

        /** Static part of the prompt: cached natively so only the user's speech is processed per request. */
        val PREFIX: String = buildString {
            append("<|startoftext|>")
            append("<|im_start|>system\n").append(SYSTEM).append("<|im_end|>\n")
            for ((u, a) in EXAMPLES) {
                append("<|im_start|>user\n").append(u).append("<|im_end|>\n")
                append("<|im_start|>assistant\n").append(a).append("<|im_end|>\n")
            }
        }

        /** LFM3 thinks by default; an empty <think></think> block turns that off. */
        fun buildSuffix(raw: String): String =
            "<|im_start|>user\n$raw<|im_end|>\n<|im_start|>assistant\n<think></think>"
    }
}

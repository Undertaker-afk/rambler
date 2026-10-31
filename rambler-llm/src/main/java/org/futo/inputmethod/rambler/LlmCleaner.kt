package org.futo.inputmethod.rambler

import android.content.Context

/**
 * Turns a raw dictated transcript into the text that should be typed, using a small model on
 * device via llama.cpp. In one pass the model removes fillers and self-corrections, drops side
 * remarks, and carries out spoken instructions to the keyboard ("add fitting emojis",
 * "translate to German"). Falls back to [RuleBasedCleaner] when the model is unavailable.
 */
class LlmCleaner private constructor(private val context: Context) {

    /** [note] is a short user-facing hint when something couldn't be done. */
    data class Result(val text: String, val note: String? = null)

    private var handle = 0L
    private var loadedModel: String? = null
    private val lock = Any()

    val isModelReady: Boolean get() = LlamaBridge.available && ModelManager.isDownloaded(context)

    /** Never throws. Call off the main thread. */
    fun process(raw: String): Result {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Result("")

        if (!isModelReady) {
            // No model: basic cleanup of the message part only; commands can't be executed.
            val parsed = CommandParser.parse(trimmed)
            val note = if (parsed.instruction != null) "Voice commands need the model - download one in Voice input settings" else null
            return Result(RuleBasedCleaner.clean(parsed.message), note)
        }

        val fallback = RuleBasedCleaner.clean(CommandParser.parse(trimmed).message)
        return try {
            synchronized(lock) {
                val spec = ModelManager.selected(context)
                if (handle != 0L && loadedModel != spec.id) {   // the user picked another model
                    LlamaBridge.nativeFree(handle); handle = 0L; loadedModel = null
                }
                if (handle == 0L) {
                    val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
                    handle = LlamaBridge.nativeLoad(ModelManager.modelFile(context, spec).path, threads)
                    if (handle != 0L) loadedModel = spec.id
                }
                if (handle == 0L) return Result(fallback, "Model failed to load - used basic cleanup")

                val maxTokens = (trimmed.length + 64).coerceIn(64, 768)
                val bytes = LlamaBridge.nativeGenerate(handle, buildPrefix(spec), buildSuffix(spec, trimmed), maxTokens)
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
        handle = 0L; loadedModel = null
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

        private const val SYSTEM = """You are the writing engine of a voice keyboard. The user dictates a message; you output the exact text to type, nothing else.

Do all of this:
1. Delete fillers: um, uh, er, like, you know, basically.
2. If the speaker restarts or corrects themselves ("no wait", "actually", "I mean", "sorry"), keep only the final version.
3. Delete every remark that is not part of the message: insults, curses and exclamations aimed at other drivers, cyclists or people ("stupid cyclist", "what the hell", "watch it buddy", "get out of the way"), and anything said to someone nearby. Delete only the remark itself: keep every other part of the message, including what the speaker says after the remark.
4. Fix punctuation and capitalisation. Keep the wording and the language.
5. Commands: if the speech contains an instruction to the keyboard, usually after the word "Rambler" (add emojis, translate to a language, make it shorter, make it more formal or casual), apply it to the message. Never type the instruction or the word "Rambler".
6. A request to the reader ("can you translate this for me") is part of the message, not a command.
Never answer or comment. Output only the final text."""

        // Each example: dictated speech -> text to type. Emoji are escaped to keep the source ASCII.
        private val EXAMPLES = listOf(
            "um so I'll be there at uh five no wait six o'clock" to
                "I'll be there at six o'clock.",
            "I'm on my way hey that's my spot you idiot anyway see you in ten" to
                "I'm on my way, see you in ten.",
            "so I just wanted to say that like the meeting is moved to thursday" to
                "The meeting is moved to Thursday.",
            "running late be there in ten minutes rambler add fitting emojis" to
                "Running late, be there in ten minutes \uD83C\uDFC3\u23F1\uFE0F",
            "can you send me the address oh my god move already the light is green sorry the address of the restaurant" to
                "Can you send me the address of the restaurant?",
            "ich komme heute etwas sp\u00E4ter rambler translate to English" to
                "I'll be a little late today.",
            "I'll pick you up at six hey watch it you clown and don't forget your keys" to
                "I'll pick you up at six, and don't forget your keys.",
            "what the hell is wrong with this guy anyway the meeting starts at nine" to
                "The meeting starts at nine.",
            "thanks for dinner it was great rambler make it more formal" to
                "Thank you for the dinner; it was wonderful.",
            "can you translate the contract for me by friday" to
                "Can you translate the contract for me by Friday?",
            "tell her happy birthday no not you honey I'm on the phone tell her happy birthday and that I'll call tomorrow" to
                "Tell her happy birthday and that I'll call tomorrow.",
            "we are out of coffee so please buy some rambler translate to German" to
                "Wir haben keinen Kaffee mehr, bitte kauf welchen.",
            "I think uh we should I mean we could move it to monday" to
                "I think we could move it to Monday.",
        )

        /** Static part of the prompt (ChatML): cached natively so only the user's speech is processed per request. */
        fun buildPrefix(spec: ModelSpec): String = buildString {
            append(spec.bos)
            append("<|im_start|>system\n").append(SYSTEM).append("<|im_end|>\n")
            for ((u, a) in EXAMPLES) {
                append("<|im_start|>user\n").append(u).append("<|im_end|>\n")
                append("<|im_start|>assistant\n").append(spec.thinkingOff).append(a).append("<|im_end|>\n")
            }
        }

        fun buildSuffix(spec: ModelSpec, raw: String): String =
            "<|im_start|>user\n$raw<|im_end|>\n<|im_start|>assistant\n${spec.thinkingOff}"
    }
}

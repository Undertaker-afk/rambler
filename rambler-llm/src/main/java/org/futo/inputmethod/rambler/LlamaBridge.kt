package org.futo.inputmethod.rambler

/** Thin JNI wrapper around llama.cpp (see src/main/cpp/llama_jni.cpp). */
object LlamaBridge {
    val available: Boolean = try {
        System.loadLibrary("ramblerllm")
        true
    } catch (e: UnsatisfiedLinkError) {
        false
    }

    /** Returns a native handle, or 0 on failure. */
    external fun nativeLoad(modelPath: String, threads: Int): Long

    /**
     * Greedy-decodes a completion for prefix + suffix; returns UTF-8 bytes or null on failure.
     * [prefix] must be the static part of the prompt: its model state is cached natively and
     * reused between calls, so only [suffix] is processed each time.
     */
    external fun nativeGenerate(handle: Long, prefix: String, suffix: String, maxTokens: Int): ByteArray?

    external fun nativeFree(handle: Long)
}

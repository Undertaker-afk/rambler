package dev.rambler.ime

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

    /** Greedy-decodes a completion for [prompt]; returns UTF-8 bytes or null on failure. */
    external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int): ByteArray?

    external fun nativeFree(handle: Long)
}

package org.futo.inputmethod.rambler

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and locates the on-device model.
 *
 * Default: Qwen3.5-2B (Apache-2.0), Q4_K_M, ~1.3 GB, via the unsloth GGUF build. Needs a llama.cpp
 * with qwen3.5 support (we pin b12020). The smaller Qwen3.5-0.8B (~0.5 GB) also loads but was
 * noticeably less reliable in testing. The prompt in [LlmCleaner] is ChatML with thinking disabled.
 */
object ModelManager {
    const val MODEL_FILE = "Qwen3.5-2B-Q4_K_M.gguf"
    const val MODEL_URL = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/$MODEL_FILE"
    const val MODEL_SIZE_LABEL = "about 1.3 GB"

    private const val MIN_VALID_BYTES = 500_000_000L

    fun modelFile(context: Context): File =
        File(File(context.filesDir, "models").apply { mkdirs() }, MODEL_FILE)

    fun isDownloaded(context: Context): Boolean =
        modelFile(context).let { it.exists() && it.length() > MIN_VALID_BYTES }

    /** Blocking, resumable download. [onProgress] gets 0..100. Throws on failure. */
    fun download(context: Context, onProgress: (Int) -> Unit) {
        val target = modelFile(context)
        val part = File(target.path + ".part")
        val have = if (part.exists()) part.length() else 0L

        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = conn.responseCode
            val resumed = code == 206
            check(code == 200 || resumed) { "HTTP $code" }
            val start = if (resumed) have else 0L
            val total = start + conn.contentLengthLong

            RandomAccessFile(part, "rw").use { out ->
                out.seek(start)
                out.setLength(start)
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var done = start
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > start) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != last) { last = pct; onProgress(pct) }
                        }
                    }
                }
            }
            check(part.length() > MIN_VALID_BYTES) { "Download incomplete" }
            check(part.renameTo(target)) { "Could not move model into place" }
            // Remove models from earlier versions of the app.
            target.parentFile?.listFiles { f -> f.name.endsWith(".gguf") && f.name != MODEL_FILE }
                ?.forEach { it.delete() }
        } finally {
            conn.disconnect()
        }
        // A failed or interrupted download keeps the .part file so the next attempt resumes.
    }
}

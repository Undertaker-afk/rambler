package dev.rambler.ime

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and locates the on-device model.
 *
 * Default: Liquid AI LFM3-1.7B, the QAD (quantization-aware distilled) Q4_0 build, ~1.05 GB.
 * Requires llama.cpp b11960 or newer. Any other ChatML-style GGUF can be swapped in via the
 * constants below, but [LlmCleaner] builds the LFM3 prompt format.
 */
object ModelManager {
    const val MODEL_FILE = "LFM3-1.7B-QAD-Q4_0.gguf"
    const val MODEL_URL = "https://huggingface.co/LiquidAI/LFM3-1.7B-GGUF/resolve/main/$MODEL_FILE"
    const val MODEL_SIZE_LABEL = "about 1 GB"

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

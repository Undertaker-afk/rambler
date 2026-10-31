package dev.rambler.ime

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and locates the small GGUF model.
 *
 * Default: Liquid AI LFM2-350M (Q4_K_M, ~230 MB). Swap [MODEL_URL]/[MODEL_FILE] for any
 * ChatML-style GGUF (e.g. LFM2-700M, Qwen2.5-0.5B-Instruct) if you want a different trade-off.
 */
object ModelManager {
    const val MODEL_FILE = "LFM2-350M-Q4_K_M.gguf"
    const val MODEL_URL =
        "https://huggingface.co/LiquidAI/LFM2-350M-GGUF/resolve/main/$MODEL_FILE"

    fun modelFile(context: Context): File =
        File(File(context.filesDir, "models").apply { mkdirs() }, MODEL_FILE)

    fun isDownloaded(context: Context): Boolean = modelFile(context).let { it.exists() && it.length() > 1_000_000 }

    /** Blocking download. [onProgress] gets 0..100. Throws on failure. */
    fun download(context: Context, onProgress: (Int) -> Unit) {
        val target = modelFile(context)
        val part = File(target.path + ".part")
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        try {
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != last) { last = pct; onProgress(pct) }
                        }
                    }
                }
            }
            check(part.renameTo(target)) { "Could not move model into place" }
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }
}

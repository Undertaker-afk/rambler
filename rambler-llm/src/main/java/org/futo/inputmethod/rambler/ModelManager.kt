package org.futo.inputmethod.rambler

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Selects, downloads and locates the on-device models listed in [Models]. The selection is kept in
 * app-private SharedPreferences so this module stays independent of the keyboard's own settings.
 */
object ModelManager {
    private const val PREFS = "rambler"
    private const val KEY_SELECTED = "selected_model"

    private fun modelsDir(context: Context) = File(context.filesDir, "models").apply { mkdirs() }

    // ------------------------------------------------------------------ selection

    fun selected(context: Context): ModelSpec =
        Models.byId(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SELECTED, null))

    fun select(context: Context, spec: ModelSpec) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SELECTED, spec.id).apply()
    }

    // ------------------------------------------------------------------ files

    fun modelFile(context: Context, spec: ModelSpec = selected(context)): File = File(modelsDir(context), spec.file)

    fun isDownloaded(context: Context, spec: ModelSpec = selected(context)): Boolean =
        modelFile(context, spec).let { it.exists() && it.length() >= spec.minBytes }

    fun downloadedModels(context: Context): List<ModelSpec> = Models.all.filter { isDownloaded(context, it) }

    /** Bytes used by all downloaded models and unfinished downloads. */
    fun diskUsage(context: Context): Long = modelsDir(context).listFiles()?.sumOf { it.length() } ?: 0L

    /** Deletes every downloaded or partial model except the currently selected one. Returns bytes freed. */
    fun deleteOthers(context: Context): Long {
        val keep = modelFile(context).name
        var freed = 0L
        modelsDir(context).listFiles()?.forEach {
            if (it.name != keep && it.name != "$keep.part") { freed += it.length(); it.delete() }
        }
        return freed
    }

    // ------------------------------------------------------------------ download

    /** Blocking, resumable download of [spec]. [onProgress] gets 0..100. Throws on failure. */
    fun download(context: Context, spec: ModelSpec, onProgress: (Int) -> Unit) {
        val target = modelFile(context, spec)
        val part = File(target.path + ".part")
        val have = if (part.exists()) part.length() else 0L

        val conn = URL(spec.url).openConnection() as HttpURLConnection
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
            check(part.length() >= spec.minBytes) { "Download incomplete" }
            check(part.renameTo(target)) { "Could not move model into place" }
        } finally {
            conn.disconnect()
        }
        // A failed or interrupted download keeps the .part file so the next attempt resumes.
    }
}

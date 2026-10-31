package dev.rambler.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.app.Activity
import java.util.concurrent.Executors

/** Setup screen: mic permission, model download, enable/select the keyboard, and a test field. */
class SettingsActivity : Activity() {

    private lateinit var modelStatus: TextView
    private lateinit var micStatus: TextView
    private lateinit var downloadBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        fun label(t: String) = TextView(this).apply { text = t; textSize = 16f; setPadding(0, pad, 0, pad / 4) }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply { text = t; setOnClickListener { onClick() } }

        micStatus = TextView(this)
        modelStatus = TextView(this)
        downloadBtn = button("Download model (${ModelManager.MODEL_SIZE_LABEL})") { downloadModel() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(TextView(this@SettingsActivity).apply { text = "Rambler"; textSize = 28f })
            addView(TextView(this@SettingsActivity).apply {
                text = "Talk naturally. Rambler listens to your whole thought, removes the ums, restarts and side comments, follows spoken instructions like \"translate to English\", and types one clean message."
            })

            addView(label("1. Microphone"))
            addView(micStatus)
            addView(button("Grant microphone permission") {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            })

            addView(label("2. Writing model (on-device, Liquid AI LFM3-1.7B)"))
            addView(modelStatus)
            addView(downloadBtn)

            addView(label("3. Turn the keyboard on"))
            addView(button("Enable Rambler keyboard") {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            })
            addView(button("Switch to Rambler") {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            })

            addView(label("Try it"))
            addView(EditText(this@SettingsActivity).apply { hint = "Tap here, then use Talk" })
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        refresh()
    }

    private fun refresh() {
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        micStatus.text = if (granted) "Granted" else "Not granted"
        val ready = ModelManager.isDownloaded(this)
        modelStatus.text = when {
            !LlamaBridge.available -> "Native LLM library failed to load - using basic cleanup only"
            ready -> "Model installed"
            else -> "Not installed - basic rule-based cleanup is used until you download it"
        }
        downloadBtn.isEnabled = !ready
    }

    private fun downloadModel() {
        downloadBtn.isEnabled = false
        Executors.newSingleThreadExecutor().execute {
            try {
                ModelManager.download(this) { pct -> runOnUiThread { modelStatus.text = "Downloading... $pct%" } }
            } catch (e: Exception) {
                runOnUiThread { modelStatus.text = "Download failed: ${e.message}" }
            }
            runOnUiThread { if (!ModelManager.isDownloaded(this)) downloadBtn.isEnabled = true else refresh() }
        }
    }
}

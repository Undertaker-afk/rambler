package dev.rambler.ime

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.util.concurrent.Executors

/** Material 3 setup screen: microphone, model download, enable/select the keyboard, test field. */
class SettingsActivity : Activity() {

    private lateinit var micStatus: TextView
    private lateinit var modelStatus: TextView
    private lateinit var downloadBtn: MaterialButton
    private lateinit var micBtn: MaterialButton
    private lateinit var progress: LinearProgressIndicator

    private val dp get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        micStatus = body("")
        modelStatus = body("")
        micBtn = button("Allow microphone", filled = true) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }
        progress = LinearProgressIndicator(this).apply { visibility = View.GONE; max = 100 }
        downloadBtn = button("Download model (${ModelManager.MODEL_SIZE_LABEL})", filled = true) { downloadModel() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(24), px(16), px(24))
            addView(title("Rambler"))
            addView(body("Talk naturally. Rambler listens to your whole thought, removes the ums, restarts and side comments, follows spoken instructions like \"translate to English\", and types one clean message."))

            addView(card("Microphone", micStatus, micBtn))
            addView(card("Writing model", modelStatus, progress, downloadBtn))
            addView(card(
                "Turn on the keyboard",
                body("Enable Rambler in system settings, then switch to it."),
                button("Enable keyboard", filled = false) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                button("Switch keyboard", filled = false) {
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
                },
            ))
            addView(card("Try it", EditText(this@SettingsActivity).apply { hint = "Tap here, then use the mic on the keyboard" }))
        }
        val scroll = ScrollView(this).apply {
            addView(root)
            clipToPadding = false
            setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom); insets
            }
        }
        setContentView(scroll)
    }

    override fun onResume() { super.onResume(); refresh() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = refresh()

    private fun refresh() {
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        micStatus.text = if (granted) "Microphone access granted." else "Rambler needs the microphone to hear you."
        micBtn.visibility = if (granted) View.GONE else View.VISIBLE

        val ready = ModelManager.isDownloaded(this)
        modelStatus.text = when {
            !LlamaBridge.available -> "The on-device model library could not load. Basic cleanup only."
            ready -> "Liquid AI LFM3-1.7B is installed and runs on your phone."
            else -> "Not installed. Basic rule-based cleanup is used until you download the model. Voice commands need it."
        }
        downloadBtn.visibility = if (ready) View.GONE else View.VISIBLE
        downloadBtn.isEnabled = true
    }

    private fun downloadModel() {
        downloadBtn.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        Executors.newSingleThreadExecutor().execute {
            try {
                ModelManager.download(this) { pct ->
                    runOnUiThread {
                        progress.isIndeterminate = false; progress.progress = pct
                        modelStatus.text = "Downloading... $pct%"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { modelStatus.text = "Download failed: ${e.message}. Tap Download to resume." }
            }
            runOnUiThread { progress.visibility = View.GONE; refresh() }
        }
    }

    // ---------------------------------------------------------------- small view helpers

    private fun title(t: String) = TextView(this).apply {
        text = t; textSize = 32f; setPadding(px(4), 0, 0, px(8))
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineMedium)
    }

    private fun body(t: String) = TextView(this).apply {
        text = t; setPadding(px(4), px(4), px(4), px(8))
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
    }

    private fun button(t: String, filled: Boolean, onClick: () -> Unit) = MaterialButton(
        this, null,
        if (filled) com.google.android.material.R.attr.materialButtonStyle
        else com.google.android.material.R.attr.materialButtonOutlinedStyle,
    ).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = px(4) }
    }

    private fun card(heading: String, vararg content: View) = MaterialCardView(
        this, null, com.google.android.material.R.attr.materialCardViewFilledStyle,
    ).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = px(12) }
        radius = 24 * dp
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(16), px(16), px(16))
            addView(TextView(context).apply {
                text = heading
                setPadding(px(4), 0, 0, px(4))
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            })
            content.forEach { addView(it) }
        })
    }
}

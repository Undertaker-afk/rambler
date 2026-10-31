package dev.rambler.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Voice-only keyboard: tap the mic, ramble, tap again (or just stop talking). The transcript is
 * cleaned by a tiny on-device LLM and committed into the focused text field as one tidy message.
 */
class RamblerIME : InputMethodService() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var status: TextView
    private lateinit var mic: Button
    private var capture: SpeechCapture? = null
    private var busy = false

    override fun onCreateInputView(): View {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        status = TextView(this).apply {
            text = "Tap Talk, say your whole thought, then tap Done"
            setTextColor(Color.parseColor("#DDDDDD"))
            textSize = 14f
            gravity = Gravity.CENTER
            minLines = 2
            maxLines = 4
            setPadding(px(12), px(8), px(12), px(4))
        }

        fun key(label: String, weight: Float, onClick: () -> Unit) = Button(this).apply {
            text = label
            textSize = 16f
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, px(64), weight).apply { setMargins(px(3), px(3), px(3), px(3)) }
        }

        mic = key("Talk", 3f) { toggleListening() }.apply { typeface = Typeface.DEFAULT_BOLD }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(4), px(2), px(4), px(8))
            addView(key("Switch", 1f) { switchKeyboard() })
            addView(key("Del", 1f) { backspace() })
            addView(mic)
            addView(key("Space", 1f) { currentInputConnection?.commitText(" ", 1) })
            addView(key("Enter", 1f) { enter() })
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#202124"))
            addView(status)
            addView(row)
        }
    }

    private fun toggleListening() {
        if (busy) return
        val c = capture
        if (c != null && c.isRunning) { c.stop(); setStatus("Writing...", busy = true); return }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setStatus("Microphone permission needed - opening Rambler...")
            startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        capture?.destroy()
        capture = SpeechCapture(
            this,
            onPartial = { /* The raw transcript is intentionally not shown; the finished thought is written at the end. */ },
            onFinished = { raw -> main.post { onTranscript(raw) } },
            onError = { msg -> main.post { setStatus(msg); mic.text = "Talk" } },
        ).also { it.start() }
        mic.text = "Done"
        setStatus("Listening...")
    }

    private fun onTranscript(raw: String) {
        mic.text = "Talk"
        if (raw.isBlank()) { setStatus("Didn't catch anything - tap the mic and try again"); return }
        setStatus("Writing...", busy = true)
        val cleaner = LlmCleaner.get(this)
        worker.execute {
            val result = cleaner.process(raw)
            main.post {
                currentInputConnection?.commitText(result.text, 1)
                setStatus(
                    result.note
                        ?: if (cleaner.isModelReady) "Inserted" else "Inserted (basic cleanup - download the model in the Rambler app for smart cleanup)"
                )
                busy = false
            }
        }
    }

    private fun setStatus(text: String, busy: Boolean = false) {
        this.busy = busy
        status.text = text
    }

    private fun backspace() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (selected.isNullOrEmpty()) ic.deleteSurroundingText(1, 0) else ic.commitText("", 1)
    }

    private fun enter() {
        val ic = currentInputConnection ?: return
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val multiLine = currentInputEditorInfo?.inputType?.and(android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if (!multiLine && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun switchKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        capture?.destroy(); capture = null
        busy = false
    }

    override fun onDestroy() {
        capture?.destroy()
        worker.shutdown()
        super.onDestroy()
    }
}

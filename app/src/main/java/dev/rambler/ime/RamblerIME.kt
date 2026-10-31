package dev.rambler.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A Gboard-style keyboard with Rambler voice typing: tap the mic, say your whole thought, tap the
 * check (or just stop talking). The transcript is turned into one clean message by an on-device
 * LLM and typed into the field, with an Undo chip to take it back.
 */
class RamblerIME : InputMethodService() {

    private enum class Layer { LETTERS, SYMBOLS, MORE_SYMBOLS }
    private enum class Shift { OFF, ONCE, LOCK }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var pal: Palette
    private lateinit var strip: VoiceStrip
    private lateinit var keys: LinearLayout
    private var capture: SpeechCapture? = null
    private var busy = false

    private var layer = Layer.LETTERS
    private var shift = Shift.OFF
    private var lastShiftTap = 0L
    private var lastInserted: String? = null
    private val letterKeys = mutableListOf<Pair<KeyView, String>>() // key + lowercase base label
    private var shiftKey: KeyView? = null

    private val dp get() = resources.displayMetrics.density

    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View {
        pal = Palette.forContext(this)
        strip = VoiceStrip(this, pal).apply {
            onSwitch = { switchKeyboard() }
            onMic = { startListening() }
            onUndo = { undo() }
            onCancel = { cancelListening() }
            onDone = { finishListening() }
        }
        keys = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((3 * dp).toInt(), 0, (3 * dp).toInt(), (6 * dp).toInt())
        }
        buildKeys()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.background)
            addView(strip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(keys, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        cancelListening()
        lastInserted = null
        strip.showIdle(false)
        val cls = (info?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
        layer = if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) Layer.SYMBOLS else Layer.LETTERS
        shift = Shift.OFF
        buildKeys()
        updateAutoCap()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        cancelListening()
    }

    // ---------------------------------------------------------------- keyboard layout

    private fun language() = resources.configuration.locales[0].language.lowercase(Locale.ROOT)

    private fun letterRows(): List<String> = when (language()) {
        "de" -> listOf("qwertzuiop", "asdfghjkl", "yxcvbnm")
        "fr" -> listOf("azertyuiop", "qsdfghjklm", "wxcvbn")
        else -> listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    }

    private fun accents(c: Char): String = when (language()) {
        "de" -> when (c) {
            'a' -> "äàáâãåā"; 'o' -> "öòóôõø"; 'u' -> "üùúû"; 's' -> "ßś"
            'e' -> "éèêë"; 'i' -> "íìîï"; 'n' -> "ñ"; 'c' -> "ç"; 'y' -> "ý"; else -> ""
        }
        else -> when (c) {
            'a' -> "àáâäãåā"; 'e' -> "éèêëē"; 'i' -> "íìîïī"; 'o' -> "óòôöõø"; 'u' -> "úùûüū"
            's' -> "ßś"; 'n' -> "ñ"; 'c' -> "ç"; 'y' -> "ý"; else -> ""
        }
    }

    private fun buildKeys() {
        if (!::keys.isInitialized) return
        keys.removeAllViews()
        letterKeys.clear(); shiftKey = null

        keys.addView(row("1234567890".map { char(it.toString()) }))
        when (layer) {
            Layer.LETTERS -> {
                val r = letterRows()
                keys.addView(row(r[0].map { letter(it) }))
                keys.addView(row(r[1].map { letter(it) }, sidePad = if (r[1].length == 9) 0.5f else 0f))
                val shiftK = KeyView(this, pal, KeyStyle.FUNCTION, iconRes = R.drawable.ic_shift) { tapShift() }
                shiftKey = shiftK
                keys.addView(row(listOf(weighted(shiftK, 1.5f)) + r[2].map { letter(it) } + listOf(backspace(1.5f))))
            }
            Layer.SYMBOLS -> {
                keys.addView(row("@#\$_&-+()/".map { char(it.toString()) }))
                keys.addView(row("*\"':;!?".map { char(it.toString()) }, sidePad = 0.5f))
                keys.addView(row(listOf(layerKey(getString(R.string.more_symbols_key), Layer.MORE_SYMBOLS, 1.5f)) +
                    "~`|^%".map { char(it.toString()) } + listOf(backspace(1.5f))))
            }
            Layer.MORE_SYMBOLS -> {
                keys.addView(row("~`|•√π÷×¶∆".map { char(it.toString()) }))
                keys.addView(row("£¢€¥^°={}\\".map { char(it.toString()) }))
                keys.addView(row(listOf(layerKey(getString(R.string.symbols_key), Layer.SYMBOLS, 1.5f)) +
                    "%©®™[]<>".map { char(it.toString()) } + listOf(backspace(1.5f))))
            }
        }
        val toggle = if (layer == Layer.LETTERS) layerKey(getString(R.string.symbols_key), Layer.SYMBOLS, 1.5f)
        else layerKey(getString(R.string.letters_key), Layer.LETTERS, 1.5f)
        keys.addView(row(listOf(
            toggle,
            char(",", 1f, KeyStyle.FUNCTION),
            weighted(KeyView(this, pal, KeyStyle.LETTER, label = " ") { commit(" ") }, 5f),
            char(".", 1f, KeyStyle.FUNCTION),
            weighted(KeyView(this, pal, KeyStyle.ACCENT, iconRes = R.drawable.ic_enter) { enter() }, 1.5f),
        )))
        refreshShift()
    }

    private fun row(views: List<View>, sidePad: Float = 0f): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val h = (50 * dp).toInt()
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
        if (sidePad > 0f) addView(View(context), LinearLayout.LayoutParams(0, h, sidePad))
        views.forEach { v ->
            val lp = (v.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(0, h, 1f)
            lp.height = h; if (lp.width != 0) lp.width = 0
            addView(v, lp)
        }
        if (sidePad > 0f) addView(View(context), LinearLayout.LayoutParams(0, h, sidePad))
    }

    private fun weighted(v: KeyView, w: Float) = v.apply { layoutParams = LinearLayout.LayoutParams(0, 0, w) }

    private fun char(s: String, weight: Float = 1f, style: KeyStyle = KeyStyle.LETTER) =
        KeyView(this, pal, style, label = s) { commit(it ?: s) }.apply { layoutParams = LinearLayout.LayoutParams(0, 0, weight) }

    private fun letter(c: Char): KeyView {
        val k = KeyView(this, pal, KeyStyle.LETTER, label = c.toString(), alternates = accents(c)) { commit(it ?: c.toString()) }
        k.layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        letterKeys += k to c.toString()
        return k
    }

    private fun backspace(weight: Float) =
        KeyView(this, pal, KeyStyle.FUNCTION, iconRes = R.drawable.ic_backspace, repeat = true) { backspace() }
            .apply { layoutParams = LinearLayout.LayoutParams(0, 0, weight) }

    private fun layerKey(label: String, to: Layer, weight: Float) =
        KeyView(this, pal, KeyStyle.FUNCTION, label = label) { layer = to; buildKeys() }
            .apply { layoutParams = LinearLayout.LayoutParams(0, 0, weight) }

    // ---------------------------------------------------------------- typing

    private fun commit(text: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
        clearUndo()
        updateAutoCap()
    }

    private fun backspace() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (selected.isNullOrEmpty()) ic.deleteSurroundingText(1, 0) else ic.commitText("", 1)
        clearUndo()
        updateAutoCap()
    }

    private fun enter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val multiLine = ((info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if (!multiLine && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
        clearUndo()
        updateAutoCap()
    }

    private fun clearUndo() {
        if (lastInserted != null) { lastInserted = null; if (!busy && capture?.isRunning != true) strip.showIdle(false) }
    }

    // ---------------------------------------------------------------- shift / auto-capitalise

    private fun tapShift() {
        val now = System.currentTimeMillis()
        shift = when {
            shift == Shift.LOCK -> Shift.OFF
            now - lastShiftTap < 350 -> Shift.LOCK
            shift == Shift.ONCE -> Shift.OFF
            else -> Shift.ONCE
        }
        lastShiftTap = now
        refreshShift()
    }

    private fun updateAutoCap() {
        if (shift == Shift.LOCK || layer != Layer.LETTERS) return
        val info = currentInputEditorInfo ?: return
        val caps = currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0
        val want = if (caps != 0) Shift.ONCE else Shift.OFF
        if (want != shift) { shift = want; refreshShift() }
    }

    private fun refreshShift() {
        for ((k, base) in letterKeys) {
            val up = shift != Shift.OFF
            k.label = if (up) base.uppercase(Locale.ROOT) else base
            k.alternates = if (up) k.alternates.uppercase(Locale.ROOT) else k.alternates.lowercase(Locale.ROOT)
            k.invalidate()
        }
        shiftKey?.let { it.active = shift != Shift.OFF; it.iconOverride = if (shift == Shift.LOCK) R.drawable.ic_shift_lock else 0 }
    }

    // ---------------------------------------------------------------- voice typing

    private fun startListening() {
        if (busy || capture?.isRunning == true) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            strip.showMessage(getString(R.string.err_no_mic))
            startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        capture?.destroy()
        capture = SpeechCapture(
            this,
            // The raw transcript is intentionally not shown: the finished thought is written at the end.
            onPartial = {},
            onFinished = { raw -> main.post { onTranscript(raw) } },
            onError = { msg -> main.post { strip.showMessage(msg) } },
        ).also { it.start() }
        lastInserted = null
        strip.showListening()
    }

    private fun cancelListening() {
        capture?.destroy(); capture = null
        if (::strip.isInitialized && !busy) strip.showIdle(lastInserted != null)
    }

    private fun finishListening() {
        val c = capture ?: return
        strip.showWriting(); busy = true
        c.stop()
    }

    private fun onTranscript(raw: String) {
        capture = null
        if (raw.isBlank()) { busy = false; strip.showMessage(getString(R.string.err_nothing)); return }
        busy = true
        strip.showWriting()
        val cleaner = LlmCleaner.get(this)
        worker.execute {
            val result = cleaner.process(raw)
            main.post {
                busy = false
                val ic = currentInputConnection
                if (ic == null || result.text.isEmpty()) { strip.showIdle(false); return@post }
                ic.commitText(result.text, 1)
                lastInserted = result.text
                updateAutoCap()
                strip.showIdle(true)
                result.note?.let { strip.showMessage(it, 5000) }
            }
        }
    }

    private fun undo() {
        val text = lastInserted ?: return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(text.length, 0)?.toString()
        if (before == text) ic.deleteSurroundingText(text.length, 0)
        lastInserted = null
        strip.showIdle(false)
        updateAutoCap()
    }

    private fun switchKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    override fun onDestroy() {
        capture?.destroy()
        worker.shutdown()
        super.onDestroy()
    }
}

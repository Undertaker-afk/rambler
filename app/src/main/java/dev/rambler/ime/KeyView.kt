package dev.rambler.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.PopupWindow
import androidx.core.content.ContextCompat

enum class KeyStyle { LETTER, FUNCTION, ACCENT }

/**
 * One keyboard key, drawn Gboard-style: rounded tonal surface with a centred label or vector icon.
 * Fires [onPress] on touch-down (fast typing), optionally repeats while held, and shows an
 * accent popup (a -> ä à á ...) on long press.
 */
@SuppressLint("ViewConstructor")
class KeyView(
    context: Context,
    private val pal: Palette,
    private val style: KeyStyle,
    var label: String? = null,
    private val iconRes: Int = 0,
    private val repeat: Boolean = false,
    var alternates: String = "",
    private val onPress: (String?) -> Unit,
) : View(context) {

    /** Highlighted (e.g. caps lock on). */
    var active = false
        set(v) { field = v; invalidate() }
    var iconOverride: Int = 0
        set(v) { field = v; icon = if (v != 0) ContextCompat.getDrawable(context, v)?.mutate() else base; invalidate() }

    private val dp = resources.displayMetrics.density
    private val base: Drawable? = if (iconRes != 0) ContextCompat.getDrawable(context, iconRes)?.mutate() else null
    private var icon: Drawable? = base
    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val handler = Handler(Looper.getMainLooper())
    private var down = false
    private var popup: PopupWindow? = null
    private var popupView: AccentPopupView? = null
    private var consumedByPopup = false

    private val longPress = Runnable {
        if (alternates.isNotEmpty() && label != null) showPopup()
    }
    private val repeater = object : Runnable {
        override fun run() { onPress(label); handler.postDelayed(this, 45) }
    }

    override fun onDraw(canvas: Canvas) {
        val inset = 2.5f * dp
        rect.set(inset, inset, width - inset, height - inset)
        val radius = if (style == KeyStyle.ACCENT) rect.height() / 2f else 12 * dp
        fill.color = when {
            style == KeyStyle.ACCENT -> pal.primary
            active -> pal.primary
            style == KeyStyle.FUNCTION -> pal.functionKey
            else -> pal.key
        }
        canvas.drawRoundRect(rect, radius, radius, fill)
        if (down) {
            fill.color = pal.pressed
            canvas.drawRoundRect(rect, radius, radius, fill)
        }

        val fg = if (style == KeyStyle.ACCENT || active) pal.onPrimary else pal.text
        icon?.let {
            val s = (24 * dp).toInt()
            val l = (width - s) / 2; val t = (height - s) / 2
            it.setBounds(l, t, l + s, t + s)
            it.setTint(fg)
            it.draw(canvas)
        }
        label?.let {
            textPaint.color = fg
            textPaint.textSize = (if (it.length > 2) 15f else 22f) * dp
            val y = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(it, width / 2f, y, textPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                down = true; consumedByPopup = false; invalidate()
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (alternates.isNotEmpty()) {
                    handler.postDelayed(longPress, 350)
                } else {
                    onPress(label)
                    if (repeat) handler.postDelayed(repeater, 400)
                }
            }
            MotionEvent.ACTION_MOVE -> popupView?.let { pv ->
                val loc = IntArray(2); getLocationOnScreen(loc)
                pv.select(loc[0] + e.x)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress); handler.removeCallbacks(repeater)
                if (e.actionMasked == MotionEvent.ACTION_UP) {
                    val pv = popupView
                    if (pv != null) { pv.selectedText()?.let { onPress(it) }; consumedByPopup = true }
                    else if (alternates.isNotEmpty()) onPress(label)  // short tap on a key with alternates
                }
                dismissPopup()
                down = false; invalidate()
            }
        }
        return true
    }

    private fun showPopup() {
        val chars = alternates.map { it.toString() }
        val pv = AccentPopupView(context, pal, chars)
        val w = pv.preferredWidth(); val h = (52 * dp).toInt()
        val loc = IntArray(2); getLocationOnScreen(loc)
        val x = (loc[0] + width / 2 - w / 2).coerceAtLeast(0)
        popup = PopupWindow(pv, w, h).apply { isClippingEnabled = false; elevation = 8 * dp }
        popup?.showAtLocation(this, Gravity.NO_GRAVITY, x, loc[1] - h - (4 * dp).toInt())
        popupView = pv
        pv.select(loc[0] + width / 2f)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun dismissPopup() { popup?.dismiss(); popup = null; popupView = null }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null); dismissPopup(); super.onDetachedFromWindow()
    }
}

/** The bubble of accent alternatives shown above a long-pressed key. */
class AccentPopupView(context: Context, private val pal: Palette, private val items: List<String>) : View(context) {
    private val dp = resources.displayMetrics.density
    private val cell = 40 * dp
    private var selected = 0
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 22 * dp }
    private val r = RectF()
    private var screenLeft = 0f

    fun preferredWidth() = (items.size * cell + 12 * dp).toInt()

    fun select(screenX: Float) {
        if (screenLeft == 0f) { val l = IntArray(2); getLocationOnScreen(l); screenLeft = l[0].toFloat() }
        val idx = ((screenX - screenLeft - 6 * dp) / cell).toInt().coerceIn(0, items.size - 1)
        if (idx != selected) { selected = idx }
        invalidate()
    }

    fun selectedText(): String? = items.getOrNull(selected)

    override fun onDraw(canvas: Canvas) {
        bg.color = pal.popup
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(r, 16 * dp, 16 * dp, bg)
        for ((i, s) in items.withIndex()) {
            val left = 6 * dp + i * cell
            if (i == selected) {
                sel.color = pal.primary
                r.set(left + 2 * dp, 6 * dp, left + cell - 2 * dp, height - 6 * dp)
                canvas.drawRoundRect(r, 12 * dp, 12 * dp, sel)
            }
            tp.color = if (i == selected) pal.onPrimary else pal.text
            canvas.drawText(s, left + cell / 2, height / 2f - (tp.descent() + tp.ascent()) / 2f, tp)
        }
    }
}

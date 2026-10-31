package dev.rambler.ime

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat

/**
 * The strip above the keys. Three looks, like Gboard on Pixel:
 *  - idle: keyboard-switch icon, an "Undo" chip (after a dictation) or a short message, mic icon
 *  - listening: animated blue bar with a cancel (X) circle and a confirm (check) circle
 *  - writing: the same bar with a short status text while the model works
 */
class VoiceStrip(context: Context, private val pal: Palette) : View(context) {

    enum class State { IDLE, LISTENING, WRITING }

    var onSwitch: () -> Unit = {}
    var onMic: () -> Unit = {}
    var onUndo: () -> Unit = {}
    var onCancel: () -> Unit = {}
    var onDone: () -> Unit = {}

    private var state = State.IDLE
    private var undoVisible = false
    private var message: String? = null

    private val dp = resources.displayMetrics.density
    private fun icon(res: Int): Drawable = ContextCompat.getDrawable(context, res)!!.mutate()
    private val icSwitch = icon(R.drawable.ic_language)
    private val icMic = icon(R.drawable.ic_mic)
    private val icUndo = icon(R.drawable.ic_undo)
    private val icClose = icon(R.drawable.ic_close)
    private val icCheck = icon(R.drawable.ic_check)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 15 * dp
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val matrix = Matrix()
    private var shader: LinearGradient? = null
    private var phase = 0f
    private val undoText get() = context.getString(R.string.undo)
    private val clearMessage = Runnable { message = null; invalidate() }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2200
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    fun showIdle(undoAvailable: Boolean) {
        state = State.IDLE; undoVisible = undoAvailable; message = null
        animator.cancel(); invalidate()
    }

    fun showListening() { state = State.LISTENING; message = null; startAnim(); contentDescription = context.getString(R.string.listening) }
    fun showWriting() { state = State.WRITING; message = null; startAnim() }

    /** Shows [msg] in the idle strip for a few seconds. */
    fun showMessage(msg: String, ms: Long = 4000) {
        state = State.IDLE; message = msg; animator.cancel(); invalidate()
        removeCallbacks(clearMessage); postDelayed(clearMessage, ms)
    }

    private fun startAnim() { if (!animator.isRunning) animator.start(); invalidate() }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (48 * dp).toInt())
    }

    private fun leftBtn() = RectF(8 * dp, 6 * dp, 8 * dp + 36 * dp, 42 * dp)
    private fun rightBtn() = RectF(width - 8 * dp - 36 * dp, 6 * dp, width - 8 * dp, 42 * dp)

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat(); val w = width.toFloat()
        when (state) {
            State.IDLE -> drawIdle(canvas, w, h)
            State.LISTENING, State.WRITING -> drawBar(canvas, w, h)
        }
    }

    private fun drawIdle(c: Canvas, w: Float, h: Float) {
        drawIcon(c, icSwitch, leftBtn(), pal.text)
        drawIcon(c, icMic, rightBtn(), pal.text)
        val msg = message
        if (msg != null) {
            text.color = pal.text
            c.drawText(msg, w / 2, h / 2 - (text.descent() + text.ascent()) / 2, text)
        } else if (undoVisible) {
            val label = undoText
            text.color = pal.text
            val tw = text.measureText(label)
            val chipW = tw + 24 * dp + 28 * dp
            val left = (w - chipW) / 2
            rect.set(left, 7 * dp, left + chipW, h - 7 * dp)
            paint.shader = null; paint.color = pal.chip
            c.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, paint)
            val ic = RectF(rect.left + 10 * dp, rect.centerY() - 9 * dp, rect.left + 28 * dp, rect.centerY() + 9 * dp)
            icUndo.setBounds(ic.left.toInt(), ic.top.toInt(), ic.right.toInt(), ic.bottom.toInt())
            icUndo.setTint(pal.text); icUndo.draw(c)
            c.drawText(label, rect.left + 32 * dp + tw / 2, h / 2 - (text.descent() + text.ascent()) / 2, text)
        }
    }

    private fun drawBar(c: Canvas, w: Float, h: Float) {
        rect.set(8 * dp, 4 * dp, w - 8 * dp, h - 4 * dp)
        val span = rect.width()
        if (shader == null || lastSpan != span) {
            shader = LinearGradient(0f, 0f, span, 0f, pal.barGradient, null, Shader.TileMode.REPEAT)
            lastSpan = span
        }
        matrix.setTranslate(rect.left + phase * span, 0f)
        shader!!.setLocalMatrix(matrix)
        paint.shader = shader
        c.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, paint)
        paint.shader = null

        if (state == State.LISTENING) {
            val l = leftBtn(); val r = rightBtn()
            paint.color = pal.barButton
            c.drawCircle(l.centerX(), l.centerY(), 17 * dp, paint)
            drawIcon(c, icClose, l, pal.text)
            paint.color = pal.primary
            c.drawCircle(r.centerX(), r.centerY(), 17 * dp, paint)
            drawIcon(c, icCheck, r, pal.onPrimary)
        } else {
            text.color = pal.text
            c.drawText(context.getString(R.string.writing), w / 2, h / 2 - (text.descent() + text.ascent()) / 2, text)
        }
    }

    private var lastSpan = 0f

    private fun drawIcon(c: Canvas, d: Drawable, area: RectF, tint: Int) {
        val s = (22 * dp).toInt()
        val l = (area.centerX() - s / 2).toInt(); val t = (area.centerY() - s / 2).toInt()
        d.setBounds(l, t, l + s, t + s); d.setTint(tint); d.draw(c)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_UP) return e.actionMasked == MotionEvent.ACTION_DOWN
        val x = e.x; val y = e.y
        when (state) {
            State.IDLE -> when {
                leftBtn().expand().contains(x, y) -> onSwitch()
                rightBtn().expand().contains(x, y) -> onMic()
                undoVisible && message == null && x > width * 0.25f && x < width * 0.75f -> onUndo()
            }
            State.LISTENING -> when {
                leftBtn().expand().contains(x, y) -> onCancel()
                rightBtn().expand().contains(x, y) -> onDone()
            }
            State.WRITING -> Unit
        }
        return true
    }

    private fun RectF.expand() = RectF(left - 6 * dp, 0f, right + 6 * dp, height.toFloat())

    override fun onDetachedFromWindow() { animator.cancel(); removeCallbacks(clearMessage); super.onDetachedFromWindow() }
}

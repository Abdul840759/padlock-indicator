package com.padlock.indicator

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Pill with a padlock in the middle.
 * IDLE: pill grows, face scan plays on the left, fingerprint plays on the right, pill shrinks away.
 * UNLOCK_BIO: pill returns with a green fingerprint pulse while the lock opens.
 * UNLOCK_PIN: lock just opens.
 */
class LockPillView(context: Context) : View(context) {

    enum class Mode { LOCKED, IDLE, UNLOCK_BIO, UNLOCK_PIN }

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val green = 0xFF34C759.toInt()
    private val argb = ArgbEvaluator()
    private val rect = RectF()

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        setShadowLayer(2f * d, 0f, 0.5f * d, 0x55000000)
    }
    private val shacklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        strokeCap = Paint.Cap.ROUND
        setShadowLayer(2f * d, 0f, 0.5f * d, 0x55000000)
    }

    private val shackle = Path().apply {
        moveTo(5f * d, 16f * d)
        lineTo(5f * d, 12f * d)
        arcTo(RectF(5f * d, 8f * d, 13f * d, 16f * d), 180f, 180f)
        lineTo(13f * d, 16f * d)
    }
    private val body = RectF(2f * d, 16f * d, 16f * d, 28f * d)

    // radius dp, start angle, sweep
    private val arcs = arrayOf(
        floatArrayOf(2.2f, 215f, 250f),
        floatArrayOf(4.8f, 200f, 270f),
        floatArrayOf(7.4f, 195f, 275f),
        floatArrayOf(10f, 205f, 250f)
    )

    private var mode = Mode.LOCKED
    private var t = 0f
    private var anim: ValueAnimator? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(130f).toInt(), dp(36f).toInt())
    }

    fun stop() {
        anim?.cancel()
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    fun play(newMode: Mode, onEnd: (() -> Unit)? = null) {
        anim?.cancel()
        mode = newMode
        t = 0f
        invalidate()

        val dur = when (newMode) {
            Mode.IDLE -> 2800f
            Mode.UNLOCK_BIO -> 1000f
            Mode.UNLOCK_PIN -> 520f
            Mode.LOCKED -> 0f
        }
        if (dur == 0f) return

        anim = ValueAnimator.ofFloat(0f, dur).apply {
            duration = dur.toLong()
            interpolator = LinearInterpolator()
            addUpdateListener {
                t = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(a: Animator) {
                    if (!cancelled) onEnd?.invoke()
                }
            })
            start()
        }
    }

    private fun seg(a: Float, b: Float): Float = ((t - a) / (b - a)).coerceIn(0f, 1f)

    private fun ease(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    private fun backOut(x: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val y = x - 1f
        return 1f + c3 * y * y * y + c1 * y * y
    }

    override fun onDraw(canvas: Canvas) {
        var pillFrac = 0f
        var glyphA = 0f
        var faceP = 0f
        var fpP = 0f
        var success = 0f
        var open = 0f

        when (mode) {
            Mode.LOCKED -> {}
            Mode.IDLE -> {
                pillFrac = ease(seg(0f, 350f)) * (1f - ease(seg(2300f, 2800f)))
                glyphA = ease(seg(250f, 450f)) * (1f - ease(seg(2200f, 2500f)))
                faceP = seg(350f, 1150f)
                fpP = seg(1150f, 1950f)
            }
            Mode.UNLOCK_BIO -> {
                pillFrac = ease(seg(0f, 250f)) * (1f - ease(seg(750f, 1000f)))
                glyphA = ease(seg(100f, 300f)) * (1f - ease(seg(700f, 900f)))
                fpP = seg(0f, 250f)
                success = seg(250f, 650f)
                open = backOut(seg(300f, 700f))
            }
            Mode.UNLOCK_PIN -> {
                open = backOut(seg(0f, 450f))
            }
        }

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val pw = dp(34f) + (dp(116f) - dp(34f)) * pillFrac

        if (pillFrac > 0f) {
            pillPaint.alpha = (230 * min(1f, pillFrac * 3f)).toInt()
            val ph = dp(34f)
            canvas.drawRoundRect(cx - pw / 2f, cy - ph / 2f, cx + pw / 2f, cy + ph / 2f, ph / 2f, ph / 2f, pillPaint)
        }

        if (glyphA > 0f) {
            val left = cx - pw / 2f
            val right = cx + pw / 2f
            if (mode == Mode.IDLE) drawFace(canvas, left + dp(20f), cy, faceP, glyphA)
            drawFingerprint(canvas, right - dp(20f), cy, fpP, success, glyphA)
        }

        // padlock, always centered and drawn on top
        canvas.save()
        canvas.translate(cx, cy)
        canvas.translate(-9f * d, -18f * d)
        canvas.save()
        canvas.translate(0f, -2f * d * open)
        canvas.rotate(28f * open, 13f * d, 16f * d)
        canvas.drawPath(shackle, shacklePaint)
        canvas.restore()
        canvas.drawRoundRect(body, 3f * d, 3f * d, bodyPaint)
        canvas.restore()
    }

    private fun drawFace(c: Canvas, cx: Float, cy: Float, p: Float, a: Float) {
        glyph.color = Color.WHITE
        glyph.alpha = (255 * a).toInt()

        val settle = ease(p / 0.4f)
        val r = dp(8.5f) * (1f + 0.3f * (1f - settle))
        val arm = dp(4f)
        for (sx in intArrayOf(-1, 1)) {
            for (sy in intArrayOf(-1, 1)) {
                val x = cx + sx * r
                val y = cy + sy * r
                c.drawLine(x, y, x - sx * arm, y, glyph)
                c.drawLine(x, y, x, y - sy * arm, glyph)
            }
        }

        val f = ease((p - 0.35f) / 0.4f)
        if (f > 0f) {
            glyph.alpha = (255 * a * f).toInt()
            c.drawLine(cx - dp(3.2f), cy - dp(3.2f), cx - dp(3.2f), cy - dp(1f), glyph)
            c.drawLine(cx + dp(3.2f), cy - dp(3.2f), cx + dp(3.2f), cy - dp(1f), glyph)
            c.drawLine(cx, cy - dp(2.5f), cx, cy + dp(1.2f), glyph)
            c.drawLine(cx, cy + dp(1.2f), cx - dp(1.2f), cy + dp(1.2f), glyph)
            rect.set(cx - dp(3.6f), cy - dp(1.2f), cx + dp(3.6f), cy + dp(4.2f))
            c.drawArc(rect, 25f, 130f, false, glyph)
        }

        val q = (p - 0.45f) / 0.5f
        if (q > 0f && q < 1f) {
            val y = cy - r + 2f * r * ease(q)
            glyph.alpha = (140 * a * sin(PI * q).toFloat()).toInt()
            c.drawLine(cx - r + dp(1f), y, cx + r - dp(1f), y, glyph)
        }
    }

    private fun drawFingerprint(c: Canvas, cx: Float, cy: Float, p: Float, success: Float, a: Float) {
        glyph.color = argb.evaluate(success, Color.WHITE, green) as Int
        for ((i, arc) in arcs.withIndex()) {
            val prog = ease(p * arcs.size - i)
            if (prog <= 0f) continue
            glyph.alpha = (255 * a).toInt()
            val r = dp(arc[0])
            rect.set(cx - r, cy - r, cx + r, cy + r)
            c.drawArc(rect, arc[1], arc[2] * prog, false, glyph)
        }
        if (success > 0f && success < 1f) {
            ringPaint.color = green
            ringPaint.alpha = (200 * (1f - success) * a).toInt()
            c.drawCircle(cx, cy, dp(10f) + dp(7f) * success, ringPaint)
        }
    }
}

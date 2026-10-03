package com.padlock.indicator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * Small iPhone-style padlock. openProgress: 0 = locked, 1 = open.
 * Everything is drawn in dp on an 18 x 30 dp canvas.
 */
class LockView(context: Context) : View(context) {

    private val d = resources.displayMetrics.density

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

    var openProgress: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((18 * d).toInt(), (30 * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val p = openProgress

        // Shackle lifts up and swings open, pivoting on its right leg
        canvas.save()
        canvas.translate(0f, -2f * d * p)
        canvas.rotate(28f * p, 13f * d, 16f * d)
        canvas.drawPath(shackle, shacklePaint)
        canvas.restore()

        // Body is drawn last so it hides the bottom of the shackle legs
        canvas.drawRoundRect(body, 3f * d, 3f * d, bodyPaint)
    }
}

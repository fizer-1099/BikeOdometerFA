package ir.balasari.bikeodometer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.max

/** نمودار میله‌ای ساده؛ اندیس ۰ سمت چپ رسم می‌شود. */
class BarChartView(context: Context) : View(context) {
    var values: DoubleArray = DoubleArray(0)
    var labels: Array<String> = emptyArray()
    var valueLabels: Array<String> = emptyArray()

    private val density = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        barPaint.color = ContextCompat.getColor(context, R.color.accent)
        trackPaint.color = ContextCompat.getColor(context, R.color.gauge_track)
        textPaint.color = ContextCompat.getColor(context, R.color.text_secondary)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 11f * density
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(0, widthMeasureSpec), (150 * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val n = values.size
        if (n == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val top = 18f * density
        val bottom = h - 20f * density
        val slot = w / n
        val bw = slot * 0.55f
        val maxV = max(values.maxOrNull() ?: 0.0, 0.0001)
        val r = 6f * density
        for (i in 0 until n) {
            val cx = slot * i + slot / 2f
            rect.set(cx - bw / 2f, top, cx + bw / 2f, bottom)
            canvas.drawRoundRect(rect, r, r, trackPaint)
            val frac = (values[i] / maxV).toFloat()
            val barTop = bottom - (bottom - top) * frac
            if (frac > 0f) {
                rect.set(cx - bw / 2f, barTop, cx + bw / 2f, bottom)
                canvas.drawRoundRect(rect, r, r, barPaint)
            }
            if (i < labels.size) canvas.drawText(labels[i], cx, h - 4f * density, textPaint)
            if (values[i] > 0.0 && i < valueLabels.size) {
                canvas.drawText(valueLabels[i], cx, barTop - 4f * density, textPaint)
            }
        }
    }
}

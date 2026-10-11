package ir.balasari.bikeodometer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** سرعت‌سنج کمانی با مقیاس خودکار (۶۰ / ۱۲۰ / ۱۸۰ / ۲۴۰) و نشانگر سقف مجاز. */
class GaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** سقف مجاز؛ صفر یعنی هشدار خاموش است. */
    var limit = 0f
        set(value) { field = value; invalidate() }

    private var shown = 0f
    private var target = 0f
    private var animator: ValueAnimator? = null
    private val rect = RectF()

    private val blue = Color.rgb(2, 136, 209)
    private val red = Color.rgb(211, 47, 47)

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(222, 228, 236)
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = red
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.rgb(90, 100, 115)
    }
    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.rgb(16, 24, 40)
        isFakeBoldText = true
    }
    private val unit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = blue
    }

    fun setSpeed(value: Float) {
        if (value == target) return
        target = value
        animator?.cancel()
        val a = ValueAnimator.ofFloat(shown, value)
        a.duration = 500
        a.addUpdateListener {
            shown = it.animatedValue as Float
            invalidate()
        }
        animator = a
        a.start()
    }

    private fun scaleMax(): Float {
        val v = max(max(shown, target), limit)
        for (s in floatArrayOf(60f, 120f, 180f, 240f)) {
            if (v <= s * 0.95f) return s
        }
        return 240f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val size = min(w, h)
        if (size <= 0f) return

        val maxV = scaleMax()
        val stroke = size * 0.075f
        val radius = size / 2f - stroke
        val cx = w / 2f
        val cy = h / 2f
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        track.strokeWidth = stroke
        canvas.drawArc(rect, 135f, 270f, false, track)

        val frac = (shown / maxV).coerceIn(0f, 1f)
        val over = limit > 0f && shown > limit
        bar.strokeWidth = stroke
        bar.color = if (over) red else blue
        if (frac > 0.005f) canvas.drawArc(rect, 135f, 270f * frac, false, bar)

        // عددهای روی کمان
        label.textSize = size * 0.055f
        val steps = 6
        val inner = radius - stroke * 1.4f
        for (i in 0..steps) {
            val ang = Math.toRadians(135.0 + 270.0 * i / steps)
            val x = cx + (inner * cos(ang)).toFloat()
            val y = cy + (inner * sin(ang)).toFloat() + label.textSize / 3f
            canvas.drawText((maxV * i / steps).toInt().toString(), x, y, label)
        }

        // خط قرمز سقف مجاز
        if (limit > 0f && limit <= maxV) {
            val ang = Math.toRadians(135.0 + 270.0 * (limit / maxV))
            mark.strokeWidth = size * 0.012f
            val r1 = radius - stroke * 0.8f
            val r2 = radius + stroke * 0.8f
            canvas.drawLine(
                cx + (r1 * cos(ang)).toFloat(), cy + (r1 * sin(ang)).toFloat(),
                cx + (r2 * cos(ang)).toFloat(), cy + (r2 * sin(ang)).toFloat(),
                mark
            )
        }

        number.textSize = size * 0.26f
        canvas.drawText(shown.toInt().toString(), cx, cy + size * 0.07f, number)
        unit.textSize = size * 0.06f
        canvas.drawText("km/h", cx, cy + size * 0.17f, unit)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}

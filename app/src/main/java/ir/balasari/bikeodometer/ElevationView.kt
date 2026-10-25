package ir.balasari.bikeodometer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import androidx.core.content.ContextCompat
import java.util.Locale

/** نمودار ارتفاع بر حسب فاصله. */
class ElevationView(context: Context) : View(context) {
    var dist: DoubleArray = DoubleArray(0)
    var ele: DoubleArray = DoubleArray(0)

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        val accent = ContextCompat.getColor(context, R.color.accent)
        fill.color = accent
        fill.alpha = 70
        line.color = accent
        line.style = Paint.Style.STROKE
        line.strokeWidth = 2.5f * d
        text.color = ContextCompat.getColor(context, R.color.text_secondary)
        text.textSize = 11f * d
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(0, widthMeasureSpec), (170 * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val n = ele.size
        if (n < 2) return
        val w = width.toFloat()
        val h = height.toFloat()
        val left = 8f * d
        val right = w - 8f * d
        val top = 18f * d
        val bottom = h - 22f * d
        val minE = ele.minOrNull() ?: 0.0
        val maxE = ele.maxOrNull() ?: 1.0
        val span = Math.max(maxE - minE, 10.0)
        val total = Math.max(dist[n - 1], 1.0)

        val path = Path()
        val area = Path()
        for (i in 0 until n) {
            val x = left + (right - left) * (dist[i] / total).toFloat()
            val y = bottom - (bottom - top) * ((ele[i] - minE) / span).toFloat()
            if (i == 0) {
                path.moveTo(x, y)
                area.moveTo(x, bottom)
                area.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                area.lineTo(x, y)
            }
        }
        area.lineTo(right, bottom)
        area.close()
        canvas.drawPath(area, fill)
        canvas.drawPath(path, line)

        text.textAlign = Paint.Align.LEFT
        canvas.drawText(String.format(Locale.US, "%d m", Math.round(maxE)), left, top - 4f * d, text)
        canvas.drawText(String.format(Locale.US, "%d m", Math.round(minE)), left, h - 6f * d, text)
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText(String.format(Locale.US, "%.1f km", total / 1000.0), right, h - 6f * d, text)
    }
}

package ir.balasari.bikeodometer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** صفحه‌ی قطب‌نما: صفحه‌ی مدرج با جهت‌های اصلی که برعکس جهت گوشی می‌چرخد. */
class CompassView(context: Context) : View(context) {
    /** جهت گوشی نسبت به شمال حقیقی (درجه) */
    var heading = 0f

    /** جهت مقصد نسبت به شمال حقیقی (درجه)؛ null یعنی مقصدی نیست */
    var destBearing: Float? = null

    private val d = resources.displayMetrics.density

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * d
        color = Color.rgb(60, 70, 85)
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2f * d
        color = Color.rgb(120, 130, 145)
    }
    private val tickMajor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3f * d
        color = Color.WHITE
    }
    private val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 22f * d
        color = Color.WHITE
        isFakeBoldText = true
    }
    private val letterNorth = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 24f * d
        color = Color.rgb(255, 82, 82)
        isFakeBoldText = true
    }
    private val redFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 82, 82) }
    private val whiteFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 225, 232) }
    private val greenFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 230, 118) }
    private val amberFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 171, 0) }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f - 16f * d

        c.save()
        c.rotate(-heading, cx, cy)

        c.drawCircle(cx, cy, r, ring)
        for (deg in 0 until 360 step 10) {
            val major = deg % 30 == 0
            val len = if (major) 16f * d else 8f * d
            val a = Math.toRadians(deg.toDouble())
            val sx = sin(a).toFloat()
            val cs = cos(a).toFloat()
            c.drawLine(
                cx + r * sx, cy - r * cs,
                cx + (r - len) * sx, cy - (r - len) * cs,
                if (major) tickMajor else tick
            )
        }

        val names = arrayOf("N", "E", "S", "W")
        for (i in 0..3) {
            val a = Math.toRadians(i * 90.0)
            val rr = r - 40f * d
            val x = cx + (rr * sin(a)).toFloat()
            val y = cy - (rr * cos(a)).toFloat() + letter.textSize / 3f
            c.drawText(names[i], x, y, if (i == 0) letterNorth else letter)
        }

        // سوزن: سرخ به سمت شمال، سفید به سمت جنوب
        val needle = r * 0.45f
        val north = Path()
        north.moveTo(cx, cy - needle)
        north.lineTo(cx - 9f * d, cy)
        north.lineTo(cx + 9f * d, cy)
        north.close()
        c.drawPath(north, redFill)
        val south = Path()
        south.moveTo(cx, cy + needle)
        south.lineTo(cx - 9f * d, cy)
        south.lineTo(cx + 9f * d, cy)
        south.close()
        c.drawPath(south, whiteFill)
        c.drawCircle(cx, cy, 5f * d, amberFill)

        // نشانگر مقصد روی حلقه
        val b = destBearing
        if (b != null) {
            val a = Math.toRadians(b.toDouble())
            val rr = r - 10f * d
            c.drawCircle(cx + (rr * sin(a)).toFloat(), cy - (rr * cos(a)).toFloat(), 9f * d, greenFill)
        }
        c.restore()

        // نشانگر ثابتِ بالا: جهتی که گوشی به آن اشاره می‌کند
        val p = Path()
        p.moveTo(cx - 11f * d, cy - r - 12f * d)
        p.lineTo(cx + 11f * d, cy - r - 12f * d)
        p.lineTo(cx, cy - r + 14f * d)
        p.close()
        c.drawPath(p, amberFill)
    }
}

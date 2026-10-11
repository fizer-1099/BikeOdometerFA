package ir.balasari.bikeodometer

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/**
 * سرعت شناور روی بقیه‌ی برنامه‌ها. فقط وقتی دیده می‌شود که سفر فعال است،
 * گزینه‌ی «نمایش شناور» روشن است و برنامه‌ی خودمان جلوی چشم نیست.
 * با کشیدن جابه‌جا می‌شود و با لمس، برنامه باز می‌شود.
 */
object FloatingOverlay {
    private var view: TextView? = null
    @Volatile private var appVisible = false

    fun canDraw(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    fun onAppVisibility(ctx: Context, visible: Boolean) {
        appVisible = visible
        if (visible) hide(ctx)
    }

    fun update(ctx: Context, speedKmh: Double) {
        val app = ctx.applicationContext
        val s = RideStore.settings(app)
        if (!s.getBoolean("float_on", false) || appVisible || !canDraw(app)) {
            hide(app)
            return
        }
        val v = view ?: build(app) ?: return
        view = v
        v.text = Units.speedValue(app, speedKmh.toFloat()).toInt().toString() + " " + Units.speedUnit(app)
        val over = s.getBoolean("limit_on", false) && speedKmh > s.getInt("limit", 30)
        v.setTextColor(if (over) Color.rgb(255, 82, 82) else Color.rgb(0, 230, 118))
    }

    fun hide(ctx: Context) {
        val v = view ?: return
        view = null
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(v)
        } catch (_: Exception) {
        }
    }

    private fun build(app: Context): TextView? {
        return try {
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val d = app.resources.displayMetrics.density
            val s = RideStore.settings(app)

            val tv = TextView(app)
            tv.textSize = 22f
            tv.typeface = Typeface.DEFAULT_BOLD
            tv.gravity = Gravity.CENTER
            tv.setTextColor(Color.WHITE)
            val bg = GradientDrawable()
            bg.setColor(Color.argb(210, 12, 16, 22))
            bg.cornerRadius = 16 * d
            tv.background = bg
            tv.setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())

            @Suppress("DEPRECATION")
            val type = if (Build.VERSION.SDK_INT >= 26) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.LEFT
            lp.x = s.getInt("float_x", (16 * d).toInt())
            lp.y = s.getInt("float_y", (120 * d).toInt())

            var sx = 0
            var sy = 0
            var tx = 0f
            var ty = 0f
            var moved = false
            tv.setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = lp.x
                        sy = lp.y
                        tx = e.rawX
                        ty = e.rawY
                        moved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - tx).toInt()
                        val dy = (e.rawY - ty).toInt()
                        if (abs(dx) + abs(dy) > 12) moved = true
                        lp.x = sx + dx
                        lp.y = sy + dy
                        try { wm.updateViewLayout(tv, lp) } catch (_: Exception) {}
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) {
                            s.edit().putInt("float_x", lp.x).putInt("float_y", lp.y).apply()
                        } else {
                            app.startActivity(
                                Intent(app, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            )
                        }
                        true
                    }
                    else -> false
                }
            }
            wm.addView(tv, lp)
            tv
        } catch (_: Exception) {
            null
        }
    }
}

package ir.balasari.bikeodometer

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.min

/**
 * نمایش HUD: سرعت درشت و آینه‌ای روی زمینه‌ی سیاه.
 * گوشی را رو به آسمان روی داشبورد بگذار تا انعکاسش روی شیشه‌ی جلو دیده شود.
 * لمس صفحه حالت آینه‌ای/عادی را عوض می‌کند.
 */
class HudActivity : Activity() {
    private val fa = Locale("fa")
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 500L)
        }
    }

    private val green = Color.rgb(0, 230, 118)
    private val red = Color.rgb(255, 82, 82)

    private lateinit var content: LinearLayout
    private lateinit var speedText: TextView
    private lateinit var infoText: TextView
    private lateinit var hintText: TextView
    private var base = 0f
    private var mirror = true

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = window.attributes
        lp.screenBrightness = 1f
        window.attributes = lp

        val dm = resources.displayMetrics
        base = min(dm.widthPixels, dm.heightPixels).toFloat()

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.gravity = Gravity.CENTER

        speedText = TextView(this)
        speedText.setTextSize(TypedValue.COMPLEX_UNIT_PX, base * 0.62f)
        speedText.typeface = Typeface.DEFAULT_BOLD
        speedText.setTextColor(green)
        speedText.includeFontPadding = false
        speedText.gravity = Gravity.CENTER
        speedText.text = "0"

        val unit = TextView(this)
        unit.text = "km/h"
        unit.setTextSize(TypedValue.COMPLEX_UNIT_PX, base * 0.09f)
        unit.setTextColor(Color.rgb(150, 160, 170))
        unit.gravity = Gravity.CENTER

        infoText = TextView(this)
        infoText.setTextSize(TypedValue.COMPLEX_UNIT_PX, base * 0.07f)
        infoText.setTextColor(Color.rgb(200, 205, 212))
        infoText.gravity = Gravity.CENTER
        infoText.setPadding(0, dp(16), 0, 0)

        content.addView(speedText)
        content.addView(unit)
        content.addView(infoText)
        root.addView(
            content,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        // راهنما: آینه نمی‌شود تا خوانا بماند
        hintText = TextView(this)
        hintText.textSize = 12f
        hintText.setTextColor(Color.rgb(110, 118, 130))
        hintText.gravity = Gravity.CENTER
        val hintLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        hintLp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        hintLp.bottomMargin = dp(24)
        root.addView(hintText, hintLp)

        root.setOnClickListener {
            mirror = !mirror
            RideStore.settings(this).edit().putBoolean("hud_mirror", mirror).apply()
            applyMirror()
        }
        setContentView(root)

        mirror = RideStore.settings(this).getBoolean("hud_mirror", true)
        applyMirror()
    }

    private fun applyMirror() {
        content.scaleX = if (mirror) -1f else 1f
    }

    private fun refresh() {
        val p = RideStore.state(this)
        val active = p.getBoolean("active", false)
        val speed = if (active) p.getFloat("speed", 0f) else 0f

        val text = speed.toInt().toString()
        speedText.text = text
        // سه رقمی‌ها کمی کوچک‌تر تا از صفحه بیرون نزنند
        val size = if (text.length >= 3) base * 0.5f else base * 0.62f
        speedText.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)

        val s = RideStore.settings(this)
        val over = s.getBoolean("limit_on", false) && speed > s.getInt("limit", 30)
        speedText.setTextColor(if (over) red else green)

        val now = System.currentTimeMillis()
        val elapsed = if (active) (now - p.getLong("start_ms", now)) / 1000L else 0L
        infoText.text = String.format(fa, "%.2f", p.getFloat("distance", 0f)) +
            " km  •  " + RideStore.formatDuration(elapsed)

        hintText.text = if (active) {
            "برای عادی/آینه‌ای کردن تصویر، صفحه را لمس کن"
        } else {
            "سفر شروع نشده؛ از صفحه‌ی اصلی یا هولدر شروع کن"
        }
    }

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }
}

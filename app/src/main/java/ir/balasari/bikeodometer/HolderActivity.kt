package ir.balasari.bikeodometer

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * حالت هولدر: داشبورد تیره و درشت برای گوشی روی پایه‌ی فرمان.
 * صفحه روشن می‌ماند و دکمه‌ی شروع/پایان بزرگ است.
 */
class HolderActivity : Activity() {
    private val fa = Locale("fa")
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 500L)
        }
    }

    private val bg = Color.rgb(8, 10, 14)
    private val panel = Color.rgb(22, 27, 34)
    private val grey = Color.rgb(140, 150, 165)
    private val blue = Color.rgb(2, 136, 209)
    private val red = Color.rgb(211, 47, 47)
    private val green = Color.rgb(0, 230, 118)
    private val alertRed = Color.rgb(255, 82, 82)

    private lateinit var speedText: TextView
    private lateinit var unitText: TextView
    private lateinit var statusText: TextView
    private lateinit var clockText: TextView
    private lateinit var distV: TextView
    private lateinit var avgV: TextView
    private lateinit var maxV: TextView
    private lateinit var timeV: TextView
    private lateinit var rideButton: Button
    private var uiActive: Boolean? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(radiusDp).toFloat()
        return d
    }

    private fun label(t: String, sizeSp: Float, color: Int): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.textSize = sizeSp
        tv.setTextColor(color)
        tv.gravity = Gravity.CENTER
        return tv
    }

    private fun tile(title: String, value: TextView): LinearLayout {
        value.textSize = 18f
        value.setTextColor(Color.WHITE)
        value.typeface = Typeface.DEFAULT_BOLD
        value.gravity = Gravity.CENTER
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.background = rounded(panel, 12)
        box.setPadding(dp(4), dp(10), dp(4), dp(10))
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.setMargins(dp(4), 0, dp(4), 0)
        box.layoutParams = lp
        box.addView(label(title, 11f, grey))
        box.addView(value)
        return box
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val dm = resources.displayMetrics
        val speedSizePx = min(dm.widthPixels * 0.42f, dm.heightPixels * 0.30f)

        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL
        outer.setBackgroundColor(bg)
        outer.fitsSystemWindows = true
        outer.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val inner = LinearLayout(this)
        inner.orientation = LinearLayout.VERTICAL
        inner.setPadding(dp(12), dp(12), dp(12), dp(12))
        outer.addView(
            inner,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        // ردیف بالا: وضعیت و ساعت
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        statusText = label("● متوقف", 15f, grey)
        statusText.gravity = Gravity.START
        top.addView(statusText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        clockText = label("--:--:--", 16f, Color.rgb(120, 190, 240))
        top.addView(clockText)
        inner.addView(
            top,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // سرعت درشت
        val mid = LinearLayout(this)
        mid.orientation = LinearLayout.VERTICAL
        mid.gravity = Gravity.CENTER
        speedText = TextView(this)
        speedText.setTextSize(TypedValue.COMPLEX_UNIT_PX, speedSizePx)
        speedText.typeface = Typeface.DEFAULT_BOLD
        speedText.setTextColor(green)
        speedText.includeFontPadding = false
        speedText.gravity = Gravity.CENTER
        speedText.text = "0"
        unitText = label("km/h", 18f, grey)
        mid.addView(speedText)
        mid.addView(unitText)
        inner.addView(mid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // کارت‌های آمار (راست به چپ: مسافت، میانگین، حداکثر، زمان)
        distV = TextView(this)
        avgV = TextView(this)
        maxV = TextView(this)
        timeV = TextView(this)
        val stats = LinearLayout(this)
        stats.orientation = LinearLayout.HORIZONTAL
        stats.addView(tile("مسافت (km)", distV))
        stats.addView(tile("میانگین", avgV))
        stats.addView(tile("حداکثر", maxV))
        stats.addView(tile("زمان", timeV))
        inner.addView(
            stats,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // دکمه‌ی بزرگ شروع/پایان
        rideButton = Button(this)
        rideButton.text = "شروع سفر"
        rideButton.textSize = 20f
        rideButton.setTextColor(Color.WHITE)
        rideButton.background = rounded(blue, 16)
        rideButton.setOnClickListener { onRideClick() }
        val btnLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64))
        btnLp.topMargin = dp(12)
        inner.addView(rideButton, btnLp)

        setContentView(outer)
    }

    private fun applyRideUi(active: Boolean) {
        rideButton.text = if (active) "پایان سفر" else "شروع سفر"
        rideButton.background = rounded(if (active) red else blue, 16)
        statusText.text = if (active) "● در حال ثبت" else "● متوقف"
        statusText.setTextColor(if (active) green else grey)
    }

    private fun refresh() {
        val p = RideStore.state(this)
        val active = p.getBoolean("active", false)
        if (uiActive != active) {
            uiActive = active
            applyRideUi(active)
        }
        clockText.text = SimpleDateFormat("HH:mm:ss", fa).format(Date())

        val speed = if (active) p.getFloat("speed", 0f) else 0f
        speedText.text = speed.toInt().toString()

        val s = RideStore.settings(this)
        val limitOn = s.getBoolean("limit_on", false)
        val limit = s.getInt("limit", 30)
        speedText.setTextColor(if (limitOn && speed > limit) alertRed else green)
        unitText.text = if (limitOn) "km/h  •  سقف " + String.format(fa, "%d", limit) else "km/h"

        val now = System.currentTimeMillis()
        val elapsed = if (active) (now - p.getLong("start_ms", now)) / 1000L else p.getLong("elapsed", 0L)
        distV.text = String.format(fa, "%.2f", p.getFloat("distance", 0f))
        avgV.text = String.format(fa, "%.1f", p.getFloat("average", 0f))
        maxV.text = String.format(fa, "%.1f", p.getFloat("max", 0f))
        timeV.text = RideStore.formatDuration(elapsed)
    }

    private fun onRideClick() {
        if (RideStore.state(this).getBoolean("active", false)) {
            AlertDialog.Builder(this)
                .setTitle("پایان سفر")
                .setMessage("سفر ذخیره و تمام شود؟")
                .setPositiveButton("پایان") { _, _ -> stopRide() }
                .setNegativeButton("ادامه", null)
                .show()
        } else {
            startRide()
        }
    }

    private fun startRide() {
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine) {
            Toast.makeText(
                this,
                "اول یک بار از صفحه‌ی اصلی «شروع سفر» را بزن تا مجوز مکان داده شود.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        RideStore.reset(this)
        ContextCompat.startForegroundService(
            this,
            Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_START)
        )
        Toast.makeText(this, "ثبت مسیر شروع شد", Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun stopRide() {
        if (isServiceRunning()) {
            startService(Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_STOP))
        } else {
            RideStore.finishRide(this, System.currentTimeMillis())
        }
        Toast.makeText(this, "سفر پایان یافت", Toast.LENGTH_SHORT).show()
        handler.postDelayed({ refresh() }, 400L)
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        return am.getRunningServices(50).any { it.service.className == TrackingService::class.java.name }
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

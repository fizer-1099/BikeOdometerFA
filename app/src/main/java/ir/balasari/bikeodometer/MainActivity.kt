package ir.balasari.bikeodometer

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : Activity() {
    private val permRequest = 410
    private val fa = Locale("fa")
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refreshUi()
            handler.postDelayed(this, 1000L)
        }
    }

    private lateinit var gauge: GaugeView
    private lateinit var map: MapView
    private lateinit var gaugePage: View
    private lateinit var mapPage: View
    private lateinit var tabGauge: Button
    private lateinit var tabMap: Button
    private lateinit var rideButton: Button
    private lateinit var statusText: TextView
    private lateinit var clockText: TextView
    private lateinit var tripTimeValue: TextView
    private lateinit var maxValueText: TextView
    private lateinit var avgValueText: TextView
    private lateinit var distanceValueText: TextView
    private lateinit var limitText: TextView
    private lateinit var limitSwitch: Button

    private val routeLine = Polyline()
    private var marker: Marker? = null
    private var liveCallback: LocationCallback? = null
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private var followMe = true
    private var centered = false
    private var lastShownPoints = -1
    private var uiActive: Boolean? = null
    private var recoveryDialogShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)

        gauge = findViewById(R.id.gauge)
        map = findViewById(R.id.map)
        gaugePage = findViewById(R.id.gaugePage)
        mapPage = findViewById(R.id.mapPage)
        tabGauge = findViewById(R.id.tabGauge)
        tabMap = findViewById(R.id.tabMap)
        rideButton = findViewById(R.id.rideButton)
        statusText = findViewById(R.id.statusText)
        clockText = findViewById(R.id.clockText)
        tripTimeValue = findViewById(R.id.tripTimeValue)
        maxValueText = findViewById(R.id.maxValueText)
        avgValueText = findViewById(R.id.avgValueText)
        distanceValueText = findViewById(R.id.distanceValueText)
        limitText = findViewById(R.id.limitText)
        limitSwitch = findViewById(R.id.limitSwitch)

        // نقشه
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(16.0)
        map.controller.setCenter(GeoPoint(35.6892, 51.3890))
        routeLine.outlinePaint.color = Color.rgb(25, 118, 210)
        routeLine.outlinePaint.strokeWidth = 8f
        map.overlays.add(routeLine)
        marker = Marker(map).apply {
            title = "مکان فعلی"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        marker?.let { map.overlays.add(it) }
        map.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_MOVE) followMe = false
            false
        }
        findViewById<Button>(R.id.followButton).setOnClickListener {
            followMe = true
            marker?.position?.let { map.controller.animateTo(it) }
        }

        // تب‌ها
        tabGauge.setOnClickListener { showPage(false) }
        tabMap.setOnClickListener { showPage(true) }

        // سقف مجاز
        findViewById<Button>(R.id.limitMinus).setOnClickListener { changeLimit(-5) }
        findViewById<Button>(R.id.limitPlus).setOnClickListener { changeLimit(5) }
        limitSwitch.setOnClickListener {
            val s = RideStore.settings(this)
            s.edit().putBoolean("limit_on", !s.getBoolean("limit_on", false)).apply()
            updateLimitUi()
        }
        updateLimitUi()

        // دکمه‌ها
        rideButton.setOnClickListener {
            if (RideStore.state(this).getBoolean("active", false)) confirmStop() else onStartClicked()
        }
        findViewById<Button>(R.id.historyButton).setOnClickListener { showHistory() }
        findViewById<Button>(R.id.exportButton).setOnClickListener { exportCsv() }
    }

    // ------------------------------------------------------------------ UI

    private fun showPage(showMap: Boolean) {
        gaugePage.visibility = if (showMap) View.GONE else View.VISIBLE
        mapPage.visibility = if (showMap) View.VISIBLE else View.GONE
        tabGauge.setBackgroundResource(if (showMap) R.drawable.bg_tab else R.drawable.bg_primary)
        tabMap.setBackgroundResource(if (showMap) R.drawable.bg_primary else R.drawable.bg_tab)
        tabGauge.setTextColor(if (showMap) Color.rgb(60, 70, 85) else Color.WHITE)
        tabMap.setTextColor(if (showMap) Color.WHITE else Color.rgb(60, 70, 85))
        if (showMap) {
            lastShownPoints = -1
            refreshRoute()
        }
    }

    private fun updateLimitUi() {
        val s = RideStore.settings(this)
        val limit = s.getInt("limit", 30)
        val on = s.getBoolean("limit_on", false)
        limitText.text = "سقف مجاز: " + String.format(fa, "%d", limit) + " km/h"
        limitSwitch.text = if (on) "🔔 روشن" else "🔕 خاموش"
        gauge.limit = if (on) limit.toFloat() else 0f
    }

    private fun changeLimit(delta: Int) {
        val s = RideStore.settings(this)
        val v = (s.getInt("limit", 30) + delta).coerceIn(5, 200)
        s.edit().putInt("limit", v).apply()
        updateLimitUi()
    }

    private fun applyRideUi(active: Boolean) {
        rideButton.text = if (active) "پایان سفر" else "شروع سفر"
        rideButton.setBackgroundResource(if (active) R.drawable.bg_stop else R.drawable.bg_primary)
        statusText.text = if (active) "● در حال ثبت" else "● متوقف"
        statusText.setTextColor(if (active) Color.rgb(46, 125, 50) else Color.rgb(120, 130, 145))
    }

    private fun refreshUi() {
        val p = RideStore.state(this)
        val active = p.getBoolean("active", false)
        if (uiActive != active) {
            uiActive = active
            applyRideUi(active)
        }
        clockText.text = SimpleDateFormat("HH:mm:ss", fa).format(Date())

        gauge.setSpeed(if (active) p.getFloat("speed", 0f) else 0f)

        val now = System.currentTimeMillis()
        val elapsed = if (active) (now - p.getLong("start_ms", now)) / 1000L else p.getLong("elapsed", 0L)
        tripTimeValue.text = RideStore.formatDuration(elapsed)
        maxValueText.text = String.format(fa, "%.1f", p.getFloat("max", 0f))
        avgValueText.text = String.format(fa, "%.1f", p.getFloat("average", 0f))
        distanceValueText.text = String.format(fa, "%.2f", p.getFloat("distance", 0f))

        if (mapPage.visibility == View.VISIBLE) refreshRoute()
        checkStaleRide(p)
    }

    private fun refreshRoute() {
        val count = RideStore.state(this).getInt("points", 0)
        if (count == lastShownPoints) return
        lastShownPoints = count
        val pts = RideStore.readRoute(this).map { GeoPoint(it.first, it.second) }
        routeLine.setPoints(pts)
        map.invalidate()
    }

    // ------------------------------------------------------------------ سفر

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun onStartClicked() {
        val missing = ArrayList<String>()
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                missing.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), permRequest)
            return
        }
        beginRide(false)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != permRequest) return
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            startLive()
            beginRide(false)
        } else {
            Toast.makeText(this, "برای ثبت مسیر، مجوز «مکان دقیق» لازم است.", Toast.LENGTH_LONG).show()
        }
    }

    private fun beginRide(resume: Boolean) {
        if (resume) {
            RideStore.state(this).edit().putLong("last_update", System.currentTimeMillis()).apply()
        } else {
            RideStore.reset(this)
        }
        recoveryDialogShown = false
        lastShownPoints = -1
        ContextCompat.startForegroundService(
            this,
            Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_START)
        )
        Toast.makeText(this, if (resume) "ثبت مسیر ادامه یافت" else "ثبت مسیر شروع شد", Toast.LENGTH_SHORT).show()
        refreshUi()
    }

    private fun confirmStop() {
        AlertDialog.Builder(this)
            .setTitle("پایان سفر")
            .setMessage("سفر ذخیره و تمام شود؟")
            .setPositiveButton("پایان") { _, _ -> stopRide() }
            .setNegativeButton("ادامه", null)
            .show()
    }

    private fun stopRide() {
        if (isServiceRunning()) {
            startService(Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_STOP))
        } else {
            RideStore.finishRide(this, System.currentTimeMillis())
        }
        Toast.makeText(this, "سفر پایان یافت", Toast.LENGTH_SHORT).show()
        handler.postDelayed({ refreshUi() }, 400L)
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        return am.getRunningServices(50).any { it.service.className == TrackingService::class.java.name }
    }

    /** اگر سیستم سرویس را کشته باشد ولی سفر هنوز «فعال» ثبت شده باشد. */
    private fun checkStaleRide(p: SharedPreferences) {
        if (recoveryDialogShown || !p.getBoolean("active", false)) return
        val ref = max(p.getLong("start_ms", 0L), p.getLong("last_update", 0L))
        if (System.currentTimeMillis() - ref < 20_000L) return
        if (isServiceRunning()) return
        recoveryDialogShown = true
        AlertDialog.Builder(this)
            .setTitle("سفر ناتمام")
            .setMessage("ثبت سفر قبلی ناخواسته متوقف شده. ادامه می‌دهی یا سفر ذخیره و تمام شود؟")
            .setCancelable(false)
            .setPositiveButton("ادامه سفر") { _, _ -> beginRide(true) }
            .setNegativeButton("ذخیره و پایان") { _, _ ->
                RideStore.finishRide(this, ref)
                recoveryDialogShown = false
            }
            .show()
    }

    // ------------------------------------------------------------------ تاریخچه و خروجی

    private fun showHistory() {
        val raw = RideStore.historyRaw(this)
        val text = if (raw.isBlank()) {
            "هنوز سفری ذخیره نشده است."
        } else {
            raw.lines().filter { it.isNotBlank() }.reversed().joinToString("\n\n") { line ->
                val p = line.split(",")
                if (p.size >= 3) {
                    val km = p[1].toDoubleOrNull()?.let { String.format(fa, "%.2f", it) } ?: p[1]
                    val dur = RideStore.formatDuration(p[2].toLongOrNull() ?: 0L)
                    "${p[0]}\n$km کیلومتر  •  $dur"
                } else line
            }
        }
        AlertDialog.Builder(this)
            .setTitle("تاریخچه سفرها")
            .setMessage(text)
            .setPositiveButton("باشه", null)
            .show()
    }

    private fun exportCsv() {
        val items = RideStore.historyRaw(this)
        if (items.isBlank()) {
            Toast.makeText(this, "هنوز گزارشی برای خروجی وجود ندارد.", Toast.LENGTH_SHORT).show()
            return
        }
        val csv = "تاریخ,مسافت_کیلومتر,مدت_ثانیه\n$items"
        val uri = FileShare.createUri(this, "rides.csv", csv)
        if (uri != null) {
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/csv")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "ارسال گزارش سفرها"))
        } else {
            Toast.makeText(this, "ساخت فایل گزارش ناموفق بود.", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ مکان زنده (فقط وقتی برنامه جلوی چشم است)

    private fun startLive() {
        if (liveCallback != null) return
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L).build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val l = result.lastLocation ?: return
                val pt = GeoPoint(l.latitude, l.longitude)
                marker?.position = pt
                if (!centered) {
                    map.controller.setCenter(pt)
                    centered = true
                } else if (followMe && mapPage.visibility == View.VISIBLE) {
                    map.controller.animateTo(pt)
                }
                map.invalidate()
            }
        }
        try {
            fused.requestLocationUpdates(request, cb, mainLooper)
            liveCallback = cb
        } catch (_: SecurityException) {
        }
    }

    private fun stopLive() {
        liveCallback?.let {
            try { fused.removeLocationUpdates(it) } catch (_: Exception) {}
        }
        liveCallback = null
    }

    // ------------------------------------------------------------------ چرخه‌ی عمر

    override fun onResume() {
        super.onResume()
        map.onResume()
        startLive()
        lastShownPoints = -1
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        stopLive()
        map.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        map.onDetach()
        super.onDestroy()
    }
}

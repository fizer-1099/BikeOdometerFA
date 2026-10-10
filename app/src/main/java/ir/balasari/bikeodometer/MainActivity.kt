package ir.balasari.bikeodometer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var map: MapView
    private lateinit var distanceText: TextView
    private lateinit var speedText: TextView
    private lateinit var statusText: TextView
    private val routeLine = Polyline()
    private val points = mutableListOf<GeoPoint>()
    private val prefs by lazy { getSharedPreferences("ride_state", MODE_PRIVATE) }
    private val requestCode = 410

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)
        map = findViewById(R.id.map)
        distanceText = findViewById(R.id.distanceText)
        speedText = findViewById(R.id.speedText)
        statusText = findViewById(R.id.statusText)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(15.0)
        map.controller.setCenter(GeoPoint(35.6892, 51.3890))
        routeLine.outlinePaint.color = android.graphics.Color.rgb(25, 118, 210)
        routeLine.outlinePaint.strokeWidth = 8f
        map.overlays.add(routeLine)

        findViewById<Button>(R.id.startButton).setOnClickListener { checkAndStart() }
        findViewById<Button>(R.id.stopButton).setOnClickListener { stopRide() }
        findViewById<Button>(R.id.historyButton).setOnClickListener { showHistory() }
        findViewById<Button>(R.id.exportButton).setOnClickListener { exportCsv() }
        refreshStats()
        refreshRoute()
    }

    private fun checkAndStart() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), requestCode)
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_START))
        statusText.text = "اندازه‌گیری فعال است؛ صفحه را می‌توانی خاموش کنی."
        Toast.makeText(this, "ثبت مسیر شروع شد", Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == this.requestCode && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) checkAndStart()
        else if (requestCode == this.requestCode) Toast.makeText(this, "برای ثبت مسیر، مجوز مکان لازم است.", Toast.LENGTH_LONG).show()
    }

    private fun stopRide() {
        stopService(Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_STOP))
        val distance = prefs.getFloat("distance", 0f).toDouble()
        val seconds = prefs.getLong("elapsed", 0L)
        if (distance > 0.01) {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
            val old = getSharedPreferences("ride_history", MODE_PRIVATE).getString("items", "") ?: ""
            val row = "$stamp,${"%.3f".format(Locale.US, distance)},$seconds"
            getSharedPreferences("ride_history", MODE_PRIVATE).edit().putString("items", if (old.isBlank()) row else "$old\n$row").apply()
        }
        statusText.text = "سفر پایان یافت"
        Toast.makeText(this, "سفر ذخیره شد", Toast.LENGTH_SHORT).show()
        refreshStats()
    }

    private fun refreshRoute() {
        val raw = prefs.getString("route", "") ?: ""
        val parsed = raw.split(";").mapNotNull { part ->
            val pair = part.split(",")
            if (pair.size == 2) try { GeoPoint(pair[0].toDouble(), pair[1].toDouble()) } catch (_: Exception) { null } else null
        }
        routeLine.setPoints(parsed)
        if (parsed.isNotEmpty()) map.controller.animateTo(parsed.last())
        map.invalidate()
    }

    private fun refreshStats() {
        distanceText.text = "مسافت: ${"%.2f".format(Locale("fa"), prefs.getFloat("distance", 0f))} کیلومتر"
        val current = prefs.getFloat("speed", 0f)
        val avg = prefs.getFloat("average", 0f)
        speedText.text = "سرعت فعلی: ${"%.1f".format(Locale("fa"), current)} | میانگین: ${"%.1f".format(Locale("fa"), avg)} کیلومتر/ساعت"
    }

    private fun showHistory() {
        val items = getSharedPreferences("ride_history", MODE_PRIVATE).getString("items", "") ?: ""
        android.app.AlertDialog.Builder(this).setTitle("تاریخچه سفرها")
            .setMessage(if (items.isBlank()) "هنوز سفری ذخیره نشده است." else items.replace(",", "  |  "))
            .setPositiveButton("باشه", null).show()
    }

    private fun exportCsv() {
        val items = getSharedPreferences("ride_history", MODE_PRIVATE).getString("items", "") ?: ""
        if (items.isBlank()) {
            Toast.makeText(this, "هنوز گزارشی برای خروجی وجود ندارد.", Toast.LENGTH_SHORT).show()
            return
        }
        val csv = "تاریخ,مسافت_کیلومتر,مدت_ثانیه\n$items"
        val uri = ContentProvider.createContentUriCompat(this, "rides.csv", csv)
        if (uri != null) {
            val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "ارسال گزارش سفرها"))
        } else Toast.makeText(this, "ساخت فایل گزارش ناموفق بود.", Toast.LENGTH_LONG).show()
    }

    override fun onResume() { super.onResume(); map.onResume(); refreshStats(); refreshRoute() }
    override fun onPause() { map.onPause(); super.onPause() }
    override fun onDestroy() { map.onDetach(); super.onDestroy() }
}

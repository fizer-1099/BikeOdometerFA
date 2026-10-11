package ir.balasari.bikeodometer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

class MainActivity : Activity() {
    private lateinit var map: MapView
    private lateinit var distanceText: TextView
    private lateinit var speedText: TextView
    private lateinit var statusText: TextView
    private val routeLine = Polyline()
    private var locationMarker: Marker? = null
    private val fused by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }
    private var liveCallback: LocationCallback? = null
    private val prefs by lazy {
        getSharedPreferences("ride_state", MODE_PRIVATE)
    }
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

        routeLine.outlinePaint.color =
            android.graphics.Color.rgb(25, 118, 210)
        routeLine.outlinePaint.strokeWidth = 8f
        map.overlays.add(routeLine)

        locationMarker = Marker(map).apply {
            title = "مکان فعلی"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        locationMarker?.let { map.overlays.add(it) }

        findViewById<Button>(R.id.startButton).setOnClickListener {
            checkAndStart()
        }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopRide()
        }
        findViewById<Button>(R.id.historyButton).setOnClickListener {
            showHistory()
        }
        findViewById<Button>(R.id.exportButton).setOnClickListener {
            exportCsv()
        }

        refreshStats()
        refreshRoute()
    }

    private fun startLiveLocation() {
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fine && !coarse) return

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 3000L
        ).setMinUpdateDistanceMeters(3f).build()

        if (liveCallback != null) return

        liveCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                val point = GeoPoint(location.latitude, location.longitude)
                locationMarker?.position = point
                map.controller.animateTo(point)
                map.invalidate()
            }
        }

        try {
            fused.requestLocationUpdates(
                request, liveCallback!!, mainLooper
            )
        } catch (_: SecurityException) {
            liveCallback = null
            Toast.makeText(
                this, "دسترسی مکان فعال نیست.", Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun checkAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) !=
                PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this, missing.toTypedArray(), requestCode
            )
            return
        }

        startLiveLocation()
        ContextCompat.startForegroundService(
            this,
            Intent(this, TrackingService::class.java)
                .setAction(TrackingService.ACTION_START)
        )
        statusText.text = "اندازه‌گیری فعال است؛ صفحه را می‌توانی خاموش کنی."
        Toast.makeText(this, "ثبت مسیر شروع شد", Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == this.requestCode &&
            grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            checkAndStart()
        } else if (requestCode == this.requestCode) {
            Toast.makeText(
                this, "برای ثبت مسیر، مجوز مکان لازم است.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun stopRide() {
        stopService(Intent(this, TrackingService::class.java))
        val distance = prefs.getFloat("distance", 0f).toDouble()
        val seconds = prefs.getLong("elapsed", 0L)

        if (distance > 0.01) {
            val stamp = SimpleDateFormat(
                "yyyy-MM-dd HH:mm", Locale.US
            ).format(Date())
            val historyPrefs =
                getSharedPreferences("ride_history", MODE_PRIVATE)
            val old = historyPrefs.getString("items", "") ?: ""
            val row = "$stamp,${"%.3f".format(Locale.US, distance)},$seconds"
            val updated = if (old.isBlank()) row else "$old\n$row"
            historyPrefs.edit().putString("items", updated).apply()
        }

        statusText.text = "سفر پایان یافت"
        Toast.makeText(this, "سفر پایان یافت", Toast.LENGTH_SHORT).show()
        refreshStats()
        refreshRoute()
    }

    private fun refreshRoute() {
        val raw = prefs.getString("route", "") ?: ""
        val parsed = raw.split(";").mapNotNull { part ->
            val pair = part.split(",")
            if (pair.size == 2) {
                try {
                    GeoPoint(pair[0].toDouble(), pair[1].toDouble())
                } catch (_: Exception) {
                    null
                }
            } else null
        }

        routeLine.setPoints(parsed)
        if (parsed.isNotEmpty()) {
            val latest = parsed.last()
            locationMarker?.position = latest
        }
        map.invalidate()
    }

    private fun refreshStats() {
        distanceText.text =
            "مسافت: ${"%.2f".format(Locale("fa"), prefs.getFloat("distance", 0f))} کیلومتر"
        val current = prefs.getFloat("speed", 0f)
        val average = prefs.getFloat("average", 0f)
        speedText.text =
            "سرعت فعلی: ${"%.1f".format(Locale("fa"), current)} | میانگین: ${"%.1f".format(Locale("fa"), average)} کیلومتر/ساعت"
    }

    private fun showHistory() {
        val items = getSharedPreferences(
            "ride_history", MODE_PRIVATE
        ).getString("items", "") ?: ""

        AlertDialog.Builder(this)
            .setTitle("تاریخچه سفرها")
            .setMessage(
                if (items.isBlank()) "هنوز سفری ذخیره نشده است."
                else items.replace(",", "  |  ")
            )
            .setPositiveButton("باشه", null)
            .show()
    }

    private fun exportCsv() {
        val items = getSharedPreferences(
            "ride_history", MODE_PRIVATE
        ).getString("items", "") ?: ""

        if (items.isBlank()) {
            Toast.makeText(
                this, "هنوز گزارشی برای خروجی وجود ندارد.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val csv = "تاریخ,مسافت_کیلومتر,مدت_ثانیه\n$items"
        val uri = ContentProvider.createContentUriCompat(
            this, "rides.csv", csv
        )

        if (uri != null) {
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/csv")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "ارسال گزارش سفرها"))
        } else {
            Toast.makeText(
                this, "ساخت فایل گزارش ناموفق بود.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        refreshStats()
        refreshRoute()
        startLiveLocation()
    }

    override fun onPause() {
        map.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        try {
            liveCallback?.let { fused.removeLocationUpdates(it) }
        } catch (_: Exception) {
        }
        map.onDetach()
        super.onDestroy()
    }
}

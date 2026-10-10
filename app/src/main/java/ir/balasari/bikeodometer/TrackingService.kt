package ir.balasari.bikeodometer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlin.math.max

class TrackingService : Service() {
    companion object {
        const val ACTION_START = "ir.balasari.bikeodometer.START"
        const val ACTION_STOP = "ir.balasari.bikeodometer.STOP"
        private const val CHANNEL = "bike_tracking"
        private const val NOTIFICATION_ID = 17
    }
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var callback: LocationCallback
    private val prefs by lazy { getSharedPreferences("ride_state", MODE_PRIVATE) }
    private var lastLocation: Location? = null
    private var distance = 0.0
    private var speedSum = 0.0
    private var speedCount = 0
    private var startMs = 0L

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        createChannel()
        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) processLocation(loc)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopTracking(); stopSelf(); return START_NOT_STICKY }
            else -> startTracking()
        }
        return START_STICKY
    }

    private fun startTracking() {
        startForeground(NOTIFICATION_ID, notification("در حال ثبت مسیر دوچرخه"))
        distance = 0.0
        speedSum = 0.0
        speedCount = 0
        lastLocation = null
        startMs = System.currentTimeMillis()
        prefs.edit().putFloat("distance", 0f).putFloat("speed", 0f).putFloat("average", 0f)
            .putLong("elapsed", 0L).putString("route", "").apply()
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
            .setMinUpdateDistanceMeters(3f).build()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return
        }
        fused.requestLocationUpdates(request, callback, mainLooper)
    }

    private fun processLocation(loc: Location) {
        val previous = lastLocation
        if (previous != null) {
            val delta = previous.distanceTo(loc).toDouble()
            val seconds = max(1.0, (loc.time - previous.time) / 1000.0)
            // Ignore inaccurate GPS jumps and stationary noise.
            if (loc.accuracy <= 35f && delta in 3.0..250.0 && seconds < 30.0) distance += delta / 1000.0
        }
        lastLocation = loc
        if (loc.accuracy <= 35f) {
            val oldRoute = prefs.getString("route", "") ?: ""
            val newPoint = "${loc.latitude},${loc.longitude}"
            prefs.edit().putString("route", if (oldRoute.isBlank()) newPoint else "$oldRoute;$newPoint").apply()
        }
        val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6 else 0.0
        if (speedKmh in 0.0..90.0 && loc.accuracy <= 35f) { speedSum += speedKmh; speedCount++ }
        val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(0L) / 1000L
        val avg = if (elapsed > 0) distance * 3600.0 / elapsed else 0.0
        prefs.edit().putFloat("distance", distance.toFloat())
            .putFloat("speed", speedKmh.toFloat()).putFloat("average", avg.toFloat())
            .putLong("elapsed", elapsed).apply()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification("مسافت: %.2f کیلومتر | سرعت: %.1f".format(distance, speedKmh)))
    }

    private fun stopTracking() {
        fused.removeLocationUpdates(callback)
        lastLocation = null
        prefs.edit().putFloat("speed", 0f).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL, "ثبت مسیر دوچرخه", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL).setContentTitle("کیلومترشمار دوچرخه")
            .setContentText(text).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true).build()

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { try { fused.removeLocationUpdates(callback) } catch (_: Exception) {}; super.onDestroy() }
}

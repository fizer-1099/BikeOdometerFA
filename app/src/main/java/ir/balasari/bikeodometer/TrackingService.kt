package ir.balasari.bikeodometer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.math.max

class TrackingService : Service() {
    companion object {
        const val ACTION_START = "ir.balasari.bikeodometer.START"
        const val ACTION_STOP = "ir.balasari.bikeodometer.STOP"
        private const val CHANNEL = "bike_tracking"
        private const val NOTIFICATION_ID = 17
        private const val MAX_ACCURACY = 30f     // متر؛ نقطه‌های بدتر نادیده گرفته می‌شوند
        private const val MAX_SPEED_MS = 30.0    // حدود ۱۰۸ km/h؛ بیشتر از این پرش GPS است
    }

    private lateinit var fused: FusedLocationProviderClient
    private lateinit var callback: LocationCallback
    private var tracking = false

    private var last: Location? = null
    private var outliers = 0
    private var distance = 0.0
    private var maxSpeed = 0.0
    private var movingSec = 0.0
    private var points = 0
    private var lastAlertMs = 0L
    private var tone: ToneGenerator? = null

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        createChannel()
        tone = try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100) } catch (_: Exception) { null }
        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) processLocation(loc)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            stopSelf()
            return START_NOT_STICKY
        }
        startTracking()
        return START_NOT_STICKY
    }

    private fun startTracking() {
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        // اندروید ۱۴: بدون مجوز مکان نباید startForeground صدا زده شود. Activity قبلاً مجوز را چک می‌کند.
        if (!fine) { stopSelf(); return }

        startForeground(NOTIFICATION_ID, buildNotification("در حال ثبت مسیر دوچرخه"))
        if (tracking) return

        // وضعیت را از prefs بخوان تا «ادامه‌ی سفر» بعد از kill شدن هم کار کند.
        val p = RideStore.state(this)
        if (!p.contains("start_ms")) p.edit().putLong("start_ms", System.currentTimeMillis()).apply()
        p.edit().putBoolean("active", true).apply()
        distance = p.getFloat("distance", 0f).toDouble()
        maxSpeed = p.getFloat("max", 0f).toDouble()
        movingSec = p.getLong("moving", 0L).toDouble()
        points = p.getInt("points", 0)
        last = null
        outliers = 0

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .build()
        try {
            fused.requestLocationUpdates(request, callback, mainLooper)
            tracking = true
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    private fun processLocation(loc: Location) {
        if (!tracking) return
        val now = System.currentTimeMillis()
        if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY) return

        // سرعت از Doppler خود GPS؛ زیر ۱.۵ km/h نویز حساب می‌شود.
        var speedKmh = if (loc.hasSpeed()) loc.speed * 3.6 else 0.0
        if (speedKmh < 1.5) speedKmh = 0.0
        val stationary = loc.hasSpeed() && loc.speed < 0.5f

        val prev = last
        if (prev == null) {
            last = loc
            addPoint(loc)
        } else {
            val dt = (loc.time - prev.time) / 1000.0
            if (dt > 0) {
                val delta = prev.distanceTo(loc).toDouble()
                val acc = if (loc.hasAccuracy()) loc.accuracy else 0f
                val noise = max(3.0, acc / 2.0)
                when {
                    // ایستاده: فاصله حساب نشود، فقط مرجع جلو برود
                    stationary -> { last = loc; outliers = 0 }
                    // وقفه‌ی طولانی (مثلاً تونل): مرجع جدید بدون حساب کردن فاصله
                    dt >= 30.0 -> { last = loc; outliers = 0; addPoint(loc) }
                    // پرش غیرواقعی: نادیده؛ اگر سه بار پشت سر هم شد، مرجع جدید
                    delta / dt > MAX_SPEED_MS -> {
                        outliers++
                        if (outliers >= 3) { last = loc; outliers = 0 }
                    }
                    // حرکت واقعی
                    delta >= noise -> {
                        distance += delta / 1000.0
                        movingSec += dt
                        last = loc
                        outliers = 0
                        addPoint(loc)
                    }
                    // حرکت کوچک‌تر از نویز: مرجع ثابت می‌ماند تا حرکت‌های کوچک جمع شوند
                    else -> {}
                }
            }
        }

        if (speedKmh > maxSpeed && speedKmh < 120.0) maxSpeed = speedKmh
        val avg = if (movingSec > 0) distance * 3600.0 / movingSec else 0.0

        RideStore.state(this).edit()
            .putFloat("distance", distance.toFloat())
            .putFloat("speed", speedKmh.toFloat())
            .putFloat("average", avg.toFloat())
            .putFloat("max", maxSpeed.toFloat())
            .putLong("moving", movingSec.toLong())
            .putInt("points", points)
            .putLong("last_update", now)
            .apply()

        checkLimit(speedKmh, now)
        FloatingOverlay.update(this, speedKmh)

        val text = "مسافت: " + Units.distText(this, distance.toFloat()) + " " + Units.distUnit(this) + " | سرعت: " + Units.speedText(this, speedKmh.toFloat()) + " " + Units.speedUnit(this)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun addPoint(loc: Location) {
        RideStore.appendPoint(this, loc.latitude, loc.longitude)
        points++
    }

    private fun checkLimit(speedKmh: Double, now: Long) {
        val s = RideStore.settings(this)
        if (!s.getBoolean("limit_on", false)) return
        val limit = s.getInt("limit", 30)
        if (speedKmh > limit && now - lastAlertMs > 5000L) {
            lastAlertMs = now
            try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 500) } catch (_: Exception) {}
            vibrateOnce()
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrateOnce() {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            v.vibrate(400)
        }
    }

    private fun stopTracking() {
        if (tracking) {
            try { fused.removeLocationUpdates(callback) } catch (_: Exception) {}
            tracking = false
        }
        RideStore.finishRide(this, System.currentTimeMillis())
        FloatingOverlay.hide(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL, "ثبت مسیر دوچرخه", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            flags
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("اف کیلومتر")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "پایان سفر", stop)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { fused.removeLocationUpdates(callback) } catch (_: Exception) {}
        try { tone?.release() } catch (_: Exception) {}
        FloatingOverlay.hide(this)
        super.onDestroy()
    }
}

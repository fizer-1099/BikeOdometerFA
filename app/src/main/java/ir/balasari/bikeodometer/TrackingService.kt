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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.speech.tts.TextToSpeech
import java.util.Locale
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

class TrackingService : Service(), SensorEventListener {
    companion object {
        const val ACTION_START = "ir.balasari.bikeodometer.START"
        const val ACTION_STOP = "ir.balasari.bikeodometer.STOP"
        const val ACTION_PAUSE = "ir.balasari.bikeodometer.PAUSE"
        const val ACTION_RESUME = "ir.balasari.bikeodometer.RESUME"
        private const val CHANNEL = "bike_tracking"
        private const val NOTIFICATION_ID = 17
        private const val MAX_ACCURACY = 30f     // متر؛ نقطه‌های بدتر نادیده گرفته می‌شوند
        private const val MAX_SPEED_MS = 30.0    // حدود ۱۰۸ km/h؛ بیشتر از این پرش GPS است
    }

    private lateinit var fused: SafeLocation
    private lateinit var callback: LocationCallback
    private var tracking = false

    private var last: Location? = null
    private var outliers = 0
    private var distance = 0.0
    private var maxSpeed = 0.0
    private var movingSec = 0.0
    private var points = 0
    private var lastAlertMs = 0L
    private var mode = "bike"
    private var paused = false
    private var autoPaused = false
    private var stillSince = 0L
    private var announced = 0
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsFa = false
    private var sensorManager: SensorManager? = null
    private var stepSensorActive = false
    private var stepBaseline = -1f
    private var stepsAtStart = 0
    private var sensorSteps = 0
    private var tone: ToneGenerator? = null

    override fun onCreate() {
        super.onCreate()
        fused = SafeLocation(this)
        createChannel()
        tone = try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100) } catch (_: Exception) { null }
        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) processLocation(loc)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            pauseTracking()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESUME) {
            resumeTracking()
            return START_NOT_STICKY
        }
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
        p.edit().putBoolean("active", true).putBoolean("paused", false).putBoolean("auto_paused", false).apply()
        distance = p.getFloat("distance", 0f).toDouble()
        maxSpeed = p.getFloat("max", 0f).toDouble()
        movingSec = p.getLong("moving", 0L).toDouble()
        points = p.getInt("points", 0)
        mode = p.getString("mode", "bike") ?: "bike"
        announced = (distance * Units.factor(this)).toInt()
        last = null
        outliers = 0

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .build()
        try {
            fused.requestLocationUpdates(request, callback, mainLooper)
            tracking = true
            startStepCounting()
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    private fun processLocation(loc: Location) {
        if (!tracking || paused) return
        val now = System.currentTimeMillis()
        if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY) return

        // سرعت از Doppler خود GPS؛ زیر ۱.۵ km/h نویز حساب می‌شود.
        var speedKmh = if (loc.hasSpeed()) loc.speed * 3.6 else 0.0
        if (speedKmh < 1.5) speedKmh = 0.0
        val stationary = loc.hasSpeed() && loc.speed < 0.5f
        updateAutoPause(stationary, now)

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
                    delta / dt > Modes.byId(mode).maxJumpMs -> {
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

        if (speedKmh > maxSpeed && speedKmh < Modes.byId(mode).maxSpeedKmh) maxSpeed = speedKmh
        val avg = if (movingSec > 0) distance * 3600.0 / movingSec else 0.0

        RideStore.state(this).edit()
            .putFloat("distance", distance.toFloat())
            .putFloat("speed", speedKmh.toFloat())
            .putFloat("average", avg.toFloat())
            .putFloat("max", maxSpeed.toFloat())
            .putLong("moving", movingSec.toLong())
            .putInt("points", points)
            .putInt("steps", currentSteps())
            .putLong("last_update", now)
            .apply()

        checkLimit(speedKmh, now)
        checkAnnounce()
        FloatingOverlay.update(this, speedKmh)

        val text = "مسافت: " + Units.distText(this, distance.toFloat()) + " " + Units.distUnit(this) + " | سرعت: " + Units.speedText(this, speedKmh.toFloat()) + " " + Units.speedUnit(this)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ---- شمارنده‌ی قدم (حالت پیاده‌روی)
    private fun startStepCounting() {
        if (!Modes.byId(mode).hasSteps) return
        stepsAtStart = RideStore.state(this).getInt("steps", 0)
        stepBaseline = -1f
        sensorSteps = 0
        try {
            val permOk = Build.VERSION.SDK_INT < 29 ||
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACTIVITY_RECOGNITION
                ) == PackageManager.PERMISSION_GRANTED
            if (!permOk) return
            val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
            stepSensorActive = sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            sensorManager = sm
        } catch (_: Exception) {
        }
    }

    private fun stopStepCounting() {
        try { sensorManager?.unregisterListener(this) } catch (_: Exception) {}
        if (stepSensorActive) {
            RideStore.state(this).edit().putInt("steps", currentSteps()).apply()
        }
        stepSensorActive = false
        sensorManager = null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_STEP_COUNTER || event.values.isEmpty()) return
        val total = event.values[0]
        if (stepBaseline < 0f) stepBaseline = total
        sensorSteps = (total - stepBaseline).toInt().coerceAtLeast(0)
        RideStore.state(this).edit().putInt("steps", stepsAtStart + sensorSteps).apply()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** قدم‌ها: از حسگر، یا (اگر حسگر/مجوز نبود) تخمین از روی مسافت. */
    private fun currentSteps(): Int {
        val m = Modes.byId(mode)
        if (!m.hasSteps) return 0
        if (stepSensorActive) return stepsAtStart + sensorSteps
        return if (m.strideM > 0.0) (distance * 1000.0 / m.strideM).toInt() else 0
    }

    // ---- اعلام صوتی هر کیلومتر
    private fun ensureTts() {
        if (tts != null) return
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.isLanguageAvailable(Locale("fa")) ?: TextToSpeech.LANG_NOT_SUPPORTED
                ttsFa = r >= TextToSpeech.LANG_AVAILABLE
                tts?.setLanguage(if (ttsFa) Locale("fa") else Locale.ENGLISH)
                ttsReady = true
            }
        }
    }

    private fun checkAnnounce() {
        if (!RideStore.settings(this).getBoolean("voice_km", false)) return
        val units = (distance * Units.factor(this)).toInt()
        if (units <= announced) return
        announced = units
        ensureTts()
        if (!ttsReady) return
        val miles = Units.isMiles(this)
        val avg = Math.round(RideStore.state(this).getFloat("average", 0f) * Units.factor(this)).toInt()
        val msg = if (ttsFa) {
            units.toString() + (if (miles) " مایل" else " کیلومتر") + " طی شد. میانگین سرعت " + avg
        } else {
            units.toString() + (if (miles) " miles" else " kilometers") + ". Average speed " + avg
        }
        tts?.speak(msg, TextToSpeech.QUEUE_ADD, null, "km" + units)
    }

    /** ارتفاع از سطح دریا (اندروید ۱۴ به بالا) یا ارتفاع GPS؛ NaN اگر نبود. */
    private fun altOf(loc: Location): Double =
        if (Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude()) loc.mslAltitudeMeters
        else if (loc.hasAltitude()) loc.altitude
        else Double.NaN

    private fun addPoint(loc: Location) {
        RouteLog.append(this, loc.latitude, loc.longitude, loc.time, altOf(loc))
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

    // ---- مکث و ادامه
    private fun pauseTracking() {
        if (!tracking || paused) return
        try { fused.removeLocationUpdates(callback) } catch (_: Exception) {}
        stopStepCounting()
        endAutoPause(System.currentTimeMillis())
        paused = true
        last = null
        outliers = 0
        RideStore.state(this).edit()
            .putBoolean("paused", true)
            .putLong("pause_start", System.currentTimeMillis())
            .putFloat("speed", 0f)
            .apply()
        FloatingOverlay.hide(this)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification("مکث شده"))
    }

    private fun resumeTracking() {
        if (!tracking || !paused) return
        val now = System.currentTimeMillis()
        val p = RideStore.state(this)
        val total = p.getLong("paused_ms", 0L) + (now - p.getLong("pause_start", now))
        p.edit().putBoolean("paused", false).putLong("paused_ms", total).putLong("last_update", now).apply()
        paused = false
        last = null
        outliers = 0
        startStepCounting()
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .build()
        try {
            fused.requestLocationUpdates(request, callback, mainLooper)
        } catch (_: SecurityException) {
            stopSelf()
            return
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification("در حال ثبت مسیر"))
    }

    // ---- مکث خودکار (وقتی چند ثانیه ایستاده‌ای)
    private fun updateAutoPause(still: Boolean, now: Long) {
        val on = RideStore.settings(this).getBoolean("auto_pause", false)
        if (!on) {
            if (autoPaused) endAutoPause(now)
            stillSince = 0L
            return
        }
        if (still) {
            if (stillSince == 0L) stillSince = now
            if (!autoPaused && now - stillSince >= 8000L) {
                autoPaused = true
                RideStore.state(this).edit()
                    .putBoolean("auto_paused", true)
                    .putLong("auto_pause_start", stillSince)
                    .apply()
            }
        } else {
            stillSince = 0L
            if (autoPaused) endAutoPause(now)
        }
    }

    private fun endAutoPause(now: Long) {
        if (!autoPaused) return
        autoPaused = false
        val p = RideStore.state(this)
        val total = p.getLong("auto_paused_ms", 0L) + (now - p.getLong("auto_pause_start", now))
        p.edit().putBoolean("auto_paused", false).putLong("auto_paused_ms", total).apply()
    }

    private fun stopTracking() {
        if (tracking) {
            try { fused.removeLocationUpdates(callback) } catch (_: Exception) {}
            tracking = false
        }
        stopStepCounting()
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
        val pauseIntent = PendingIntent.getService(
            this, 2,
            Intent(this, TrackingService::class.java).setAction(if (paused) ACTION_RESUME else ACTION_PAUSE),
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
            .setSmallIcon(R.drawable.ic_stat_gauge)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, if (paused) "ادامه" else "مکث", pauseIntent)
            .addAction(0, "پایان سفر", stop)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { fused.removeLocationUpdates(callback) } catch (_: Exception) {}
        try { tone?.release() } catch (_: Exception) {}
        try { stopStepCounting() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        FloatingOverlay.hide(this)
        super.onDestroy()
    }
}

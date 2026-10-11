package ir.balasari.bikeodometer

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** حالت سفر جاری، مسیر، تنظیمات و تاریخچه؛ بین Activity و Service مشترک است. */
object RideStore {
    private const val STATE = "ride_state"
    private const val HISTORY = "ride_history"
    private const val SETTINGS = "ride_settings"

    fun state(c: Context): SharedPreferences = c.getSharedPreferences(STATE, Context.MODE_PRIVATE)
    fun settings(c: Context): SharedPreferences = c.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)

    private fun routeFile(c: Context) = File(c.filesDir, "current_route.csv")

    /** شروع سفر تازه: آمار و مسیر قبلی پاک می‌شود. */
    fun reset(c: Context) {
        val now = System.currentTimeMillis()
        state(c).edit().clear()
            .putBoolean("active", true)
            .putLong("start_ms", now)
            .putLong("last_update", now)
            .apply()
        try { routeFile(c).delete() } catch (_: Exception) {}
    }

    fun appendPoint(c: Context, lat: Double, lon: Double) {
        try { routeFile(c).appendText("$lat,$lon\n") } catch (_: Exception) {}
    }

    fun readRoute(c: Context): List<Pair<Double, Double>> {
        val f = routeFile(c)
        if (!f.exists()) return emptyList()
        return try {
            f.readLines().mapNotNull { line ->
                val p = line.split(",")
                if (p.size == 2) {
                    val la = p[0].toDoubleOrNull()
                    val lo = p[1].toDoubleOrNull()
                    if (la != null && lo != null) Pair(la, lo) else null
                } else null
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** پایان سفر و ذخیره در تاریخچه. اگر سفر فعال نباشد کاری نمی‌کند (امن برای دوبار صدا زدن). */
    fun finishRide(c: Context, endMs: Long) {
        val p = state(c)
        if (!p.getBoolean("active", false)) return
        val start = p.getLong("start_ms", endMs)
        val elapsed = ((endMs - start) / 1000L).coerceAtLeast(0L)
        val distance = p.getFloat("distance", 0f)
        p.edit().putBoolean("active", false).putLong("elapsed", elapsed).putFloat("speed", 0f).apply()
        if (distance > 0.01f) {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(start))
            val h = c.getSharedPreferences(HISTORY, Context.MODE_PRIVATE)
            val old = h.getString("items", "") ?: ""
            val row = "$stamp,${String.format(Locale.US, "%.3f", distance)},$elapsed"
            h.edit().putString("items", if (old.isBlank()) row else "$old\n$row").apply()
        }
    }

    fun historyRaw(c: Context): String =
        c.getSharedPreferences(HISTORY, Context.MODE_PRIVATE).getString("items", "") ?: ""

    fun formatDuration(sec: Long): String {
        val s = sec.coerceAtLeast(0L)
        return String.format(Locale("fa"), "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }
}

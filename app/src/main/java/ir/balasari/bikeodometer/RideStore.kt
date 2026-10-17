package ir.balasari.bikeodometer

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** یک سفر ذخیره‌شده. سفرهای قدیمی (فقط ۳ ستون) مسیر و حداکثر سرعت ندارند. */
data class Ride(
    val index: Int,
    val startMs: Long,
    val stamp: String,
    val km: Double,
    val elapsed: Long,
    val moving: Long,
    val maxKmh: Double,
    val avgKmh: Double,
    val mode: String = "bike",
    val steps: Int = 0
)

/** حالت سفر جاری، مسیر، تنظیمات و تاریخچه؛ بین Activity و Service مشترک است. */
object RideStore {
    private const val STATE = "ride_state"
    private const val HISTORY = "ride_history"
    private const val SETTINGS = "ride_settings"

    fun state(c: Context): SharedPreferences = c.getSharedPreferences(STATE, Context.MODE_PRIVATE)
    fun settings(c: Context): SharedPreferences = c.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)

    private fun routeFile(c: Context) = File(c.filesDir, "current_route.csv")
    private fun routesDir(c: Context) = File(c.filesDir, "routes")
    private fun rideRouteFile(c: Context, startMs: Long) = File(routesDir(c), "$startMs.csv")

    /** شروع سفر تازه: آمار و مسیر قبلی پاک می‌شود. */
    fun reset(c: Context) {
        val now = System.currentTimeMillis()
        state(c).edit().clear()
            .putBoolean("active", true)
            .putLong("start_ms", now)
            .putLong("last_update", now)
            .putString("mode", settings(c).getString("mode", "bike") ?: "bike")
            .apply()
        try { routeFile(c).delete() } catch (_: Exception) {}
    }

    fun appendPoint(c: Context, lat: Double, lon: Double) {
        try { routeFile(c).appendText("$lat,$lon\n") } catch (_: Exception) {}
    }

    private fun readRouteFile(f: File): List<Pair<Double, Double>> {
        if (!f.exists()) return emptyList()
        return try {
            f.readLines().mapNotNull { line ->
                val p = line.split(",")
                if (p.size >= 2) {
                    val la = p[0].toDoubleOrNull()
                    val lo = p[1].toDoubleOrNull()
                    if (la != null && lo != null) Pair(la, lo) else null
                } else null
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun readRoute(c: Context): List<Pair<Double, Double>> = readRouteFile(routeFile(c))

    fun readRideRoute(c: Context, startMs: Long): List<Pair<Double, Double>> =
        if (startMs <= 0L) emptyList() else readRouteFile(rideRouteFile(c, startMs))

    /** پایان سفر و ذخیره در تاریخچه. اگر سفر فعال نباشد کاری نمی‌کند (امن برای دوبار صدا زدن). */
    fun finishRide(c: Context, endMs: Long) {
        val p = state(c)
        if (!p.getBoolean("active", false)) return
        val start = p.getLong("start_ms", endMs)
        val elapsed = activeElapsed(p, endMs)
        val distance = p.getFloat("distance", 0f)
        val moving = p.getLong("moving", 0L)
        val maxK = p.getFloat("max", 0f)
        val avgK = p.getFloat("average", 0f)
        val mode = p.getString("mode", "bike") ?: "bike"
        val steps = p.getInt("steps", 0)
        p.edit().putBoolean("active", false).putBoolean("paused", false).putLong("elapsed", elapsed).putFloat("speed", 0f).apply()

        if (distance > 0.01f) {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(start))
            val row = listOf(
                stamp,
                String.format(Locale.US, "%.3f", distance),
                elapsed.toString(),
                moving.toString(),
                String.format(Locale.US, "%.1f", maxK),
                String.format(Locale.US, "%.1f", avgK),
                start.toString(),
                mode,
                steps.toString()
            ).joinToString(",")
            val h = c.getSharedPreferences(HISTORY, Context.MODE_PRIVATE)
            val old = h.getString("items", "") ?: ""
            h.edit().putString("items", if (old.isBlank()) row else "$old\n$row").apply()

            val rf = routeFile(c)
            if (rf.exists()) {
                try {
                    routesDir(c).mkdirs()
                    rf.copyTo(rideRouteFile(c, start), overwrite = true)
                } catch (_: Exception) {}
            }
        }
    }

    private fun historyLines(c: Context): List<String> =
        (c.getSharedPreferences(HISTORY, Context.MODE_PRIVATE).getString("items", "") ?: "")
            .lines().filter { it.isNotBlank() }

    /** از قدیمی به جدید. */
    fun loadRides(c: Context): List<Ride> =
        historyLines(c).mapIndexedNotNull { i, line ->
            val p = line.split(",")
            if (p.size < 3) return@mapIndexedNotNull null
            val km = p[1].toDoubleOrNull() ?: 0.0
            val elapsed = p[2].toLongOrNull() ?: 0L
            var avg = p.getOrNull(5)?.toDoubleOrNull() ?: 0.0
            if (avg <= 0.0 && elapsed > 0) avg = km * 3600.0 / elapsed
            Ride(
                index = i,
                startMs = p.getOrNull(6)?.toLongOrNull() ?: 0L,
                stamp = p[0],
                km = km,
                elapsed = elapsed,
                moving = p.getOrNull(3)?.toLongOrNull() ?: 0L,
                maxKmh = p.getOrNull(4)?.toDoubleOrNull() ?: 0.0,
                avgKmh = avg,
                mode = p.getOrNull(7) ?: "bike",
                steps = p.getOrNull(8)?.toIntOrNull() ?: 0
            )
        }

    fun deleteRide(c: Context, index: Int) {
        val lines = historyLines(c).toMutableList()
        if (index !in lines.indices) return
        val removed = lines.removeAt(index)
        val start = removed.split(",").getOrNull(6)?.toLongOrNull() ?: 0L
        if (start > 0L) {
            try { rideRouteFile(c, start).delete() } catch (_: Exception) {}
        }
        c.getSharedPreferences(HISTORY, Context.MODE_PRIVATE)
            .edit().putString("items", lines.joinToString("\n")).apply()
    }

    /** ثانیه‌های سفر بدون زمان مکث‌ها. */
    fun activeElapsed(p: SharedPreferences, now: Long): Long {
        val start = p.getLong("start_ms", now)
        var pausedMs = p.getLong("paused_ms", 0L)
        if (p.getBoolean("paused", false)) pausedMs += now - p.getLong("pause_start", now)
        return ((now - start - pausedMs) / 1000L).coerceAtLeast(0L)
    }

    fun formatDuration(sec: Long): String {
        val s = sec.coerceAtLeast(0L)
        return String.format(Locale("fa"), "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }
}

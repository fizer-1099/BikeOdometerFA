package ir.balasari.bikeodometer

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** نوشتن نقطه‌های مسیر همراه با زمان، و ساخت فایل GPX. هر خط: عرض,طول,زمان‌میلی‌ثانیه */
object RouteLog {
    private fun current(c: Context) = File(c.filesDir, "current_route.csv")
    private fun saved(c: Context, startMs: Long) = File(File(c.filesDir, "routes"), "$startMs.csv")

    fun append(c: Context, lat: Double, lon: Double, timeMs: Long) {
        try { current(c).appendText("$lat,$lon,$timeMs\n") } catch (_: Exception) {}
    }

    fun rideLines(c: Context, startMs: Long): List<String> {
        if (startMs <= 0L) return emptyList()
        val f = saved(c, startMs)
        return try {
            if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun buildGpx(name: String, lines: List<String>): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        iso.timeZone = TimeZone.getTimeZone("UTC")
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"AF Kilometer\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <trk>\n    <name>").append(escape(name)).append("</name>\n    <trkseg>\n")
        for (line in lines) {
            val p = line.split(",")
            if (p.size < 2) continue
            val la = p[0].toDoubleOrNull() ?: continue
            val lo = p[1].toDoubleOrNull() ?: continue
            sb.append("      <trkpt lat=\"").append(la).append("\" lon=\"").append(lo).append("\"")
            val t = p.getOrNull(2)?.toLongOrNull()
            if (t != null && t > 0L) {
                sb.append("><time>").append(iso.format(Date(t))).append("</time></trkpt>\n")
            } else {
                sb.append("/>\n")
            }
        }
        sb.append("    </trkseg>\n  </trk>\n</gpx>\n")
        return sb.toString()
    }
}

package ir.balasari.bikeodometer

import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** نمایش مختصات در قالب‌های رایج. */
object Coords {
    fun decimal(lat: Double, lon: Double): String =
        String.format(Locale.US, "%.6f, %.6f", lat, lon)

    private fun dmsPart(v: Double, pos: String, neg: String): String {
        val hemi = if (v >= 0) pos else neg
        val a = abs(v)
        val d = a.toInt()
        val mFull = (a - d) * 60.0
        val m = mFull.toInt()
        val s = (mFull - m) * 60.0
        return String.format(Locale.US, "%d°%02d′%05.2f″%s", d, m, s, hemi)
    }

    fun dms(lat: Double, lon: Double): String = dmsPart(lat, "N", "S") + " " + dmsPart(lon, "E", "W")

    /** UTM (WGS84)؛ مثال: 39S 535210 E 3950120 N */
    fun utm(lat: Double, lon: Double): String {
        if (lat < -80.0 || lat > 84.0) return "—"
        val a = 6378137.0
        val f = 1.0 / 298.257223563
        val k0 = 0.9996
        val e2 = f * (2.0 - f)
        val ep2 = e2 / (1.0 - e2)
        var zone = ((lon + 180.0) / 6.0).toInt() + 1
        if (zone < 1) zone = 1
        if (zone > 60) zone = 60
        val lon0 = Math.toRadians((zone - 1) * 6.0 - 180.0 + 3.0)
        val phi = Math.toRadians(lat)
        val lam = Math.toRadians(lon)
        val n = a / sqrt(1.0 - e2 * sin(phi).pow(2))
        val tt = tan(phi).pow(2)
        val c = ep2 * cos(phi).pow(2)
        val aa = cos(phi) * (lam - lon0)
        val m = a * ((1.0 - e2 / 4.0 - 3.0 * e2 * e2 / 64.0 - 5.0 * e2.pow(3) / 256.0) * phi -
            (3.0 * e2 / 8.0 + 3.0 * e2 * e2 / 32.0 + 45.0 * e2.pow(3) / 1024.0) * sin(2.0 * phi) +
            (15.0 * e2 * e2 / 256.0 + 45.0 * e2.pow(3) / 1024.0) * sin(4.0 * phi) -
            (35.0 * e2.pow(3) / 3072.0) * sin(6.0 * phi))
        val x = k0 * n * (aa + (1.0 - tt + c) * aa.pow(3) / 6.0 +
            (5.0 - 18.0 * tt + tt * tt + 72.0 * c - 58.0 * ep2) * aa.pow(5) / 120.0) + 500000.0
        var y = k0 * (m + n * tan(phi) * (aa * aa / 2.0 +
            (5.0 - tt + 9.0 * c + 4.0 * c * c) * aa.pow(4) / 24.0 +
            (61.0 - 58.0 * tt + tt * tt + 600.0 * c - 330.0 * ep2) * aa.pow(6) / 720.0))
        if (lat < 0) y += 10000000.0
        val bands = "CDEFGHJKLMNPQRSTUVWX"
        val band = bands[((lat + 80.0) / 8.0).toInt().coerceIn(0, 19)]
        return "" + zone + band + " " + x.roundToLong() + " E " + y.roundToLong() + " N"
    }

    fun link(lat: Double, lon: Double): String {
        val la = String.format(Locale.US, "%.6f", lat)
        val lo = String.format(Locale.US, "%.6f", lon)
        return "https://www.openstreetmap.org/?mlat=" + la + "&mlon=" + lo + "#map=17/" + la + "/" + lo
    }

    private val names = arrayOf(
        "شمال", "شمال‌شرق", "شرق", "جنوب‌شرق", "جنوب", "جنوب‌غرب", "غرب", "شمال‌غرب"
    )

    /** نام جهت فارسی برای زاویه‌ی درجه (۰ = شمال). */
    fun directionName(deg: Double): String {
        val d = ((deg % 360.0) + 360.0) % 360.0
        return names[((d + 22.5) / 45.0).toInt() % 8]
    }
}

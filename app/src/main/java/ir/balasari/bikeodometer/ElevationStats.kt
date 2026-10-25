package ir.balasari.bikeodometer

import android.location.Location
import java.io.File

/** مسافت، صعود و نزول (با هموارسازی و آستانه‌ی ۳ متر برای حذف نویز GPS) و داده‌ی نمودار ارتفاع. */
object ElevationStats {
    class Result(
        val distanceM: Double,
        val ascent: Double,
        val descent: Double,
        val min: Double,
        val max: Double,
        val hasEle: Boolean
    )

    fun parseLines(lines: List<String>): List<TrackFile.Pt> {
        val out = ArrayList<TrackFile.Pt>()
        for (l in lines) {
            val p = l.split(",")
            if (p.size < 2) continue
            val la = p[0].toDoubleOrNull() ?: continue
            val lo = p[1].toDoubleOrNull() ?: continue
            val t = p.getOrNull(2)?.toLongOrNull() ?: 0L
            val e = p.getOrNull(3)?.toDoubleOrNull() ?: Double.NaN
            out.add(TrackFile.Pt(la, lo, e, t))
        }
        return out
    }

    private fun dist(a: TrackFile.Pt, b: TrackFile.Pt): Double {
        val r = FloatArray(1)
        Location.distanceBetween(a.lat, a.lon, b.lat, b.lon, r)
        return r[0].toDouble()
    }

    private fun smooth(e: DoubleArray): DoubleArray {
        val out = DoubleArray(e.size)
        for (i in e.indices) {
            var s = 0.0
            var n = 0
            for (k in (i - 2)..(i + 2)) {
                if (k >= 0 && k < e.size) {
                    s += e[k]
                    n++
                }
            }
            out[i] = s / n
        }
        return out
    }

    fun compute(pts: List<TrackFile.Pt>): Result {
        var d = 0.0
        for (i in 1 until pts.size) d += dist(pts[i - 1], pts[i])
        val eles = pts.map { it.ele }.filter { !it.isNaN() }
        if (eles.size < 2) return Result(d, 0.0, 0.0, 0.0, 0.0, false)
        val sm = smooth(eles.toDoubleArray())
        var asc = 0.0
        var desc = 0.0
        var ref = sm[0]
        val threshold = 3.0
        for (v in sm) {
            if (v - ref > threshold) {
                asc += v - ref
                ref = v
            } else if (ref - v > threshold) {
                desc += ref - v
                ref = v
            }
        }
        return Result(d, asc, desc, sm.minOrNull() ?: 0.0, sm.maxOrNull() ?: 0.0, true)
    }

    fun fromRouteFile(f: File): Result {
        return try {
            if (!f.exists()) Result(0.0, 0.0, 0.0, 0.0, 0.0, false)
            else compute(parseLines(f.readLines()))
        } catch (_: Exception) {
            Result(0.0, 0.0, 0.0, 0.0, 0.0, false)
        }
    }

    /** (فاصله‌ی تجمعی به متر، ارتفاع هموار) برای حداکثر حدود ۴۰۰ نقطه. */
    fun profile(pts: List<TrackFile.Pt>): Pair<DoubleArray, DoubleArray> {
        val ds = ArrayList<Double>()
        val es = ArrayList<Double>()
        var cum = 0.0
        for (i in pts.indices) {
            if (i > 0) cum += dist(pts[i - 1], pts[i])
            if (!pts[i].ele.isNaN()) {
                ds.add(cum)
                es.add(pts[i].ele)
            }
        }
        if (es.size < 2) return Pair(DoubleArray(0), DoubleArray(0))
        val sm = smooth(es.toDoubleArray())
        val step = Math.max(1, sm.size / 400)
        val od = ArrayList<Double>()
        val oe = ArrayList<Double>()
        var i = 0
        while (i < sm.size) {
            od.add(ds[i])
            oe.add(sm[i])
            i += step
        }
        if (i - step != sm.size - 1) {
            od.add(ds[sm.size - 1])
            oe.add(sm[sm.size - 1])
        }
        return Pair(od.toDoubleArray(), oe.toDoubleArray())
    }
}

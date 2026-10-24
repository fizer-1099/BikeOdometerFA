package ir.balasari.bikeodometer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** نقطه‌های ذخیره‌شده؛ در فایل waypoints.json داخل حافظه‌ی برنامه نگه داشته می‌شوند. */
object Waypoints {
    data class Wp(
        val id: Long,
        val name: String,
        val note: String,
        val lat: Double,
        val lon: Double,
        val alt: Double
    )

    private fun file(c: Context) = File(c.filesDir, "waypoints.json")

    fun parse(json: String): List<Wp> {
        val arr = JSONArray(json)
        val out = ArrayList<Wp>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val lat = o.optDouble("lat")
            val lon = o.optDouble("lon")
            if (lat.isNaN() || lon.isNaN()) continue
            out.add(Wp(o.optLong("id"), o.optString("name"), o.optString("note"), lat, lon, o.optDouble("alt", 0.0)))
        }
        return out
    }

    fun load(c: Context): List<Wp> {
        val f = file(c)
        if (!f.exists()) return emptyList()
        return try { parse(f.readText(Charsets.UTF_8)) } catch (_: Exception) { emptyList() }
    }

    fun rawJson(c: Context): String? {
        val f = file(c)
        return try { if (f.exists()) f.readText(Charsets.UTF_8) else null } catch (_: Exception) { null }
    }

    private fun save(c: Context, list: List<Wp>) {
        val arr = JSONArray()
        for (w in list) {
            arr.put(
                JSONObject().put("id", w.id).put("name", w.name).put("note", w.note)
                    .put("lat", w.lat).put("lon", w.lon).put("alt", w.alt)
            )
        }
        try { file(c).writeText(arr.toString(), Charsets.UTF_8) } catch (_: Exception) {}
    }

    fun add(c: Context, name: String, note: String, lat: Double, lon: Double, alt: Double = 0.0): Wp {
        val list = load(c).toMutableList()
        var id = System.currentTimeMillis()
        while (list.any { it.id == id }) id++
        val w = Wp(id, name, note, lat, lon, alt)
        list.add(w)
        save(c, list)
        return w
    }

    fun update(c: Context, w: Wp) {
        save(c, load(c).map { if (it.id == w.id) w else it })
    }

    fun delete(c: Context, id: Long) {
        save(c, load(c).filter { it.id != id })
    }

    /** نقطه‌های تازه (با شناسه‌ی جدید) را اضافه می‌کند و تعدادشان را برمی‌گرداند. */
    fun mergeFromJson(c: Context, json: String): Int {
        return try {
            val incoming = parse(json)
            val list = load(c).toMutableList()
            val ids = list.map { it.id }.toHashSet()
            var added = 0
            for (w in incoming) {
                if (w.id !in ids) {
                    list.add(w)
                    ids.add(w.id)
                    added++
                }
            }
            if (added > 0) save(c, list)
            added
        } catch (_: Exception) {
            0
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun toGpx(list: List<Wp>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"AF Kilometer\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        for (w in list) {
            sb.append("  <wpt lat=\"").append(w.lat).append("\" lon=\"").append(w.lon).append("\">\n")
            if (w.alt != 0.0) sb.append("    <ele>").append(w.alt).append("</ele>\n")
            sb.append("    <name>").append(esc(w.name)).append("</name>\n")
            if (w.note.isNotBlank()) sb.append("    <desc>").append(esc(w.note)).append("</desc>\n")
            sb.append("  </wpt>\n")
        }
        sb.append("</gpx>\n")
        return sb.toString()
    }
}

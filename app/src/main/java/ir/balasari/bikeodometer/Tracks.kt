package ir.balasari.bikeodometer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** مسیرهای واردشده از GPX/KML؛ هر مسیر یک فایل CSV (عرض,طول,زمان,ارتفاع) و یک فهرست JSON. */
object Tracks {
    data class Meta(
        val id: Long,
        val name: String,
        val km: Double,
        val ascent: Double,
        val descent: Double,
        val count: Int
    )

    private fun dir(c: Context) = File(c.filesDir, "tracks").apply { mkdirs() }
    private fun idx(c: Context) = File(dir(c), "index.json")
    private fun csv(c: Context, id: Long) = File(dir(c), "$id.csv")

    /** تازه‌ترین اول */
    fun list(c: Context): List<Meta> = oldestFirst(c).reversed()

    private fun oldestFirst(c: Context): List<Meta> {
        val f = idx(c)
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONArray(f.readText(Charsets.UTF_8))
            val out = ArrayList<Meta>()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(
                    Meta(
                        o.getLong("id"), o.optString("name"), o.optDouble("km"),
                        o.optDouble("asc"), o.optDouble("desc"), o.optInt("n")
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveIndex(c: Context, metas: List<Meta>) {
        val a = JSONArray()
        for (m in metas) {
            a.put(
                JSONObject().put("id", m.id).put("name", m.name).put("km", m.km)
                    .put("asc", m.ascent).put("desc", m.descent).put("n", m.count)
            )
        }
        try { idx(c).writeText(a.toString(), Charsets.UTF_8) } catch (_: Exception) {}
    }

    fun add(c: Context, name: String, pts: List<TrackFile.Pt>): Meta {
        val existing = oldestFirst(c)
        var id = System.currentTimeMillis()
        while (existing.any { it.id == id }) id++
        val sb = StringBuilder()
        for (p in pts) {
            sb.append(p.lat).append(',').append(p.lon).append(',').append(p.time).append(',')
            if (!p.ele.isNaN()) sb.append(p.ele)
            sb.append('\n')
        }
        csv(c, id).writeText(sb.toString(), Charsets.UTF_8)
        val st = ElevationStats.compute(pts)
        val m = Meta(id, name, st.distanceM / 1000.0, st.ascent, st.descent, pts.size)
        saveIndex(c, existing + m)
        return m
    }

    fun lines(c: Context, id: Long): List<String> =
        try { csv(c, id).readLines().filter { it.isNotBlank() } } catch (_: Exception) { emptyList() }

    fun load(c: Context, id: Long): List<TrackFile.Pt> = ElevationStats.parseLines(lines(c, id))

    fun delete(c: Context, id: Long) {
        try { csv(c, id).delete() } catch (_: Exception) {}
        saveIndex(c, oldestFirst(c).filter { it.id != id })
    }
}

package ir.balasari.bikeodometer

import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * جستجوی مکان (Nominatim) و مسیر (سرورهای FOSSGIS برای OpenStreetMap).
 * این سرویس‌های عمومی محدودیت دارند: حداکثر ۱ درخواست در ثانیه و استفاده‌ی معقول.
 * آدرس‌ها را اینجا نگه داشتیم تا اگر لازم شد، فقط همین‌جا عوض شوند.
 */
object Navigator {
    private const val NOMINATIM = "https://nominatim.openstreetmap.org"
    private const val ROUTER = "https://routing.openstreetmap.de"
    private const val USER_AGENT = "fizer.ir/1.0 (info@fizer.ir)"

    data class Place(val name: String, val lat: Double, val lon: Double)
    class Route(val points: List<GeoPoint>, val distanceM: Double, val durationS: Double)

    private var lastCall = 0L

    @Synchronized
    private fun throttle() {
        val wait = 1100L - (System.currentTimeMillis() - lastCall)
        if (wait > 0L) Thread.sleep(wait)
        lastCall = System.currentTimeMillis()
    }

    private fun get(url: String): String {
        throttle()
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "GET"
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept-Language", "fa,en")
            if (c.responseCode != 200) throw RuntimeException("HTTP " + c.responseCode)
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** این تابع شبکه را صدا می‌زند؛ حتماً از ترد پس‌زمینه استفاده شود. */
    fun search(query: String, nearLat: Double?, nearLon: Double?): List<Place> {
        var url = NOMINATIM + "/search?format=jsonv2&limit=6&accept-language=fa&q=" +
            URLEncoder.encode(query, "UTF-8")
        if (nearLat != null && nearLon != null) {
            val d = 0.5
            // فقط برای اولویت‌دادن به اطراف موقعیت فعلی؛ نتایج را محدود نمی‌کند
            url += "&viewbox=" + (nearLon - d) + "," + (nearLat + d) + "," + (nearLon + d) + "," + (nearLat - d)
        }
        val arr = JSONArray(get(url))
        val out = ArrayList<Place>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            var name = o.optString("display_name", "")
            if (name.length > 90) name = name.substring(0, 90) + "…"
            val lat = o.optString("lat").toDoubleOrNull() ?: continue
            val lon = o.optString("lon").toDoubleOrNull() ?: continue
            out.add(Place(name, lat, lon))
        }
        return out
    }

    /** profile: routed-car، routed-bike یا routed-foot */
    fun route(profile: String, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Route? {
        val url = ROUTER + "/" + profile + "/route/v1/driving/" +
            fromLon + "," + fromLat + ";" + toLon + "," + toLat +
            "?overview=full&geometries=geojson"
        val root = JSONObject(get(url))
        if (root.optString("code") != "Ok") return null
        val r = root.getJSONArray("routes").getJSONObject(0)
        val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
        val pts = ArrayList<GeoPoint>()
        for (i in 0 until coords.length()) {
            val c = coords.getJSONArray(i)
            pts.add(GeoPoint(c.getDouble(1), c.getDouble(0)))
        }
        return Route(pts, r.getDouble("distance"), r.getDouble("duration"))
    }
}

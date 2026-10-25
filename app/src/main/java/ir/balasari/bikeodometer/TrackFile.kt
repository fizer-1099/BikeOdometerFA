package ir.balasari.bikeodometer

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.OffsetDateTime

/** خواندن فایل‌های GPX (مسیر، رشته‌مسیر، نقطه) و KML (Point، LineString، gx:Track). */
object TrackFile {
    /** ele برابر NaN یعنی ارتفاع نیست؛ time صفر یعنی زمان نیست. */
    data class Pt(val lat: Double, val lon: Double, val ele: Double, val time: Long)

    class Track(val name: String, val pts: List<Pt>)
    class Wpt(val name: String, val note: String, val lat: Double, val lon: Double, val ele: Double)
    class Parsed(val tracks: List<Track>, val waypoints: List<Wpt>)

    private fun parseTime(s: String): Long = try {
        OffsetDateTime.parse(s).toInstant().toEpochMilli()
    } catch (_: Exception) {
        0L
    }

    private fun kmlTuple(s: String): Pt? {
        val p = s.split(",")
        if (p.size < 2) return null
        val lon = p[0].trim().toDoubleOrNull() ?: return null
        val lat = p[1].trim().toDoubleOrNull() ?: return null
        val ele = p.getOrNull(2)?.trim()?.toDoubleOrNull() ?: Double.NaN
        return Pt(lat, lon, ele, 0L)
    }

    fun parse(stream: InputStream): Parsed {
        val p = Xml.newPullParser()
        p.setInput(stream, null)
        val tracks = ArrayList<Track>()
        val wpts = ArrayList<Wpt>()

        var inTrk = false
        var trkName = ""
        var trkPts = ArrayList<Pt>()
        var inPt = false
        var ptLat = 0.0
        var ptLon = 0.0
        var ptEle = Double.NaN
        var ptTime = 0L
        var inWpt = false
        var wLat = 0.0
        var wLon = 0.0
        var wName = ""
        var wDesc = ""
        var wEle = Double.NaN
        var inPlace = false
        var plName = ""
        var gxPts = ArrayList<Pt>()
        var text = StringBuilder()
        val ws = Regex("\\s+")

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    when (p.name.substringAfter(':')) {
                        "trk", "rte" -> {
                            inTrk = true
                            trkName = ""
                            trkPts = ArrayList()
                        }
                        "trkpt", "rtept" -> {
                            inPt = true
                            ptLat = p.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                            ptLon = p.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                            ptEle = Double.NaN
                            ptTime = 0L
                        }
                        "wpt" -> {
                            inWpt = true
                            wLat = p.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                            wLon = p.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                            wName = ""
                            wDesc = ""
                            wEle = Double.NaN
                        }
                        "Placemark" -> {
                            inPlace = true
                            plName = ""
                        }
                        "Track" -> gxPts = ArrayList()
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> text.append(p.text)
                XmlPullParser.END_TAG -> {
                    val t = text.toString().trim()
                    when (p.name.substringAfter(':')) {
                        "name" -> when {
                            inPt -> {}
                            inWpt -> wName = t
                            inTrk -> trkName = t
                            inPlace -> plName = t
                        }
                        "desc" -> if (inWpt) wDesc = t
                        "ele" -> {
                            val v = t.toDoubleOrNull() ?: Double.NaN
                            if (inPt) ptEle = v else if (inWpt) wEle = v
                        }
                        "time" -> if (inPt) ptTime = parseTime(t)
                        "trkpt", "rtept" -> {
                            if (inPt) trkPts.add(Pt(ptLat, ptLon, ptEle, ptTime))
                            inPt = false
                        }
                        "trk", "rte" -> {
                            if (trkPts.size >= 2) tracks.add(Track(trkName, trkPts))
                            inTrk = false
                        }
                        "wpt" -> {
                            wpts.add(Wpt(wName, wDesc, wLat, wLon, wEle))
                            inWpt = false
                        }
                        "coordinates" -> if (inPlace) {
                            val tuples = t.split(ws).filter { it.isNotBlank() }.mapNotNull { kmlTuple(it) }
                            if (tuples.size == 1) {
                                wpts.add(Wpt(plName, "", tuples[0].lat, tuples[0].lon, tuples[0].ele))
                            } else if (tuples.size >= 2) {
                                tracks.add(Track(plName, tuples))
                            }
                        }
                        "coord" -> {
                            val q = t.split(ws)
                            if (q.size >= 2) {
                                val lon = q[0].toDoubleOrNull()
                                val lat = q[1].toDoubleOrNull()
                                val ele = q.getOrNull(2)?.toDoubleOrNull() ?: Double.NaN
                                if (lon != null && lat != null) gxPts.add(Pt(lat, lon, ele, 0L))
                            }
                        }
                        "Track" -> {
                            if (gxPts.size >= 2) tracks.add(Track(plName, gxPts))
                            gxPts = ArrayList()
                        }
                        "Placemark" -> inPlace = false
                    }
                }
            }
            ev = p.next()
        }
        return Parsed(tracks, wpts)
    }
}

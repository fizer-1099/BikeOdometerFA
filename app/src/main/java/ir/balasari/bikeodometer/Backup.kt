package ir.balasari.bikeodometer

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** پشتیبان کامل (تاریخچه + مسیر هر سفر + نقطه‌های ذخیره‌شده) در یک فایل ZIP، و بازیابی بدون پاک کردن داده‌های فعلی. */
object Backup {
    private const val HISTORY = "ride_history"

    fun create(c: Context): File? {
        return try {
            val raw = c.getSharedPreferences(HISTORY, Context.MODE_PRIVATE).getString("items", "") ?: ""
            val routes = File(c.filesDir, "routes").listFiles()
                ?.filter { it.isFile && it.name.endsWith(".csv") } ?: emptyList()
            val wp = Waypoints.rawJson(c)
            if (raw.isBlank() && routes.isEmpty() && wp == null) return null
            val dir = File(c.cacheDir, "exports").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            val out = File(dir, "fkilometer_backup_$stamp.zip")
            ZipOutputStream(FileOutputStream(out)).use { z ->
                z.putNextEntry(ZipEntry("history.txt"))
                z.write(raw.toByteArray(Charsets.UTF_8))
                z.closeEntry()
                if (wp != null) {
                    z.putNextEntry(ZipEntry("waypoints.json"))
                    z.write(wp.toByteArray(Charsets.UTF_8))
                    z.closeEntry()
                }
                for (f in routes) {
                    z.putNextEntry(ZipEntry("routes/" + f.name))
                    f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                }
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    /** تعداد موردهای تازه (سفر + نقطه) را برمی‌گرداند؛ -1 یعنی فایل معتبر نبود. */
    fun restore(c: Context, uri: Uri): Int {
        return try {
            var incoming = ""
            var wpJson = ""
            val routeFiles = HashMap<String, ByteArray>()
            val stream = c.contentResolver.openInputStream(uri) ?: return -1
            stream.use { ins ->
                ZipInputStream(ins).use { z ->
                    var e = z.nextEntry
                    var count = 0
                    while (e != null && count < 5000) {
                        count++
                        val name = e.name
                        if (name == "history.txt") {
                            incoming = String(z.readBytes(), Charsets.UTF_8)
                        } else if (name == "waypoints.json") {
                            wpJson = String(z.readBytes(), Charsets.UTF_8)
                        } else if (Regex("routes/[0-9]+\\.csv").matches(name)) {
                            routeFiles[name.substring(7)] = z.readBytes()
                        }
                        z.closeEntry()
                        e = z.nextEntry
                    }
                }
            }
            if (incoming.isBlank() && routeFiles.isEmpty() && wpJson.isBlank()) return -1

            val prefs = c.getSharedPreferences(HISTORY, Context.MODE_PRIVATE)
            val current = (prefs.getString("items", "") ?: "").lines().filter { it.isNotBlank() }.toMutableList()
            val knownStarts = HashSet<Long>()
            for (l in current) {
                val s = l.split(",").getOrNull(6)?.toLongOrNull() ?: 0L
                if (s > 0L) knownStarts.add(s)
            }
            val knownRows = current.toHashSet()
            var added = 0
            for (l in incoming.lines()) {
                if (l.isBlank()) continue
                val p = l.split(",")
                if (p.size < 3) continue
                val start = p.getOrNull(6)?.toLongOrNull() ?: 0L
                if (start > 0L && start in knownStarts) continue
                if (start <= 0L && l in knownRows) continue
                current.add(l)
                if (start > 0L) knownStarts.add(start)
                added++
            }
            if (added > 0) {
                current.sortWith(
                    compareBy({ it.split(",").getOrNull(6)?.toLongOrNull() ?: 0L }, { it })
                )
                prefs.edit().putString("items", current.joinToString("\n")).apply()
            }
            val dir = File(c.filesDir, "routes").apply { mkdirs() }
            for ((n, bytes) in routeFiles) {
                val f = File(dir, n)
                if (!f.exists()) f.writeBytes(bytes)
            }
            if (wpJson.isNotBlank()) added += Waypoints.mergeFromJson(c, wpJson)
            added
        } catch (_: Exception) {
            -1
        }
    }
}

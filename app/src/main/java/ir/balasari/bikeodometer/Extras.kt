package ir.balasari.bikeodometer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

/** اشتراک مسیر یک سفر به‌صورت GPX (قابل واردکردن در Strava، Komoot، Google Earth و...). */
fun Activity.shareGpx(r: Ride) {
    val lines = RouteLog.rideLines(this, r.startMs)
    if (lines.isEmpty()) {
        Toast.makeText(this, "مسیر این سفر ذخیره نشده است.", Toast.LENGTH_LONG).show()
        return
    }
    val gpx = RouteLog.buildGpx("اف کیلومتر " + r.stamp, lines)
    val fileName = "ride_" + r.stamp.replace(" ", "_").replace(":", "-") + ".gpx"
    val uri = FileShare.createUri(this, fileName, gpx, false)
    if (uri == null) {
        Toast.makeText(this, "ساخت فایل GPX ناموفق بود.", Toast.LENGTH_LONG).show()
        return
    }
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/gpx+xml")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(send, "اشتراک مسیر سفر"))
}

/** صفحه‌ی اطلاعات برنامه؛ از آنجا می‌شود به بخش باتری رفت و محدودیت را برداشت. */
fun Activity.openBatterySettings() {
    try {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    } catch (_: Exception) {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
        }
    }
}

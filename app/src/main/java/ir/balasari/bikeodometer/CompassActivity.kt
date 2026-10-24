package ir.balasari.bikeodometer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * قطب‌نما و مختصات زنده: جهت نسبت به شمال حقیقی، مختصات (اعشاری، درجه‌دقیقه‌ثانیه، UTM)،
 * ارتفاع، و جهت و فاصله تا مقصدی که روی نقشه انتخاب شده.
 */
class CompassActivity : Activity(), SensorEventListener {
    private val fa = Locale("fa")
    private val bg = Color.rgb(8, 10, 14)
    private val panel = Color.rgb(22, 27, 34)
    private val grey = Color.rgb(140, 150, 165)
    private val green = Color.rgb(0, 230, 118)
    private val permCode = 77

    private lateinit var compass: CompassView
    private lateinit var headingText: TextView
    private lateinit var destText: TextView
    private lateinit var coordText: TextView
    private lateinit var infoText: TextView
    private lateinit var calText: TextView
    private lateinit var fmtButton: Button

    private var sm: SensorManager? = null
    private var locator: SafeLocation? = null
    private var listening = false
    private var last: Location? = null
    private var heading = 0f
    private var haveHeading = false
    private var declination = 0f
    private var fmt = 0

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val l = result.lastLocation ?: return
            last = l
            declination = try {
                GeomagneticField(
                    l.latitude.toFloat(), l.longitude.toFloat(), l.altitude.toFloat(), System.currentTimeMillis()
                ).declination
            } catch (_: Exception) {
                0f
            }
            refreshCoords()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radiusDp).toFloat()
        return g
    }

    private fun label(t: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = sizeSp
        v.setTextColor(color)
        v.gravity = Gravity.CENTER
        if (bold) v.typeface = Typeface.DEFAULT_BOLD
        return v
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locator = SafeLocation(this)

        val dm = resources.displayMetrics
        val dial = (min(dm.widthPixels, dm.heightPixels) * 0.82f).toInt()

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(bg)
        scroll.fitsSystemWindows = true
        scroll.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(dp(16), dp(12), dp(16), dp(16))
        scroll.addView(
            col,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        col.addView(label("قطب‌نما", 18f, Color.WHITE, true))

        compass = CompassView(this)
        val clp = LinearLayout.LayoutParams(dial, dial)
        clp.topMargin = dp(8)
        col.addView(compass, clp)

        headingText = label("—", 28f, Color.WHITE, true)
        col.addView(headingText)

        calText = label("دقت قطب‌نما پایین است؛ گوشی را چند بار به شکل عدد ۸ بچرخان.", 12f, Color.rgb(255, 171, 0))
        calText.visibility = View.GONE
        col.addView(calText)

        destText = label("", 14f, green)
        destText.setPadding(0, dp(8), 0, 0)
        col.addView(destText)

        coordText = label("در انتظار GPS…", 17f, Color.WHITE, true)
        coordText.setPadding(0, dp(14), 0, 0)
        coordText.setOnClickListener { cycleFormat() }
        col.addView(coordText)

        infoText = label("", 12f, grey)
        infoText.setPadding(0, dp(4), 0, dp(10))
        col.addView(infoText)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        fun addButton(text: String, onClick: () -> Unit): Button {
            val b = Button(this)
            b.text = text
            b.textSize = 12f
            b.minWidth = 0
            b.minHeight = 0
            b.setTextColor(Color.WHITE)
            b.background = rounded(panel, 12)
            b.setPadding(dp(2), 0, dp(2), 0)
            val lp = LinearLayout.LayoutParams(0, dp(48), 1f)
            lp.setMargins(dp(3), 0, dp(3), 0)
            b.layoutParams = lp
            b.setOnClickListener { onClick() }
            row.addView(b)
            return b
        }
        addButton("کپی") { copyCoords() }
        addButton("اشتراک") { shareCoords() }
        addButton("ذخیره‌ی نقطه") { saveWaypoint() }
        fmtButton = addButton("قالب: اعشاری") { cycleFormat() }
        col.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        setContentView(scroll)
        refreshHeading()
        refreshCoords()
    }

    // ------------------------------------------------------------------ حسگر جهت

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val r = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(r, event.values)
        val o = FloatArray(3)
        SensorManager.getOrientation(r, o)
        val az: Float
        if (abs(o[1]) > 0.8f) {
            // گوشی تقریباً عمودی است (مثل نگه داشتن جلوی صورت): جهت پشت گوشی
            val r2 = FloatArray(9)
            SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_X, SensorManager.AXIS_Z, r2)
            val o2 = FloatArray(3)
            SensorManager.getOrientation(r2, o2)
            az = Math.toDegrees(o2[0].toDouble()).toFloat()
        } else {
            az = Math.toDegrees(o[0].toDouble()).toFloat()
        }
        val target = (((az + declination) % 360f) + 360f) % 360f
        if (!haveHeading) {
            heading = target
            haveHeading = true
        } else {
            var d = target - heading
            while (d > 180f) d -= 360f
            while (d < -180f) d += 360f
            heading = (((heading + d * 0.2f) % 360f) + 360f) % 360f
        }
        refreshHeading()
        val l = last
        if (l != null) updateDest(l)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            calText.visibility = if (accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) View.VISIBLE else View.GONE
        }
    }

    private fun refreshHeading() {
        compass.heading = heading
        compass.invalidate()
        if (!haveHeading) {
            headingText.text = "—"
            return
        }
        val deg = Math.round(heading).toInt() % 360
        headingText.text = String.format(fa, "%d", deg) + "°  " + Coords.directionName(heading.toDouble())
    }

    // ------------------------------------------------------------------ مختصات

    private fun altitudeOf(l: Location): Double =
        if (Build.VERSION.SDK_INT >= 34 && l.hasMslAltitude()) l.mslAltitudeMeters else l.altitude

    private fun coordString(l: Location): String = when (fmt) {
        0 -> Coords.decimal(l.latitude, l.longitude)
        1 -> Coords.dms(l.latitude, l.longitude)
        else -> Coords.utm(l.latitude, l.longitude)
    }

    private fun distText(m: Double): String {
        return if (Units.isMiles(this)) {
            Units.distText(this, (m / 1000.0).toFloat(), 2) + " " + Units.distUnit(this)
        } else if (m < 1000.0) {
            String.format(fa, "%d", Math.round(m).toInt()) + " متر"
        } else {
            Units.distText(this, (m / 1000.0).toFloat(), 2) + " " + Units.distWord(this)
        }
    }

    private fun refreshCoords() {
        val l = last
        if (l == null) {
            coordText.text = "در انتظار GPS…"
            infoText.text = ""
            destText.text = ""
            return
        }
        coordText.text = coordString(l)
        val sb = StringBuilder()
        if (l.hasAltitude()) {
            sb.append("ارتفاع: ").append(String.format(fa, "%d", Math.round(altitudeOf(l)).toInt())).append(" متر")
        }
        if (l.hasAccuracy()) {
            if (sb.isNotEmpty()) sb.append("  •  ")
            sb.append("دقت: ±").append(String.format(fa, "%d", Math.round(l.accuracy).toInt())).append(" متر")
        }
        if (l.hasSpeed()) {
            if (sb.isNotEmpty()) sb.append("  •  ")
            sb.append(Units.speedText(this, l.speed * 3.6f)).append(" ").append(Units.speedUnit(this))
        }
        infoText.text = sb.toString()
        updateDest(l)
    }

    private fun updateDest(l: Location) {
        val s = RideStore.settings(this)
        val la = s.getString("dest_lat", null)?.toDoubleOrNull()
        val lo = s.getString("dest_lon", null)?.toDoubleOrNull()
        if (la == null || lo == null) {
            compass.destBearing = null
            destText.text = "مقصدی انتخاب نشده (روی نقشه لمس طولانی بزن)"
            return
        }
        val dest = Location("dest")
        dest.latitude = la
        dest.longitude = lo
        var b = l.bearingTo(dest)
        if (b < 0f) b += 360f
        compass.destBearing = b
        val name = (s.getString("dest_name", "") ?: "").take(30)
        val deg = Math.round(b).toInt() % 360
        var turn = ""
        if (haveHeading) {
            val rel = ((b - heading + 540f) % 360f) - 180f
            turn = if (abs(rel) < 10f) {
                "  •  مستقیم جلوی توست"
            } else {
                "  •  " + String.format(fa, "%d", Math.round(abs(rel)).toInt()) + "° به " + (if (rel > 0f) "راست" else "چپ")
            }
        }
        destText.text = "🎯 " + name + Char(10) + distText(l.distanceTo(dest).toDouble()) +
            "  •  جهت " + String.format(fa, "%d", deg) + "° " + Coords.directionName(b.toDouble()) + turn
        compass.invalidate()
    }

    private fun cycleFormat() {
        fmt = (fmt + 1) % 3
        fmtButton.text = "قالب: " + arrayOf("اعشاری", "درجه‌دقیقه", "UTM")[fmt]
        refreshCoords()
    }

    private fun copyCoords() {
        val l = last
        if (l == null) {
            toast("هنوز موقعیتی پیدا نشده.")
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("coordinates", coordString(l)))
        toast("مختصات کپی شد.")
    }

    private fun shareCoords() {
        val l = last
        if (l == null) {
            toast("هنوز موقعیتی پیدا نشده.")
            return
        }
        val nl = Char(10).toString()
        var text = Coords.decimal(l.latitude, l.longitude) + nl + Coords.dms(l.latitude, l.longitude) +
            nl + "UTM: " + Coords.utm(l.latitude, l.longitude)
        if (l.hasAltitude()) text += nl + "Altitude: " + Math.round(altitudeOf(l)) + " m"
        text += nl + Coords.link(l.latitude, l.longitude)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, "اشتراک مختصات"))
    }

    private fun saveWaypoint() {
        val l = last
        if (l == null) {
            toast("هنوز موقعیتی پیدا نشده.")
            return
        }
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)
        val n = EditText(this)
        n.setText("نقطه " + (Waypoints.load(this).size + 1))
        n.setSingleLine(true)
        n.setTextColor(Color.WHITE)
        val t = EditText(this)
        t.hint = "یادداشت (اختیاری)"
        t.setTextColor(Color.WHITE)
        t.setHintTextColor(grey)
        box.addView(n)
        box.addView(t)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("ذخیره‌ی نقطه")
            .setView(box)
            .setPositiveButton("ذخیره") { _, _ ->
                val name = n.text.toString().trim().ifEmpty { "نقطه" }
                Waypoints.add(this, name, t.text.toString().trim(), l.latitude, l.longitude, altitudeOf(l))
                toast("نقطه ذخیره شد.")
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    // ------------------------------------------------------------------ موقعیت و چرخه‌ی عمر

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun startLocation() {
        if (listening) return
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                permCode
            )
            return
        }
        try {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
            locator?.requestLocationUpdates(req, callback, mainLooper)
            listening = true
        } catch (_: SecurityException) {
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permCode) startLocation()
    }

    override fun onResume() {
        super.onResume()
        val s = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (s == null) {
            headingText.text = "این گوشی حسگر قطب‌نما ندارد"
        } else {
            sm?.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)
        }
        startLocation()
    }

    override fun onPause() {
        try { sm?.unregisterListener(this) } catch (_: Exception) {}
        if (listening) {
            try { locator?.removeLocationUpdates(callback) } catch (_: Exception) {}
            listening = false
        }
        super.onPause()
    }
}

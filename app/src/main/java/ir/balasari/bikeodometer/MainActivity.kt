package ir.balasari.bikeodometer

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val permRequest = 410
    private val fa = Locale("fa")
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refreshUi()
            handler.postDelayed(this, 1000L)
        }
    }

    private lateinit var gauge: GaugeView
    private lateinit var map: MapView
    private lateinit var gaugePage: View
    private lateinit var mapPage: View
    private lateinit var tabGauge: Button
    private lateinit var tabMap: Button
    private lateinit var rideButton: Button
    private lateinit var statusText: TextView
    private lateinit var clockText: TextView
    private lateinit var limitText: TextView
    private lateinit var limitSwitch: Button
    private lateinit var viewBanner: View
    private lateinit var viewBannerText: TextView

    private lateinit var homePage: View
    private lateinit var tripsPage: View
    private lateinit var recordsPage: View
    private lateinit var settingsPage: View
    private lateinit var navBar: LinearLayout
    private lateinit var tripsList: ListView
    private lateinit var tripsEmpty: TextView
    private lateinit var recordsContainer: LinearLayout
    private lateinit var settingsContainer: LinearLayout
    private lateinit var statsRow: LinearLayout

    private lateinit var distTitle: TextView
    private lateinit var avgTitle: TextView
    private lateinit var maxTitle: TextView
    private lateinit var distanceValueText: TextView
    private lateinit var avgValueText: TextView
    private lateinit var maxValueText: TextView
    private lateinit var tripTimeValue: TextView
    private val tabNavs = ArrayList<TextView>()

    private val routeLine = Polyline()
    private var marker: Marker? = null
    private var liveCallback: LocationCallback? = null
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private var currentTab = 0
    private var viewingRide: Ride? = null
    private var followMe = true
    private var centered = false
    private var lastShownPoints = -1
    private var uiActive: Boolean? = null
    private var recoveryDialogShown = false

    // پوسته‌ی انتخابی (۰ سیستم، ۱ روشن، ۲ تیره) را روی Configuration اعمال می‌کند.
    override fun attachBaseContext(base: Context) {
        val mode = RideStore.settings(base).getInt("theme", 0)
        if (mode == 0) {
            super.attachBaseContext(base)
            return
        }
        val cfg = android.content.res.Configuration(base.resources.configuration)
        val night = if (mode == 2) android.content.res.Configuration.UI_MODE_NIGHT_YES
        else android.content.res.Configuration.UI_MODE_NIGHT_NO
        cfg.uiMode = (cfg.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        super.attachBaseContext(base.createConfigurationContext(cfg))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)

        gauge = findViewById(R.id.gauge)
        map = findViewById(R.id.map)
        gaugePage = findViewById(R.id.gaugePage)
        mapPage = findViewById(R.id.mapPage)
        tabGauge = findViewById(R.id.tabGauge)
        tabMap = findViewById(R.id.tabMap)
        rideButton = findViewById(R.id.rideButton)
        statusText = findViewById(R.id.statusText)
        clockText = findViewById(R.id.clockText)
        limitText = findViewById(R.id.limitText)
        limitSwitch = findViewById(R.id.limitSwitch)
        viewBanner = findViewById(R.id.viewBanner)
        viewBannerText = findViewById(R.id.viewBannerText)
        homePage = findViewById(R.id.homePage)
        tripsPage = findViewById(R.id.tripsPage)
        recordsPage = findViewById(R.id.recordsPage)
        settingsPage = findViewById(R.id.settingsPage)
        navBar = findViewById(R.id.navBar)
        tripsList = findViewById(R.id.tripsList)
        tripsEmpty = findViewById(R.id.tripsEmpty)
        recordsContainer = findViewById(R.id.recordsContainer)
        settingsContainer = findViewById(R.id.settingsContainer)
        statsRow = findViewById(R.id.statsRow)

        // کارت‌های آمار (راست به چپ: مسافت، میانگین، حداکثر، زمان)
        val d = addStat("مسافت", 18f)
        distTitle = d[0]
        distanceValueText = d[1]
        val a = addStat("میانگین", 18f)
        avgTitle = a[0]
        avgValueText = a[1]
        val m = addStat("حداکثر", 18f)
        maxTitle = m[0]
        maxValueText = m[1]
        val t = addStat("زمان سفر", 15f)
        tripTimeValue = t[1]

        // نقشه
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(18.0)
        map.controller.setCenter(GeoPoint(35.6892, 51.3890))
        routeLine.outlinePaint.color = Color.rgb(25, 118, 210)
        routeLine.outlinePaint.strokeWidth = 8f
        map.overlays.add(routeLine)
        marker = Marker(map).apply {
            title = "مکان فعلی"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        marker?.let { map.overlays.add(it) }
        map.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_MOVE) followMe = false
            false
        }
        findViewById<Button>(R.id.followButton).setOnClickListener {
            if (viewingRide != null) closeView()
            followMe = true
            marker?.position?.let { map.controller.animateTo(it) }
        }
        findViewById<Button>(R.id.viewBannerClose).setOnClickListener { closeView() }

        // دکمه‌های زوم نقشه
        map.setBuiltInZoomControls(false)
        val zoomBox = LinearLayout(this)
        zoomBox.orientation = LinearLayout.VERTICAL
        for ((label, step) in listOf("+" to 1, "−" to -1)) {
            val zb = Button(this)
            zb.text = label
            zb.textSize = 22f
            zb.minWidth = 0
            zb.minHeight = 0
            zb.setBackgroundResource(R.drawable.bg_primary)
            zb.setTextColor(Color.WHITE)
            val zbp = LinearLayout.LayoutParams(dp(48), dp(48))
            zbp.setMargins(0, 0, 0, dp(8))
            zb.layoutParams = zbp
            zb.setOnClickListener { if (step > 0) map.controller.zoomIn() else map.controller.zoomOut() }
            zoomBox.addView(zb)
        }
        val zoomLp = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        zoomLp.gravity = Gravity.CENTER_VERTICAL or Gravity.END
        zoomLp.setMargins(dp(12), 0, dp(12), 0)
        (mapPage as android.widget.FrameLayout).addView(zoomBox, zoomLp)

        tabGauge.setOnClickListener { showPage(false) }
        tabMap.setOnClickListener { showPage(true) }

        findViewById<Button>(R.id.limitMinus).setOnClickListener { changeLimit(-5) }
        findViewById<Button>(R.id.limitPlus).setOnClickListener { changeLimit(5) }
        limitSwitch.setOnClickListener {
            val s = RideStore.settings(this)
            s.edit().putBoolean("limit_on", !s.getBoolean("limit_on", false)).apply()
            updateLimitUi()
        }

        rideButton.setOnClickListener {
            if (RideStore.state(this).getBoolean("active", false)) confirmStop() else onStartClicked()
        }
        findViewById<Button>(R.id.exportButton).setOnClickListener { exportCsv() }

        // نوار پایین (راست به چپ)
        tabNavs.add(addNav("🏠\nاصلی") { showTab(0) })
        addNav("🔆\nHUD") { startActivity(Intent(this, HudActivity::class.java)) }
        addNav("📱\nهولدر") { startActivity(Intent(this, HolderActivity::class.java)) }
        tabNavs.add(addNav("🧭\nسفرها") { showTab(1) })
        tabNavs.add(addNav("🏆\nرکوردها") { showTab(2) })
        tabNavs.add(addNav("⚙️\nتنظیمات") { showTab(3) })

        updateUnitLabels()
        updateLimitUi()
        showTab(savedInstanceState?.getInt("tab", 0) ?: 0)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { backAction() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", currentTab)
    }

    // ------------------------------------------------------------------ کمکی‌ها

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun col(id: Int) = ContextCompat.getColor(this, id)

    private fun isDark() =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun dlg() = AlertDialog.Builder(
        this,
        if (isDark()) android.R.style.Theme_Material_Dialog_Alert
        else android.R.style.Theme_Material_Light_Dialog_Alert
    )

    private fun tv(t: String, sizeSp: Float, colorRes: Int, bold: Boolean = false, topDp: Int = 0): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = sizeSp
        v.setTextColor(col(colorRes))
        if (bold) v.typeface = Typeface.DEFAULT_BOLD
        if (topDp > 0) v.setPadding(0, dp(topDp), 0, 0)
        return v
    }

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setBackgroundResource(R.drawable.bg_card)
        c.setPadding(dp(16), dp(14), dp(16), dp(14))
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, 0, 0, dp(10))
        c.layoutParams = lp
        return c
    }

    private fun addStat(title: String, sizeSp: Float): Array<TextView> {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.setBackgroundResource(R.drawable.bg_card)
        box.setPadding(dp(6), dp(8), dp(6), dp(8))
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.setMargins(dp(4), dp(4), dp(4), dp(4))
        box.layoutParams = lp
        val titleView = tv(title, 12f, R.color.text_secondary)
        val valueView = tv("—", sizeSp, R.color.accent, true, 4)
        box.addView(titleView)
        box.addView(valueView)
        statsRow.addView(box)
        return arrayOf(titleView, valueView)
    }

    private fun addNav(label: String, onClick: () -> Unit): TextView {
        val v = TextView(this)
        v.text = label
        v.textSize = 11f
        v.gravity = Gravity.CENTER
        v.setPadding(dp(2), dp(8), dp(2), dp(8))
        v.setTextColor(col(R.color.text_secondary))
        v.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        v.setOnClickListener { onClick() }
        navBar.addView(v)
        return v
    }

    private fun updateUnitLabels() {
        distTitle.text = "مسافت (" + Units.distUnit(this) + ")"
        avgTitle.text = "میانگین (" + Units.speedUnit(this) + ")"
        maxTitle.text = "حداکثر (" + Units.speedUnit(this) + ")"
        gauge.unitLabel = Units.speedUnit(this)
        gauge.invalidate()
    }

    // ------------------------------------------------------------------ ناوبری

    private fun showTab(i: Int) {
        currentTab = i
        homePage.visibility = if (i == 0) View.VISIBLE else View.GONE
        tripsPage.visibility = if (i == 1) View.VISIBLE else View.GONE
        recordsPage.visibility = if (i == 2) View.VISIBLE else View.GONE
        settingsPage.visibility = if (i == 3) View.VISIBLE else View.GONE
        for ((idx, v) in tabNavs.withIndex()) {
            v.setTextColor(col(if (idx == i) R.color.accent else R.color.text_secondary))
        }
        if (i == 1) refreshTrips()
        if (i == 2) refreshRecords()
        if (i == 3) refreshSettings()
    }

    private fun backAction() {
        when {
            viewingRide != null -> closeView()
            currentTab != 0 -> showTab(0)
            else -> finish()
        }
    }

    private fun showPage(showMap: Boolean) {
        gaugePage.visibility = if (showMap) View.GONE else View.VISIBLE
        mapPage.visibility = if (showMap) View.VISIBLE else View.GONE
        tabGauge.setBackgroundResource(if (showMap) R.drawable.bg_tab else R.drawable.bg_primary)
        tabMap.setBackgroundResource(if (showMap) R.drawable.bg_primary else R.drawable.bg_tab)
        tabGauge.setTextColor(if (showMap) col(R.color.tab_text) else Color.WHITE)
        tabMap.setTextColor(if (showMap) Color.WHITE else col(R.color.tab_text))
        if (showMap) {
            lastShownPoints = -1
            refreshRoute()
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            viewingRide != null -> closeView()
            currentTab != 0 -> showTab(0)
            else -> super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------ صفحه‌ی اصلی

    private fun updateLimitUi() {
        val s = RideStore.settings(this)
        val limit = s.getInt("limit", 30)
        val on = s.getBoolean("limit_on", false)
        limitText.text = "سقف مجاز: " + Units.speedText(this, limit.toFloat(), 0) + " " + Units.speedUnit(this)
        limitSwitch.text = if (on) "🔔 روشن" else "🔕 خاموش"
        gauge.limit = if (on) Units.speedValue(this, limit.toFloat()) else 0f
    }

    private fun changeLimit(delta: Int) {
        val s = RideStore.settings(this)
        val f = Units.factor(this)
        val shown = (s.getInt("limit", 30) * f).roundToInt() + delta
        val kmh = (shown.coerceAtLeast(3) / f).roundToInt().coerceIn(3, 300)
        s.edit().putInt("limit", kmh).apply()
        updateLimitUi()
    }

    private fun applyRideUi(active: Boolean) {
        rideButton.text = if (active) "پایان سفر" else "شروع سفر"
        rideButton.setBackgroundResource(if (active) R.drawable.bg_stop else R.drawable.bg_primary)
        statusText.text = if (active) "● در حال ثبت" else "● متوقف"
        statusText.setTextColor(col(if (active) R.color.ok else R.color.text_secondary))
    }

    private fun refreshUi() {
        val p = RideStore.state(this)
        val active = p.getBoolean("active", false)
        if (uiActive != active) {
            uiActive = active
            applyRideUi(active)
            if (!active) {
                if (currentTab == 1) refreshTrips()
                if (currentTab == 2) refreshRecords()
            }
        }
        clockText.text = SimpleDateFormat("HH:mm:ss", fa).format(Date())

        gauge.setSpeed(if (active) Units.speedValue(this, p.getFloat("speed", 0f)) else 0f)

        val now = System.currentTimeMillis()
        val elapsed = if (active) (now - p.getLong("start_ms", now)) / 1000L else p.getLong("elapsed", 0L)
        tripTimeValue.text = RideStore.formatDuration(elapsed)
        maxValueText.text = Units.speedText(this, p.getFloat("max", 0f))
        avgValueText.text = Units.speedText(this, p.getFloat("average", 0f))
        distanceValueText.text = Units.distText(this, p.getFloat("distance", 0f))

        if (homePage.visibility == View.VISIBLE && mapPage.visibility == View.VISIBLE) refreshRoute()
        checkStaleRide(p)
    }

    private fun refreshRoute() {
        if (viewingRide != null) return
        val count = RideStore.state(this).getInt("points", 0)
        if (count == lastShownPoints) return
        lastShownPoints = count
        val pts = RideStore.readRoute(this).map { GeoPoint(it.first, it.second) }
        routeLine.setPoints(pts)
        map.invalidate()
    }

    // ------------------------------------------------------------------ سفر

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun onStartClicked() {
        val missing = ArrayList<String>()
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                missing.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), permRequest)
            return
        }
        beginRide(false)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != permRequest) return
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            startLive()
            beginRide(false)
        } else {
            Toast.makeText(this, "برای ثبت مسیر، مجوز «مکان دقیق» لازم است.", Toast.LENGTH_LONG).show()
        }
    }

    private fun beginRide(resume: Boolean) {
        if (resume) {
            RideStore.state(this).edit().putLong("last_update", System.currentTimeMillis()).apply()
        } else {
            RideStore.reset(this)
        }
        recoveryDialogShown = false
        lastShownPoints = -1
        if (viewingRide != null) closeView()
        ContextCompat.startForegroundService(
            this,
            Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_START)
        )
        Toast.makeText(this, if (resume) "ثبت مسیر ادامه یافت" else "ثبت مسیر شروع شد", Toast.LENGTH_SHORT).show()
        refreshUi()
    }

    private fun confirmStop() {
        dlg()
            .setTitle("پایان سفر")
            .setMessage("سفر ذخیره و تمام شود؟")
            .setPositiveButton("پایان") { _, _ -> stopRide() }
            .setNegativeButton("ادامه", null)
            .show()
    }

    private fun stopRide() {
        if (isServiceRunning()) {
            startService(Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_STOP))
        } else {
            RideStore.finishRide(this, System.currentTimeMillis())
        }
        Toast.makeText(this, "سفر پایان یافت", Toast.LENGTH_SHORT).show()
        handler.postDelayed({ refreshUi() }, 400L)
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        return am.getRunningServices(50).any { it.service.className == TrackingService::class.java.name }
    }

    private fun checkStaleRide(p: SharedPreferences) {
        if (recoveryDialogShown || !p.getBoolean("active", false)) return
        val ref = max(p.getLong("start_ms", 0L), p.getLong("last_update", 0L))
        if (System.currentTimeMillis() - ref < 20_000L) return
        if (isServiceRunning()) return
        recoveryDialogShown = true
        dlg()
            .setTitle("سفر ناتمام")
            .setMessage("ثبت سفر قبلی ناخواسته متوقف شده. ادامه می‌دهی یا سفر ذخیره و تمام شود؟")
            .setCancelable(false)
            .setPositiveButton("ادامه سفر") { _, _ -> beginRide(true) }
            .setNegativeButton("ذخیره و پایان") { _, _ ->
                RideStore.finishRide(this, ref)
                recoveryDialogShown = false
            }
            .show()
    }

    // ------------------------------------------------------------------ سفرها

    private fun refreshTrips() {
        val rides = RideStore.loadRides(this).reversed()
        tripsEmpty.visibility = if (rides.isEmpty()) View.VISIBLE else View.GONE
        tripsList.adapter = object : ArrayAdapter<Ride>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, rides
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val r = rides[position]
                val km = Units.distText(this@MainActivity, r.km.toFloat())
                val dur = RideStore.formatDuration(r.elapsed)
                val t1 = v.findViewById<TextView>(android.R.id.text1)
                t1.text = km + " " + Units.distWord(this@MainActivity) + "  •  " + dur
                t1.setTextColor(col(R.color.text_primary))
                var sub = r.stamp
                if (r.avgKmh > 0) sub += "  •  میانگین " + Units.speedText(this@MainActivity, r.avgKmh.toFloat())
                if (r.maxKmh > 0) sub += " | حداکثر " + Units.speedText(this@MainActivity, r.maxKmh.toFloat())
                val t2 = v.findViewById<TextView>(android.R.id.text2)
                t2.text = sub
                t2.setTextColor(col(R.color.text_secondary))
                return v
            }
        }
        tripsList.setOnItemClickListener { _, _, pos, _ -> showRideMenu(rides[pos]) }
    }

    private fun showRideMenu(r: Ride) {
        dlg()
            .setTitle(r.stamp)
            .setItems(arrayOf("نمایش مسیر روی نقشه", "اشتراک مسیر (GPX)", "حذف سفر")) { _, which ->
                when (which) { 0 -> viewRide(r); 1 -> shareGpx(r); else -> confirmDelete(r) }
            }
            .show()
    }

    private fun confirmDelete(r: Ride) {
        dlg()
            .setTitle("حذف سفر")
            .setMessage("سفر ${r.stamp} حذف شود؟")
            .setPositiveButton("حذف") { _, _ ->
                RideStore.deleteRide(this, r.index)
                refreshTrips()
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun viewRide(r: Ride) {
        val pts = RideStore.readRideRoute(this, r.startMs).map { GeoPoint(it.first, it.second) }
        if (pts.isEmpty()) {
            Toast.makeText(this, "مسیر این سفر ذخیره نشده است.", Toast.LENGTH_LONG).show()
            return
        }
        viewingRide = r
        followMe = false
        routeLine.setPoints(pts)
        viewBannerText.text = "سفر " + r.stamp
        viewBanner.visibility = View.VISIBLE
        showTab(0)
        showPage(true)
        map.post {
            try {
                if (pts.size >= 2) map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts), false, 100)
                else map.controller.setCenter(pts[0])
            } catch (_: Exception) {
            }
            map.invalidate()
        }
    }

    private fun closeView() {
        viewingRide = null
        viewBanner.visibility = View.GONE
        followMe = true
        lastShownPoints = -1
        refreshRoute()
    }

    /** CSV همیشه با واحد متریک (کیلومتر) خروجی می‌گیرد. */
    private fun exportCsv() {
        val rides = RideStore.loadRides(this)
        if (rides.isEmpty()) {
            Toast.makeText(this, "هنوز گزارشی برای خروجی وجود ندارد.", Toast.LENGTH_SHORT).show()
            return
        }
        val sb = StringBuilder("تاریخ,مسافت_کیلومتر,مدت_ثانیه,زمان_حرکت_ثانیه,حداکثر_سرعت,میانگین_سرعت\n")
        for (r in rides) {
            sb.append(r.stamp).append(',')
                .append(String.format(Locale.US, "%.3f", r.km)).append(',')
                .append(r.elapsed).append(',')
                .append(r.moving).append(',')
                .append(String.format(Locale.US, "%.1f", r.maxKmh)).append(',')
                .append(String.format(Locale.US, "%.1f", r.avgKmh)).append('\n')
        }
        val uri = FileShare.createUri(this, "rides.csv", sb.toString())
        if (uri != null) {
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/csv")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "ارسال گزارش سفرها"))
        } else {
            Toast.makeText(this, "ساخت فایل گزارش ناموفق بود.", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ رکوردها

    private fun addRecord(title: String, value: String, sub: String?) {
        val c = card()
        c.addView(tv(title, 13f, R.color.text_secondary))
        c.addView(tv(value, 22f, R.color.accent, true))
        if (sub != null) c.addView(tv(sub, 12f, R.color.text_secondary))
        recordsContainer.addView(c)
    }

    private fun refreshRecords() {
        recordsContainer.removeAllViews()
        val rides = RideStore.loadRides(this)
        if (rides.isEmpty()) {
            addRecord("هنوز رکوردی ثبت نشده", "—", "اولین سفرت رو شروع کن")
            return
        }
        val sUnit = Units.speedUnit(this)
        val dUnit = Units.distUnit(this)
        val fastest = rides.maxByOrNull { it.maxKmh }
        if (fastest != null && fastest.maxKmh > 0) {
            addRecord("🚀 بیشترین سرعت", Units.speedText(this, fastest.maxKmh.toFloat()) + " " + sUnit, fastest.stamp)
        }
        val longest = rides.maxByOrNull { it.km }
        if (longest != null) {
            addRecord("📏 طولانی‌ترین سفر", Units.distText(this, longest.km.toFloat()) + " " + dUnit, longest.stamp)
        }
        val longestTime = rides.maxByOrNull { it.elapsed }
        if (longestTime != null) {
            addRecord("⏱ بیشترین زمان سواری", RideStore.formatDuration(longestTime.elapsed), longestTime.stamp)
        }
        val bestAvg = rides.maxByOrNull { it.avgKmh }
        if (bestAvg != null && bestAvg.avgKmh > 0) {
            addRecord("⚡ بهترین میانگین سرعت", Units.speedText(this, bestAvg.avgKmh.toFloat()) + " " + sUnit, bestAvg.stamp)
        }
        val totalKm = rides.sumOf { it.km }
        val totalSec = rides.sumOf { it.elapsed }
        addRecord(
            "📊 مجموع مسافت",
            Units.distText(this, totalKm.toFloat(), 1) + " " + dUnit,
            String.format(fa, "%d", rides.size) + " سفر"
        )
        addRecord("🕒 مجموع زمان سواری", RideStore.formatDuration(totalSec), null)
    }

    // ------------------------------------------------------------------ تنظیمات

    private fun segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(10), 0, 0)
        for ((i, label) in options.withIndex()) {
            val b = Button(this)
            b.text = label
            b.minWidth = 0
            b.minHeight = 0
            val sel = i == selected
            b.setBackgroundResource(if (sel) R.drawable.bg_primary else R.drawable.bg_tab)
            b.setTextColor(if (sel) Color.WHITE else col(R.color.tab_text))
            val lp = LinearLayout.LayoutParams(0, dp(44), 1f)
            lp.setMargins(dp(3), 0, dp(3), 0)
            b.layoutParams = lp
            b.setOnClickListener { onSelect(i) }
            row.addView(b)
        }
        return row
    }

    private fun onUnitsChanged() {
        updateUnitLabels()
        updateLimitUi()
        refreshUi()
        refreshSettings()
    }

    private fun setFloating(on: Boolean) {
        if (on && !FloatingOverlay.canDraw(this)) {
            Toast.makeText(
                this,
                "مجوز «نمایش روی برنامه‌های دیگر» را بده، بعد برگرد و دوباره روشن کن.",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        RideStore.settings(this).edit().putBoolean("float_on", on).apply()
        if (!on) FloatingOverlay.hide(this)
        refreshSettings()
    }

    private fun refreshSettings() {
        settingsContainer.removeAllViews()
        val s = RideStore.settings(this)

        // واحد
        val units = card()
        units.addView(tv("واحد سرعت و مسافت", 14f, R.color.text_primary, true))
        units.addView(segmented(listOf("کیلومتر (km)", "مایل (mi)"), if (Units.isMiles(this)) 1 else 0) { i ->
            s.edit().putString("units", if (i == 1) "mi" else "km").apply()
            onUnitsChanged()
        })
        units.addView(tv("فایل CSV همیشه با کیلومتر ذخیره می‌شود.", 11f, R.color.text_secondary, false, 8))
        settingsContainer.addView(units)

        // پوسته
        val theme = card()
        theme.addView(tv("پوسته", 14f, R.color.text_primary, true))
        theme.addView(segmented(listOf("سیستم", "روشن", "تیره"), s.getInt("theme", 0)) { i ->
            s.edit().putInt("theme", i).apply()
            recreate()
        })
        settingsContainer.addView(theme)

        // نمایش شناور
        val fl = card()
        fl.addView(tv("نمایش شناور", 14f, R.color.text_primary, true))
        fl.addView(
            tv(
                "وقتی سفر فعال است و برنامه جلوی چشم نیست، سرعت روی بقیه‌ی برنامه‌ها نشان داده می‌شود. " +
                    "با کشیدن جابه‌جا می‌شود و با لمس، برنامه باز می‌شود.",
                12f, R.color.text_secondary, false, 6
            )
        )
        val on = s.getBoolean("float_on", false) && FloatingOverlay.canDraw(this)
        fl.addView(segmented(listOf("خاموش", "روشن"), if (on) 1 else 0) { i -> setFloating(i == 1) })
        settingsContainer.addView(fl)

        // باتری
        val bat = card()
        bat.addView(tv("محدودیت باتری", 14f, R.color.text_primary, true))
        bat.addView(
            tv(
                "اگر ثبت مسیر با صفحه‌ی خاموش قطع می‌شود: اطلاعات برنامه ← باتری ← «بدون محدودیت».",
                12f, R.color.text_secondary, false, 6
            )
        )
        bat.addView(segmented(listOf("باز کردن اطلاعات برنامه"), -1) { openBatterySettings() })
        settingsContainer.addView(bat)

        // درباره
        val about = card()
        about.addView(tv("اف کیلومتر", 20f, R.color.accent, true))
        about.addView(tv("کیلومترشمار و ثبت مسیر دوچرخه", 13f, R.color.text_secondary, false, 2))
        about.addView(tv("سازنده: نوید بلانیان", 15f, R.color.text_primary, true, 12))
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            null
        }
        if (ver != null) about.addView(tv("نسخه $ver", 12f, R.color.text_secondary, false, 4))
        settingsContainer.addView(about)
    }

    // ------------------------------------------------------------------ مکان زنده (فقط وقتی برنامه جلوی چشم است)

    private fun startLive() {
        if (liveCallback != null) return
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L).build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val l = result.lastLocation ?: return
                val pt = GeoPoint(l.latitude, l.longitude)
                marker?.position = pt
                if (!centered && viewingRide == null) {
                    map.controller.setCenter(pt)
                    centered = true
                } else if (followMe && viewingRide == null && mapPage.visibility == View.VISIBLE) {
                    map.controller.animateTo(pt)
                }
                map.invalidate()
            }
        }
        try {
            fused.requestLocationUpdates(request, cb, mainLooper)
            liveCallback = cb
        } catch (_: SecurityException) {
        }
    }

    private fun stopLive() {
        liveCallback?.let {
            try { fused.removeLocationUpdates(it) } catch (_: Exception) {}
        }
        liveCallback = null
    }

    // ------------------------------------------------------------------ چرخه‌ی عمر

    override fun onResume() {
        super.onResume()
        FloatingOverlay.onAppVisibility(this, true)
        map.onResume()
        startLive()
        lastShownPoints = -1
        if (currentTab == 3) refreshSettings()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        FloatingOverlay.onAppVisibility(this, false)
        stopLive()
        map.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        map.onDetach()
        super.onDestroy()
    }
}

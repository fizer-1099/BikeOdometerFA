package ir.balasari.bikeodometer

import android.content.Context

/** انواع سفر و تنظیمات مخصوص هرکدام. */
object Modes {
    class Mode(
        val id: String,
        val label: String,
        val emoji: String,
        val hasSteps: Boolean,
        val maxJumpMs: Double,     // بیشتر از این سرعت (m/s) بین دو نقطه یعنی پرش GPS
        val maxSpeedKmh: Double,   // سقف سرعت قابل قبول برای «حداکثر سرعت»
        val scales: FloatArray,    // مقیاس‌های سرعت‌سنج (بر ۶ بخش‌پذیر)
        val defaultLimit: Int,     // سقف مجاز پیش‌فرض (km/h)
        val strideM: Double        // طول گام برای تخمین قدم‌ها وقتی حسگر در دسترس نیست
    )

    val all: List<Mode> = listOf(
        Mode("bike", "دوچرخه", "🚲", false, 30.0, 120.0, floatArrayOf(60f, 120f, 180f, 240f), 30, 0.0),
        Mode("walk", "پیاده‌روی", "🚶", true, 8.0, 25.0, floatArrayOf(12f, 24f, 60f), 7, 0.75),
        Mode("moto", "موتور", "🏍", false, 80.0, 250.0, floatArrayOf(60f, 120f, 180f, 240f), 60, 0.0),
        Mode("car", "ماشین", "🚗", false, 100.0, 300.0, floatArrayOf(120f, 180f, 240f, 300f), 90, 0.0)
    )

    fun byId(id: String?): Mode = all.firstOrNull { it.id == id } ?: all[0]
    fun currentId(c: Context): String = RideStore.settings(c).getString("mode", "bike") ?: "bike"
    fun currentMode(c: Context): Mode = byId(currentId(c))
}

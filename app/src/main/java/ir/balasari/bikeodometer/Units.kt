package ir.balasari.bikeodometer

import android.content.Context
import java.util.Locale

/** تبدیل نمایشی km و km/h به mi و mph. داده‌های ذخیره‌شده همیشه کیلومتر هستند. */
object Units {
    private const val KM_TO_MI = 0.621371

    fun isMiles(c: Context): Boolean = RideStore.settings(c).getString("units", "km") == "mi"
    fun factor(c: Context): Double = if (isMiles(c)) KM_TO_MI else 1.0
    fun speedUnit(c: Context): String = if (isMiles(c)) "mph" else "km/h"
    fun distUnit(c: Context): String = if (isMiles(c)) "mi" else "km"
    fun distWord(c: Context): String = if (isMiles(c)) "مایل" else "کیلومتر"

    fun speedValue(c: Context, kmh: Float): Float = (kmh * factor(c)).toFloat()

    fun speedText(c: Context, kmh: Float, decimals: Int = 1): String =
        String.format(Locale("fa"), "%." + decimals + "f", kmh * factor(c))

    fun distText(c: Context, km: Float, decimals: Int = 2): String =
        String.format(Locale("fa"), "%." + decimals + "f", km * factor(c))
}

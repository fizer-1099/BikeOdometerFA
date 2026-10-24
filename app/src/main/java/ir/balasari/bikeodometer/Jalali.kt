package ir.balasari.bikeodometer

/** تبدیل تاریخ میلادی به شمسی برای نمایش. داده‌ها همچنان میلادی ذخیره می‌شوند. */
object Jalali {
    fun fromGregorian(gy: Int, gm: Int, gd: Int): IntArray {
        val gdm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + (365 * gy) + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) + ((gy2 + 399) / 400) + gd + gdm[gm - 1]
        var jy = -1595 + (33 * (days / 12053))
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm: Int
        val jd: Int
        if (days < 186) {
            jm = 1 + days / 31
            jd = 1 + days % 31
        } else {
            jm = 7 + (days - 186) / 30
            jd = 1 + (days - 186) % 30
        }
        return intArrayOf(jy, jm, jd)
    }

    fun faDigits(s: String): String =
        s.map { if (it in '0'..'9') '۰' + (it - '0') else it }.joinToString("")

    /** مثال: "2026-10-12 14:20" ← "۱۴۰۵/۰۷/۲۰  ۱۴:۲۰" */
    fun stamp(s: String): String {
        return try {
            val parts = s.trim().split(" ")
            val d = parts[0].split("-")
            val j = fromGregorian(d[0].toInt(), d[1].toInt(), d[2].toInt())
            val date = String.format(java.util.Locale.US, "%04d/%02d/%02d", j[0], j[1], j[2])
            val time = if (parts.size > 1) "  " + parts[1] else ""
            faDigits(date + time)
        } catch (_: Exception) {
            s
        }
    }
}

val Ride.shamsi: String get() = Jalali.stamp(stamp)

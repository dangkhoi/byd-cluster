package com.byd.clusternav

import java.util.Locale

/**
 * Parse/format khoảng cách + ETA từ chuỗi notification. TÁCH khỏi ClusterBroadcaster để builder
 * (AmapFrameBuilder) và parser dùng chung 1 nguồn. Output PHẢI giữ y hệt bản cũ — đây là các giá trị
 * đi thẳng lên cụm (SEG_REMAIN_DIS, ROUTE_REMAIN_*, *_AUTO). Không đổi regex/format.
 */
object NavParse {

    /** Làm TRÒN cự ly hiển thị theo bước theo độ xa (chống nhảy từng-mét NHƯNG đủ MỊN để đếm ngược mượt).
     *  Bước nhỏ lại (so bản cũ 50/100m) → số trượt đều thay vì "đứng im rồi nhảy cục". */
    fun quantizeDisplay(m: Int): Int = when {
        m < 0 -> m
        m >= 1000 -> (m / 100) * 100   // >1km: bước 100m = 0.1km (đúng độ phân giải chuỗi "x.x km")
        m >= 300 -> (m / 25) * 25       // 300m-1km: bước 25m (cũ 50m → nhảy to)
        m >= 100 -> (m / 10) * 10       // 100-300m: bước 10m
        else -> (m / 5) * 5             // <100m: bước 5m
    }

    /** "250 m" / "1.2 km" / "1,2 km" -> mét (int). -1 nếu không đọc được. */
    fun parseMeters(s: String): Int {
        val m = Regex("""(\d+([.,]\d+)?)\s*(km|m)""", RegexOption.IGNORE_CASE).find(s) ?: return -1
        val v = m.groupValues[1].replace(",", ".").toDoubleOrNull() ?: return -1
        return if (m.groupValues[3].equals("km", true)) (v * 1000).toInt() else v.toInt()
    }

    /** ETA "10:32 · 5.2 km · 8 phút" -> (mét còn lại, giây còn lại). -1 nếu thiếu. */
    fun parseEta(s: String): Pair<Int, Int> {
        val dis = Regex("""(\d+([.,]\d+)?)\s*km""", RegexOption.IGNORE_CASE).find(s)
            ?.let { (it.groupValues[1].replace(",", ".").toDoubleOrNull() ?: 0.0) * 1000 }?.toInt() ?: -1
        val min = Regex("""(\d+)\s*(phút|min|分)""", RegexOption.IGNORE_CASE).find(s)
            ?.groupValues?.get(1)?.toIntOrNull()
        val hr = Regex("""(\d+)\s*(giờ|h|hour|时)""", RegexOption.IGNORE_CASE).find(s)
            ?.groupValues?.get(1)?.toIntOrNull()
        val sec = when {
            min != null || hr != null -> ((hr ?: 0) * 3600) + ((min ?: 0) * 60)
            else -> -1
        }
        return dis to sec
    }

    // Khớp DashCast formatMeters/formatSeconds — giữ y output.
    fun formatMeters(m: Int): String =
        if (m >= 1000) String.format(Locale.US, "%.1f km", m / 1000.0f) else "$m m"

    fun formatSeconds(total: Int): String {
        val mins = total / 60; val h = mins / 60; val mm = mins % 60
        return if (h > 0) "${h}h ${mm}m" else "$mm min"
    }

    // ── Thời gian/ETA cho cụm: firmware AmapService.parseTime() CHỈ parse được TIẾNG TRUNG.
    //    Remaining-time: token 天(ngày)/时(giờ)/分(phút).  Arrival: "预计 ... HH:MM 到达" (今天=hôm nay).
    //    -> phải format kiểu này thì INSTRUMENT_NAVI_TRIP_INFO_HOUR/MINUTE mới được ghi.

    /** Thời gian CÒN LẠI -> "1时20分" / "8分" / "2天3时5分" (parseTime đọc 天/时/分). "-1" nếu thiếu. */
    fun formatRemainTimeCn(total: Int): String {
        if (total < 0) return "-1"
        val mins = total / 60
        val d = mins / (60 * 24)
        val h = (mins / 60) % 24
        val m = mins % 60
        val sb = StringBuilder()
        if (d > 0) sb.append("${d}天")
        if (h > 0) sb.append("${h}时")
        sb.append("${m}分")          // luôn có 分 để parseTime có token hợp lệ
        return sb.toString()
    }

    /** Lấy giờ tới "HH:MM" từ chuỗi ETA notification ("10:32 · 5.2 km · 8 phút"). null nếu không có. */
    fun extractArrivalClock(s: String): String? =
        Regex("""\b(\d{1,2}):(\d{2})\b""").find(s)?.let {
            val h = it.groupValues[1].toInt(); val m = it.groupValues[2].toInt()
            if (h in 0..23 && m in 0..59) String.format(Locale.US, "%d:%02d", h, m) else null
        }

    /** "10:32" -> "预计今天10:32到达" (parseTime cần 预计 + 到达 + ":"). */
    fun formatEtaCn(clock: String): String = "预计今天${clock}到达"
}

package com.byd.clusternav

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.util.Locale

/**
 * Log cự ly nav ra CSV (pull về phân tích vụ "nhảy 120→20m"): mỗi frame ghi cự ly THÔ GMaps gửi,
 * cự ly đã nội suy (project), cự ly hiển thị (quantize), tốc độ + closingRate, đường + key maneuver.
 * Mở lazy, flush mỗi dòng. Pull: adb pull /sdcard/Android/data/com.byd.clusternav/files/nav_log_*.csv
 */
object NavDistanceLog {
    private var w: BufferedWriter? = null
    @Volatile var path: String = ""; private set

    @Synchronized
    fun ensure(ctx: Context) {
        if (w != null) return
        runCatching {
            val f = File(ctx.applicationContext.getExternalFilesDir(null), "nav_log_${System.currentTimeMillis()}.csv")
            w = f.bufferedWriter().also {
                it.appendLine("t_ms,rawGmaps_m,projected_m,display_m,closing_mps,speed_mps,road,key")
            }
            path = f.absolutePath
        }
    }

    @Synchronized
    fun record(rawM: Int, projected: Int, display: Int, closing: Double, speed: Double, road: String, key: String) {
        val ww = w ?: return
        runCatching {
            val L = Locale.US
            val safeRoad = road.replace(',', ' ').replace('\n', ' ')
            val safeKey = key.replace(',', ' ').replace('\n', ' ')
            ww.append("${System.currentTimeMillis()},$rawM,$projected,$display,")
            ww.append("${String.format(L, "%.1f", closing)},${String.format(L, "%.1f", speed)},")
            ww.appendLine("$safeRoad,$safeKey")
            ww.flush()
        }
    }
}

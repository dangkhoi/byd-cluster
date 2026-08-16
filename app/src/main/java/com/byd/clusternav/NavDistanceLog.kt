package com.byd.clusternav

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Log cự ly nav ra CSV (pull về phân tích vụ "nhảy 120→20m"): mỗi frame ghi cự ly THÔ GMaps gửi,
 * cự ly đã nội suy (project), cự ly hiển thị (quantize), tốc độ + closingRate, đường + key maneuver.
 * Pull: adb pull /sdcard/Android/data/com.byd.clusternav/files/nav_log_*.csv
 *
 * D3 (closeout 1.28): (a) GATED behind [NavLog.verbose] (MẶC ĐỊNH TẮT — tuning xong) và (b) mở file + ghi +
 * flush chạy trên một single-thread daemon Executor, KHÔNG trên main thread (record được gọi từ
 * ClusterBroadcaster.sendFrame ~4×/s trên main). Định dạng CSV GIỮ NGUYÊN khi bật. Executor lazy → không tạo
 * thread cho tới khi verbose bật.
 */
object NavDistanceLog {
    private var w: BufferedWriter? = null
    @Volatile var path: String = ""; private set

    private val io: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "navdistancelog").apply { isDaemon = true } }
    }

    fun ensure(ctx: Context) {
        if (!NavLog.verbose) return
        io.execute { ensureLocked(ctx) }
    }

    private fun ensureLocked(ctx: Context) {
        if (w != null) return
        runCatching {
            val f = File(ctx.applicationContext.getExternalFilesDir(null), "nav_log_${System.currentTimeMillis()}.csv")
            w = f.bufferedWriter().also {
                it.appendLine("t_ms,rawGmaps_m,projected_m,display_m,closing_mps,speed_mps,screenRead_m,screenRead_age_ms,road,key")
            }
            path = f.absolutePath
        }
    }

    fun record(
        rawM: Int, projected: Int, display: Int, closing: Double, speed: Double,
        screenReadM: Int, screenReadAgeMs: Long, road: String, key: String,
    ) {
        if (!NavLog.verbose) return
        val t = System.currentTimeMillis()
        io.execute { recordLocked(t, rawM, projected, display, closing, speed, screenReadM, screenReadAgeMs, road, key) }
    }

    private fun recordLocked(
        t: Long, rawM: Int, projected: Int, display: Int, closing: Double, speed: Double,
        screenReadM: Int, screenReadAgeMs: Long, road: String, key: String,
    ) {
        val ww = w ?: return
        runCatching {
            val L = Locale.US
            val safeRoad = road.replace(',', ' ').replace('\n', ' ')
            val safeKey = key.replace(',', ' ').replace('\n', ' ')
            ww.append("$t,$rawM,$projected,$display,")
            ww.append("${String.format(L, "%.1f", closing)},${String.format(L, "%.1f", speed)},")
            ww.append("$screenReadM,$screenReadAgeMs,")
            ww.appendLine("$safeRoad,$safeKey")
            ww.flush()
        }
    }
}

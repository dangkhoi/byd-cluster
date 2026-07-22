package com.byd.clusternav.modules.navprobe

import android.content.Context

/**
 * CHỤP TRẠNG THÁI CarPlay / Android Auto — chạy qua dadb **loopback** `localhost:5555`.
 *
 * VÌ SAO PHẢI LÀ APP TỰ CHỤP, KHÔNG PHẢI adb TỪ MÁY TÍNH (CLAUDE.md §11):
 * cắm CarPlay/AA là đầu xe **tắt WiFi**, nên adb từ ngoài không vào được — mà đó đúng là lúc duy nhất
 * các lệnh dưới đây có dữ liệu. App thì chạy NGAY TRÊN đầu xe và nối loopback, không cần mạng.
 *
 * Chụp tự động khi [NavProbeBroadcast] nghe được tín hiệu cắm/rút, và chụp tay được từ màn Máy dò.
 * Có single-flight: cắm/rút hay bắn nhiều broadcast liên tiếp, không để chồng nhiều phiên dadb.
 */
object NavProbeSnap {

    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Đúng những thứ chỉ đọc được KHI ĐANG CẮM — rút ra là trắng. */
    private val CMDS = listOf(
        "media session" to "dumpsys media_session",
        "service AA" to "dumpsys activity services com.byd.androidauto",
        "service CarPlay" to "dumpsys activity services com.byd.carplay.ui",
        "activity đang chạy" to "dumpsys activity activities | grep -iE 'carplay|androidauto|projection' ",
        "stack" to "am stack list",
        "prop kết nối" to "getprop | grep -iE 'carplay|androidauto|projection'",
    )

    fun capture(ctx: Context, why: String) {
        if (!NavProbe.isOn(ctx)) return
        if (!busy.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        Thread {
            try {
                val sb = StringBuilder("=== ").append(stamp()).append(" · [CHỤP CP/AA] ").append(why).append('\n')
                runCatching {
                    dadb.Dadb.create("localhost", 5555, com.byd.clusternav.AdbKeys.ensure(app)).use { adb ->
                        for ((label, cmd) in CMDS) {
                            val out = runCatching { adb.shell(cmd).output.trim() }.getOrElse { "(lỗi: ${it.message})" }
                            sb.append("--- ").append(label).append(" ---\n")
                                .append(if (out.isBlank()) "(rỗng)" else out.take(12000)).append('\n')
                        }
                    }
                }.onFailure { sb.append("(không nối được dadb: ").append(it.message).append(")\n") }
                NavProbe.append(app, sb.append('\n').toString())
                NavProbe.noteSeen("chụp CP/AA")
            } finally { busy.set(false) }
        }.apply { isDaemon = true }.start()
    }

    private fun stamp() =
        java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
}

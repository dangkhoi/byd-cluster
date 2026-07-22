package com.byd.clusternav.modules.navprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * KÊNH 4 của máy dò — nghe BROADCAST của CarPlay / Android Auto / hệ thống BYD.
 *
 * Danh sách action KHÔNG phải em đoán: rút ra từ chính firmware đã decompile trong workspace.
 *
 *  • `byd.intent.action.NAVIGATION_STATE_CHANGED`
 *      — `sysapk/jadx-carplay/.../com/byd/systeminterface/broadcast/BroadcastConstants.java`.
 *        CarPlay tự phát ra, **KHÔNG kèm permission** ⇒ app thường nghe được. Mang `packageName` +
 *        trạng thái đang dẫn đường hay không. Đây là tín hiệu CarPlay DUY NHẤT liên quan nav mà ta biết chắc
 *        là phát ra ngoài. ⚠ Nó là CỜ TRẠNG THÁI, không phải dữ liệu rẽ — đừng kỳ vọng có mét và tên đường.
 *  • `byd.intent.action.AA_CP_CONNECT_STATE_CHANGED`, `byd.intent.action.CARPLAY_STATE`,
 *    `com.google.androidauto.connect.state`
 *      — `firmware/fw-2602-diff/jadx-l3-new/sources/com/android/launcher3/cardwindow/constants/CardAction.java`.
 *        Launcher dùng chúng để biết CP/AA đã cắm chưa. Bắt được thì biết CHÍNH XÁC thời điểm cắm/rút —
 *        cực kỳ hữu ích để đối chiếu với lỗi "cắm CP không lên".
 *  • `AUTONAVI_STANDARD_BROADCAST_SEND` / `_RECV`
 *      — giao thức `com.example.amapservice` (đang cài trên xe) dùng để đẩy nav lên cụm. Nghe để xem
 *        CÓ AI KHÁC đang bắn nav qua đường này không.
 *  • `com.byd.updatewidgets` — launcher tự làm mới card; bắt để hiểu nhịp cập nhật của hệ thống.
 *
 * Đăng ký ĐỘNG (không khai trong Manifest) và chỉ khi máy dò đang bật — hết đo là gỡ, không để lại gì.
 */
object NavProbeBroadcast {

    private val ACTIONS = listOf(
        "byd.intent.action.NAVIGATION_STATE_CHANGED",
        "byd.intent.action.AA_CP_CONNECT_STATE_CHANGED",
        "byd.intent.action.CARPLAY_STATE",
        "com.google.androidauto.connect.state",
        "AUTONAVI_STANDARD_BROADCAST_SEND",
        "AUTONAVI_STANDARD_BROADCAST_RECV",
        "com.byd.updatewidgets",
    )

    /** Thuộc tính hệ thống mà launcher đọc để biết AA đã cắm chưa (`launcher3/framework/utils/VehicleUtils.java`). */
    private val PROPS = listOf(
        "sys.androidauto.connect.state",
        "sys.carplay.connect.state",
        "persist.sys.carplay.state",
    )

    /** Các action báo CP/AA vừa cắm hoặc vừa rút — mốc thời gian duy nhất ta biết chắc để tự chụp. */
    private val CONNECT_ACTIONS = setOf(
        "byd.intent.action.AA_CP_CONNECT_STATE_CHANGED",
        "byd.intent.action.CARPLAY_STATE",
        "com.google.androidauto.connect.state",
    )

    @Volatile private var receiver: BroadcastReceiver? = null

    @Synchronized
    fun start(ctx: Context) {
        if (!NavProbe.isOn(ctx) || receiver != null) return
        val app = ctx.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                i ?: return
                runCatching { NavProbe.recordBroadcast(app, i) }
                // ★ CẮM CP/AA LÀ ĐẦU XE TẮT WIFI ⇒ adb từ ngoài không vào được, mà đó ĐÚNG là lúc cần dữ liệu
                //   (CLAUDE.md §11). Nhưng chính broadcast này báo cho ta biết thời điểm đó. Nên: nghe được
                //   tín hiệu cắm → tự chụp ngay qua dadb loopback (localhost:5555, không cần mạng).
                if (i.action in CONNECT_ACTIONS) runCatching { NavProbeSnap.capture(app, "cắm/rút CP-AA") }
            }
        }
        val f = IntentFilter().apply { ACTIONS.forEach { addAction(it) } }
        runCatching { app.registerReceiver(r, f); receiver = r }
            .onSuccess { NavProbe.append(app, "[BROADCAST] đã nghe ${ACTIONS.size} action:\n" + ACTIONS.joinToString("\n") { "   $it" } + "\n\n") }
            .onFailure { NavProbe.append(app, "[BROADCAST] KHÔNG đăng ký được: ${it.message}\n\n") }
        snapshotProps(app)
    }

    @Synchronized
    fun stop(ctx: Context) {
        val r = receiver ?: return
        receiver = null
        runCatching { ctx.applicationContext.unregisterReceiver(r) }
    }

    /**
     * Chụp giá trị các thuộc tính hệ thống liên quan CP/AA.
     * Đọc qua reflection `android.os.SystemProperties.get` — lớp ẩn, nhưng chỉ ĐỌC, không ghi.
     */
    private fun snapshotProps(ctx: Context) {
        val sb = StringBuilder("[PROP] thuộc tính hệ thống liên quan CP/AA:\n")
        runCatching {
            val cls = Class.forName("android.os.SystemProperties")
            val get = cls.getMethod("get", String::class.java, String::class.java)
            PROPS.forEach { k ->
                val v = runCatching { get.invoke(null, k, "(không có)") as? String }.getOrNull() ?: "(lỗi đọc)"
                sb.append("   ").append(k).append(" = ").append(v).append('\n')
            }
        }.onFailure { sb.append("   không đọc được SystemProperties: ").append(it.message).append('\n') }
        NavProbe.append(ctx, sb.append('\n').toString())
    }
}

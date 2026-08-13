package com.byd.clusternav

import com.byd.clusternav.carexec.LocalDeviceShell
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * BIND lại nav listener — cách DUY NHẤT ăn trên firmware BYD head-unit (firmware BỎ QUA requestRebind).
 * Dùng dadb (ADB local client, localhost:5555, uid=shell) chạy `cmd notification disallow/allow_listener`
 * y như DashCast. Lần đầu có popup "Allow USB debugging" trên xe → bấm Allow 1 lần (key lưu ở filesDir).
 *
 * - [reconnect]  : ép disallow→allow ngay (nút tay + auto khi chưa bound).
 * - [ensureConnected] : gọi lúc mở app — chờ bind tự nhiên ~1.8s, CHƯA bound thì mới reconnect qua dadb
 *   (không disallow/allow khi đang chạy tốt → tránh ngắt nav đang chạy). Đây là "auto connect khi khởi động app".
 */
object NavConnect {
    private const val TAG = "NavConnect"
    private const val COMP = "com.byd.clusternav/com.byd.clusternav.NavNotificationListener"
    private const val ACC_COMP = "com.byd.clusternav/com.byd.clusternav.modules.navaccess.NavAccessibilityService"
    private val reconnecting = java.util.concurrent.atomic.AtomicBoolean(false)   // single-flight: tap dồn dập / ensure trùng → 1 chu kỳ disallow→allow
    private val grantingAcc = java.util.concurrent.atomic.AtomicBoolean(false)    // single-flight cho grantAccessibility (dadb read-modify-write)

    /** Reconnect NGAY qua dadb (chạy nền). An toàn gọi nhiều lần. */
    fun reconnect(ctx: Context) {
        val app = ctx.applicationContext
        Thread { doReconnect(app) }.start()
    }

    /**
     * CẤP QUYỀN notification-listener NGAY trong app qua dadb uid-shell (`cmd notification allow_listener`).
     * Đường CHUẨN trên BYD IVI khoá: màn Settings "Truy cập thông báo" KHÔNG mở được (startActivity bị chặn →
     * toast hệ thống "IVI không hỗ trợ hoạt động này"), NHƯNG quyền này là quyền adb
     * (settings secure enabled_notification_listeners) mà uid shell (2000) qua loopback ĐƯỢC PHÉP đặt — y như
     * DashCast. Lần đầu có popup "Allow USB debugging" trên xe → bấm Allow 1 lần (key lưu ở filesDir).
     *
     * KHÁC [reconnect]: dùng cho lần THIẾU quyền (nút "Cấp quyền" / bật công tắc). Chỉ `allow_listener`
     * (KHÔNG `disallow` trước — lần đầu chưa có trong danh sách) rồi requestRebind + chờ bind để phản hồi UI.
     *
     * @param onResult gọi trên MAIN thread: true nếu listener đã bound sau khi grant, false nếu grant/nối lỗi.
     */
    fun selfGrant(ctx: Context, onResult: ((Boolean) -> Unit)? = null) {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread {
            val ok = doSelfGrant(app)
            onResult?.let { cb -> main.post { cb(ok) } }
        }.start()
    }

    /** Lõi blocking của [selfGrant]. Chạy trên thread nền của caller. Trả true nếu listener đã bound. */
    private fun doSelfGrant(app: Context): Boolean {
        if (!reconnecting.compareAndSet(false, true)) { Log.i(TAG, "grant/reconnect đang chạy — bỏ lần trùng"); return NavNotificationListener.connected }
        try {
            return runCatching {
                val keyPair = AdbKeys.ensure(app)
                val allowed = LocalDeviceShell.session(keyPair) { sh ->
                    sh("cmd notification allow_listener $COMP").ok
                }
                if (allowed != true) {
                    Log.e(TAG, "selfGrant: dadb allow_listener không chạy được (allowed=$allowed)")
                    return@runCatching NavNotificationListener.connected
                }
                NotificationListenerService.requestRebind(ComponentName(app, NavNotificationListener::class.java))
                var waited = 0
                while (waited < 4500 && !NavNotificationListener.connected) { Thread.sleep(300); waited += 300 }
                Log.i(TAG, "selfGrant xong sau ${waited}ms: bound=${NavNotificationListener.connected}")
                NavNotificationListener.connected
            }.getOrElse { Log.e(TAG, "selfGrant qua dadb LỖI (popup Allow chưa bấm?)", it); false }
        } finally { reconnecting.set(false) }
    }

    /**
     * CẤP QUYỀN Hỗ trợ (accessibility) cho [NavAccessibilityService] qua dadb uid-shell — cần cho T3 (nút vật
     * lý → trợ lý) VÀ cho booster đọc màn GMaps. Cùng lý do như [selfGrant]: màn Settings > Hỗ trợ trên IVI
     * khoá có thể không mở/không bật được, nhưng `settings put secure enabled_accessibility_services` từ uid
     * shell thì được. ĐỌC-SỬA-GHI để KHÔNG đá văng service hỗ trợ khác đang bật (append, không overwrite).
     *
     * @param onResult gọi trên MAIN thread: true nếu phiên dadb chạy được (đã append + bật accessibility).
     */
    fun grantAccessibility(ctx: Context, onResult: ((Boolean) -> Unit)? = null) {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread {
            val ok = doGrantAccessibility(app)
            onResult?.let { cb -> main.post { cb(ok) } }
        }.start()
    }

    private fun doGrantAccessibility(app: Context): Boolean {
        if (!grantingAcc.compareAndSet(false, true)) { Log.i(TAG, "grantAccessibility đang chạy — bỏ lần trùng"); return false }
        try {
            return runCatching {
                val keyPair = AdbKeys.ensure(app)
                LocalDeviceShell.session(keyPair) { sh ->
                    val cur = sh("settings get secure enabled_accessibility_services").output.trim()
                    val has = cur.split(':').any { it.trim() == ACC_COMP }
                    if (!has) {
                        val next = if (cur.isBlank() || cur == "null") ACC_COMP else "$cur:$ACC_COMP"
                        sh("settings put secure enabled_accessibility_services $next")
                    }
                    sh("settings put secure accessibility_enabled 1")
                    Log.i(TAG, "grantAccessibility xong (đã có sẵn=$has)")
                    true
                } ?: false
            }.getOrElse { Log.e(TAG, "grantAccessibility qua dadb LỖI (popup Allow chưa bấm?)", it); false }
        } finally { grantingAcc.set(false) }
    }

    /**
     * Auto-ensure lúc mở app: xin rebind, chờ ~1.8s cho hệ thống bind; nếu listener vẫn CHƯA bound
     * ([NavNotificationListener.connected] == false) thì reconnect qua dadb. Không đụng gì nếu đã bound.
     */
    fun ensureConnected(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            runCatching {
                NotificationListenerService.requestRebind(ComponentName(app, NavNotificationListener::class.java))
                // R5: POLL ~300ms tới ~4.5s thay vì chờ cứng 1.8s — bind tự nhiên xong thì THOÁT SỚM (tránh dadb
                // disallow/allow thừa làm rớt nav vừa mới lên, trễ frame đầu vài giây).
                var waited = 0
                while (waited < 4500) {
                    if (NavNotificationListener.connected) { Log.i(TAG, "listener đã bound (${waited}ms) → khỏi dadb"); return@runCatching }
                    Thread.sleep(300); waited += 300
                }
                Log.i(TAG, "listener chưa bound sau ${waited}ms → reconnect qua dadb")
                doReconnect(app)
            }.onFailure { Log.e(TAG, "ensureConnected failed", it) }
        }.start()
    }

    /** Lõi blocking: dadb connect localhost:5555 → disallow → allow. Chạy trên thread nền của caller. */
    private fun doReconnect(app: Context) {
        if (!reconnecting.compareAndSet(false, true)) { Log.i(TAG, "reconnect đang chạy — bỏ lần trùng"); return }
        try {
            runCatching {
                val keyPair = AdbKeys.ensure(app)   // key CHUNG, sinh nguyên tử + khoá chung (chống đua với các client dadb khác)
                LocalDeviceShell.session(keyPair) { sh ->
                    sh("cmd notification disallow_listener $COMP")
                    Thread.sleep(1500)
                    sh("cmd notification allow_listener $COMP")
                }
                // Fallback cho chắc.
                NotificationListenerService.requestRebind(ComponentName(app, NavNotificationListener::class.java))
                Log.i(TAG, "reconnect qua dadb xong")
            }.onFailure { Log.e(TAG, "reconnect qua dadb LỖI (popup Allow chưa bấm?)", it) }
        } finally { reconnecting.set(false) }
    }
}

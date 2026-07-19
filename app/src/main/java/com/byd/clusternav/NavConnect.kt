package com.byd.clusternav

import android.content.ComponentName
import android.content.Context
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

    /** Reconnect NGAY qua dadb (chạy nền). An toàn gọi nhiều lần. */
    fun reconnect(ctx: Context) {
        val app = ctx.applicationContext
        Thread { doReconnect(app) }.start()
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
        runCatching {
            val keyPair = AdbKeys.ensure(app)   // key CHUNG, sinh nguyên tử + khóa chung (chống đua với MockLoc.selfGrant)
            dadb.Dadb.create("localhost", 5555, keyPair).use { adb ->
                adb.shell("cmd notification disallow_listener $COMP")
                Thread.sleep(1500)
                adb.shell("cmd notification allow_listener $COMP")
            }
            // Fallback cho chắc.
            NotificationListenerService.requestRebind(ComponentName(app, NavNotificationListener::class.java))
            Log.i(TAG, "reconnect qua dadb xong")
        }.onFailure { Log.e(TAG, "reconnect qua dadb LỖI (popup Allow chưa bấm?)", it) }
    }
}

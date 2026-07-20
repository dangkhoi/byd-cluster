package com.byd.clusternav

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * SELF-HEAL nav listener — auto-rebind KHÔNG cần mở app / không cần disallow→allow tay.
 *
 * Head-unit BYD hay GIỮ quyền listener nhưng KHÔNG bind (hoặc THẢ binding lúc chạy)
 * → [NavNotificationListener.onNotificationPosted] câm = "nav không lên / flaky".
 * Quyền vẫn ON, Maps vẫn đẩy noti category=navigation, nhưng service không ở trạng thái bound.
 *
 * Ba lớp tự hồi phục:
 *  1. Sự kiện hệ thống: MY_PACKAGE_REPLACED + BOOT_COMPLETED + LOCKED_BOOT_COMPLETED → rebind ngay.
 *  2. [NavNotificationListener.onListenerDisconnected] → rebind ngay khi binding rớt.
 *  3. WATCHDOG định kỳ ([ACTION_WATCHDOG] qua AlarmManager ~60s) → rebind lại kể cả khi
 *     binding CHƯA TỪNG lên (case "sáng nay đi không lên") mà không cần thao tác tay.
 *
 * Đăng ký trong AndroidManifest (manifest-declared, để nhận được kể cả khi process đã chết).
 */
class RebindReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "rebind trigger: $action")
        rebind(context)
        // Trên sự kiện boot: (re)đặt watchdog định kỳ — alarm không sống qua reboot.
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            // Dọn test-provider mock MỒ CÔI còn sót từ phiên trước bị kill bẩn (onDestroy không chạy) → GPS_PROVIDER
            // kẹt = mock chết CHẶN GPS thật toàn hệ thống. An toàn tại boot: DR chưa chạy nên không gỡ mock đang dùng.
            runCatching { com.byd.clusternav.modules.mockloc.MockLoc.stop(context) }
            scheduleWatchdog(context)
            // AUTO bật GPS hiệu chỉnh sau khi boot (nếu user chưa tắt). API 29 cho start FGS từ boot receiver.
            // #1 FIX (chống ZOMBIE): CHỈ start khi ĐÃ có quyền Vị trí. Service không tự xin quyền được (không phải
            // Activity) → start lúc thiếu quyền chỉ tạo zombie chạy-mà-không-nhận-fix, lại che mất việc MainActivity
            // xin quyền. Chưa có quyền → bỏ qua, để MainActivity xin khi user mở app.
            if (Prefs.gpsAuto(context) &&
                context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED) {
                runCatching {
                    context.startForegroundService(
                        Intent(context, com.byd.clusternav.modules.deadreckon.DeadReckonService::class.java)
                    )
                }.onFailure { Log.e(TAG, "auto-start GPS on boot failed", it) }
            } else if (Prefs.gpsAuto(context)) {
                Log.i(TAG, "boot: bỏ auto-start GPS — chưa có quyền Vị trí (MainActivity sẽ xin)")
            }
            // AUTO hiện NÚT NỔI lúc boot (user: "luôn hiện bubble") — cần quyền overlay (minSdk29 nên canDrawOverlays luôn có).
            if (Prefs.bubbleAuto(context) && android.provider.Settings.canDrawOverlays(context)) {
                runCatching { context.startForegroundService(Intent(context, com.byd.clusternav.modules.clustercast.FloatingBubbleService::class.java)) }
                    .onFailure { Log.e(TAG, "auto-start bubble on boot failed", it) }
            }
        }
    }

    companion object {
        private const val TAG = "NavRebind"
        const val ACTION_WATCHDOG = "com.byd.clusternav.REBIND_WATCHDOG"
        private const val INTERVAL_MS = 60_000L

        /** Ép hệ thống bind lại nav listener (an toàn gọi nhiều lần; no-op nếu đã bound). */
        fun rebind(context: Context) {
            runCatching {
                NotificationListenerService.requestRebind(
                    ComponentName(context, NavNotificationListener::class.java)
                )
            }.onFailure { Log.e(TAG, "requestRebind failed", it) }
        }

        /** Đặt alarm lặp ~60s gọi lại [rebind] → tự hồi phục binding khi đang chạy/đỗ. */
        fun scheduleWatchdog(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, RebindReceiver::class.java).setAction(ACTION_WATCHDOG),
                flags
            )
            runCatching {
                am.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,   // R4: WAKEUP để watchdog vẫn chạy khi head-unit SoC suspend
                    SystemClock.elapsedRealtime() + INTERVAL_MS,
                    INTERVAL_MS,
                    pi
                )
            }.onFailure { Log.e(TAG, "scheduleWatchdog failed", it) }
        }
    }
}

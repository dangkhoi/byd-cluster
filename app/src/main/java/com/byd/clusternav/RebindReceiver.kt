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
        when (action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                // Dọn test-provider mock MỒ CÔI còn sót từ phiên trước bị kill bẩn (onDestroy không chạy) → GPS_PROVIDER
                // kẹt = mock chết CHẶN GPS thật toàn hệ thống. An toàn tại boot: DR chưa chạy nên không gỡ mock đang dùng.
                runCatching { com.byd.clusternav.modules.mockloc.MockLoc.stop(context) }
                scheduleWatchdog(context)   // alarm không sống qua reboot → (re)đặt khi boot
                autoStartServices(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // ★ FIX D1: sau install -r / update, app + DR service bị KILL nhưng KHÔNG tự chạy lại (chỉ rebind
                //   listener ở trên). Hệ quả: user lái sau update → DR TẮT → mất GPS trong hầm KHÔNG được che →
                //   GMaps báo "mất tín hiệu GPS". Tự bật lại DR + bubble như lúc boot (cùng uid app → startFGS OK).
                runCatching { com.byd.clusternav.modules.mockloc.MockLoc.stop(context) }   // dọn mock mồ côi từ phiên bị kill lúc update
                autoStartServices(context)
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

        /**
         * AUTO bật DR (GPS hầm) + nút nổi — gọi khi BOOT và khi MY_PACKAGE_REPLACED (sau update). Cùng uid app nên
         * startForegroundService chạy được (khác adb shell bị chặn export). API29 cho start FGS từ receiver này.
         * #1 (chống ZOMBIE): CHỈ start DR khi ĐÃ có quyền Vị trí — service không tự xin được (không phải Activity);
         * start lúc thiếu quyền = zombie chạy-không-nhận-fix + che việc MainActivity xin quyền. Chưa có → để app xin.
         */
        fun autoStartServices(context: Context) {
            if (Prefs.gpsAuto(context) &&
                context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED) {
                runCatching {
                    context.startForegroundService(
                        Intent(context, com.byd.clusternav.modules.deadreckon.DeadReckonService::class.java)
                    )
                }.onFailure { Log.e(TAG, "auto-start GPS failed", it) }
            } else if (Prefs.gpsAuto(context)) {
                Log.i(TAG, "bỏ auto-start GPS — chưa có quyền Vị trí (MainActivity sẽ xin)")
            }
            // NÚT NỔI (user: "luôn hiện bubble") — cần quyền overlay (minSdk29 nên canDrawOverlays luôn có nếu đã cấp).
            if (Prefs.bubbleAuto(context) && android.provider.Settings.canDrawOverlays(context)) {
                runCatching { context.startForegroundService(Intent(context, com.byd.clusternav.modules.clustercast.FloatingBubbleService::class.java)) }
                    .onFailure { Log.e(TAG, "auto-start bubble failed", it) }
            }
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

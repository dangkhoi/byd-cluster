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
            Intent.ACTION_BOOT_COMPLETED -> {
                scheduleWatchdog(context)
                com.byd.clusternav.modules.clustercast.CastLifecycleReceiver.schedule(context)
                castBootWork(context, automation = true)
            }
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                scheduleWatchdog(context)
                com.byd.clusternav.modules.clustercast.CastLifecycleReceiver.schedule(context)
                castBootWork(context, automation = false)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                com.byd.clusternav.modules.clustercast.CastLifecycleReceiver.schedule(context)
                castBootWork(context, automation = false)
            }
        }
    }

    /**
     * One bounded background pass: read-only Cast rehydration, optional opted-in Bubble presentation
     * and, only for post-unlock BOOT_COMPLETED, the durable-first boot automation record.
     *
     * The receiver performs no observation, planner, journal, DADB or gateway call, and it always
     * finishes. Locked boot, package replacement and the watchdog can never record or claim
     * automation.
     */
    private fun castBootWork(context: Context, automation: Boolean) {
        val pending = goAsync()
        Thread {
            try {
                val app = context.applicationContext
                runCatching {
                    val result = com.byd.clusternav.cast.platform.CastAndroidLifecycle.rehydrate(app)
                    Log.i(TAG, "Cast V2 lifecycle: $result")
                }.onFailure { Log.e(TAG, "Cast rehydrate failed", it) }
                runCatching { startOptedInBubble(app) }.onFailure { Log.e(TAG, "bubble restore failed", it) }
                if (automation) {
                    val request = runCatching {
                        com.byd.clusternav.modules.clustercast.CastAutomationService.recordAndEnqueue(app)
                    }.onFailure { Log.e(TAG, "boot automation record failed", it) }.getOrNull()
                    Log.i(TAG, "Cast boot automation: ${request?.state ?: "none"}")
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    /** Presentation-only restore of an explicitly opted-in Bubble; it never dispatches Cast work. */
    private fun startOptedInBubble(app: Context) {
        val optedIn = runCatching {
            com.byd.clusternav.cast.platform.CastAppCatalog(app).bubbleEnabled()
        }.getOrDefault(false)
        if (!optedIn || !android.provider.Settings.canDrawOverlays(app)) return
        runCatching {
            app.startForegroundService(
                Intent(app, com.byd.clusternav.modules.clustercast.FloatingBubbleService::class.java),
            )
        }.onFailure { Log.e(TAG, "auto-start bubble failed", it) }
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

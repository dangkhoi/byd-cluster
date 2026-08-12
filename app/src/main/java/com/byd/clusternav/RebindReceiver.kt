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
                castBootWork(context, automation = true)
            }
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                scheduleWatchdog(context)
                castBootWork(context, automation = false)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                castBootWork(context, automation = false)
                // OTA auto-reopen (owner 2026-08-12): the installer kills us on update and does NOT
                // relaunch. Bring the app back to the foreground so the user lands on Home after an
                // update instead of a blank screen. Manifest-declared receiver ⇒ delivered even though
                // our process was replaced. Background-activity-start is allowed here because the app
                // holds SYSTEM_ALERT_WINDOW (the overlay/bubble permission) — the standard A10
                // exemption; best-effort (runCatching) if the grant is missing.
                relaunchAfterUpdate(context)
            }
        }
    }

    /**
     * One bounded background pass: read-only Cast rehydration, optional opted-in Bubble presentation
     * and, only for post-unlock BOOT_COMPLETED, the durable-first boot automation record.
     *
     * 2026-08-03: V2 lifecycle rehydrate removed — simplified coordinator owns projection.
     * Only bubble presentation and boot automation remain.
     */
    private fun castBootWork(context: Context, automation: Boolean) {
        val pending = goAsync()
        Thread {
            try {
                val app = context.applicationContext
                // V2 CastAndroidLifecycle.rehydrate removed — simplified coordinator active
                runCatching { startOptedInBubble(app) }.onFailure { Log.e(TAG, "bubble restore failed", it) }
                if (automation) {
                    runCatching {
                        com.byd.clusternav.modules.clustercast.CastAutomationService.recordAndEnqueue(app)
                    }.onFailure { Log.e(TAG, "boot automation record failed", it) }
                    Log.i(TAG, "Cast boot automation: disabled (simplified coordinator)")
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    /**
     * Presentation-only restore of the always-on Bubble; it never dispatches Cast work.
     *
     * v0.72: the bubble no longer has an enable/disable toggle (docs/specs/cast-simplified-active-app-toggle.html)
     * -- it starts on every boot as long as the overlay permission is already granted. If it is not,
     * `FloatingBubbleService.onStartCommand` itself sends the user to the one system screen that can
     * grant it, so starting the service unconditionally here is what lets that happen on first boot too.
     */
    private fun startOptedInBubble(app: Context) {
        runCatching {
            app.startForegroundService(
                Intent(app, com.byd.clusternav.modules.clustercast.FloatingBubbleService::class.java),
            )
        }.onFailure { Log.e(TAG, "auto-start bubble failed", it) }
    }

    /**
     * Bring the app back to the foreground after a self-update (MY_PACKAGE_REPLACED). Uses the
     * package's own launcher intent (Home / MainActivity) with NEW_TASK; CLEAR_TOP so a stale task
     * isn't stacked. Best-effort: background activity-start needs the SYSTEM_ALERT_WINDOW exemption,
     * so this may be a no-op if the overlay grant is absent — it never throws.
     */
    private fun relaunchAfterUpdate(context: Context) {
        runCatching {
            val app = context.applicationContext
            val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                ?: Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(launch)
            Log.i(TAG, "relaunch after update requested")
        }.onFailure { Log.e(TAG, "relaunch after update failed", it) }
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

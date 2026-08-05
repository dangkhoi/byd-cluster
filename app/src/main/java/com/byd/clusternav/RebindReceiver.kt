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
        // Debug hook: bắn speed limit test qua adb.
        // adb shell am broadcast -a com.byd.clusternav.TEST_SPEED_LIMIT --ei limit 60
        if (action == "com.byd.clusternav.TEST_SPEED_LIMIT") {
            val limit = intent.getIntExtra("limit", 0)
            Log.i(TAG, "TEST_SPEED_LIMIT limit=$limit")
            runCatching {
                if (limit > 0) ClusterBroadcaster.pushSpeedLimit(context.applicationContext, limit)
                else ClusterBroadcaster.pushSpeedLimit(context.applicationContext, null)
            }.onFailure { Log.e(TAG, "TEST_SPEED_LIMIT failed", it) }
            return
        }
        // Debug: probe a BYD HAL device methods + feature IDs.
        // adb shell am broadcast -a com.byd.clusternav.TEST_ADAS_PROBE --es dev instrument -f 0x01000000
        if (action == "com.byd.clusternav.TEST_ADAS_PROBE") {
            val devName = intent.getStringExtra("dev") ?: "adas"
            Thread {
                runCatching {
                    val hal = com.byd.clusternav.modules.hal.BydHal
                    val fqn = when (devName.lowercase()) {
                        "instrument" -> hal.INSTRUMENT
                        "setting" -> hal.SETTING
                        else -> hal.ADAS
                    }
                    val sys = hal.systemBypassContext()
                    val dev = hal.device(fqn, sys, hal.bypass(context.applicationContext))
                    if (dev == null) { Log.e(TAG, "PROBE[$devName]: device null"); return@runCatching }
                    Log.i(TAG, "PROBE[$devName] device=${dev.javaClass.name}")
                    hal.methods(dev, "set", "get", "on", "enable", "notify").filter {
                        it.contains("peed", true) || it.contains("imit", true) || it.contains("ign", true) ||
                        it.contains("SLA", true) || it.contains("ISLA", true) || it.contains("TSR", true) || it.contains("Camera", true)
                    }.forEach { Log.i(TAG, "PROBE[$devName] method: $it") }
                    hal.featureIdsMatching("SPEED", "LIMIT", "SIGN", "SLA", "ISLA", "TSR", "CAMERA", "OVERSPEED").forEach {
                        Log.i(TAG, "PROBE[$devName] feature: ${it.first} = ${it.second}")
                    }
                }.onFailure { Log.e(TAG, "PROBE[$devName] failed", it) }
            }.start()
            return
        }
        // Debug: read a value from an ADAS feature id via get(int[], Class).
        // adb shell am broadcast -a com.byd.clusternav.TEST_ADAS_READ --ei id 828375077 -f 0x01000000
        if (action == "com.byd.clusternav.TEST_ADAS_READ") {
            val ids = intent.getIntExtra("id", 0)
            Thread {
                runCatching {
                    val hal = com.byd.clusternav.modules.hal.BydHal
                    val sys = hal.systemBypassContext()
                    val adas = hal.device(hal.ADAS, sys, hal.bypass(context.applicationContext)) ?: run {
                        Log.e(TAG, "ADAS_READ: device null"); return@runCatching
                    }
                    // Read many SLA/ISLA candidates via get(int[], Class)
                    val candidates = mapOf(
                        "ADAS_SLA_STATE" to 828375077,
                        "ADAS_SLA_STATE_1" to 725614605,
                        "ADAS_SLA_STATE_2" to 760217608,
                        "ADAS_ISLA_CONFIG" to 1126170654,
                        "ADAS_ISLA_SWITCH_STATUS_5R13V" to 760217615,
                    )
                    val getM = adas.javaClass.methods.firstOrNull { it.name == "get" && it.parameterTypes.size == 2 }
                    candidates.forEach { (name, fid) ->
                        val r = runCatching {
                            val res = getM?.invoke(adas, intArrayOf(fid), Int::class.javaObjectType)
                            hal.readValue(res)
                        }.getOrElse { hal.root(it) }
                        Log.i(TAG, "ADAS_READ $name($fid) = $r")
                    }
                    listOf("getSLAState").forEach { Log.i(TAG, "ADAS_READ $it=${hal.callGetter(adas, it)}") }
                }.onFailure { Log.e(TAG, "ADAS_READ failed", it) }
            }.start()
            return
        }
        // Debug: write a value to an ADAS feature id and read back SLA state.
        // adb shell am broadcast -a com.byd.clusternav.TEST_ADAS_WRITE --ei id 944767010 --ei val 60 -f 0x01000000
        if (action == "com.byd.clusternav.TEST_ADAS_WRITE") {
            val id = intent.getIntExtra("id", 0)
            val v = intent.getIntExtra("val", 0)
            Thread {
                runCatching {
                    val hal = com.byd.clusternav.modules.hal.BydHal
                    val sys = hal.systemBypassContext()
                    val adas = hal.device(hal.ADAS, sys, hal.bypass(context.applicationContext)) ?: run {
                        Log.e(TAG, "ADAS_WRITE: device null"); return@runCatching
                    }
                    // Try dedicated setSLAState with the value directly (rc=0 = success)
                    runCatching { hal.invokeM(adas, "setSLAState", v) }.onSuccess { Log.i(TAG, "ADAS_WRITE setSLAState($v)=$it") }
                    val rc = runCatching { hal.setInt(adas, id, v) }.getOrElse { hal.root(it) }
                    Log.i(TAG, "ADAS_WRITE id=$id val=$v rc=$rc")
                    // Read back SLA getters
                    listOf("getSLAState").forEach { g ->
                        Log.i(TAG, "ADAS_WRITE $g=${hal.callGetter(adas, g)}")
                    }
                }.onFailure { Log.e(TAG, "ADAS_WRITE failed", it) }
            }.start()
            return
        }
        // Debug: MASS-write a value to EVERY feature id matching speed/limit/sign on a device.
        // Logs each feature + rc so we find which one(s) accept (rc=0) and which renders the sign.
        // adb shell am broadcast -a com.byd.clusternav.TEST_ADAS_MASS --es dev instrument --ei val 60 -f 0x01000000
        // Reset all to 0: --ei val 0
        if (action == "com.byd.clusternav.TEST_ADAS_MASS") {
            val devName = intent.getStringExtra("dev") ?: "instrument"
            val v = intent.getIntExtra("val", 60)
            Thread {
                runCatching {
                    val hal = com.byd.clusternav.modules.hal.BydHal
                    val fqn = when (devName.lowercase()) {
                        "instrument" -> hal.INSTRUMENT
                        "adas" -> hal.ADAS
                        "setting" -> hal.SETTING
                        else -> hal.INSTRUMENT
                    }
                    val sys = hal.systemBypassContext()
                    val dev = hal.device(fqn, sys, hal.bypass(context.applicationContext)) ?: run {
                        Log.e(TAG, "MASS[$devName]: device null"); return@runCatching
                    }
                    // Only _SET / writable-looking features carrying a speed/limit/sign value
                    val feats = hal.featureIdsMatching("SPEED", "LIMIT", "SIGN", "SLA", "ISLA", "TSR", "OVERSPEED")
                        .filter { it.first.contains("SET", true) || it.first.contains("SPEED", true) || it.first.contains("LIMIT", true) }
                    Log.i(TAG, "MASS[$devName] writing $v to ${feats.size} features")
                    feats.forEach { (name, fid) ->
                        val rc = runCatching { hal.setInt(dev, fid, v) }.getOrElse { hal.root(it) }
                        val ok = rc.toString() == "0"
                        Log.i(TAG, "MASS[$devName] ${if (ok) "OK  " else "err "} $name($fid) rc=$rc")
                    }
                    Log.i(TAG, "MASS[$devName] done — NHÌN cụm xem biển $v hiện chưa; note feature rc=0")
                }.onFailure { Log.e(TAG, "MASS[$devName] failed", it) }
            }.start()
            return
        }
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

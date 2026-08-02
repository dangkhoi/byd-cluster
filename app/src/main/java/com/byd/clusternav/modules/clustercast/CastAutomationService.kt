package com.byd.clusternav.modules.clustercast

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.byd.clusternav.R
import com.byd.clusternav.modules.clustercast.v2.AutomationReason
import com.byd.clusternav.modules.clustercast.v2.AutomationRequestState
import com.byd.clusternav.modules.clustercast.v2.BootAutomationRequest
import com.byd.clusternav.cast.platform.CastAndroidRuntime
import com.byd.clusternav.cast.platform.CastAppCatalog
import com.byd.clusternav.modules.clustercast.v2.CastAutomationSettings
import com.byd.clusternav.modules.clustercast.v2.CastManualIntentResult
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Bounded, visible host for one claimed boot automation request.
 *
 * The receiver commits the durable request first and only then enqueues this service with that exact
 * request id. The host calls `startForeground` before submitting any worker, consumes at most one
 * claim, delegates every mutation to the existing manual-intent owner, enforces an absolute deadline
 * and always calls `stopSelf`. It never plans or issues a command itself.
 */
class CastAutomationService : Service() {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "cast-automation-host").apply { isDaemon = true }
    }
    private var foregroundStarted = false

    /** Set when the host gives up; no abandoned worker may claim, bind or terminalize afterwards. */
    @Volatile private var abandoned = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (requestId == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!notificationsPermitted()) {
            terminalizeOffMain(requestId, AutomationReason.NOTIFICATION_PERMISSION_DENIED, startId)
            return START_NOT_STICKY
        }
        if (!startForegroundOnce()) {
            terminalizeOffMain(requestId, AutomationReason.NOTIFICATION_START_FAILED, startId)
            return START_NOT_STICKY
        }
        val deadline = SystemClock.elapsedRealtime() + HOST_BUDGET_MS
        val task = worker.submit {
            runCatching {
                val runtime = CastAndroidRuntime.create(applicationContext)
                evaluate(runtime, runtime.automation, requestId)
            }.onFailure { Log.e(TAG, "automation evaluation failed", it) }
        }
        Thread({
            try {
                task.get((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            } catch (timeout: TimeoutException) {
                abandoned = true
                task.cancel(true)
                terminalize(requestId, AutomationReason.DEADLINE_EXPIRED)
            } catch (failure: Throwable) {
                abandoned = true
                Log.e(TAG, "automation host failed", failure)
                terminalize(requestId, AutomationReason.KNOWN_FAILURE)
            } finally {
                runCatching { stopSelf(startId) }
            }
        }, "cast-automation-deadline").start()
        return START_NOT_STICKY
    }

    /** Durable terminal writes never run on the main thread and never escape as a crash. */
    private fun terminalizeOffMain(requestId: UUID, reason: AutomationReason, startId: Int) {
        runCatching { stopSelf(startId) }
        Thread({ terminalize(requestId, reason) }, "cast-automation-terminal").start()
    }

    private fun terminalize(requestId: UUID, reason: AutomationReason) {
        runCatching {
            CastAndroidRuntime.create(applicationContext).automation
                .terminalize(requestId, AutomationRequestState.BLOCKED, reason)
        }.onFailure { Log.e(TAG, "terminalize failed", it) }
    }

    override fun onDestroy() {
        abandoned = true
        worker.shutdownNow()
        super.onDestroy()
    }

    /** One evaluation: prior truth, one claim CAS, then the existing manual-intent orchestration. */
    private fun evaluate(
        runtime: CastAndroidRuntime.Runtime,
        settings: CastAutomationSettings,
        requestId: UUID,
    ) {
        val envelope = CastFacade.wrapping(runtime, CastAppCatalog(applicationContext)).envelope() ?: run {
            settings.terminalize(requestId, AutomationRequestState.BLOCKED, AutomationReason.KNOWN_FAILURE)
            return
        }
        val request = envelope.bootAutomationRequest
        if (request == null || request.requestId != requestId || request.terminal) return
        if (envelope.stopRequested) {
            settings.terminalize(requestId, AutomationRequestState.SUPERSEDED, AutomationReason.STOP_SUPERSEDED)
            return
        }
        if (CastFacade.wrapping(runtime, CastAppCatalog(applicationContext)).pendingIntentIsUserRequested(envelope)) {
            settings.terminalize(requestId, AutomationRequestState.SUPERSEDED, AutomationReason.USER_SUPERSEDED)
            return
        }
        if (!request.claimable) {
            // The single claim budget was already consumed, so this boot may only record a terminal.
            settings.terminalize(
                requestId,
                AutomationRequestState.BLOCKED,
                if (CastAutomationSettings.effectFree(envelope, request)) {
                    AutomationReason.CLAIMED_NO_EFFECT_AFTER_RESTART
                } else {
                    AutomationReason.UNKNOWN_EFFECT
                },
            )
            return
        }
        if (envelope.transaction != null) {
            deferForPriorJournal(settings, request)
            return
        }
        if (envelope.stableSession?.state == com.byd.clusternav.modules.clustercast.v2.StableState.RECOVERY_PENDING ||
            envelope.stableSession?.state == com.byd.clusternav.modules.clustercast.v2.StableState.MANUAL_REQUIRED
        ) {
            settings.terminalize(requestId, AutomationRequestState.BLOCKED, AutomationReason.RECOVERY_OR_MANUAL)
            return
        }
        // Something is already on the cluster. Within one boot a verified active target can only have
        // been placed by a surface the driver drove (the floating bubble or the Home panel) — a session
        // carried over from before the reboot cannot converge in `CastLifecycleMigration
        // .revalidateStable` (the cluster's virtual display does not survive a power cycle) and is
        // demoted to RECOVERY_PENDING, which the gate above already refuses. So the honest terminal is
        // USER_SUPERSEDED: automation declined because the user got there first, not because anything
        // failed. `CastCoordinator.runManualIntent` fences the same condition for real; this branch
        // exists so the outcome is RECORDED as superseded instead of surfacing later as a misleading
        // BLOCKED/KNOWN_FAILURE. Review 2026-07-30, docs/specs/cast-simplified-active-app-toggle.html.
        if (envelope.stableSession?.activeTarget != null) {
            settings.terminalize(requestId, AutomationRequestState.SUPERSEDED, AutomationReason.USER_SUPERSEDED)
            return
        }
        if (abandoned) return
        when (val claim = settings.claim(requestId)) {
            is CastAutomationSettings.ClaimResult.Rejected -> {
                Log.i(TAG, "automation claim rejected: ${claim.reason}")
                return
            }
            is CastAutomationSettings.ClaimResult.Superseded -> return
            is CastAutomationSettings.ClaimResult.Claimed -> Unit
        }
        if (abandoned) {
            settings.terminalize(requestId, AutomationRequestState.BLOCKED, AutomationReason.DEADLINE_EXPIRED)
            return
        }
        if (!settings.bindAutoPending(requestId)) {
            // Stop or an explicit user placement won the race inside the same locked transaction.
            settings.terminalize(requestId, AutomationRequestState.SUPERSEDED, AutomationReason.STOP_SUPERSEDED)
            return
        }
        val catalog = CastAppCatalog(applicationContext) { CastFacade.wrapping(runtime, CastAppCatalog(applicationContext)).phoneSession(it) }
        val result = runCatching {
            CastFacade.wrapping(runtime, catalog).runBootAutomationIntent(
                request.targetPackage,
                automationRequestId = requestId,
            )
        }.getOrElse { failure ->
            Log.e(TAG, "automation orchestration failed", failure)
            CastManualIntentResult.Blocked(failure.message ?: failure.javaClass.simpleName)
        }
        when (result) {
            is CastManualIntentResult.Succeeded ->
                settings.terminalize(requestId, AutomationRequestState.COMPLETED, null)
            is CastManualIntentResult.Blocked -> {
                val current = CastFacade.wrapping(runtime, CastAppCatalog(applicationContext)).envelope()
                val stillCurrent = current?.bootAutomationRequest?.takeIf { it.requestId == requestId }
                val reason = if (current?.transaction != null && stillCurrent != null && !stillCurrent.terminal) {
                    AutomationReason.PRIOR_JOURNAL
                } else {
                    AutomationReason.KNOWN_FAILURE
                }
                settings.terminalize(requestId, AutomationRequestState.BLOCKED, reason)
            }
            is CastManualIntentResult.RecoveryRequired ->
                settings.terminalize(requestId, AutomationRequestState.BLOCKED, AutomationReason.UNKNOWN_EFFECT)
            is CastManualIntentResult.VerificationPending ->
                settings.terminalize(requestId, AutomationRequestState.BLOCKED, AutomationReason.UNKNOWN_EFFECT)
            is CastManualIntentResult.Queued ->
                settings.terminalize(requestId, AutomationRequestState.BLOCKED, AutomationReason.RECOVERY_OR_MANUAL)
        }
    }

    /** Exactly one request-bound revalidation alarm is permitted, then the request exhausts. */
    private fun deferForPriorJournal(settings: CastAutomationSettings, request: BootAutomationRequest) {
        if (request.state == AutomationRequestState.DEFERRED && request.reevaluationCount >= 1) {
            settings.terminalize(
                request.requestId, AutomationRequestState.BLOCKED, AutomationReason.REEVALUATION_EXHAUSTED,
            )
            return
        }
        if (request.state != AutomationRequestState.DEFERRED) settings.defer(request.requestId)
        if (settings.consumeReevaluation(request.requestId) == null) {
            settings.terminalize(
                request.requestId, AutomationRequestState.BLOCKED, AutomationReason.REEVALUATION_EXHAUSTED,
            )
            return
        }
        if (!scheduleRevalidation(applicationContext, request.requestId)) {
            settings.terminalize(
                request.requestId, AutomationRequestState.BLOCKED, AutomationReason.REEVALUATION_EXHAUSTED,
            )
        }
    }

    private fun notificationsPermitted(): Boolean = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun startForegroundOnce(): Boolean {
        if (foregroundStarted) return true
        return runCatching {
            startForeground(NOTIFICATION_ID, notification())
            foregroundStarted = true
            true
        }.getOrElse {
            Log.e(TAG, "startForeground denied", it)
            false
        }
    }

    private fun notification(): android.app.Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL, "Cluster Cast tự động", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val pending = PendingIntent.getActivity(
            this, 1, Intent(this, com.byd.clusternav.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return android.app.Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Cluster Cast đang tự động chiếu")
            .setContentText("Nhấn để mở điều khiển hoặc dừng")
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }

    companion object {
        private const val TAG = "CastAutomation"
        private const val CHANNEL = "cluster_cast_automation"
        private const val NOTIFICATION_ID = 1043
        internal const val HOST_BUDGET_MS = 60_000L
        internal const val REVALIDATION_DELAY_MS = 45_000L
        internal const val EXTRA_REQUEST_ID = "com.byd.clusternav.cast.AUTOMATION_REQUEST_ID"

        /**
         * Post-unlock boot entry. The request is committed durably first; only then is the visible
         * host enqueued with that exact id. An enqueue failure terminalizes the same record.
         */
        fun recordAndEnqueue(context: Context): BootAutomationRequest? {
            val app = context.applicationContext
            val bootId = observedBootId(app) ?: return null
            val runtime = CastAndroidRuntime.create(app)
            val request = runtime.automation.recordOrGet(bootId) ?: return null
            if (request.terminal) return request
            val started = runCatching { app.startForegroundService(intentFor(app, request.requestId)) }
            if (started.isFailure) {
                Log.e(TAG, "automation host enqueue denied", started.exceptionOrNull())
                runtime.automation.terminalize(
                    request.requestId, AutomationRequestState.BLOCKED, AutomationReason.FOREGROUND_START_DENIED,
                )
            }
            return request
        }

        /** Authoritative boot identity; an unreadable or negative value prevents automation. */
        fun observedBootId(context: Context): String? = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        }.getOrDefault(-1).takeIf { it >= 0 }?.toString()

        internal fun intentFor(context: Context, requestId: UUID): Intent =
            Intent(context, CastAutomationService::class.java).putExtra(EXTRA_REQUEST_ID, requestId.toString())

        private fun scheduleRevalidation(context: Context, requestId: UUID): Boolean {
            val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
            val pending = PendingIntent.getForegroundService(
                context, 2, intentFor(context, requestId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return runCatching {
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + REVALIDATION_DELAY_MS,
                    pending,
                )
            }.onFailure { Log.e(TAG, "revalidation alarm failed", it) }.isSuccess
        }
    }
}

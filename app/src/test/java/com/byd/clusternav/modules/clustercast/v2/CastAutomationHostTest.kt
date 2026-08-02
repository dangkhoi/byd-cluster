package com.byd.clusternav.modules.clustercast.v2

import com.byd.clusternav.cast.platform.CastAndroidLifecycle
import com.byd.clusternav.testsupport.SourceRoots
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID

/**
 * Boot automation host contract: durable record before enqueue, foreground before work, finite
 * triggers, and deterministic process-death cut points.
 */
private const val AUTOMATION_TARGET = "com.example.maps"

class CastAutomationHostTest {

    private fun source(relative: String): String {
        val direct = Paths.get("app/src/$relative")
        val nested = SourceRoots.path("src/$relative")
        return (if (Files.exists(direct)) direct else nested).toFile().readText()
    }

    private val service = source("main/java/com/byd/clusternav/modules/clustercast/CastAutomationService.kt")
    private val receiver = source("main/java/com/byd/clusternav/RebindReceiver.kt")
    private val manifest = source("main/AndroidManifest.xml")
    private val coordinator = source("main/java/com/byd/clusternav/modules/clustercast/v2/CastCoordinator.kt")

    private val target = AUTOMATION_TARGET

    private class Memory : AtomicBytes {
        var bytes: ByteArray? = null
        override fun exists() = bytes != null
        override fun read(): ByteArray = bytes ?: error("empty")
        override fun write(value: ByteArray) { bytes = value }
    }

    private class Fixture(var now: Long = 1_000L) {
        val store = CastSessionStore(Memory())
        val settings = CastAutomationSettings(store) { now }
        init { store.locked { initializeForBoot("11") } }
        fun envelope(): CastSessionEnvelope = (store.locked { read() } as StoreRead.Loaded).envelope
        fun armed(): BootAutomationRequest {
            settings.setDefault(AUTOMATION_TARGET)
            settings.accept()
            return checkNotNull(settings.recordOrGet("11"))
        }
    }

    @Test
    fun `receiver records the request durably before enqueuing the host`() {
        val record = service.indexOf("runtime.automation.recordOrGet(bootId)")
        val enqueue = service.indexOf("startForegroundService(intentFor(app, request.requestId))")
        assertTrue(record in 1 until enqueue, "record must precede enqueue")
        assertTrue(service.contains("AutomationReason.FOREGROUND_START_DENIED"))
        assertTrue(service.contains("EXTRA_REQUEST_ID"))
    }

    @Test
    fun `only post-unlock boot may record automation`() {
        assertTrue(receiver.contains("castBootWork(context, automation = true)"))
        val locked = receiver.substringAfter("Intent.ACTION_LOCKED_BOOT_COMPLETED ->").substringBefore("}")
        assertTrue(locked.contains("automation = false"))
        val replaced = receiver.substringAfter("Intent.ACTION_MY_PACKAGE_REPLACED ->").substringBefore("}")
        assertTrue(replaced.contains("automation = false"))
        assertEquals(1, Regex("automation = true").findAll(receiver).count())
    }

    @Test
    fun `receiver performs no observation planner journal or gateway call and always finishes`() {
        val code = receiver.lineSequence()
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }
            .joinToString("\n")
        listOf("coordinator.plan", "coordinator.execute", "runManualIntent", ".gateway", "Dadb", "observe(")
            .forEach { assertFalse(code.contains(it), "receiver must not call $it") }
        assertTrue(receiver.contains("goAsync()"))
        assertTrue(receiver.contains("pending.finish()"))
        assertTrue(receiver.contains("CastAndroidLifecycle.rehydrate"))
    }

    @Test
    fun `host starts foreground before submitting the worker and always stops itself`() {
        val foreground = service.indexOf("startForegroundOnce()")
        val submit = service.indexOf("worker.submit")
        assertTrue(foreground in 1 until submit, "startForeground must precede worker submission")
        assertTrue(service.contains("stopSelf(startId)"))
        assertTrue(service.contains("AutomationReason.DEADLINE_EXPIRED"))
        assertTrue(service.contains("HOST_BUDGET_MS = 60_000L"))
        assertTrue(service.contains("AutomationReason.NOTIFICATION_PERMISSION_DENIED"))
        assertTrue(service.contains("AutomationReason.NOTIFICATION_START_FAILED"))
        assertTrue(service.contains("task.cancel(true)"))
    }

    @Test
    fun `host delegates every mutation to the existing manual intent owner`() {
        // 2026-07-27: origin do façade đặt (runBootAutomationIntent) nên service không còn dựng nó.
        // Hợp đồng cần giữ: mutation vẫn đi qua chủ sở hữu manual-intent, không tự plan/execute.
        assertTrue(service.contains("runBootAutomationIntent("))
        assertTrue(service.contains("automationRequestId = requestId"))
        assertFalse(service.contains("coordinator.execute"))
        assertFalse(service.contains("coordinator.plan("))
        assertFalse(service.contains("ClusterCast."))
    }

    @Test
    fun `exactly one request bound revalidation alarm is scheduled`() {
        assertTrue(service.contains("consumeReevaluation"))
        assertTrue(service.contains("AutomationReason.REEVALUATION_EXHAUSTED"))
        assertEquals(1, Regex("scheduleRevalidation\\(").findAll(service).count() - 1)
        assertTrue(service.contains("PendingIntent.getForegroundService"))
    }

    @Test
    fun `automation host is declared non exported in the default process with special use type`() {
        val declaration = manifest.substringAfter(".modules.clustercast.CastAutomationService")
            .substringBefore("</service>")
        assertTrue(declaration.contains("android:exported=\"false\""))
        assertTrue(declaration.contains("android:foregroundServiceType=\"specialUse\""))
        assertTrue(declaration.contains("PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))
        assertFalse(declaration.contains("android:process"))
        listOf(
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.RECEIVE_BOOT_COMPLETED",
        ).forEach { assertTrue(manifest.contains(it), "missing permission $it") }
    }

    @Test
    fun `death after record leaves an unclaimed zero effect request that only the same boot may enqueue`() {
        val fixture = Fixture()
        val request = fixture.armed()
        assertEquals(AutomationRequestState.PENDING, request.state)
        assertNull(fixture.envelope().transaction)
        assertNull(fixture.envelope().pendingIntent)
        val sameBoot = checkNotNull(fixture.settings.recordOrGet("11", UUID.randomUUID()))
        assertEquals(request.requestId, sameBoot.requestId)
        fixture.now = 4_000L
        val nextBoot = checkNotNull(fixture.settings.recordOrGet("12"))
        assertFalse(nextBoot.requestId == request.requestId)
        assertEquals(AutomationReason.BOOT_ROLLOVER, fixture.settings.outcome()?.reason)
    }

    @Test
    fun `claimed with no journal is blocked after restart and never reclaimed`() {
        val fixture = Fixture()
        val request = fixture.armed()
        fixture.now = 2_000L
        fixture.settings.claim(request.requestId)
        fixture.now = 3_000L
        val outcome = checkNotNull(
            fixture.settings.terminalize(
                request.requestId, AutomationRequestState.BLOCKED,
                AutomationReason.CLAIMED_NO_EFFECT_AFTER_RESTART,
            ),
        )
        assertEquals(AutomationReason.CLAIMED_NO_EFFECT_AFTER_RESTART, outcome.reason)
        assertTrue(fixture.settings.claim(request.requestId) is CastAutomationSettings.ClaimResult.Rejected)
        assertEquals(AutomationDisposition.BLOCKED, fixture.settings.disposition())
    }

    @Test
    fun `auto origin pending is bound to the claim and can never resume as user origin`() {
        val fixture = Fixture()
        val request = fixture.armed()
        fixture.now = 2_000L
        fixture.settings.claim(request.requestId)
        assertTrue(fixture.settings.bindAutoPending(request.requestId))
        val pending = checkNotNull(fixture.envelope().pendingIntent)
        assertEquals(CastIntentOrigin.BOOT_AUTO, pending.origin)
        assertEquals(request.requestId, pending.automationRequestId)
        assertFalse(fixture.envelope().pendingIsUser)
        assertTrue(pending.matches(request.requestId))
        assertFalse(pending.matches(UUID.randomUUID()))
    }

    @Test
    fun `stop clears both origins and fences the epoch`() {
        val fixture = Fixture()
        val request = fixture.armed()
        fixture.now = 2_000L
        fixture.settings.claim(request.requestId)
        fixture.settings.bindAutoPending(request.requestId)
        val before = fixture.envelope().durableEpoch
        fixture.store.locked { bumpEpoch { it.copy(stopRequested = true, pendingIntent = null) } }
        val after = fixture.envelope()
        assertNull(after.pendingIntent)
        assertTrue(after.durableEpoch > before)
        fixture.now = 3_000L
        assertEquals(
            AutomationRequestState.SUPERSEDED,
            checkNotNull(
                fixture.settings.terminalize(
                    request.requestId, AutomationRequestState.SUPERSEDED, AutomationReason.STOP_SUPERSEDED,
                ),
            ).terminalState,
        )
    }

    @Test
    fun `no host thread can escape as an uncaught crash and every exit stops the service`() {
        assertTrue(service.contains("catch (failure: Throwable)"))
        assertTrue(service.contains("runCatching { stopSelf(startId) }"))
        assertTrue(service.contains("runCatching {\n                val runtime = CastAndroidRuntime.create(applicationContext)"))
        assertTrue(service.contains("onFailure { Log.e(TAG, \"terminalize failed\", it) }"))
        assertTrue(receiver.contains("onFailure { Log.e(TAG, \"Cast rehydrate failed\", it) }"))
        assertTrue(receiver.contains("onFailure { Log.e(TAG, \"bubble restore failed\", it) }"))
    }

    @Test
    fun `an abandoned host can neither claim nor bind nor orchestrate`() {
        assertTrue(service.contains("@Volatile private var abandoned = false"))
        assertTrue(service.contains("if (abandoned) return"))
        val claimAt = service.indexOf("settings.claim(requestId)")
        val guardAt = service.lastIndexOf("if (abandoned) return", claimAt)
        assertTrue(guardAt in 1 until claimAt, "claim must be guarded by the abandonment flag")
        assertTrue(service.indexOf("abandoned = true") < service.indexOf("task.cancel(true)"))
        assertTrue(service.contains("if (!settings.bindAutoPending(requestId))"))
    }

    @Test
    fun `durable terminal writes never run on the service main thread`() {
        val onStart = service.substringAfter("override fun onStartCommand").substringBefore("private fun terminalizeOffMain")
        assertFalse(onStart.contains("settings.terminalize"))
        assertTrue(service.contains("\"cast-automation-terminal\""))
    }

    @Test
    fun `a failed revalidation alarm exhausts the request instead of leaving it deferred`() {
        assertTrue(service.contains("if (!scheduleRevalidation(applicationContext, request.requestId))"))
        assertTrue(service.contains("): Boolean {\n            val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false"))
        assertTrue(service.contains("setExactAndAllowWhileIdle"))
    }

    @Test
    fun `a queued or late transaction outcome is always finite`() {
        assertTrue(service.contains("AutomationReason.RECOVERY_OR_MANUAL"))
        assertTrue(service.contains("AutomationReason.PRIOR_JOURNAL\n                } else {"))
        assertTrue(service.contains("settings.terminalize(requestId, AutomationRequestState.BLOCKED, reason)"))
    }

    @Test
    fun `origin tagged orchestration is rejected unless the exact request is claimed`() {
        assertTrue(coordinator.contains("Stop requested; automation is fenced"))
        assertTrue(coordinator.contains("automation request is not claimed"))
        assertTrue(coordinator.contains("automation target no longer matches the claim"))
        assertTrue(coordinator.contains("pending placement belongs to another origin"))
        assertTrue(coordinator.contains("automation request id exists exactly for BOOT_AUTO origin"))
        val gate = coordinator.substringAfter("if (origin == CastIntentOrigin.BOOT_AUTO) {")
            .substringBefore("return manualIntentRunner().run")
        assertTrue(gate.indexOf("envelope.stopRequested") < gate.indexOf("request.targetPackage"))
    }

    @Test
    fun `a request left claimed by host or process death is terminalized on re-entry`() {
        assertTrue(service.contains("if (!request.claimable) {"))
        assertTrue(service.contains("AutomationReason.CLAIMED_NO_EFFECT_AFTER_RESTART"))
        assertTrue(service.contains("CastAutomationSettings.effectFree(envelope, request)"))
        assertTrue(service.indexOf("if (!request.claimable) {") < service.indexOf("deferForPriorJournal(settings, request)"))
        assertTrue(service.contains("AutomationReason.PRIOR_JOURNAL"))
    }

    @Test
    fun `the foreground obligation is released before any durable terminal write`() {
        val helper = service.substringAfter("private fun terminalizeOffMain").substringBefore("private fun terminalize(")
        assertTrue(helper.indexOf("stopSelf(startId)") < helper.indexOf("terminalize(requestId, reason)"))
    }

    @Test
    fun `an unreadable boot identity prevents any request`() {
        val fixture = Fixture()
        fixture.settings.setDefault(target)
        fixture.settings.accept()
        assertTrue(service.contains("takeIf { it >= 0 }"))
        assertTrue(service.contains("observedBootId(app) ?: return null"))
        val request = fixture.settings.recordOrGet("11")
        assertEquals("11", checkNotNull(request).bootId)
        assertEquals(request.bootId, fixture.envelope().bootId)
    }
}

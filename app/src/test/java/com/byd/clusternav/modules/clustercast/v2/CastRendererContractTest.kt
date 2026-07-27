package com.byd.clusternav.modules.clustercast.v2

import com.byd.clusternav.modules.clustercast.CastUiMutationSnapshot
import com.byd.clusternav.modules.clustercast.activityActions

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastRendererContractTest {
    @Test fun `renderer exports every action exactly once and only projector allowed actions are enabled`() {
        val state = CastUiStateProjector.project(base().copy(
            stableState = StableState.ACTIVE_VERIFIED,
            stableConverged = true,
            target = CastTarget("com.example.maps", 1, 1),
        ))
        val model = CastUiRenderer.render(state, instant(2_000))
        assertTrue(model.actions.single { it.action == CastAction.STOP }.enabled)
        assertFalse(model.actions.single { it.action == CastAction.CAST }.enabled)
        assertNull(model.actions.single { it.action == CastAction.STOP }.disabledReason)
        assertTrue(model.actions.map { it.action }.toSet() == CastAction.entries.toSet())
        assertFalse(model.durableStatusPriority)
        assertThrows(UnsupportedOperationException::class.java) { (model.actions as MutableList).clear() }
    }

    @Test fun `legacy state never renders interactive Stop and has durable status priority`() {
        val state = CastUiStateProjector.project(base().copy(engineVersion = EngineVersion.LEGACY, observedNonIdle = true))
        val model = CastUiRenderer.render(state, instant(2_000))
        val stop = model.actions.single { it.action == CastAction.STOP }
        assertFalse(stop.enabled)
        assertTrue(stop.disabledReason == DisabledReason.LEGACY_SESSION_UNSAFE)
        assertTrue(model.durableStatusPriority)
    }

    @Test fun `Stop recovery and manual states have durable status priority`() {
        val states = listOf(
            CastUiStateProjector.project(base().copy(stopRequested = true)),
            CastUiStateProjector.project(base().copy(
                recoverySubstate = RecoverySubstate.UNKNOWN_EFFECT_STOP_AVAILABLE,
            )),
            CastUiStateProjector.project(base().copy(decodeValid = false)),
        )
        states.forEach { assertTrue(CastUiRenderer.render(it, instant(2_000)).durableStatusPriority) }
    }

    @Test fun `Stop acknowledgement becomes explicit only after durable state and times out after 500 milliseconds`() {
        val state = CastUiStateProjector.project(base().copy(
            stableState = StableState.IDLE_VERIFIED,
            stableConverged = true,
        ))
        val atBoundary = CastUiRenderer.render(state, instant(1_500), instant(1_000))
        assertFalse(atBoundary.operationAcknowledged)
        assertFalse(atBoundary.stopAcknowledgementTimedOut)
        val afterBoundary = CastUiRenderer.render(state, instant(1_501), instant(1_000))
        assertFalse(afterBoundary.operationAcknowledged)
        assertTrue(afterBoundary.stopAcknowledgementTimedOut)
        assertEquals("Chưa nhận xác nhận trong 500 ms", afterBoundary.status)

        val durableStop = CastUiStateProjector.project(base().copy(stopRequested = true))
        val acknowledged = CastUiRenderer.render(durableStop, instant(1_100), instant(1_000))
        assertTrue(acknowledged.operationAcknowledged)
        assertFalse(acknowledged.stopAcknowledgementTimedOut)
    }

    @Test fun `pre-existing operation cannot acknowledge Stop and durable recovery suppresses timeout`() {
        val activeTransaction = CastUiStateProjector.project(base().copy(
            transaction = PlannerUiProjection(
                OperationPhase.ACTIVATING,
                java.util.UUID(0L, 1L),
                instant(10_000),
                StopDisposition(StopDispositionKind.AVAILABLE),
                NextSafeAction.REQUEST_STOP,
                setOf(CastAction.STOP),
            ),
        ))
        val preExisting = CastUiRenderer.render(activeTransaction, instant(1_100), instant(1_000))
        assertFalse(preExisting.operationAcknowledged)

        val recovery = CastUiStateProjector.project(base().copy(
            recoverySubstate = RecoverySubstate.UNKNOWN_EFFECT_STOP_AVAILABLE,
        ))
        val durable = CastUiRenderer.render(recovery, instant(1_501), instant(1_000))
        assertTrue(durable.durableStatusPriority)
        assertTrue(durable.stopAcknowledgementTimedOut)
        assertFalse(durable.status == "Chưa nhận xác nhận trong 500 ms")
    }

    @Test fun `pristine missing named display exposes cold actions without Retry`() {
        val model = CastRuntimeUi.render(
            StoreRead.Loaded(pristine()),
            ObservationValue.Unknown(MISSING_NAMED_CLUSTER_DISPLAY_REASON),
            instant(2_000),
        )
        assertEquals(
            setOf(
                CastAction.CAST,
                CastAction.CHOOSE_ANOTHER_APP,
                CastAction.OPEN_APP_MANAGER,
                CastAction.OPEN_DIAGNOSTICS,
            ),
            model.actions.filter { it.enabled }.map { it.action }.toSet(),
        )
        assertFalse(model.actions.single { it.action == CastAction.RETRY_CONNECT }.enabled)
        assertEquals("Cluster Cast sẵn sàng tạo cụm", model.title)
        assertEquals("Chọn ứng dụng để chiếu", model.status)
        assertEquals(
            setOf(CastAction.STOP, CastAction.CHOOSE_ANOTHER_APP),
            model.activityActions(CastUiMutationSnapshot(1L, pending = true)),
        )
    }

    @Test fun `bootstrap transaction permits latest target selection without another Cast or Retry`() {
        val envelope = pristine().copy(
            durableEpoch = 1L,
            pendingIntent = PendingCastIntent("com.example.first"),
            transaction = bootstrapTransaction(),
        )
        val model = CastRuntimeUi.render(
            StoreRead.Loaded(envelope),
            ObservationValue.Unknown(MISSING_NAMED_CLUSTER_DISPLAY_REASON),
            instant(2_000),
        )
        assertEquals(
            setOf(CastAction.STOP, CastAction.CHOOSE_ANOTHER_APP),
            model.actions.filter { it.enabled }.map { it.action }.toSet(),
        )
        assertFalse(model.actions.single { it.action == CastAction.CAST }.enabled)
        assertFalse(model.actions.single { it.action == CastAction.RETRY_CONNECT }.enabled)
        assertEquals(
            setOf(CastAction.STOP, CastAction.CHOOSE_ANOTHER_APP),
            model.activityActions(CastUiMutationSnapshot(2L, pending = true)),
        )
    }

    @Test fun `non-pristine or different Unknown reason remains Diagnostics only`() {
        val cases = listOf(
            StoreRead.Loaded(pristine().copy(durableEpoch = 1L)) to
                ObservationValue.Unknown(MISSING_NAMED_CLUSTER_DISPLAY_REASON),
            StoreRead.Loaded(pristine()) to ObservationValue.Unknown("display observation unavailable"),
        )
        cases.forEach { (storeRead, observation) ->
            val model = CastRuntimeUi.render(storeRead, observation, instant(2_000))
            assertEquals(
                setOf(CastAction.OPEN_DIAGNOSTICS),
                model.actions.filter { it.enabled }.map { it.action }.toSet(),
            )
        }
    }

    @Test fun `Activity separates tokenized operation status from Stop acknowledgement`() {
        val activity = source("main/java/com/byd/clusternav/modules/clustercast/ClusterCastActivity.kt")
        val timers = source("main/java/com/byd/clusternav/modules/clustercast/CastActivityStatusTimers.kt")
        assertTrue(activity.contains("operationStatus.begin(initial)"))
        assertTrue(activity.contains("operationStatus.complete(token, message"))
        assertTrue(activity.contains("operationStatus.snapshot(token"))
        assertTrue(activity.contains("operationStatus.clearAll()"))
        assertTrue(activity.contains("statusTimers.scheduleStopAckRefresh"))
        assertTrue(activity.indexOf("work.misc {") in 0 until activity.indexOf("facade.initialize"))
        val stop = activity.substring(activity.indexOf("private fun executeStop"), activity.indexOf("private fun continueStopAfterAcknowledgement"))
        assertTrue(stop.indexOf("work.stop {") in 0 until stop.indexOf("facade.requestStop()"))
        assertTrue(Regex("stopRequestedAt = null").findAll(stop).count() >= 2)
        assertTrue(stop.indexOf("statusTimers.cancelStopAckRefresh()") in 0 until stop.lastIndexOf("continueStopAfterAcknowledgement()"))
        // 2026-07-27: ngân sách chờ được truyền vào timers thay vì timers tự đọc hằng số của tầng dưới.
        assertTrue(timers.contains("graceMillis + 1L"))
        assertTrue(activity.contains("facade.stopAcknowledgementGraceMillis()"))
        assertTrue(timers.contains("operationStatus.expire(token"))
        assertFalse(activity.contains("operationRequestedAt"))
        assertFalse(Regex("(?m)^\\s*Thread \\{").containsMatchIn(activity))
        assertTrue(activity.lineSequence().count() < 501)
    }

    @Test fun `manual Cast owns bootstrap and lifecycle only resumes durable pending placement`() {
        val activity = source("main/java/com/byd/clusternav/modules/clustercast/ClusterCastActivity.kt")
        val refreshReader = source("main/java/com/byd/clusternav/modules/clustercast/CastActivityRefresh.kt")
        // 2026-07-27: Activity đi qua CastFacade; hợp đồng giữ nguyên là "không tự gọi bootstrap"
        assertEquals(0, Regex("(coordinator|facade)\\.bootstrap\\(").findAll(activity).count())
        assertEquals(1, Regex("facade\\.runManualIntent\\(").findAll(activity).count())
        assertTrue(activity.contains("facade.resumePendingIntent"))
        val initialization = activity.substring(activity.indexOf("facade.initialize"), activity.indexOf("override fun onNewIntent"))
        assertTrue(initialization.contains("reconcileSelectionAndDrain()"))
        val reconciliation = activity.substring(activity.indexOf("private fun reconcileSelectionAndDrain"), activity.indexOf("private fun drainPendingTarget"))
        assertTrue(reconciliation.indexOf("queueLatestTarget(packageName)") in 0 until reconciliation.lastIndexOf("drainPendingTarget()"))
        val resume = activity.substring(activity.indexOf("private fun drainPendingTarget"), activity.indexOf("private fun openAdjustment"))
        assertTrue(resume.indexOf("currentSelectionRevision()") in 0 until resume.indexOf("resumePendingIntent"))
        assertTrue(resume.contains("isCurrentSelection(selectionRevision)"))
        assertTrue(resume.contains("target == pending"))
        assertTrue(resume.contains("selectedPackage == null || selectedPackage == pending"))
        assertTrue(activity.contains("work.mutationSnapshot()"))
        assertTrue(activity.contains("work.isCurrentMutation(mutationSnapshot)"))
        assertTrue(activity.contains("&& result.selectedEligible"))
        // 2026-07-27: phép kiểm eligibility chuyển vào façade; hợp đồng giữ nguyên là chỉ chiếu khi Ready.
        assertTrue(refreshReader.contains("facade.selectionReady("))
        val selection = activity.substring(
            activity.indexOf("private fun selectApp"),
            activity.indexOf("private fun openAppManager"),
        )
        assertTrue(selection.indexOf("refresh()") in 0 until selection.indexOf("work.selection"))
        assertTrue(selection.contains("facade.queueLatestTarget(packageName)"))
        assertTrue(activity.contains("isEnabled = false; minimumHeight = dp(56)"))
        assertTrue(activity.contains("if (values.isEmpty()) apps.addView") && activity.contains("refresh()\n            }"))
        listOf(
            "main/java/com/byd/clusternav/modules/clustercast/FloatingBubbleService.kt",
            "main/java/com/byd/clusternav/modules/clustercast/DiagActivity.kt",
            "main/java/com/byd/clusternav/modules/clustercast/CastLifecycleReceiver.kt",
            "main/java/com/byd/clusternav/RebindReceiver.kt",
        ).forEach { relative -> assertFalse(source(relative).contains("coordinator.bootstrap("), relative) }
    }

    private fun bootstrapTransaction() = CastTransaction(
        operationId = java.util.UUID(0L, 70L),
        epoch = 1L,
        operation = CastOperation.BOOTSTRAP,
        phase = OperationPhase.ACTIVATING,
        sourcePkg = null,
        targetPkg = "com.example.first",
        targetClass = TargetClass.NORMAL.name,
        expectedDisplayIdentity = "bootstrap-pending",
        baseline = CastBaseline(),
        ledger = (SealDl3BootstrapProfile.forwardKinds + SealDl3BootstrapProfile.compensationKinds)
            .mapIndexed { index, kind ->
                LedgerStep("bootstrap-$index", "fixed", kind, index.toLong(), null, LedgerEffect.PLANNED, null, false)
            },
        retries = 0,
        deadlineAtEpochMillis = 60_000L,
        lastFailure = null,
        expectedPostcondition = "two equal known clean named-display observations",
        compensationUsed = false,
    )

    private fun pristine() = CastSessionEnvelope(
        durableEpoch = 0L, bootId = "boot-a", stopRequested = false, pendingIntent = null,
        effectiveUiVersion = EngineVersion.V2, pendingUiRollback = false,
        stableSession = null, transaction = null,
    )

    private fun source(relative: String): String {
        val current = Path.of(System.getProperty("user.dir"))
        val app = if (Files.exists(current.resolve("src"))) current else current.resolve("app")
        return app.resolve("src").resolve(relative).toFile().readText()
    }

    private fun base() = CastProjectionInput(
        true, EngineVersion.V2, false, false, null, null, null, null, false,
        InteractionContext(InteractionContextValue.PARKED, "test", instant(1), instant(10_000), null),
        null, null, null, 1, instant(2_000),
    )
    private fun instant(value: Long) = Instant.ofEpochMilli(value)
}

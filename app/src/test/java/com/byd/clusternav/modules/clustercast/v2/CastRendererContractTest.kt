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
    /** Hai phép không phát lệnh ra xe nên luôn bật: xem Chẩn đoán, và chọn app để chuẩn bị. */
    private val ALWAYS = setOf(CastAction.OPEN_DIAGNOSTICS, CastAction.SELECT_TARGET_APP, CastAction.OPEN_APP_MANAGER)

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
            ALWAYS + setOf(
                CastAction.CAST,
                CastAction.CHOOSE_ANOTHER_APP,
                CastAction.OPEN_APP_MANAGER,
            ),
            model.actions.filter { it.enabled }.map { it.action }.toSet(),
        )
        assertFalse(model.actions.single { it.action == CastAction.RETRY_CONNECT }.enabled)
        assertEquals("Cluster Cast sẵn sàng tạo cụm", model.title)
        assertEquals("Chọn ứng dụng để chiếu", model.status)
        assertEquals(
            ALWAYS + setOf(CastAction.STOP, CastAction.CHOOSE_ANOTHER_APP),
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
            ALWAYS + setOf(CastAction.STOP, CastAction.CHOOSE_ANOTHER_APP),
            model.actions.filter { it.enabled }.map { it.action }.toSet(),
        )
        assertFalse(model.actions.single { it.action == CastAction.CAST }.enabled)
        assertFalse(model.actions.single { it.action == CastAction.RETRY_CONNECT }.enabled)
        assertEquals(
            ALWAYS + setOf(CastAction.STOP, CastAction.CHOOSE_ANOTHER_APP),
            model.activityActions(CastUiMutationSnapshot(2L, pending = true)),
        )
    }

    @Test fun `a different Unknown reason (not the exact missing-cluster-display one) remains Diagnostics only`() {
        val model = CastRuntimeUi.render(
            StoreRead.Loaded(pristine()),
            ObservationValue.Unknown("display observation unavailable"),
            instant(2_000),
        )
        assertEquals(ALWAYS, model.actions.filter { it.enabled }.map { it.action }.toSet())
    }

    /**
     * 2026-07-29: before this fix, a non-zero epoch alone (with `stableSession`/`transaction` both
     * null) kept this Diagnostics-only forever — `durableEpoch == 0L` used to be equivalent to
     * "nothing recorded" only because nothing else ever nulled `stableSession` after the epoch moved
     * past zero. `CastCoordinator.reconcileUnobservableIdleSession()` now does exactly that for a
     * stale post-reboot idle claim, so this same envelope shape (nothing recorded, missing cluster
     * display) must reach cold-pristine and offer CAST regardless of the epoch value. See
     * `CastReconcileUnobservableIdleSessionTest`/`CastPostBootIdleRecoveryTest` (core) for the
     * coordinator-level half of this fix.
     */
    @Test fun `a non-zero epoch with nothing else recorded now reaches cold pristine, not Diagnostics only`() {
        val model = CastRuntimeUi.render(
            StoreRead.Loaded(pristine().copy(durableEpoch = 1L)),
            ObservationValue.Unknown(MISSING_NAMED_CLUSTER_DISPLAY_REASON),
            instant(2_000),
        )
        assertEquals(
            ALWAYS + setOf(CastAction.CAST, CastAction.CHOOSE_ANOTHER_APP, CastAction.OPEN_APP_MANAGER),
            model.actions.filter { it.enabled }.map { it.action }.toSet(),
        )
    }

    @Test fun `Activity separates tokenized operation status from Stop acknowledgement`() {
        // 2026-07-29: đổi target từ ClusterCastActivity (xoá) sang MainActivityCastController — cùng
        // logic operationStatus/statusTimers, chỉ khác Activity nào giữ nó.
        val activity = source("main/java/com/byd/clusternav/modules/clustercast/MainActivityCastController.kt")
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

    /**
     * 2026-07-29: đơn giản hoá theo docs/specs/cast-simplified-active-app-toggle.html — không còn màn
     * chọn app (`selectApp`/`queueLatestTarget`/`resumePendingIntent`/`CastAppListView`), vì bong bóng tự
     * dò app đang mở và chiếu thẳng, còn auto-start-khi-mở-app cũng gọi thẳng `executeCast`. Cái CÒN PHẢI
     * giữ đúng: không nơi nào tự gọi `coordinator.bootstrap()` trực tiếp — mọi cast đi qua đúng MỘT cửa
     * `facade.runManualIntent(...)`, và bootstrap chỉ xảy ra bên trong façade/coordinator khi cần.
     */
    @Test fun `manual Cast owns bootstrap, no surface calls coordinator bootstrap directly`() {
        val controller = source("main/java/com/byd/clusternav/modules/clustercast/MainActivityCastController.kt")
        assertEquals(0, Regex("(coordinator|facade)\\.bootstrap\\(").findAll(controller).count())
        assertEquals(1, Regex("facade\\.runManualIntent\\(").findAll(controller).count())
        assertTrue(controller.contains("work.mutationSnapshot()"))
        assertTrue(controller.contains("work.isCurrentMutation(mutationSnapshot)"))
        listOf(
            "main/java/com/byd/clusternav/modules/clustercast/FloatingBubbleService.kt",
            "main/java/com/byd/clusternav/modules/clustercast/DiagActivity.kt",
            "main/java/com/byd/clusternav/modules/clustercast/CastLifecycleReceiver.kt",
            "main/java/com/byd/clusternav/RebindReceiver.kt",
        ).forEach { relative -> assertFalse(source(relative).contains("coordinator.bootstrap("), relative) }
    }

    /**
     * 2026-07-29: cả hai bề mặt giờ đơn giản như nhau — không còn `CastRetryPrompt`/`allowDestructive`
     * (hộp thoại tự leo thang escalate) ở bất kỳ đâu; tap đầu tiên luôn không-destructive trên cả bong
     * bóng lẫn auto-start-khi-mở-app, cùng một hình dạng gọi `facade.runManualIntent(...)`.
     */
    @Test fun `bubble tap-to-cast dispatches the same runManualIntent shape as Home's auto-start Cast`() {
        val bubble = source("main/java/com/byd/clusternav/modules/clustercast/FloatingBubbleService.kt")
        val controller = source("main/java/com/byd/clusternav/modules/clustercast/MainActivityCastController.kt")
        assertEquals(1, Regex("facade\\.runManualIntent\\(").findAll(bubble).count())
        val bubbleDispatch = bubble.substring(
            bubble.indexOf("private fun dispatchTarget"),
            bubble.indexOf("private fun requestStopOnce"),
        )
        assertTrue(bubbleDispatch.contains("catalog.clusterDensityDpi(packageName)"))
        assertTrue(bubbleDispatch.contains("catalog.clusterStyle(packageName)"))
        assertFalse(bubbleDispatch.contains("allowDestructive"))
        assertFalse(bubbleDispatch.contains("CastRetryPrompt"))
        val controllerDispatch = controller.substring(
            controller.indexOf("private fun executeCast"),
            controller.indexOf("private fun executeStop"),
        )
        assertTrue(controllerDispatch.contains("catalog.clusterDensityDpi(pkg)"))
        assertTrue(controllerDispatch.contains("catalog.clusterStyle(pkg)"))
        assertFalse(controllerDispatch.contains("allowDestructive"))
        assertFalse(controllerDispatch.contains("CastRetryPrompt"))
    }

    /**
     * FIXED 2026-07-28 (was a KNOWN GAP found during bubble-menu-actions re-verification): the Activity
     * completes a user Stop with TWO steps -- `facade.requestStop()` (durably marks
     * `stopRequested=true`, fences in-flight work; per `CastManualIntentTest`'s "Stop at bootstrap
     * stable boundary…" / "…ordinary cast…" cases this alone issues ZERO `CommandKind`) and then, once
     * accepted with nothing in flight, the shared `CastFacade.continueStopAfterAcknowledgement(...)`,
     * which actually plans and dispatches the opcode sequence that returns the cluster to the clock.
     * `FloatingBubbleService.requestStopOnce()` used to only make the first call, so tapping "Dừng
     * chiếu" in the bubble menu recorded the Stop request and updated the projected status without
     * ever returning the cluster display. It now drives the same second step through the same shared
     * façade method the Activity uses -- not a bubble-local reimplementation of plan/execute.
     */
    @Test fun `bubble requestStopOnce now drives the same continueStopAfterAcknowledgement dispatch as the Activity`() {
        // 2026-07-29: đổi target từ ClusterCastActivity (xoá) sang MainActivityCastController.
        val activity = source("main/java/com/byd/clusternav/modules/clustercast/MainActivityCastController.kt")
        assertTrue(activity.contains("facade.requestStop()"))
        val delegateCall = "facade.continueStopAfterAcknowledgement { pkg -> catalog.evidence(pkg, facade.phoneSession(pkg)) }"
        assertTrue(activity.contains(delegateCall))

        val bubble = source("main/java/com/byd/clusternav/modules/clustercast/FloatingBubbleService.kt")
        val bubbleStop = bubble.substring(
            bubble.indexOf("private fun requestStopOnce"),
            bubble.indexOf("private fun bubbleText"),
        )
        assertTrue(bubbleStop.contains("facade.requestStop()"))
        // The bubble must not reimplement plan/execute locally -- it delegates to the same shared call.
        assertFalse(bubbleStop.contains("facade.planStop("))
        assertFalse(bubbleStop.contains("facade.executeAndSettle("))
        assertTrue(bubbleStop.contains(delegateCall))

        val facadeSrc = source("main/java/com/byd/clusternav/modules/clustercast/CastFacade.kt")
        assertTrue(facadeSrc.contains("fun continueStopAfterAcknowledgement(evidenceFor: (String) -> TargetEvidence?): String"))
        assertTrue(facadeSrc.contains("val plan = planStop(pkg, pkg?.let(evidenceFor))"))
        assertTrue(facadeSrc.contains("executeAndSettle(plan, pkg)"))
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

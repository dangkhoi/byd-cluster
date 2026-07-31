package com.byd.clusternav.cast.transport

import com.byd.clusternav.modules.clustercast.v2.AcceptedGeometry
import com.byd.clusternav.modules.clustercast.v2.AdjustmentApplyState
import com.byd.clusternav.modules.clustercast.v2.AdjustmentResult
import com.byd.clusternav.modules.clustercast.v2.CastAdjustmentWorkspace
import com.byd.clusternav.modules.clustercast.v2.CastIntent
import com.byd.clusternav.modules.clustercast.v2.CastIntentKind
import com.byd.clusternav.modules.clustercast.v2.CastRect
import com.byd.clusternav.modules.clustercast.v2.CastTarget
import com.byd.clusternav.modules.clustercast.v2.CommandKind
import com.byd.clusternav.modules.clustercast.v2.ExecutionResult
import com.byd.clusternav.modules.clustercast.v2.ObservationValue
import com.byd.clusternav.modules.clustercast.v2.ObservedCoarseState
import com.byd.clusternav.modules.clustercast.v2.ObservedState
import com.byd.clusternav.modules.clustercast.v2.PlanResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Extends [CastE2ECommandHarness.kt] (per its own KDoc, which names "geometry adjust" as one of the
 * Coverage-phase scenarios the foundation was built for) to trace the UX8 draft-vs-accepted-geometry
 * contract (`docs/specs/clusternav-uxui-rebaseline.html`, row UX8) all the way from the same primitives
 * [com.byd.clusternav.modules.clustercast.CastFacade.applyGeometry] calls — `adjustment.edit` →
 * `adjustment.beginApply` → `coordinator.plan`/`execute` → `adjustment.bindExecution` →
 * `coordinator.observeAndComplete` → `coordinator.observe` ×2 → `adjustment.recordVerifiedApply` →
 * `adjustment.done` — down to the exact `am task resize` / `wm density` strings
 * [CastGeometryCommandEncoder] emits through the REAL [CastPlacementCommands] ladder.
 *
 * [CastAdjustmentWorkspaceTest] (`:core`) already proves the store-level contract with a hand-built
 * transaction/observed pair. What was still unverified end-to-end: that the SAME durable envelope the
 * workspace reads/writes is the one [CastGeometryE2ECommandLogTest] drives through a real
 * plan→execute→verify cycle that actually dispatches [CastPlacementCommands]-encoded shell strings, and
 * that `StableCastSession.acceptedGeometry` — the value every later encode call binds to — never moves
 * off the prior accepted geometry except through the explicit, two-sample-verified `Done` call.
 */
class CastGeometryE2ECommandLogTest {
    private val target = CastTarget("com.example.maps", 7, 2)
    private val entry = AcceptedGeometry(CastRect(0, 0, 1920, 720), 160, "android-user-10")
    private val changed = AcceptedGeometry(CastRect(96, 36, 1824, 684), 200, "android-user-10")

    private fun observedWith(geometry: AcceptedGeometry) = ObservedState(
        ObservedCoarseState.ACTIVE_SINGLE, "display-2", target, setOf(target.packageName), null, geometry,
    )

    private fun evidence() = e2eNormalTargetEvidence()

    @Test
    fun `geometry apply dispatches real placement commands but only commits accepted geometry through Done`() {
        val world = CastVehicleWorld()
        val fixture = castE2EFixture(stable = e2eActiveSession(target.packageName).copy(acceptedGeometry = entry), epoch = 4L, world = world)
        val workspace = CastAdjustmentWorkspace(fixture.store)
        val entryObserved = observedWith(entry)
        val changedObserved = observedWith(changed)

        assertTrue(workspace.open(entryObserved) is AdjustmentResult.Ready)
        workspace.edit(changed)
        val applying = (workspace.beginApply(entryObserved) as AdjustmentResult.Ready).draft
        assertEquals(AdjustmentApplyState.APPLYING, applying.applyState)

        // ── plan + execute: this is the exact CastCoordinator call CastFacade.applyGeometry makes ──
        fixture.scriptStates(entryObserved)
        val plan = fixture.coordinator.plan(
            CastIntent(CastIntentKind.APPLY_GEOMETRY, target.packageName, geometry = changed, expectedTarget = target),
            evidence(), installed = true, hasLauncher = true,
        )
        assertTrue(plan is PlanResult.Ready)
        assertEquals(
            listOf(CommandKind.APPLY_TASK_GEOMETRY, CommandKind.APPLY_DISPLAY_GEOMETRY),
            (plan as PlanResult.Ready).plan.steps.map { it.commandKind },
        )
        val execution = fixture.coordinator.execute(plan, target.packageName)
        assertTrue(execution is ExecutionResult.AwaitingVerification, "expected AwaitingVerification, got $execution")
        execution as ExecutionResult.AwaitingVerification

        // The REAL CastPlacementCommands-encoded strings were dispatched — not a stand-in.
        assertEquals(
            "kind=APPLY_TASK_GEOMETRY shell=am task resize 7 96 36 1824 684\n" +
                "kind=APPLY_DISPLAY_GEOMETRY shell=wm density 200 -d 2",
            fixture.commandLog(),
        )

        val bound = workspace.bindExecution(execution.operationId, execution.epoch)
        assertTrue(bound is AdjustmentResult.Ready)

        // Two-sample convergence at the coordinator/session layer (CastCoordinator.completeVerificationLocked).
        fixture.scriptStates(changedObserved, changedObserved)
        assertTrue(fixture.coordinator.observeAndComplete(execution.operationId))

        // Verified at the session layer already — but acceptedGeometry MUST NOT have moved yet.
        assertEquals(entry, fixture.envelope().stableSession!!.acceptedGeometry, "session-level verify must not promote accepted geometry")

        // Workspace-level two-sample verification (CastAdjustmentWorkspace.recordVerifiedApply), same as CastFacade.applyGeometry.
        fixture.scriptStates(changedObserved, changedObserved)
        val first = (fixture.coordinator.observe() as ObservationValue.Known).value
        val second = (fixture.coordinator.observe() as ObservationValue.Known).value
        val verified = workspace.recordVerifiedApply(first, second) as AdjustmentResult.Ready
        assertEquals(AdjustmentApplyState.APPLIED_VERIFIED, verified.draft.applyState)

        // Still no promotion: draft is verified, but Done has not been called yet.
        assertEquals(entry, fixture.envelope().stableSession!!.acceptedGeometry, "recordVerifiedApply alone must not promote accepted geometry")

        // Only the explicit, independently-converged Done call promotes accepted geometry.
        fixture.scriptStates(changedObserved, changedObserved)
        val d1 = (fixture.coordinator.observe() as ObservationValue.Known).value
        val d2 = (fixture.coordinator.observe() as ObservationValue.Known).value
        assertTrue(workspace.done(d1, d2) is AdjustmentResult.Ready)
        assertEquals(changed, fixture.envelope().stableSession!!.acceptedGeometry, "Done must promote the verified draft")
        assertEquals(null, fixture.envelope().adjustmentDraft)
    }

    @Test
    fun `unverified geometry apply never overwrites the previously accepted geometry`() {
        val world = CastVehicleWorld()
        val fixture = castE2EFixture(stable = e2eActiveSession(target.packageName).copy(acceptedGeometry = entry), epoch = 4L, world = world)
        val workspace = CastAdjustmentWorkspace(fixture.store)
        val entryObserved = observedWith(entry)

        workspace.open(entryObserved)
        workspace.edit(changed)
        workspace.beginApply(entryObserved)

        fixture.scriptStates(entryObserved)
        val plan = fixture.coordinator.plan(
            CastIntent(CastIntentKind.APPLY_GEOMETRY, target.packageName, geometry = changed, expectedTarget = target),
            evidence(), installed = true, hasLauncher = true,
        ) as PlanResult.Ready
        val execution = fixture.coordinator.execute(plan, target.packageName) as ExecutionResult.AwaitingVerification
        workspace.bindExecution(execution.operationId, execution.epoch)

        // The vehicle never converges on the requested geometry (task resize did not stick) — every
        // read-back still shows the OLD bounds/dpi, so completeVerificationLocked's terminal check
        // (`observed.geometry == tx.requestedGeometry`) never passes.
        fixture.scriptStates(entryObserved, entryObserved)
        assertFalse(fixture.coordinator.observeAndComplete(execution.operationId), "must not report converged when geometry never matched")

        workspace.markApplyFailed("Geometry chưa hội tụ")
        assertEquals(
            AdjustmentApplyState.FAILED_UNVERIFIED,
            fixture.envelope().adjustmentDraft!!.applyState,
        )
        assertEquals(entry, fixture.envelope().stableSession!!.acceptedGeometry, "a failed apply must leave accepted geometry untouched")

        // Done must refuse to promote a draft that was never verified, however the caller retries it.
        val rejected = workspace.done(entryObserved, entryObserved)
        assertTrue(rejected is AdjustmentResult.Rejected, "Done must reject an unverified draft")
        assertEquals(entry, fixture.envelope().stableSession!!.acceptedGeometry, "Done must not promote after rejection")
        assertTrue(fixture.envelope().adjustmentDraft != null, "a rejected Done must not silently clear the draft")

        // The placement commands were still genuinely dispatched (the apply attempt was real) —
        // only the commit was correctly withheld.
        assertEquals(
            "kind=APPLY_TASK_GEOMETRY shell=am task resize 7 96 36 1824 684\n" +
                "kind=APPLY_DISPLAY_GEOMETRY shell=wm density 200 -d 2",
            fixture.commandLog(),
        )
    }
}

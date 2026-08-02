package com.byd.clusternav.cast.transport

import com.byd.clusternav.modules.clustercast.v2.CastOperation
import com.byd.clusternav.modules.clustercast.v2.CastPlan
import com.byd.clusternav.modules.clustercast.v2.CastTarget
import com.byd.clusternav.modules.clustercast.v2.CommandKind
import com.byd.clusternav.modules.clustercast.v2.ExecutionResult
import com.byd.clusternav.modules.clustercast.v2.PlanResult
import com.byd.clusternav.modules.clustercast.v2.PlannedStep
import com.byd.clusternav.modules.clustercast.v2.StableState
import com.byd.clusternav.modules.clustercast.v2.TargetClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Isolates and proves CastExecutor's in-flight/double-dispatch guard for Stop, one layer below the
 * bug [CastE2ECommandLogTest.`Stop from an active session`] already confirmed: the REAL
 * `CastPlanner`-built Stop plan always ends with `SEAL_DL3_COMPENSATE_18`/`_0`
 * (CastPlanner.kt:119-128), which are exactly `SealDl3BootstrapProfile.compensationKinds` — so
 * `CastExecutor.executeLocked`'s anti-bootstrap-replay check (`plan.steps.any { it.commandKind in
 * BOOTSTRAP_COMMANDS }`, CastExecutor.kt:154-156) rejects EVERY Stop plan before a transaction is ever
 * created. That means there is never an in-flight Stop transaction through the production path for a
 * second tap to race against, so the double-dispatch guard cannot be exercised through
 * `CastPlanner.plan(STOP, ...)` today.
 *
 * This test drives a hand-built Stop-shaped [CastPlan] directly through [CastCoordinator.execute] — the
 * exact same steps `CastPlanner` emits for `TargetClass.NORMAL`, minus only the two colliding
 * compensation steps — to prove the guard CastExecutor actually relies on
 * (`if (loaded.envelope.transaction != null) return@locked null`, CastExecutor.kt:148, which runs
 * BEFORE the buggy bootstrap-collision check at :154) really does reject a second Stop attempt made
 * while the first is awaiting verification, with zero additional commands dispatched, and that the
 * first attempt still converges to a clean IDLE_VERIFIED terminal state with `stopRequested` cleared.
 *
 * This does NOT prove the production Stop button works — it currently does not (see
 * [CastE2ECommandLogTest]). It proves the concurrency/no-double-dispatch mechanism the production Stop
 * path depends on is sound, so once the collision bug above is fixed, this same operation-agnostic
 * guard protects Stop's real dispatch too.
 */
class CastStopConcurrencyTest {

    @Test
    fun `second Stop attempt while first is awaiting verification is rejected, not double-dispatched`() {
        val target = "com.example.maps"
        val fixture = castE2EFixture(stable = e2eActiveSession(target), epoch = 4L)
        // Two identical IDLE_CLEAN samples: observeAndComplete requires two matching stable reads.
        fixture.scriptStates(e2eIdle(), e2eIdle())

        val accepted = fixture.coordinator.requestStop()
        assertTrue(accepted != null && accepted.stopRequested, "Stop must be journaled before any mutation is planned")
        val epoch = fixture.envelope().durableEpoch

        // Same shape CastPlanner emits for TargetClass.NORMAL (CastPlanner.kt:75-99), minus the two
        // SEAL_DL3_COMPENSATE_18/0 steps that collide with BOOTSTRAP_COMMANDS.
        val strippedStopPlan = CastPlan(
            operation = CastOperation.STOP,
            epoch = epoch,
            steps = listOf(
                PlannedStep("return-normal", CommandKind.RETURN_NORMAL_TO_MAIN, "active normal target identity is fresh"),
                PlannedStep("restore-pip", CommandKind.RESTORE_PIP, "baseline app-op mode is journaled"),
                PlannedStep(
                    "restore-transition",
                    CommandKind.RESTORE_TRANSITION_ANIMATION,
                    "baseline animation scales are journaled",
                ),
                PlannedStep(
                    "restore-clean-display",
                    CommandKind.RESET_CLEAN_DISPLAY,
                    "gateway independently proves the expected display has zero tasks before reset",
                ),
            ),
            expectedPostcondition = "IDLE_VERIFIED or durable recovery terminal",
            plannerAllowedActions = emptySet(),
            targetClass = TargetClass.NORMAL,
            expectedDisplayIdentity = "display-2",
            expectedTarget = CastTarget(target, 7, 2),
            geometry = e2eGeometry(),
        )

        val first = fixture.coordinator.execute(PlanResult.Ready(strippedStopPlan), target)
        assertTrue(
            first is ExecutionResult.AwaitingVerification,
            "expected the stripped plan to clear the bootstrap-collision check and dispatch; got $first",
        )
        assertEquals(
            listOf(
                CommandKind.RETURN_NORMAL_TO_MAIN,
                CommandKind.RESTORE_PIP,
                CommandKind.RESTORE_TRANSITION_ANIMATION,
                CommandKind.RESET_CLEAN_DISPLAY,
            ),
            fixture.commands.map { it.kind },
            "first Stop attempt must dispatch exactly the stripped ladder, in order",
        )

        // Second tap: the store still holds a non-null transaction (phase VERIFYING) from the first
        // attempt. This must be rejected without dispatching a single further command.
        val second = fixture.coordinator.execute(PlanResult.Ready(strippedStopPlan), target)
        assertTrue(second is ExecutionResult.Blocked, "second concurrent Stop attempt must no-op, got $second")
        assertEquals(
            "store unavailable or transaction already active",
            (second as ExecutionResult.Blocked).reason,
        )
        assertEquals(4, fixture.commands.size, "second Stop attempt must dispatch ZERO additional commands")

        // The first operation still converges cleanly once verification is allowed to run.
        val operationId = (first as ExecutionResult.AwaitingVerification).operationId
        assertTrue(
            fixture.coordinator.observeAndComplete(operationId),
            "first Stop attempt must converge to a clean idle terminal",
        )
        assertEquals(StableState.IDLE_VERIFIED, fixture.envelope().stableSession!!.state)
        assertNull(fixture.envelope().transaction)
        assertFalse(fixture.envelope().stopRequested)
    }
}

package com.byd.clusternav.modules.clustercast.v2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class CastCarPlaySliceTest {
    @Test fun `connected projection sink is resume only and never force stopped`() {
        val targetClass = CastPolicy.classify(TargetEvidence(true, true, false))
        assertEquals(TargetClass.PROJECTION_SINK, targetClass)
        val result = CastPlanner.plan(CastIntent(CastIntentKind.CAST, "com.carplay", "com.carplay/.Main"), snapshot(targetClass)) as PlanResult.Ready
        assertEquals(ExpectedLadder.protectedTarget, result.plan.steps.map { it.commandKind })
        assertFalse(result.plan.steps.any { it.commandKind == CommandKind.FORCE_STOP_NORMAL })
        assertFalse(CastPolicy.mayForceStop(targetClass))
    }

    private fun snapshot(targetClass: TargetClass) = PlannerSnapshot(
        ObservationValue.Known(ObservedState(ObservedCoarseState.IDLE_CLEAN, "display", null, emptySet(), null, null)),
        StableCastSession(
            StableState.IDLE_VERIFIED, EngineVersion.V2, "test", null, "display",
            CastBaseline(), null, null, null, 1,
        ), targetClass, true, true, 0,
    )
}

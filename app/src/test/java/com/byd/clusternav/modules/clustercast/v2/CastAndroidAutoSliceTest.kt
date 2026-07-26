package com.byd.clusternav.modules.clustercast.v2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastAndroidAutoSliceTest {
    @Test fun `projection evidence without connected truth fails closed`() {
        assertEquals(TargetClass.UNKNOWN_PROTECTED, CastPolicy.classify(TargetEvidence(true, false, false)))
        val result = CastPlanner.plan(
            CastIntent(CastIntentKind.CAST, "com.android.auto"),
            snapshot(TargetClass.UNKNOWN_PROTECTED),
        )
        assertTrue(result is PlanResult.Blocked)
    }

    @Test fun `confirmed protected Android Auto uses resume only`() {
        val result = CastPlanner.plan(
            CastIntent(CastIntentKind.CAST, "com.android.auto"),
            snapshot(TargetClass.PROJECTION_SINK),
        ) as PlanResult.Ready
        assertEquals(ExpectedLadder.protectedTarget, result.plan.steps.map { it.commandKind })
    }

    private fun snapshot(targetClass: TargetClass) = PlannerSnapshot(
        ObservationValue.Known(ObservedState(ObservedCoarseState.IDLE_CLEAN, "display", null, emptySet(), null, null)),
        StableCastSession(
            StableState.IDLE_VERIFIED, EngineVersion.V2, "test", null, "display",
            CastBaseline(), null, null, null, 1,
        ), targetClass, true, true, 0,
    )
}

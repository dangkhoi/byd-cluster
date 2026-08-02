package com.byd.clusternav.modules.clustercast.v2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastWarmMatrixTest {
    @Test fun `normal to normal uses bounded fresh placement`() {
        val result = CastPlanner.plan(
            CastIntent(CastIntentKind.SWITCH, "com.example.b"), snapshot(TargetClass.NORMAL),
        ) as PlanResult.Ready
        assertEquals(
            ExpectedLadder.normal + CommandKind.RETURN_PROTECTED_GENTLY,
            result.plan.steps.map { it.commandKind },
        )
    }

    @Test fun `same normal target recast is verified without destructive restart`() {
        val current = CastTarget("com.example.same", 7, 2)
        val result = CastPlanner.plan(
            CastIntent(CastIntentKind.SWITCH, current.packageName),
            PlannerSnapshot(
                ObservationValue.Known(ObservedState(ObservedCoarseState.ACTIVE_SINGLE, "display-2", current, setOf(current.packageName), null, null)),
                StableCastSession(
                    StableState.ACTIVE_VERIFIED, EngineVersion.V2, "test", null, "display-2",
                    CastBaseline(), current, null, null, 1,
                ), TargetClass.NORMAL, true, true, 4,
            ),
        ) as PlanResult.Ready
        assertTrue(result.plan.steps.isEmpty())
    }

    @Test fun `existing protected residue blocks every second-residue switch`() {
        val observed = ObservedState(
            ObservedCoarseState.ACTIVE_MULTI, "display", CastTarget("normal", 1, 1), setOf("normal"),
            ProtectedResidue("com.carplay", 2, ResidueVisibility.HIDDEN), null,
        )
        val result = CastPlanner.plan(
            CastIntent(CastIntentKind.SWITCH, "com.android.auto"),
            snapshot(TargetClass.PROJECTION_SINK).copy(observed = ObservationValue.Known(observed)),
        )
        assertTrue(result is PlanResult.Blocked)
    }

    private fun snapshot(targetClass: TargetClass): PlannerSnapshot {
        val target = CastTarget("old", 1, 1)
        val observed = ObservedState(
            ObservedCoarseState.ACTIVE_SINGLE, "display", target, setOf("old"), null, null,
        )
        val stable = StableCastSession(
            StableState.ACTIVE_VERIFIED, EngineVersion.V2, "test", null, "display",
            CastBaseline(), target, null, null, 1,
        )
        return PlannerSnapshot(ObservationValue.Known(observed), stable, targetClass, true, true, 1)
    }
}

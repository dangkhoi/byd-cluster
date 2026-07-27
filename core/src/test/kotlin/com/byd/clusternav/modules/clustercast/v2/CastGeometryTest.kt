package com.byd.clusternav.modules.clustercast.v2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastGeometryTest {
    private val target = CastTarget("com.example.maps", 7, 1)
    private val observed = ObservedState(
        ObservedCoarseState.ACTIVE_SINGLE, "display", target, setOf(target.packageName), null, null,
    )

    @Test fun `matching target and epoch accepts bounded geometry`() {
        val decision = CastGeometry.validate(
            GeometryDraft(target, CastRect(0, 0, 1920, 720), 160, "seal", 9), observed, 9,
        ) as GeometryDecision.Ready
        assertEquals(1920, decision.geometry.bounds.right)
        assertEquals(160, decision.geometry.densityDpi)
    }

    @Test fun `stale target epoch protected residue and invalid bounds fail closed`() {
        val valid = GeometryDraft(target, CastRect(0, 0, 1920, 720), 160, "seal", 9)
        assertTrue(CastGeometry.validate(valid.copy(basedOnEpoch = 8), observed, 9) is GeometryDecision.Rejected)
        assertTrue(CastGeometry.validate(valid.copy(target = target.copy(taskId = 8)), observed, 9) is GeometryDecision.Rejected)
        val residue = observed.copy(protectedResidue = ProtectedResidue("projection", 2, ResidueVisibility.HIDDEN))
        assertEquals(
            DisabledReason.PROTECTED_SESSION,
            (CastGeometry.validate(valid, residue, 9) as GeometryDecision.Rejected).reason,
        )
        assertEquals(
            DisabledReason.GEOMETRY_LIMITED,
            (CastGeometry.validate(valid.copy(bounds = CastRect(10, 0, 5, 1)), observed, 9) as GeometryDecision.Rejected).reason,
        )
    }

    @Test fun `geometry plan carries exact target and rejects same package task replacement`() {
        val geometry = AcceptedGeometry(CastRect(0, 0, 1920, 720), 160, "seal")
        val snapshot = PlannerSnapshot(
            ObservationValue.Known(observed.copy(geometry = geometry)),
            stableSession = null,
            targetClass = TargetClass.NORMAL,
            installed = true,
            hasLauncher = true,
            plannerEpoch = 9,
        )
        val ready = CastPlanner.plan(
            CastIntent(CastIntentKind.APPLY_GEOMETRY, target.packageName, geometry = geometry, expectedTarget = target),
            snapshot,
        ) as PlanResult.Ready
        assertEquals(target, ready.plan.expectedTarget)
        assertEquals(geometry, ready.plan.geometry)
        assertEquals(
            listOf(CommandKind.APPLY_TASK_GEOMETRY, CommandKind.APPLY_DISPLAY_GEOMETRY),
            ready.plan.steps.map { it.commandKind },
        )
        assertTrue(CastPlanner.plan(
            CastIntent(
                CastIntentKind.APPLY_GEOMETRY,
                target.packageName,
                geometry = geometry,
                expectedTarget = target.copy(taskId = 99),
            ),
            snapshot,
        ) is PlanResult.Blocked)
    }
}

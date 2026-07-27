package com.byd.clusternav.modules.clustercast.v2

import com.byd.clusternav.testsupport.SourceRoots
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastDryPlannerTest {
    @Test
    fun `ready plan renders deterministic typed transcript without gateway execution`() {
        val result = CastPlanner.plan(
            CastIntent(CastIntentKind.CAST, "com.example.maps", "com.example.maps/.Main"),
            snapshot(TargetClass.NORMAL),
        )
        val first = CastDryPlanner.render(result)!!
        val second = CastDryPlanner.render(result)!!
        assertEquals(first.operation, second.operation)
        assertEquals(first.epoch, second.epoch)
        assertEquals(first.steps, second.steps)
        assertEquals(
            ExpectedLadder.normal,
            first.steps.map { it.commandKind },
        )
        assertTrue(first.steps.all { it.mutating })
        assertThrows(UnsupportedOperationException::class.java) {
            (first.steps as MutableList).clear()
        }
    }

    @Test
    fun `blocked plan has no transcript and unknown observation fails before mutation`() {
        val blocked = CastPlanner.plan(
            CastIntent(CastIntentKind.CAST, "com.example.maps"),
            snapshot(TargetClass.NORMAL).copy(observed = ObservationValue.Unknown("timeout")),
        )
        assertNull(CastDryPlanner.render(blocked))
        assertTrue(blocked is PlanResult.Blocked)
    }

    @Test
    fun `dry planner source has no live gateway or raw mutation command`() {
        val source = SourceRoots.text("src/main/java/com/byd/clusternav/modules/clustercast/v2/CastPlanner.kt")
        assertFalse(source.contains("ShellGateway"))
        assertFalse(source.contains("gateway.execute"))
        assertFalse(source.contains("am display move-stack"))
        assertEquals(32, CastCaseManifest.cases.size)
    }

    private fun snapshot(targetClass: TargetClass) = PlannerSnapshot(
        observed = ObservationValue.Known(
            ObservedState(ObservedCoarseState.IDLE_CLEAN, "display", null, emptySet(), null, null)
        ),
        stableSession = StableCastSession(
            StableState.IDLE_VERIFIED, EngineVersion.V2, "test", null, "display",
            CastBaseline(), null, null, null, 1,
        ),
        targetClass = targetClass,
        installed = true,
        hasLauncher = true,
        plannerEpoch = 9,
    )
}

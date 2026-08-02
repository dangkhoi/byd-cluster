package com.byd.clusternav.modules.clustercast.v2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant

/** Canonical UI v5 automation presentation: additive fields with strict first-match precedence. */
class CastUiAutomationProjectionTest {

    private val artifact: String = listOf(
        Paths.get("docs/specs/cast-ui-state-v2.schema.json"),
        Paths.get("../docs/specs/cast-ui-state-v2.schema.json"),
    ).first { Files.exists(it) }.toFile().readText()

    private val now: Instant = Instant.parse("2026-07-25T14:00:00Z")

    private fun input(
        stopRequested: Boolean = false,
        recovery: RecoverySubstate? = null,
        transaction: PlannerUiProjection? = null,
        stableState: StableState? = StableState.IDLE_VERIFIED,
        converged: Boolean = true,
        disposition: AutomationDisposition? = AutomationDisposition.PENDING,
        reason: AutomationReason? = null,
    ) = CastProjectionInput(
        decodeValid = true,
        engineVersion = EngineVersion.V2,
        observedNonIdle = false,
        stopRequested = stopRequested,
        recoverySubstate = recovery,
        transaction = transaction,
        destructiveRecoveryEligible = null,
        stableState = stableState,
        stableConverged = converged,
        interactionContext = InteractionContext(
            InteractionContextValue.UNKNOWN, "test", now, now, "diagnostic only",
        ),
        target = null,
        protectedResidue = null,
        acceptedGeometry = null,
        durableEpoch = 3L,
        now = now,
        automationDisposition = disposition,
        automationReason = reason,
        automationTargetPackage = "com.example.maps",
    )

    @Test
    fun `artifact declares version five with the exact automation fields and enums`() {
        assertTrue(artifact.contains("\"schemaVersion\": 5"))
        listOf("automationDisposition", "automationReason", "automationTargetPackage").forEach {
            assertTrue(artifact.contains(it), "artifact missing $it")
        }
        AutomationDisposition.entries.forEach { assertTrue(artifact.contains("\"${it.name}\"")) }
        AutomationReason.entries.forEach { assertTrue(artifact.contains("\"${it.name}\"")) }
        assertEquals(5, CAST_UI_SCHEMA_VERSION)
    }

    @Test
    fun `steady state surfaces the automation disposition`() {
        val state = CastUiStateProjector.project(input())
        assertEquals(AutomationDisposition.PENDING, state.automationDisposition)
        assertEquals("com.example.maps", state.automationTargetPackage)
        assertNull(state.automationReason)
    }

    @Test
    fun `every disposition round trips through the projection`() {
        AutomationDisposition.entries.forEach { disposition ->
            val state = CastUiStateProjector.project(input(disposition = disposition))
            assertEquals(disposition, state.automationDisposition)
        }
    }

    @Test
    fun `durable stop outranks automation`() {
        val state = CastUiStateProjector.project(input(stopRequested = true))
        assertNull(state.automationDisposition)
        assertNull(state.automationTargetPackage)
    }

    @Test
    fun `recovery and manual states outrank automation`() {
        val state = CastUiStateProjector.project(
            input(
                recovery = RecoverySubstate.UNKNOWN_EFFECT_STOP_AVAILABLE,
                stableState = StableState.RECOVERY_PENDING,
                converged = false,
            ),
        )
        assertNull(state.automationDisposition)
    }

    @Test
    fun `an active transaction outranks automation`() {
        val state = CastUiStateProjector.project(
            input(
                transaction = PlannerUiProjection(
                    phase = OperationPhase.ACTIVATING,
                    operationId = java.util.UUID.randomUUID(),
                    deadlineAt = now.plusSeconds(30),
                    stopDisposition = StopDisposition(StopDispositionKind.AVAILABLE),
                    nextSafeAction = NextSafeAction.REQUEST_STOP,
                    allowedActions = setOf(CastAction.STOP),
                ),
            ),
        )
        assertNull(state.automationDisposition)
        assertEquals(OperationPhase.ACTIVATING, state.operationPhase)
    }

    @Test
    fun `a corrupt envelope projects contract unmapped manual state rather than disabled automation`() {
        val state = CastUiStateProjector.project(input(disposition = null).copy(decodeValid = false))
        assertEquals(CoarseState.MANUAL_REQUIRED, state.coarseState)
        assertEquals(UnavailableReason.CONTRACT_UNMAPPED, state.unavailableReason)
        assertNull(state.automationDisposition)
    }

    @Test
    fun `automation never widens the exported action set`() {
        val withAutomation = CastUiStateProjector.project(input())
        val without = CastUiStateProjector.project(input(disposition = null))
        assertEquals(without.allowedActions, withAutomation.allowedActions)
    }
}

package com.byd.clusternav.modules.clustercast.v2

import com.byd.clusternav.testsupport.SourceRoots
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Locks a finding from tracing `CastAction.RETRY_CONNECT` for `RecoverySubstate.TRANSPORT_PREMUTATION_IDLE`
 * (docs/diagnostics/cast-recovery-deadend-2026-07-28.md): `CastUiStateProjector.recoveryRows` maps this
 * substate to `NextSafeAction.RETRY_CONNECT_BOUNDED` -- but `CastRuntimeUi.render`, the only production
 * site that builds `CastProjectionInput.recoverySubstate`, can NEVER assign this value (nor 8 of its 15
 * siblings, updated 2026-07-31). Its `recovery` local is `transactionRecovery ?: when { ... }`, a closed
 * expression whose only possible non-null outputs are the ones asserted below.
 *
 * `OBSERVATION_DIVERGED_STOP_AVAILABLE`/`OBSERVATION_DIVERGED_WAITING` joined `PRODUCTION_REACHABLE`
 * 2026-07-31 (docs/specs/cast-recovery-honesty-and-multi-occupant.html §R2): both existed in
 * `recoveryRows` since before, unreachable for the same reason `TRANSPORT_PREMUTATION_IDLE` still is —
 * nothing ever produced them. Measured on DiLink3 the same day: a durable claim (`stable.state` in
 * `IDLE_VERIFIED`/`ACTIVE_VERIFIED`/`ACTIVE_DEGRADED`) can stop matching live observation for reasons no
 * ClusterNav transaction caused (another app lands on the cluster, or two apps both read visible=true) —
 * `CastRuntimeUi.render` now assigns these substates for exactly that shape instead of falling through to
 * `CastUiStateProjector.failClosed` (`CONTRACT_UNMAPPED`, a false "chưa nhận diện được" when the mismatch
 * was in fact identified).
 *
 * If this test ever needs to change, it means someone deliberately wired one of the previously-unreachable
 * substates -- update `PRODUCTION_REACHABLE` and re-verify the new path actually changes durable state
 * (see `NoDeadEndStateTest`, which only proves an action is *offered*, not that it *works*).
 */
class CastRetryConnectDeadEndTest {
    private val runtimeUiSource by lazy {
        SourceRoots.text("src/main/java/com/byd/clusternav/modules/clustercast/v2/CastRuntimeUi.kt")
    }

    @Test
    fun `CastRuntimeUi can only ever assign the enumerated reachable recovery substates`() {
        val mentioned = Regex("RecoverySubstate\\.([A-Z_]+)").findAll(runtimeUiSource)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(PRODUCTION_REACHABLE, mentioned)
    }

    @Test
    fun `TRANSPORT_PREMUTATION_IDLE -- the substate the 2026-07-28 dead-end is attributed to -- is unreachable today`() {
        assertFalse(runtimeUiSource.contains("RecoverySubstate.TRANSPORT_PREMUTATION_IDLE"))
        // The projector still requires a total table (CastUiStateProjectorTest asserts 19 rows), so the
        // substate is fully specified as a UI contract -- just never produced by the runtime that feeds it.
        assertTrue(RecoverySubstate.entries.contains(RecoverySubstate.TRANSPORT_PREMUTATION_IDLE))
    }

    @Test
    fun `every substate absent from CastRuntimeUi is also absent from the reachable set`() {
        RecoverySubstate.entries.forEach { substate ->
            val reachable = substate.name in PRODUCTION_REACHABLE
            val mentionedInRuntimeUi = runtimeUiSource.contains("RecoverySubstate.${substate.name}")
            assertEquals(mentionedInRuntimeUi, reachable, substate.name)
        }
    }

    private fun assertEquals(expected: Set<String>, actual: Set<String>) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual)
    }

    private fun assertEquals(expected: Boolean, actual: Boolean, message: String) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual, message)
    }

    companion object {
        /**
         * Exhaustive as of 2026-07-28: the only `RecoverySubstate` values `CastRuntimeUi.render` can ever
         * assign to `CastProjectionInput.recoverySubstate`. Everything in `RecoverySubstate.entries` that is
         * not in this set is reachable only through direct unit tests of `CastUiStateProjector`, never
         * through the real Android-facing runtime path `retryConnect()` -> `executeCast()` ->
         * `CastFacade` -> `CastCoordinator` -> ... -> `CastRuntimeUi.render` actually walks.
         */
        val PRODUCTION_REACHABLE = setOf(
            "COMPENSATION_EXHAUSTED",
            "UNKNOWN_EFFECT_STOP_REQUESTED",
            "UNKNOWN_EFFECT_STOP_AVAILABLE",
            "UNSUPPORTED_PROFILE_IDLE",
            "UNSUPPORTED_PROFILE_ACTIVE_UNKNOWN",
            "PROTECTED_SINK_CONNECTED",
            "DISCONNECTED_SINK_ELIGIBLE",
            "OBSERVATION_DIVERGED_STOP_AVAILABLE",
            "OBSERVATION_DIVERGED_WAITING",
        )
    }
}

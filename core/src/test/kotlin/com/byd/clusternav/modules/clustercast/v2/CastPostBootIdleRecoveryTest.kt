package com.byd.clusternav.modules.clustercast.v2

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Locks the dead-end class found 2026-07-29 on a real ignition power cycle, and proves the fix
 * (`CastCoordinator.reconcileUnobservableIdleSession` + the relaxed `CastRuntimeUi.isColdPristine`)
 * closes it end to end at the render layer.
 *
 * Real values below are the exact decoded fields read off the vehicle's `cast-v2/session.env` that
 * evening: `durableEpoch=4`, `stableSession` = `IDLE_VERIFIED` created by `"runtime-bootstrap"` /
 * `"seal-dl3-cold-bootstrap-v1"`, `expectedDisplayIdentity="display-1"`, geometry
 * `Rect(0,0,1920,720)@320dpi` profile `"android-user-0"`. After the real reboot, `dumpsys display`
 * showed only `displayId=0` — the cluster's WindowManager-level virtual display never survives a
 * reboot — so the live observation is `Unknown(MISSING_NAMED_CLUSTER_DISPLAY_REASON)`, never `Known`.
 *
 * Pre-fix, this exact envelope+observation pair renders `"Cần xử lý thủ công"` (MANUAL_REQUIRED) with
 * only Diagnostics enabled — the driver had no action that recovers casting short of the
 * `run-as`/checksum surgery this session used. Post-fix, once `reconcileUnobservableIdleSession()`
 * has cleared `stableSession` (simulated directly here, since the coordinator-level mechanics are
 * already locked by `CastReconcileUnobservableIdleSessionTest`), the SAME still-missing observation
 * renders `COLD_PRISTINE` with `CAST` enabled — no button press required beyond opening the screen.
 */
class CastPostBootIdleRecoveryTest {

    private val staleIdleEnvelope = CastSessionEnvelope(
        durableEpoch = 4,
        bootId = "boot-100",
        stopRequested = false,
        pendingIntent = null,
        effectiveUiVersion = EngineVersion.V2,
        pendingUiRollback = false,
        stableSession = StableCastSession(
            state = StableState.IDLE_VERIFIED,
            engineVersion = EngineVersion.V2,
            createdByBuild = "runtime-bootstrap",
            profileExport = "seal-dl3-cold-bootstrap-v1",
            expectedDisplayIdentity = "display-1",
            baseline = CastBaseline(
                geometry = AcceptedGeometry(CastRect(0, 0, 1920, 720), 320, "android-user-0"),
                animationPerKey = mapOf(
                    "animator_duration_scale" to "1.0",
                    "transition_animation_scale" to "0.5",
                    "window_animation_scale" to "0.5",
                ),
            ),
            activeTarget = null,
            protectedResidue = null,
            acceptedGeometry = AcceptedGeometry(CastRect(0, 0, 1920, 720), 320, "android-user-0"),
            lastVerifiedAtEpochMillis = 1_785_326_866_773L,
        ),
        transaction = null,
    )

    private val missingDisplay: ObservationValue<ObservedState> =
        ObservationValue.Unknown(MISSING_NAMED_CLUSTER_DISPLAY_REASON)

    private fun CastRenderModel.enabled(action: CastAction) = actions.first { it.action == action }.enabled

    @Test
    fun `pre-fix a stale idle session with no cluster display dead-ends at manual required`() {
        val model = CastRuntimeUi.render(
            StoreRead.Loaded(staleIdleEnvelope), missingDisplay,
            now = Instant.ofEpochMilli(2_000),
        )
        assertEquals("Cần xử lý thủ công", model.title)
        assertFalse(model.enabled(CastAction.CAST))
        assertTrue(model.enabled(CastAction.OPEN_DIAGNOSTICS))
    }

    @Test
    fun `once the stale idle session is cleared the same missing display reaches cold pristine with CAST enabled`() {
        val healed = staleIdleEnvelope.copy(stableSession = null)
        val model = CastRuntimeUi.render(
            StoreRead.Loaded(healed), missingDisplay,
            now = Instant.ofEpochMilli(2_000),
        )
        assertEquals("Cluster Cast sẵn sàng tạo cụm", model.title)
        assertTrue(model.enabled(CastAction.CAST), "CAST must be offered without any manual step")
    }

    @Test
    fun `clearing stableSession alone does not fabricate cold pristine when other envelope fields disqualify it`() {
        val healedButStopRequested = staleIdleEnvelope.copy(stableSession = null, stopRequested = true)
        val model = CastRuntimeUi.render(
            StoreRead.Loaded(healedButStopRequested), missingDisplay,
            now = Instant.ofEpochMilli(2_000),
        )
        assertFalse(model.enabled(CastAction.CAST), "a pending Stop must still block a fresh cast")
    }
}

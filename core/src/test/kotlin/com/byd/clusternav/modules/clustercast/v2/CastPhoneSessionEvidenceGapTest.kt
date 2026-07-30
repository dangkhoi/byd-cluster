package com.byd.clusternav.modules.clustercast.v2

import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Locks a gap found while tracing the 2026-07-28 CastPolicy fix end to end.
 *
 * `CastPolicy.classify` was just fixed so a `null` `connectedPhoneSession` reading for a
 * projection-hinted package (CarPlay/Android Auto host) no longer fails closed to
 * `UNKNOWN_PROTECTED` — see `CastPolicyTest`. That makes CarPlay/Android Auto reach
 * `ACTIVE_VERIFIED`/`ACTIVE_DEGRADED` for the first time.
 *
 * `CastActivityRefreshReader.read()` (app module) resolves `RecoverySubstate.PROTECTED_SINK_CONNECTED`
 * vs `DISCONNECTED_SINK_ELIGIBLE` for a `RECOVERY_PENDING` target by calling the exact same
 * `connectedPhoneSession(ownerPackage)` → `ProjectionSessionEvidenceParser.parse(dumpsys activity
 * services <ownerPackage>)` pipeline that the CastPolicy fix's own doc comment says can never return
 * true/false for the CarPlay/Android Auto host package — only for a third-party app being mirrored.
 * That call site was NOT touched by the CastPolicy fix.
 *
 * These are the exact real `dumpsys activity services <pkg>` dumps captured on the vehicle
 * 2026-07-23 for `com.byd.carplay.ui` and `com.byd.androidauto`
 * (docs/diagnostics/carlog-2026-07-23-trace/live/08-svc-carplay.txt,
 * .../09-svc-androidauto.txt). Parsing them proves `phoneConnected` can never become `false` for
 * these two packages in `CastActivityRefreshReader`, so `if (phoneConnected == false)` there is dead
 * for CarPlay/Android Auto, `recoveryEligible` never gets set, `phoneSessionConnected` stays `null`,
 * and `CastRuntimeUi.render`'s `phoneSessionConnected == true -> PROTECTED_SINK_CONNECTED` /
 * `phoneSessionConnected == false && destructiveRecoveryEligible == true -> DISCONNECTED_SINK_ELIGIBLE`
 * both fall through to `else -> null` — recovery substate `null` while `stableState ==
 * RECOVERY_PENDING` reaches `CastUiStateProjector.failClosed`, i.e. the same dead-end class as
 * `docs/diagnostics/cast-recovery-deadend-2026-07-28.md`, now newly reachable for exactly the two
 * flagship cast targets this app exists to serve.
 *
 * If this test ever fails because a real dump gains one of the fields the parser looks for, that is
 * good news: it means the gap above is closed and this test (and the diagnostic note it locks) should
 * be revisited/deleted.
 *
 * 2026-07-28 follow-up: the call site above is now fixed. `CastActivityRefreshReader.read()` (app
 * module) no longer passes the raw, structurally-`null` reading straight through to
 * `CastRuntimeUi.render`. It resolves it first with `resolveProjectionPhoneConnected(raw) = raw !=
 * false` -- the same "unmeasurable is not false" principle `CastPolicy.classify` already applies --
 * so a `null` reading for a `projectionComponent` target now reaches `PROTECTED_SINK_CONNECTED`
 * instead of dead-ending in `failClosed`. That resolution function lives in the app module (it is the
 * one call site that ever sees this evidence), so it cannot be unit-tested from `:core` directly; see
 * `CastActivityRefreshPhoneConnectedTest` (app module) for the direct proof on these exact dumps. The
 * two tests below prove the `:core` half of the contract this fix depends on: what
 * `CastRuntimeUi.render`/`CastUiStateProjector.project` actually do with the resolved `true`/`false`
 * values for a `RECOVERY_PENDING` projection-sink target.
 */
class CastPhoneSessionEvidenceGapTest {

    private fun realDump(name: String): String {
        val roots = listOf(
            "docs/diagnostics/carlog-2026-07-23-trace/live",
            "../docs/diagnostics/carlog-2026-07-23-trace/live",
        ).map(Paths::get)
        val root = roots.firstOrNull(Files::exists) ?: error("không tìm thấy thư mục fixture; đã thử $roots")
        return root.resolve(name).toFile().readText()
    }

    @Test
    fun `real CarPlay host service dump never yields a phone-session reading`() {
        assertNull(ProjectionSessionEvidenceParser.parse(realDump("08-svc-carplay.txt")))
    }

    @Test
    fun `real Android Auto host service dump never yields a phone-session reading`() {
        assertNull(ProjectionSessionEvidenceParser.parse(realDump("09-svc-androidauto.txt")))
    }

    private fun recoveryPendingEnvelope(ownerPackage: String) = CastSessionEnvelope(
        durableEpoch = 3,
        bootId = "boot-1",
        stopRequested = false,
        pendingIntent = null,
        effectiveUiVersion = EngineVersion.V2,
        pendingUiRollback = false,
        stableSession = StableCastSession(
            state = StableState.RECOVERY_PENDING,
            engineVersion = EngineVersion.V2,
            createdByBuild = "test",
            profileExport = null,
            expectedDisplayIdentity = "display-2",
            baseline = CastBaseline(),
            activeTarget = CastTarget("com.example.maps", 7, 2),
            protectedResidue = ProtectedResidue(ownerPackage, 9, ResidueVisibility.HIDDEN),
            acceptedGeometry = null,
            lastVerifiedAtEpochMillis = 1_000,
        ),
        transaction = null,
    )

    private fun recoveryPendingObservation(ownerPackage: String) = ObservationValue.Known(
        ObservedState(
            coarseState = ObservedCoarseState.ACTIVE_MULTI,
            displayIdentity = "display-2",
            target = CastTarget("com.example.maps", 7, 2),
            occupants = setOf("com.example.maps", ownerPackage),
            protectedResidue = ProtectedResidue(ownerPackage, 9, ResidueVisibility.HIDDEN),
            geometry = null,
        ),
    )

    /**
     * Proves the fix closes the exact dead end described above: before it existed, the reader passed
     * the raw (structurally-null) reading straight through, and `phoneSessionConnected == null` fell
     * to `CastRuntimeUi.render`'s `else -> null`, then `CastUiStateProjector.project` (recovery=null,
     * stableConverged=false for RECOVERY_PENDING) reached `failClosed` -- MANUAL_REQUIRED with only
     * Diagnostics. With the reader's fix (`resolveProjectionPhoneConnected(null) == true`), the exact
     * same unmeasurable reading now reaches `PROTECTED_SINK_CONNECTED`, a real (non-destructive) next
     * step: ask the driver to disconnect their phone from the projection host.
     */
    private fun CastRenderModel.enabled(action: CastAction): Boolean =
        actions.first { it.action == action }.enabled

    @Test
    fun `unmeasurable reading dead-ends pre-fix but reaches PROTECTED_SINK_CONNECTED once resolved`() {
        val ownerPackage = "com.byd.carplay.ui"
        val storeRead = StoreRead.Loaded(recoveryPendingEnvelope(ownerPackage))
        val observation = recoveryPendingObservation(ownerPackage)

        // Pre-fix: the raw (structurally-null for this package) reading passed straight through --
        // `CastUiStateProjector.failClosed`, the exact dead-end class this fix closes.
        val preFix = CastRuntimeUi.render(
            storeRead, observation, now = Instant.ofEpochMilli(2_000),
            destructiveRecoveryEligible = null, phoneSessionConnected = null,
        )
        assertEquals("Cần xử lý thủ công", preFix.title)
        assertFalse(preFix.enabled(CastAction.REQUEST_PHONE_DISCONNECT))
        assertFalse(preFix.enabled(CastAction.STOP))
        assertTrue(preFix.enabled(CastAction.OPEN_DIAGNOSTICS))

        // Post-fix: resolveProjectionPhoneConnected(null) == true reaches PROTECTED_SINK_CONNECTED, a
        // real non-destructive next step, instead of the dead end above.
        val postFix = CastRuntimeUi.render(
            storeRead, observation, now = Instant.ofEpochMilli(2_000),
            destructiveRecoveryEligible = null,
            phoneSessionConnected = resolveProjectionPhoneConnectedForTest(null),
        )
        assertEquals("Đang chờ phục hồi", postFix.title)
        assertTrue(postFix.enabled(CastAction.REQUEST_PHONE_DISCONNECT))
        assertTrue(postFix.enabled(CastAction.OPEN_DIAGNOSTICS))
        assertFalse(postFix.enabled(CastAction.STOP))
    }

    /**
     * The safety property the fix must NOT weaken: only a positively confirmed `false` reading may
     * ever unlock `DISCONNECTED_SINK_ELIGIBLE`, and only when the second (convergent) observation
     * proves it -- exactly the pre-existing `recoveryEligible` gate, unchanged by this fix. A confirmed
     * `false` without that convergent proof still fails closed, same as before.
     */
    @Test
    fun `only a confirmed-false reading with a converged proof unlocks DISCONNECTED_SINK_ELIGIBLE`() {
        val ownerPackage = "com.byd.androidauto"
        val storeRead = StoreRead.Loaded(recoveryPendingEnvelope(ownerPackage))
        val observation = recoveryPendingObservation(ownerPackage)

        val eligible = CastRuntimeUi.render(
            storeRead, observation, now = Instant.ofEpochMilli(2_000),
            destructiveRecoveryEligible = true,
            phoneSessionConnected = resolveProjectionPhoneConnectedForTest(false),
        )
        assertTrue(eligible.enabled(CastAction.TRY_ELIGIBLE_RECOVERY_ONCE))
        assertFalse(eligible.enabled(CastAction.STOP))

        // Without the convergent second-observation proof, a confirmed-false reading still fails
        // closed -- the fix must not weaken this pre-existing safety gate.
        val notYetProven = CastRuntimeUi.render(
            storeRead, observation, now = Instant.ofEpochMilli(2_000),
            destructiveRecoveryEligible = false,
            phoneSessionConnected = resolveProjectionPhoneConnectedForTest(false),
        )
        assertEquals("Cần xử lý thủ công", notYetProven.title)
        assertFalse(notYetProven.enabled(CastAction.TRY_ELIGIBLE_RECOVERY_ONCE))
    }

    /**
     * Mirrors `resolveProjectionPhoneConnected` in `CastActivityRefresh.kt` (app module). Duplicated
     * here, not imported, because `:core` cannot depend on `:app` -- see
     * `TwoPipelineStaticBoundaryTest`. The app-module `CastActivityRefreshPhoneConnectedTest` proves the
     * real function directly, including on the real dumps; this local mirror exists only so this test
     * documents *why* the value fed to `CastRuntimeUi.render` is `true`/`false` rather than the raw
     * reading, keeping the two tests honest about the same contract from each side of the module
     * boundary.
     */
    private fun resolveProjectionPhoneConnectedForTest(rawPhoneSession: Boolean?): Boolean =
        rawPhoneSession != false
}

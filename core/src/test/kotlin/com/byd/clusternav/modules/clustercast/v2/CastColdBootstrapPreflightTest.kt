package com.byd.clusternav.modules.clustercast.v2

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Preflight was the gate that made every on-car cast impossible: it demanded a display topology no
 * real vehicle has. It now requires only parseable truth plus a cluster display that is either
 * absent (create it) or unoccupied (adopt it).
 */
class CastColdBootstrapPreflightTest {
    @Test
    fun `pure raw preflight requires pristine V2 ownerless envelope and exact main-only truth`() {
        val envelope = pristineEnvelope()
        assertTrue(CastColdBootstrapPreflight.inspect(envelope, facts(), raw()) is ColdBootstrapPreflight.Ready)
        val realMainOnlyDump = """
            DISPLAY MANAGER (dumpsys display)
            Display Adapters: size=4
            Display Devices: size=1
              DisplayDeviceInfo{"Built-in Screen": uniqueId="local:19261206365013889", 1920 x 1080}
            Display 0:
              mDisplayId=0
              mPhysicalDisplayId=19261206365013889
              mBaseDisplayInfo=DisplayInfo{"Built-in Screen, displayId 0", app 1920 x 1080, density 240}
            Display Power Controller Locked State:
            Display Power State:
        """.trimIndent()
        assertTrue(
            CastColdBootstrapPreflight.inspect(
                envelope,
                facts(),
                raw().copy(displays = realMainOnlyDump),
            ) is ColdBootstrapPreflight.Ready,
        )
        val blockedEnvelopes = listOf(
            envelope.copy(stopRequested = true),
            envelope.copy(pendingIntent = PendingCastIntent("com.example.maps")), envelope.copy(pendingUiRollback = true),
            envelope.copy(effectiveUiVersion = EngineVersion.LEGACY),
            envelope.copy(stableSession = idleSession()),
        )
        blockedEnvelopes.forEach {
            assertTrue(CastColdBootstrapPreflight.inspect(it, facts(), raw()) is ColdBootstrapPreflight.Blocked)
        }
        // 2026-07-29: a non-zero epoch alone no longer blocks bootstrap. It used to be equivalent to
        // "stableSession/transaction were never set" only because nothing else ever nulled
        // stableSession after the epoch moved past zero; `reconcileUnobservableIdleSession()` now does
        // exactly that for a stale post-reboot idle claim, so a driver who cleared one must be able to
        // bootstrap fresh again despite the epoch having moved. See
        // `CastReconcileUnobservableIdleSessionTest`/`CastPostBootIdleRecoveryTest`.
        assertTrue(
            CastColdBootstrapPreflight.inspect(envelope.copy(durableEpoch = 1), facts(), raw())
                is ColdBootstrapPreflight.Ready,
        )
        // 0.72: unrelated logical displays (launcher-split, unnamed virtual surfaces) and stacks that
        // live on them exist on real vehicles and must not block bootstrap.
        listOf(
            raw().copy(displays = raw().displays + "\nDisplay 2:\n mDisplayId=2"),
            raw().copy(wmDisplays = "Display: mDisplayId=0\nDisplay: mDisplayId=2"),
            raw().copy(amStacks = "Stack id=7 displayId=2 userId=0\n taskId=8: com.example.maps/.Main"),
        ).forEach {
            assertTrue(
                CastColdBootstrapPreflight.inspect(envelope, facts(), it) is ColdBootstrapPreflight.Ready,
                it.toString(),
            )
        }
        // A named cluster display that already exists is adopted while it is unoccupied…
        assertTrue(
            CastColdBootstrapPreflight.inspect(
                envelope, facts(), raw().copy(displays = SEAL_CLUSTER_DUMP),
            ) is ColdBootstrapPreflight.Ready,
        )
        // …and refused with an actionable reason while an app still holds it.
        val occupied = CastColdBootstrapPreflight.inspect(
            envelope,
            facts(),
            raw().copy(
                displays = SEAL_CLUSTER_DUMP,
                amStacks = "Stack id=0 displayId=0 userId=0\n taskId=1: com.example.launcher/.Main\n" +
                    "Stack id=7 displayId=2 userId=0\n taskId=8: com.example.maps/.Main",
            ),
        )
        assertTrue(occupied is ColdBootstrapPreflight.Blocked)
        assertTrue((occupied as ColdBootstrapPreflight.Blocked).reason.contains("already holds a task"))
        listOf(
            raw().copy(displays = raw().displays + "\nDisplay 2: trailing"),
            raw().copy(amStacks = "Stack id=7 displayId=0\n taskId=8: com.example.maps/.Main\nStack id=7 displayId=0\n taskId=9: com.example.other/.Main"),
            raw().copy(profile = "current-user"), raw().copy(animations = "1\n1"), raw().copy(appOps = ""),
        ).forEach {
            assertTrue(CastColdBootstrapPreflight.inspect(envelope, facts(), it) is ColdBootstrapPreflight.Blocked)
        }
    }

    /**
     * Fail-closed vehicle-identity gate: only the EXACT Seal DL3 tuple reaches Ready. A near-miss
     * (single field off) must stay Blocked, not silently widen to a fuzzy "byd" substring match --
     * that was the central risk flagged in review of the generic vehicle-profile catalog and is
     * exactly what CastVehicleProfileCatalog.GENERIC_FALLBACK (dispatchImplemented = false) enforces.
     */
    @Test
    fun `a near-miss vehicle tuple stays Blocked, never widened to a fuzzy match`() {
        val envelope = pristineEnvelope()
        val nearMiss = facts().copy(manufacturer = "BYD Auto")
        val nearMissResult = CastColdBootstrapPreflight.inspect(envelope, nearMiss, raw())
        assertTrue(nearMissResult is ColdBootstrapPreflight.Blocked)
        assertTrue((nearMissResult as ColdBootstrapPreflight.Blocked).reason.contains("GENERIC_FALLBACK") ||
            nearMissResult.reason.contains("exact"), nearMissResult.reason)

        val dl5Marker = facts().copy(product = "DiLink5", device = "DiLink5")
        val dl5Result = CastColdBootstrapPreflight.inspect(envelope, dl5Marker, raw())
        assertTrue(dl5Result is ColdBootstrapPreflight.Blocked)
        assertTrue((dl5Result as ColdBootstrapPreflight.Blocked).reason.contains("DiLink5"), dl5Result.reason)

        // Explicit override to SEAL_DL3 short-circuits detection even for otherwise-unknown facts.
        val overridden = CastColdBootstrapPreflight.inspect(
            envelope, nearMiss, raw(), VehicleProfileId.SEAL_DL3,
        )
        assertTrue(overridden is ColdBootstrapPreflight.Ready)
    }

    private fun facts() = SealDl3BootstrapProfile.exactFacts
    private fun raw() = RawObservation(
        amStacks = "Stack id=0 bounds=[0,0][1920,1080] displayId=0 userId=0\n" +
            "  taskId=1: com.example.launcher/.Main bounds=[0,0][1920,1080] userId=0",
        wmDisplays = "Display: mDisplayId=0",
        displays = "Display 0:\n  mDisplayId=0\n  DisplayDeviceInfo{Built-in Screen}",
        profile = "10", animations = "1.0\n0.5\n1.0", appOps = "No app ops",
    )

    private fun knownIdle(): ObservationValue.Known<ObservedState> = ObservationValue.Known(
        ObservedState(
            ObservedCoarseState.IDLE_CLEAN, "display-2", null, emptySet(), null,
            AcceptedGeometry(CastRect(0, 0, 1920, 720), 180, "android-user-10"),
            mapOf("window_animation_scale" to "1.0", "transition_animation_scale" to "0.5",
                "animator_duration_scale" to "1.0"), null, "android-user-10", "fission_bg_xdjaVirtualSurface",
        ),
    )

    private fun idleSession() = StableCastSession(
        StableState.IDLE_VERIFIED, EngineVersion.V2, "bootstrap", "seal-dl3-cold-bootstrap-v1",
        "display-2", CastBaseline(geometry = knownIdle().value.geometry), null, null,
        knownIdle().value.geometry, 1,
    )
    private fun pristineEnvelope() = CastSessionEnvelope(
        durableEpoch = 0, bootId = "boot", stopRequested = false, pendingIntent = null,
        effectiveUiVersion = EngineVersion.V2, pendingUiRollback = false,
        stableSession = null, transaction = null,
    )
}

package com.byd.clusternav.modules.clustercast.v2

import java.io.IOException
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Locks the exact bug found 2026-07-28 while triple-verifying the Stop-dispatch fix: a driver could
 * tap Stop while an unrelated transaction (CAST/SWITCH/APPLY_GEOMETRY) was stuck in RECOVERING —
 * `CastCoordinator.plan()`'s `transaction != null` guard correctly refuses to build a second concurrent
 * plan, so `ClusterCastActivity.executeStop()` defers to the existing transaction resolving first.
 *
 * `reconcileAbandoned()` is the ONLY path that ever clears a stuck transaction, and it did so purely
 * from observing `ObservedCoarseState.IDLE_CLEAN` — proof the window-manager layer holds nothing on the
 * cluster display. That is NOT proof the OEM AutoContainer projection itself was closed: CastPlanner
 * kt's STOP ladder comment (measured on the vehicle 2026-07-26) records that the OEM keeps mirroring
 * the last cluster frame until told to close it (`SEAL_DL3_COMPENSATE_18`/`_0`). The old code cleared
 * `stopRequested = false` as a side effect of the SAME assignment that cleared the abandoned
 * transaction — so a pending driver-requested Stop could be silently discarded with the close-projection
 * opcodes never dispatched, leaving the physical cluster possibly frozen on a stale frame while the app
 * believed Stop had completed.
 */
class CastReconcileAbandonedTest {

    @Test
    fun `an abandoned transaction is cleared while a pending Stop request survives to run the real teardown`() {
        val fixture = fixture(stopRequested = true)
        fixture.states += idleClean()

        assertTrue(fixture.coordinator.reconcileAbandoned())

        val afterReconcile = fixture.envelope()
        assertNull(afterReconcile.transaction, "the abandoned transaction must still be cleared")
        assertTrue(afterReconcile.stopRequested, "a pending Stop request must survive unrelated reconciliation")

        // The narrow dead-end this test locks: before the fix, stopRequested was already false here,
        // so a second Stop attempt would never even try to build a plan. Now transaction == null, so
        // the real Stop plan (which carries the close-projection opcodes) can finally be built.
        // CastPlanner needs its own fresh observation independent of the one reconcileAbandoned just
        // consumed.
        fixture.states += idleClean()
        val plan = fixture.coordinator.plan(
            CastIntent(CastIntentKind.STOP, "com.example.maps"),
            TargetEvidence(projectionComponent = false, connectedPhoneSession = false, userProtected = false),
            installed = true,
            hasLauncher = true,
        )
        assertTrue(plan is PlanResult.Ready, "Stop must be plannable once the stuck transaction is cleared, got $plan")
        val steps = (plan as PlanResult.Ready).plan.steps.map { it.commandKind }
        assertTrue(
            CommandKind.SEAL_DL3_COMPENSATE_18 in steps && CommandKind.SEAL_DL3_COMPENSATE_0 in steps,
            "the resumed Stop plan must still close the OEM projection",
        )
    }

    @Test
    fun `an abandoned transaction with no pending Stop reconciles exactly as before`() {
        val fixture = fixture(stopRequested = false)
        fixture.states += idleClean()

        assertTrue(fixture.coordinator.reconcileAbandoned())

        val envelope = fixture.envelope()
        assertNull(envelope.transaction)
        assertFalse(envelope.stopRequested, "no Stop was pending, so nothing should appear to be pending after reconciliation")
    }

    @Test
    fun `reconciliation refuses when the cluster is not observed idle, regardless of a pending Stop`() {
        val fixture = fixture(stopRequested = true)
        fixture.states += ObservedState(
            ObservedCoarseState.ACTIVE_SINGLE, "display-2",
            CastTarget("com.example.maps", 7, 2), emptySet(), null,
            null, emptyMap(), null, "android-user-10", "fission_bg_xdja",
        )

        assertFalse(fixture.coordinator.reconcileAbandoned())
        assertTrue(fixture.envelope().transaction != null, "an occupied cluster must never be declared abandoned")
    }

    private fun idleClean() = ObservedState(
        ObservedCoarseState.IDLE_CLEAN, "display-2", null, emptySet(), null,
        null, emptyMap(), null, "android-user-10", "fission_bg_xdja",
    )

    private fun fixture(stopRequested: Boolean): Fixture {
        val store = CastSessionStore(MemoryAtomicBytes())
        val stuck = CastTransaction(
            UUID(0L, 900L), 0L, CastOperation.CAST, OperationPhase.RECOVERING,
            null, "com.example.maps", TargetClass.NORMAL.name, "display-2", CastBaseline(),
            listOf(
                LedgerStep(
                    "start", "known", CommandKind.START_FRESH_NORMAL, 0L, null,
                    LedgerEffect.ISSUED, null, false,
                ),
            ),
            0, 60_000L, "transport lost", "expected", false,
        )
        val stable = StableCastSession(
            StableState.ACTIVE_VERIFIED, EngineVersion.V2, "test", null, "display-2",
            CastBaseline(geometry = AcceptedGeometry(CastRect(0, 0, 1920, 720), 180, "android-user-10")),
            CastTarget("com.example.maps", 7, 2), null, null, 1,
        )
        store.locked {
            initialize("boot")
            update {
                it.copy(
                    effectiveUiVersion = EngineVersion.V2,
                    stableSession = stable,
                    transaction = stuck,
                    stopRequested = stopRequested,
                )
            }
        }
        val states = ArrayDeque<ObservedState>()
        val executor = CastExecutor(
            store,
            CastMutationGateway { MutationResult.Observed("known") },
            nowEpochMillis = { 1_000L },
            operationId = { UUID(0L, 901L) },
            sleeper = CastSleeper { },
        )
        val reader = ObservedStateReader(
            RawShell(),
            ObservedStateParser {
                if (states.isEmpty()) ObservationValue.Unknown("no scripted state") else ObservationValue.Known(states.removeFirst())
            },
            nowEpochMillis = { 1_000L },
        )
        val coordinator = CastCoordinator(
            store, reader, executor, CastRecovery(store, executor),
            manualSleeper = CastSleeper { }, manualVerificationDelayMillis = 0L,
        )
        return Fixture(store, coordinator, states)
    }

    private data class Fixture(
        val store: CastSessionStore,
        val coordinator: CastCoordinator,
        val states: ArrayDeque<ObservedState>,
    ) {
        fun envelope() = (store.locked { read() } as StoreRead.Loaded).envelope
    }

    private class RawShell : ShellGateway {
        override fun execute(request: ReadOnlyShellRequest): ShellResult {
            val output = when (request.kind) {
                CommandKind.AM_STACK_LIST -> "Stack id=0 displayId=0 userId=0\n  taskId=1: com.example.launcher/.Main"
                CommandKind.WM_DISPLAYS -> "Display: mDisplayId=0"
                CommandKind.DISPLAY_STATE -> "Display 0:\n  mDisplayId=0"
                CommandKind.PROFILE_STATE -> "10"
                CommandKind.ANIMATION_STATE -> "1.0\n0.5\n1.0"
                CommandKind.APP_OPS_STATE -> "No app ops"
                else -> error("Unexpected read ${request.kind}")
            }
            return ShellResult.Success(output, "", 1L)
        }
        override fun close() {}
    }

    private class MemoryAtomicBytes : AtomicBytes {
        private var bytes: ByteArray? = null
        override fun exists() = bytes != null
        override fun read(): ByteArray = bytes?.copyOf() ?: throw IOException("missing")
        override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
    }
}

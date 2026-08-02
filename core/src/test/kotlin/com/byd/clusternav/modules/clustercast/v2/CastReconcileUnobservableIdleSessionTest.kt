package com.byd.clusternav.modules.clustercast.v2

import java.io.IOException
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Locks the bug found 2026-07-29 on a real ignition power cycle: `stableSession` is deliberately
 * preserved across a boot change (`CastSessionStore`/`CastLifecycleMigration` keep it so an unrelated
 * transaction can still be diagnosed), but the cluster's virtual display is WindowManager-level only
 * and never survives a reboot. Nothing outside the boot-receiver/watchdog path
 * (`CastLifecycleMigration.revalidateStable`) ever re-verified a *non-transaction* idle claim, and
 * that path is never reached by the Activity's own `refresh()` — so a driver who simply opened the
 * Cast screen after any reboot found every cast button disabled ("Cần xử lý thủ công · Chưa nhận diện
 * được trạng thái cụm"), on every boot after the very first successful cast, for every app, with no
 * self-heal action offered.
 *
 * `reconcileUnobservableIdleSession()` is the narrow fix: it only ever fires when nothing was active
 * (`IDLE_VERIFIED`) and the cluster display cannot be found AT ALL (`Unknown` with the exact
 * "no named cluster display" reason) — never for a `Known`-but-mismatched observation, which may mean
 * something unexpected really is on the display and must stay conservative.
 */
class CastReconcileUnobservableIdleSessionTest {

    @Test
    fun `an idle session with no cluster display to observe is cleared so the next attempt can bootstrap fresh`() {
        val fixture = fixture(state = StableState.IDLE_VERIFIED)
        fixture.observations += ObservationValue.Unknown(MISSING_NAMED_CLUSTER_DISPLAY_REASON)

        assertTrue(fixture.coordinator.reconcileUnobservableIdleSession())

        val after = fixture.envelope()
        assertNull(after.stableSession, "the stale idle claim must be cleared")
        assertNull(after.transaction, "no transaction existed and none should appear")
    }

    @Test
    fun `refuses when the cluster is actually observed, even if it does not match the baseline`() {
        val fixture = fixture(state = StableState.IDLE_VERIFIED)
        fixture.observations += ObservationValue.Known(
            ObservedState(
                ObservedCoarseState.ACTIVE_SINGLE, "display-9",
                CastTarget("com.example.other", 3, 1), emptySet(), null,
                null, emptyMap(), null, "android-user-0", "fission_bg_xdja",
            ),
        )

        assertFalse(fixture.coordinator.reconcileUnobservableIdleSession())
        assertNotNull(fixture.envelope().stableSession, "a real (even mismatched) observation must never be auto-cleared")
    }

    @Test
    fun `refuses for an active session even when the display cannot be found`() {
        val fixture = fixture(state = StableState.ACTIVE_VERIFIED)
        fixture.observations += ObservationValue.Unknown(MISSING_NAMED_CLUSTER_DISPLAY_REASON)

        assertFalse(
            fixture.coordinator.reconcileUnobservableIdleSession(),
            "an active target must go through the vanished/restore path, not this one",
        )
        assertNotNull(fixture.envelope().stableSession)
    }

    @Test
    fun `refuses while a transaction is still unresolved`() {
        val fixture = fixture(state = StableState.IDLE_VERIFIED)
        fixture.observations += ObservationValue.Unknown(MISSING_NAMED_CLUSTER_DISPLAY_REASON)
        fixture.store.locked {
            update {
                it.copy(
                    transaction = CastTransaction(
                        UUID(0L, 700L), 0L, CastOperation.CAST, OperationPhase.RECOVERING,
                        null, "com.example.maps", TargetClass.NORMAL.name, "display-1", CastBaseline(),
                        emptyList(), 0, 60_000L, "transport lost", "expected", false,
                    ),
                )
            }
        }

        assertFalse(fixture.coordinator.reconcileUnobservableIdleSession())
    }

    /**
     * Added in review 2026-07-29. Clearing the claim is only worth doing when clearing is by itself
     * enough to reach cold-pristine. Under a durable Stop it is not: `isColdPristine` stays false, so
     * the screen would be just as stuck, the baseline evidence would be gone, and — worse —
     * `CastRolloutRegistry.resolve` reports no action owner once nothing is recorded, which turns
     * `CastFacade.v2OwnsActions` false and leaves the pending Stop with no way left to dispatch and
     * clear itself. The right answer is to leave the envelope alone and retry on a later refresh.
     */
    @Test
    fun `refuses when clearing alone would not reach cold pristine`() {
        listOf<(CastSessionEnvelope) -> CastSessionEnvelope>(
            { it.copy(stopRequested = true) },
            { it.copy(pendingIntent = PendingCastIntent("com.example.maps")) },
            { it.copy(pendingUiRollback = true) },
        ).forEach { block ->
            val fixture = fixture(state = StableState.IDLE_VERIFIED)
            fixture.observations += ObservationValue.Unknown(MISSING_NAMED_CLUSTER_DISPLAY_REASON)
            fixture.store.locked { update(block) }

            assertFalse(fixture.coordinator.reconcileUnobservableIdleSession())
            assertNotNull(
                fixture.envelope().stableSession,
                "a claim must not be destroyed when destroying it cannot unblock the screen",
            )
        }
    }

    private fun fixture(state: StableState): Fixture {
        val store = CastSessionStore(MemoryAtomicBytes())
        val stable = StableCastSession(
            state, EngineVersion.V2, "test", null, "display-1",
            CastBaseline(geometry = AcceptedGeometry(CastRect(0, 0, 1920, 720), 320, "android-user-0")),
            if (state == StableState.IDLE_VERIFIED) null else CastTarget("com.example.maps", 7, 1),
            null, null, 1,
        )
        store.locked {
            initialize("boot-1")
            update { it.copy(effectiveUiVersion = EngineVersion.V2, stableSession = stable) }
        }
        val observations = ArrayDeque<ObservationValue<ObservedState>>()
        val executor = CastExecutor(
            store,
            CastMutationGateway { MutationResult.Observed("known") },
            nowEpochMillis = { 1_000L },
            operationId = { UUID(0L, 701L) },
            sleeper = CastSleeper { },
        )
        val reader = ObservedStateReader(
            NoopShell(),
            ObservedStateParser {
                if (observations.isEmpty()) ObservationValue.Unknown("no scripted state") else observations.removeFirst()
            },
            nowEpochMillis = { 1_000L },
        )
        val coordinator = CastCoordinator(
            store, reader, executor, CastRecovery(store, executor),
            manualSleeper = CastSleeper { }, manualVerificationDelayMillis = 0L,
        )
        return Fixture(store, coordinator, observations)
    }

    private data class Fixture(
        val store: CastSessionStore,
        val coordinator: CastCoordinator,
        val observations: ArrayDeque<ObservationValue<ObservedState>>,
    ) {
        fun envelope() = (store.locked { read() } as StoreRead.Loaded).envelope
    }

    /**
     * `ObservedStateReader.read()` first runs [ObservedStateReader.inspectRaw] over the real shell —
     * every command must return non-blank output or it short-circuits to `Unknown` before the scripted
     * [ObservedStateParser] lambda below is ever consulted. The dummy content here is irrelevant to
     * these tests (the parser ignores it and returns the scripted [observations] queue instead); it
     * only needs to be non-blank so `inspectRaw` succeeds.
     */
    private class NoopShell : ShellGateway {
        override fun execute(request: ReadOnlyShellRequest): ShellResult {
            val output = when (request.kind) {
                CommandKind.AM_STACK_LIST -> "Stack id=0 displayId=0 userId=0\n  taskId=1: com.example.launcher/.Main"
                CommandKind.WM_DISPLAYS -> "Display: mDisplayId=0"
                CommandKind.DISPLAY_STATE -> "Display 0:\n  mDisplayId=0"
                CommandKind.PROFILE_STATE -> "0"
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

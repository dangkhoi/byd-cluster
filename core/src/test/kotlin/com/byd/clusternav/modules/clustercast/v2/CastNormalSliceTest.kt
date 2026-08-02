package com.byd.clusternav.modules.clustercast.v2

import java.io.IOException
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastNormalSliceTest {
    private val geometry = AcceptedGeometry(CastRect(0, 0, 1920, 720), 160, "test-profile")

    @Test
    fun `normal cast publishes stable state only after matching observed target`() {
        val fixture = fixture()
        val result = fixture.executor.execute(plan(0), CastBaseline(), "com.example.maps")
            as ExecutionResult.AwaitingVerification
        assertEquals(StableState.IDLE_VERIFIED, envelope(fixture.store).stableSession!!.state)
        val wrong = ObservedState(
            ObservedCoarseState.ACTIVE_SINGLE, "display", CastTarget("com.example.other", 1, 1),
            setOf("com.example.other"), null, null,
        )
        assertFalse(fixture.coordinator.completeVerification(result.operationId, wrong))
        assertEquals(OperationPhase.RECOVERING, envelope(fixture.store).transaction!!.phase)
    }

    @Test
    fun `matching observation clears transaction and persists active verified`() {
        val fixture = fixture()
        val result = fixture.executor.execute(plan(0), CastBaseline(), "com.example.maps")
            as ExecutionResult.AwaitingVerification
        val observed = ObservedState(
            ObservedCoarseState.ACTIVE_SINGLE, "display", CastTarget("com.example.maps", 7, 1),
            setOf("com.example.maps"), null, geometry,
        )
        assertFalse(fixture.coordinator.completeVerification(result.operationId, observed))
        assertTrue(fixture.coordinator.completeVerification(result.operationId, observed))
        val envelope = envelope(fixture.store)
        assertNull(envelope.transaction)
        assertEquals(StableState.ACTIVE_VERIFIED, envelope.stableSession!!.state)
        assertEquals("com.example.maps", envelope.stableSession!!.activeTarget!!.packageName)
    }

    @Test
    fun `latest in flight target remains durable through stable verification`() {
        val fixture = fixture()
        val result = fixture.executor.execute(plan(0), CastBaseline(), "com.example.maps")
            as ExecutionResult.AwaitingVerification
        assertTrue(fixture.coordinator.queueLatestTarget("com.example.first"))
        assertTrue(fixture.coordinator.queueLatestTarget("com.example.latest"))
        assertEquals("com.example.latest", envelope(fixture.store).pendingPackage)

        val observed = ObservedState(
            ObservedCoarseState.ACTIVE_SINGLE, "display", CastTarget("com.example.maps", 7, 1),
            setOf("com.example.maps"), null, geometry,
        )
        assertFalse(fixture.coordinator.completeVerification(result.operationId, observed))
        assertTrue(fixture.coordinator.completeVerification(result.operationId, observed))
        assertEquals("com.example.latest", envelope(fixture.store).pendingPackage)
        assertNull(envelope(fixture.store).transaction)
    }

    @Test
    fun `rehydrated stable pending target accepts latest restored selection before resume`() {
        val fixture = fixture()
        fixture.store.locked { update { it.copy(pendingIntent = PendingCastIntent("com.example.old")) } }

        assertTrue(fixture.coordinator.queueLatestTarget("com.example.latest"))
        val envelope = envelope(fixture.store)
        assertNull(envelope.transaction)
        assertEquals("com.example.latest", envelope.pendingPackage)
    }

    @Test
    fun `Stop before verification prevents late active fold`() {
        val fixture = fixture()
        val result = fixture.executor.execute(plan(0), CastBaseline(), "com.example.maps")
            as ExecutionResult.AwaitingVerification
        fixture.coordinator.requestStop()
        val observed = ObservedState(
            ObservedCoarseState.ACTIVE_SINGLE, "display", CastTarget("com.example.maps", 7, 1),
            setOf("com.example.maps"), null, geometry,
        )

        assertFalse(fixture.coordinator.completeVerification(result.operationId, observed))
        val envelope = envelope(fixture.store)
        assertEquals(StableState.IDLE_VERIFIED, envelope.stableSession!!.state)
        assertEquals(OperationPhase.RECOVERING, envelope.transaction!!.phase)
        assertEquals("verification fenced by Stop or epoch change", envelope.transaction!!.lastFailure)
    }

    @Test
    fun `Stop supersedes and clears durable latest target`() {
        val fixture = fixture()
        fixture.executor.execute(plan(0), CastBaseline(), "com.example.maps")
        assertTrue(fixture.coordinator.queueLatestTarget("com.example.latest"))
        val stopped = fixture.coordinator.requestStop()!!
        assertTrue(stopped.stopRequested)
        assertNull(stopped.pendingPackage)
    }

    private fun fixture(): Fixture {
        val bytes = MemoryAtomicBytes()
        val store = CastSessionStore(bytes)
        store.locked {
            initialize("boot")
            update { it.copy(stableSession = idleSession()) }
        }
        val gateway = CastMutationGateway { MutationResult.Observed("ok") }
        val executor = CastExecutor(store, gateway, operationId = {
            UUID.fromString("00000000-0000-0000-0000-000000000127")
        })
        val reader = ObservedStateReader(object : ShellGateway {
            override fun execute(request: ReadOnlyShellRequest) = ShellResult.Success("known", "", 1)
            override fun close() = Unit
        }) { ObservationValue.Known(ObservedState(ObservedCoarseState.IDLE_CLEAN, "display", null, emptySet(), null, null)) }
        val recovery = CastRecovery(store, executor)
        return Fixture(store, executor, CastCoordinator(store, reader, executor, recovery))
    }

    private fun envelope(store: CastSessionStore) = (store.locked { read() } as StoreRead.Loaded).envelope
    private fun plan(epoch: Long) = CastPlan(
        CastOperation.CAST, epoch,
        listOf(PlannedStep("start", CommandKind.START_FRESH_NORMAL, "known")),
        "visible", setOf(CastAction.STOP), expectedDisplayIdentity = "display",
    )

    private fun idleSession() = StableCastSession(
        StableState.IDLE_VERIFIED, EngineVersion.V2, "test", null, "display",
        CastBaseline(geometry = geometry), null, null, geometry, 1,
    )

    private data class Fixture(val store: CastSessionStore, val executor: CastExecutor, val coordinator: CastCoordinator)
    private class MemoryAtomicBytes : AtomicBytes {
        var bytes: ByteArray? = null
        override fun exists() = bytes != null
        override fun read(): ByteArray = bytes?.copyOf() ?: throw IOException("missing")
        override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
    }
}

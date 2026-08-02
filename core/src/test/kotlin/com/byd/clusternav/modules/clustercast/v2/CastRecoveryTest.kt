package com.byd.clusternav.modules.clustercast.v2

import java.io.IOException
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastRecoveryTest {
    @Test
    fun `unknown effect is never replayed or compensated across executor recreation`() {
        val bytes = MemoryAtomicBytes()
        val store = CastSessionStore(bytes)
        store.locked {
            initialize("boot-a")
            update { it.copy(stableSession = idleSession()) }
        }
        val calls = mutableListOf<CommandKind>()
        val firstGateway = CastMutationGateway { request ->
            calls += request.kind
            MutationResult.UnknownEffect("transport closed")
        }
        val id = UUID.fromString("00000000-0000-0000-0000-000000000123")
        val first = CastExecutor(store, firstGateway, nowEpochMillis = { 1_000 }, operationId = { id })
        val result = first.execute(plan(0), CastBaseline(), "com.example.maps")
        assertTrue(result is ExecutionResult.RecoveryRequired)
        assertEquals(listOf(CommandKind.START_FRESH_NORMAL), calls)

        val recreatedGateway = CastMutationGateway { request ->
            calls += request.kind
            MutationResult.Observed("must not run")
        }
        val recreatedStore = CastSessionStore(bytes)
        val recreatedExecutor = CastExecutor(recreatedStore, recreatedGateway, nowEpochMillis = { 1_100 })
        val recovery = CastRecovery(recreatedStore, recreatedExecutor, nowEpochMillis = { 1_100 })
        assertTrue(recovery.compensate() is ExecutionResult.Blocked)
        assertEquals(listOf(CommandKind.START_FRESH_NORMAL), calls)
        val loaded = recreatedStore.locked { read() } as StoreRead.Loaded
        assertFalse(loaded.envelope.transaction!!.compensationUsed)

        val afterRestart = CastRecovery(
            CastSessionStore(bytes),
            CastExecutor(CastSessionStore(bytes), recreatedGateway),
            nowEpochMillis = { 1_200 },
        )
        assertTrue(afterRestart.compensate() is ExecutionResult.Blocked)
        assertEquals(1, calls.size)
    }

    @Test
    fun `unknown effect is classified manual rather than compensation eligible`() {
        val bytes = MemoryAtomicBytes()
        val store = CastSessionStore(bytes)
        store.locked {
            initialize("boot")
            update { it.copy(stableSession = idleSession()) }
        }
        val executor = CastExecutor(store, CastMutationGateway { MutationResult.UnknownEffect("x") }, operationId = {
            UUID.fromString("00000000-0000-0000-0000-000000000124")
        })
        executor.execute(plan(0), CastBaseline(), "com.example.maps")
        val decision = CastRecovery(store, executor).decide()
        assertTrue(decision is RecoveryDecision.Manual)
    }

    private fun plan(epoch: Long) = CastPlan(
        CastOperation.CAST,
        epoch,
        listOf(PlannedStep("start", CommandKind.START_FRESH_NORMAL, "known target")),
        "target visible",
        setOf(CastAction.STOP),
    )

    private fun idleSession() = StableCastSession(
        StableState.IDLE_VERIFIED, EngineVersion.V2, "test", null, "display",
        CastBaseline(), null, null, null, 1,
    )

    private class MemoryAtomicBytes : AtomicBytes {
        var bytes: ByteArray? = null
        override fun exists() = bytes != null
        override fun read(): ByteArray = bytes?.copyOf() ?: throw IOException("missing")
        override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
    }
}

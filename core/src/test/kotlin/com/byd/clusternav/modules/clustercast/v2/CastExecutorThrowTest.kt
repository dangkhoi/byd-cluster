package com.byd.clusternav.modules.clustercast.v2

import java.io.IOException
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastExecutorThrowTest {
    @Test
    fun `thrown mutation remains issued and requires recovery without replay`() {
        val bytes = MemoryAtomicBytes()
        val store = initialized(bytes)
        var calls = 0
        val executor = CastExecutor(store, CastMutationGateway {
            calls++
            throw IOException("transport disappeared")
        }, operationId = { id() })

        val result = executor.execute(plan(0), CastBaseline(), "com.example.maps")
        assertTrue(result is ExecutionResult.RecoveryRequired)
        val tx = loaded(store)
        assertEquals(OperationPhase.RECOVERING, tx.phase)
        assertEquals(LedgerEffect.ISSUED, tx.ledger.single().effect)
        assertEquals(1, calls)

        val recreated = CastExecutor(CastSessionStore(bytes), CastMutationGateway { calls++; MutationResult.Observed("bad replay") })
        assertTrue(recreated.execute(plan(1), CastBaseline(), "com.example.maps") is ExecutionResult.Blocked)
        assertEquals(1, calls)
    }

    @Test
    fun `unknown mutation never opens or consumes compensation budget`() {
        val bytes = MemoryAtomicBytes()
        val store = initialized(bytes)
        val primary = CastExecutor(store, CastMutationGateway { MutationResult.UnknownEffect("lost reply") }, operationId = { id() })
        assertTrue(primary.execute(plan(0), CastBaseline(), "com.example.maps") is ExecutionResult.RecoveryRequired)

        var compensationCalls = 0
        val compensator = CastExecutor(store, CastMutationGateway {
            compensationCalls++
            throw IOException("must not dispatch")
        })
        assertTrue(compensator.compensate(id()) is ExecutionResult.Blocked)
        assertFalse(loaded(store).compensationUsed)

        val recreated = CastExecutor(CastSessionStore(bytes), CastMutationGateway {
            compensationCalls++
            MutationResult.Observed("must not execute")
        })
        assertTrue(recreated.compensate(id()) is ExecutionResult.Blocked)
        assertEquals(0, compensationCalls)
    }

    private fun initialized(bytes: MemoryAtomicBytes) = CastSessionStore(bytes).also { store ->
        store.locked {
            initialize("boot-a")
            update { it.copy(stableSession = idleSession("display")) }
        }
    }
    private fun idleSession(display: String) = StableCastSession(
        StableState.IDLE_VERIFIED, EngineVersion.V2, "test", null, display,
        CastBaseline(), null, null, null, 1,
    )
    private fun loaded(store: CastSessionStore) = (store.locked { read() } as StoreRead.Loaded).envelope.transaction!!
    private fun id() = UUID.fromString("00000000-0000-0000-0000-000000000777")
    private fun plan(epoch: Long) = CastPlan(
        CastOperation.CAST,
        epoch,
        listOf(PlannedStep("start", CommandKind.START_FRESH_NORMAL, "known")),
        "visible",
        setOf(CastAction.STOP),
    )

    private class MemoryAtomicBytes : AtomicBytes {
        private var bytes: ByteArray? = null
        override fun exists() = bytes != null
        override fun read(): ByteArray = bytes?.copyOf() ?: throw IOException("missing")
        override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
    }
}

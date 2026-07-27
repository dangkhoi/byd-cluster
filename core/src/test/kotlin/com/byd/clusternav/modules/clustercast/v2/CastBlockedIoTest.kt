package com.byd.clusternav.modules.clustercast.v2

import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastBlockedIoTest {
    @Test
    fun `blocked gateway holds the sole mutation lease and second operation cannot start`() {
        val bytes = MemoryAtomicBytes()
        val store = CastSessionStore(bytes)
        store.locked {
            initialize("boot")
            update { it.copy(stableSession = idleSession()) }
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gateway = CastMutationGateway {
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
            MutationResult.UnknownEffect("closed")
        }
        val executor = CastExecutor(store, gateway, operationId = {
            UUID.fromString("00000000-0000-0000-0000-000000000125")
        })
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<ExecutionResult> { executor.execute(plan(0), CastBaseline(), "com.example.maps") }
            assertTrue(entered.await(500, TimeUnit.MILLISECONDS))
            val second = pool.submit<ExecutionResult> { executor.execute(plan(0), CastBaseline(), "com.example.other") }
            Thread.sleep(50)
            assertFalse(second.isDone, "second mutation crossed the lease")
            release.countDown()
            assertTrue(first.get(1, TimeUnit.SECONDS) is ExecutionResult.RecoveryRequired)
            assertTrue(second.get(1, TimeUnit.SECONDS) is ExecutionResult.Blocked)
            val envelope = (store.locked { read() } as StoreRead.Loaded).envelope
            assertEquals(1, envelope.durableEpoch)
            assertEquals(OperationPhase.RECOVERING, envelope.transaction!!.phase)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `Stop persists epoch and fences blocked transport without waiting for mutation lease`() {
        val bytes = MemoryAtomicBytes()
        val store = CastSessionStore(bytes)
        store.locked {
            initialize("boot")
            update { it.copy(stableSession = idleSession()) }
        }
        val gateway = BlockingFenceGateway()
        val executor = CastExecutor(store, gateway, operationId = {
            UUID.fromString("00000000-0000-0000-0000-000000000225")
        })
        val reader = ObservedStateReader(object : ShellGateway {
            override fun execute(request: ReadOnlyShellRequest) = ShellResult.Success("known", "", 1)
            override fun close() = Unit
        }) { ObservationValue.Known(ObservedState(ObservedCoarseState.IDLE_CLEAN, "display-2", null, emptySet(), null, null)) }
        val coordinator = CastCoordinator(store, reader, executor, CastRecovery(store, executor))
        val pool = Executors.newSingleThreadExecutor()
        try {
            val first = pool.submit<ExecutionResult> { executor.execute(plan(0), CastBaseline(), "com.example.maps") }
            assertTrue(gateway.entered.await(500, TimeUnit.MILLISECONDS))
            val started = System.nanoTime()
            val accepted = coordinator.requestStop()
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue(elapsedMs <= 500, "Stop acknowledgment took ${'$'}elapsedMs ms")
            assertTrue(gateway.fenced)
            assertTrue(accepted!!.stopRequested)
            assertEquals(2, accepted.durableEpoch)
            assertTrue(first.get(1, TimeUnit.SECONDS) is ExecutionResult.RecoveryRequired)
            val durable = (store.locked { read() } as StoreRead.Loaded).envelope
            assertTrue(durable.stopRequested)
            assertEquals(OperationPhase.RECOVERING, durable.transaction!!.phase)
        } finally {
            gateway.release.countDown()
            pool.shutdownNow()
        }
    }

    private fun plan(epoch: Long) = CastPlan(
        CastOperation.CAST, epoch,
        listOf(PlannedStep("start", CommandKind.START_FRESH_NORMAL, "known")),
        "visible", setOf(CastAction.STOP),
    )

    private class BlockingFenceGateway : CastMutationGateway {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var fenced = false
        @Volatile private var fenceToken = 0L

        override fun execute(request: CastMutationRequest): MutationResult {
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
            return MutationResult.UnknownEffect("fenced")
        }

        override fun fenceInFlight() {
            fenced = true
            fenceToken++
            release.countDown()
        }

        override fun currentFenceToken(): Long = fenceToken
    }

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

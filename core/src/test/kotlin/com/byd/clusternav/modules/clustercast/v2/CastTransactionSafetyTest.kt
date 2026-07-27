package com.byd.clusternav.modules.clustercast.v2

import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastTransactionSafetyTest {
    private val id = UUID.fromString("00000000-0000-0000-0000-000000000701")
    private val geometry = AcceptedGeometry(CastRect(0, 0, 1920, 720), 160, "test-profile")

    @Test
    fun `Stop between durable issue and transport entry prevents dispatch`() {
        val store = initializedStore()
        val gateway = StopBeforeDispatchGateway()
        val executor = CastExecutor(store, gateway, operationId = { id })
        val coordinator = coordinator(store, executor)
        gateway.beforeDispatch = { coordinator.requestStop() }

        assertTrue(executor.execute(singleStepPlan(), CastBaseline(), "com.example.maps") is ExecutionResult.RecoveryRequired)

        val envelope = envelope(store)
        assertFalse(gateway.dispatched)
        assertTrue(envelope.stopRequested)
        assertEquals(2, envelope.durableEpoch)
        assertEquals(LedgerEffect.REJECTED, envelope.transaction!!.ledger.single().effect)
        assertEquals(1L, gateway.currentFenceToken())
    }

    @Test
    fun `command is not issued exactly at its deadline`() {
        val store = initializedStore()
        val times = ArrayDeque(listOf(1_000L, 1_001L))
        var calls = 0
        val executor = CastExecutor(
            store,
            CastMutationGateway { calls++; MutationResult.Observed("must not run") },
            nowEpochMillis = { times.removeFirstOrNull() ?: 1_001L },
            operationId = { id },
            operationTimeoutMillis = 1L,
        )

        assertTrue(executor.execute(singleStepPlan(), CastBaseline(), "com.example.maps") is ExecutionResult.RecoveryRequired)
        assertEquals(0, calls)
        assertEquals(LedgerEffect.PLANNED, envelope(store).transaction!!.ledger.single().effect)
    }

    @Test
    fun `direct verifier waits for the sole executor lease`() {
        val store = initializedStore()
        val executor = CastExecutor(store, CastMutationGateway { MutationResult.Observed("ok") }, operationId = { id })
        val coordinator = coordinator(store, executor)
        val execution = executor.execute(singleStepPlan(), CastBaseline(), "com.example.maps")
            as ExecutionResult.AwaitingVerification
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val holder = pool.submit {
                executor.withMutationLease {
                    entered.countDown()
                    release.await(2, TimeUnit.SECONDS)
                }
            }
            assertTrue(entered.await(500, TimeUnit.MILLISECONDS))
            val verifierStarted = CountDownLatch(1)
            val verifier = pool.submit<Boolean> {
                verifierStarted.countDown()
                coordinator.completeVerification(execution.operationId, activeObservation())
            }
            assertTrue(verifierStarted.await(500, TimeUnit.MILLISECONDS))
            assertThrows(TimeoutException::class.java) { verifier.get(50, TimeUnit.MILLISECONDS) }
            release.countDown()
            holder.get(1, TimeUnit.SECONDS)
            assertFalse(verifier.get(1, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `Unknown breaks two equal verification sample continuity`() {
        val store = initializedStore()
        val samples = ArrayDeque<ObservationValue<ObservedState>>()
        samples += ObservationValue.Unknown("transport gap")
        val executor = CastExecutor(store, CastMutationGateway { MutationResult.Observed("ok") }, operationId = { id })
        val coordinator = coordinator(store, executor) { samples.removeFirstOrNull() ?: ObservationValue.Unknown("empty") }
        val execution = executor.execute(singleStepPlan(), CastBaseline(), "com.example.maps")
            as ExecutionResult.AwaitingVerification
        val active = activeObservation()

        assertFalse(coordinator.completeVerification(execution.operationId, active))
        assertFalse(coordinator.observeAndComplete(execution.operationId))
        assertFalse(coordinator.completeVerification(execution.operationId, active))
        assertTrue(coordinator.completeVerification(execution.operationId, active))
        assertNull(envelope(store).transaction)
    }

    @Test
    fun `unequal Known pair is abandoned before the next verification attempt`() {
        val store = initializedStore()
        val first = activeObservation(taskId = 7)
        val second = activeObservation(taskId = 8)
        val samples = ArrayDeque<ObservationValue<ObservedState>>()
        samples += ObservationValue.Known(first)
        samples += ObservationValue.Known(second)
        val executor = CastExecutor(store, CastMutationGateway { MutationResult.Observed("ok") }, operationId = { id })
        val coordinator = coordinator(store, executor) { samples.removeFirstOrNull() ?: ObservationValue.Unknown("empty") }
        val execution = executor.execute(singleStepPlan(), CastBaseline(), "com.example.maps")
            as ExecutionResult.AwaitingVerification

        assertFalse(coordinator.observeAndComplete(execution.operationId))
        assertFalse(coordinator.completeVerification(execution.operationId, second))
        assertTrue(coordinator.completeVerification(execution.operationId, second))
        assertNull(envelope(store).transaction)
    }

    @Test
    fun `post-dispatch shell failure remains unknown and non-compensatable`() {
        val store = initializedStore()
        val postDispatch = classifyMutationShellResult(1, "partial output", "remote shell failure")
        assertTrue(postDispatch is MutationResult.UnknownEffect)
        var calls = 0
        val executor = CastExecutor(store, CastMutationGateway {
            calls++
            postDispatch
        }, operationId = { id })

        assertTrue(executor.execute(singleStepPlan(), CastBaseline(), "com.example.maps") is ExecutionResult.RecoveryRequired)
        assertEquals(1, calls)
        assertEquals(LedgerEffect.ISSUED, envelope(store).transaction!!.ledger.single().effect)
        assertTrue(executor.compensate(id) is ExecutionResult.Blocked)
        assertEquals(1, calls)
    }

    @Test
    fun `known failure permits one compensation of the last observed effect`() {
        val store = initializedStore()
        val calls = mutableListOf<CommandKind>()
        val executor = CastExecutor(store, CastMutationGateway { request ->
            calls += request.kind
            if (request.kind == CommandKind.FORCE_STOP_NORMAL) MutationResult.Rejected("known reject")
            else MutationResult.Observed("ok")
        }, operationId = { id })

        val failed = executor.execute(twoStepPlan(), CastBaseline(), "com.example.maps")
            as ExecutionResult.RecoveryRequired
        assertEquals(listOf(LedgerEffect.OBSERVED, LedgerEffect.REJECTED), envelope(store).transaction!!.ledger.map { it.effect })
        assertTrue(executor.compensate(failed.operationId) is ExecutionResult.RecoveryRequired)
        assertEquals(CommandKind.RETURN_NORMAL_TO_MAIN, calls.last())
        val compensatedStep = envelope(store).transaction!!.ledger.first()
        assertTrue(compensatedStep.compensationIssuedAtEpochMillis != null)
        assertEquals(0L, compensatedStep.compensationGatewayGeneration)
        assertEquals(LedgerEffect.OBSERVED, compensatedStep.compensationEffect)
        assertTrue(compensatedStep.compensationObserved)
        val countAfterOne = calls.size
        assertTrue(executor.compensate(failed.operationId) is ExecutionResult.Blocked)
        assertEquals(countAfterOne, calls.size)
        assertTrue(envelope(store).transaction!!.compensationUsed)
    }

    @Test
    fun `unknown compensation remains issued and one-shot across retry`() {
        val store = initializedStore()
        val calls = mutableListOf<CommandKind>()
        val executor = CastExecutor(store, CastMutationGateway { request ->
            calls += request.kind
            when (request.kind) {
                CommandKind.FORCE_STOP_NORMAL -> MutationResult.Rejected("known reject")
                CommandKind.RETURN_NORMAL_TO_MAIN -> MutationResult.UnknownEffect("lost compensation reply")
                else -> MutationResult.Observed("ok")
            }
        }, operationId = { id })
        val failed = executor.execute(twoStepPlan(), CastBaseline(), "com.example.maps")
            as ExecutionResult.RecoveryRequired

        assertTrue(executor.compensate(failed.operationId) is ExecutionResult.RecoveryRequired)
        val step = envelope(store).transaction!!.ledger.first()
        assertEquals(LedgerEffect.ISSUED, step.compensationEffect)
        assertFalse(step.compensationObserved)
        val count = calls.size
        assertTrue(executor.compensate(failed.operationId) is ExecutionResult.Blocked)
        assertEquals(count, calls.size)
    }

    @Test
    fun `Stop blocks otherwise eligible compensation before dispatch`() {
        val store = initializedStore()
        val calls = mutableListOf<CommandKind>()
        val executor = knownFailureExecutor(store, calls)
        val failed = executor.execute(twoStepPlan(), CastBaseline(), "com.example.maps")
            as ExecutionResult.RecoveryRequired
        coordinator(store, executor).requestStop()
        val before = calls.size

        assertTrue(executor.compensate(failed.operationId) is ExecutionResult.Blocked)
        assertEquals(before, calls.size)
        assertFalse(envelope(store).transaction!!.compensationUsed)
    }

    @Test
    fun `expired compensation is blocked at the exact deadline`() {
        val store = initializedStore()
        val calls = mutableListOf<CommandKind>()
        var now = 1_000L
        val executor = CastExecutor(
            store,
            CastMutationGateway { request ->
                calls += request.kind
                if (request.kind == CommandKind.FORCE_STOP_NORMAL) MutationResult.Rejected("known reject")
                else MutationResult.Observed("ok")
            },
            nowEpochMillis = { now },
            operationId = { id },
            operationTimeoutMillis = 10L,
        )
        val failed = executor.execute(twoStepPlan(), CastBaseline(), "com.example.maps")
            as ExecutionResult.RecoveryRequired
        now = 1_010L
        val before = calls.size

        assertTrue(executor.compensate(failed.operationId) is ExecutionResult.Blocked)
        assertEquals(before, calls.size)
        assertFalse(envelope(store).transaction!!.compensationUsed)
    }

    private fun knownFailureExecutor(store: CastSessionStore, calls: MutableList<CommandKind>) =
        CastExecutor(store, CastMutationGateway { request ->
            calls += request.kind
            if (request.kind == CommandKind.FORCE_STOP_NORMAL) MutationResult.Rejected("known reject")
            else MutationResult.Observed("ok")
        }, operationId = { id })

    private fun singleStepPlan() = CastPlan(
        CastOperation.CAST, 0,
        listOf(PlannedStep("start", CommandKind.START_FRESH_NORMAL, "known")),
        "visible", setOf(CastAction.STOP), expectedDisplayIdentity = "display",
    )

    private fun twoStepPlan() = CastPlan(
        CastOperation.CAST, 0,
        listOf(
            PlannedStep("start", CommandKind.START_FRESH_NORMAL, "known"),
            PlannedStep("fail", CommandKind.FORCE_STOP_NORMAL, "known"),
        ),
        "visible", setOf(CastAction.STOP), expectedDisplayIdentity = "display",
    )

    private fun activeObservation(taskId: Int = 7) = ObservedState(
        ObservedCoarseState.ACTIVE_SINGLE,
        "display",
        CastTarget("com.example.maps", taskId, 1),
        setOf("com.example.maps"),
        null,
        geometry,
    )

    private fun coordinator(
        store: CastSessionStore,
        executor: CastExecutor,
        sample: () -> ObservationValue<ObservedState> = { ObservationValue.Unknown("unused") },
    ): CastCoordinator {
        val reader = ObservedStateReader(object : ShellGateway {
            override fun execute(request: ReadOnlyShellRequest) = ShellResult.Success("known", "", 1)
            override fun close() = Unit
        }) { sample() }
        return CastCoordinator(store, reader, executor, CastRecovery(store, executor))
    }

    private fun initializedStore() = CastSessionStore(MemoryAtomicBytes()).also { store ->
        store.locked {
            initialize("boot")
            update { it.copy(stableSession = StableCastSession(
                StableState.IDLE_VERIFIED,
                EngineVersion.V2,
                "test",
                null,
                "display",
                CastBaseline(geometry = geometry),
                null,
                null,
                geometry,
                1,
            )) }
        }
    }

    private fun envelope(store: CastSessionStore) = (store.locked { read() } as StoreRead.Loaded).envelope

    private class StopBeforeDispatchGateway : CastMutationGateway {
        var beforeDispatch: () -> Unit = {}
        var dispatched = false
        private var token = 0L

        override fun execute(request: CastMutationRequest): MutationResult {
            beforeDispatch()
            if (request.fenceToken != token) return MutationResult.Rejected("mutation fenced before dispatch")
            dispatched = true
            return MutationResult.Observed("unexpected")
        }

        override fun fenceInFlight() { token++ }
        override fun currentFenceToken(): Long = token
    }

    private class MemoryAtomicBytes : AtomicBytes {
        private var bytes: ByteArray? = null
        override fun exists() = bytes != null
        override fun read(): ByteArray = bytes?.copyOf() ?: throw IOException("missing")
        override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
    }
}

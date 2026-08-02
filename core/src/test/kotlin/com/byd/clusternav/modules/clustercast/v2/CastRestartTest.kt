package com.byd.clusternav.modules.clustercast.v2

import java.io.IOException
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastRestartTest {
    @Test
    fun `process recreation retains verifying transaction and refuses blind replay`() {
        val bytes = MemoryAtomicBytes()
        val store = CastSessionStore(bytes)
        store.locked {
            initialize("boot-a")
            update { it.copy(stableSession = idleSession()) }
        }
        var calls = 0
        val gateway = CastMutationGateway { calls++; MutationResult.Observed("landed") }
        val executor = CastExecutor(store, gateway, operationId = {
            UUID.fromString("00000000-0000-0000-0000-000000000126")
        })
        assertTrue(executor.execute(plan(0), CastBaseline(), "com.example.maps") is ExecutionResult.AwaitingVerification)
        assertEquals(1, calls)

        val recreated = CastSessionStore(bytes)
        val loaded = recreated.locked { read() } as StoreRead.Loaded
        assertEquals(OperationPhase.VERIFYING, loaded.envelope.transaction!!.phase)
        assertEquals(LedgerEffect.OBSERVED, loaded.envelope.transaction!!.ledger.single().effect)

        val second = CastExecutor(recreated, gateway)
        assertTrue(second.execute(plan(1), CastBaseline(), "com.example.maps") is ExecutionResult.Blocked)
        assertEquals(1, calls, "restart must not replay an already observed mutation")
    }

    @Test
    fun `bootstrap stable fold atomically rejects Stop arriving at commit`() {
        val bytes = MemoryAtomicBytes()
        val store = CastSessionStore(bytes)
        store.locked {
            initialize("boot-a")
            update { it.copy(effectiveUiVersion = EngineVersion.V2) }
        }
        var armed = false
        var armedClockCalls = 0
        val commands = mutableListOf<CommandKind>()
        val executor = CastExecutor(
            store,
            CastMutationGateway { request -> commands += request.kind; MutationResult.Observed("clean") },
            nowEpochMillis = {
                if (armed && ++armedClockCalls == 2) {
                    store.locked { bumpEpoch { it.copy(stopRequested = true) } }
                }
                1_000L
            },
            operationId = { UUID.fromString("00000000-0000-0000-0000-000000000127") },
            bootstrapVerificationAttempts = 2,
            bootstrapVerificationPollMillis = 0,
            sleeper = CastSleeper { },
        )
        var sample = 0
        val result = executor.bootstrap(
            SealDl3BootstrapProfile.exactFacts,
            { _ -> ObservationValue.Known(rawMainOnly()) },
            { _ ->
                sample++
                if (sample == 2) armed = true
                knownBootstrapIdle()
            },
        )

        assertTrue(result is ColdBootstrapResult.RecoveryRequired)
        val envelope = (store.locked { read() } as StoreRead.Loaded).envelope
        assertTrue(envelope.stopRequested)
        assertEquals(2L, envelope.durableEpoch)
        assertEquals(null, envelope.stableSession)
        assertEquals(OperationPhase.RECOVERING, envelope.transaction!!.phase)
        assertEquals(SealDl3BootstrapProfile.forwardKinds, commands)
    }

    @Test
    fun `Stop before bootstrap transaction blocks opcode 30`() {
        val store = CastSessionStore(MemoryAtomicBytes())
        store.locked {
            initialize("boot-a")
            update { it.copy(effectiveUiVersion = EngineVersion.V2) }
        }
        val commands = mutableListOf<CommandKind>()
        val executor = CastExecutor(
            store,
            CastMutationGateway { request -> commands += request.kind; MutationResult.Observed("unexpected") },
            nowEpochMillis = { 1_000L },
            operationId = {
                store.locked { bumpEpoch { it.copy(stopRequested = true) } }
                UUID.fromString("00000000-0000-0000-0000-000000000128")
            },
            sleeper = CastSleeper { },
        )

        val result = executor.bootstrap(
            SealDl3BootstrapProfile.exactFacts,
            { ObservationValue.Known(rawMainOnly()) },
            { knownBootstrapIdle() },
        )

        assertTrue(result is ColdBootstrapResult.Blocked)
        assertTrue(commands.isEmpty())
        val envelope = (store.locked { read() } as StoreRead.Loaded).envelope
        assertTrue(envelope.stopRequested)
        assertEquals(null, envelope.transaction)
    }

    @Test
    fun `Stop after opcode 16 suppresses opcode 35 and compensation`() {
        val store = CastSessionStore(MemoryAtomicBytes())
        store.locked {
            initialize("boot-a")
            update { it.copy(effectiveUiVersion = EngineVersion.V2) }
        }
        val commands = mutableListOf<CommandKind>()
        val executor = CastExecutor(
            store,
            CastMutationGateway { request ->
                commands += request.kind
                if (request.kind == CommandKind.SEAL_DL3_BOOTSTRAP_16) {
                    store.locked { bumpEpoch { it.copy(stopRequested = true) } }
                }
                MutationResult.Observed("known")
            },
            nowEpochMillis = { 1_000L },
            operationId = { UUID.fromString("00000000-0000-0000-0000-000000000129") },
            sleeper = CastSleeper { },
        )

        val result = executor.bootstrap(
            SealDl3BootstrapProfile.exactFacts,
            { ObservationValue.Known(rawMainOnly()) },
            { knownBootstrapIdle() },
        )

        assertTrue(result is ColdBootstrapResult.RecoveryRequired)
        assertEquals(
            listOf(CommandKind.SEAL_DL3_BOOTSTRAP_30, CommandKind.SEAL_DL3_BOOTSTRAP_16),
            commands,
        )
        val envelope = (store.locked { read() } as StoreRead.Loaded).envelope
        assertTrue(envelope.stopRequested)
        assertEquals(OperationPhase.RECOVERING, envelope.transaction!!.phase)
    }

    private fun rawMainOnly() = RawObservation(
        "Stack id=0 displayId=0 userId=0\n  taskId=1: com.example.launcher/.Main", "Display: mDisplayId=0",
        "Display 0:\n  mDisplayId=0", "10", "1.0\n0.5\n1.0", "none",
    )

    private fun knownBootstrapIdle() = ObservationValue.Known(
        ObservedState(
            ObservedCoarseState.IDLE_CLEAN, "display-2", null, emptySet(), null,
            AcceptedGeometry(CastRect(0, 0, 1920, 720), 180, "android-user-10"),
            mapOf(
                "window_animation_scale" to "1.0",
                "transition_animation_scale" to "0.5",
                "animator_duration_scale" to "1.0",
            ),
            null, "android-user-10", "fission_bg_xdjaVirtualSurface",
        ),
    )

    private fun plan(epoch: Long) = CastPlan(
        CastOperation.CAST, epoch,
        listOf(PlannedStep("start", CommandKind.START_FRESH_NORMAL, "known")),
        "visible", setOf(CastAction.STOP),
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

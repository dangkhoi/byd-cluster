package com.byd.clusternav.modules.clustercast.v2

import java.io.IOException
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Locks the exact regression found 2026-07-28 during a full E2E coverage pass: every ordinary Stop
 * plan carries `SEAL_DL3_COMPENSATE_18`/`SEAL_DL3_COMPENSATE_0` (see `CastPlanner.plan`'s
 * `CastIntentKind.STOP` branch — teardown reuses the same physical opcodes bootstrap rollback does).
 * `CastExecutor`'s bootstrap-replay guard used to match on `forwardKinds + compensationKinds`, so
 * `plan.steps.any { it.commandKind in BOOTSTRAP_COMMANDS }` was true for EVERY Stop plan ever built —
 * `executeLocked` returned null before a transaction was ever created, and Stop never dispatched
 * anything, on any vehicle, in any state. Traced end to end via `ClusterCastActivity.executeStop() ->
 * CastFacade.planStop()/executeAndSettle() -> CastCoordinator.execute() -> CastExecutor.executeLocked()`.
 */
class CastExecutorBootstrapGuardTest {

    @Test
    fun `an ordinary Stop plan carrying the teardown opcodes is not treated as a bootstrap replay`() {
        val executor = executor()
        val stopPlan = CastPlan(
            CastOperation.STOP, 0L,
            listOf(
                PlannedStep("teardown-18", CommandKind.SEAL_DL3_COMPENSATE_18, "known"),
                PlannedStep("teardown-0", CommandKind.SEAL_DL3_COMPENSATE_0, "known"),
            ),
            "IDLE_VERIFIED or durable recovery terminal",
            setOf(CastAction.STOP),
        )
        val result = executor.execute(stopPlan, CastBaseline(), "com.example.maps")
        assertTrue(result is ExecutionResult.AwaitingVerification, "Stop must actually dispatch, got: $result")
    }

    @Test
    fun `a plan smuggling a forward bootstrap activation opcode outside BOOTSTRAP is still blocked`() {
        listOf(
            CommandKind.SEAL_DL3_BOOTSTRAP_30,
            CommandKind.SEAL_DL3_BOOTSTRAP_31,
            CommandKind.SEAL_DL3_BOOTSTRAP_16,
            CommandKind.SEAL_DL3_BOOTSTRAP_35,
        ).forEach { forwardKind ->
            val executor = executor()
            val plan = CastPlan(
                CastOperation.CAST, 0L,
                listOf(PlannedStep("smuggled", forwardKind, "known")),
                "expected",
                setOf(CastAction.STOP),
            )
            val result = executor.execute(plan, CastBaseline(), "com.example.maps")
            assertTrue(result is ExecutionResult.Blocked, "$forwardKind must stay blocked outside bootstrap, got: $result")
        }
    }

    @Test
    fun `a plan whose operation is literally BOOTSTRAP is still blocked from the ordinary execute path`() {
        val executor = executor()
        val plan = CastPlan(
            CastOperation.BOOTSTRAP, 0L,
            listOf(PlannedStep("start", CommandKind.START_FRESH_NORMAL, "known")),
            "expected",
            setOf(CastAction.STOP),
        )
        assertTrue(executor.execute(plan, CastBaseline(), "com.example.maps") is ExecutionResult.Blocked)
    }

    private fun executor(): CastExecutor {
        val bytes = MemoryAtomicBytes()
        val store = CastSessionStore(bytes).also { s ->
            s.locked {
                initialize("boot-a")
                update {
                    it.copy(
                        stableSession = StableCastSession(
                            StableState.IDLE_VERIFIED, EngineVersion.V2, "test", null, "display",
                            CastBaseline(), null, null, null, 1,
                        ),
                    )
                }
            }
        }
        return CastExecutor(
            store,
            CastMutationGateway { MutationResult.Observed("known") },
            operationId = { UUID.fromString("00000000-0000-0000-0000-000000000778") },
        )
    }

    private class MemoryAtomicBytes : AtomicBytes {
        private var bytes: ByteArray? = null
        override fun exists() = bytes != null
        override fun read(): ByteArray = bytes?.copyOf() ?: throw IOException("missing")
        override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
    }
}

package com.byd.clusternav.modules.clustercast.v2

import java.io.IOException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastSwitchExecutionTest {
    @Test
    fun `switch targets incoming placement then durable outgoing return`() {
        val bytes = MemoryAtomicBytes()
        val store = CastSessionStore(bytes)
        store.locked {
            initialize("boot"); update { envelope -> envelope.copy(stableSession = StableCastSession(
                StableState.ACTIVE_VERIFIED,
                EngineVersion.V2,
                "test",
                null,
                "display-2",
                CastBaseline(),
                CastTarget("com.example.old", 1, 2),
                null,
                null,
                1,
            )) }
        }
        val requests = mutableListOf<CastMutationRequest>()
        val executor = CastExecutor(store, CastMutationGateway { request ->
            requests += request
            MutationResult.Observed("ok")
        })
        val plan = CastPlanner.plan(
            CastIntent(CastIntentKind.SWITCH, "com.example.new"),
            PlannerSnapshot(
                ObservationValue.Known(ObservedState(
                    ObservedCoarseState.ACTIVE_SINGLE,
                    "display-2",
                    CastTarget("com.example.old", 1, 2),
                    setOf("com.example.old"),
                    null,
                    null,
                )),
                store.locked { (read() as StoreRead.Loaded).envelope.stableSession },
                TargetClass.NORMAL,
                true,
                true,
                0,
            ),
        ) as PlanResult.Ready

        assertTrue(executor.execute(plan.plan, CastBaseline(), "com.example.new") is ExecutionResult.AwaitingVerification)
        assertEquals(
            List(ExpectedLadder.normal.size) { "com.example.new" } + "com.example.old",
            requests.map { it.targetPackage },
        )
        assertEquals(TargetClass.NORMAL.name, (store.locked { read() } as StoreRead.Loaded).envelope.transaction!!.targetClass)
    }

    @Test
    fun `display geometry failure compensates exact prior geometry on exact stable target`() {
        val bytes = MemoryAtomicBytes()
        val store = CastSessionStore(bytes)
        val target = CastTarget("com.example.maps", 17, 2)
        val prior = AcceptedGeometry(CastRect(0, 0, 1920, 720), 160, "android-user-0")
        val requested = AcceptedGeometry(CastRect(100, 20, 1820, 700), 180, "android-user-0")
        store.locked {
            initialize("boot")
            update { envelope -> envelope.copy(stableSession = StableCastSession(
                StableState.ACTIVE_VERIFIED, EngineVersion.V2, "test", null, "display-2",
                CastBaseline(geometry = prior), target, null, prior, 1,
            )) }
        }
        val requests = mutableListOf<CastMutationRequest>()
        val executor = CastExecutor(store, CastMutationGateway { request ->
            requests += request
            if (request.kind == CommandKind.APPLY_DISPLAY_GEOMETRY && requests.size == 2) {
                MutationResult.Rejected("density command rejected")
            } else MutationResult.Observed("ok")
        })
        val plan = CastPlan(
            CastOperation.APPLY_GEOMETRY, 0,
            listOf(
                PlannedStep("task", CommandKind.APPLY_TASK_GEOMETRY, "exact target"),
                PlannedStep("display", CommandKind.APPLY_DISPLAY_GEOMETRY, "single occupant"),
            ),
            "exact geometry", setOf(CastAction.STOP), TargetClass.NORMAL, "display-2", target, requested,
        )
        val failed = executor.execute(plan, CastBaseline(geometry = prior), target.packageName)
            as ExecutionResult.RecoveryRequired
        assertTrue(executor.compensate(failed.operationId) is ExecutionResult.RecoveryRequired)
        val compensation = requests.last()
        assertEquals(CommandKind.APPLY_TASK_GEOMETRY, compensation.kind)
        assertEquals(target, compensation.expectedTarget)
        assertEquals(prior, compensation.geometry)
    }

    private class MemoryAtomicBytes : AtomicBytes {
        private var bytes: ByteArray? = null
        override fun exists() = bytes != null
        override fun read(): ByteArray = bytes?.copyOf() ?: throw IOException("missing")
        override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
    }
}

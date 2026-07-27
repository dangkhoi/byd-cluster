package com.byd.clusternav.modules.clustercast.v2

import java.io.IOException
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastColdBootstrapTest {
    @Test
    fun `profile gate accepts only exact Seal DL3 facts`() {
        assertTrue(SealDl3BootstrapProfile.eligible(facts()))
        listOf(
            facts().copy(sdk = 28), facts().copy(manufacturer = "BYD"), facts().copy(brand = "BYD"),
            facts().copy(product = "DiLink5.0"), facts().copy(device = "generic"),
            facts().copy(buildProduct = "DiLink3"),
        ).forEach { assertFalse(SealDl3BootstrapProfile.eligible(it), it.toString()) }
    }

    @Test
    fun `an existing clean cluster display is adopted with zero seal commands`() {
        val fixture = fixture()
        val calls = mutableListOf<CommandKind>()
        val executor = executor(fixture.store, CastMutationGateway { request -> calls += request.kind; MutationResult.Observed("unexpected") })
        val observations = ArrayDeque(listOf(knownIdle(), knownIdle()))
        val result = executor.bootstrap(
            facts(),
            { _ -> ObservationValue.Known(raw().copy(displays = SEAL_CLUSTER_DUMP)) },
            { _ -> observations.removeFirst() },
        )

        assertTrue(result is ColdBootstrapResult.Succeeded, result.toString())
        assertEquals(emptyList<CommandKind>(), calls)
        val envelope = loaded(fixture.store)
        assertNull(envelope.transaction)
        val stable = envelope.stableSession!!
        assertEquals(StableState.IDLE_VERIFIED, stable.state)
        assertEquals("display-2", stable.expectedDisplayIdentity)
        assertEquals(knownIdle().value.geometry, stable.baseline.geometry)
    }

    @Test
    fun `known failure after an observed forward uses one durable 18 then 0 compensation`() {
        val fixture = fixture()
        val calls = mutableListOf<CommandKind>()
        val gateway = CastMutationGateway { request ->
            calls += request.kind
            when (request.kind) {
                CommandKind.SEAL_DL3_BOOTSTRAP_16 -> MutationResult.Rejected("known reject")
                CommandKind.SEAL_DL3_COMPENSATE_18 -> {
                    val tx = transaction(fixture.store)!!
                    assertTrue(tx.compensationUsed)
                    assertEquals(LedgerEffect.ISSUED, tx.ledger[3].effect)
                    MutationResult.Observed("18 clean")
                }
                else -> MutationResult.Observed("clean")
            }
        }
        val result = executor(fixture.store, gateway).bootstrap(
            facts(), { _ -> ObservationValue.Known(raw()) }, { _ -> knownIdle() },
        ) as ColdBootstrapResult.RecoveryRequired
        assertTrue(result.effectKnown)
        assertTrue(result.compensationAttempted)
        assertEquals(
            listOf(CommandKind.SEAL_DL3_BOOTSTRAP_30, CommandKind.SEAL_DL3_BOOTSTRAP_16,
                CommandKind.SEAL_DL3_COMPENSATE_18, CommandKind.SEAL_DL3_COMPENSATE_0),
            calls,
        )
        val tx = transaction(fixture.store)!!
        assertEquals(OperationPhase.RECOVERING, tx.phase)
        assertTrue(tx.compensationUsed)
        assertEquals(listOf(LedgerEffect.OBSERVED, LedgerEffect.REJECTED, LedgerEffect.PLANNED,
            LedgerEffect.OBSERVED, LedgerEffect.OBSERVED), tx.ledger.map { it.effect })
    }

    @Test
    fun `unknown effect stops immediately without next opcode or compensation`() {
        val fixture = fixture()
        val calls = mutableListOf<CommandKind>()
        val gateway = CastMutationGateway { request ->
            calls += request.kind
            if (request.kind == CommandKind.SEAL_DL3_BOOTSTRAP_16) MutationResult.UnknownEffect("lost")
            else MutationResult.Observed("clean")
        }
        val result = executor(fixture.store, gateway).bootstrap(
            facts(), { _ -> ObservationValue.Known(raw()) }, { _ -> knownIdle() },
        ) as ColdBootstrapResult.RecoveryRequired
        assertFalse(result.effectKnown)
        assertFalse(result.compensationAttempted)
        assertEquals(listOf(CommandKind.SEAL_DL3_BOOTSTRAP_30, CommandKind.SEAL_DL3_BOOTSTRAP_16), calls)
        assertFalse(transaction(fixture.store)!!.compensationUsed)
    }

    @Test
    fun `failed or unknown compensation 18 never issues 0`() {
        listOf<MutationResult>(MutationResult.Rejected("18 failed"), MutationResult.UnknownEffect("18 unknown")).forEach { failure ->
            val fixture = fixture()
            val calls = mutableListOf<CommandKind>()
            val gateway = CastMutationGateway { request ->
                calls += request.kind
                when (request.kind) {
                    CommandKind.SEAL_DL3_BOOTSTRAP_16 -> MutationResult.Rejected("known")
                    CommandKind.SEAL_DL3_COMPENSATE_18 -> failure
                    else -> MutationResult.Observed("clean")
                }
            }
            executor(fixture.store, gateway).bootstrap(facts(), { _ -> ObservationValue.Known(raw()) }, { _ -> knownIdle() })
            assertEquals(CommandKind.SEAL_DL3_COMPENSATE_18, calls.last())
            assertFalse(calls.contains(CommandKind.SEAL_DL3_COMPENSATE_0))
            assertTrue(transaction(fixture.store)!!.compensationUsed)
        }
    }

    @Test
    fun `verification compensates only when bounded observations keep effects known`() {
        val knownFixture = fixture()
        val knownCalls = mutableListOf<CommandKind>()
        val knownExecutor = executor(knownFixture.store, CastMutationGateway { request ->
            knownCalls += request.kind; MutationResult.Observed("clean")
        }, verificationAttempts = 2)
        val invalid = ObservationValue.Known(knownIdle().value.copy(displayName = "ordinary"))
        val knownResult = knownExecutor.bootstrap(facts(), { _ -> ObservationValue.Known(raw()) }, { _ -> invalid })
            as ColdBootstrapResult.RecoveryRequired
        assertTrue(knownResult.effectKnown)
        assertEquals(SealDl3BootstrapProfile.forwardKinds + SealDl3BootstrapProfile.compensationKinds, knownCalls)

        val unknownFixture = fixture()
        val unknownCalls = mutableListOf<CommandKind>()
        val unknownExecutor = executor(unknownFixture.store, CastMutationGateway { request ->
            unknownCalls += request.kind; MutationResult.Observed("clean")
        }, verificationAttempts = 2)
        val unknownResult = unknownExecutor.bootstrap(
            facts(), { _ -> ObservationValue.Known(raw()) }, { _ -> ObservationValue.Unknown("partial") },
        ) as ColdBootstrapResult.RecoveryRequired
        assertFalse(unknownResult.effectKnown)
        assertEquals(SealDl3BootstrapProfile.forwardKinds, unknownCalls)
    }

    @Test
    fun `transaction creation rechecks the complete pristine envelope after operation id allocation`() {
        val fixture = fixture()
        var mutations = 0
        val executor = CastExecutor(
            fixture.store,
            CastMutationGateway { mutations++; MutationResult.Observed("unexpected") },
            nowEpochMillis = { 1_000L },
            operationId = {
                fixture.store.locked { update { it.copy(pendingIntent = PendingCastIntent("com.example.pending")) } }
                operationId()
            },
            sleeper = CastSleeper { },
        )

        val result = executor.bootstrap(facts(), { _ -> ObservationValue.Known(raw()) }, { _ -> knownIdle() })

        assertTrue(result is ColdBootstrapResult.Blocked)
        assertEquals(0, mutations)
        assertEquals(0, loaded(fixture.store).durableEpoch)
        assertEquals("com.example.pending", loaded(fixture.store).pendingPackage)
        assertNull(transaction(fixture.store))
    }

    @Test
    fun `absolute transaction deadline bounds aggregate verification observation`() {
        val fixture = fixture()
        var now = 1_000L
        val observedDeadlines = mutableListOf<Long>()
        val calls = mutableListOf<CommandKind>()
        val executor = CastExecutor(
            fixture.store,
            CastMutationGateway { request -> calls += request.kind; MutationResult.Observed("clean") },
            nowEpochMillis = { now },
            operationId = { operationId() },
            bootstrapTimeoutMillis = 8_500L,
            bootstrapVerificationAttempts = 2,
            bootstrapVerificationPollMillis = 25L,
            sleeper = CastSleeper { now += it },
        )
        val result = executor.bootstrap(
            facts(),
            { deadline -> assertEquals(9_500L, deadline); ObservationValue.Known(raw()) },
            { deadline -> observedDeadlines += deadline; now = deadline + 1; knownIdle() },
        ) as ColdBootstrapResult.RecoveryRequired

        assertFalse(result.effectKnown)
        assertFalse(result.compensationAttempted)
        assertEquals(listOf(9_500L), observedDeadlines)
        assertEquals(SealDl3BootstrapProfile.forwardKinds, calls)
    }

    @Test
    fun `Stop fence after compensation 18 prevents opcode 0`() {
        val fixture = fixture()
        val calls = mutableListOf<CommandKind>()
        val gateway = CastMutationGateway { request ->
            calls += request.kind
            when (request.kind) {
                CommandKind.SEAL_DL3_BOOTSTRAP_16 -> MutationResult.Rejected("known failure")
                CommandKind.SEAL_DL3_COMPENSATE_18 -> {
                    fixture.store.locked { bumpEpoch { it.copy(stopRequested = true) } }
                    MutationResult.Observed("18 clean")
                }
                else -> MutationResult.Observed("clean")
            }
        }

        val result = executor(fixture.store, gateway).bootstrap(
            facts(), { _ -> ObservationValue.Known(raw()) }, { _ -> knownIdle() },
        ) as ColdBootstrapResult.RecoveryRequired

        assertTrue(result.compensationAttempted)
        assertEquals(
            listOf(
                CommandKind.SEAL_DL3_BOOTSTRAP_30,
                CommandKind.SEAL_DL3_BOOTSTRAP_16,
                CommandKind.SEAL_DL3_COMPENSATE_18,
            ),
            calls,
        )
        assertEquals(LedgerEffect.PLANNED, transaction(fixture.store)!!.ledger[4].effect)
    }

    @Test
    fun `verification measures the cluster instead of assuming one hardcoded size`() {
        val valid = knownIdle().value
        assertTrue(CastColdBootstrapVerification.accepts(valid))
        // A different vehicle geometry must still verify: only measurement sanity is required.
        listOf(
            valid.copy(geometry = valid.geometry!!.copy(bounds = CastRect(0, 0, 1280, 720))),
            valid.copy(geometry = valid.geometry!!.copy(densityDpi = 200)),
        ).forEach { assertTrue(CastColdBootstrapVerification.accepts(it), it.toString()) }
        listOf(
            valid.copy(displayName = "fission-only"),
            valid.copy(geometry = valid.geometry!!.copy(bounds = CastRect(0, 0, 0, 720))),
            valid.copy(geometry = valid.geometry!!.copy(bounds = CastRect(8, 0, 1920, 720))),
            valid.copy(geometry = valid.geometry!!.copy(densityDpi = 0)),
            valid.copy(geometry = valid.geometry!!.copy(densityDpi = null)),
            valid.copy(profile = "android-user-x"),
            valid.copy(animationPerKey = valid.animationPerKey + ("window_animation_scale" to "NaN")),
        ).forEach { assertFalse(CastColdBootstrapVerification.accepts(it), it.toString()) }
    }

    @Test
    fun `coordinator reruns raw preflight and never trusts prior parsed UI state`() {
        val fixture = fixture()
        var rawNow = raw()
        val reader = ObservedStateReader(object : ShellGateway {
            override fun execute(request: ReadOnlyShellRequest): ShellResult {
                val value = when (request.kind) {
                    CommandKind.AM_STACK_LIST -> rawNow.amStacks
                    CommandKind.WM_DISPLAYS -> rawNow.wmDisplays
                    CommandKind.DISPLAY_STATE -> rawNow.displays
                    CommandKind.PROFILE_STATE -> rawNow.profile
                    CommandKind.ANIMATION_STATE -> rawNow.animations
                    CommandKind.APP_OPS_STATE -> rawNow.appOps
                    else -> error("unexpected ${request.kind}")
                }
                return ShellResult.Success(value, "", 1)
            }
            override fun close() = Unit
        }) { ObservationValue.Known(knownIdle().value) }
        assertTrue(reader.read() is ObservationValue.Known)
        rawNow = rawNow.copy(amStacks = "Stack taskId=9 displayId=2 com.example.maps/.Main")
        var mutations = 0
        val executor = executor(fixture.store, CastMutationGateway { mutations++; MutationResult.Observed("bad") })
        val coordinator = CastCoordinator(
            fixture.store, reader, executor, CastRecovery(fixture.store, executor),
            now = { java.time.Instant.ofEpochMilli(1_000L) },
        )
        assertTrue(coordinator.bootstrap(facts()) is ColdBootstrapResult.Blocked)
        assertEquals(0, mutations)
        assertNull(transaction(fixture.store))
    }

    @Test
    fun `ordinary planner remains fenced until matching durable stable state exists`() {
        val idle = knownIdle().value
        val castIntent = CastIntent(CastIntentKind.CAST, "com.example.maps")
        val noStable = PlannerSnapshot(ObservationValue.Known(idle), null, TargetClass.NORMAL, true, true, 0)
        assertTrue(CastPlanner.plan(castIntent, noStable) is PlanResult.Blocked)
        val idleStable = idleSession()
        assertTrue(CastPlanner.plan(castIntent, noStable.copy(stableSession = idleStable)) is PlanResult.Ready)
        val activeObservation = idle.copy(
            coarseState = ObservedCoarseState.ACTIVE_SINGLE,
            target = CastTarget("com.example.old", 7, 2),
            occupants = setOf("com.example.old"),
        )
        val switch = CastIntent(CastIntentKind.SWITCH, "com.example.new")
        assertTrue(CastPlanner.plan(switch, noStable.copy(observed = ObservationValue.Known(activeObservation),
            stableSession = idleStable)) is PlanResult.Blocked)
        assertTrue(CastPlanner.plan(switch, noStable.copy(observed = ObservationValue.Known(activeObservation),
            stableSession = idleStable.copy(state = StableState.ACTIVE_VERIFIED,
                activeTarget = activeObservation.target))) is PlanResult.Ready)
    }

    @Test
    fun `generic executor cannot bypass bootstrap or durable stable session gate`() {
        val fixture = fixture()
        var calls = 0
        val executor = executor(fixture.store, CastMutationGateway { calls++; MutationResult.Observed("bad") })
        val ordinary = CastPlan(
            CastOperation.CAST, 0,
            listOf(PlannedStep("start", CommandKind.START_FRESH_NORMAL, "known")),
            "visible", setOf(CastAction.STOP),
        )
        assertTrue(executor.execute(ordinary, CastBaseline(), "com.example.maps") is ExecutionResult.Blocked)
        fixture.store.locked { update { it.copy(stableSession = idleSession()) } }
        val bypass = CastPlan(
            CastOperation.BOOTSTRAP, 0,
            listOf(PlannedStep("raw", CommandKind.SEAL_DL3_BOOTSTRAP_30, "fixed")),
            "invalid bypass", emptySet(),
        )
        assertTrue(executor.execute(bypass, CastBaseline(), null) is ExecutionResult.Blocked)
        assertEquals(0, calls)
    }

    @Test
    fun `restart may finalize VERIFYING bootstrap read only but never replays ledger`() {
        val fixture = fixture()
        val id = operationId()
        fixture.store.locked { bumpEpoch { envelope -> envelope.copy(transaction = CastTransaction(
            id, envelope.durableEpoch, CastOperation.BOOTSTRAP, OperationPhase.VERIFYING,
            null, null, null, "bootstrap-pending", CastBaseline(),
            (SealDl3BootstrapProfile.forwardKinds + SealDl3BootstrapProfile.compensationKinds).mapIndexed { index, kind ->
                LedgerStep("s$index", "fixed", kind, index.toLong(), 1,
                    if (index < 3) LedgerEffect.OBSERVED else LedgerEffect.PLANNED, null, false)
            },
            0, 60_000, null, "clean", false,
        )) } }
        var mutations = 0
        val executor = executor(fixture.store, CastMutationGateway { mutations++; MutationResult.Observed("bad") })
        val reader = ObservedStateReader(object : ShellGateway {
            override fun execute(request: ReadOnlyShellRequest) = ShellResult.Success("unused", "", 1)
            override fun close() = Unit
        }) { _ -> knownIdle() }
        val coordinator = CastCoordinator(
            fixture.store, reader, executor, CastRecovery(fixture.store, executor),
            now = { java.time.Instant.ofEpochMilli(1_000L) },
        )
        assertFalse(coordinator.completeVerification(id, knownIdle().value))
        assertTrue(coordinator.completeVerification(id, knownIdle().value))
        assertEquals(0, mutations)
        assertNull(transaction(fixture.store))
        assertEquals(StableState.IDLE_VERIFIED, loaded(fixture.store).stableSession!!.state)
        assertEquals("display-2", loaded(fixture.store).stableSession!!.expectedDisplayIdentity)
    }

    @Test
    fun `expired VERIFYING bootstrap cannot fold a stable session after restart`() {
        val fixture = fixture()
        val id = operationId()
        fixture.store.locked { bumpEpoch { envelope -> envelope.copy(transaction = CastTransaction(
            id, envelope.durableEpoch, CastOperation.BOOTSTRAP, OperationPhase.VERIFYING,
            null, null, null, "bootstrap-pending", CastBaseline(),
            (SealDl3BootstrapProfile.forwardKinds + SealDl3BootstrapProfile.compensationKinds).mapIndexed { index, kind ->
                LedgerStep(
                    "s$index", "fixed", kind, index.toLong(), 1,
                    if (index < 3) LedgerEffect.OBSERVED else LedgerEffect.PLANNED,
                    null, false,
                )
            },
            0, 999L, null, "clean", false,
        )) } }
        var mutations = 0
        val executor = executor(fixture.store, CastMutationGateway { mutations++; MutationResult.Observed("bad") })
        val reader = ObservedStateReader(object : ShellGateway {
            override fun execute(request: ReadOnlyShellRequest) = ShellResult.Success("unused", "", 1)
            override fun close() = Unit
        }) { _ -> knownIdle() }
        val coordinator = CastCoordinator(
            fixture.store, reader, executor, CastRecovery(fixture.store, executor),
            now = { java.time.Instant.ofEpochMilli(1_000L) },
        )

        assertFalse(coordinator.completeVerification(id, knownIdle().value))
        assertEquals(0, mutations)
        assertNull(loaded(fixture.store).stableSession)
        assertEquals(OperationPhase.RECOVERING, transaction(fixture.store)!!.phase)
        assertEquals("verification deadline exceeded", transaction(fixture.store)!!.lastFailure)
    }

    private fun fixture(): Fixture {
        val store = CastSessionStore(MemoryAtomicBytes())
        store.locked {
            initialize("boot")
            update { it.copy(effectiveUiVersion = EngineVersion.V2) }
        }
        return Fixture(store)
    }

    private fun executor(
        store: CastSessionStore,
        gateway: CastMutationGateway,
        sleeps: MutableList<Long> = mutableListOf(),
        verificationAttempts: Int = 3,
    ) = CastExecutor(
        store, gateway, nowEpochMillis = { 1_000L }, operationId = { operationId() },
        bootstrapVerificationAttempts = verificationAttempts, bootstrapVerificationPollMillis = 25L,
        sleeper = CastSleeper { sleeps += it },
    )

    private fun facts() = SealDl3BootstrapProfile.exactFacts
    private fun raw() = RawObservation(
        amStacks = "Stack id=0 bounds=[0,0][1920,1080] displayId=0 userId=0\n" +
            "  taskId=1: com.example.launcher/.Main bounds=[0,0][1920,1080] userId=0",
        wmDisplays = "Display: mDisplayId=0",
        displays = "Display 0:\n  mDisplayId=0\n  DisplayDeviceInfo{Built-in Screen}",
        profile = "10", animations = "1.0\n0.5\n1.0", appOps = "No app ops",
    )
    private fun knownIdle(): ObservationValue.Known<ObservedState> = ObservationValue.Known(
        ObservedState(
            ObservedCoarseState.IDLE_CLEAN, "display-2", null, emptySet(), null,
            AcceptedGeometry(CastRect(0, 0, 1920, 720), 180, "android-user-10"),
            mapOf("window_animation_scale" to "1.0", "transition_animation_scale" to "0.5",
                "animator_duration_scale" to "1.0"), null, "android-user-10", "fission_bg_xdjaVirtualSurface",
        ),
    )
    private fun idleSession() = StableCastSession(
        StableState.IDLE_VERIFIED, EngineVersion.V2, "bootstrap", "seal-dl3-cold-bootstrap-v1",
        "display-2", CastBaseline(geometry = knownIdle().value.geometry), null, null,
        knownIdle().value.geometry, 1,
    )
    private fun pristineEnvelope() = CastSessionEnvelope(
        durableEpoch = 0, bootId = "boot", stopRequested = false, pendingIntent = null,
        effectiveUiVersion = EngineVersion.V2, pendingUiRollback = false,
        stableSession = null, transaction = null,
    )
    private fun loaded(store: CastSessionStore) = (store.locked { read() } as StoreRead.Loaded).envelope
    private fun transaction(store: CastSessionStore) = loaded(store).transaction
    private fun operationId() = UUID.fromString("00000000-0000-0000-0000-00000000b007")
    private data class Fixture(val store: CastSessionStore)

    private class MemoryAtomicBytes : AtomicBytes {
        private var bytes: ByteArray? = null
        override fun exists() = bytes != null
        override fun read(): ByteArray = bytes?.copyOf() ?: throw IOException("missing")
        override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
    }
}

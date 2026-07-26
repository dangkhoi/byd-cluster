package com.byd.clusternav.modules.clustercast.v2

import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastManualIntentTest {
    @Test
    fun `one cold normal intent journals target then reaches active verified`() {
        val fixture = fixture()
        val target = "com.example.maps"
        fixture.states += listOf(idle(), idle(), idle(), active(target), active(target))
        fixture.onMutation = { request ->
            val envelope = fixture.envelope()
            when (request.kind) {
                CommandKind.SEAL_DL3_BOOTSTRAP_30 -> {
                    assertEquals(target, envelope.pendingPackage)
                    assertEquals(CastOperation.BOOTSTRAP, envelope.transaction!!.operation)
                    assertEquals(target, envelope.transaction!!.targetPkg)
                    assertEquals(5, envelope.transaction!!.ledger.size)
                }
                CommandKind.SET_FORCE_RESIZABLE -> {
                    assertEquals(StableState.IDLE_VERIFIED, envelope.stableSession!!.state)
                    assertNull(envelope.pendingPackage)
                    assertEquals(CastOperation.CAST, envelope.transaction!!.operation)
                }
                else -> Unit
            }
            MutationResult.Observed("known")
        }
        var targetReads = 0
        val result = fixture.coordinator.runManualIntent(target, facts(), CastManualTargetReader {
            targetReads++
            normalTarget()
        })

        assertTrue(result is CastManualIntentResult.Succeeded)
        assertEquals(
            SealDl3BootstrapProfile.forwardKinds + ExpectedLadder.normal,
            fixture.commands,
        )
        assertEquals(2, targetReads)
        assertEquals(2L, fixture.envelope().durableEpoch)
        assertEquals(StableState.ACTIVE_VERIFIED, fixture.envelope().stableSession!!.state)
        assertEquals(target, fixture.envelope().stableSession!!.activeTarget!!.packageName)
    }

    @Test
    fun `one cold protected intent resumes without force stop and reaches active verified`() {
        val fixture = fixture()
        val target = "com.example.carplay"
        fixture.states += listOf(idle(), idle(), idle(), active(target), active(target))
        fixture.onMutation = { MutationResult.Observed("known") }

        val result = fixture.coordinator.runManualIntent(target, facts(), CastManualTargetReader {
            protectedTarget()
        })

        assertTrue(result is CastManualIntentResult.Succeeded)
        assertEquals(
            SealDl3BootstrapProfile.forwardKinds + ExpectedLadder.protectedTarget,
            fixture.commands,
        )
        assertFalse(CommandKind.FORCE_STOP_NORMAL in fixture.commands)
    }

    @Test
    fun `existing executor lease spans bootstrap stable boundary and fresh target read`() {
        val fixture = fixture()
        val target = "com.example.maps"
        fixture.states += listOf(idle(), idle(), idle(), active(target), active(target))
        fixture.onMutation = { MutationResult.Observed("known") }
        val atStableBoundary = CountDownLatch(1)
        val releaseBoundary = CountDownLatch(1)
        val firstResult = AtomicReference<CastManualIntentResult>()
        var reads = 0
        val first = thread(name = "manual-first") {
            firstResult.set(fixture.coordinator.runManualIntent(target, facts(), CastManualTargetReader {
                reads++
                if (reads == 2) {
                    atStableBoundary.countDown()
                    assertTrue(releaseBoundary.await(2, TimeUnit.SECONDS))
                }
                normalTarget()
            }))
        }
        assertTrue(atStableBoundary.await(2, TimeUnit.SECONDS))

        val secondDone = CountDownLatch(1)
        val secondResult = AtomicReference<CastManualIntentResult>()
        val second = thread(name = "manual-second") {
            secondResult.set(fixture.coordinator.runManualIntent(
                "com.example.other",
                facts(),
                CastManualTargetReader { normalTarget().copy(installed = false) },
            ))
            secondDone.countDown()
        }
        assertFalse(secondDone.await(50, TimeUnit.MILLISECONDS))
        releaseBoundary.countDown()
        first.join(2_000)
        second.join(2_000)

        assertTrue(firstResult.get() is CastManualIntentResult.Succeeded)
        assertTrue(secondResult.get() is CastManualIntentResult.Blocked)
        assertEquals(
            SealDl3BootstrapProfile.forwardKinds + ExpectedLadder.normal,
            fixture.commands,
        )
    }

    @Test
    fun `target invalid before bootstrap produces zero durable or gateway mutation`() {
        val fixture = fixture()
        fixture.onMutation = { MutationResult.Observed("unexpected") }

        val result = fixture.coordinator.runManualIntent(
            "com.example.missing",
            facts(),
            CastManualTargetReader { normalTarget().copy(installed = false) },
        )

        assertTrue(result is CastManualIntentResult.Blocked)
        assertTrue(fixture.commands.isEmpty())
        assertEquals(0L, fixture.envelope().durableEpoch)
        assertNull(fixture.envelope().pendingPackage)
        assertNull(fixture.envelope().transaction)
        assertNull(fixture.envelope().stableSession)
    }

    @Test
    fun `target removed after bootstrap remains verified idle and is consumed without placement`() {
        val fixture = fixture()
        fixture.states += listOf(idle(), idle())
        fixture.onMutation = { MutationResult.Observed("known") }
        var reads = 0

        val result = fixture.coordinator.runManualIntent(
            "com.example.maps",
            facts(),
            CastManualTargetReader {
                reads++
                normalTarget().copy(installed = reads == 1)
            },
        )

        assertTrue(result is CastManualIntentResult.Blocked)
        assertEquals(SealDl3BootstrapProfile.forwardKinds, fixture.commands)
        assertEquals(StableState.IDLE_VERIFIED, fixture.envelope().stableSession!!.state)
        assertNull(fixture.envelope().pendingPackage)
        assertNull(fixture.envelope().transaction)
    }

    @Test
    fun `selection during bootstrap replaces one durable slot and only latest target is placed`() {
        val fixture = fixture()
        val first = "com.example.first"
        val latest = "com.example.latest"
        fixture.states += listOf(idle(), idle(), idle(), active(latest), active(latest))
        fixture.onMutation = { request ->
            if (request.kind == CommandKind.SEAL_DL3_BOOTSTRAP_30) {
                assertEquals(first, fixture.envelope().pendingPackage)
                assertTrue(fixture.coordinator.queueLatestTarget(latest))
                assertEquals(latest, fixture.envelope().pendingPackage)
            }
            MutationResult.Observed("known")
        }
        val requestedPackages = mutableListOf<String>()

        val result = fixture.coordinator.runManualIntent(first, facts(), CastManualTargetReader { packageName ->
            requestedPackages += packageName
            normalTarget()
        })

        assertTrue(result is CastManualIntentResult.Succeeded)
        assertEquals(listOf(first, latest), requestedPackages)
        assertEquals(latest, fixture.envelope().stableSession!!.activeTarget!!.packageName)
        assertNull(fixture.envelope().pendingPackage)
    }

    @Test
    fun `Stop at bootstrap stable boundary prevents CAST transaction creation`() {
        val fixture = fixture()
        fixture.states += listOf(idle(), idle())
        fixture.onMutation = { MutationResult.Observed("known") }
        var reads = 0

        val result = fixture.coordinator.runManualIntent(
            "com.example.maps",
            facts(),
            CastManualTargetReader {
                reads++
                if (reads == 2) fixture.coordinator.requestStop()
                normalTarget()
            },
        )

        assertTrue(result is CastManualIntentResult.Blocked)
        assertEquals(SealDl3BootstrapProfile.forwardKinds, fixture.commands)
        assertEquals(StableState.IDLE_VERIFIED, fixture.envelope().stableSession!!.state)
        assertTrue(fixture.envelope().stopRequested)
        assertNull(fixture.envelope().pendingPackage)
        assertNull(fixture.envelope().transaction)
    }

    @Test
    fun `Stop after first bootstrap opcode clears target and suppresses every later opcode`() {
        val fixture = fixture()
        fixture.onMutation = { request ->
            if (request.kind == CommandKind.SEAL_DL3_BOOTSTRAP_30) {
                assertTrue(fixture.coordinator.requestStop()!!.stopRequested)
            }
            MutationResult.Observed("known")
        }

        val result = fixture.coordinator.runManualIntent(
            "com.example.maps",
            facts(),
            CastManualTargetReader { normalTarget() },
        )

        assertTrue(result is CastManualIntentResult.RecoveryRequired)
        assertEquals(listOf(CommandKind.SEAL_DL3_BOOTSTRAP_30), fixture.commands)
        assertTrue(fixture.envelope().stopRequested)
        assertNull(fixture.envelope().pendingPackage)
        assertEquals(OperationPhase.RECOVERING, fixture.envelope().transaction!!.phase)
    }

    @Test
    fun `Stop during ordinary cast suppresses every later placement opcode`() {
        val fixture = fixture(stable = idleSession(), epoch = 4L)
        fixture.states += idle()
        fixture.onMutation = { request ->
            if (request.kind == CommandKind.SET_FORCE_RESIZABLE) fixture.coordinator.requestStop()
            MutationResult.Observed("known")
        }

        val result = fixture.coordinator.runManualIntent(
            "com.example.maps",
            facts(),
            CastManualTargetReader { normalTarget() },
        )

        assertTrue(result is CastManualIntentResult.RecoveryRequired)
        assertEquals(listOf(CommandKind.SET_FORCE_RESIZABLE), fixture.commands)
        assertTrue(fixture.envelope().stopRequested)
        assertEquals(OperationPhase.RECOVERING, fixture.envelope().transaction!!.phase)
    }

    @Test
    fun `warm verified cast bypasses all bootstrap commands`() {
        val fixture = fixture(stable = idleSession(), epoch = 4L)
        val target = "com.example.maps"
        fixture.states += listOf(idle(), active(target), active(target))
        fixture.onMutation = { MutationResult.Observed("known") }

        val result = fixture.coordinator.runManualIntent(target, facts(), CastManualTargetReader { normalTarget() })

        assertTrue(result is CastManualIntentResult.Succeeded)
        assertEquals(ExpectedLadder.normal, fixture.commands)
        assertTrue(fixture.commands.none { it in SealDl3BootstrapProfile.forwardKinds })
    }

    @Test
    fun `warm verified switch bypasses bootstrap and preserves display identity`() {
        val old = "com.example.old"
        val target = "com.example.new"
        val fixture = fixture(stable = activeSession(old), epoch = 4L)
        fixture.states += listOf(active(old), active(target), active(target))
        fixture.onMutation = { MutationResult.Observed("known") }

        val result = fixture.coordinator.runManualIntent(target, facts(), CastManualTargetReader { normalTarget() })

        assertTrue(result is CastManualIntentResult.Succeeded)
        assertEquals(
            ExpectedLadder.normal + CommandKind.RETURN_PROTECTED_GENTLY,
            fixture.commands,
        )
        assertTrue(fixture.commands.none { it in SealDl3BootstrapProfile.forwardKinds })
        assertEquals("display-2", fixture.envelope().stableSession!!.expectedDisplayIdentity)
        assertEquals(target, fixture.envelope().stableSession!!.activeTarget!!.packageName)
    }

    @Test
    fun `read only rehydration continues one pending placement without bootstrap replay`() {
        val target = "com.example.maps"
        val fixture = fixture(stable = idleSession(), pending = target, epoch = 1L)
        fixture.states += listOf(idle(), active(target), active(target))
        fixture.onMutation = { request ->
            if (request.kind == CommandKind.SET_FORCE_RESIZABLE) {
                assertNull(fixture.envelope().pendingPackage)
                assertEquals(CastOperation.CAST, fixture.envelope().transaction!!.operation)
            }
            MutationResult.Observed("known")
        }

        val result = fixture.coordinator.resumePendingIntent(CastManualTargetReader { normalTarget() })

        assertTrue(result is CastManualIntentResult.Succeeded)
        assertEquals(ExpectedLadder.normal, fixture.commands)
        assertNull(fixture.coordinator.resumePendingIntent(CastManualTargetReader { normalTarget() }))
    }

    private fun fixture(
        stable: StableCastSession? = null,
        pending: String? = null,
        epoch: Long = 0L,
    ): Fixture {
        val store = CastSessionStore(MemoryAtomicBytes())
        store.locked {
            initialize("boot")
            update {
                it.copy(
                    durableEpoch = epoch,
                    effectiveUiVersion = EngineVersion.V2,
                    stableSession = stable,
                    pendingIntent = pending?.let(::PendingCastIntent),
                )
            }
        }
        val states = ArrayDeque<ObservedState>()
        val commands = mutableListOf<CommandKind>()
        lateinit var fixture: Fixture
        val gateway = CastMutationGateway { request ->
            commands += request.kind
            fixture.onMutation(request)
        }
        val executor = CastExecutor(
            store,
            gateway,
            nowEpochMillis = { 1_000L },
            operationId = idSequence(),
            bootstrapVerificationPollMillis = 0L,
            sleeper = CastSleeper { },
        )
        val reader = ObservedStateReader(
            RawShell(),
            ObservedStateParser {
                if (states.isEmpty()) ObservationValue.Unknown("no scripted state")
                else ObservationValue.Known(states.removeFirst())
            },
            nowEpochMillis = { 1_000L },
        )
        val coordinator = CastCoordinator(
            store,
            reader,
            executor,
            CastRecovery(store, executor),
            now = { Instant.ofEpochMilli(1_000L) },
            manualSleeper = CastSleeper { },
            manualVerificationDelayMillis = 0L,
        )
        fixture = Fixture(store, coordinator, states, commands)
        return fixture
    }

    private fun normalTarget() = CastManualTargetSnapshot(
        TargetEvidence(projectionComponent = false, connectedPhoneSession = false, userProtected = false),
        installed = true,
        hasLauncher = true,
    )

    private fun protectedTarget() = CastManualTargetSnapshot(
        TargetEvidence(projectionComponent = true, connectedPhoneSession = true, userProtected = false),
        installed = true,
        hasLauncher = true,
    )

    private fun idle() = ObservedState(
        ObservedCoarseState.IDLE_CLEAN,
        "display-2",
        null,
        emptySet(),
        null,
        geometry(),
        animations(),
        null,
        "android-user-10",
        "fission_bg_xdjaVirtualSurface",
    )

    private fun active(packageName: String) = idle().copy(
        coarseState = ObservedCoarseState.ACTIVE_SINGLE,
        target = CastTarget(packageName, 7, 2),
        occupants = setOf(packageName),
    )

    private fun idleSession() = StableCastSession(
        StableState.IDLE_VERIFIED,
        EngineVersion.V2,
        "test",
        "seal-dl3-cold-bootstrap-v1",
        "display-2",
        CastBaseline(geometry = geometry(), animationPerKey = animations(), profile = "android-user-10"),
        null,
        null,
        geometry(),
        1L,
    )

    private fun activeSession(packageName: String) = idleSession().copy(
        state = StableState.ACTIVE_VERIFIED,
        activeTarget = CastTarget(packageName, 7, 2),
    )

    private fun geometry() = AcceptedGeometry(CastRect(0, 0, 1920, 720), 180, "android-user-10")
    private fun animations() = mapOf(
        "window_animation_scale" to "1.0",
        "transition_animation_scale" to "0.5",
        "animator_duration_scale" to "1.0",
    )
    private fun facts() = SealDl3BootstrapProfile.exactFacts

    private fun idSequence(): () -> UUID {
        var next = 1L
        return { UUID(0L, next++) }
    }

    private data class Fixture(
        val store: CastSessionStore,
        val coordinator: CastCoordinator,
        val states: ArrayDeque<ObservedState>,
        val commands: MutableList<CommandKind>,
        var onMutation: (CastMutationRequest) -> MutationResult = { MutationResult.Observed("known") },
    ) {
        fun envelope() = (store.locked { read() } as StoreRead.Loaded).envelope
    }

    private class RawShell : ShellGateway {
        override fun execute(request: ReadOnlyShellRequest): ShellResult {
            val output = when (request.kind) {
                CommandKind.AM_STACK_LIST -> "Stack id=0 displayId=0 userId=0\n  taskId=1: com.example.launcher/.Main"
                CommandKind.WM_DISPLAYS -> "Display: mDisplayId=0"
                CommandKind.DISPLAY_STATE -> "Display 0:\n  mDisplayId=0"
                CommandKind.PROFILE_STATE -> "10"
                CommandKind.ANIMATION_STATE -> "1.0\n0.5\n1.0"
                CommandKind.APP_OPS_STATE -> "No app ops"
                else -> error("Unexpected read ${request.kind}")
            }
            return ShellResult.Success(output, "", 1L)
        }
        override fun close() = Unit
    }

    private class MemoryAtomicBytes : AtomicBytes {
        private var bytes: ByteArray? = null
        override fun exists() = bytes != null
        override fun read(): ByteArray = bytes?.copyOf() ?: throw IOException("missing")
        override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
    }
}

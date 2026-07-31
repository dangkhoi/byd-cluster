package com.byd.clusternav.cast.transport

import com.byd.clusternav.modules.clustercast.v2.AcceptedGeometry
import com.byd.clusternav.modules.clustercast.v2.AtomicBytes
import com.byd.clusternav.modules.clustercast.v2.CastBaseline
import com.byd.clusternav.modules.clustercast.v2.CastCoordinator
import com.byd.clusternav.modules.clustercast.v2.CastExecutor
import com.byd.clusternav.modules.clustercast.v2.CastMutationGateway
import com.byd.clusternav.modules.clustercast.v2.CastMutationRequest
import com.byd.clusternav.modules.clustercast.v2.CastRecovery
import com.byd.clusternav.modules.clustercast.v2.CastRect
import com.byd.clusternav.modules.clustercast.v2.CastSessionEnvelope
import com.byd.clusternav.modules.clustercast.v2.CastSessionStore
import com.byd.clusternav.modules.clustercast.v2.CastSleeper
import com.byd.clusternav.modules.clustercast.v2.CastTarget
import com.byd.clusternav.modules.clustercast.v2.CommandKind
import com.byd.clusternav.modules.clustercast.v2.EngineVersion
import com.byd.clusternav.modules.clustercast.v2.MutationResult
import com.byd.clusternav.modules.clustercast.v2.NamedClusterDisplay
import com.byd.clusternav.modules.clustercast.v2.ObservationValue
import com.byd.clusternav.modules.clustercast.v2.ObservedCoarseState
import com.byd.clusternav.modules.clustercast.v2.ObservedState
import com.byd.clusternav.modules.clustercast.v2.ObservedStateParser
import com.byd.clusternav.modules.clustercast.v2.ObservedStateReader
import com.byd.clusternav.modules.clustercast.v2.PendingCastIntent
import com.byd.clusternav.modules.clustercast.v2.ProtectedResidue
import com.byd.clusternav.modules.clustercast.v2.ReadOnlyShellRequest
import com.byd.clusternav.modules.clustercast.v2.ShellGateway
import com.byd.clusternav.modules.clustercast.v2.ShellResult
import com.byd.clusternav.modules.clustercast.v2.StableCastSession
import com.byd.clusternav.modules.clustercast.v2.StableState
import com.byd.clusternav.modules.clustercast.v2.StoreRead
import com.byd.clusternav.modules.clustercast.v2.TargetEvidence
import com.byd.clusternav.modules.clustercast.v2.classifyMutationShellResult
import dadb.AdbShellResponse
import dadb.AdbShellStream
import dadb.AdbStream
import dadb.AdbSyncStream
import dadb.Dadb
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import okio.Sink
import okio.Source

/**
 * Off-device E2E command-log harness for Cluster Cast V2.
 *
 * WHY THIS EXISTS: [com.byd.clusternav.modules.clustercast.v2.CastManualIntentTest] (in `:core`) already
 * proves the exact [CommandKind] SEQUENCE dispatched for a manual intent, using a gateway lambda that
 * never actually encodes a shell string. That is as far as `:core` can go by construction — it has no
 * `dadb` on its classpath (`CoreIsolationTest` enforces that), and the placement ladder's real string
 * templates live in [CastPlacementCommands], which needs a `dadb.Dadb` to discover the cluster display,
 * resolve a launch component, and check whether a task already landed. This harness is the parallel
 * extension the ground rules ask for: same fixture/onMutation shape, one layer lower, so it can capture
 * the pair (CommandKind, fully-encoded shell string) using the REAL production encoder instead of a
 * hand-written string.
 *
 * CALL BOUNDARY: every scenario drives [CastCoordinator] directly (`runManualIntent`, `requestStop`,
 * `plan`/`execute`) rather than [com.byd.clusternav.modules.clustercast.CastFacade]. `CastFacade` cannot
 * run in an off-device JVM test — it takes an `android.content.Context` to build `CastAppCatalog`/
 * `CastAndroidRuntime` — but every one of its methods used here (`runManualIntent`, `requestStop`,
 * `planStop`+`executeAndSettle`) is a one-line pass-through to the exact `CastCoordinator` call this
 * harness makes; see CastFacade.kt. This is the same boundary `CastManualIntentTest`'s own fixture already
 * documents itself as standing in for.
 *
 * NOT MODELLED (say so loudly, do not let it look more complete than it is): the mutation gateway here
 * really runs [CastPlacementCommands.of] against a scripted [FakeDadb], so the recorded shell string for
 * every mutating [CommandKind] is the production string. The READ side (`ObservedStateReader`) is the
 * same shortcut `CastManualIntentTest` already uses — a scripted [ObservedState] queue, bypassing the
 * real dumpsys/am-stack-list parser — because the parser side already has its own fixture-backed tests
 * (`RealFixtureParsingTest`) and re-deriving realistic raw dumps for every verification poll is a second,
 * separable harness, not part of this foundation.
 */

/**
 * Minimal in-memory stand-in for [Dadb]. Only [shell] is meaningful: it is the sole entry point
 * [CastPlacementCommands.of] and its internal discovery helpers (`am stack list`, `dumpsys display`,
 * `cmd package resolve-activity`) call. Every other member throws — a scenario needing one of them is a
 * sign this fake has grown past what it was built to prove.
 */
class FakeDadb(private val world: CastVehicleWorld) : Dadb {
    override fun shell(command: String): AdbShellResponse = when {
        command == "am stack list" -> AdbShellResponse(world.amStackList, "", 0)
        command == "dumpsys display" -> AdbShellResponse(world.dumpsysDisplay, "", 0)
        command.startsWith("cmd package resolve-activity") -> {
            val pkg = command.substringAfterLast(' ')
            val component = world.resolveActivity(pkg)
            AdbShellResponse(component?.let { "$it\n" } ?: "", "", 0)
        }
        // A launch is the ONE mutating command whose stderr the vehicle really does write into
        // (measured on DiLink3 2026-07-30), so it is the only one this fake can speak back on. Default
        // is still the silent success every other scenario already assumes.
        command.startsWith("am start") -> AdbShellResponse("", world.launchStderr, 0)
        // Every other mutating command (am task resize / settings put / appops set /
        // am display move-stack / service call …) has no modelled device-side effect: the harness's job
        // is to record the exact string CastPlacementCommands.of built, not to simulate the OS.
        else -> AdbShellResponse("", "", 0)
    }

    override fun open(destination: String): AdbStream = unsupported()
    override fun supportsFeature(feature: String): Boolean = false
    override fun openShell(command: String): AdbShellStream = unsupported()
    override fun push(src: File, remotePath: String, mode: Int, lastModifiedMs: Long): Unit = unsupported()
    override fun push(src: Source, remotePath: String, mode: Int, lastModifiedMs: Long): Unit = unsupported()
    override fun pull(dst: File, remotePath: String): Unit = unsupported()
    override fun pull(dst: Sink, remotePath: String): Unit = unsupported()
    override fun openSync(): AdbSyncStream = unsupported()
    override fun install(file: File, vararg options: String): Unit = unsupported()
    override fun install(source: Source, sourceSize: Long, vararg options: String): Unit = unsupported()
    override fun installMultiple(files: List<File>, vararg options: String): Unit = unsupported()
    override fun uninstall(packageName: String): Unit = unsupported()
    override fun execCmd(vararg args: String): AdbStream = unsupported()
    override fun abbExec(vararg args: String): AdbStream = unsupported()
    override fun root(): Unit = unsupported()
    override fun unroot(): Unit = unsupported()
    override fun tcpForward(hostPort: Int, targetPort: Int): AutoCloseable = unsupported()
    override fun close() = Unit

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("FakeDadb only models shell(); the harness never needs this call")
}

/**
 * The only state a scenario needs to steer [CastPlacementCommands.of]'s discovery reads. Mutate the
 * fields from a scenario's `onDispatched` hook to make a step "land" partway through a ladder — exactly
 * the way `am stack list` on the real vehicle changes once a launch actually took, and the reason later
 * escalation rungs no-op.
 */
class CastVehicleWorld(
    var amStackList: String = "Stack id=0 displayId=0\n  taskId=1: com.example.launcher/.Main",
    var dumpsysDisplay: String = SEAL_DL3_DISPLAY_TOPOLOGY,
    var resolveActivity: (String) -> String? = { pkg -> "$pkg/.MainActivity" },
    /**
     * What the ROM writes on stderr for an `am start` (empty = the silent success every other scenario
     * assumes). Exists because the real vehicle is NOT silent here: bringing an app that already has a
     * task onto the cluster makes `am start` print one benign warning line on stderr, and treating that
     * as a failed effect is what wedged a successful cast in RECOVERING on DiLink3 2026-07-30
     * (docs/diagnostics/cast-stop-recovering-stuck-2026-07-30.md). See [CastBenignLaunchStderrTest].
     */
    var launchStderr: String = "",
) {
    /** Rewrites [amStackList] so [packageName] now shows landed on [displayId] as [taskId]. */
    fun land(packageName: String, displayId: Int, taskId: Int = 42) {
        amStackList = "Stack id=0 displayId=0\n" +
            "  taskId=1: com.example.launcher/.Main\n" +
            "Stack id=5 displayId=$displayId\n" +
            "  taskId=$taskId: $packageName/.MainActivity"
    }
}

/** Field-verified Seal DL3 topology: main, launcher-split and the cluster VD. Same shape used across the
 *  suite (`ExpectedLadder.SEAL_CLUSTER_DUMP` in `:core`'s test tree) — duplicated here because `:core`'s
 *  test source set is not visible from `:car-integration`'s. Used by [CastVehicleWorld] (the mutation
 *  side's `dumpsys display`, queried by the placement ladder AFTER the VD has been created). */
const val SEAL_DL3_DISPLAY_TOPOLOGY = """Display 0:
  mDisplayId=0
  mBaseDisplayInfo=DisplayInfo{"Built-in Screen, displayId 0", app 1920 x 1080, density 240}
Display 1:
  mDisplayId=1
  mBaseDisplayInfo=DisplayInfo{"launcher-split, displayId 1", app 1284 x 1080, density 240}
Display 2:
  mDisplayId=2
  mBaseDisplayInfo=DisplayInfo{"fission_bg_xdjaVirtualSurface, displayId 2", app 1920 x 720, density 180}"""

/**
 * Same vehicle, BEFORE Cluster Cast has ever created its virtual display — no Display 2 block at all.
 * This is what [ScriptedReadOnlyShell] hands to [CastColdBootstrap]'s ONE-TIME raw preflight read
 * (`inspectRaw`, before any mutation is dispatched). Getting this wrong is not cosmetic:
 * `CastColdBootstrap.run()` computes `adopt = discoverClusterDisplayId(raw.displays) != null` — if the
 * scripted preflight dump already showed the cluster VD (as [SEAL_DL3_DISPLAY_TOPOLOGY] does), bootstrap
 * decides there is nothing to create, sets `adopt=true`, and skips dispatching the SEAL_DL3_BOOTSTRAP_30/
 * 16/35 opcodes entirely — a genuine off-by-one this harness caught on its first run (see the KDoc above
 * [ScriptedReadOnlyShell]). A cold-bootstrap scenario must use THIS topology for the preflight read.
 */
const val PRE_BOOTSTRAP_DISPLAY_TOPOLOGY = """Display 0:
  mDisplayId=0
  mBaseDisplayInfo=DisplayInfo{"Built-in Screen, displayId 0", app 1920 x 1080, density 240}
Display 1:
  mDisplayId=1
  mBaseDisplayInfo=DisplayInfo{"launcher-split, displayId 1", app 1284 x 1080, density 240}"""

/** One dispatched mutation: its [CommandKind] paired with the exact string sent to the vehicle. `shell`
 * is null only when the gateway refused to dispatch at all (unsupported/unresolved/fenced). */
data class DispatchedCommand(val kind: CommandKind, val shell: String?)

/**
 * [CastMutationGateway] that dispatches through the REAL [CastPlacementCommands] encoder against a
 * [FakeDadb], recording (kind, shell) for every call, then classifies the result exactly like
 * [CastAdbGateway] does in production — only swapping the live transport for the fake.
 */
class RecordingMutationGateway(
    private val dadb: FakeDadb,
    private val isDisplayClean: (Int) -> Boolean = { false },
    private val measuredCluster: () -> NamedClusterDisplay? = { null },
    private val onDispatched: (CastMutationRequest, String) -> Unit = { _, _ -> },
) : CastMutationGateway {
    private val fenceToken = AtomicLong(0)
    val log: MutableList<DispatchedCommand> = mutableListOf()

    override fun execute(request: CastMutationRequest): MutationResult {
        if (request.fenceToken != fenceToken.get()) return MutationResult.Rejected("mutation fenced before dispatch")
        val command = CastPlacementCommands.of(
            dadb, request, cancelled = { false },
            isDisplayClean = { id, _ -> isDisplayClean(id) },
            measuredCluster = measuredCluster,
        )
        if (command == null) {
            log += DispatchedCommand(request.kind, null)
            return MutationResult.Rejected("unsupported, unresolved, or fenced mutation ${request.kind}")
        }
        log += DispatchedCommand(request.kind, command)
        onDispatched(request, command)
        val result = dadb.shell(command)
        return classifyMutationShellResult(result.exitCode, result.output, result.errorOutput)
    }

    override fun fenceInFlight() { fenceToken.incrementAndGet() }
    override fun currentFenceToken(): Long = fenceToken.get()
}

/** In-memory [AtomicBytes]; the store never touches a real file in this harness. */
class MemoryAtomicBytes : AtomicBytes {
    private var bytes: ByteArray? = null
    override fun exists() = bytes != null
    override fun read(): ByteArray = bytes?.copyOf() ?: throw IOException("missing")
    override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
}

/**
 * Read-only [ShellGateway] for two DIFFERENT consumers that must not be confused:
 *  1. [com.byd.clusternav.modules.clustercast.v2.ObservedStateReader]'s `read()` — its
 *     [ObservedStateParser] (below) bypasses real parsing and returns a scripted [ObservedState] queue
 *     directly, exactly like `CastManualIntentTest`'s own `RawShell`, so THIS gateway's output content is
 *     irrelevant there (only non-blank matters, so `inspectRaw` never short-circuits on `Unknown`).
 *  2. [CastColdBootstrap]'s ONE-TIME `inspectRaw` preflight read, which DOES parse [request]'s
 *     `DISPLAY_STATE` output for real (see [PRE_BOOTSTRAP_DISPLAY_TOPOLOGY]'s KDoc) — this is the one
 *     case where the content genuinely matters, which is why [displayState] is a constructor parameter
 *     instead of a hardcoded literal.
 */
private class ScriptedReadOnlyShell(
    private val displayState: String = PRE_BOOTSTRAP_DISPLAY_TOPOLOGY,
) : ShellGateway {
    override fun execute(request: ReadOnlyShellRequest): ShellResult = when (request.kind) {
        CommandKind.AM_STACK_LIST -> ShellResult.Success("Stack id=0 displayId=0\n  taskId=1: com.example.launcher/.Main", "", 1L)
        CommandKind.WM_DISPLAYS -> ShellResult.Success("Display: mDisplayId=0", "", 1L)
        CommandKind.DISPLAY_STATE -> ShellResult.Success(displayState, "", 1L)
        CommandKind.PROFILE_STATE -> ShellResult.Success("10", "", 1L)
        CommandKind.ANIMATION_STATE -> ShellResult.Success("1.0\n0.5\n1.0", "", 1L)
        CommandKind.APP_OPS_STATE -> ShellResult.Success("No app ops", "", 1L)
        else -> error("Unexpected read ${request.kind}")
    }
    override fun close() = Unit
}

/**
 * The reusable fixture handle a scenario test drives. Mirrors `CastManualIntentTest.Fixture` one layer
 * lower: same `states`/`commands` shape, plus [world] (the fake vehicle) and [commandLog] (the diffable
 * text render Coverage-phase scenarios can reuse verbatim).
 */
class CastE2EFixture(
    val store: CastSessionStore,
    val coordinator: CastCoordinator,
    val world: CastVehicleWorld,
    private val gateway: RecordingMutationGateway,
    private val states: ArrayDeque<ObservedState>,
) {
    /** Appends observed states to the read-side script, consumed FIFO by every `observation.read()`. */
    fun scriptStates(vararg observed: ObservedState) { states += observed }

    fun envelope(): CastSessionEnvelope = (store.locked { read() } as StoreRead.Loaded).envelope

    val commands: List<DispatchedCommand> get() = gateway.log

    /**
     * One line per dispatched mutation: `kind=<CommandKind> shell=<exact string>` (or `shell=<NONE>`
     * when the gateway refused to dispatch). Stable across runs, diffable byte-for-byte against a
     * checked-in golden fixture.
     */
    fun commandLog(): String = gateway.log.joinToString("\n") { "kind=${it.kind} shell=${it.shell ?: "<NONE>"}" }
}

/**
 * Builds a [CastE2EFixture] wired exactly like [CastCoordinator] is wired in production, minus the
 * Android/dadb transport (replaced by [FakeDadb]) and real timers (zero sleeps, deterministic clock).
 */
fun castE2EFixture(
    stable: StableCastSession? = null,
    pending: String? = null,
    epoch: Long = 0L,
    world: CastVehicleWorld = CastVehicleWorld(),
    isDisplayClean: (Int) -> Boolean = { false },
    onDispatched: (CastMutationRequest, String) -> Unit = { _, _ -> },
): CastE2EFixture {
    val store = CastSessionStore(MemoryAtomicBytes())
    store.locked {
        initialize("e2e-harness")
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
    val dadb = FakeDadb(world)
    val gateway = RecordingMutationGateway(dadb, isDisplayClean = isDisplayClean, onDispatched = onDispatched)
    val executor = CastExecutor(
        store, gateway,
        nowEpochMillis = { 1_000L },
        operationId = e2eIdSequence(),
        bootstrapVerificationPollMillis = 0L,
        sleeper = CastSleeper { },
    )
    val reader = ObservedStateReader(
        ScriptedReadOnlyShell(),
        ObservedStateParser {
            if (states.isEmpty()) ObservationValue.Unknown("no scripted state")
            else ObservationValue.Known(states.removeFirst())
        },
        nowEpochMillis = { 1_000L },
    )
    val coordinator = CastCoordinator(
        store, reader, executor, CastRecovery(store, executor),
        now = { Instant.ofEpochMilli(1_000L) },
        manualSleeper = CastSleeper { },
        manualVerificationDelayMillis = 0L,
    )
    return CastE2EFixture(store, coordinator, world, gateway, states)
}

private fun e2eIdSequence(): () -> UUID {
    var next = 1L
    return { UUID(0L, next++) }
}

// ── Shared ObservedState / evidence builders, same values `CastManualIntentTest` already proves work ──

fun e2eGeometry() = AcceptedGeometry(CastRect(0, 0, 1920, 720), 180, "android-user-10")

fun e2eAnimations() = mapOf(
    "window_animation_scale" to "1.0",
    "transition_animation_scale" to "0.5",
    "animator_duration_scale" to "1.0",
)

fun e2eIdle() = ObservedState(
    ObservedCoarseState.IDLE_CLEAN,
    "display-2",
    null,
    emptySet(),
    null,
    e2eGeometry(),
    e2eAnimations(),
    null,
    "android-user-10",
    "fission_bg_xdjaVirtualSurface",
)

fun e2eActive(packageName: String, protectedResidue: ProtectedResidue? = null) = e2eIdle().copy(
    coarseState = ObservedCoarseState.ACTIVE_SINGLE,
    target = CastTarget(packageName, 7, 2),
    occupants = setOf(packageName),
    protectedResidue = protectedResidue,
)

fun e2eIdleSession() = StableCastSession(
    StableState.IDLE_VERIFIED,
    EngineVersion.V2,
    "test",
    "seal-dl3-cold-bootstrap-v1",
    "display-2",
    CastBaseline(geometry = e2eGeometry(), animationPerKey = e2eAnimations(), profile = "android-user-10"),
    null, null, e2eGeometry(), 1L,
)

fun e2eActiveSession(packageName: String) = e2eIdleSession().copy(
    state = StableState.ACTIVE_VERIFIED,
    activeTarget = CastTarget(packageName, 7, 2),
)

fun e2eNormalTargetEvidence() = TargetEvidence(projectionComponent = false, connectedPhoneSession = false, userProtected = false)

fun e2eProtectedTargetEvidence() = TargetEvidence(projectionComponent = true, connectedPhoneSession = true, userProtected = false)

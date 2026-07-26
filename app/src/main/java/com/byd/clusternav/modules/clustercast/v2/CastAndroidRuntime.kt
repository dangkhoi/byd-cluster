package com.byd.clusternav.modules.clustercast.v2

import android.content.Context
import android.os.Build
import com.byd.clusternav.AdbKeys
import dadb.Dadb
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private val DISPLAY_BLOCK_HEADER = Regex("^Display\\s+([^:]+):\\s*$", RegexOption.IGNORE_CASE)
private val DISPLAY_BLOCK_ID = Regex("^mDisplayId\\s*=\\s*([^\\s,}]+)$", RegexOption.IGNORE_CASE)
private val BASE_DISPLAY_INFO = Regex(
    "mBaseDisplayInfo\\s*=\\s*DisplayInfo\\{\\s*\"(.+),\\s*displayId\\s+(\\d+)\"",
    RegexOption.IGNORE_CASE,
)
private val DISPLAY_APP_SIZE = Regex("\\bapp\\s+(\\d+)\\s*x\\s*(\\d+)", RegexOption.IGNORE_CASE)
private val DISPLAY_DENSITY = Regex("\\bdensity\\s+(\\d+)", RegexOption.IGNORE_CASE)
private const val MAX_DISPLAY_DIMENSION = 32_768
private const val MAX_DISPLAY_DENSITY_DPI = 10_000
internal const val MISSING_NAMED_CLUSTER_DISPLAY_REASON = "expected exactly one named cluster display"

internal fun classifyMutationShellResult(exitCode: Int, stdout: String, stderr: String): MutationResult =
    if (exitCode == 0 && stderr.isBlank()) MutationResult.Observed(stdout.take(200))
    else MutationResult.UnknownEffect(
        stderr.takeIf(String::isNotBlank)?.take(200) ?: "shell exit $exitCode after dispatch",
    )

internal data class NamedClusterDisplay(
    val id: Int,
    val name: String,
    val appWidth: Int,
    val appHeight: Int,
    val densityDpi: Int,
)

internal fun discoverClusterDisplay(dumpsysDisplay: String): NamedClusterDisplay? {
    val headerIds = when (val parsed = CastLogicalDisplayParser.parseHeaders(dumpsysDisplay)) {
        is CastDumpParse.Known -> parsed.value
        is CastDumpParse.Malformed -> return null
    }
    if (headerIds.size != headerIds.toSet().size) return null
    val logicalBlocks = mutableListOf<Pair<Int, List<String>>>()
    var currentDisplayId: Int? = null
    var currentBlock = mutableListOf<String>()

    fun finishBlock() {
        currentDisplayId?.let { logicalBlocks += it to currentBlock.toList() }
        currentBlock = mutableListOf()
    }

    dumpsysDisplay.lineSequence().forEach { raw ->
        val line = raw.trim()
        val header = DISPLAY_BLOCK_HEADER.matchEntire(line)
        if (header != null) {
            finishBlock()
            currentDisplayId = header.groupValues[1].trim().toIntOrNull()
            return@forEach
        }
        if (currentDisplayId != null) currentBlock += line
    }
    finishBlock()

    val candidates = ArrayList<NamedClusterDisplay>()
    logicalBlocks.forEach { (id, lines) ->
        val blockIds = lines.mapNotNull { DISPLAY_BLOCK_ID.matchEntire(it)?.groupValues?.get(1) }
        if (blockIds.size != 1 || blockIds.single().toIntOrNull() != id) return null
        if (id <= 0) return@forEach
        val sameBlock = lines.joinToString("\n")
        val baseInfos = BASE_DISPLAY_INFO.findAll(sameBlock).toList()
        if (baseInfos.isEmpty()) {
            if (sameBlock.contains("fission", ignoreCase = true) || sameBlock.contains("xdja", ignoreCase = true)) {
                return null
            }
            return@forEach
        }
        if (baseInfos.size != 1 || baseInfos.single().groupValues[2].toIntOrNull() != id) return null
        val name = baseInfos.single().groupValues[1].trim()
        if (!name.contains("fission", ignoreCase = true) && !name.contains("xdja", ignoreCase = true)) {
            return@forEach
        }
        val size = DISPLAY_APP_SIZE.find(sameBlock)?.groupValues ?: return null
        val width = size[1].toIntOrNull()?.takeIf { it in 1..MAX_DISPLAY_DIMENSION } ?: return null
        val height = size[2].toIntOrNull()?.takeIf { it in 1..MAX_DISPLAY_DIMENSION } ?: return null
        val density = DISPLAY_DENSITY.find(sameBlock)?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in 1..MAX_DISPLAY_DENSITY_DPI } ?: return null
        candidates += NamedClusterDisplay(id, name, width, height, density)
    }
    return candidates.singleOrNull()
}

internal fun discoverClusterDisplayId(dumpsysDisplay: String): Int? =
    discoverClusterDisplay(dumpsysDisplay)?.id

internal fun fixedSealDl3BootstrapCommand(kind: CommandKind): String? = when (kind) {
    CommandKind.SEAL_DL3_BOOTSTRAP_30 -> "service call AutoContainer 2 i32 1000 i32 30 s16 \"\""
    CommandKind.SEAL_DL3_BOOTSTRAP_31 -> "service call AutoContainer 2 i32 1000 i32 31 s16 \"\""
    CommandKind.SEAL_DL3_BOOTSTRAP_16 -> "service call AutoContainer 2 i32 1000 i32 16 s16 \"\""
    CommandKind.SEAL_DL3_BOOTSTRAP_35 -> "service call AutoContainer 2 i32 1000 i32 35 s16 \"\""
    CommandKind.SEAL_DL3_COMPENSATE_18 -> "service call AutoContainer 2 i32 1000 i32 18 s16 \"\""
    CommandKind.SEAL_DL3_COMPENSATE_0 -> "service call AutoContainer 2 i32 1000 i32 0 s16 \"\""
    else -> null
}

/** Android integration factory. Every shell call owns and closes its Cast-only DADB session. */
object CastAndroidRuntime {
    data class Runtime(
        val coordinator: CastCoordinator,
        val store: CastSessionStore,
        val gateway: CastAndroidGateway,
        val adjustment: CastAdjustmentWorkspace,
        val automation: CastAutomationSettings,
        val vehicleFacts: CastVehicleFacts,
    )

    @Volatile private var processRuntime: Runtime? = null

    /** One process-wide control plane: all Activities/services share the same store lock and mutation lease. */
    fun create(context: Context): Runtime = processRuntime ?: synchronized(this) {
        processRuntime ?: newRuntime(context.applicationContext).also { processRuntime = it }
    }

    private fun newRuntime(app: Context): Runtime {
        val store = CastSessionStore(File(app.filesDir, "cast-v2/session.env"))
        val gateway = CastAndroidGateway(app)
        val reader = ObservedStateReader(
            gateway,
            AndroidObservedStateParser.withFallback { CastInProcessDisplay.measure(app) },
        )
        val executor = CastExecutor(store, gateway)
        val recovery = CastRecovery(store, executor)
        return Runtime(
            store = store,
            gateway = gateway,
            coordinator = CastCoordinator(store, reader, executor, recovery),
            adjustment = CastAdjustmentWorkspace(store),
            automation = CastAutomationSettings(store),
            vehicleFacts = CastVehicleFacts(
                Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.BRAND,
                Build.PRODUCT, Build.DEVICE, Build.PRODUCT,
            ),
        )
    }
}


class CastAndroidGateway(private val context: Context) : ShellGateway, CastMutationGateway {
    private val closed = AtomicBoolean(false)
    private val mutationGeneration = AtomicLong(0)
    private val activeSessions = ConcurrentHashMap.newKeySet<Dadb>()
    private val activeTasks = ConcurrentHashMap.newKeySet<Future<*>>()
    private val activeMutationSessions = ConcurrentHashMap.newKeySet<Dadb>()
    private val activeMutationTasks = ConcurrentHashMap.newKeySet<Future<*>>()
    private val io = ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4),
        ThreadFactory { runnable -> Thread(runnable, "cast-v2-dadb").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    override fun execute(request: ReadOnlyShellRequest): ShellResult {
        if (closed.get()) return ShellResult.Closed(0)
        val started = System.currentTimeMillis()
        return withDadbBounded(
            timeoutMillis = request.deadlineMillis,
            success = { adb, cancelled ->
                if (cancelled()) return@withDadbBounded ShellResult.Closed(System.currentTimeMillis() - started)
                val result = adb.shell(request.command)
                val elapsed = System.currentTimeMillis() - started
                if (result.exitCode == 0 && result.errorOutput.isBlank()) {
                    ShellResult.Success(result.output, result.errorOutput, elapsed)
                } else ShellResult.Failure(result.exitCode, result.errorOutput, elapsed)
            },
            timeout = { ShellResult.Timeout(System.currentTimeMillis() - started) },
            failure = { error -> ShellResult.Failure(null, error.message.orEmpty(), System.currentTimeMillis() - started) },
        )
    }

    override fun execute(request: CastMutationRequest): MutationResult {
        if (closed.get()) return MutationResult.UnknownEffect("gateway closed")
        if (!request.kind.mutating) return MutationResult.Rejected("read-only kind on mutation gateway")
        if (request.fenceToken != mutationGeneration.get()) {
            return MutationResult.Rejected("mutation fenced before dispatch")
        }
        val remaining = request.deadlineAtEpochMillis - System.currentTimeMillis()
        if (remaining <= 0) return MutationResult.Rejected("deadline expired")
        return withDadbBounded(
            timeoutMillis = remaining.coerceAtMost(15_000L),
            mutationCall = true,
            expectedMutationGeneration = request.fenceToken,
            absoluteDeadlineAtEpochMillis = request.deadlineAtEpochMillis,
            success = { adb, cancelled ->
                val command = CastPlacementCommands.of(
                    adb, request, cancelled,
                    isDisplayClean = { id, c -> isDisplayClean(adb, id, c) },
                    measuredCluster = { CastInProcessDisplay.measure(context) },
                )
                    ?: return@withDadbBounded MutationResult.Rejected("unsupported, unresolved, or fenced mutation ${request.kind}")
                if (System.currentTimeMillis() >= request.deadlineAtEpochMillis) {
                    return@withDadbBounded MutationResult.Rejected("deadline expired before dispatch")
                }
                if (cancelled()) return@withDadbBounded MutationResult.UnknownEffect("mutation fenced before dispatch")
                CastOperationLog.record("   $ $command")
                val result = adb.shell(command)
                classifyMutationShellResult(result.exitCode, result.output, result.errorOutput)
            },
            timeout = { MutationResult.UnknownEffect("DADB mutation deadline exceeded") },
            failure = { error -> MutationResult.UnknownEffect(error.message ?: error.javaClass.simpleName) },
        )
    }

    fun connectedPhoneSession(packageName: String): Boolean? = when (
        val result = execute(ReadOnlyShellRequest.phoneSession(packageName))
    ) {
        is ShellResult.Success -> ProjectionSessionEvidenceParser.parse(result.stdout)
        is ShellResult.Failure, is ShellResult.Timeout, is ShellResult.Closed -> null
    }

    override fun fenceInFlight() {
        mutationGeneration.incrementAndGet()
        activeMutationTasks.forEach { it.cancel(true) }
        activeMutationSessions.forEach { session -> runCatching { session.close() } }
    }

    override fun currentFenceToken(): Long = mutationGeneration.get()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            mutationGeneration.incrementAndGet()
            activeTasks.forEach { it.cancel(true) }
            activeSessions.forEach { session -> runCatching { session.close() } }
            io.shutdownNow()
        }
    }

    private fun isDisplayClean(adb: Dadb, displayId: Int, cancelled: () -> Boolean): Boolean {
        if (cancelled()) return false
        val result = adb.shell("am stack list")
        if (result.exitCode != 0 || result.errorOutput.isNotBlank() || result.output.isBlank()) return false
        return CastAmStackParser.isKnownCleanDisplay(result.output, displayId)
    }

    private fun <T> withDadbBounded(
        timeoutMillis: Long,
        mutationCall: Boolean = false,
        expectedMutationGeneration: Long? = null,
        absoluteDeadlineAtEpochMillis: Long? = null,
        success: (Dadb, () -> Boolean) -> T,
        timeout: () -> T,
        failure: (Exception) -> T,
    ): T {
        val acceptedMutationGeneration = expectedMutationGeneration ?: mutationGeneration.get()
        val callCancelled = AtomicBoolean(false)
        val callSession = AtomicReference<Dadb?>()
        val cancelled = {
            callCancelled.get() || closed.get() ||
                (absoluteDeadlineAtEpochMillis != null && System.currentTimeMillis() >= absoluteDeadlineAtEpochMillis) ||
                (mutationCall && mutationGeneration.get() != acceptedMutationGeneration)
        }
        val task = try {
            io.submit<T> {
                if (cancelled()) throw IllegalStateException("gateway fenced")
                val adb = Dadb.create("localhost", 5555, AdbKeys.ensure(context))
                if (cancelled()) {
                    adb.close()
                    throw IllegalStateException("DADB call cancelled after connect")
                }
                callSession.set(adb)
                activeSessions += adb
                if (mutationCall) activeMutationSessions += adb
                try {
                    if (cancelled()) throw IllegalStateException("gateway fenced")
                    success(adb, cancelled)
                } finally {
                    activeSessions -= adb
                    activeMutationSessions -= adb
                    if (callSession.compareAndSet(adb, null)) adb.close()
                }
            }
        } catch (rejected: RejectedExecutionException) {
            return failure(rejected)
        }
        activeTasks += task
        if (mutationCall) activeMutationTasks += task
        return try {
            task.get(timeoutMillis.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            callCancelled.set(true)
            task.cancel(true)
            callSession.getAndSet(null)?.let { session ->
                activeSessions -= session
                activeMutationSessions -= session
                runCatching { session.close() }
            }
            timeout()
        } catch (interrupted: InterruptedException) {
            callCancelled.set(true)
            task.cancel(true)
            Thread.currentThread().interrupt()
            failure(interrupted)
        } catch (wrapped: ExecutionException) {
            when (val cause = wrapped.cause) {
                is Exception -> failure(cause)
                is Error -> throw cause
                else -> failure(IllegalStateException("unknown DADB failure", cause))
            }
        } finally {
            activeTasks -= task
            activeMutationTasks -= task
        }
    }

    companion object {
        private val ANDROID_PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        private val COMPONENT = Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")
    }
}

object AndroidObservedStateParser : ObservedStateParser {
    private val BOUNDS = Regex("(?:mBounds|bounds)\\s*=\\s*(?:Rect\\()?\\[?(\\d+)[, ]+(\\d+)\\]?[ ,\\-]+\\[?(\\d+)[, ]+(\\d+)\\]?\\)?", RegexOption.IGNORE_CASE)
    private val DENSITY = Regex("(?:densityDpi|density)\\s*[=: ]\\s*(\\d+)", RegexOption.IGNORE_CASE)
    private val PROJECTION_HINTS = setOf("carplay", "androidauto", "android.auto", "projection")
    private val APP_OPS_PACKAGE = Regex("(?im)^\\s*Package\\s+([A-Za-z][A-Za-z0-9_.]+)\\s*:")
    private val PIP_MODE = Regex(
        "PICTURE_IN_PICTURE\\s*:\\s*(allow|ignore|deny|default|foreground)",
        RegexOption.IGNORE_CASE,
    )

    /** A measured fallback keeps the runtime usable when the dump wording changes. */
    internal fun withFallback(measured: () -> NamedClusterDisplay?): ObservedStateParser =
        ObservedStateParser { raw -> parse(raw, measured) }

    override fun parse(raw: RawObservation): ObservationValue<ObservedState> = parse(raw) { null }

    internal fun parse(
        raw: RawObservation,
        measured: () -> NamedClusterDisplay?,
    ): ObservationValue<ObservedState> {
        val namedDisplay = discoverClusterDisplay(raw.displays)
            ?: measured()?.also { CastOperationLog.record("display identity from DisplayManager fallback: ${it.id}") }
            ?: return ObservationValue.Unknown(MISSING_NAMED_CLUSTER_DISPLAY_REASON)
        val display = namedDisplay.id
        val am = when (val parsed = CastAmStackParser.parse(raw.amStacks)) {
            is CastDumpParse.Known -> parsed.value
            is CastDumpParse.Malformed -> return ObservationValue.Unknown(parsed.reason)
        }
        if (am.stacks.isEmpty()) return ObservationValue.Unknown("AM stack topology is empty")
        val displayTasks = am.tasks.filter { it.displayId == display }
        val occupants = displayTasks.map { it.packageName }.toSet()
        val coarse = when (occupants.size) {
            0 -> ObservedCoarseState.IDLE_CLEAN
            1 -> ObservedCoarseState.ACTIVE_SINGLE
            else -> ObservedCoarseState.ACTIVE_MULTI
        }
        val records = displayTasks.map { Triple(it.packageName, it.taskId, it.visible) }
        val targetRecord = when (records.size) {
            0 -> null
            1 -> records.single()
            else -> records.filter { it.third == true }.singleOrNull()
                ?: return ObservationValue.Unknown("multi-occupant target visibility unavailable")
        }
        val target = targetRecord?.let { CastTarget(it.first, it.second, display) }
        val residueRecords = records.filter { it != targetRecord }.filter { record ->
            PROJECTION_HINTS.any { record.first.contains(it, true) }
        }
        if (residueRecords.size > 1) return ObservationValue.Unknown("multiple protected residues")
        val residue = residueRecords.singleOrNull()?.let {
            ProtectedResidue(
                it.first,
                it.second,
                if (it.third == false) ResidueVisibility.HIDDEN else ResidueVisibility.UNKNOWN,
            )
        }
        val activeUser = raw.profile.trim().toIntOrNull()
            ?: return ObservationValue.Unsupported("active Android profile format unsupported")
        val profileId = "android-user-$activeUser"
        val geometry = if (coarse == ObservedCoarseState.IDLE_CLEAN && target == null) {
            AcceptedGeometry(
                CastRect(0, 0, namedDisplay.appWidth, namedDisplay.appHeight),
                namedDisplay.densityDpi,
                profileId,
            )
        } else {
            val geometryText = raw.wmDisplays + "\n" + raw.displays
            val bounds = BOUNDS.find(geometryText)?.groupValues?.let { groups ->
                val left = groups[1].toIntOrNull()
                val top = groups[2].toIntOrNull()
                val right = groups[3].toIntOrNull()
                val bottom = groups[4].toIntOrNull()
                if (left == null || top == null || right == null || bottom == null) {
                    return ObservationValue.Unknown("display bounds numeric format unsupported")
                }
                CastRect(left, top, right, bottom)
            }
            val density = DENSITY.find(geometryText)?.groupValues?.get(1)?.toIntOrNull()
            bounds?.let { AcceptedGeometry(it, density, profileId) }
        }
        val animationLines = raw.animations.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (animationLines.size < 3) return ObservationValue.Unknown("animation settings incomplete")
        val animations = linkedMapOf(
            "window_animation_scale" to animationLines[0],
            "transition_animation_scale" to animationLines[1],
            "animator_duration_scale" to animationLines[2],
        )
        val pipMode = target?.packageName?.let { packageName ->
            val headers = APP_OPS_PACKAGE.findAll(raw.appOps).toList()
            val index = headers.indexOfFirst { it.groupValues[1].equals(packageName, true) }
            if (index < 0) return ObservationValue.Unknown("target app-ops block unavailable")
            val block = raw.appOps.substring(
                headers[index].range.first,
                headers.getOrNull(index + 1)?.range?.first ?: raw.appOps.length,
            )
            PIP_MODE.find(block)?.groupValues?.get(1)?.lowercase() ?: "default"
        }
        return ObservationValue.Known(
            ObservedState(
                coarse, "display-$display", target, occupants, residue, geometry,
                animations, pipMode, profileId, namedDisplay.name,
            ),
        )
    }
}

object ProjectionSessionEvidenceParser {
    private val BOOLEAN = Regex(
        "(?:connectedPhoneSession|mPhoneConnected|sessionConnected)\\s*[=:]\\s*(true|false)",
        RegexOption.IGNORE_CASE,
    )
    private val STATE = Regex("(?:connectionState|sessionState)\\s*[=:]\\s*(CONNECTED|DISCONNECTED|2|0)", RegexOption.IGNORE_CASE)

    fun parse(raw: String): Boolean? {
        BOOLEAN.find(raw)?.groupValues?.get(1)?.let { return it.equals("true", true) }
        return when (STATE.find(raw)?.groupValues?.get(1)?.uppercase()) {
            "CONNECTED", "2" -> true
            "DISCONNECTED", "0" -> false
            else -> null
        }
    }
}

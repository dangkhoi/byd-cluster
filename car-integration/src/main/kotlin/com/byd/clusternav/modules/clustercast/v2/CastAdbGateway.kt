package com.byd.clusternav.modules.clustercast.v2

import dadb.AdbKeyPair
import dadb.Dadb
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

/**
 * Transport tới head unit qua adb. Không có Android ở đây, cố ý.
 *
 * Trước 2026-07-27 lớp này tên CastAndroidGateway và nhận thẳng một Context, dùng nó cho đúng hai việc:
 * lấy cặp khoá adb, và đo display của cụm. Vì hai việc đó mà toàn bộ transport bị khoá vào Android, và
 * hệ quả là CLI runner không thể chạy trên xe mà không dựng cả APK — đúng thứ review đối kháng chỉ ra.
 * Giờ hai việc đó thành hai hàm được truyền vào: app cấp bản Android, CLI runner cấp bản của nó.
 *
 * Tên cũ có chữ "Android" bị bỏ vì nó sẽ nói sai về module đang chứa nó.
 */

class CastAdbGateway(
    private val keys: () -> AdbKeyPair,
    private val measureCluster: () -> NamedClusterDisplay?,
    // Mặc định là loopback vì trong app, code này chạy NGAY TRÊN head unit. CLI runner chạy từ laptop
    // nên phải trỏ tới IP của xe — cùng một transport, chỉ khác điểm đến.
    private val host: String = "localhost",
    private val port: Int = 5555,
) : ShellGateway, CastMutationGateway {
    private val closed = AtomicBoolean(false)
    private val mutationGeneration = AtomicLong(0)
    private val activeSessions = ConcurrentHashMap.newKeySet<Dadb>()
    private val activeTasks = ConcurrentHashMap.newKeySet<Future<*>>()
    private val activeMutationSessions = ConcurrentHashMap.newKeySet<Dadb>()
    private val activeMutationTasks = ConcurrentHashMap.newKeySet<Future<*>>()
    /**
     * A bounded pool is only safe if the work it runs is bounded too. `task.cancel(true)` interrupts
     * the worker, but interruption does not unblock classic blocking socket I/O, so a loopback
     * endpoint that is dead (after a vehicle power cycle) or waiting on ADB key authorisation (after
     * app data was cleared) used to hold a worker forever. Two of those wedged both workers, the
     * queue filled, and AbortPolicy then rejected every later read: observation degraded to Unknown
     * and the whole feature dead-ended with no operator exit. Measured on DiLink3 2026-07-26.
     *
     * Both timeouts must stay well below the caller deadlines in [DEFAULT_TIMEOUT_MS] users so a
     * wedged connect surfaces as a normal timeout instead of a permanent leak.
     */
    private val DADB_CONNECT_TIMEOUT_MS = 1_500
    private val DADB_SOCKET_TIMEOUT_MS = 4_000

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
                    measuredCluster = { measureCluster() },
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
                val adb = Dadb.create(
                    host,
                    port,
                    keys(),
                    DADB_CONNECT_TIMEOUT_MS,
                    DADB_SOCKET_TIMEOUT_MS,
                )
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


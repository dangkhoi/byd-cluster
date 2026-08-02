package com.byd.clusternav.navigation

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Cluster-lane endpoint. Its worker resources are per-instance and never shared with HUD. */
class ClusterLaneAdapter(
    delivery: NavigationFrameDelivery,
    config: OutputAdapterConfig = OutputAdapterConfig(),
    nowEpochMs: () -> Long = System::currentTimeMillis,
    initiallyEnabled: Boolean = true
) : NavigationOutputPort {
    private val worker = BoundedNavigationOutputWorker(
        NavigationOutputTarget.CLUSTER_LANE,
        "navigation-cluster-lane",
        delivery,
        config,
        nowEpochMs,
        initiallyEnabled
    )

    override val target = NavigationOutputTarget.CLUSTER_LANE
    override fun setEnabled(enabled: Boolean) = worker.setEnabled(enabled)
    override fun submit(frame: NavigationFrame) = worker.submit(frame)
    override fun markDisplayVerified(sequence: Long, observedAtEpochMs: Long) =
        worker.markDisplayVerified(sequence, observedAtEpochMs)
    override fun markStale() = worker.markStale()
    override fun recordFault(reason: NavigationOutputFailureReason, detail: String?) = worker.recordFault(reason, detail)
    override fun stopSession() = worker.stopSession()
    override fun health() = worker.health()
    override fun close() = worker.close()
}

/** Shared implementation code, but every adapter owns a distinct instance and therefore distinct live resources. */
internal class BoundedNavigationOutputWorker(
    private val target: NavigationOutputTarget,
    threadName: String,
    private val delivery: NavigationFrameDelivery,
    private val config: OutputAdapterConfig,
    private val nowEpochMs: () -> Long,
    initiallyEnabled: Boolean
) : AutoCloseable {
    private val lock = Any()
    private val generation = AtomicLong(0L)
    private val activeTasks = ConcurrentHashMap.newKeySet<FutureTask<Unit>>()
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(config.queueCapacity),
        namedThreadFactory("$threadName-delivery"),
        ThreadPoolExecutor.AbortPolicy()
    )
    private val deadlines = ScheduledThreadPoolExecutor(1, namedThreadFactory("$threadName-deadline")).apply {
        removeOnCancelPolicy = true
    }

    private var enabled = initiallyEnabled
    private var status: NavigationOutputStatus = NavigationOutputStatus.OFF
    private var cachedSequence: Long? = null
    private var lastAttemptAt: Long? = null
    private var lastCompletedAt: Long? = null
    private var lastVerifiedAt: Long? = null

    fun setEnabled(value: Boolean) = synchronized(lock) {
        enabled = value
        resetLiveWorkLocked(clearCache = !value)
        status = if (value) NavigationOutputStatus.STARTING else NavigationOutputStatus.OFF
    }

    fun submit(frame: NavigationFrame): OutputSubmission {
        val acceptedGeneration: Long
        synchronized(lock) {
            if (!enabled) return OutputSubmission.DISABLED
            if (status == NavigationOutputStatus.OFF) status = NavigationOutputStatus.STARTING
            acceptedGeneration = generation.get()
        }

        val timedOut = AtomicBoolean(false)
        val deadlineRef = AtomicReference<ScheduledFuture<*>?>()
        lateinit var task: FutureTask<Unit>
        task = FutureTask {
            if (!isCurrent(acceptedGeneration)) return@FutureTask
            updateIfCurrent(acceptedGeneration) {
                status = NavigationOutputStatus.EMITTING
                lastAttemptAt = nowEpochMs()
            }
            try {
                delivery.deliver(frame)
                if (!timedOut.get()) updateIfCurrent(acceptedGeneration) {
                    status = NavigationOutputStatus.EMITTING
                    lastCompletedAt = nowEpochMs()
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                if (!timedOut.get()) updateIfCurrent(acceptedGeneration) {
                    status = NavigationOutputStatus.FAULT(
                        NavigationOutputFailureReason.DELIVERY_THROWN,
                        interrupted.message
                    )
                }
            } catch (error: RuntimeException) {
                updateIfCurrent(acceptedGeneration) {
                    status = NavigationOutputStatus.FAULT(
                        NavigationOutputFailureReason.DELIVERY_THROWN,
                        error.message
                    )
                }
            } finally {
                deadlineRef.get()?.cancel(false)
                activeTasks.remove(task)
            }
        }

        try {
            activeTasks += task
            executor.execute(task)
            synchronized(lock) {
                if (isCurrent(acceptedGeneration)) cachedSequence = frame.sequence
            }
        } catch (_: RejectedExecutionException) {
            activeTasks.remove(task)
            synchronized(lock) {
                generation.incrementAndGet()
                status = NavigationOutputStatus.FAULT(
                    if (executor.isShutdown) NavigationOutputFailureReason.EXECUTOR_REJECTED
                    else NavigationOutputFailureReason.QUEUE_SATURATED
                )
            }
            return if (executor.isShutdown) OutputSubmission.REJECTED_EXECUTOR else OutputSubmission.REJECTED_QUEUE_FULL
        }

        deadlineRef.set(deadlines.schedule({
            if (!task.isDone && isCurrent(acceptedGeneration)) {
                timedOut.set(true)
                updateIfCurrent(acceptedGeneration) {
                    status = NavigationOutputStatus.FAULT(NavigationOutputFailureReason.DEADLINE_EXCEEDED)
                }
                task.cancel(true)
            }
        }, config.deliveryDeadlineMs, TimeUnit.MILLISECONDS))
        return OutputSubmission.ACCEPTED
    }

    fun markDisplayVerified(sequence: Long, observedAtEpochMs: Long): Boolean = synchronized(lock) {
        if (!enabled || cachedSequence != sequence || observedAtEpochMs < 0 || status is NavigationOutputStatus.FAULT) {
            return false
        }
        generation.incrementAndGet()
        status = NavigationOutputStatus.DISPLAY_VERIFIED
        lastVerifiedAt = observedAtEpochMs
        true
    }

    fun markStale() = synchronized(lock) {
        if (enabled && cachedSequence != null) {
            generation.incrementAndGet()
            status = NavigationOutputStatus.STALE
        }
    }

    fun recordFault(reason: NavigationOutputFailureReason, detail: String?) = synchronized(lock) {
        generation.incrementAndGet()
        status = NavigationOutputStatus.FAULT(reason, detail)
    }

    fun stopSession() = synchronized(lock) {
        resetLiveWorkLocked(clearCache = true)
        status = NavigationOutputStatus.OFF
    }

    fun health(): NavigationOutputHealth = synchronized(lock) {
        NavigationOutputHealth(
            target = target,
            enabled = enabled,
            status = status,
            pendingCount = executor.queue.size + executor.activeCount,
            cachedSequence = cachedSequence,
            lastAttemptAtEpochMs = lastAttemptAt,
            lastCompletedAtEpochMs = lastCompletedAt,
            lastVerifiedAtEpochMs = lastVerifiedAt
        )
    }

    override fun close() {
        synchronized(lock) { resetLiveWorkLocked(clearCache = true) }
        executor.shutdownNow()
        deadlines.shutdownNow()
    }

    private fun resetLiveWorkLocked(clearCache: Boolean) {
        generation.incrementAndGet()
        activeTasks.forEach { it.cancel(true) }
        activeTasks.clear()
        executor.queue.clear()
        if (clearCache) cachedSequence = null
        lastAttemptAt = null
        lastCompletedAt = null
        lastVerifiedAt = null
    }

    private fun isCurrent(value: Long): Boolean = generation.get() == value

    private fun updateIfCurrent(value: Long, update: () -> Unit) = synchronized(lock) {
        if (isCurrent(value)) update()
    }

    private fun namedThreadFactory(name: String) = ThreadFactory { runnable ->
        Thread(runnable, name).apply { isDaemon = true }
    }
}

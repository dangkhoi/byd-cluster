package com.byd.clusternav.modules.clustercast

import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class CastUiMutationSnapshot(val revision: Long, val pending: Boolean)

/** Activity work lanes. Accepted durable selection/Stop commands outlive Activity teardown. */
internal class CastActivityWork : Closeable {
    private val closed = AtomicBoolean(false)
    private val operationExecutor = single("cast-ui-operation")
    private val refreshExecutor = single("cast-ui-refresh")
    private val miscExecutor = Executors.newFixedThreadPool(2, threads("cast-ui-misc"))
    private val refreshRevision = AtomicLong(0)
    private val selectionRevision = AtomicLong(0)
    private val mutationState = AtomicReference(CastUiMutationSnapshot(0L, false))

    fun operation(block: () -> Unit) = submit(operationExecutor, block)
    fun stop(block: () -> Unit) = submitDurable(STOP_EXECUTOR, block)
    fun misc(block: () -> Unit) = submit(miscExecutor, block)

    fun selection(block: (Long) -> Unit): Long {
        val revision = selectionRevision.incrementAndGet()
        submitDurable(SELECTION_EXECUTOR) { block(revision) }
        return revision
    }

    fun refresh(block: (Long) -> Unit): Long {
        val revision = refreshRevision.incrementAndGet()
        submit(refreshExecutor) { block(revision) }
        return revision
    }

    fun isCurrentSelection(revision: Long): Boolean =
        !closed.get() && selectionRevision.get() == revision

    fun currentSelectionRevision(): Long = selectionRevision.get()

    fun beginMutation(): Long {
        while (true) {
            val current = mutationState.get()
            val next = CastUiMutationSnapshot(Math.addExact(current.revision, 1L), true)
            if (mutationState.compareAndSet(current, next)) return next.revision
        }
    }

    fun finishMutation(revision: Long): Boolean {
        while (true) {
            val current = mutationState.get()
            if (!current.pending || current.revision != revision) return false
            val next = CastUiMutationSnapshot(Math.addExact(current.revision, 1L), false)
            if (mutationState.compareAndSet(current, next)) return true
        }
    }

    fun mutationSnapshot(): CastUiMutationSnapshot = mutationState.get()
    fun isCurrentMutation(snapshot: CastUiMutationSnapshot): Boolean = mutationState.get() == snapshot

    fun isCurrentRefresh(revision: Long): Boolean =
        !closed.get() && refreshRevision.get() == revision

    private fun submit(executor: ExecutorService, block: () -> Unit) {
        if (closed.get()) return
        try {
            executor.execute { if (!closed.get()) block() }
        } catch (_: RejectedExecutionException) {
            // Lifecycle shutdown won the race; no UI work may be delivered afterward.
        }
    }

    private fun submitDurable(executor: ExecutorService, block: () -> Unit) {
        if (closed.get()) return
        try {
            executor.execute(block)
        } catch (_: RejectedExecutionException) {
            // Process shutdown only; no command can be accepted by this lane afterward.
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        listOf(operationExecutor, refreshExecutor, miscExecutor).forEach(ExecutorService::shutdownNow)
    }

    private fun single(name: String) = Executors.newSingleThreadExecutor(threads(name))

    private fun threads(prefix: String) = threadFactory(prefix)

    companion object {
        private val THREAD_SEQUENCE = AtomicLong(0)
        private fun threadFactory(prefix: String) = ThreadFactory { runnable ->
            Thread(runnable, "$prefix-${THREAD_SEQUENCE.incrementAndGet()}").apply { isDaemon = true }
        }
        private val SELECTION_EXECUTOR = Executors.newSingleThreadExecutor(threadFactory("cast-ui-selection"))
        private val STOP_EXECUTOR = Executors.newSingleThreadExecutor(threadFactory("cast-ui-stop"))
    }
}

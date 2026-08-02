package com.byd.clusternav.modules.clustercast

import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.time.Duration
import java.time.Instant

internal fun statusTimerDelayMillis(now: Instant, expiresAt: Instant): Long {
    if (!now.isBefore(expiresAt)) return 0L
    val nanos = Duration.between(now, expiresAt).toNanos()
    return ((nanos - 1L) / 1_000_000L + 1L).coerceAtLeast(1L)
}

internal class CastActivityStatusTimers(
    private val operationStatus: CastOperationStatus,
    private val refresh: () -> Unit,
) : Closeable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var statusExpiryCallback: Runnable? = null
    private var stopAckCallback: Runnable? = null
    private var closed = false

    fun scheduleStatusExpiry(token: CastOperationToken, expiresAt: Instant) {
        cancelStatusExpiry()
        val callback = Runnable {
            statusExpiryCallback = null
            if (closed) return@Runnable
            val now = Instant.now()
            if (operationStatus.expire(token, now)) {
                refresh()
            } else {
                operationStatus.snapshot(token, now)
                    ?.takeIf { it.phase == CastOperationStatusPhase.COMPLETED }
                    ?.expiresAt
                    ?.let { scheduleStatusExpiry(token, it) }
            }
        }
        statusExpiryCallback = callback
        mainHandler.postDelayed(callback, statusTimerDelayMillis(Instant.now(), expiresAt))
    }

    fun cancelStatusExpiry() {
        statusExpiryCallback?.let(mainHandler::removeCallbacks)
        statusExpiryCallback = null
    }

    /**
     * [graceMillis] được truyền vào thay vì đọc hằng số của tầng dưới: lớp này là hạ tầng đếm thời gian
     * của UI, nó không cần biết ai định nghĩa ngân sách chờ.
     */
    fun scheduleStopAckRefresh(requestedAt: Instant, graceMillis: Long, isCurrent: (Instant) -> Boolean) {
        cancelStopAckRefresh()
        val callback = Runnable {
            stopAckCallback = null
            if (!closed && isCurrent(requestedAt)) refresh()
        }
        stopAckCallback = callback
        mainHandler.postDelayed(callback, graceMillis + 1L)
    }

    fun cancelStopAckRefresh() {
        stopAckCallback?.let(mainHandler::removeCallbacks)
        stopAckCallback = null
    }

    override fun close() {
        closed = true
        cancelStatusExpiry()
        cancelStopAckRefresh()
    }
}

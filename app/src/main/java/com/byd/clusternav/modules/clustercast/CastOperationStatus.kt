package com.byd.clusternav.modules.clustercast

import com.byd.clusternav.modules.clustercast.v2.CastManualIntentResult
import com.byd.clusternav.modules.clustercast.v2.StableState
import java.time.Instant

@JvmInline
value class CastOperationToken internal constructor(internal val sequence: Long)

enum class CastOperationStatusPhase { IN_FLIGHT, COMPLETED }

data class CastOperationStatusSnapshot(
    val message: String,
    val phase: CastOperationStatusPhase,
    val expiresAt: Instant? = null,
)

/** Transient presentation only. Durable Cast truth remains exclusively in CastSessionStore. */
class CastOperationStatus {
    private sealed interface Entry {
        val token: CastOperationToken
        val message: String

        data class InFlight(
            override val token: CastOperationToken,
            override val message: String,
        ) : Entry

        data class Completed(
            override val token: CastOperationToken,
            override val message: String,
            val expiresAt: Instant,
        ) : Entry
    }

    private val lock = Any()
    private var sequence = 0L
    private var current: Entry? = null

    fun begin(initial: String): CastOperationToken {
        require(initial.isNotBlank()) { "Initial operation status must not be blank" }
        return synchronized(lock) {
            val token = CastOperationToken(Math.addExact(sequence, 1L))
            sequence = token.sequence
            current = Entry.InFlight(token, initial)
            token
        }
    }

    fun complete(token: CastOperationToken, result: String, now: Instant): Boolean {
        require(result.isNotBlank()) { "Completion status must not be blank" }
        return synchronized(lock) {
            val active = current as? Entry.InFlight
            if (active?.token != token) return@synchronized false
            current = Entry.Completed(token, result, now.plusMillis(RESULT_TTL_MILLIS))
            true
        }
    }

    fun snapshot(now: Instant): CastOperationStatusSnapshot? = synchronized(lock) {
        snapshotLocked(current, now)
    }

    fun snapshot(token: CastOperationToken, now: Instant): CastOperationStatusSnapshot? = synchronized(lock) {
        snapshotLocked(current?.takeIf { it.token == token }, now)
    }

    fun isCurrent(token: CastOperationToken, now: Instant): Boolean = synchronized(lock) {
        snapshotLocked(current?.takeIf { it.token == token }, now) != null
    }

    fun expire(token: CastOperationToken, now: Instant): Boolean = synchronized(lock) {
        val completed = current as? Entry.Completed ?: return@synchronized false
        if (completed.token != token || now.isBefore(completed.expiresAt)) return@synchronized false
        current = null
        true
    }

    private fun snapshotLocked(entry: Entry?, now: Instant): CastOperationStatusSnapshot? = when (entry) {
        null -> null
        is Entry.InFlight -> CastOperationStatusSnapshot(
            entry.message,
            CastOperationStatusPhase.IN_FLIGHT,
        )
        is Entry.Completed -> if (now.isBefore(entry.expiresAt)) {
            CastOperationStatusSnapshot(
                entry.message,
                CastOperationStatusPhase.COMPLETED,
                entry.expiresAt,
            )
        } else {
            if (current?.token == entry.token) current = null
            null
        }
    }

    fun visibleText(projected: String, durablePriority: Boolean, now: Instant): String {
        if (durablePriority) return projected
        return snapshot(now)?.message ?: projected
    }

    fun clear(token: CastOperationToken): Boolean = synchronized(lock) {
        if (current?.token != token) return@synchronized false
        current = null
        true
    }

    /** Invalidates any local operation only when durable Stop supersedes all transient work. */
    fun clearAll() = synchronized(lock) { current = null }

    companion object {
        const val RESULT_TTL_MILLIS = 4_000L
    }
}

internal fun CastManualIntentResult.statusMessage(): String = when (this) {
    is CastManualIntentResult.Succeeded -> when (stableSession.state) {
        StableState.ACTIVE_VERIFIED -> "Đã chiếu và xác minh"
        StableState.ACTIVE_DEGRADED -> "Đã chiếu · còn một residue được bảo vệ"
        else -> "Đã hoàn tất tại ${stableSession.state}"
    }
    is CastManualIntentResult.Queued -> "Đã lưu lựa chọn mới nhất; sẽ chạy sau stable point"
    is CastManualIntentResult.VerificationPending -> "Đang chờ xác minh: $reason"
    is CastManualIntentResult.RecoveryRequired -> "Cần phục hồi: $reason"
    is CastManualIntentResult.Blocked -> "Bị chặn: $reason"
}

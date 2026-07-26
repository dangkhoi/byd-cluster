package com.byd.clusternav.modules.clustercast.v2

import java.time.Duration
import java.time.Instant
import java.util.Collections

data class CastRenderAction(val action: CastAction, val enabled: Boolean, val disabledReason: DisabledReason?)

class CastRenderModel(
    val title: String,
    val status: String,
    val operationAcknowledged: Boolean,
    val stopAcknowledgementTimedOut: Boolean,
    val durableStatusPriority: Boolean,
    actions: List<CastRenderAction>,
) {
    val actions: List<CastRenderAction> = Collections.unmodifiableList(ArrayList(actions))
}

object CastUiRenderer {
    fun render(state: CastUiStateV2, renderedAt: Instant, stopRequestedAt: Instant? = null): CastRenderModel {
        val acknowledgement = stopRequestedAt == null || state.coarseState == CoarseState.STOP_REQUESTED
        val acknowledgementTimedOut = stopRequestedAt != null && !acknowledgement &&
            Duration.between(stopRequestedAt, renderedAt).toMillis() > STOP_ACK_GRACE_MILLIS
        val durableStatusPriority = state.recoverySubstate != null || state.coarseState in setOf(
            CoarseState.LEGACY_ACTIVE_READ_ONLY,
            CoarseState.STOP_REQUESTED,
            CoarseState.RESTORING,
            CoarseState.RECOVERING,
            CoarseState.RECOVERY_PENDING,
            CoarseState.MANUAL_REQUIRED,
        )
        val title = when (state.coarseState) {
            CoarseState.COLD_PRISTINE -> "Cluster Cast sẵn sàng tạo cụm"
            CoarseState.IDLE_VERIFIED -> "Cluster Cast sẵn sàng"
            CoarseState.ACTIVE_VERIFIED -> "Đang chiếu"
            CoarseState.ACTIVE_DEGRADED -> "Đang chiếu · giới hạn"
            CoarseState.LEGACY_ACTIVE_READ_ONLY -> "Phiên cũ · chỉ đọc"
            CoarseState.MANUAL_REQUIRED -> "Cần xử lý thủ công"
            CoarseState.RECOVERY_PENDING -> "Đang chờ phục hồi"
            else -> "Đang xử lý"
        }
        val status = when {
            acknowledgementTimedOut && !durableStatusPriority -> "Chưa nhận xác nhận trong 500 ms"
            state.coarseState == CoarseState.COLD_PRISTINE -> "Chọn ứng dụng để chiếu"
            state.unavailableReason != null -> state.unavailableReason.name.replace('_', ' ').lowercase()
            state.nextSafeAction == NextSafeAction.NONE -> "Sẵn sàng"
            else -> state.nextSafeAction.name.replace('_', ' ').lowercase()
        }
        val actions = CastAction.entries.map { action ->
            val allowed = action in state.allowedActions
            CastRenderAction(action, allowed, if (allowed) null else state.disabledReasons[action])
        }
        return CastRenderModel(
            title,
            status,
            acknowledgement,
            acknowledgementTimedOut,
            durableStatusPriority,
            actions,
        )
    }

    const val STOP_ACK_GRACE_MILLIS = 500L
}

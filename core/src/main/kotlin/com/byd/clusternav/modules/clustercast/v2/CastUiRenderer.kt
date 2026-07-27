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
        val unavailable = state.unavailableReason
        val status = when {
            acknowledgementTimedOut && !durableStatusPriority -> "Chưa nhận xác nhận trong 500 ms"
            state.coarseState == CoarseState.COLD_PRISTINE -> "Chọn ứng dụng để chiếu"
            // R13 — nói đúng phần đo được, không nói hơn. Bằng chứng cho ACTIVE_VERIFIED chỉ là cửa
            // sổ đã nằm trên display cụm với đúng target và đúng khung; ngày 2026-07-27 xác nhận việc
            // task nằm trên display 1 KHÔNG chứng minh cụm đang hiện app (dump cho thấy task hiển
            // thị=true trong lúc tài xế vẫn thấy đồng hồ). Câu này nêu đúng cơ sở đó và mời người dùng
            // nhìn cụm — người là cảm biến duy nhất hiện có cho tới khi có observable thật.
            state.coarseState == CoarseState.ACTIVE_VERIFIED -> "Cửa sổ đã lên cụm · nhìn cụm để xác nhận"
            unavailable != null -> explain(unavailable)
            state.nextSafeAction == NextSafeAction.NONE -> "Sẵn sàng"
            else -> explain(state.nextSafeAction)
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


    /**
     * The detail line used to be the enum name with underscores stripped, so the screen said things
     * like "contract unmapped" and "wait and observe" to a driver. The mapping is exhaustive on
     * purpose: adding a state without a sentence for it will not compile.
     */
    private fun explain(reason: UnavailableReason): String = when (reason) {
        UnavailableReason.LEGACY_SESSION_UNSAFE -> "Có phiên cũ trên cụm, chưa an toàn để can thiệp"
        UnavailableReason.OBSERVATION_EXHAUSTED -> "Không đọc được trạng thái cụm sau nhiều lần thử"
        UnavailableReason.COMPENSATION_EXHAUSTED -> "Đã thử hoàn tác hết cách mà chưa về được nền cũ"
        UnavailableReason.RECOVERY_ACTION_ONLY -> "Chỉ còn các hành động khắc phục"
        UnavailableReason.PROTECTED_SESSION -> "Phiên được bảo vệ, không tắt app đang chạy"
        UnavailableReason.ONE_ATTEMPT_EXHAUSTED -> "Đã dùng hết một lần thử được phép"
        UnavailableReason.OCCLUSION_NOT_PROVEN -> "Chưa chứng minh được app đang che cụm"
        UnavailableReason.BASELINE_UNKNOWN -> "Chưa biết nền cũ của máy nên không dám sửa"
        UnavailableReason.PROFILE_UNSUPPORTED_ACTIVE -> "Đời máy này chưa được hỗ trợ khi đang có phiên"
        UnavailableReason.CONTRACT_UNMAPPED -> "Chưa nhận diện được trạng thái cụm"
    }

    private fun explain(next: NextSafeAction): String = when (next) {
        NextSafeAction.NONE -> "Sẵn sàng"
        NextSafeAction.REQUEST_STOP -> "Bấm Dừng để trả đồng hồ"
        NextSafeAction.RETRY_CONNECT_BOUNDED -> "Thử kết nối lại"
        NextSafeAction.WAIT_AND_OBSERVE -> "Đang chờ xác nhận từ cụm"
        NextSafeAction.OPEN_DIAGNOSTICS -> "Mở Chẩn đoán để xem chi tiết"
        NextSafeAction.REQUEST_PHONE_DISCONNECT -> "Ngắt kết nối điện thoại trước"
        NextSafeAction.TRY_ELIGIBLE_RECOVERY_ONCE -> "Có thể thử phục hồi một lần"
        NextSafeAction.SHOW_PHYSICAL_INSTRUCTION -> "Cần một thao tác trực tiếp trên xe"
        NextSafeAction.OPEN_PROFILE_SETUP -> "Cần thiết lập hồ sơ cho đời máy này"
    }

    const val STOP_ACK_GRACE_MILLIS = 500L
}

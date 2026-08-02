package com.byd.clusternav.modules.clustercast

import com.byd.clusternav.modules.clustercast.v2.NON_DISPATCHING_ACTIONS
import com.byd.clusternav.modules.clustercast.v2.CastAction
import com.byd.clusternav.cast.platform.CastAndroidRuntime
import com.byd.clusternav.cast.platform.CastAppCatalog
import com.byd.clusternav.modules.clustercast.v2.CastRenderModel
import com.byd.clusternav.modules.clustercast.v2.StableState
import java.time.Instant

internal data class CastActivityRefreshResult(
    val model: CastRenderModel,
    val visibleStatus: String,
    val selectedEligible: Boolean,
    /**
     * Ba sự thật về cụm mà chính lần đọc này quan sát được — trả ra để tầng UI KHÔNG phải tự gọi lại
     * `facade.observedState()`. Chỉ kiểu nguyên thuỷ: `ObservedState` là kiểu của tầng dưới và không
     * được lọt qua ranh giới (`CastArchitectureRatchetTest`).
     *
     * Lý do có mặt (lỗi thật, 2026-07-29): panel kích thước của Home gọi `facade.observedState()` ngay
     * trong nhánh `postUi` của refresh, tức trên main thread — mà `CastFacade.observe()` tự ghi rõ "Đây
     * là I/O: đừng gọi trên main thread", và đó đúng là lớp lỗi façade sinh ra để chặn ("UI có thể vô
     * tình chạm vào transport ngay trong đường vẽ màn hình và làm treo màn hình"). Với nhịp tick 1s của
     * Home thì đó là hai vòng dumpsys mỗi giây trên luồng vẽ.
     */
    val activeTarget: String?,
    val clusterSize: Pair<Int, Int>?,
    val geometryProfileId: String?,
)


/**
 * Các phép KHÔNG phát lệnh nào ra xe, nên một phép ghi đang chờ không có lý do gì khoá chúng.
 *
 * Lấy từ [NON_DISPATCHING_ACTIONS] ở `:core` thay vì tự liệt kê — bản tự liệt kê đã lệch với projector
 * ngay lần sửa kế tiếp. Thêm `CHOOSE_ANOTHER_APP`: đổi target cũng không phát lệnh, nhưng nó chỉ đi qua
 * khi trạng thái vốn đã cho (phép giao), nên không tự nới quyền.
 */
private val NON_DISPATCHING = NON_DISPATCHING_ACTIONS + CastAction.CHOOSE_ANOTHER_APP

/**
 * Thu hẹp tập phép khi đang chờ một phép ghi hội tụ.
 *
 * Bản trước so sánh BẰNG với hai tập cứng để đoán "có được chọn app không". Cách đó sai hai lần: nó
 * vỡ im lặng mỗi khi tập phép đổi (thêm một phép mới là `selectionAllowed` lặng lẽ thành false), và nó
 * trả về đúng một phép STOP — nghĩa là trong lúc chờ, người dùng MẤT cả Chẩn đoán. Phát hiện ngày
 * 2026-07-27 khi chạy E2E trên emulator.
 *
 * Bản này lọc theo Ý ĐỊNH thay vì theo danh sách: chặn mọi phép phát lệnh mới, giữ Stop để luôn có
 * đường thoát, và giữ những phép không phát lệnh nào mà trạng thái vốn đã cho.
 */
internal fun CastRenderModel.activityActions(mutation: CastUiMutationSnapshot): Set<CastAction> {
    val canonical = actions.filterTo(linkedSetOf()) { it.enabled }.mapTo(linkedSetOf()) { it.action }
    if (!mutation.pending) return canonical
    return buildSet {
        add(CastAction.STOP)
        addAll(canonical.intersect(NON_DISPATCHING))
    }
}
/**
 * Chuẩn hoá phép đọc phone-session thô (`dumpsys activity services <ownerPackage>` → parser
 * `ProjectionSessionEvidenceParser` ở tầng dưới) thành giá trị dùng để quyết định recovery cho một
 * target `projectionComponent` (CarPlay/Android Auto host, khớp theo tên kiểu `PROJECTION_HINTS` —
 * generic, không hardcode package cụ thể).
 *
 * Phát hiện 2026-07-28 khi lần theo cùng ngày với fix `CastPolicy.classify`: với CHÍNH package host
 * CarPlay/Android Auto, `dumpsys activity services <host>` không bao giờ chứa field mà parser tìm
 * (`connectedPhoneSession`/`mPhoneConnected`/…) — field đó mô tả app THỨ BA đang được chiếu qua, không
 * phải bản thân host. Xác nhận bằng dump thật 2026-07-23
 * (`docs/diagnostics/carlog-2026-07-23-trace/live/08-svc-carplay.txt`,
 * `.../09-svc-androidauto.txt`, khoá lại ở `CastPhoneSessionEvidenceGapTest`). Vậy phép đọc `null` ở
 * đây là CẤU TRÚC (không bao giờ đổi), không phải "chưa đo được lần này".
 *
 * Trước fix này, `phoneConnected` giữ nguyên `null`, khiến `CastRuntimeUi.render`'s `when` rơi vào
 * `else -> null` (không phải PROTECTED_SINK_CONNECTED cũng không phải DISCONNECTED_SINK_ELIGIBLE), và
 * `CastUiStateProjector.project` với `recoverySubstate=null` + `stableState=RECOVERY_PENDING` +
 * `stableConverged=false` rơi tới `failClosed` — một ngõ cụt vĩnh viễn, đúng lớp lỗi đã khoá ở
 * `docs/diagnostics/cast-recovery-deadend-2026-07-28.md`, tái hiện lại cho đúng hai target chủ lực.
 *
 * Cùng nguyên tắc `CastPolicy.classify` đã áp dụng cùng ngày (`evidence.connectedPhoneSession !=
 * false`): khi không đo được, coi như phiên CÒN NỐI (hướng an toàn hơn — chỉ mở đường
 * `REQUEST_PHONE_DISCONNECT` không phá huỷ gì), KHÔNG BAO GIỜ coi như đã ngắt. Chỉ một phép đọc `false`
 * XÁC NHẬN mới được đi tiếp vào nhánh tính `recoveryEligible` (không đổi hành vi nhánh đó — nó vẫn đòi
 * hỏi quan sát lần hai hội tụ y hệt trước khi fix này).
 */
internal fun resolveProjectionPhoneConnected(rawPhoneSession: Boolean?): Boolean = rawPhoneSession != false

/** Read-only refresh boundary. Call only from the serialized refresh lane. */
internal class CastActivityRefreshReader(
    private val runtime: CastAndroidRuntime.Runtime,
    private val catalog: CastAppCatalog,
    private val operationStatus: CastOperationStatus,
) {
    fun read(selectedPackage: String?, stopRequestedAt: Instant?): CastActivityRefreshResult {
        // A journal that still holds an unresolved operation makes the whole screen read-only, so give
        // it a chance to reach a terminal state before anything is rendered from it.
        val facade = CastFacade.wrapping(runtime, catalog)
        if (facade.reconcileAbandoned()) facade.recordOperation("cleared an abandoned operation on refresh")
        if (facade.reconcileUnobservableIdleSession()) {
            facade.recordOperation("cleared unobservable idle stable session on refresh")
        }
        if (facade.reconcileStaleRecovery()) {
            facade.recordOperation("cleared stale RECOVERY_PENDING: cluster empty, safe to re-bootstrap")
        }
        // Không cần biết kiểu StoreRead/ObservationValue: façade trả thẳng thứ cần dùng.
        val envelope = facade.envelope()
        // Một lần quan sát cho một lần vẽ. Trước 2026-07-29 chỗ này gọi `observe()` rồi `observedState()`
        // — hai vòng quan sát ra xe cho cùng một khoảnh khắc, và mô hình render từ mẫu này trong khi
        // `ownerPackage` lại đến từ mẫu kia. Với nhịp 1s của Home đó còn là gấp đôi lưu lượng, mãi mãi.
        var (observation, observed) = facade.observeBoth()
        val ownerPackage = observed?.protectedResidue?.packageName ?: observed?.target?.packageName
        var phoneConnected: Boolean? = null
        var recoveryEligible: Boolean? = null
        if (envelope?.stableSession?.state == StableState.RECOVERY_PENDING && ownerPackage != null &&
            catalog.evidence(ownerPackage).projectionComponent == true
        ) {
            val rawPhoneSession = facade.phoneSession(ownerPackage)
            phoneConnected = resolveProjectionPhoneConnected(rawPhoneSession)
            if (rawPhoneSession == false) {
                // Mẫu thứ hai vẫn là một quan sát ĐỘC LẬP — điều kiện "hai mẫu bằng nhau" không đổi.
                val (second, secondKnown) = facade.observeBoth()
                recoveryEligible = secondKnown != null && secondKnown == observed &&
                    (secondKnown.protectedResidue?.packageName ?: secondKnown.target?.packageName) == ownerPackage
                observation = second
                observed = secondKnown
            }
        }
        val renderedAt = Instant.now()
        val model = facade.renderModel(
            observation = observation,
            now = renderedAt,
            stopRequestedAt = stopRequestedAt,
            destructiveRecoveryEligible = recoveryEligible,
            phoneSessionConnected = phoneConnected,
        )
        val placementAllowed = model.actions.any {
            it.enabled && it.action in setOf(CastAction.CAST, CastAction.SWITCH)
        }
        val selectedEligible = placementAllowed && selectedPackage?.let { packageName ->
            val current = envelope ?: return@let false
            runCatching {
                facade.selectionReady(packageName, current)
            }.getOrDefault(false)
        } == true
        return CastActivityRefreshResult(
            model,
            operationStatus.visibleText(
                "${model.title} · ${model.status}",
                model.durableStatusPriority,
                renderedAt,
            ),
            selectedEligible,
            activeTarget = observed?.target?.packageName,
            clusterSize = observed?.geometry?.bounds?.let { (it.right - it.left) to (it.bottom - it.top) },
            geometryProfileId = observed?.geometry?.profileId,
        )
    }
}

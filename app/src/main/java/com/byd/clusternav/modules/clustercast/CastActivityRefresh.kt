package com.byd.clusternav.modules.clustercast

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
)


/**
 * Các phép KHÔNG phát lệnh nào ra xe, nên một phép ghi đang chờ không có lý do gì khoá chúng.
 * Chọn app và mở danh sách app chỉ ghi tuỳ chọn cục bộ; Chẩn đoán chỉ đọc.
 */
private val NON_DISPATCHING = setOf(
    CastAction.OPEN_DIAGNOSTICS,
    CastAction.SELECT_TARGET_APP,
    CastAction.CHOOSE_ANOTHER_APP,
)

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
        // Không cần biết kiểu StoreRead/ObservationValue: façade trả thẳng thứ cần dùng.
        val envelope = facade.envelope()
        var observation = facade.observe()
        val observed = facade.observedState()
        val ownerPackage = observed?.protectedResidue?.packageName ?: observed?.target?.packageName
        var phoneConnected: Boolean? = null
        var recoveryEligible: Boolean? = null
        if (envelope?.stableSession?.state == StableState.RECOVERY_PENDING && ownerPackage != null &&
            catalog.evidence(ownerPackage).projectionComponent == true
        ) {
            phoneConnected = facade.phoneSession(ownerPackage)
            if (phoneConnected == false) {
                val second = facade.observe()
                val secondKnown = facade.observedState()
                recoveryEligible = secondKnown != null && secondKnown == observed &&
                    (secondKnown.protectedResidue?.packageName ?: secondKnown.target?.packageName) == ownerPackage
                observation = second
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
        )
    }
}

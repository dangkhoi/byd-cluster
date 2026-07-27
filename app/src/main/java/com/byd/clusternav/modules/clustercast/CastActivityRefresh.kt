package com.byd.clusternav.modules.clustercast

import com.byd.clusternav.modules.clustercast.v2.CastAction
import com.byd.clusternav.modules.clustercast.v2.CastAndroidRuntime
import com.byd.clusternav.modules.clustercast.v2.CastAppCatalog
import com.byd.clusternav.modules.clustercast.v2.CastRenderModel
import com.byd.clusternav.modules.clustercast.v2.StableState
import java.time.Instant

internal data class CastActivityRefreshResult(
    val model: CastRenderModel,
    val visibleStatus: String,
    val selectedEligible: Boolean,
)


internal fun CastRenderModel.activityActions(mutation: CastUiMutationSnapshot): Set<CastAction> {
    val canonical = actions.filterTo(linkedSetOf()) { it.enabled }.mapTo(linkedSetOf()) { it.action }
    if (!mutation.pending) return canonical
    val selectionAllowed = canonical == setOf(
        CastAction.CAST,
        CastAction.CHOOSE_ANOTHER_APP,
        CastAction.OPEN_APP_MANAGER,
        CastAction.OPEN_DIAGNOSTICS,
    ) || canonical == setOf(CastAction.STOP, CastAction.CHOOSE_ANOTHER_APP)
    return buildSet {
        add(CastAction.STOP)
        if (selectionAllowed) add(CastAction.CHOOSE_ANOTHER_APP)
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
        val facade = CastFacade.wrapping(runtime)
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
                facade.selectionReady(catalog.snapshot(packageName, facade.phoneSession(packageName)), current)
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

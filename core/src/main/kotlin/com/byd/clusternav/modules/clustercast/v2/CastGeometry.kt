package com.byd.clusternav.modules.clustercast.v2

data class GeometryDraft(
    val target: CastTarget,
    val bounds: CastRect,
    val densityDpi: Int?,
    val profileId: String,
    val basedOnEpoch: Long,
)

sealed interface GeometryDecision {
    data class Ready(val geometry: AcceptedGeometry) : GeometryDecision
    data class Rejected(val reason: DisabledReason) : GeometryDecision
}

object CastGeometry {
    fun validate(
        draft: GeometryDraft,
        observed: ObservedState,
        durableEpoch: Long,
    ): GeometryDecision {
        if (draft.basedOnEpoch != durableEpoch || observed.target != draft.target) {
            return GeometryDecision.Rejected(DisabledReason.CONTRACT_UNMAPPED)
        }
        if (observed.protectedResidue != null) {
            return GeometryDecision.Rejected(DisabledReason.PROTECTED_SESSION)
        }
        val b = draft.bounds
        if (b.left < 0 || b.top < 0 || b.right <= b.left || b.bottom <= b.top ||
            draft.densityDpi?.let { it !in 72..640 } == true || draft.profileId.isBlank()
        ) {
            return GeometryDecision.Rejected(DisabledReason.GEOMETRY_LIMITED)
        }
        return GeometryDecision.Ready(AcceptedGeometry(b, draft.densityDpi, draft.profileId))
    }
}


/** Pure command encoding; callers must still bind requests to a fresh observed target/display. */
object CastGeometryCommandEncoder {
    fun taskBounds(target: CastTarget, displayIdentity: String, geometry: AcceptedGeometry): String? {
        if (target.displayId < 1 || displayIdentity != "display-${target.displayId}") return null
        val b = geometry.bounds
        if (b.left < 0 || b.top < 0 || b.right <= b.left || b.bottom <= b.top) return null
        return "am task resize ${target.taskId} ${b.left} ${b.top} ${b.right} ${b.bottom}"
    }

    fun displayDensity(target: CastTarget, displayIdentity: String, geometry: AcceptedGeometry): String? {
        if (target.displayId < 1 || displayIdentity != "display-${target.displayId}") return null
        val density = geometry.densityDpi?.takeIf { it in 72..640 } ?: return null
        return "wm density $density -d ${target.displayId}"
    }

    fun resetDisplay(displayId: Int, displayIdentity: String): String? {
        if (displayId < 1 || displayIdentity != "display-$displayId") return null
        return "wm size reset -d $displayId; wm density reset -d $displayId; wm overscan reset -d $displayId"
    }

    fun restoreDisplay(displayId: Int, displayIdentity: String, geometry: AcceptedGeometry): String? {
        if (displayId < 1 || displayIdentity != "display-$displayId") return null
        val bounds = geometry.bounds
        val density = geometry.densityDpi?.takeIf { it in 72..640 } ?: return null
        if (bounds.left != 0 || bounds.top != 0 || bounds.right <= 0 || bounds.bottom <= 0) return null
        return "wm size ${bounds.right}x${bounds.bottom} -d $displayId; " +
            "wm density $density -d $displayId; wm overscan reset -d $displayId"
    }
}

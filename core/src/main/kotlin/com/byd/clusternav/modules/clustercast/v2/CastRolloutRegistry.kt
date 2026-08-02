package com.byd.clusternav.modules.clustercast.v2

import java.util.Collections

data class CastRolloutFlags(
    val navUiV2: Boolean = false,
    val castUiV2: Boolean = false,
    val engineNormalV2: Boolean = false,
    val engineKeepSessionV2: Boolean = false,
    val engineProjectionSinkV2: Boolean = false,
)

class RolloutDecision(
    val actionOwner: EngineVersion?,
    val effectiveUiVersion: EngineVersion,
    val pendingUiRollback: Boolean,
    enabledSlices: Set<TargetClass>,
) {
    val enabledSlices: Set<TargetClass> =
        Collections.unmodifiableSet(LinkedHashSet(enabledSlices))
}

object CastRolloutRegistry {
    val defaults = CastRolloutFlags()
    val vehicleTestCandidate = CastRolloutFlags(
        castUiV2 = true,
        engineNormalV2 = true,
        engineKeepSessionV2 = true,
        engineProjectionSinkV2 = true,
    )

    /**
     * Resolves Cast routing from checksummed durable truth. navUiV2 is deliberately absent from all
     * Cast decisions: the requested flags are independent. At verified idle/no transaction there is
     * no action owner, so the requested Cast UI may become the next effective version and any pending
     * rollback is cleared. Working/active/recovery routing remains session-sticky.
     *
     * `pristineBoundary` dropped its `durableEpoch == 0L` requirement 2026-07-29: that check used to
     * be equivalent to "nothing recorded" only because nothing ever nulled `stableSession` after the
     * epoch moved past zero. `CastCoordinator.reconcileUnobservableIdleSession()` now does exactly
     * that (clearing a stale idle claim this boot can never re-verify), so "nothing recorded" must be
     * read directly off `stableSession == null && transaction == null`, not off the epoch.
     */
    fun resolve(envelope: CastSessionEnvelope, flags: CastRolloutFlags = defaults): RolloutDecision {
        val stableSession = envelope.stableSession
        val transaction = envelope.transaction
        val pristineBoundary = stableSession == null && transaction == null
        val safeBoundary = transaction == null &&
            (pristineBoundary || stableSession?.state == StableState.IDLE_VERIFIED)
        val requestedUi = if (flags.castUiV2) EngineVersion.V2 else EngineVersion.LEGACY

        val effectiveUi = if (safeBoundary) requestedUi else envelope.effectiveUiVersion
        val actionOwner = if (safeBoundary) null else stableSession?.engineVersion ?: effectiveUi
        val rollbackRequested = effectiveUi == EngineVersion.V2 && !flags.castUiV2
        val pendingRollback = if (safeBoundary) false else envelope.pendingUiRollback || rollbackRequested

        val slices = linkedSetOf<TargetClass>()
        if (flags.engineNormalV2) slices += TargetClass.NORMAL
        if (flags.engineKeepSessionV2) slices += TargetClass.KEEP_SESSION
        if (flags.engineProjectionSinkV2) slices += TargetClass.PROJECTION_SINK
        return RolloutDecision(actionOwner, effectiveUi, pendingRollback, slices)
    }

    fun apply(envelope: CastSessionEnvelope, flags: CastRolloutFlags = defaults): CastSessionEnvelope {
        val decision = resolve(envelope, flags)
        return envelope.copy(
            effectiveUiVersion = decision.effectiveUiVersion,
            pendingUiRollback = decision.pendingUiRollback,
        )
    }
}

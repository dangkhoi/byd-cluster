package com.byd.clusternav.modules.clustercast.v2

import java.util.UUID

sealed interface AdjustmentResult {
    data class Ready(val draft: AdjustmentDraft) : AdjustmentResult
    data class Rejected(val reason: String) : AdjustmentResult
}

/**
 * Durable one-target geometry workspace. Every transition is persisted through the Cast store lock.
 * It never issues Android mutations; the coordinator/executor owns that boundary.
 */
class CastAdjustmentWorkspace(
    private val store: CastSessionStore,
) {
    fun open(observed: ObservedState): AdjustmentResult = store.locked {
        val envelope = loaded() ?: return@locked AdjustmentResult.Rejected("durable store unavailable")
        if (envelope.stopRequested || envelope.transaction != null) {
            return@locked AdjustmentResult.Rejected("another operation owns mutation")
        }
        val stable = envelope.stableSession
            ?: return@locked AdjustmentResult.Rejected("stable Cast session unavailable")
        if (stable.state != StableState.ACTIVE_VERIFIED || observed.protectedResidue != null) {
            return@locked AdjustmentResult.Rejected("adjustment requires a verified single target")
        }
        val target = observed.target
            ?: return@locked AdjustmentResult.Rejected("observed target unavailable")
        val geometry = observed.geometry
            ?: return@locked AdjustmentResult.Rejected("observed geometry unavailable")
        if (target != stable.activeTarget || observed.displayIdentity != stable.expectedDisplayIdentity) {
            return@locked AdjustmentResult.Rejected("stable and observed target identity differ")
        }
        envelope.adjustmentDraft?.let { existing ->
            return@locked if (matches(existing, observed, envelope.durableEpoch)) {
                AdjustmentResult.Ready(existing)
            } else {
                val frozen = existing.copy(
                    applyState = AdjustmentApplyState.FROZEN,
                    operationId = null,
                    validationError = "target or epoch changed",
                )
                update { it.copy(adjustmentDraft = frozen) }
                AdjustmentResult.Ready(frozen)
            }
        }
        val draft = AdjustmentDraft(
            target, stable.expectedDisplayIdentity, envelope.durableEpoch,
            stable.acceptedGeometry ?: geometry, geometry, geometry,
            previousVerifiedApply = null, lastVerifiedApply = geometry,
            operationId = null, dirty = false,
            applyState = AdjustmentApplyState.APPLIED_VERIFIED, validationError = null,
        )
        update { it.copy(adjustmentDraft = draft) }
        AdjustmentResult.Ready(draft)
    }

    fun edit(geometry: AcceptedGeometry): AdjustmentResult = mutateDraft { envelope, draft ->
        if (draft.applyState == AdjustmentApplyState.FROZEN) return@mutateDraft rejected("workspace is frozen")
        if (envelope.transaction != null || envelope.stopRequested) return@mutateDraft rejected("mutation owner unavailable")
        ready(draft.copy(
            localDraft = geometry,
            operationId = null,
            dirty = geometry != draft.acceptedGeometry,
            applyState = AdjustmentApplyState.SAVED_ONLY,
            validationError = null,
        ))
    }

    fun beginApply(observed: ObservedState): AdjustmentResult = mutateDraft { envelope, draft ->
        if (!matches(draft, observed, envelope.durableEpoch)) return@mutateDraft frozen(draft, "target or epoch changed")
        if (envelope.transaction != null || envelope.stopRequested) return@mutateDraft rejected("mutation owner unavailable")
        val decision = CastGeometry.validate(
            GeometryDraft(
                draft.target, draft.localDraft.bounds, draft.localDraft.densityDpi,
                draft.localDraft.profileId, draft.durableEpoch,
            ),
            observed,
            envelope.durableEpoch,
        )
        if (decision is GeometryDecision.Rejected) {
            return@mutateDraft ready(draft.copy(validationError = decision.reason.name))
        }
        ready(draft.copy(
            operationId = null,
            applyState = AdjustmentApplyState.APPLYING,
            validationError = null,
        ))
    }

    /** Bind the durable draft only after the executor has persisted the real transaction epoch/ID. */
    fun bindExecution(id: UUID, epoch: Long): AdjustmentResult = mutateDraft { envelope, draft ->
        val tx = envelope.transaction
        if (draft.applyState != AdjustmentApplyState.APPLYING || tx?.operationId != id ||
            tx.epoch != epoch || tx.operation != CastOperation.APPLY_GEOMETRY
        ) return@mutateDraft rejected("geometry transaction does not own the draft")
        ready(draft.copy(operationId = id, durableEpoch = epoch, validationError = null))
    }

    fun recordVerifiedApply(first: ObservedState, second: ObservedState): AdjustmentResult =
        mutateDraft { envelope, draft ->
            if (draft.applyState != AdjustmentApplyState.APPLYING || draft.operationId == null) {
                return@mutateDraft rejected("no applying draft")
            }
            if (first != second || !matches(draft, first, envelope.durableEpoch) || first.geometry != draft.localDraft) {
                return@mutateDraft ready(draft.copy(
                    operationId = null,
                    applyState = AdjustmentApplyState.FAILED_UNVERIFIED,
                    validationError = "two-sample geometry verification failed",
                ))
            }
            val previous = draft.lastVerifiedApply?.takeUnless { it == draft.localDraft }
            ready(draft.copy(
                previousVerifiedApply = previous ?: draft.previousVerifiedApply,
                lastVerifiedApply = draft.localDraft,
                operationId = null,
                applyState = AdjustmentApplyState.APPLIED_VERIFIED,
                validationError = null,
            ))
        }

    fun markApplyFailed(reason: String): AdjustmentResult = mutateDraft { _, draft ->
        ready(draft.copy(
            operationId = null,
            applyState = AdjustmentApplyState.FAILED_UNVERIFIED,
            validationError = reason.ifBlank { "geometry apply failed" },
        ))
    }

    fun undoLast(): AdjustmentResult = mutateDraft { _, draft ->
        val previous = draft.previousVerifiedApply ?: return@mutateDraft rejected("no verified geometry to undo")
        ready(draft.copy(
            localDraft = previous,
            operationId = null,
            dirty = previous != draft.acceptedGeometry,
            applyState = AdjustmentApplyState.SAVED_ONLY,
            validationError = null,
        ))
    }

    fun restoreEntry(): AdjustmentResult = mutateDraft { _, draft ->
        ready(draft.copy(
            localDraft = draft.entrySnapshot,
            operationId = null,
            dirty = draft.entrySnapshot != draft.acceptedGeometry,
            applyState = AdjustmentApplyState.SAVED_ONLY,
            validationError = null,
        ))
    }

    fun resetDefault(defaultGeometry: AcceptedGeometry): AdjustmentResult = edit(defaultGeometry)

    /** Promote only a same-target, two-sample verified draft; this is the explicit Done action. */
    fun done(first: ObservedState, second: ObservedState): AdjustmentResult = store.locked {
        val envelope = loaded() ?: return@locked AdjustmentResult.Rejected("durable store unavailable")
        val draft = envelope.adjustmentDraft ?: return@locked AdjustmentResult.Rejected("workspace unavailable")
        val stable = envelope.stableSession ?: return@locked AdjustmentResult.Rejected("stable session unavailable")
        if (envelope.transaction != null || envelope.stopRequested || first != second ||
            !matches(draft, first, envelope.durableEpoch) || first.geometry != draft.localDraft ||
            draft.lastVerifiedApply != draft.localDraft || draft.applyState != AdjustmentApplyState.APPLIED_VERIFIED
        ) return@locked AdjustmentResult.Rejected("latest draft is not verified for Done")
        val promoted = stable.copyAcceptedGeometry(draft.localDraft)
        update { it.copy(stableSession = promoted, adjustmentDraft = null) }
        AdjustmentResult.Ready(draft.copy(
            acceptedGeometry = draft.localDraft,
            dirty = false,
            validationError = null,
        ))
    }

    /** Cancel exits only after entry geometry has independently converged twice. */
    fun cancelAfterRestore(first: ObservedState, second: ObservedState): AdjustmentResult = store.locked {
        val envelope = loaded() ?: return@locked AdjustmentResult.Rejected("durable store unavailable")
        val draft = envelope.adjustmentDraft ?: return@locked AdjustmentResult.Rejected("workspace unavailable")
        if (envelope.transaction != null || first != second || !matches(draft, first, envelope.durableEpoch) ||
            first.geometry != draft.entrySnapshot
        ) return@locked AdjustmentResult.Rejected("entry geometry is not verified")
        update { it.copy(adjustmentDraft = null) }
        AdjustmentResult.Ready(draft.copy(
            localDraft = draft.entrySnapshot,
            operationId = null,
            dirty = false,
            applyState = AdjustmentApplyState.FAILED_RESTORED,
            validationError = null,
        ))
    }

    /** Process recreation is read-only. Stale identity freezes the draft and never replays Apply. */
    fun rehydrate(observed: ObservedState): AdjustmentResult = mutateDraft { envelope, draft ->
        if (matches(draft, observed, envelope.durableEpoch)) ready(draft)
        else frozen(draft, "target or epoch changed during recreation")
    }

    private fun matches(draft: AdjustmentDraft, observed: ObservedState, epoch: Long): Boolean =
        draft.durableEpoch == epoch &&
            draft.target == observed.target &&
            draft.expectedDisplayIdentity == observed.displayIdentity

    private fun mutateDraft(
        transform: (CastSessionEnvelope, AdjustmentDraft) -> AdjustmentResult,
    ): AdjustmentResult = store.locked {
        val envelope = loaded() ?: return@locked AdjustmentResult.Rejected("durable store unavailable")
        val draft = envelope.adjustmentDraft ?: return@locked AdjustmentResult.Rejected("workspace unavailable")
        when (val result = transform(envelope, draft)) {
            is AdjustmentResult.Rejected -> result
            is AdjustmentResult.Ready -> {
                update { it.copy(adjustmentDraft = result.draft) }
                result
            }
        }
    }

    private fun CastSessionStore.Locked.loaded(): CastSessionEnvelope? =
        (read() as? StoreRead.Loaded)?.envelope

    private fun ready(draft: AdjustmentDraft) = AdjustmentResult.Ready(draft)
    private fun rejected(reason: String) = AdjustmentResult.Rejected(reason)
    private fun frozen(draft: AdjustmentDraft, reason: String) = ready(draft.copy(
        operationId = null,
        applyState = AdjustmentApplyState.FROZEN,
        validationError = reason,
    ))

    private fun StableCastSession.copyAcceptedGeometry(value: AcceptedGeometry) = StableCastSession(
        state, engineVersion, createdByBuild, profileExport, expectedDisplayIdentity, baseline,
        activeTarget, protectedResidue, value, lastVerifiedAtEpochMillis,
    )
}

package com.byd.clusternav.modules.clustercast.v2

import java.time.Instant

/** Pure Android-facing projection adapter; UI surfaces never interpret durable fields independently. */
object CastRuntimeUi {
    fun render(
        storeRead: StoreRead,
        observation: ObservationValue<ObservedState>,
        now: Instant = Instant.now(),
        stopRequestedAt: Instant? = null,
        destructiveRecoveryEligible: Boolean? = null,
        phoneSessionConnected: Boolean? = null,
    ): CastRenderModel {
        val loaded = storeRead as? StoreRead.Loaded
        val envelope = loaded?.envelope
        val coldPristine = isColdPristine(storeRead, observation)
        val observed = (observation as? ObservationValue.Known)?.value
        val tx = envelope?.transaction
        val stable = envelope?.stableSession
        val transactionRecovery = tx?.takeIf { it.phase == OperationPhase.RECOVERING }?.let {
            when {
                it.compensationUsed -> RecoverySubstate.COMPENSATION_EXHAUSTED
                envelope.stopRequested -> RecoverySubstate.UNKNOWN_EFFECT_STOP_REQUESTED
                else -> RecoverySubstate.UNKNOWN_EFFECT_STOP_AVAILABLE
            }
        }
        val profileUnsupported = observation is ObservationValue.Unsupported &&
            observation.reason.contains("profile", ignoreCase = true)
        val recovery = transactionRecovery ?: when {
            profileUnsupported && stable?.state == StableState.IDLE_VERIFIED ->
                RecoverySubstate.UNSUPPORTED_PROFILE_IDLE
            profileUnsupported && stable?.state != null ->
                RecoverySubstate.UNSUPPORTED_PROFILE_ACTIVE_UNKNOWN
            stable?.state != StableState.RECOVERY_PENDING -> null
            phoneSessionConnected == true -> RecoverySubstate.PROTECTED_SINK_CONNECTED
            phoneSessionConnected == false && destructiveRecoveryEligible == true ->
                RecoverySubstate.DISCONNECTED_SINK_ELIGIBLE
            else -> null
        }
        val transaction = tx?.takeUnless { it.phase == OperationPhase.RECOVERING }?.let {
            val stopping = envelope.stopRequested || it.operation == CastOperation.STOP
            PlannerUiProjection(
                phase = if (stopping) OperationPhase.STOP_REQUESTED else it.phase,
                operationId = it.operationId,
                deadlineAt = Instant.ofEpochMilli(it.deadlineAtEpochMillis),
                stopDisposition = StopDisposition(if (stopping) StopDispositionKind.REQUESTED else StopDispositionKind.AVAILABLE),
                nextSafeAction = if (stopping) NextSafeAction.WAIT_AND_OBSERVE else NextSafeAction.REQUEST_STOP,
                allowedActions = when {
                    stopping -> emptySet()
                    it.operation == CastOperation.BOOTSTRAP ->
                        setOf(CastAction.STOP, CastAction.CHOOSE_ANOTHER_APP)
                    else -> setOf(CastAction.STOP)
                },
            )
        }
        val state = CastUiStateProjector.project(
            CastProjectionInput(
                decodeValid = loaded != null,
                engineVersion = stable?.engineVersion ?: envelope?.effectiveUiVersion,
                observedNonIdle = observed?.coarseState?.let { it != ObservedCoarseState.IDLE_CLEAN } ?: false,
                stopRequested = envelope?.stopRequested ?: false,
                recoverySubstate = recovery,
                transaction = transaction,
                destructiveRecoveryEligible = if (recovery == null) destructiveRecoveryEligible else null,
                stableState = stable?.state,
                stableConverged = stable != null && tx == null && observed != null && when (stable.state) {
                    StableState.IDLE_VERIFIED -> observed.coarseState == ObservedCoarseState.IDLE_CLEAN &&
                        observed.displayIdentity == stable.expectedDisplayIdentity &&
                        observed.geometry == stable.baseline.geometry &&
                        observed.animationPerKey == stable.baseline.animationPerKey &&
                        observed.pipMode == stable.baseline.pipMode && observed.profile == stable.baseline.profile
                    StableState.ACTIVE_VERIFIED -> observed.coarseState == ObservedCoarseState.ACTIVE_SINGLE &&
                        observed.displayIdentity == stable.expectedDisplayIdentity &&
                        observed.target == stable.activeTarget && observed.protectedResidue == null &&
                        observed.geometry == stable.acceptedGeometry
                    StableState.ACTIVE_DEGRADED -> observed.coarseState == ObservedCoarseState.ACTIVE_MULTI &&
                        observed.displayIdentity == stable.expectedDisplayIdentity &&
                        observed.target == stable.activeTarget &&
                        observed.protectedResidue == stable.protectedResidue &&
                        observed.geometry == stable.acceptedGeometry
                    else -> false
                },
                interactionContext = InteractionContext(
                    InteractionContextValue.UNKNOWN,
                    "android-runtime",
                    now,
                    now,
                    "interaction context is diagnostic only",
                ),
                target = stable?.activeTarget,
                protectedResidue = stable?.protectedResidue,
                acceptedGeometry = stable?.acceptedGeometry,
                durableEpoch = envelope?.durableEpoch ?: -1,
                now = now,
                coldPristine = coldPristine,
                automationDisposition = envelope?.let {
                    CastAutomationPolicy.disposition(
                        it.automationConfig, it.bootAutomationRequest, it.lastAutomationOutcome, it.bootId,
                    )
                },
                automationReason = envelope?.bootAutomationRequest?.takeIf { it.bootId == envelope.bootId }?.reason
                    ?: envelope?.lastAutomationOutcome?.takeIf { it.bootId == envelope.bootId }?.reason,
                automationTargetPackage = envelope
                    ?.bootAutomationRequest?.takeIf { it.bootId == envelope.bootId }?.targetPackage
                    ?: envelope?.automationConfig?.defaultPackage?.takeIf { envelope.automationConfig.armable },
            ),
        )
        return CastUiRenderer.render(state, now, stopRequestedAt)
    }

    /** Actionable UI classification only; it never claims a durable stable state. */
    fun isColdPristine(
        storeRead: StoreRead,
        observation: ObservationValue<ObservedState>,
    ): Boolean {
        val envelope = (storeRead as? StoreRead.Loaded)?.envelope ?: return false
        if (observation !is ObservationValue.Unknown ||
            observation.reason != MISSING_NAMED_CLUSTER_DISPLAY_REASON
        ) return false
        val rollout = CastRolloutRegistry.resolve(envelope, CastRolloutRegistry.vehicleTestCandidate)
        return envelope.schemaVersion == CAST_ENVELOPE_SCHEMA_VERSION &&
            envelope.durableEpoch == 0L && envelope.effectiveUiVersion == EngineVersion.V2 &&
            !envelope.stopRequested && envelope.pendingIntent == null && !envelope.pendingUiRollback &&
            envelope.stableSession == null && envelope.transaction == null && envelope.adjustmentDraft == null &&
            rollout.effectiveUiVersion == EngineVersion.V2 && rollout.actionOwner == null
    }
}

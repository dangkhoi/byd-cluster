package com.byd.clusternav.modules.clustercast.v2

import android.content.Context
import android.provider.Settings

sealed interface CastLifecycleResult {
    data class Ready(val envelope: CastSessionEnvelope) : CastLifecycleResult
    data class ReadOnlyLegacy(val envelope: CastSessionEnvelope) : CastLifecycleResult
    data class Unavailable(val reason: String) : CastLifecycleResult
}

object CastLifecycleMigration {
    fun migratePristine(
        envelope: CastSessionEnvelope,
        observed: ObservationValue<ObservedState>,
        verifiedAtEpochMillis: Long,
    ): CastSessionEnvelope? {
        if (envelope.transaction != null || envelope.stableSession != null) return null
        val rollout = CastRolloutRegistry.resolve(envelope, CastRolloutRegistry.vehicleTestCandidate)
        if (rollout.effectiveUiVersion != EngineVersion.V2 || rollout.actionOwner != null) return null
        val state = (observed as? ObservationValue.Known)?.value ?: return null
        // A genuinely unknown observation is handled by the Unknown branch of the caller.
        if (state.coarseState == ObservedCoarseState.UNKNOWN) return null
        // Measured on DiLink3 2026-07-26: only IDLE_CLEAN used to be adopted here, so a first run —
        // or any run after the app's data was cleared — while ANYTHING occupied the cluster fell
        // through to read-only legacy mode. That renders as "Cần xử lý thủ công · contract unmapped"
        // with every control disabled, and it never recovers: not after the cluster is freed, not
        // after another data clear. Adopting the observation as a reclaimable session keeps the
        // claim truthful (we do not pretend the cluster is idle or verified) while leaving Stop
        // available, which is the one action that can legitimately resolve it.
        if (state.coarseState != ObservedCoarseState.IDLE_CLEAN) {
            return envelope.copy(
                effectiveUiVersion = EngineVersion.V2,
                stableSession = StableCastSession(
                    StableState.RECOVERY_PENDING,
                    EngineVersion.V2,
                    "runtime-migration-unowned",
                    null,
                    state.displayIdentity ?: "unresolved",
                    CastBaseline(
                        geometry = state.geometry,
                        animationPerKey = state.animationPerKey,
                        pipMode = state.pipMode,
                        profile = state.profile,
                    ),
                    state.target,
                    state.protectedResidue,
                    null,
                    verifiedAtEpochMillis,
                ),
            )
        }
        return envelope.copy(
            effectiveUiVersion = EngineVersion.V2,
            stableSession = StableCastSession(
                StableState.IDLE_VERIFIED,
                EngineVersion.V2,
                "runtime-migration",
                null,
                state.displayIdentity ?: "unresolved",
                CastBaseline(
                    geometry = state.geometry,
                    animationPerKey = state.animationPerKey,
                    pipMode = state.pipMode,
                    profile = state.profile,
                ),
                null,
                null,
                state.geometry,
                verifiedAtEpochMillis,
            ),
        )
    }

    fun rehydrateExisting(
        envelope: CastSessionEnvelope,
        newBootId: String,
        first: ObservationValue<ObservedState>,
        second: ObservationValue<ObservedState>,
        verifiedAtEpochMillis: Long,
    ): CastSessionEnvelope {
        if (envelope.bootId == newBootId) return envelope
        val tx = envelope.transaction
        if (tx != null) {
            return envelope.copy(
                bootId = newBootId,
                transaction = CastTransaction(
                    tx.operationId, tx.epoch, tx.operation, OperationPhase.RECOVERING,
                    tx.sourcePkg, tx.targetPkg, tx.targetClass, tx.expectedDisplayIdentity,
                    tx.baseline, tx.ledger, tx.retries, tx.deadlineAtEpochMillis,
                    "boot changed; re-observation required", tx.expectedPostcondition,
                    tx.compensationUsed, tx.requestedGeometry,
                ),
            )
        }
        return revalidateStable(envelope.copy(bootId = newBootId), first, second, verifiedAtEpochMillis)
    }

    fun revalidateStable(
        envelope: CastSessionEnvelope,
        first: ObservationValue<ObservedState>,
        second: ObservationValue<ObservedState>,
        verifiedAtEpochMillis: Long,
    ): CastSessionEnvelope {
        if (envelope.transaction != null) return envelope
        val stable = envelope.stableSession ?: return envelope
        val a = (first as? ObservationValue.Known)?.value
        val b = (second as? ObservationValue.Known)?.value
        val converged = a != null && a == b && when (stable.state) {
            StableState.IDLE_VERIFIED -> a.coarseState == ObservedCoarseState.IDLE_CLEAN &&
                a.displayIdentity == stable.expectedDisplayIdentity && a.geometry == stable.baseline.geometry &&
                a.animationPerKey == stable.baseline.animationPerKey &&
                a.pipMode == stable.baseline.pipMode && a.profile == stable.baseline.profile
            StableState.ACTIVE_VERIFIED, StableState.ACTIVE_DEGRADED ->
                a.displayIdentity == stable.expectedDisplayIdentity &&
                    a.target == stable.activeTarget && a.geometry == stable.acceptedGeometry
            else -> false
        }
        return envelope.copy(stableSession = stable.copy(
            state = if (converged) stable.state else StableState.RECOVERY_PENDING,
            lastVerifiedAtEpochMillis = if (converged) verifiedAtEpochMillis else stable.lastVerifiedAtEpochMillis,
        ))
    }
}

object CastAndroidLifecycle {
    /** Same-boot wake/watchdog check. Read-only observation; no transaction is replayed. */
    fun revalidate(context: Context): CastLifecycleResult {
        val runtime = CastAndroidRuntime.create(context.applicationContext)
        return try {
            val bootId = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0).toString()
            val envelope = runtime.coordinator.initialize(bootId)
            if (envelope.bootId != bootId) return rehydrate(context)
            val transaction = envelope.transaction
            if (transaction != null) {
                val first = runtime.coordinator.observe(transaction.deadlineAtEpochMillis)
                val second = runtime.coordinator.observe(transaction.deadlineAtEpochMillis)
                if (transaction.phase == OperationPhase.VERIFYING) {
                    (first as? ObservationValue.Known)?.value?.let {
                        runtime.coordinator.completeVerification(transaction.operationId, it)
                    }
                    (second as? ObservationValue.Known)?.value?.let {
                        runtime.coordinator.completeVerification(transaction.operationId, it)
                    }
                }
                val current = runtime.store.locked { (read() as? StoreRead.Loaded)?.envelope }
                    ?: envelope
                return CastLifecycleResult.Ready(current)
            }
            if (envelope.stableSession == null) return CastLifecycleResult.Ready(envelope)
            val first = runtime.coordinator.observe()
            val second = runtime.coordinator.observe()
            val updated = CastLifecycleMigration.revalidateStable(
                envelope, first, second, System.currentTimeMillis(),
            )
            val persisted = runtime.store.locked { update { updated } }
            if (vanished(persisted, first, second)) {
                CastOperationLog.record("watchdog: cast target vanished from the cluster → restoring gauges")
                restoreVanishedCluster(context, runtime, persisted)
                val restored = runtime.store.locked { (read() as? StoreRead.Loaded)?.envelope } ?: persisted
                return CastLifecycleResult.Ready(restored)
            }
            CastLifecycleResult.Ready(persisted)
        } catch (failure: Exception) {
            CastLifecycleResult.Unavailable(failure.message ?: failure.javaClass.simpleName)
        }
    }

    /**
     * V1's watchdog, kept restorative only: the app that was cast is gone, the cluster display is
     * empty, and the gauges would stay missing until somebody opened the app. Two identical clean
     * observations of the expected display are required, and the only action taken is the canonical
     * Stop that returns the cluster to its journaled baseline.
     */
    private fun vanished(
        envelope: CastSessionEnvelope,
        first: ObservationValue<ObservedState>,
        second: ObservationValue<ObservedState>,
    ): Boolean {
        if (envelope.transaction != null || envelope.stopRequested) return false
        val stable = envelope.stableSession ?: return false
        if (stable.activeTarget == null) return false
        if (stable.state !in setOf(StableState.ACTIVE_VERIFIED, StableState.ACTIVE_DEGRADED, StableState.RECOVERY_PENDING)) {
            return false
        }
        val a = (first as? ObservationValue.Known)?.value ?: return false
        val b = (second as? ObservationValue.Known)?.value ?: return false
        if (a != b) return false
        return a.displayIdentity == stable.expectedDisplayIdentity &&
            a.coarseState == ObservedCoarseState.IDLE_CLEAN && a.target == null &&
            a.occupants.isEmpty() && a.protectedResidue == null
    }

    private fun restoreVanishedCluster(
        context: Context,
        runtime: CastAndroidRuntime.Runtime,
        envelope: CastSessionEnvelope,
    ) {
        val pkg = envelope.stableSession?.activeTarget?.packageName ?: return
        if (runtime.coordinator.requestStop() == null) return
        // The target is already gone from the cluster, so no return-to-main step may run: relaunching
        // it on the centre screen would be a surprise mutation. Unknown-protected evidence plans the
        // restore-only Stop (PIP, animation, display reset) without touching the vanished app.
        val plan = runtime.coordinator.plan(
            CastIntent(CastIntentKind.STOP, pkg),
            TargetEvidence(projectionComponent = null, connectedPhoneSession = null, userProtected = false),
            installed = true,
            hasLauncher = true,
        )
        val result = runtime.coordinator.execute(plan, pkg)
        if (result is ExecutionResult.AwaitingVerification) {
            repeat(2) {
                (runtime.coordinator.observe() as? ObservationValue.Known)?.value?.let { observed ->
                    runtime.coordinator.completeVerification(result.operationId, observed)
                }
            }
        }
        CastOperationLog.record("watchdog restore → $result")
    }

    /**
     * Rehydrates durable state only. A pristine, observed-clean install is migrated to an idle V2
     * boundary. Active/unknown legacy truth is retained read-only; transactions are never replayed.
     */
    fun rehydrate(context: Context): CastLifecycleResult {
        val runtime = CastAndroidRuntime.create(context.applicationContext)
        return try {
            val bootId = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0).toString()
            val initialized = runtime.coordinator.initialize(bootId)
            if (initialized.transaction != null || initialized.stableSession != null) {
                if (initialized.bootId == bootId) {
                    CastLifecycleResult.Ready(initialized)
                } else {
                    val first = runtime.coordinator.observe()
                    val second = runtime.coordinator.observe()
                    val fenced = CastLifecycleMigration.rehydrateExisting(
                        initialized, bootId, first, second, System.currentTimeMillis(),
                    )
                    val persisted = runtime.store.locked { update { fenced } }
                    CastLifecycleResult.Ready(persisted)
                }
            } else {
                val observed = runtime.coordinator.observe()
                val migrated = CastLifecycleMigration.migratePristine(
                    initialized,
                    observed,
                    System.currentTimeMillis(),
                )
                when {
                    migrated != null -> {
                        val persisted = runtime.store.locked { update { migrated } }
                        CastLifecycleResult.Ready(persisted)
                    }
                    observed is ObservationValue.Known -> CastLifecycleResult.ReadOnlyLegacy(initialized)
                    observed is ObservationValue.Unknown -> CastLifecycleResult.Unavailable(observed.reason)
                    observed is ObservationValue.Unsupported -> CastLifecycleResult.Unavailable(observed.reason)
                    else -> CastLifecycleResult.Unavailable("unmapped observation")
                }
            }
        } catch (failure: Exception) {
            CastLifecycleResult.Unavailable(failure.message ?: failure.javaClass.simpleName)
        }
    }
}

package com.byd.clusternav.modules.clustercast.v2

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap


private val ANDROID_PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
/** V2 owner facade. Constructing it has no mutation; runtime wiring remains separately controlled. */
class CastCoordinator(
    private val store: CastSessionStore,
    private val observation: ObservedStateReader,
    private val executor: CastExecutor,
    private val recovery: CastRecovery,
    private val now: () -> Instant = Instant::now,
    private val manualSleeper: CastSleeper = CastSleeper(Thread::sleep),
    private val manualVerificationDelayMillis: Long = 500L,
) {
    private val verificationSamples = ConcurrentHashMap<java.util.UUID, ObservedState>()
    fun initialize(bootId: String): CastSessionEnvelope = store.locked { initialize(bootId) }

    fun applyRollout(flags: CastRolloutFlags): CastSessionEnvelope = store.locked {
        update { CastRolloutRegistry.apply(it, flags) }
    }

    /** Persist Stop intent and bump the cancellation epoch before waiting for any mutation lease. */

    fun queueLatestTarget(packageName: String): Boolean {
        require(ANDROID_PACKAGE.matches(packageName)) { "Invalid Android package name" }
        return store.locked {
            val loaded = read() as? StoreRead.Loaded ?: return@locked false
            if (loaded.envelope.stopRequested) return@locked false
            val replaceable = loaded.envelope.transaction != null ||
                (loaded.envelope.pendingIntent != null && loaded.envelope.stableSession != null)
            if (!replaceable) return@locked false
            update { it.withPendingPackage(packageName) }
            true
        }
    }

    fun requestStop(): CastSessionEnvelope? {
        val accepted = store.locked {
            val loaded = read() as? StoreRead.Loaded ?: return@locked null
            if (loaded.envelope.stopRequested) return@locked loaded.envelope
            bumpEpoch { envelope -> envelope.copy(stopRequested = true, pendingIntent = null) }
        } ?: return null
        executor.fenceInFlight()
        return accepted
    }

    fun plan(intent: CastIntent, targetEvidence: TargetEvidence, installed: Boolean, hasLauncher: Boolean): PlanResult {
        val envelope = store.locked { (read() as? StoreRead.Loaded)?.envelope }
            ?: return PlanResult.Blocked("durable store unavailable")
        if (envelope.transaction != null) return PlanResult.Blocked("operation already active")
        if (envelope.stopRequested && intent.kind != CastIntentKind.STOP) {
            return PlanResult.Blocked("Stop requested; new mutation intents are fenced")
        }
        return CastPlanner.plan(
            intent,
            PlannerSnapshot(
                observed = observation.read(),
                stableSession = envelope.stableSession,
                targetClass = CastPolicy.classify(targetEvidence),
                installed = installed,
                hasLauncher = hasLauncher,
                plannerEpoch = envelope.durableEpoch,
            ),
        )
    }

    fun execute(result: PlanResult, targetPackage: String?): ExecutionResult = when (result) {
        is PlanResult.Blocked -> ExecutionResult.Blocked(result.reason)
        is PlanResult.Ready -> {
            val baseline = store.locked {
                val envelope = (read() as? StoreRead.Loaded)?.envelope
                val stable = envelope?.stableSession
                if (result.plan.operation == CastOperation.APPLY_GEOMETRY && stable != null) {
                    CastBaseline(
                        stable.baseline.occupants,
                        stable.acceptedGeometry ?: stable.baseline.geometry,
                        stable.baseline.animationPerKey,
                        stable.baseline.pipMode,
                        stable.baseline.profile,
                    )
                } else stable?.baseline ?: CastBaseline()
            }
            executor.execute(result.plan, baseline, targetPackage)
        }
    }

    fun observeAndComplete(operationId: java.util.UUID): Boolean = executor.withMutationLease {
        val first = observation.read() as? ObservationValue.Known
        if (first == null) {
            resetVerificationSampleLocked(operationId)
            return@withMutationLease false
        }
        if (completeVerificationLocked(operationId, first.value)) return@withMutationLease true
        val second = observation.read() as? ObservationValue.Known
        if (second == null) {
            resetVerificationSampleLocked(operationId)
            return@withMutationLease false
        }
        val completed = completeVerificationLocked(operationId, second.value)
        if (!completed) resetVerificationSampleLocked(operationId)
        completed
    }

    fun observe(): ObservationValue<ObservedState> = observation.read()

    fun observe(deadlineAtEpochMillis: Long): ObservationValue<ObservedState> =
        observation.read(deadlineAtEpochMillis)

    fun bootstrap(
        facts: CastVehicleFacts,
        style: ClusterStyle = ClusterStyle.CURVED,
    ): ColdBootstrapResult = executor.bootstrap(
        facts,
        { deadline -> observation.inspectRaw(deadline) },
        { deadline -> observation.read(deadline) },
        style,
    )

    fun runManualIntent(
        packageName: String,
        facts: CastVehicleFacts,
        targets: CastManualTargetReader,
        origin: CastIntentOrigin = CastIntentOrigin.USER,
        automationRequestId: java.util.UUID? = null,
        allowDestructive: Boolean = false,
        preferredDensityDpi: Int? = null,
        clusterStyle: ClusterStyle = ClusterStyle.CURVED,
    ): CastManualIntentResult {
        require((origin == CastIntentOrigin.BOOT_AUTO) == (automationRequestId != null)) {
            "automation request id exists exactly for BOOT_AUTO origin"
        }
        if (origin == CastIntentOrigin.BOOT_AUTO) {
            val envelope = store.locked { (read() as? StoreRead.Loaded)?.envelope }
                ?: return CastManualIntentResult.Blocked("durable store unavailable")
            if (envelope.stopRequested) {
                return CastManualIntentResult.Blocked("Stop requested; automation is fenced")
            }
            val request = envelope.bootAutomationRequest
            if (request == null || request.requestId != automationRequestId ||
                request.state != AutomationRequestState.CLAIMED
            ) {
                return CastManualIntentResult.Blocked("automation request is not claimed")
            }
            if (request.targetPackage != packageName) {
                return CastManualIntentResult.Blocked("automation target no longer matches the claim")
            }
            val pending = envelope.pendingIntent
            if (pending != null && !pending.matches(automationRequestId)) {
                return CastManualIntentResult.Blocked("pending placement belongs to another origin")
            }
        }
        return manualIntentRunner()
            .run(packageName, facts, targets, allowDestructive, preferredDensityDpi, clusterStyle)
    }

    fun resumePendingIntent(targets: CastManualTargetReader): CastManualIntentResult? =
        manualIntentRunner().resumePending(targets)

    private fun manualIntentRunner() = CastManualIntentRunner(
        store,
        observation,
        executor,
        ::completeVerification,
        ::resetVerificationSample,
        ::queueLatestTarget,
        manualSleeper,
        manualVerificationDelayMillis,
    )

    fun recover(): ExecutionResult = recovery.compensate()

    fun project(input: CastProjectionInput): CastUiStateV2 = CastUiStateProjector.project(input)

    fun completeVerification(operationId: java.util.UUID, observed: ObservedState): Boolean =
        executor.withMutationLease { completeVerificationLocked(operationId, observed) }

    private fun resetVerificationSample(operationId: java.util.UUID) =
        executor.withMutationLease { resetVerificationSampleLocked(operationId) }

    private fun resetVerificationSampleLocked(operationId: java.util.UUID) {
        verificationSamples.remove(operationId)
    }

    private fun completeVerificationLocked(operationId: java.util.UUID, observed: ObservedState): Boolean = store.locked {
        val loaded = read() as? StoreRead.Loaded ?: return@locked false
        val tx = loaded.envelope.transaction ?: return@locked false
        if (tx.operationId != operationId || tx.phase != OperationPhase.VERIFYING) return@locked false
        if (loaded.envelope.durableEpoch != tx.epoch ||
            (loaded.envelope.stopRequested && tx.operation !in setOf(CastOperation.STOP, CastOperation.RECOVER))
        ) {
            verificationSamples.remove(operationId)
            update { envelope -> envelope.copy(transaction = tx.copyForRecovery("verification fenced by Stop or epoch change")) }
            return@locked false
        }
        if (now().toEpochMilli() >= tx.deadlineAtEpochMillis) {
            verificationSamples.remove(operationId)
            update { envelope -> envelope.copy(transaction = tx.copyForRecovery("verification deadline exceeded")) }
            return@locked false
        }
        val targetMatches = tx.targetPkg == null || observed.target?.packageName == tx.targetPkg
        val displayMatches = tx.expectedDisplayIdentity != "unresolved" &&
            observed.displayIdentity == tx.expectedDisplayIdentity
        val geometryKnown = observed.geometry != null
        val terminal = when (tx.operation) {
            CastOperation.BOOTSTRAP -> CastColdBootstrapVerification.accepts(observed)
            CastOperation.STOP, CastOperation.RECOVER ->
                observed.coarseState == ObservedCoarseState.IDLE_CLEAN && displayMatches &&
                    observed.geometry == tx.baseline.geometry &&
                    observed.animationPerKey == tx.baseline.animationPerKey &&
                    observed.pipMode == tx.baseline.pipMode && observed.profile == tx.baseline.profile
            CastOperation.APPLY_GEOMETRY ->
                targetMatches && displayMatches && geometryKnown && tx.requestedGeometry != null &&
                    observed.geometry == tx.requestedGeometry &&
                    observed.coarseState == ObservedCoarseState.ACTIVE_SINGLE
            else -> targetMatches && displayMatches && geometryKnown &&
                (observed.coarseState == ObservedCoarseState.ACTIVE_SINGLE ||
                    (observed.coarseState == ObservedCoarseState.ACTIVE_MULTI && observed.protectedResidue != null))
        }
        if (!terminal) {
            verificationSamples.remove(operationId)
            update { envelope -> envelope.copy(transaction = tx.copyForRecovery("verification diverged")) }
            return@locked false
        }
        val previous = verificationSamples.put(operationId, observed)
        if (previous != observed) return@locked false
        verificationSamples.remove(operationId)
        val stableState = if (observed.protectedResidue == null) StableState.ACTIVE_VERIFIED else StableState.ACTIVE_DEGRADED
        val stable = when (tx.operation) {
            CastOperation.BOOTSTRAP -> StableCastSession(
                StableState.IDLE_VERIFIED, EngineVersion.V2, "runtime-bootstrap", "seal-dl3-cold-bootstrap-v1",
                checkNotNull(observed.displayIdentity),
                CastBaseline(emptySet(), observed.geometry, observed.animationPerKey, observed.pipMode, observed.profile),
                null, null, observed.geometry, now().toEpochMilli(),
            )
            CastOperation.STOP, CastOperation.RECOVER -> StableCastSession(
                StableState.IDLE_VERIFIED, EngineVersion.V2, "runtime", null,
                tx.expectedDisplayIdentity, tx.baseline, null, null, observed.geometry, now().toEpochMilli(),
            )
            CastOperation.APPLY_GEOMETRY -> {
                val prior = loaded.envelope.stableSession ?: return@locked false
                StableCastSession(
                    stableState, prior.engineVersion, prior.createdByBuild, prior.profileExport,
                    tx.expectedDisplayIdentity, prior.baseline, observed.target, observed.protectedResidue,
                    prior.acceptedGeometry, now().toEpochMilli(),
                )
            }
            else -> StableCastSession(
                stableState, EngineVersion.V2, "runtime", null,
                tx.expectedDisplayIdentity, tx.baseline, observed.target, observed.protectedResidue,
                observed.geometry, now().toEpochMilli(),
            )
        }
        update { envelope ->
            envelope.copy(
                stableSession = stable,
                transaction = null,
                stopRequested = if (tx.operation in setOf(CastOperation.STOP, CastOperation.RECOVER)) false else envelope.stopRequested,
            )
        }
        true
    }

    private fun CastTransaction.copyForRecovery(reason: String) = CastTransaction(
        operationId, epoch, operation, OperationPhase.RECOVERING, sourcePkg, targetPkg, targetClass,
        expectedDisplayIdentity, baseline, ledger, retries, deadlineAtEpochMillis, reason,
        expectedPostcondition, compensationUsed, requestedGeometry,
    )
}

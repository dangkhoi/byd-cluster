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

    /**
     * Closes an operation that was issued and never resolved, when the vehicle itself proves there is
     * nothing left to undo.
     *
     * Measured on the vehicle 2026-07-27: a CAST transaction sat at phase=RECOVERING with
     * place-keep-session at effect=ISSUED, durableEpoch=3 against tx.epoch=2, and stopRequested set.
     * Every one of those three independently disqualifies compensation in CastRecovery.decide, and
     * completeVerification only accepts phase=VERIFYING, so no path existed to end the operation. The
     * screen stayed READ_ONLY for good: no cast, no stop, not even app selection. A durable journal
     * must always have a way back to a truthful terminal state.
     *
     * This is not an assumption that the operation failed. It requires a fresh Known observation
     * showing the cluster idle with no occupants and no target, and requires that no observed step
     * still owes a compensation. Under those two proofs the world is already at baseline, so the only
     * honest record is "operation ended, nothing left behind". The epoch is bumped so a late gateway
     * reply from the abandoned operation cannot be mistaken for a live one.
     */
    fun reconcileAbandoned(): Boolean = executor.tryMutationLease {
        val transaction = (store.locked { read() } as? StoreRead.Loaded)?.envelope?.transaction
            ?: return@tryMutationLease false
        if (transaction.phase != OperationPhase.RECOVERING) return@tryMutationLease false
        val id = transaction.operationId

        // RETRACTED 2026-07-27 by owner correction: an earlier version of this function adopted a
        // stuck transaction as a success when the observation showed ACTIVE_SINGLE with the expected
        // target on display-1. The owner confirmed the physical cluster never displayed that app at any
        // point, and the captured fixture agrees: the task was parented to the cluster's virtual
        // display while the driver saw the native gauges throughout. Window-manager placement is
        // therefore not evidence of a cast, and no success may be declared from it. See
        // docs/refactor-car-execution/spec.html Q1/Q7.
        //
        // What remains is the conservative half: an operation may be closed only when the vehicle shows
        // nothing left to undo. Note that this half rests on the same window-manager reading and is
        // itself under review as Q8.
        val second = (observation.read() as? ObservationValue.Known)?.value
            ?: return@tryMutationLease false
        if (second.coarseState != ObservedCoarseState.IDLE_CLEAN ||
            second.occupants.isNotEmpty() ||
            second.target != null ||
            second.protectedResidue != null
        ) {
            return@tryMutationLease false
        }
        if (transaction.ledger.any {
                it.effect == LedgerEffect.OBSERVED && it.compensation != null && !it.compensationObserved
            }
        ) {
            return@tryMutationLease false
        }
        verificationSamples.remove(id)
        // Deliberately does NOT clear stopRequested. "IDLE_CLEAN" is CastAmStackParser proof that the
        // window-manager layer holds nothing on the cluster display — it is NOT proof the OEM
        // AutoContainer projection was ever told to close. CastPlanner.kt's own comment on the STOP
        // ladder (measured on the vehicle 2026-07-26) records that the OEM keeps mirroring the last
        // cluster frame until the close-projection opcodes run. Before this fix, a driver who tapped
        // Stop while an unrelated transaction was stuck in RECOVERING could have that tap silently
        // discarded the moment this reconciler ran: it cleared stopRequested as a side effect of
        // closing the abandoned transaction, with SEAL_DL3_COMPENSATE_18/0 never dispatched, so the
        // physical cluster could stay frozen on a stale frame while the app believed Stop was done.
        // Clearing the stuck transaction is still safe to do purely from observation, independent of
        // whether Stop was requested — bookkeeping cleanup and Stop fulfillment are different facts and
        // must not be conflated into one assignment. If stopRequested is true, it stays true: the next
        // Stop attempt now finds transaction == null and runs the real teardown, including the
        // close-projection opcodes, instead of finding nothing left to do.
        store.locked { bumpEpoch { envelope -> envelope.copy(transaction = null) } }
        CastOperationLog.record(
            "closed abandoned ${transaction.operation} ($id): cluster observed idle, no compensation owing",
        )
        true
    } ?: false

    /**
     * Clears a durable idle claim that this boot can never re-verify, so the next refresh is free to
     * bootstrap fresh instead of dead-ending forever.
     *
     * Measured on the vehicle 2026-07-29 after a real ignition power cycle: the cluster's virtual
     * display is WindowManager-level only and never survives a reboot, but `stableSession` (an
     * `IDLE_VERIFIED` claim recorded before the reboot) is deliberately preserved across a boot change
     * by `CastSessionStore`/`CastLifecycleMigration` so an unrelated transaction can still be diagnosed.
     * Nothing, however, ever re-verifies a *non-transaction* idle claim from the Activity's own refresh
     * path — only the boot-receiver/watchdog path does, via `CastLifecycleMigration.revalidateStable`.
     * The result was permanent `MANUAL_REQUIRED` with only read-only Diagnostics, on every single boot
     * after the first successful cast of the app's life, for every app, not just the one that was
     * active at reboot time.
     *
     * This is deliberately narrower than `revalidateStable`: it only ever fires when nothing was
     * active (`IDLE_VERIFIED`, so there is no target/session to protect or restore) and the cluster
     * display cannot be found AT ALL (`Unknown(MISSING_NAMED_CLUSTER_DISPLAY_REASON)`), never for a
     * `Known`-but-mismatched observation, which may mean something unexpected really is on the
     * display and must stay conservative. `ACTIVE_VERIFIED`/`ACTIVE_DEGRADED` after a vanished display
     * already has a correct, narrower mechanism (`CastAndroidLifecycle.vanished`/
     * `restoreVanishedCluster`) that this function must not duplicate or race with.
     *
     * The last gate is the strictest one: the claim is dropped ONLY when dropping it is by itself
     * enough to reach cold-pristine, asked of [CastRuntimeUi.isColdPristine] against the exact
     * envelope this write would produce. Added in review 2026-07-29 after two ways the unguarded
     * version made things worse rather than better. A durable Stop, a pending placement, an open
     * geometry draft or a pending UI rollback each independently keep `isColdPristine` false, so
     * clearing under any of them destroyed the only record of what the cluster used to be while
     * leaving the screen exactly as stuck as before — and with `stopRequested` set it produced a NEW
     * dead end: `CastRolloutRegistry.resolve` reports no action owner once nothing is recorded, so
     * `CastFacade.v2OwnsActions` turns false and the pending Stop could never be dispatched to clear
     * itself. Refusing here is not a regression for those states: they were already blocked, they
     * keep their evidence, and the next refresh retries this reconciler once the blocker is gone.
     */
    fun reconcileUnobservableIdleSession(): Boolean = executor.tryMutationLease {
        val envelope = (store.locked { read() } as? StoreRead.Loaded)?.envelope
            ?: return@tryMutationLease false
        if (envelope.transaction != null) return@tryMutationLease false
        val stable = envelope.stableSession ?: return@tryMutationLease false
        if (stable.state != StableState.IDLE_VERIFIED) return@tryMutationLease false
        val current = observation.read()
        if (current !is ObservationValue.Unknown || current.reason != MISSING_NAMED_CLUSTER_DISPLAY_REASON) {
            return@tryMutationLease false
        }
        // The projected envelope deliberately keeps the current epoch: isColdPristine stopped reading
        // durableEpoch on 2026-07-29, for exactly the reason this function exists. Should an epoch
        // condition ever be reintroduced there, this projection has to bump too or it will lie.
        if (!CastRuntimeUi.isColdPristine(StoreRead.Loaded(envelope.copy(stableSession = null)), current)) {
            return@tryMutationLease false
        }
        store.locked { bumpEpoch { it.copy(stableSession = null) } }
        CastOperationLog.record(
            "cleared unobservable idle stable session: cluster display not found, nothing was active, safe to re-bootstrap",
        )
        true
    } ?: false

    fun observe(): ObservationValue<ObservedState> = observation.read()

    fun observe(deadlineAtEpochMillis: Long): ObservationValue<ObservedState> =
        observation.read(deadlineAtEpochMillis)

    /** Raw dumpsys/am text this boundary already fetches for every observation, before parsing. */
    fun inspectRaw(deadlineAtEpochMillis: Long = Long.MAX_VALUE): ObservationValue<RawObservation> =
        observation.inspectRaw(deadlineAtEpochMillis)

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
            // Review 2026-07-30 (vòng 3, docs/specs/cast-simplified-active-app-toggle.html). Boot
            // automation never takes the cluster away from a placement that is ALREADY on it.
            //
            // Every other precedence rule in this block compares automation against *durable
            // configuration* identity, and `CastAutomationPolicy.claimAllowed` does the same
            // (revision/consent/default equality). None of that moves when the driver casts something
            // by hand, so nothing here refused a claim whose cluster was already occupied — and
            // `CastManualIntentRunner.executeOrdinary` reads `stable.activeTarget != null` as
            // `CastIntentKind.SWITCH`, i.e. it would replace what the driver just chose with the
            // configured default. That is reachable inside ONE boot: `CastAutomationService`
            // .deferForPriorJournal re-arms itself REVALIDATION_DELAY_MS (45 s) later when a prior
            // journal was still open at first evaluation, and 45 s is more than enough for a bubble
            // tap while driving.
            //
            // R6 already states the rule for the interactive auto-start ("không cướp phiên", enforced
            // by `autoStartTarget`); this is the same rule for R7. This read is a cheap early exit —
            // the binding one runs inside the mutation lease, via the `automation` flag below, because
            // everything read here can still move before the runner acquires that lease.
            if (envelope.stableSession?.activeTarget != null) {
                return CastManualIntentResult.Blocked(LIVE_SESSION_REFUSAL)
            }
        }
        return manualIntentRunner().run(
            packageName, facts, targets, allowDestructive, preferredDensityDpi, clusterStyle,
            automation = origin == CastIntentOrigin.BOOT_AUTO,
        )
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

    companion object {
        /** Cùng một câu cho cả cửa sớm ở đây lẫn cửa ràng buộc trong lease — không viết hai bản chữ. */
        internal const val LIVE_SESSION_REFUSAL =
            "cluster already holds an active target; automation never supersedes a live session"
    }
}

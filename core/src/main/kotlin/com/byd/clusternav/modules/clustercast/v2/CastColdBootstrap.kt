package com.byd.clusternav.modules.clustercast.v2

import java.util.UUID

private const val BOOTSTRAP_PROFILE_EXPORT = "seal-dl3-cold-bootstrap-v1"
private const val BOOTSTRAP_PENDING_DISPLAY = "bootstrap-pending"
private val DISPLAY_ID = Regex("mDisplayId\\s*=\\s*([^\\s,}]+)", RegexOption.IGNORE_CASE)
private val REQUIRED_ANIMATIONS = listOf(
    "window_animation_scale", "transition_animation_scale", "animator_duration_scale",
)

/** Exact immutable build facts supplied by the later Android wiring stage. */
data class CastVehicleFacts(
    val sdk: Int,
    val manufacturer: String,
    val brand: String,
    val product: String,
    val device: String,
    val buildProduct: String,
)

object SealDl3BootstrapProfile {
    val exactFacts = CastVehicleFacts(29, "BYD AUTO", "BYD-AUTO", "DiLink3.0", "DiLink3.0", "DiLink3.0")
    val forwardKinds = listOf(
        CommandKind.SEAL_DL3_BOOTSTRAP_30,
        CommandKind.SEAL_DL3_BOOTSTRAP_16,
        CommandKind.SEAL_DL3_BOOTSTRAP_35,
    )
    val compensationKinds = listOf(
        CommandKind.SEAL_DL3_COMPENSATE_18,
        CommandKind.SEAL_DL3_COMPENSATE_0,
    )
    /** Only the style opcode differs: 30 keeps the km/h gauge, 31 hands over the full pane. */
    fun forwardKindsFor(style: ClusterStyle): List<CommandKind> = when (style) {
        ClusterStyle.CURVED -> forwardKinds
        ClusterStyle.RECT -> listOf(
            CommandKind.SEAL_DL3_BOOTSTRAP_31,
            CommandKind.SEAL_DL3_BOOTSTRAP_16,
            CommandKind.SEAL_DL3_BOOTSTRAP_35,
        )
    }

    val styleKinds: Set<CommandKind> =
        setOf(CommandKind.SEAL_DL3_BOOTSTRAP_30, CommandKind.SEAL_DL3_BOOTSTRAP_31)

    fun eligible(facts: CastVehicleFacts): Boolean = facts == exactFacts
}

fun interface CastSleeper { fun sleep(millis: Long) }

sealed interface ColdBootstrapResult {
    data class Succeeded(val stableSession: StableCastSession) : ColdBootstrapResult
    data class Blocked(val reason: String) : ColdBootstrapResult
    data class RecoveryRequired(
        val operationId: UUID,
        val reason: String,
        val effectKnown: Boolean,
        val compensationAttempted: Boolean,
    ) : ColdBootstrapResult
}

sealed interface ColdBootstrapPreflight {
    data class Ready(val baseline: CastBaseline) : ColdBootstrapPreflight
    data class Blocked(val reason: String) : ColdBootstrapPreflight
}

object CastColdBootstrapPreflight {
    /**
     * `durableEpoch != 0L` dropped from the block list 2026-07-29: it used to be equivalent to
     * "stableSession/transaction were never set" only because nothing ever nulled `stableSession`
     * after the epoch moved past zero. `CastCoordinator.reconcileUnobservableIdleSession()` now does
     * exactly that for a stale idle claim this boot can never re-verify (its cluster display is
     * WindowManager-level and does not survive a reboot) — so a driver could clear that claim, see
     * CAST offered again, tap it, and still get blocked here with a stale epoch reason. The remaining
     * checks below (`stableSession`, `transaction`, `stopRequested`, `pendingIntent`,
     * `adjustmentDraft`, `pendingUiRollback`, plus `rollout.actionOwner == null` above) already fully
     * capture "nothing recorded, safe to bootstrap fresh" without needing the epoch itself to be zero.
     */
    fun inspect(
        envelope: CastSessionEnvelope,
        facts: CastVehicleFacts,
        raw: RawObservation,
        profileOverride: VehicleProfileId? = null,
    ): ColdBootstrapPreflight {
        val vehicleProfile = CastVehicleProfileCatalog.resolve(facts, profileOverride)
        if (!vehicleProfile.dispatchImplemented) return blocked(checkNotNull(vehicleProfile.blockedReason))
        val rollout = CastRolloutRegistry.resolve(envelope, CastRolloutRegistry.vehicleTestCandidate)
        if (envelope.effectiveUiVersion != EngineVersion.V2 || rollout.effectiveUiVersion != EngineVersion.V2 ||
            rollout.actionOwner != null
        ) return blocked("effective V2 with no action owner required")
        if (envelope.stableSession != null || envelope.transaction != null ||
            envelope.stopRequested || envelope.pendingIntent != null || envelope.adjustmentDraft != null ||
            envelope.pendingUiRollback
        ) return blocked("durable envelope is not pristine")

        val headers = when (val parsed = CastLogicalDisplayParser.parseHeaders(raw.displays)) {
            is CastDumpParse.Known -> parsed.value
            is CastDumpParse.Malformed -> return blocked(parsed.reason)
        }
        // 0.72: real vehicles also expose launcher-split and other logical displays. Only two facts
        // matter: the dump parses unambiguously, and the NAMED cluster display is either absent
        // (create it) or present and unoccupied (adopt it).
        if (headers.isEmpty() || 0 !in headers) {
            return blocked("display topology must contain logical Display 0")
        }
        val existingCluster = discoverClusterDisplayId(raw.displays)
        val displayIds = DISPLAY_ID.findAll(raw.displays).map { it.groupValues[1].toIntOrNull() }.toList()
        val displayIdMentions = Regex("mDisplayId", RegexOption.IGNORE_CASE).findAll(raw.displays).count()
        if (displayIds.isEmpty() || displayIds.size != displayIdMentions || displayIds.any { it == null }) {
            return blocked("display topology is ambiguous or malformed")
        }
        val wmIds = DISPLAY_ID.findAll(raw.wmDisplays).map { it.groupValues[1].toIntOrNull() }.toList()
        val wmIdMentions = Regex("mDisplayId", RegexOption.IGNORE_CASE).findAll(raw.wmDisplays).count()
        if (wmIds.isEmpty() || wmIds.size != wmIdMentions || wmIds.any { it == null }) {
            return blocked("window topology is ambiguous or malformed")
        }

        val am = when (val parsed = CastAmStackParser.parse(raw.amStacks)) {
            is CastDumpParse.Known -> parsed.value
            is CastDumpParse.Malformed -> return blocked(parsed.reason)
        }
        if (am.stacks.isEmpty() || am.tasks.isEmpty()) {
            return blocked("AM stack topology is empty or malformed")
        }
        // Adopting an already-created cluster display is only safe while nothing occupies it.
        if (existingCluster != null && am.tasks.any { it.displayId == existingCluster }) {
            return blocked("the cluster display already holds a task; stop the current cast first")
        }
        if (raw.appOps.isBlank()) return blocked("appops observation is blank")

        val userId = raw.profile.trim().toIntOrNull()?.takeIf { it >= 0 }
            ?: return blocked("active Android user is not parseable")
        val animationValues = raw.animations.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (animationValues.size != REQUIRED_ANIMATIONS.size || animationValues.any {
                it.toDoubleOrNull()?.takeIf(Double::isFinite)?.takeIf { value -> value >= 0.0 } == null
            }
        ) return blocked("exactly three parseable animation values are required")
        return ColdBootstrapPreflight.Ready(
            CastBaseline(
                animationPerKey = REQUIRED_ANIMATIONS.zip(animationValues).toMap(),
                profile = "android-user-$userId",
            ),
        )
    }

    private fun blocked(reason: String) = ColdBootstrapPreflight.Blocked(reason)
}

object CastColdBootstrapVerification {
    private const val MAX_CLUSTER_DIMENSION = 32_768
    private const val MAX_CLUSTER_DENSITY = 10_000

    fun accepts(state: ObservedState): Boolean {
        val geometry = state.geometry ?: return false
        val bounds = geometry.bounds
        val profile = state.profile
        val animationsValid = state.animationPerKey.keys == REQUIRED_ANIMATIONS.toSet() &&
            state.animationPerKey.values.all { value ->
                value.toDoubleOrNull()?.takeIf(Double::isFinite)?.let { it >= 0.0 } == true
            }
        // 0.72: the cluster is MEASURED, never assumed. Hardcoding 1920x720/180 made every vehicle
        // whose virtual display reports a different size or density fail verification forever.
        val measuredBounds = bounds.left == 0 && bounds.top == 0 &&
            bounds.right in 1..MAX_CLUSTER_DIMENSION && bounds.bottom in 1..MAX_CLUSTER_DIMENSION
        val measuredDensity = geometry.densityDpi?.let { it in 1..MAX_CLUSTER_DENSITY } == true
        return state.coarseState == ObservedCoarseState.IDLE_CLEAN && state.target == null &&
            state.protectedResidue == null && state.occupants.isEmpty() &&
            state.displayIdentity?.matches(Regex("display-[1-9][0-9]*")) == true &&
            state.displayName?.matches(Regex(".*fission.*xdja.*", RegexOption.IGNORE_CASE)) == true &&
            measuredBounds && measuredDensity &&
            profile?.matches(Regex("android-user-[0-9]+")) == true && geometry.profileId == profile &&
            animationsValid
    }
}

private sealed interface BootstrapCreation {
    data class Created(val envelope: CastSessionEnvelope) : BootstrapCreation
    data class Blocked(val reason: String) : BootstrapCreation
}

private data class BootstrapDispatch(val epoch: Long, val deadline: Long, val fenceToken: Long)

/** Runs only under CastExecutor's existing mutation lease. */
internal class CastColdBootstrap(
    private val store: CastSessionStore,
    private val gateway: CastMutationGateway,
    private val nowEpochMillis: () -> Long,
    private val operationId: () -> UUID,
    private val sleeper: CastSleeper,
    private val timeoutMillis: Long,
    private val verificationAttempts: Int,
    private val verificationPollMillis: Long,
) {
    init {
        require(timeoutMillis in 1..60_000L)
        require(verificationAttempts >= 2)
        require(verificationPollMillis >= 0)
    }

    fun run(
        facts: CastVehicleFacts,
        inspectRaw: (Long) -> ObservationValue<RawObservation>,
        observe: (Long) -> ObservationValue<ObservedState>,
        pendingTarget: String? = null,
        style: ClusterStyle = ClusterStyle.CURVED,
        profileOverride: VehicleProfileId? = null,
    ): ColdBootstrapResult {
        val id = operationId()
        val deadline = Math.addExact(nowEpochMillis(), timeoutMillis)
        val resolvedProfile = CastVehicleProfileCatalog.resolve(facts, profileOverride)
        val raw = when (val inspected = inspectRaw(deadline)) {
            is ObservationValue.Known -> inspected.value
            is ObservationValue.Unknown -> return ColdBootstrapResult.Blocked("raw preflight unavailable: ${inspected.reason}")
            is ObservationValue.Unsupported -> return ColdBootstrapResult.Blocked("raw preflight unsupported: ${inspected.reason}")
        }
        if (deadlineExpired(deadline)) return ColdBootstrapResult.Blocked("bootstrap deadline exceeded during preflight")
        val adopt = discoverClusterDisplayId(raw.displays) != null
        val creation = createTransaction(id, deadline, facts, raw, pendingTarget, adopt, style, profileOverride, resolvedProfile)
        if (creation is BootstrapCreation.Blocked) return ColdBootstrapResult.Blocked(creation.reason)
        val created = (creation as BootstrapCreation.Created).envelope
        val epoch = checkNotNull(created.transaction).epoch
        if (!setPhaseIfCurrent(id, epoch, OperationPhase.ACTIVATING)) {
            return fail(id, resolvedProfile, "bootstrap fence changed before activation", true)
        }

        if (!adopt) resolvedProfile.forwardKindsFor(style).forEachIndexed { index, kind ->
            val dispatch = authorizeForward(id, epoch, index, kind)
                ?: return fail(id, resolvedProfile, "bootstrap epoch, Stop fence, order, or deadline changed before ${kind.name}", true)
            when (val result = execute(dispatch, id, kind)) {
                is MutationResult.Observed -> markEffect(id, index, LedgerEffect.OBSERVED)
                is MutationResult.Rejected -> {
                    markEffect(id, index, LedgerEffect.REJECTED)
                    return fail(id, resolvedProfile, "${kind.name} rejected: ${result.reason}", true)
                }
                is MutationResult.UnknownEffect -> return fail(id, resolvedProfile, "${kind.name} effect unknown: ${result.reason}", false)
            }
            if (!sleepWithin(resolvedProfile.forwardSettlesMillis[index], deadline)) {
                return fail(id, resolvedProfile, "bootstrap settle exceeded deadline after ${kind.name}", true)
            }
        }
        if (!setPhaseIfCurrent(id, epoch, OperationPhase.VERIFYING)) {
            return fail(id, resolvedProfile, "bootstrap fence changed before verification", true)
        }
        var previous: ObservedState? = null
        var allEffectsKnown = true
        repeat(verificationAttempts) { attempt ->
            if (!stillCurrent(id, epoch)) {
                return fail(id, resolvedProfile, "bootstrap epoch or Stop fence changed during verification", true)
            }
            if (deadlineExpired(deadline)) return fail(id, resolvedProfile, "bootstrap verification deadline exceeded", allEffectsKnown)
            when (val sample = observe(deadline)) {
                is ObservationValue.Known -> {
                    if (deadlineExpired(deadline)) {
                        allEffectsKnown = false
                        previous = null
                    } else {
                        val state = sample.value
                        if (CastColdBootstrapVerification.accepts(state) && state == previous) return succeed(id, resolvedProfile, state)
                        previous = state.takeIf(CastColdBootstrapVerification::accepts)
                    }
                }
                is ObservationValue.Unknown, is ObservationValue.Unsupported -> {
                    allEffectsKnown = false
                    previous = null
                }
            }
            if (attempt + 1 < verificationAttempts && !sleepWithin(verificationPollMillis, deadline)) {
                return fail(id, resolvedProfile, "bootstrap verification deadline exceeded", allEffectsKnown)
            }
        }
        return fail(id, resolvedProfile, "bootstrap verification did not converge", allEffectsKnown)
    }

    private fun createTransaction(
        id: UUID,
        deadline: Long,
        facts: CastVehicleFacts,
        raw: RawObservation,
        pendingTarget: String?,
        adopt: Boolean,
        style: ClusterStyle,
        profileOverride: VehicleProfileId?,
        resolvedProfile: VehicleCastProfile,
    ): BootstrapCreation = store.locked {
        val loaded = read() as? StoreRead.Loaded
            ?: return@locked BootstrapCreation.Blocked("durable store unavailable")
        when (val preflight = CastColdBootstrapPreflight.inspect(loaded.envelope, facts, raw, profileOverride)) {
            is ColdBootstrapPreflight.Blocked -> BootstrapCreation.Blocked(preflight.reason)
            is ColdBootstrapPreflight.Ready -> BootstrapCreation.Created(
                bumpEpoch { envelope -> envelope.copy(
                    pendingIntent = pendingTarget?.let { pkg ->
                        loaded.envelope.pendingIntent
                            ?.takeIf { it.packageName == pkg && it.origin == CastIntentOrigin.BOOT_AUTO }
                            ?: PendingCastIntent(pkg)
                    },
                    transaction = CastTransaction(
                        id, envelope.durableEpoch, CastOperation.BOOTSTRAP, OperationPhase.PREPARING,
                        null, pendingTarget, null, BOOTSTRAP_PENDING_DISPLAY, preflight.baseline,
                        if (adopt) emptyList()
                        else (resolvedProfile.forwardKindsFor(style) + resolvedProfile.compensationKinds)
                            .map { kind ->
                                LedgerStep(
                                    "bootstrap-${kind.name.lowercase()}", "fixed Seal DL3 bootstrap sequence",
                                    kind, 0L, null, LedgerEffect.PLANNED, null, false,
                                )
                            },
                        0, deadline, null, "two equal known clean named-display observations", false,
                    ),
                ) },
            )
        }
    }

    private fun execute(dispatch: BootstrapDispatch, id: UUID, kind: CommandKind): MutationResult = try {
        gateway.execute(CastMutationRequest(
            id,
            dispatch.epoch,
            kind,
            null,
            BOOTSTRAP_PENDING_DISPLAY,
            dispatch.deadline,
            fenceToken = dispatch.fenceToken,
        ))
    } catch (failure: Exception) {
        MutationResult.UnknownEffect("gateway threw ${failure.javaClass.simpleName}: ${failure.message.orEmpty()}")
    }

    private fun fail(id: UUID, resolvedProfile: VehicleCastProfile, reason: String, effectKnown: Boolean): ColdBootstrapResult {
        setRecovery(id, reason)
        val tx = transaction(id)
        val observedForward = tx?.ledger?.take(3)?.any { it.effect == LedgerEffect.OBSERVED } == true
        val compensated = tx != null && effectKnown && observedForward && compensate(id, resolvedProfile)
        return ColdBootstrapResult.RecoveryRequired(id, reason, effectKnown, compensated)
    }

    private fun compensate(id: UUID, resolvedProfile: VehicleCastProfile): Boolean {
        val first = authorizeCompensation(id, resolvedProfile, 3, consumeBudget = true) ?: return false
        when (execute(first, id, resolvedProfile.compensationKinds[0])) {
            is MutationResult.Observed -> markEffect(id, 3, LedgerEffect.OBSERVED)
            is MutationResult.Rejected -> {
                markEffect(id, 3, LedgerEffect.REJECTED)
                return true
            }
            is MutationResult.UnknownEffect -> return true
        }
        if (!sleepWithin(resolvedProfile.compensationSettleMillis, first.deadline)) return true
        val second = authorizeCompensation(id, resolvedProfile, 4, consumeBudget = false) ?: return true
        when (execute(second, id, resolvedProfile.compensationKinds[1])) {
            is MutationResult.Observed -> markEffect(id, 4, LedgerEffect.OBSERVED)
            is MutationResult.Rejected -> markEffect(id, 4, LedgerEffect.REJECTED)
            is MutationResult.UnknownEffect -> Unit
        }
        return true
    }

    private fun succeed(id: UUID, resolvedProfile: VehicleCastProfile, observed: ObservedState): ColdBootstrapResult {
        val tx = transaction(id) ?: return ColdBootstrapResult.Blocked("bootstrap transaction disappeared")
        val stable = StableCastSession(
            StableState.IDLE_VERIFIED, EngineVersion.V2, "runtime-bootstrap", BOOTSTRAP_PROFILE_EXPORT,
            checkNotNull(observed.displayIdentity),
            CastBaseline(emptySet(), observed.geometry, observed.animationPerKey, observed.pipMode, observed.profile),
            null, null, observed.geometry, nowEpochMillis(),
        )
        val committed = store.locked {
            val envelope = (read() as? StoreRead.Loaded)?.envelope ?: return@locked false
            val current = envelope.transaction
            if (envelope.stopRequested || envelope.durableEpoch != tx.epoch ||
                current?.operationId != id || current.epoch != tx.epoch ||
                current.phase != OperationPhase.VERIFYING || nowEpochMillis() >= current.deadlineAtEpochMillis
            ) return@locked false
            update { it.copy(stableSession = stable, transaction = null, effectiveUiVersion = EngineVersion.V2) }
            true
        }
        if (!committed) {
            return if (transaction(id) != null) fail(id, resolvedProfile, "bootstrap fence changed before commit", true)
            else ColdBootstrapResult.Blocked("bootstrap transaction disappeared before commit")
        }
        return ColdBootstrapResult.Succeeded(stable)
    }

    private fun authorizeForward(
        id: UUID,
        epoch: Long,
        index: Int,
        kind: CommandKind,
    ): BootstrapDispatch? = store.locked {
        val loaded = read() as? StoreRead.Loaded ?: return@locked null
        val tx = loaded.envelope.transaction ?: return@locked null
        val now = nowEpochMillis()
        if (loaded.envelope.stopRequested || loaded.envelope.durableEpoch != epoch ||
            tx.operationId != id || tx.epoch != epoch || tx.operation != CastOperation.BOOTSTRAP ||
            tx.phase != OperationPhase.ACTIVATING || now >= tx.deadlineAtEpochMillis ||
            index !in 0..2 || tx.ledger.take(index).any { it.effect != LedgerEffect.OBSERVED }
        ) return@locked null
        val step = tx.ledger.getOrNull(index)
            ?.takeIf { it.commandKind == kind && it.effect == LedgerEffect.PLANNED }
            ?: return@locked null
        authorizeLedgerStep(tx, step, index, now, consumeBudget = false)
    }

    private fun authorizeCompensation(
        id: UUID,
        resolvedProfile: VehicleCastProfile,
        index: Int,
        consumeBudget: Boolean,
    ): BootstrapDispatch? = store.locked {
        val loaded = read() as? StoreRead.Loaded ?: return@locked null
        val tx = loaded.envelope.transaction ?: return@locked null
        val now = nowEpochMillis()
        if (loaded.envelope.stopRequested || loaded.envelope.durableEpoch != tx.epoch ||
            tx.operationId != id || tx.operation != CastOperation.BOOTSTRAP ||
            tx.phase != OperationPhase.RECOVERING || now >= tx.deadlineAtEpochMillis ||
            tx.ledger.take(3).any { it.effect == LedgerEffect.ISSUED } ||
            (consumeBudget && tx.compensationUsed) || (!consumeBudget && (!tx.compensationUsed || tx.ledger[3].effect != LedgerEffect.OBSERVED))
        ) return@locked null
        val expectedKind = if (index == 3) resolvedProfile.compensationKinds[0] else resolvedProfile.compensationKinds[1]
        val step = tx.ledger.getOrNull(index)
            ?.takeIf { it.commandKind == expectedKind && it.effect == LedgerEffect.PLANNED }
            ?: return@locked null
        authorizeLedgerStep(tx, step, index, now, consumeBudget)
    }

    private fun CastSessionStore.Locked.authorizeLedgerStep(
        tx: CastTransaction,
        step: LedgerStep,
        index: Int,
        now: Long,
        consumeBudget: Boolean,
    ): BootstrapDispatch {
        val token = gateway.currentFenceToken()
        val ledger = tx.ledger.toMutableList().also {
            it[index] = step.copy(gatewayGeneration = token, issuedAtEpochMillis = now, effect = LedgerEffect.ISSUED)
        }
        update { it.copy(transaction = tx.rebuild(
            ledger = ledger,
            compensationUsed = tx.compensationUsed || consumeBudget,
        )) }
        return BootstrapDispatch(tx.epoch, tx.deadlineAtEpochMillis, token)
    }

    private fun markEffect(id: UUID, index: Int, effect: LedgerEffect): Boolean = store.locked {
        val loaded = read() as? StoreRead.Loaded ?: return@locked false
        val tx = loaded.envelope.transaction?.takeIf { it.operationId == id } ?: return@locked false
        val step = tx.ledger.getOrNull(index)?.takeIf { it.effect == LedgerEffect.ISSUED }
            ?: return@locked false
        update { it.copy(transaction = tx.rebuild(ledger = tx.ledger.toMutableList().also { ledger ->
            ledger[index] = step.copy(effect = effect)
        })) }
        true
    }

    private fun setPhaseIfCurrent(id: UUID, epoch: Long, phase: OperationPhase): Boolean = store.locked {
        val loaded = read() as? StoreRead.Loaded ?: return@locked false
        val tx = loaded.envelope.transaction ?: return@locked false
        if (loaded.envelope.stopRequested || loaded.envelope.durableEpoch != epoch ||
            tx.operationId != id || tx.epoch != epoch || nowEpochMillis() >= tx.deadlineAtEpochMillis
        ) return@locked false
        update { it.copy(transaction = tx.rebuild(phase = phase)) }
        true
    }

    private fun setRecovery(id: UUID, reason: String) = store.locked {
        val loaded = read() as? StoreRead.Loaded ?: return@locked
        val tx = loaded.envelope.transaction?.takeIf { it.operationId == id } ?: return@locked
        update { it.copy(transaction = tx.rebuild(phase = OperationPhase.RECOVERING, lastFailure = reason)) }
    }

    private fun stillCurrent(id: UUID, epoch: Long): Boolean = store.locked {
        val envelope = (read() as? StoreRead.Loaded)?.envelope ?: return@locked false
        envelope.durableEpoch == epoch && !envelope.stopRequested &&
            envelope.transaction?.let { it.operationId == id && it.epoch == epoch } == true
    }

    private fun transaction(id: UUID): CastTransaction? = store.locked {
        ((read() as? StoreRead.Loaded)?.envelope?.transaction)?.takeIf { it.operationId == id }
    }

    private fun CastTransaction.rebuild(
        phase: OperationPhase = this.phase,
        ledger: List<LedgerStep> = this.ledger,
        lastFailure: String? = this.lastFailure,
        compensationUsed: Boolean = this.compensationUsed,
    ) = CastTransaction(
        operationId, epoch, operation, phase, sourcePkg, targetPkg, targetClass, expectedDisplayIdentity,
        baseline, ledger, retries, deadlineAtEpochMillis, lastFailure, expectedPostcondition,
        compensationUsed, requestedGeometry,
    )

    private fun deadlineExpired(deadline: Long): Boolean = nowEpochMillis() >= deadline

    private fun sleepWithin(millis: Long, deadline: Long): Boolean {
        if (millis < 0 || deadline - nowEpochMillis() < millis) return false
        return try {
            if (millis > 0) sleeper.sleep(millis)
            !deadlineExpired(deadline)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: RuntimeException) {
            false
        }
    }
}

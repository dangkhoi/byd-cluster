package com.byd.clusternav.modules.clustercast.v2

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class CastMutationRequest(
    val operationId: UUID,
    val epoch: Long,
    val kind: CommandKind,
    val targetPackage: String?,
    val expectedDisplayIdentity: String,
    val deadlineAtEpochMillis: Long,
    val expectedTarget: CastTarget? = null,
    val geometry: AcceptedGeometry? = null,
    val fenceToken: Long = 0L,
    val baseline: CastBaseline? = null,
)

sealed interface MutationResult {
    data class Observed(val evidence: String) : MutationResult
    data class Rejected(val reason: String) : MutationResult
    data class UnknownEffect(val reason: String) : MutationResult
}

interface CastMutationFence {
    fun fenceInFlight()
    fun currentFenceToken(): Long
}

interface CastMutationGateway : CastMutationFence {
    fun execute(request: CastMutationRequest): MutationResult
}

@Suppress("FunctionName")
fun CastMutationGateway(execute: (CastMutationRequest) -> MutationResult): CastMutationGateway =
    object : CastMutationGateway {
        private val fenceToken = AtomicLong(0)

        override fun execute(request: CastMutationRequest): MutationResult =
            if (request.fenceToken == fenceToken.get()) execute(request)
            else MutationResult.Rejected("mutation fenced before dispatch")

        override fun fenceInFlight() { fenceToken.incrementAndGet() }
        override fun currentFenceToken(): Long = fenceToken.get()
    }

sealed interface ExecutionResult {
    data class AwaitingVerification(val operationId: UUID, val epoch: Long) : ExecutionResult
    data class RecoveryRequired(val operationId: UUID, val reason: String) : ExecutionResult
    data class Blocked(val reason: String) : ExecutionResult
}

private data class AuthorizedIssue(val transaction: CastTransaction, val fenceToken: Long)
private data class AuthorizedCompensation(val ledgerIndex: Int, val request: CastMutationRequest)

/** Single-writer executor. Every effect is journaled ISSUED before gateway invocation. */
class CastExecutor(
    private val store: CastSessionStore,
    private val gateway: CastMutationGateway,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val operationId: () -> UUID = UUID::randomUUID,
    private val operationTimeoutMillis: Long = 15_000L,
    private val bootstrapTimeoutMillis: Long = 60_000L,
    private val bootstrapVerificationAttempts: Int = 6,
    private val bootstrapVerificationPollMillis: Long = 500L,
    private val sleeper: CastSleeper = CastSleeper(Thread::sleep),
) {
    private val lease = ReentrantLock()

    init {
        require(operationTimeoutMillis > 0)
        require(bootstrapTimeoutMillis in 1..60_000L)
        require(bootstrapVerificationAttempts >= 2)
        require(bootstrapVerificationPollMillis >= 0)
    }

    internal fun <T> withMutationLease(block: () -> T): T = lease.withLock { block() }

    fun bootstrap(
        facts: CastVehicleFacts,
        inspectRaw: (Long) -> ObservationValue<RawObservation>,
        observe: (Long) -> ObservationValue<ObservedState>,
        style: ClusterStyle = ClusterStyle.CURVED,
    ): ColdBootstrapResult = bootstrapLocked(facts, null, inspectRaw, observe, style)

    internal fun bootstrapForManualIntent(
        facts: CastVehicleFacts,
        pendingTarget: String,
        inspectRaw: (Long) -> ObservationValue<RawObservation>,
        observe: (Long) -> ObservationValue<ObservedState>,
        style: ClusterStyle = ClusterStyle.CURVED,
    ): ColdBootstrapResult = bootstrapLocked(facts, pendingTarget, inspectRaw, observe, style)

    private fun bootstrapLocked(
        facts: CastVehicleFacts,
        pendingTarget: String?,
        inspectRaw: (Long) -> ObservationValue<RawObservation>,
        observe: (Long) -> ObservationValue<ObservedState>,
        style: ClusterStyle,
    ): ColdBootstrapResult = lease.withLock {
        CastColdBootstrap(
            store, gateway, nowEpochMillis, operationId, sleeper, bootstrapTimeoutMillis,
            bootstrapVerificationAttempts, bootstrapVerificationPollMillis,
        ).run(facts, inspectRaw, observe, pendingTarget, style)
    }

    fun fenceInFlight() {
        gateway.fenceInFlight()
    }

    fun execute(plan: CastPlan, baseline: CastBaseline, targetPackage: String?): ExecutionResult =
        lease.withLock { executeLocked(plan, baseline, targetPackage, null) }

    internal fun executePendingTarget(
        plan: CastPlan,
        baseline: CastBaseline,
        targetPackage: String,
    ): ExecutionResult = lease.withLock { executeLocked(plan, baseline, targetPackage, targetPackage) }

    private fun executeLocked(
        plan: CastPlan,
        baseline: CastBaseline,
        targetPackage: String?,
        pendingTarget: String?,
    ): ExecutionResult {
        val id = operationId()
        val deadline = Math.addExact(nowEpochMillis(), operationTimeoutMillis)
        val created = store.locked {
            val loaded = read() as? StoreRead.Loaded ?: return@locked null
            if (loaded.envelope.transaction != null) return@locked null
            if (plan.epoch != loaded.envelope.durableEpoch) return@locked null
            if (loaded.envelope.stopRequested && plan.operation !in setOf(CastOperation.STOP, CastOperation.RECOVER)) {
                return@locked null
            }
            if (pendingTarget != null && loaded.envelope.pendingPackage != pendingTarget) return@locked null
            if (plan.operation == CastOperation.BOOTSTRAP ||
                plan.steps.any { it.commandKind in BOOTSTRAP_COMMANDS }
            ) return@locked null
            if (!ordinaryStableStateAllows(loaded.envelope, plan.operation)) return@locked null
            val createTransaction: (CastSessionEnvelope) -> CastSessionEnvelope = { envelope ->
                envelope.copy(
                    pendingIntent = if (pendingTarget == null) envelope.pendingIntent else null,
                    transaction = CastTransaction(
                    operationId = id,
                    epoch = envelope.durableEpoch,
                    operation = plan.operation,
                    phase = if (plan.operation == CastOperation.STOP) OperationPhase.STOP_REQUESTED else OperationPhase.PREPARING,
                    sourcePkg = envelope.stableSession?.activeTarget?.packageName,
                    targetPkg = targetPackage,
                    targetClass = plan.targetClass?.name,
                    expectedDisplayIdentity = plan.expectedDisplayIdentity
                        ?: envelope.stableSession?.expectedDisplayIdentity
                        ?: "unresolved",
                    baseline = baseline,
                    ledger = plan.steps.map { step ->
                        LedgerStep(
                            stepId = step.id,
                            precondition = step.guard,
                            commandKind = step.commandKind,
                            gatewayGeneration = 0L,
                            issuedAtEpochMillis = null,
                            effect = LedgerEffect.PLANNED,
                            compensation = compensationFor(step.commandKind),
                            compensationObserved = false,
                        )
                    },
                    retries = 0,
                    deadlineAtEpochMillis = deadline,
                    lastFailure = null,
                    expectedPostcondition = plan.expectedPostcondition,
                    compensationUsed = false,
                    requestedGeometry = plan.geometry,
                ))
            }
            if (plan.operation == CastOperation.STOP && loaded.envelope.stopRequested) {
                update(createTransaction)
            } else {
                bumpEpoch(createTransaction)
            }
        } ?: return ExecutionResult.Blocked("store unavailable or transaction already active")

        val tx = checkNotNull(created.transaction)
        tx.ledger.forEachIndexed { index, step ->
            val authorized = authorizeIssue(id, tx.epoch, plan.operation, index, step.stepId)
                ?: return markRecovery(id, "operation epoch, Stop fence, or deadline changed before ${step.stepId}")
            val active = authorized.transaction
            val requestTarget = when (step.commandKind) {
                CommandKind.RETURN_NORMAL_TO_MAIN, CommandKind.RETURN_PROTECTED_GENTLY -> active.sourcePkg
                else -> targetPackage
            }
            val request = CastMutationRequest(
                id, active.epoch, step.commandKind, requestTarget,
                active.expectedDisplayIdentity, active.deadlineAtEpochMillis,
                plan.expectedTarget, plan.geometry, authorized.fenceToken, active.baseline,
            )
            CastOperationLog.record("${plan.operation} ▸ ${step.stepId} (${step.commandKind})")
            val result = try {
                gateway.execute(request)
            } catch (failure: Exception) {
                return markRecovery(id, "unknown effect: gateway threw ${failure.javaClass.simpleName}: ${failure.message.orEmpty()}")
            }
            CastOperationLog.record("   → ${resultLabel(result)}")
            when (result) {
                is MutationResult.Observed -> if (!markEffect(id, index, LedgerEffect.OBSERVED)) {
                    return markRecovery(id, "observed effect could not be journaled for ${step.stepId}")
                }
                is MutationResult.Rejected -> {
                    markEffect(id, index, LedgerEffect.REJECTED)
                    return markRecovery(id, result.reason)
                }
                is MutationResult.UnknownEffect -> return markRecovery(id, "unknown effect: ${result.reason}")
            }
        }
        if (!beginVerificationIfCurrent(id, tx.epoch, plan.operation)) {
            return markRecovery(id, "operation epoch, Stop fence, or deadline changed before verification")
        }
        return ExecutionResult.AwaitingVerification(id, tx.epoch)
    }

    fun compensate(id: UUID): ExecutionResult = lease.withLock {
        val authorized = store.locked {
            val loaded = read() as? StoreRead.Loaded ?: return@locked null
            val tx = loaded.envelope.transaction ?: return@locked null
            val now = nowEpochMillis()
            if (tx.operationId != id || tx.operation == CastOperation.BOOTSTRAP ||
                tx.phase != OperationPhase.RECOVERING || tx.compensationUsed ||
                loaded.envelope.durableEpoch != tx.epoch || loaded.envelope.stopRequested ||
                now >= tx.deadlineAtEpochMillis || tx.ledger.any { it.effect == LedgerEffect.ISSUED }
            ) return@locked null
            val selected = tx.ledger.withIndex().lastOrNull {
                it.value.effect == LedgerEffect.OBSERVED && it.value.compensation != null
            } ?: return@locked null
            val step = selected.value
            val token = gateway.currentFenceToken()
            val issuedStep = step.copy(
                compensationIssuedAtEpochMillis = now,
                compensationGatewayGeneration = token,
                compensationEffect = LedgerEffect.ISSUED,
            )
            val updated = tx.withLedger(selected.index, issuedStep).copyCompensationUsed()
            update { envelope -> envelope.copy(transaction = updated) }
            val expectedTarget = loaded.envelope.stableSession?.activeTarget
                ?.takeIf { it.packageName == tx.sourcePkg && "display-${it.displayId}" == tx.expectedDisplayIdentity }
            AuthorizedCompensation(
                selected.index,
                CastMutationRequest(
                    id, tx.epoch, checkNotNull(step.compensation), tx.sourcePkg,
                    tx.expectedDisplayIdentity, tx.deadlineAtEpochMillis,
                    expectedTarget = expectedTarget,
                    geometry = if (step.compensation in setOf(CommandKind.APPLY_TASK_GEOMETRY, CommandKind.APPLY_DISPLAY_GEOMETRY)) {
                        tx.baseline.geometry
                    } else null,
                    fenceToken = token,
                ),
            )
        } ?: return ExecutionResult.Blocked("compensation unavailable, unsafe, expired, or already used")

        val result = try {
            gateway.execute(authorized.request)
        } catch (failure: Exception) {
            return markRecovery(id, "compensation effect unknown: gateway threw ${failure.javaClass.simpleName}: ${failure.message.orEmpty()}")
        }
        return when (result) {
            is MutationResult.Observed -> {
                val recorded = markCompensationEffect(id, authorized.ledgerIndex, LedgerEffect.OBSERVED)
                val reason = if (recorded) "compensation observed; re-observation required"
                else "compensation observed but durable result journaling failed"
                ExecutionResult.RecoveryRequired(id, reason)
            }
            is MutationResult.Rejected -> {
                markCompensationEffect(id, authorized.ledgerIndex, LedgerEffect.REJECTED)
                ExecutionResult.RecoveryRequired(id, "compensation rejected: ${result.reason}")
            }
            is MutationResult.UnknownEffect ->
                ExecutionResult.RecoveryRequired(id, "compensation effect unknown: ${result.reason}")
        }
    }

    private fun authorizeIssue(
        id: UUID,
        epoch: Long,
        operation: CastOperation,
        index: Int,
        stepId: String,
    ): AuthorizedIssue? = store.locked {
        val loaded = read() as? StoreRead.Loaded ?: return@locked null
        val tx = loaded.envelope.transaction ?: return@locked null
        val now = nowEpochMillis()
        if (loaded.envelope.durableEpoch != epoch || tx.operationId != id || tx.epoch != epoch ||
            tx.operation != operation || tx.phase !in setOf(OperationPhase.PREPARING, OperationPhase.STOP_REQUESTED) ||
            tx.ledger.take(index).any { it.effect != LedgerEffect.OBSERVED } ||
            (operation !in setOf(CastOperation.STOP, CastOperation.RECOVER) && loaded.envelope.stopRequested) ||
            now >= tx.deadlineAtEpochMillis
        ) return@locked null
        val step = tx.ledger.getOrNull(index)
            ?.takeIf { it.stepId == stepId && it.effect == LedgerEffect.PLANNED }
            ?: return@locked null
        val token = gateway.currentFenceToken()
        val updated = tx.withLedger(index, step.copy(
            gatewayGeneration = token,
            issuedAtEpochMillis = now,
            effect = LedgerEffect.ISSUED,
        ))
        update { it.copy(transaction = updated) }
        AuthorizedIssue(updated, token)
    }

    private fun markEffect(id: UUID, index: Int, effect: LedgerEffect): Boolean = store.locked {
        val loaded = read() as? StoreRead.Loaded ?: return@locked false
        val tx = loaded.envelope.transaction?.takeIf { it.operationId == id } ?: return@locked false
        val step = tx.ledger.getOrNull(index)?.takeIf { it.effect == LedgerEffect.ISSUED }
            ?: return@locked false
        update { it.copy(transaction = tx.withLedger(index, step.copy(effect = effect))) }
        true
    }

    private fun markCompensationEffect(id: UUID, index: Int, effect: LedgerEffect): Boolean = store.locked {
        val loaded = read() as? StoreRead.Loaded ?: return@locked false
        val tx = loaded.envelope.transaction?.takeIf { it.operationId == id } ?: return@locked false
        val step = tx.ledger.getOrNull(index)
            ?.takeIf { it.compensationEffect == LedgerEffect.ISSUED }
            ?: return@locked false
        update { it.copy(transaction = tx.withLedger(index, step.copy(
            compensationObserved = effect == LedgerEffect.OBSERVED,
            compensationEffect = effect,
        ))) }
        true
    }

    private fun beginVerificationIfCurrent(id: UUID, epoch: Long, operation: CastOperation): Boolean = store.locked {
        val loaded = read() as? StoreRead.Loaded ?: return@locked false
        val tx = loaded.envelope.transaction ?: return@locked false
        if (loaded.envelope.durableEpoch != epoch || tx.operationId != id || tx.epoch != epoch ||
            tx.operation != operation || nowEpochMillis() >= tx.deadlineAtEpochMillis ||
            tx.ledger.any { it.effect != LedgerEffect.OBSERVED } ||
            (operation !in setOf(CastOperation.STOP, CastOperation.RECOVER) && loaded.envelope.stopRequested)
        ) return@locked false
        update { it.copy(transaction = tx.copyPhase(OperationPhase.VERIFYING)) }
        true
    }

    private fun markRecovery(id: UUID, reason: String): ExecutionResult {
        store.locked {
            val loaded = read() as? StoreRead.Loaded ?: return@locked
            val tx = loaded.envelope.transaction?.takeIf { it.operationId == id } ?: return@locked
            update { it.copy(transaction = tx.copyPhase(OperationPhase.RECOVERING, reason)) }
        }
        return ExecutionResult.RecoveryRequired(id, reason)
    }

    private fun CastTransaction.withLedger(index: Int, value: LedgerStep): CastTransaction = CastTransaction(
        operationId, epoch, operation, phase, sourcePkg, targetPkg, targetClass,
        expectedDisplayIdentity, baseline, ledger.toMutableList().also { it[index] = value }, retries,
        deadlineAtEpochMillis, lastFailure, expectedPostcondition, compensationUsed, requestedGeometry,
    )

    private fun CastTransaction.copyPhase(value: OperationPhase, failure: String? = lastFailure) = CastTransaction(
        operationId, epoch, operation, value, sourcePkg, targetPkg, targetClass,
        expectedDisplayIdentity, baseline, ledger, retries, deadlineAtEpochMillis, failure,
        expectedPostcondition, compensationUsed, requestedGeometry,
    )

    private fun CastTransaction.copyCompensationUsed() = CastTransaction(
        operationId, epoch, operation, OperationPhase.RECOVERING, sourcePkg, targetPkg, targetClass,
        expectedDisplayIdentity, baseline, ledger, retries, deadlineAtEpochMillis, lastFailure,
        expectedPostcondition, true, requestedGeometry,
    )

    private fun resultLabel(result: MutationResult): String = when (result) {
        is MutationResult.Observed -> "OK ${result.evidence.take(80)}"
        is MutationResult.Rejected -> "REFUSED ${result.reason.take(120)}"
        is MutationResult.UnknownEffect -> "UNKNOWN ${result.reason.take(120)}"
    }

    private fun compensationFor(kind: CommandKind): CommandKind? = when (kind) {
        CommandKind.START_FRESH_NORMAL -> CommandKind.RETURN_NORMAL_TO_MAIN
        CommandKind.RESUME_PROTECTED -> CommandKind.RETURN_PROTECTED_GENTLY
        CommandKind.APPLY_TASK_GEOMETRY -> CommandKind.APPLY_TASK_GEOMETRY
        CommandKind.APPLY_DISPLAY_GEOMETRY -> CommandKind.APPLY_DISPLAY_GEOMETRY
        else -> null
    }

    private fun ordinaryStableStateAllows(envelope: CastSessionEnvelope, operation: CastOperation): Boolean {
        val stable = envelope.stableSession
        return when (operation) {
            CastOperation.BOOTSTRAP -> false
            CastOperation.CAST -> stable?.engineVersion == EngineVersion.V2 &&
                stable.state == StableState.IDLE_VERIFIED && stable.activeTarget == null
            CastOperation.SWITCH -> stable?.engineVersion == EngineVersion.V2 &&
                stable.state in setOf(StableState.ACTIVE_VERIFIED, StableState.ACTIVE_DEGRADED) &&
                stable.activeTarget != null
            CastOperation.APPLY_GEOMETRY -> stable?.engineVersion == EngineVersion.V2 &&
                stable.state in setOf(StableState.ACTIVE_VERIFIED, StableState.ACTIVE_DEGRADED) &&
                stable.activeTarget != null
            CastOperation.STOP, CastOperation.RECOVER -> true
        }
    }

    companion object {
        private val BOOTSTRAP_COMMANDS =
            (SealDl3BootstrapProfile.forwardKinds + SealDl3BootstrapProfile.compensationKinds).toSet()
    }
}

package com.byd.clusternav.modules.clustercast.v2

import java.util.UUID

sealed interface RecoveryDecision {
    data class CompensationEligible(val operationId: UUID) : RecoveryDecision
    data class Exhausted(val operationId: UUID) : RecoveryDecision
    data class Manual(val reason: UnavailableReason) : RecoveryDecision
}

/** Recovery never replays an operation; it may request the originating transaction's one compensation. */
class CastRecovery(
    private val store: CastSessionStore,
    private val executor: CastExecutor,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    fun decide(): RecoveryDecision = store.locked {
        val loaded = read() as? StoreRead.Loaded
            ?: return@locked RecoveryDecision.Manual(UnavailableReason.BASELINE_UNKNOWN)
        val tx = loaded.envelope.transaction
            ?: return@locked RecoveryDecision.Manual(UnavailableReason.CONTRACT_UNMAPPED)
        if (tx.compensationUsed) return@locked RecoveryDecision.Exhausted(tx.operationId)
        if (tx.operation == CastOperation.BOOTSTRAP || tx.phase != OperationPhase.RECOVERING ||
            loaded.envelope.durableEpoch != tx.epoch || loaded.envelope.stopRequested ||
            nowEpochMillis() >= tx.deadlineAtEpochMillis ||
            tx.ledger.any { it.effect == LedgerEffect.ISSUED } ||
            tx.ledger.none { it.effect == LedgerEffect.OBSERVED && it.compensation != null }
        ) {
            return@locked RecoveryDecision.Manual(UnavailableReason.COMPENSATION_EXHAUSTED)
        }
        RecoveryDecision.CompensationEligible(tx.operationId)
    }

    fun compensate(): ExecutionResult = when (val decision = decide()) {
        is RecoveryDecision.CompensationEligible -> executor.compensate(decision.operationId)
        is RecoveryDecision.Exhausted -> ExecutionResult.Blocked("compensation already exhausted")
        is RecoveryDecision.Manual -> ExecutionResult.Blocked("manual recovery required: ${decision.reason}")
    }
}

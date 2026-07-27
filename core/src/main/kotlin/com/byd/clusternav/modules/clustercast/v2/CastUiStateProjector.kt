package com.byd.clusternav.modules.clustercast.v2

import java.time.Instant
import java.util.UUID

class PlannerUiProjection(
    val phase: OperationPhase,
    val operationId: UUID,
    val deadlineAt: Instant,
    val stopDisposition: StopDisposition,
    val nextSafeAction: NextSafeAction,
    allowedActions: Set<CastAction>,
) {
    val allowedActions: Set<CastAction> =
        java.util.Collections.unmodifiableSet(LinkedHashSet(allowedActions))
}

data class CastProjectionInput(
    val decodeValid: Boolean,
    val engineVersion: EngineVersion?,
    val observedNonIdle: Boolean,
    val stopRequested: Boolean,
    val recoverySubstate: RecoverySubstate?,
    val transaction: PlannerUiProjection?,
    val destructiveRecoveryEligible: Boolean?,
    val stableState: StableState?,
    val stableConverged: Boolean,
    val interactionContext: InteractionContext,
    val target: CastTarget?,
    val protectedResidue: ProtectedResidue?,
    val acceptedGeometry: AcceptedGeometry?,
    val durableEpoch: Long,
    val now: Instant,
    val coldPristine: Boolean = false,
    val automationDisposition: AutomationDisposition? = null,
    val automationReason: AutomationReason? = null,
    val automationTargetPackage: String? = null,
)

private data class RecoveryRow(
    val messageKey: String,
    val next: NextSafeAction,
    val disposition: StopDisposition,
    val actions: Set<CastAction>,
)

object CastUiStateProjector {
    val recoveryMappings: Map<RecoverySubstate, String> get() = recoveryRows.mapValues { it.value.messageKey }

    fun project(input: CastProjectionInput): CastUiStateV2 {
        if (!input.decodeValid || input.durableEpoch < 0) return failClosed(input)
        if (input.engineVersion == EngineVersion.LEGACY && input.observedNonIdle) return legacy(input)
        val recovery = input.recoverySubstate
        if (input.stopRequested && recovery != null) {
            val row = recoveryRows[recovery] ?: return failClosed(input)
            if (row.disposition.kind == StopDispositionKind.AVAILABLE) return failClosed(input)
            return recovery(input, recovery, row)
        }
        if (recovery != null) return recoveryRows[recovery]?.let { recovery(input, recovery, it) } ?: failClosed(input)
        if (input.stopRequested) return state(
            input, CoarseState.STOP_REQUESTED, null, OperationPhase.STOP_REQUESTED, null,
            StopDisposition(StopDispositionKind.REQUESTED), NextSafeAction.WAIT_AND_OBSERVE,
            // Đã yêu cầu Stop mà chưa có recovery substate: trước 2026-07-27 nhánh này trả emptySet(),
            // nghĩa là màn hình không còn hành động nào trong lúc chờ. Quét vét cạn R14 bắt được đúng ca
            // này. Giữ hai hành động chỉ-đọc: Chẩn đoán để biết vì sao, và thử kết nối lại để cứu chính
            // cái kênh đang được chờ. Stop vẫn bị chặn bởi disposition REQUESTED nên không phát trùng.
            setOf(CastAction.OPEN_DIAGNOSTICS, CastAction.RETRY_CONNECT),
        )
        input.transaction?.let { tx ->
            if (!validDisposition(tx.stopDisposition) ||
                !validActionExport(tx.nextSafeAction, tx.allowedActions, tx.stopDisposition)
            ) return failClosed(input)
            return state(
                input, coarse(tx.phase), null, tx.phase, null, tx.stopDisposition, tx.nextSafeAction,
                tx.allowedActions, tx.operationId, tx.deadlineAt,
            )
        }
        input.destructiveRecoveryEligible?.let { eligible ->
            if (!eligible) return failClosed(input)
            return state(
                input, CoarseState.RECOVERY_PENDING, StableState.RECOVERY_PENDING, null, null,
                StopDisposition(StopDispositionKind.UNAVAILABLE, UnavailableReason.RECOVERY_ACTION_ONLY),
                NextSafeAction.TRY_ELIGIBLE_RECOVERY_ONCE, setOf(CastAction.TRY_ELIGIBLE_RECOVERY_ONCE),
            )
        }
        if (input.coldPristine) {
            if (input.engineVersion != EngineVersion.V2 || input.stableState != null || input.stableConverged) {
                return failClosed(input)
            }
            return coldPristine(input)
        }
        if (input.stableConverged) return stable(input)
        return failClosed(input)
    }

    private fun coldPristine(input: CastProjectionInput): CastUiStateV2 = state(
        input, CoarseState.COLD_PRISTINE, null, null, null,
        StopDisposition(StopDispositionKind.COMPLETED), NextSafeAction.NONE,
        setOf(
            CastAction.CAST,
            CastAction.CHOOSE_ANOTHER_APP,
            CastAction.OPEN_APP_MANAGER,
            CastAction.OPEN_DIAGNOSTICS,
        ),
    )

    private fun stable(input: CastProjectionInput): CastUiStateV2 = when (input.stableState) {
        StableState.IDLE_VERIFIED -> state(
            input, CoarseState.IDLE_VERIFIED, StableState.IDLE_VERIFIED, null, null,
            StopDisposition(StopDispositionKind.COMPLETED), NextSafeAction.NONE,
            setOf(CastAction.CAST, CastAction.CHOOSE_ANOTHER_APP, CastAction.OPEN_APP_MANAGER),
        )
        StableState.ACTIVE_VERIFIED -> if (input.target != null && input.protectedResidue == null) state(
            input, CoarseState.ACTIVE_VERIFIED, StableState.ACTIVE_VERIFIED, null, null,
            StopDisposition(StopDispositionKind.AVAILABLE), NextSafeAction.REQUEST_STOP,
            setOf(CastAction.SWITCH, CastAction.ADJUST, CastAction.STOP),
        ) else failClosed(input)
        StableState.ACTIVE_DEGRADED -> if (input.target != null && input.protectedResidue != null) state(
            input, CoarseState.ACTIVE_DEGRADED, StableState.ACTIVE_DEGRADED, null, null,
            StopDisposition(StopDispositionKind.AVAILABLE), NextSafeAction.REQUEST_STOP,
            setOf(CastAction.SWITCH, CastAction.STOP, CastAction.OPEN_DIAGNOSTICS),
        ) else failClosed(input)
        else -> failClosed(input)
    }

    private fun recovery(input: CastProjectionInput, substate: RecoverySubstate, row: RecoveryRow): CastUiStateV2 {
        if (!validDisposition(row.disposition) ||
            !validActionExport(row.next, row.actions, row.disposition)
        ) return failClosed(input)
        val manual = substate == RecoverySubstate.JOURNAL_CORRUPT_MANUAL
        return state(
            input,
            if (manual) CoarseState.MANUAL_REQUIRED else CoarseState.RECOVERY_PENDING,
            if (manual) StableState.MANUAL_REQUIRED else StableState.RECOVERY_PENDING,
            null,
            substate,
            row.disposition,
            row.next,
            row.actions,
        )
    }

    private fun legacy(input: CastProjectionInput) = state(
        input, CoarseState.LEGACY_ACTIVE_READ_ONLY, null, null, null,
        StopDisposition(StopDispositionKind.UNAVAILABLE, UnavailableReason.LEGACY_SESSION_UNSAFE),
        NextSafeAction.OPEN_DIAGNOSTICS,
        setOf(CastAction.OPEN_DIAGNOSTICS, CastAction.SHOW_PHYSICAL_INSTRUCTION),
    )

    private fun failClosed(input: CastProjectionInput) = state(
        input, CoarseState.MANUAL_REQUIRED, StableState.MANUAL_REQUIRED, null, null,
        StopDisposition(StopDispositionKind.UNAVAILABLE, UnavailableReason.CONTRACT_UNMAPPED),
        NextSafeAction.OPEN_DIAGNOSTICS, setOf(CastAction.OPEN_DIAGNOSTICS),
    )

    /**
     * Nhóm phép không bao giờ bị khoá, vì chúng không phát lệnh nào ra xe: xem Chẩn đoán để biết vì
     * sao, và chọn app để chuẩn bị. Gộp ở một chỗ để không trạng thái nào lỡ bỏ sót — thay vì nhớ
     * thêm tay ở từng nhánh, cách đã bỏ sót hai lần trong ngày 27/7.
     */
    private fun alwaysAllowed(actions: Set<CastAction>): Set<CastAction> =
        actions + CastAction.OPEN_DIAGNOSTICS + CastAction.SELECT_TARGET_APP

    private fun state(
        input: CastProjectionInput,
        coarse: CoarseState,
        stable: StableState?,
        phase: OperationPhase?,
        recovery: RecoverySubstate?,
        disposition: StopDisposition,
        next: NextSafeAction,
        actions: Set<CastAction>,
        operationId: UUID? = null,
        deadline: Instant? = null,
    ): CastUiStateV2 {
        val reason = disposition.reason
        check((disposition.kind == StopDispositionKind.UNAVAILABLE) == (reason != null))
        val dominant = input.stopRequested || recovery != null || phase != null || coarse in DOMINANT_STATES
        return CastUiStateV2(
            CAST_UI_SCHEMA_VERSION, CAST_UI_SCHEMA_HASH, coarse, stable, phase, recovery, disposition,
            next, reason, alwaysAllowed(actions), disabled(alwaysAllowed(actions), coarse),
            input.interactionContext, input.target,
            input.protectedResidue, input.acceptedGeometry, operationId, input.durableEpoch, deadline,
            automationDisposition = input.automationDisposition.takeUnless { dominant },
            automationReason = input.automationReason.takeUnless { dominant },
            automationTargetPackage = input.automationTargetPackage.takeUnless { dominant },
        )
    }

    private fun disabled(
        allowed: Set<CastAction>,
        coarse: CoarseState,
    ): Map<CastAction, DisabledReason> = CastAction.entries.filterNot { it in allowed }.associateWith {
        when (coarse) {
            CoarseState.LEGACY_ACTIVE_READ_ONLY -> DisabledReason.LEGACY_SESSION_UNSAFE
            CoarseState.RECOVERY_PENDING, CoarseState.MANUAL_REQUIRED -> DisabledReason.RECOVERY_PENDING
            CoarseState.PREPARING, CoarseState.ACTIVATING, CoarseState.SWITCHING,
            CoarseState.VERIFYING, CoarseState.STOP_REQUESTED, CoarseState.RESTORING,
            CoarseState.RECOVERING -> DisabledReason.OPERATION_IN_FLIGHT
            else -> DisabledReason.ACTION_NOT_EXPORTED
        }
    }

    private fun validDisposition(value: StopDisposition): Boolean =
        (value.kind == StopDispositionKind.UNAVAILABLE) == (value.reason != null)

    private fun validActionExport(
        next: NextSafeAction,
        actions: Set<CastAction>,
        disposition: StopDisposition,
    ): Boolean {
        if (CastAction.STOP in actions && disposition.kind != StopDispositionKind.AVAILABLE) return false
        val translated = translate(next)
        return translated == null || translated in actions
    }

    fun translate(next: NextSafeAction): CastAction? = when (next) {
        NextSafeAction.NONE, NextSafeAction.WAIT_AND_OBSERVE -> null
        NextSafeAction.REQUEST_STOP -> CastAction.STOP
        NextSafeAction.RETRY_CONNECT_BOUNDED -> CastAction.RETRY_CONNECT
        NextSafeAction.OPEN_DIAGNOSTICS -> CastAction.OPEN_DIAGNOSTICS
        NextSafeAction.REQUEST_PHONE_DISCONNECT -> CastAction.REQUEST_PHONE_DISCONNECT
        NextSafeAction.TRY_ELIGIBLE_RECOVERY_ONCE -> CastAction.TRY_ELIGIBLE_RECOVERY_ONCE
        NextSafeAction.SHOW_PHYSICAL_INSTRUCTION -> CastAction.SHOW_PHYSICAL_INSTRUCTION
        NextSafeAction.OPEN_PROFILE_SETUP -> CastAction.OPEN_PROFILE_SETUP
    }

    private fun coarse(phase: OperationPhase): CoarseState = CoarseState.valueOf(phase.name)

    /**
     * Every recovery row keeps at least one safe operator action.
     *
     * Measured on the vehicle 2026-07-26: a WAIT_AND_OBSERVE row translated to an empty action set,
     * so an interrupted cast left the whole screen disabled — including Chẩn đoán, the only way to
     * see why, and Thử kết nối lại, the very action that could restore the channel it was waiting on.
     * The state survived process death and force-stop, so the feature was permanently unusable.
     *
     * Diagnostics is read-only and therefore always safe. A bounded reconnect is read-only too, so it
     * is offered whenever the state is merely waiting. Stop is deliberately NOT added here: it stays
     * gated by the stop disposition.
     */
    private fun row(key: String, next: NextSafeAction, kind: StopDispositionKind, reason: UnavailableReason? = null) =
        RecoveryRow(
            key,
            next,
            StopDisposition(kind, reason),
            buildSet {
                translate(next)?.let(::add)
                add(CastAction.OPEN_DIAGNOSTICS)
                if (next == NextSafeAction.WAIT_AND_OBSERVE) add(CastAction.RETRY_CONNECT)
            },
        )

    private val recoveryRows: Map<RecoverySubstate, RecoveryRow> = mapOf(
        RecoverySubstate.UNKNOWN_EFFECT_STOP_AVAILABLE to row("cast.recovery.unknown_effect_stop_available", NextSafeAction.REQUEST_STOP, StopDispositionKind.AVAILABLE),
        RecoverySubstate.UNKNOWN_EFFECT_STOP_REQUESTED to row("cast.recovery.unknown_effect_stop_requested", NextSafeAction.WAIT_AND_OBSERVE, StopDispositionKind.REQUESTED),
        RecoverySubstate.UNKNOWN_EFFECT_STOP_IN_PROGRESS to row("cast.recovery.unknown_effect_stop_in_progress", NextSafeAction.WAIT_AND_OBSERVE, StopDispositionKind.IN_PROGRESS),
        RecoverySubstate.TRANSPORT_PREMUTATION_IDLE to row("cast.recovery.transport_premutation_idle", NextSafeAction.RETRY_CONNECT_BOUNDED, StopDispositionKind.COMPLETED),
        RecoverySubstate.TRANSPORT_ACTIVE_STOP_AVAILABLE to row("cast.recovery.transport_active_stop_available", NextSafeAction.REQUEST_STOP, StopDispositionKind.AVAILABLE),
        RecoverySubstate.OBSERVATION_DIVERGED_STOP_AVAILABLE to row("cast.recovery.observation_diverged_stop_available", NextSafeAction.REQUEST_STOP, StopDispositionKind.AVAILABLE),
        RecoverySubstate.OBSERVATION_DIVERGED_WAITING to row("cast.recovery.observation_diverged_waiting", NextSafeAction.WAIT_AND_OBSERVE, StopDispositionKind.REQUESTED),
        RecoverySubstate.OBSERVATION_BUDGET_EXHAUSTED to row("cast.recovery.observation_budget_exhausted", NextSafeAction.OPEN_DIAGNOSTICS, StopDispositionKind.UNAVAILABLE, UnavailableReason.OBSERVATION_EXHAUSTED),
        RecoverySubstate.COMPENSATION_IN_PROGRESS to row("cast.recovery.compensation_in_progress", NextSafeAction.WAIT_AND_OBSERVE, StopDispositionKind.IN_PROGRESS),
        RecoverySubstate.COMPENSATION_EXHAUSTED to row("cast.recovery.compensation_exhausted", NextSafeAction.OPEN_DIAGNOSTICS, StopDispositionKind.UNAVAILABLE, UnavailableReason.COMPENSATION_EXHAUSTED),
        RecoverySubstate.PROTECTED_SINK_CONNECTED to row("cast.recovery.protected_sink_connected", NextSafeAction.REQUEST_PHONE_DISCONNECT, StopDispositionKind.UNAVAILABLE, UnavailableReason.PROTECTED_SESSION),
        RecoverySubstate.DISCONNECTED_SINK_ELIGIBLE to row("cast.recovery.disconnected_sink_eligible", NextSafeAction.TRY_ELIGIBLE_RECOVERY_ONCE, StopDispositionKind.UNAVAILABLE, UnavailableReason.RECOVERY_ACTION_ONLY),
        RecoverySubstate.DISCONNECTED_SINK_ATTEMPT_EXHAUSTED to row("cast.recovery.disconnected_sink_attempt_exhausted", NextSafeAction.OPEN_DIAGNOSTICS, StopDispositionKind.UNAVAILABLE, UnavailableReason.ONE_ATTEMPT_EXHAUSTED),
        RecoverySubstate.OCCLUSION_FAILED_CONNECTED to row("cast.recovery.occlusion_failed_connected", NextSafeAction.REQUEST_PHONE_DISCONNECT, StopDispositionKind.UNAVAILABLE, UnavailableReason.OCCLUSION_NOT_PROVEN),
        RecoverySubstate.OCCLUSION_FAILED_DISCONNECTED to row("cast.recovery.occlusion_failed_disconnected", NextSafeAction.OPEN_DIAGNOSTICS, StopDispositionKind.UNAVAILABLE, UnavailableReason.OCCLUSION_NOT_PROVEN),
        RecoverySubstate.JOURNAL_CORRUPT_DIAGNOSTIC to row("cast.recovery.journal_corrupt_diagnostic", NextSafeAction.OPEN_DIAGNOSTICS, StopDispositionKind.UNAVAILABLE, UnavailableReason.BASELINE_UNKNOWN),
        RecoverySubstate.JOURNAL_CORRUPT_MANUAL to row("cast.recovery.journal_corrupt_manual", NextSafeAction.SHOW_PHYSICAL_INSTRUCTION, StopDispositionKind.UNAVAILABLE, UnavailableReason.BASELINE_UNKNOWN),
        RecoverySubstate.UNSUPPORTED_PROFILE_IDLE to row("cast.recovery.unsupported_profile_idle", NextSafeAction.OPEN_PROFILE_SETUP, StopDispositionKind.COMPLETED),
        RecoverySubstate.UNSUPPORTED_PROFILE_ACTIVE_UNKNOWN to row("cast.recovery.unsupported_profile_active_unknown", NextSafeAction.OPEN_DIAGNOSTICS, StopDispositionKind.UNAVAILABLE, UnavailableReason.PROFILE_UNSUPPORTED_ACTIVE),
    ).also { check(it.keys == RecoverySubstate.entries.toSet()) }

    /** Durable Stop, recovery/manual and any active transaction always outrank automation. */
    private val DOMINANT_STATES: Set<CoarseState> = setOf(
        CoarseState.UNKNOWN, CoarseState.LEGACY_ACTIVE_READ_ONLY, CoarseState.STOP_REQUESTED,
        CoarseState.RESTORING, CoarseState.RECOVERING, CoarseState.RECOVERY_PENDING,
        CoarseState.MANUAL_REQUIRED,
    )
}

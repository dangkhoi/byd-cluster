package com.byd.clusternav.modules.clustercast.v2

import java.time.Instant
import java.util.Collections
import java.util.UUID

typealias Sha256LowerHex = String

/** Immutable Stage-2 contracts. Nothing in this package is wired to the legacy Cast runtime. */
const val CAST_ENVELOPE_SCHEMA_VERSION = 3
const val CAST_UI_SCHEMA_VERSION = 5
const val CAST_UI_SCHEMA_HASH =
    "79595611424c083cca80b87002e65ee16097f668b09ea8f4d721235a2f060918"

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))
private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))

private fun valueHash(vararg values: Any?): Int = values.contentHashCode()

enum class EngineVersion { LEGACY, V2 }
enum class StableState { IDLE_VERIFIED, ACTIVE_VERIFIED, ACTIVE_DEGRADED, RECOVERY_PENDING, MANUAL_REQUIRED }
enum class OperationPhase { PREPARING, ACTIVATING, SWITCHING, VERIFYING, STOP_REQUESTED, RESTORING, RECOVERING }
enum class CastOperation { BOOTSTRAP, CAST, SWITCH, STOP, RECOVER, APPLY_GEOMETRY }
enum class LedgerEffect { PLANNED, ISSUED, OBSERVED, REJECTED }
enum class TargetClass { NORMAL, PROJECTION_SINK, KEEP_SESSION, UNKNOWN_PROTECTED }

/**
 * Cluster style, chosen per application exactly as V1 proved necessary: one global flag made every
 * app inherit the previous app's cluster shape. CURVED keeps the km/h gauge, RECT uses the full pane.
 */
enum class ClusterStyle { CURVED, RECT }

enum class CommandKind(val mutating: Boolean) {
    AM_STACK_LIST(false), WM_DISPLAYS(false), DISPLAY_STATE(false), PROFILE_STATE(false),
    ANIMATION_STATE(false), APP_OPS_STATE(false), PHONE_SESSION_STATE(false), PACKAGE_RESOLVE(false),
    SEAL_DL3_BOOTSTRAP_30(true), SEAL_DL3_BOOTSTRAP_31(true),
    SEAL_DL3_BOOTSTRAP_16(true), SEAL_DL3_BOOTSTRAP_35(true),
    SEAL_DL3_COMPENSATE_18(true), SEAL_DL3_COMPENSATE_0(true),
    START_FRESH_NORMAL(true), RESUME_PROTECTED(true), RETURN_NORMAL_TO_MAIN(true),
    RETURN_PROTECTED_GENTLY(true), FORCE_STOP_NORMAL(true), APPLY_TASK_GEOMETRY(true),
    APPLY_DISPLAY_GEOMETRY(true), RESET_CLEAN_DISPLAY(true), DISCONNECTED_SINK_RECOVERY_ONCE(true),

    /**
     * Field-proven placement ladder (0.72). Every step is journaled like any other mutation, but the
     * order is the one verified on the vehicle: keep the running session first, escalate only when a
     * gentler rung provably failed to land the task on the cluster display.
     */
    SET_FORCE_RESIZABLE(true),
    DISABLE_TRANSITION_ANIMATION(true),
    RESTORE_TRANSITION_ANIMATION(true),
    BLOCK_PIP(true),
    RESTORE_PIP(true),
    PRE_OPEN_ON_MAIN(true),
    PLACE_KEEP_SESSION(true),
    MOVE_STACK_TO_CLUSTER(true),
    REASSERT_ON_CLUSTER(true),
    FIT_CLUSTER_COMPOSITE(true),
}

data class CastTarget(val packageName: String, val taskId: Int, val displayId: Int)
data class ProtectedResidue(val packageName: String, val taskId: Int, val visibility: ResidueVisibility)
enum class ResidueVisibility { HIDDEN, UNKNOWN }
data class CastRect(val left: Int, val top: Int, val right: Int, val bottom: Int)
data class AcceptedGeometry(val bounds: CastRect, val densityDpi: Int?, val profileId: String)
enum class AdjustmentApplyState {
    SAVED_ONLY, APPLYING, APPLIED_VERIFIED, FAILED_UNVERIFIED, FAILED_RESTORED, FROZEN,
}
data class AdjustmentDraft(
    val target: CastTarget,
    val expectedDisplayIdentity: String,
    val durableEpoch: Long,
    val acceptedGeometry: AcceptedGeometry,
    val entrySnapshot: AcceptedGeometry,
    val localDraft: AcceptedGeometry,
    val previousVerifiedApply: AcceptedGeometry?,
    val lastVerifiedApply: AcceptedGeometry?,
    val operationId: UUID?,
    val dirty: Boolean,
    val applyState: AdjustmentApplyState,
    val validationError: String?,
)

class CastBaseline(
    occupants: Set<String> = emptySet(),
    val geometry: AcceptedGeometry? = null,
    animationPerKey: Map<String, String> = emptyMap(),
    val pipMode: String? = null,
    val profile: String? = null,
) {
    val occupants: Set<String> = immutableSet(occupants)
    val animationPerKey: Map<String, String> = immutableMap(animationPerKey)
    override fun equals(other: Any?) = other is CastBaseline && occupants == other.occupants &&
        geometry == other.geometry && animationPerKey == other.animationPerKey &&
        pipMode == other.pipMode && profile == other.profile
    override fun hashCode() = valueHash(occupants, geometry, animationPerKey, pipMode, profile)
    override fun toString() = "CastBaseline(occupants=$occupants, geometry=$geometry, animationPerKey=$animationPerKey, pipMode=$pipMode, profile=$profile)"
}

data class LedgerStep(
    val stepId: String, val precondition: String, val commandKind: CommandKind,
    val gatewayGeneration: Long, val issuedAtEpochMillis: Long?, val effect: LedgerEffect,
    val compensation: CommandKind?, val compensationObserved: Boolean,
    val compensationIssuedAtEpochMillis: Long? = null,
    val compensationGatewayGeneration: Long? = null,
    val compensationEffect: LedgerEffect = LedgerEffect.PLANNED,
)

data class StableCastSession(
    val state: StableState, val engineVersion: EngineVersion, val createdByBuild: String,
    val profileExport: String?, val expectedDisplayIdentity: String, val baseline: CastBaseline,
    val activeTarget: CastTarget?, val protectedResidue: ProtectedResidue?,
    val acceptedGeometry: AcceptedGeometry?, val lastVerifiedAtEpochMillis: Long,
)

class CastTransaction(
    val operationId: UUID, val epoch: Long, val operation: CastOperation, val phase: OperationPhase,
    val sourcePkg: String?, val targetPkg: String?, val targetClass: String?,
    val expectedDisplayIdentity: String, val baseline: CastBaseline, ledger: List<LedgerStep>,
    val retries: Int, val deadlineAtEpochMillis: Long, val lastFailure: String?,
    val expectedPostcondition: String, val compensationUsed: Boolean,
    val requestedGeometry: AcceptedGeometry? = null,
) {
    val ledger: List<LedgerStep> = immutableList(ledger)
    override fun equals(other: Any?) = other is CastTransaction && operationId == other.operationId &&
        epoch == other.epoch && operation == other.operation && phase == other.phase &&
        sourcePkg == other.sourcePkg && targetPkg == other.targetPkg && targetClass == other.targetClass &&
        expectedDisplayIdentity == other.expectedDisplayIdentity && baseline == other.baseline &&
        ledger == other.ledger && retries == other.retries && deadlineAtEpochMillis == other.deadlineAtEpochMillis &&
        lastFailure == other.lastFailure && expectedPostcondition == other.expectedPostcondition &&
        compensationUsed == other.compensationUsed && requestedGeometry == other.requestedGeometry
    override fun hashCode() = valueHash(operationId, epoch, operation, phase, sourcePkg, targetPkg, targetClass,
        expectedDisplayIdentity, baseline, ledger, retries, deadlineAtEpochMillis, lastFailure,
        expectedPostcondition, compensationUsed, requestedGeometry)
    override fun toString() = "CastTransaction(operationId=$operationId, epoch=$epoch, operation=$operation, phase=$phase, ledger=$ledger)"
}

data class CastSessionEnvelope(
    val schemaVersion: Int = CAST_ENVELOPE_SCHEMA_VERSION,
    val checksum: String = "",
    val durableEpoch: Long,
    val bootId: String,
    val stopRequested: Boolean,
    val pendingIntent: PendingCastIntent?,
    val effectiveUiVersion: EngineVersion,
    val pendingUiRollback: Boolean,
    val stableSession: StableCastSession?,
    val transaction: CastTransaction?,
    val adjustmentDraft: AdjustmentDraft? = null,
    val automationConfig: AutomationConfig = AutomationConfig(),
    val bootAutomationRequest: BootAutomationRequest? = null,
    val lastAutomationOutcome: AutomationOutcome? = null,
) {
    init {
        require(bootAutomationRequest == null || bootAutomationRequest.bootId == bootId) {
            "automation request must belong to the envelope boot"
        }
    }

    /** Target of the current pending placement regardless of origin. */
    val pendingPackage: String? get() = pendingIntent?.packageName

    /** True only for a pending placement created by the user, never by automation. */
    val pendingIsUser: Boolean get() = pendingIntent?.origin == CastIntentOrigin.USER

    fun withPendingPackage(packageName: String?): CastSessionEnvelope =
        copy(pendingIntent = packageName?.let { PendingCastIntent(it) })
}

sealed interface ObservationValue<out T> {
    data class Known<T>(val value: T) : ObservationValue<T>
    data class Unknown(val reason: String) : ObservationValue<Nothing>
    data class Unsupported(val reason: String) : ObservationValue<Nothing>
}

data class RawObservation(
    val amStacks: String,
    val wmDisplays: String,
    val displays: String,
    val profile: String,
    val animations: String = "",
    val appOps: String = "",
)

enum class ObservedCoarseState { UNKNOWN, IDLE_CLEAN, ACTIVE_SINGLE, ACTIVE_MULTI, DIRTY_IDLE, SPLIT_BRAIN }
class ObservedState(
    val coarseState: ObservedCoarseState,
    val displayIdentity: String?,
    val target: CastTarget?,
    occupants: Set<String>,
    val protectedResidue: ProtectedResidue?,
    val geometry: AcceptedGeometry?,
    animationPerKey: Map<String, String> = emptyMap(),
    val pipMode: String? = null,
    val profile: String? = null,
    val displayName: String? = null,
) {
    val occupants: Set<String> = immutableSet(occupants)
    val animationPerKey: Map<String, String> = immutableMap(animationPerKey)
    fun copy(
        coarseState: ObservedCoarseState = this.coarseState,
        displayIdentity: String? = this.displayIdentity,
        target: CastTarget? = this.target,
        occupants: Set<String> = this.occupants,
        protectedResidue: ProtectedResidue? = this.protectedResidue,
        geometry: AcceptedGeometry? = this.geometry,
        animationPerKey: Map<String, String> = this.animationPerKey,
        pipMode: String? = this.pipMode,
        profile: String? = this.profile,
        displayName: String? = this.displayName,
    ) = ObservedState(
        coarseState, displayIdentity, target, occupants, protectedResidue, geometry,
        animationPerKey, pipMode, profile, displayName,
    )
    override fun equals(other: Any?) = other is ObservedState && coarseState == other.coarseState &&
        displayIdentity == other.displayIdentity && target == other.target && occupants == other.occupants &&
        protectedResidue == other.protectedResidue && geometry == other.geometry &&
        animationPerKey == other.animationPerKey && pipMode == other.pipMode && profile == other.profile &&
        displayName == other.displayName
    override fun hashCode() = valueHash(
        coarseState, displayIdentity, target, occupants, protectedResidue, geometry,
        animationPerKey, pipMode, profile, displayName,
    )
    override fun toString() = "ObservedState(coarseState=$coarseState, displayIdentity=$displayIdentity, target=$target, occupants=$occupants)"
}

enum class CastIntentKind { CAST, SWITCH, STOP, RECOVER, APPLY_GEOMETRY }
data class DisconnectedSinkRecoveryProof(
    val ownerPackage: String,
    val first: ObservedState,
    val second: ObservedState,
    val phoneDisconnected: Boolean,
    val projectionComponent: Boolean,
    val consequenceConfirmed: Boolean,
)
data class CastIntent(
    val kind: CastIntentKind,
    val targetPackage: String? = null,
    val targetComponent: String? = null,
    val geometry: AcceptedGeometry? = null,
    val expectedTarget: CastTarget? = null,
    val recoveryProof: DisconnectedSinkRecoveryProof? = null,
    /** Explicit user opt-in to the destructive R3 rung (force-stop + clear-task relaunch). */
    val allowDestructive: Boolean = false,
)
data class PlannerSnapshot(
    val observed: ObservationValue<ObservedState>, val stableSession: StableCastSession?,
    val targetClass: TargetClass, val installed: Boolean, val hasLauncher: Boolean, val plannerEpoch: Long,
)
data class PlannedStep(val id: String, val commandKind: CommandKind, val guard: String)
class CastPlan(
    val operation: CastOperation,
    val epoch: Long,
    steps: List<PlannedStep>,
    val expectedPostcondition: String,
    plannerAllowedActions: Set<CastAction>,
    val targetClass: TargetClass? = null,
    val expectedDisplayIdentity: String? = null,
    val expectedTarget: CastTarget? = null,
    val geometry: AcceptedGeometry? = null,
) {
    val steps: List<PlannedStep> = immutableList(steps)
    val plannerAllowedActions: Set<CastAction> = immutableSet(plannerAllowedActions)
    override fun equals(other: Any?) = other is CastPlan && operation == other.operation && epoch == other.epoch &&
        steps == other.steps && expectedPostcondition == other.expectedPostcondition &&
        plannerAllowedActions == other.plannerAllowedActions && targetClass == other.targetClass &&
        expectedDisplayIdentity == other.expectedDisplayIdentity && expectedTarget == other.expectedTarget &&
        geometry == other.geometry
    override fun hashCode() = valueHash(operation, epoch, steps, expectedPostcondition, plannerAllowedActions,
        targetClass, expectedDisplayIdentity, expectedTarget, geometry)
    override fun toString() = "CastPlan(operation=$operation, epoch=$epoch, steps=$steps, expectedPostcondition=$expectedPostcondition, plannerAllowedActions=$plannerAllowedActions, targetClass=$targetClass, expectedDisplayIdentity=$expectedDisplayIdentity, expectedTarget=$expectedTarget, geometry=$geometry)"
}
sealed interface PlanResult {
    data class Ready(val plan: CastPlan) : PlanResult
    data class Blocked(val reason: String) : PlanResult
}

data class ManifestCase(
    val id: Int, val name: String, val successTerminal: String, val failureTerminal: String,
    val retryPolicy: String, val evidenceGate: String,
)

enum class CastAction {
    CAST, SWITCH, ADJUST, STOP, CHOOSE_ANOTHER_APP, OPEN_APP_MANAGER, RETRY_CONNECT,
    OPEN_DIAGNOSTICS, REQUEST_PHONE_DISCONNECT, TRY_ELIGIBLE_RECOVERY_ONCE,
    SHOW_PHYSICAL_INSTRUCTION, OPEN_PROFILE_SETUP,

    /**
     * Chọn app sẽ chiếu. Đây là phép ghi TUỲ CHỌN CỤC BỘ: không phát lệnh nào ra xe, không đụng
     * transaction, không đổi gì trên cụm — việc chiếu chỉ xảy ra khi bấm Chiếu. Vì thế nó nằm trong
     * nhóm luôn được phép, cùng với Chẩn đoán.
     *
     * Có mục này vì 2026-07-27 phát hiện tile app bị vô hiệu hoá theo `OPEN_APP_MANAGER ||
     * CHOOSE_ANOTHER_APP`; ở trạng thái "cần xử lý thủ công" projector không cấp hai phép đó, nên
     * người dùng không chọn nổi app để chuẩn bị — bị khoá đúng như sáng cùng ngày.
     */
    SELECT_TARGET_APP,
}
enum class CoarseState {
    UNKNOWN, COLD_PRISTINE, LEGACY_ACTIVE_READ_ONLY, PREPARING, ACTIVATING, SWITCHING, VERIFYING,
    STOP_REQUESTED, RESTORING, RECOVERING, IDLE_VERIFIED, ACTIVE_VERIFIED,
    ACTIVE_DEGRADED, RECOVERY_PENDING, MANUAL_REQUIRED,
}
enum class DisabledReason {
    NO_ACTIVE_TARGET, OPERATION_IN_FLIGHT, LEGACY_SESSION_UNSAFE, CONTRACT_UNMAPPED,
    PROTECTED_SESSION, GEOMETRY_LIMITED, RECOVERY_PENDING, ACTION_NOT_EXPORTED,
}
enum class InteractionContextValue { PARKED, MOVING, UNKNOWN }
enum class NextSafeAction {
    NONE, REQUEST_STOP, RETRY_CONNECT_BOUNDED, WAIT_AND_OBSERVE, OPEN_DIAGNOSTICS,
    REQUEST_PHONE_DISCONNECT, TRY_ELIGIBLE_RECOVERY_ONCE,
    SHOW_PHYSICAL_INSTRUCTION, OPEN_PROFILE_SETUP,
}
enum class StopDispositionKind { AVAILABLE, REQUESTED, IN_PROGRESS, COMPLETED, UNAVAILABLE }
enum class UnavailableReason {
    LEGACY_SESSION_UNSAFE, OBSERVATION_EXHAUSTED, COMPENSATION_EXHAUSTED,
    RECOVERY_ACTION_ONLY, PROTECTED_SESSION, ONE_ATTEMPT_EXHAUSTED, OCCLUSION_NOT_PROVEN,
    BASELINE_UNKNOWN, PROFILE_UNSUPPORTED_ACTIVE, CONTRACT_UNMAPPED,
}
enum class RecoverySubstate {
    UNKNOWN_EFFECT_STOP_AVAILABLE, UNKNOWN_EFFECT_STOP_REQUESTED, UNKNOWN_EFFECT_STOP_IN_PROGRESS,
    TRANSPORT_PREMUTATION_IDLE, TRANSPORT_ACTIVE_STOP_AVAILABLE,
    OBSERVATION_DIVERGED_STOP_AVAILABLE, OBSERVATION_DIVERGED_WAITING,
    OBSERVATION_BUDGET_EXHAUSTED, COMPENSATION_IN_PROGRESS, COMPENSATION_EXHAUSTED,
    PROTECTED_SINK_CONNECTED, DISCONNECTED_SINK_ELIGIBLE,
    DISCONNECTED_SINK_ATTEMPT_EXHAUSTED, OCCLUSION_FAILED_CONNECTED,
    OCCLUSION_FAILED_DISCONNECTED, JOURNAL_CORRUPT_DIAGNOSTIC,
    JOURNAL_CORRUPT_MANUAL, UNSUPPORTED_PROFILE_IDLE, UNSUPPORTED_PROFILE_ACTIVE_UNKNOWN,
}
data class StopDisposition(val kind: StopDispositionKind, val reason: UnavailableReason? = null)
data class InteractionContext(
    val value: InteractionContextValue, val provenance: String, val observedAt: Instant,
    val freshUntil: Instant, val disagreementReason: String?,
)
class CastUiStateV2(
    val schemaVersion: Int,
    val schemaHash: Sha256LowerHex,
    val coarseState: CoarseState,
    val stableState: StableState?,
    val operationPhase: OperationPhase?,
    val recoverySubstate: RecoverySubstate?,
    val stopDisposition: StopDisposition,
    val nextSafeAction: NextSafeAction,
    val unavailableReason: UnavailableReason?,
    allowedActions: Set<CastAction>,
    disabledReasons: Map<CastAction, DisabledReason>,
    val interactionContext: InteractionContext,
    val target: CastTarget?,
    val protectedResidue: ProtectedResidue?,
    val acceptedGeometry: AcceptedGeometry?,
    val operationId: UUID?,
    val durableEpoch: Long,
    val deadlineAt: Instant?,
    val automationDisposition: AutomationDisposition? = null,
    val automationReason: AutomationReason? = null,
    val automationTargetPackage: String? = null,
) {
    val allowedActions: Set<CastAction> = immutableSet(allowedActions)
    val disabledReasons: Map<CastAction, DisabledReason> = immutableMap(disabledReasons)
    override fun equals(other: Any?) = other is CastUiStateV2 && schemaVersion == other.schemaVersion &&
        schemaHash == other.schemaHash && coarseState == other.coarseState && stableState == other.stableState &&
        operationPhase == other.operationPhase && recoverySubstate == other.recoverySubstate &&
        stopDisposition == other.stopDisposition && nextSafeAction == other.nextSafeAction &&
        unavailableReason == other.unavailableReason && allowedActions == other.allowedActions &&
        disabledReasons == other.disabledReasons && interactionContext == other.interactionContext &&
        target == other.target && protectedResidue == other.protectedResidue &&
        acceptedGeometry == other.acceptedGeometry && operationId == other.operationId &&
        durableEpoch == other.durableEpoch && deadlineAt == other.deadlineAt &&
        automationDisposition == other.automationDisposition && automationReason == other.automationReason &&
        automationTargetPackage == other.automationTargetPackage
    override fun hashCode() = valueHash(schemaVersion, schemaHash, coarseState, stableState, operationPhase,
        recoverySubstate, stopDisposition, nextSafeAction, unavailableReason, allowedActions, disabledReasons,
        interactionContext, target, protectedResidue, acceptedGeometry, operationId, durableEpoch, deadlineAt,
        automationDisposition, automationReason, automationTargetPackage)
}

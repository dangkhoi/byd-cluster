package com.byd.clusternav.modules.clustercast.v2

import java.util.UUID

/**
 * Pure durable automation contract for candidate 0.71.
 *
 * Nothing here observes, plans or mutates. It defines the exact envelope-owned configuration,
 * one origin-tagged pending intent, one boot request per boot and the closed terminal reason set.
 * Invalid combinations throw, so the codec fails closed as corrupt instead of silently disabling
 * a request the user consented to.
 */
const val CURRENT_AUTOMATION_CONSENT_VERSION = 1

internal val AUTOMATION_PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

fun isAutomationPackage(value: String?): Boolean = value != null && AUTOMATION_PACKAGE.matches(value)

enum class CastIntentOrigin { USER, BOOT_AUTO }

/** Origin-tagged replacement for the ambiguous v1/v2 pending string slot. */
data class PendingCastIntent(
    val packageName: String,
    val origin: CastIntentOrigin = CastIntentOrigin.USER,
    val automationRequestId: UUID? = null,
) {
    init {
        require(AUTOMATION_PACKAGE.matches(packageName)) { "pending package grammar" }
        require((origin == CastIntentOrigin.BOOT_AUTO) == (automationRequestId != null)) {
            "automationRequestId exists exactly for BOOT_AUTO origin"
        }
    }

    fun matches(requestId: UUID?): Boolean =
        origin == CastIntentOrigin.BOOT_AUTO && requestId != null && automationRequestId == requestId
}

/** Operational configuration. Only the Cast envelope owns it, under a monotonic revision. */
data class AutomationConfig(
    val revision: Long = 0L,
    val defaultPackage: String? = null,
    val autoCastEnabled: Boolean = false,
    val consentVersion: Int? = null,
) {
    init {
        require(revision >= 0L) { "revision cannot be negative" }
        require(defaultPackage == null || AUTOMATION_PACKAGE.matches(defaultPackage)) { "default package grammar" }
        require(consentVersion == null || consentVersion >= 1) { "consent version must be positive" }
        if (autoCastEnabled) {
            require(defaultPackage != null) { "enabled automation requires a default package" }
            require(consentVersion != null) { "enabled automation requires recorded consent" }
        }
    }

    /** True only when the persisted consent matches the disclosure this build can present. */
    val consentCurrent: Boolean get() = consentVersion == CURRENT_AUTOMATION_CONSENT_VERSION

    /** True when a request may be recorded at the next post-unlock boot. */
    val armable: Boolean get() = autoCastEnabled && consentCurrent && defaultPackage != null

    fun withDefault(packageName: String?): AutomationConfig {
        val next = packageName?.takeIf { AUTOMATION_PACKAGE.matches(it) }
        require(packageName == null || next != null) { "default package grammar" }
        val cleared = next == null || next != defaultPackage
        return AutomationConfig(
            revision = Math.addExact(revision, 1L),
            defaultPackage = next,
            autoCastEnabled = if (cleared) false else autoCastEnabled,
            consentVersion = if (cleared && next == null) null else consentVersion,
        )
    }

    fun accepting(consent: Int = CURRENT_AUTOMATION_CONSENT_VERSION): AutomationConfig {
        require(defaultPackage != null) { "cannot enable automation without a default" }
        return AutomationConfig(Math.addExact(revision, 1L), defaultPackage, true, consent)
    }

    fun revoking(): AutomationConfig =
        AutomationConfig(Math.addExact(revision, 1L), defaultPackage, false, null)
}

enum class AutomationRequestState { PENDING, DEFERRED, CLAIMED, COMPLETED, BLOCKED, SUPERSEDED }

enum class AutomationReason {
    PRIOR_JOURNAL, STOP_REQUESTED, RECOVERY_OR_MANUAL, PROFILE_UNSUPPORTED, TARGET_INVALID,
    CONFIG_CHANGED, CONSENT_REVOKED, USER_SUPERSEDED, STOP_SUPERSEDED, BOOT_ROLLOVER,
    FOREGROUND_START_DENIED, NOTIFICATION_PERMISSION_DENIED, NOTIFICATION_START_FAILED,
    DEADLINE_EXPIRED, CLAIMED_NO_EFFECT_AFTER_RESTART, KNOWN_FAILURE, UNKNOWN_EFFECT,
    REEVALUATION_EXHAUSTED,
}

/** UI-facing disposition. DISABLED/ARMED/REVIEW_REQUIRED are derived, never persisted states. */
enum class AutomationDisposition {
    DISABLED, REVIEW_REQUIRED, ARMED, PENDING, DEFERRED, CLAIMED, COMPLETED, BLOCKED, SUPERSEDED,
}

object AutomationReasons {
    val supersede: Set<AutomationReason> = setOf(
        AutomationReason.USER_SUPERSEDED, AutomationReason.STOP_SUPERSEDED,
        AutomationReason.CONFIG_CHANGED, AutomationReason.CONSENT_REVOKED,
        AutomationReason.BOOT_ROLLOVER,
    )
    val block: Set<AutomationReason> = setOf(
        AutomationReason.PRIOR_JOURNAL, AutomationReason.STOP_REQUESTED,
        AutomationReason.RECOVERY_OR_MANUAL, AutomationReason.PROFILE_UNSUPPORTED,
        AutomationReason.TARGET_INVALID, AutomationReason.FOREGROUND_START_DENIED,
        AutomationReason.NOTIFICATION_PERMISSION_DENIED, AutomationReason.NOTIFICATION_START_FAILED,
        AutomationReason.DEADLINE_EXPIRED, AutomationReason.CLAIMED_NO_EFFECT_AFTER_RESTART,
        AutomationReason.KNOWN_FAILURE, AutomationReason.UNKNOWN_EFFECT,
        AutomationReason.REEVALUATION_EXHAUSTED,
    )

    fun compatible(state: AutomationRequestState, reason: AutomationReason?): Boolean = when (state) {
        AutomationRequestState.PENDING, AutomationRequestState.CLAIMED,
        AutomationRequestState.COMPLETED -> reason == null
        AutomationRequestState.DEFERRED -> reason == AutomationReason.PRIOR_JOURNAL
        AutomationRequestState.BLOCKED -> reason != null && reason in block
        AutomationRequestState.SUPERSEDED -> reason != null && reason in supersede
    }
}

/** At most one request exists per boot. Attempt is the single claim budget. */
data class BootAutomationRequest(
    val requestId: UUID,
    val bootId: String,
    val targetPackage: String,
    val configRevision: Long,
    val consentVersion: Int,
    val state: AutomationRequestState,
    val attempt: Int,
    val reevaluationCount: Int,
    val requestedAtEpochMillis: Long,
    val claimedAtEpochMillis: Long? = null,
    val terminalAtEpochMillis: Long? = null,
    val reason: AutomationReason? = null,
) {
    val origin: CastIntentOrigin get() = CastIntentOrigin.BOOT_AUTO

    init {
        require(bootId.isNotBlank()) { "request boot id" }
        require(AUTOMATION_PACKAGE.matches(targetPackage)) { "request target grammar" }
        require(configRevision >= 0L) { "request revision" }
        require(consentVersion >= 1) { "request consent version" }
        require(attempt == 0 || attempt == 1) { "attempt budget is 0 or 1" }
        require(reevaluationCount == 0 || reevaluationCount == 1) { "one re-evaluation at most" }
        require(requestedAtEpochMillis > 0L) { "requestedAt must be set" }
        require(AutomationReasons.compatible(state, reason)) { "reason incompatible with $state" }
        when (state) {
            AutomationRequestState.PENDING, AutomationRequestState.DEFERRED -> {
                require(attempt == 0) { "$state cannot consume the claim budget" }
                require(claimedAtEpochMillis == null && terminalAtEpochMillis == null) { "$state is not claimed or terminal" }
            }
            AutomationRequestState.CLAIMED -> {
                require(attempt == 1) { "CLAIMED consumes the claim budget" }
                require(claimedAtEpochMillis != null && claimedAtEpochMillis >= requestedAtEpochMillis) { "claimedAt" }
                require(terminalAtEpochMillis == null) { "CLAIMED is not terminal" }
            }
            AutomationRequestState.COMPLETED -> {
                require(attempt == 1) { "COMPLETED requires a claim" }
                require(claimedAtEpochMillis != null) { "COMPLETED requires claimedAt" }
                require(terminalAtEpochMillis != null && terminalAtEpochMillis >= claimedAtEpochMillis) { "terminalAt" }
            }
            AutomationRequestState.BLOCKED, AutomationRequestState.SUPERSEDED -> {
                require((claimedAtEpochMillis != null) == (attempt == 1)) { "claimedAt exists iff attempt=1" }
                require(terminalAtEpochMillis != null && terminalAtEpochMillis >= requestedAtEpochMillis) { "terminalAt" }
                require(claimedAtEpochMillis == null || terminalAtEpochMillis >= claimedAtEpochMillis) { "terminalAt order" }
            }
        }
    }

    val terminal: Boolean get() = state in TERMINAL_STATES
    val claimable: Boolean get() = attempt == 0 && (state == AutomationRequestState.PENDING || state == AutomationRequestState.DEFERRED)

    fun claimed(atEpochMillis: Long): BootAutomationRequest {
        require(claimable) { "only an unclaimed request may be claimed" }
        return copy(
            state = AutomationRequestState.CLAIMED,
            attempt = 1,
            claimedAtEpochMillis = maxOf(atEpochMillis, requestedAtEpochMillis),
            reason = null,
        )
    }

    fun deferred(): BootAutomationRequest {
        require(claimable) { "only an unclaimed request may defer" }
        return copy(state = AutomationRequestState.DEFERRED, reason = AutomationReason.PRIOR_JOURNAL)
    }

    fun reevaluated(): BootAutomationRequest {
        require(state == AutomationRequestState.DEFERRED) { "only a deferred request re-evaluates" }
        require(reevaluationCount == 0) { "re-evaluation budget exhausted" }
        return copy(reevaluationCount = 1)
    }

    fun terminalized(
        terminalState: AutomationRequestState,
        reason: AutomationReason?,
        atEpochMillis: Long,
    ): BootAutomationRequest {
        require(terminalState in TERMINAL_STATES) { "$terminalState is not terminal" }
        require(!terminal) { "request already terminal" }
        return copy(
            state = terminalState,
            reason = reason,
            terminalAtEpochMillis = maxOf(atEpochMillis, claimedAtEpochMillis ?: requestedAtEpochMillis),
        )
    }

    fun outcome(): AutomationOutcome {
        require(terminal) { "only a terminal request produces an outcome" }
        return AutomationOutcome(
            requestId, bootId, targetPackage, state, reason,
            checkNotNull(terminalAtEpochMillis) { "terminal request must carry terminalAt" },
        )
    }

    companion object {
        val TERMINAL_STATES: Set<AutomationRequestState> = setOf(
            AutomationRequestState.COMPLETED, AutomationRequestState.BLOCKED, AutomationRequestState.SUPERSEDED,
        )

        /** Creates the single PENDING record a post-unlock boot may commit. */
        fun pending(
            requestId: UUID,
            bootId: String,
            config: AutomationConfig,
            atEpochMillis: Long,
        ): BootAutomationRequest {
            require(config.armable) { "cannot record a request without current consent and default" }
            return BootAutomationRequest(
                requestId = requestId,
                bootId = bootId,
                targetPackage = checkNotNull(config.defaultPackage),
                configRevision = config.revision,
                consentVersion = checkNotNull(config.consentVersion),
                state = AutomationRequestState.PENDING,
                attempt = 0,
                reevaluationCount = 0,
                requestedAtEpochMillis = maxOf(atEpochMillis, 1L),
            )
        }
    }
}

/** Last terminal automation evidence, retained for presentation across boots. */
data class AutomationOutcome(
    val requestId: UUID,
    val bootId: String,
    val targetPackage: String,
    val terminalState: AutomationRequestState,
    val reason: AutomationReason?,
    val terminalAtEpochMillis: Long,
) {
    init {
        require(bootId.isNotBlank()) { "outcome boot id" }
        require(AUTOMATION_PACKAGE.matches(targetPackage)) { "outcome target grammar" }
        require(terminalState in BootAutomationRequest.TERMINAL_STATES) { "outcome must be terminal" }
        require(AutomationReasons.compatible(terminalState, reason)) { "outcome reason incompatible" }
        require(terminalAtEpochMillis > 0L) { "outcome terminalAt" }
    }
}

/** Pure projection of durable automation truth. No I/O, no policy duplication elsewhere. */
object CastAutomationPolicy {
    fun disposition(
        config: AutomationConfig,
        request: BootAutomationRequest?,
        outcome: AutomationOutcome?,
        currentBootId: String? = null,
    ): AutomationDisposition {
        val current = request?.takeIf { currentBootId == null || it.bootId == currentBootId }
        if (current != null) {
            return when (current.state) {
                AutomationRequestState.PENDING -> AutomationDisposition.PENDING
                AutomationRequestState.DEFERRED -> AutomationDisposition.DEFERRED
                AutomationRequestState.CLAIMED -> AutomationDisposition.CLAIMED
                AutomationRequestState.COMPLETED -> AutomationDisposition.COMPLETED
                AutomationRequestState.BLOCKED -> AutomationDisposition.BLOCKED
                AutomationRequestState.SUPERSEDED -> AutomationDisposition.SUPERSEDED
            }
        }
        val stale = outcome?.takeIf { currentBootId != null && it.bootId == currentBootId }
        if (stale != null) {
            return when (stale.terminalState) {
                AutomationRequestState.COMPLETED -> AutomationDisposition.COMPLETED
                AutomationRequestState.BLOCKED -> AutomationDisposition.BLOCKED
                else -> AutomationDisposition.SUPERSEDED
            }
        }
        return when {
            config.autoCastEnabled && config.defaultPackage != null && !config.consentCurrent ->
                AutomationDisposition.REVIEW_REQUIRED
            config.armable -> AutomationDisposition.ARMED
            else -> AutomationDisposition.DISABLED
        }
    }

    /**
     * Exact CAS predicate: a request may only be claimed while its own boot, revision, enable flag,
     * current consent and default target still match the configuration it was recorded from.
     */
    fun claimAllowed(
        config: AutomationConfig,
        request: BootAutomationRequest,
        envelopeBootId: String,
    ): Boolean = request.claimable &&
        request.bootId == envelopeBootId &&
        config.armable &&
        config.revision == request.configRevision &&
        config.consentVersion == request.consentVersion &&
        config.defaultPackage == request.targetPackage

    /** Typed reason when configuration truth moved under an unclaimed or effect-free request. */
    fun supersessionReason(
        config: AutomationConfig,
        request: BootAutomationRequest,
    ): AutomationReason? = when {
        config.defaultPackage != request.targetPackage -> AutomationReason.CONFIG_CHANGED
        config.consentVersion == null || !config.consentCurrent -> AutomationReason.CONSENT_REVOKED
        !config.autoCastEnabled -> AutomationReason.CONFIG_CHANGED
        config.revision != request.configRevision -> AutomationReason.CONFIG_CHANGED
        else -> null
    }
}

package com.byd.clusternav.modules.clustercast.v2

import java.util.UUID

/**
 * Durable owner of operational automation configuration and the single per-boot request.
 *
 * Every transition is one locked store transaction on the existing envelope. This type never
 * observes the device, plans, or issues a command: orchestration stays in the manual-intent path.
 */
class CastAutomationSettings(
    private val store: CastSessionStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    sealed interface ClaimResult {
        data class Claimed(val request: BootAutomationRequest) : ClaimResult
        data class Rejected(val reason: String) : ClaimResult
        data class Superseded(val reason: AutomationReason) : ClaimResult
    }

    fun config(): AutomationConfig = envelope()?.automationConfig ?: AutomationConfig()

    fun request(): BootAutomationRequest? = envelope()?.bootAutomationRequest

    fun outcome(): AutomationOutcome? = envelope()?.lastAutomationOutcome

    fun disposition(): AutomationDisposition = envelope()?.let {
        CastAutomationPolicy.disposition(it.automationConfig, it.bootAutomationRequest, it.lastAutomationOutcome, it.bootId)
    } ?: AutomationDisposition.DISABLED

    /** Set, replace or clear the one durable default. Clearing also disables automation. */
    fun setDefault(packageName: String?): AutomationConfig =
        commitConfig { it.automationConfig.withDefault(packageName) }

    /** Explicit Accept of the current disclosure. Requires an existing default. */
    fun accept(consentVersion: Int = CURRENT_AUTOMATION_CONSENT_VERSION): AutomationConfig {
        require(consentVersion == CURRENT_AUTOMATION_CONSENT_VERSION) {
            "only the current disclosure version may be accepted"
        }
        return commitConfig { it.automationConfig.accepting(consentVersion) }
    }

    /** Disable or Revoke. Consent is cleared so a later Enable must re-accept the disclosure. */
    fun revoke(): AutomationConfig = commitConfig { it.automationConfig.revoking() }

    /**
     * One-time legacy migration: an old auto-cast package becomes the default only, never enabled
     * and never consented. It is ignored when a default already exists.
     */
    fun migrateLegacyDefault(packageName: String): Boolean {
        if (!isAutomationPackage(packageName)) return false
        return store.locked {
            val current = (read() as? StoreRead.Loaded)?.envelope ?: return@locked false
            if (current.automationConfig.revision != 0L) return@locked false
            if (current.automationConfig.defaultPackage != null) return@locked false
            if (current.automationConfig.autoCastEnabled) return@locked false
            update { it.copy(automationConfig = it.automationConfig.withDefault(packageName)) }
            true
        }
    }

    /**
     * Bounded receiver transaction: adopt the observed boot identity, then return the single request
     * for this boot. A duplicate delivery returns the same still-current record instead of a second
     * one. Nothing is enqueued or executed here.
     */
    fun recordOrGet(
        observedBootId: String,
        requestId: UUID = UUID.randomUUID(),
    ): BootAutomationRequest? = store.locked {
        val now = clock()
        val initialized = initializeForBoot(observedBootId, now)
        val existing = initialized.bootAutomationRequest
        if (existing != null) return@locked existing
        val config = initialized.automationConfig
        if (!config.armable) return@locked null
        update {
            it.copy(bootAutomationRequest = BootAutomationRequest.pending(requestId, observedBootId, config, now))
        }.bootAutomationRequest
    }

    /**
     * Exactly one claim per boot. The CAS re-verifies boot identity, config revision, enable flag,
     * current consent and exact default equality before the attempt budget is consumed.
     */
    fun claim(requestId: UUID): ClaimResult = store.locked {
        val current = (read() as? StoreRead.Loaded)?.envelope
            ?: return@locked ClaimResult.Rejected("durable store unavailable")
        val request = current.bootAutomationRequest
            ?: return@locked ClaimResult.Rejected("no request for this boot")
        if (request.requestId != requestId) return@locked ClaimResult.Rejected("request superseded by another id")
        if (request.terminal) return@locked ClaimResult.Rejected("request already terminal: ${request.state}")
        if (!request.claimable) return@locked ClaimResult.Rejected("claim budget already consumed")
        if (current.stopRequested) {
            return@locked supersede(request, AutomationReason.STOP_SUPERSEDED)
        }
        if (current.pendingIntent?.origin == CastIntentOrigin.USER) {
            return@locked supersede(request, AutomationReason.USER_SUPERSEDED)
        }
        CastAutomationPolicy.supersessionReason(current.automationConfig, request)?.let {
            return@locked supersede(request, it)
        }
        if (!CastAutomationPolicy.claimAllowed(current.automationConfig, request, current.bootId)) {
            return@locked ClaimResult.Rejected("claim preconditions no longer hold")
        }
        val claimed = update { it.copy(bootAutomationRequest = request.claimed(clock())) }.bootAutomationRequest
        ClaimResult.Claimed(checkNotNull(claimed))
    }

    /** Records a bounded deferral. Only a prior-journal condition may defer, and only once. */
    fun defer(requestId: UUID): BootAutomationRequest? = store.locked {
        val current = (read() as? StoreRead.Loaded)?.envelope ?: return@locked null
        val request = current.bootAutomationRequest?.takeIf { it.requestId == requestId && it.claimable }
            ?: return@locked null
        update { it.copy(bootAutomationRequest = request.deferred()) }.bootAutomationRequest
    }

    fun consumeReevaluation(requestId: UUID): BootAutomationRequest? = store.locked {
        val current = (read() as? StoreRead.Loaded)?.envelope ?: return@locked null
        val request = current.bootAutomationRequest ?: return@locked null
        if (request.requestId != requestId) return@locked null
        if (request.state != AutomationRequestState.DEFERRED || request.reevaluationCount != 0) return@locked null
        update { it.copy(bootAutomationRequest = request.reevaluated()) }.bootAutomationRequest
    }

    /** Terminalizes the exact still-current request and archives its outcome in one write. */
    fun terminalize(
        requestId: UUID,
        state: AutomationRequestState,
        reason: AutomationReason?,
    ): AutomationOutcome? = store.locked {
        val current = (read() as? StoreRead.Loaded)?.envelope ?: return@locked null
        val request = current.bootAutomationRequest ?: return@locked null
        if (request.requestId != requestId || request.terminal) return@locked null
        val terminal = request.terminalized(state, reason, clock())
        update {
            it.copy(
                bootAutomationRequest = terminal,
                lastAutomationOutcome = terminal.outcome(),
                pendingIntent = it.pendingIntent?.takeUnless { pending -> pending.matches(requestId) },
            )
        }.lastAutomationOutcome
    }

    /**
     * Config, manual target and Stop precedence. Before any issued effect the request is superseded
     * with zero mutation; once a row is ISSUED the caller keeps the existing Stop/recovery path and
     * this returns null instead of pretending the effect was undone.
     */
    fun supersedeIfEffectFree(reason: AutomationReason): AutomationOutcome? = store.locked {
        val current = (read() as? StoreRead.Loaded)?.envelope ?: return@locked null
        val request = current.bootAutomationRequest?.takeUnless { it.terminal } ?: return@locked null
        if (!effectFree(current, request)) return@locked null
        val terminal = request.terminalized(AutomationRequestState.SUPERSEDED, reason, clock())
        update {
            it.copy(
                bootAutomationRequest = terminal,
                lastAutomationOutcome = terminal.outcome(),
                pendingIntent = it.pendingIntent?.takeUnless { pending ->
                    pending.matches(request.requestId)
                },
            )
        }.lastAutomationOutcome
    }

    /** Binds the claimed request to its durable pending placement so restarts cannot mistake it. */
    fun bindAutoPending(requestId: UUID): Boolean = store.locked {
        val current = (read() as? StoreRead.Loaded)?.envelope ?: return@locked false
        val request = current.bootAutomationRequest ?: return@locked false
        if (request.requestId != requestId || request.state != AutomationRequestState.CLAIMED) return@locked false
        if (current.stopRequested || current.pendingIntent?.origin == CastIntentOrigin.USER) return@locked false
        update {
            it.copy(
                pendingIntent = PendingCastIntent(
                    request.targetPackage, CastIntentOrigin.BOOT_AUTO, request.requestId,
                ),
            )
        }
        true
    }

    private fun CastSessionStore.Locked.supersede(
        request: BootAutomationRequest,
        reason: AutomationReason,
    ): ClaimResult {
        val terminal = request.terminalized(AutomationRequestState.SUPERSEDED, reason, clock())
        update {
            it.copy(
                bootAutomationRequest = terminal,
                lastAutomationOutcome = terminal.outcome(),
                pendingIntent = it.pendingIntent?.takeUnless { pending -> pending.matches(request.requestId) },
            )
        }
        return ClaimResult.Superseded(reason)
    }

    private fun commitConfig(transform: (CastSessionEnvelope) -> AutomationConfig): AutomationConfig =
        store.locked {
            val current = (read() as? StoreRead.Loaded)?.envelope ?: error("Cast store is not initialized")
            val next = transform(current)
            val stale = current.bootAutomationRequest?.takeUnless { it.terminal }
            val effectFree = stale != null && effectFree(current, stale)
            val supersededReason = stale?.let { CastAutomationPolicy.supersessionReason(next, it) }
            val terminal = if (stale != null && effectFree && supersededReason != null) {
                stale.terminalized(AutomationRequestState.SUPERSEDED, supersededReason, clock())
            } else {
                null
            }
            update { envelope ->
                envelope.copy(
                    automationConfig = next,
                    bootAutomationRequest = terminal ?: envelope.bootAutomationRequest,
                    lastAutomationOutcome = terminal?.outcome() ?: envelope.lastAutomationOutcome,
                    pendingIntent = if (terminal != null && stale != null) {
                        envelope.pendingIntent?.takeUnless { it.matches(stale.requestId) }
                    } else {
                        envelope.pendingIntent
                    },
                )
            }.automationConfig
        }

    private fun envelope(): CastSessionEnvelope? = store.locked { (read() as? StoreRead.Loaded)?.envelope }

    companion object {
        /** True once any ledger row left PLANNED, meaning a remote effect may already exist. */
        fun hasIssuedEffect(envelope: CastSessionEnvelope): Boolean {
            val transaction = envelope.transaction ?: return false
            return transaction.ledger.any { it.effect != LedgerEffect.PLANNED }
        }

        /**
         * A claimed request may be superseded only while nothing can have taken effect: every
         * ledger row still PLANNED and no verified session already holding its target. Otherwise
         * the existing Stop/recovery rules own the outcome instead of a fake terminal.
         */
        fun effectFree(envelope: CastSessionEnvelope, request: BootAutomationRequest): Boolean {
            if (hasIssuedEffect(envelope)) return false
            if (request.attempt == 0) return true
            return envelope.stableSession?.activeTarget?.packageName != request.targetPackage
        }
    }
}

package com.byd.clusternav.modules.clustercast.v2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** Pure contract tests for the 0.71 durable automation model. No Android, no I/O. */
class CastAutomationTest {

    private val target = "com.example.maps"
    private val boot = "7"
    private val id: UUID = UUID.fromString("11111111-2222-3333-4444-555555555555")

    private fun armed(revision: Long = 1L) =
        AutomationConfig(revision, target, true, CURRENT_AUTOMATION_CONSENT_VERSION)

    private fun pending(config: AutomationConfig = armed(), at: Long = 1_000L) =
        BootAutomationRequest.pending(id, boot, config, at)

    @Test
    fun `fresh config is disabled without default or consent`() {
        val fresh = AutomationConfig()
        assertEquals(0L, fresh.revision)
        assertNull(fresh.defaultPackage)
        assertNull(fresh.consentVersion)
        assertFalse(fresh.autoCastEnabled)
        assertFalse(fresh.armable)
        assertEquals(AutomationDisposition.DISABLED, CastAutomationPolicy.disposition(fresh, null, null))
    }

    @Test
    fun `enabled config cannot exist without default or consent`() {
        assertThrows(IllegalArgumentException::class.java) { AutomationConfig(1L, null, true, 1) }
        assertThrows(IllegalArgumentException::class.java) { AutomationConfig(1L, target, true, null) }
        assertThrows(IllegalArgumentException::class.java) { AutomationConfig(-1L, target, false, null) }
        assertThrows(IllegalArgumentException::class.java) { AutomationConfig(1L, "notapackage", false, null) }
        assertThrows(IllegalArgumentException::class.java) { AutomationConfig(1L, target, false, 0) }
    }

    @Test
    fun `default selection alone never enables automation and always bumps revision`() {
        val withDefault = AutomationConfig().withDefault(target)
        assertEquals(1L, withDefault.revision)
        assertEquals(target, withDefault.defaultPackage)
        assertFalse(withDefault.autoCastEnabled)
        assertNull(withDefault.consentVersion)
        assertEquals(AutomationDisposition.DISABLED, CastAutomationPolicy.disposition(withDefault, null, null))
    }

    @Test
    fun `accept enables with current consent and revoke clears consent`() {
        val accepted = AutomationConfig().withDefault(target).accepting()
        assertTrue(accepted.autoCastEnabled)
        assertTrue(accepted.armable)
        assertEquals(CURRENT_AUTOMATION_CONSENT_VERSION, accepted.consentVersion)
        assertEquals(AutomationDisposition.ARMED, CastAutomationPolicy.disposition(accepted, null, null))
        val revoked = accepted.revoking()
        assertFalse(revoked.autoCastEnabled)
        assertNull(revoked.consentVersion)
        assertEquals(target, revoked.defaultPackage)
        assertEquals(accepted.revision + 1, revoked.revision)
        assertEquals(AutomationDisposition.DISABLED, CastAutomationPolicy.disposition(revoked, null, null))
    }

    @Test
    fun `enable requires a default package`() {
        assertThrows(IllegalArgumentException::class.java) { AutomationConfig().accepting() }
    }

    @Test
    fun `replacing or clearing the default disables automation`() {
        val accepted = AutomationConfig().withDefault(target).accepting()
        val replaced = accepted.withDefault("com.example.other")
        assertFalse(replaced.autoCastEnabled)
        assertEquals("com.example.other", replaced.defaultPackage)
        val cleared = accepted.withDefault(null)
        assertFalse(cleared.autoCastEnabled)
        assertNull(cleared.defaultPackage)
        assertNull(cleared.consentVersion)
    }

    @Test
    fun `same default keeps consent and still bumps revision`() {
        val accepted = AutomationConfig().withDefault(target).accepting()
        val again = accepted.withDefault(target)
        assertTrue(again.autoCastEnabled)
        assertEquals(accepted.revision + 1, again.revision)
    }

    @Test
    fun `stale consent version renders review required and blocks arming`() {
        val stale = AutomationConfig(4L, target, true, CURRENT_AUTOMATION_CONSENT_VERSION + 1)
        assertFalse(stale.consentCurrent)
        assertFalse(stale.armable)
        assertEquals(AutomationDisposition.REVIEW_REQUIRED, CastAutomationPolicy.disposition(stale, null, null))
        assertThrows(IllegalArgumentException::class.java) { BootAutomationRequest.pending(id, boot, stale, 5L) }
    }

    @Test
    fun `pending request records exact config truth`() {
        val request = pending()
        assertEquals(AutomationRequestState.PENDING, request.state)
        assertEquals(CastIntentOrigin.BOOT_AUTO, request.origin)
        assertEquals(target, request.targetPackage)
        assertEquals(0, request.attempt)
        assertEquals(0, request.reevaluationCount)
        assertNull(request.reason)
        assertTrue(request.claimable)
        assertFalse(request.terminal)
    }

    @Test
    fun `state matrix rejects illegal attempt timestamp and reason combinations`() {
        val base = pending()
        assertThrows(IllegalArgumentException::class.java) { base.copy(attempt = 1) }
        assertThrows(IllegalArgumentException::class.java) { base.copy(reason = AutomationReason.STOP_REQUESTED) }
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(state = AutomationRequestState.CLAIMED, attempt = 1, claimedAtEpochMillis = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(state = AutomationRequestState.CLAIMED, attempt = 1, claimedAtEpochMillis = 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(
                state = AutomationRequestState.COMPLETED, attempt = 1, claimedAtEpochMillis = 2_000L,
                terminalAtEpochMillis = 3_000L, reason = AutomationReason.KNOWN_FAILURE,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(
                state = AutomationRequestState.BLOCKED, terminalAtEpochMillis = 2_000L,
                reason = AutomationReason.USER_SUPERSEDED,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(
                state = AutomationRequestState.SUPERSEDED, terminalAtEpochMillis = 2_000L,
                reason = AutomationReason.DEADLINE_EXPIRED,
            )
        }
        assertThrows(IllegalArgumentException::class.java) { base.copy(reevaluationCount = 2) }
    }

    @Test
    fun `deferred requires exactly the prior journal reason`() {
        val deferred = pending().deferred()
        assertEquals(AutomationReason.PRIOR_JOURNAL, deferred.reason)
        assertEquals(0, deferred.attempt)
        assertTrue(deferred.claimable)
        assertThrows(IllegalArgumentException::class.java) {
            deferred.copy(reason = AutomationReason.STOP_REQUESTED)
        }
    }

    @Test
    fun `claim consumes the only attempt and cannot repeat`() {
        val claimed = pending().claimed(2_000L)
        assertEquals(1, claimed.attempt)
        assertEquals(2_000L, claimed.claimedAtEpochMillis)
        assertFalse(claimed.claimable)
        assertThrows(IllegalArgumentException::class.java) { claimed.claimed(3_000L) }
        assertThrows(IllegalArgumentException::class.java) { claimed.deferred() }
    }

    @Test
    fun `one re-evaluation is permitted then exhausted`() {
        val deferred = pending().deferred()
        val once = deferred.reevaluated()
        assertEquals(1, once.reevaluationCount)
        assertThrows(IllegalArgumentException::class.java) { once.reevaluated() }
        assertThrows(IllegalArgumentException::class.java) { pending().reevaluated() }
    }

    @Test
    fun `terminal transitions are one-way and produce a compatible outcome`() {
        val completed = pending().claimed(2_000L)
            .terminalized(AutomationRequestState.COMPLETED, null, 3_000L)
        assertEquals(3_000L, completed.terminalAtEpochMillis)
        assertNull(completed.reason)
        assertTrue(completed.terminal)
        val outcome = completed.outcome()
        assertEquals(AutomationRequestState.COMPLETED, outcome.terminalState)
        assertNull(outcome.reason)
        assertEquals(target, outcome.targetPackage)
        assertThrows(IllegalArgumentException::class.java) {
            completed.terminalized(AutomationRequestState.BLOCKED, AutomationReason.KNOWN_FAILURE, 4_000L)
        }
        assertThrows(IllegalArgumentException::class.java) { pending().outcome() }
        assertThrows(IllegalArgumentException::class.java) {
            pending().terminalized(AutomationRequestState.CLAIMED, null, 4_000L)
        }
    }

    @Test
    fun `unclaimed block keeps attempt zero and no claimed timestamp`() {
        val blocked = pending().terminalized(AutomationRequestState.BLOCKED, AutomationReason.TARGET_INVALID, 2_500L)
        assertEquals(0, blocked.attempt)
        assertNull(blocked.claimedAtEpochMillis)
        assertEquals(2_500L, blocked.terminalAtEpochMillis)
        assertEquals(AutomationReason.TARGET_INVALID, blocked.outcome().reason)
    }

    @Test
    fun `outcome rejects nonterminal state and incompatible reason`() {
        assertThrows(IllegalArgumentException::class.java) {
            AutomationOutcome(id, boot, target, AutomationRequestState.PENDING, null, 10L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AutomationOutcome(id, boot, target, AutomationRequestState.COMPLETED, AutomationReason.KNOWN_FAILURE, 10L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AutomationOutcome(id, boot, target, AutomationRequestState.BLOCKED, null, 10L)
        }
    }

    @Test
    fun `pending intent carries origin and only automation may bind a request id`() {
        val user = PendingCastIntent(target)
        assertEquals(CastIntentOrigin.USER, user.origin)
        assertNull(user.automationRequestId)
        assertFalse(user.matches(id))
        val auto = PendingCastIntent(target, CastIntentOrigin.BOOT_AUTO, id)
        assertTrue(auto.matches(id))
        assertFalse(auto.matches(UUID.randomUUID()))
        assertThrows(IllegalArgumentException::class.java) { PendingCastIntent(target, CastIntentOrigin.USER, id) }
        assertThrows(IllegalArgumentException::class.java) { PendingCastIntent(target, CastIntentOrigin.BOOT_AUTO, null) }
        assertThrows(IllegalArgumentException::class.java) { PendingCastIntent("nope") }
    }

    @Test
    fun `claim CAS requires boot revision consent enable and exact default equality`() {
        val config = armed()
        val request = pending(config)
        assertTrue(CastAutomationPolicy.claimAllowed(config, request, boot))
        assertFalse(CastAutomationPolicy.claimAllowed(config, request, "8"))
        assertFalse(CastAutomationPolicy.claimAllowed(config.copy(revision = 2L), request, boot))
        assertFalse(CastAutomationPolicy.claimAllowed(config.revoking(), request, boot))
        assertFalse(
            CastAutomationPolicy.claimAllowed(
                AutomationConfig(1L, "com.example.other", true, CURRENT_AUTOMATION_CONSENT_VERSION), request, boot,
            ),
        )
        assertFalse(
            CastAutomationPolicy.claimAllowed(
                AutomationConfig(1L, target, true, CURRENT_AUTOMATION_CONSENT_VERSION + 1), request, boot,
            ),
        )
        assertFalse(CastAutomationPolicy.claimAllowed(config, request.claimed(2_000L), boot))
    }

    @Test
    fun `supersession reason distinguishes revocation from other config movement`() {
        val config = armed()
        val request = pending(config)
        assertNull(CastAutomationPolicy.supersessionReason(config, request))
        assertEquals(
            AutomationReason.CONSENT_REVOKED,
            CastAutomationPolicy.supersessionReason(config.revoking(), request),
        )
        assertEquals(
            AutomationReason.CONFIG_CHANGED,
            CastAutomationPolicy.supersessionReason(config.withDefault("com.example.other").accepting(), request),
        )
        assertEquals(
            AutomationReason.CONFIG_CHANGED,
            CastAutomationPolicy.supersessionReason(config.withDefault(target).accepting(), request),
        )
    }

    @Test
    fun `disposition prefers the current boot request over stale outcome`() {
        val config = armed()
        val request = pending(config)
        assertEquals(
            AutomationDisposition.PENDING,
            CastAutomationPolicy.disposition(config, request, null, boot),
        )
        val previous = request.terminalized(AutomationRequestState.BLOCKED, AutomationReason.TARGET_INVALID, 9_000L)
            .outcome().copy(bootId = "6")
        assertEquals(
            AutomationDisposition.PENDING,
            CastAutomationPolicy.disposition(config, request, previous, boot),
        )
        assertEquals(
            AutomationDisposition.ARMED,
            CastAutomationPolicy.disposition(config, null, previous, boot),
        )
        val sameBoot = previous.copy(bootId = boot)
        assertEquals(
            AutomationDisposition.BLOCKED,
            CastAutomationPolicy.disposition(config, null, sameBoot, boot),
        )
    }

    @Test
    fun `every request state maps to exactly one disposition`() {
        val config = armed()
        val states = mapOf(
            AutomationRequestState.PENDING to AutomationDisposition.PENDING,
            AutomationRequestState.DEFERRED to AutomationDisposition.DEFERRED,
            AutomationRequestState.CLAIMED to AutomationDisposition.CLAIMED,
            AutomationRequestState.COMPLETED to AutomationDisposition.COMPLETED,
            AutomationRequestState.BLOCKED to AutomationDisposition.BLOCKED,
            AutomationRequestState.SUPERSEDED to AutomationDisposition.SUPERSEDED,
        )
        assertEquals(AutomationRequestState.values().size, states.size)
        states.forEach { (state, expected) ->
            val request = when (state) {
                AutomationRequestState.PENDING -> pending(config)
                AutomationRequestState.DEFERRED -> pending(config).deferred()
                AutomationRequestState.CLAIMED -> pending(config).claimed(2_000L)
                AutomationRequestState.COMPLETED ->
                    pending(config).claimed(2_000L).terminalized(state, null, 3_000L)
                AutomationRequestState.BLOCKED ->
                    pending(config).terminalized(state, AutomationReason.KNOWN_FAILURE, 3_000L)
                AutomationRequestState.SUPERSEDED ->
                    pending(config).terminalized(state, AutomationReason.USER_SUPERSEDED, 3_000L)
            }
            assertEquals(expected, CastAutomationPolicy.disposition(config, request, null, boot))
        }
    }

    @Test
    fun `reason compatibility sets are closed and disjoint per terminal state`() {
        AutomationReason.values().forEach { reason ->
            assertTrue(
                reason in AutomationReasons.block || reason in AutomationReasons.supersede,
                "reason $reason must belong to a terminal class",
            )
        }
        assertTrue(AutomationReasons.compatible(AutomationRequestState.DEFERRED, AutomationReason.PRIOR_JOURNAL))
        assertFalse(AutomationReasons.compatible(AutomationRequestState.DEFERRED, null))
        assertFalse(AutomationReasons.compatible(AutomationRequestState.COMPLETED, AutomationReason.BOOT_ROLLOVER))
        assertTrue(AutomationReasons.compatible(AutomationRequestState.PENDING, null))
    }

    @Test
    fun `automation package grammar is shared and strict`() {
        assertTrue(isAutomationPackage("com.example.maps"))
        assertFalse(isAutomationPackage("com"))
        assertFalse(isAutomationPackage(""))
        assertFalse(isAutomationPackage(null))
        assertFalse(isAutomationPackage("com..maps"))
    }
}

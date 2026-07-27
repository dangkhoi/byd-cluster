package com.byd.clusternav.modules.clustercast.v2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** Durable config, one-claim boot request and precedence transitions on the existing envelope. */
class CastAutomationSettingsTest {

    private val target = "com.example.maps"
    private val other = "com.example.vietmap"

    private class Memory : AtomicBytes {
        var bytes: ByteArray? = null
        override fun exists() = bytes != null
        override fun read(): ByteArray = bytes ?: error("empty")
        override fun write(value: ByteArray) { bytes = value }
    }

    private class Fixture(var now: Long = 1_000L) {
        val store = CastSessionStore(Memory())
        val settings = CastAutomationSettings(store) { now }
        init { store.locked { initializeForBoot("11") } }
        fun envelope(): CastSessionEnvelope = (store.locked { read() } as StoreRead.Loaded).envelope
        fun arm(pkg: String) {
            settings.setDefault(pkg)
            settings.accept()
        }
    }

    private fun issuedTransaction(epoch: Long): CastTransaction = CastTransaction(
        operationId = UUID.randomUUID(), epoch = epoch, operation = CastOperation.CAST,
        phase = OperationPhase.ACTIVATING, sourcePkg = null, targetPkg = target, targetClass = "NORMAL",
        expectedDisplayIdentity = "display", baseline = CastBaseline(),
        ledger = listOf(
            LedgerStep("s1", "pre", CommandKind.START_FRESH_NORMAL, 1L, 10L, LedgerEffect.ISSUED, null, false),
        ),
        retries = 0, deadlineAtEpochMillis = 99_000L, lastFailure = null,
        expectedPostcondition = "active", compensationUsed = false,
    )

    private fun plannedTransaction(epoch: Long): CastTransaction = CastTransaction(
        operationId = UUID.randomUUID(), epoch = epoch, operation = CastOperation.CAST,
        phase = OperationPhase.PREPARING, sourcePkg = null, targetPkg = target, targetClass = "NORMAL",
        expectedDisplayIdentity = "display", baseline = CastBaseline(),
        ledger = listOf(
            LedgerStep("s1", "pre", CommandKind.START_FRESH_NORMAL, 1L, null, LedgerEffect.PLANNED, null, false),
        ),
        retries = 0, deadlineAtEpochMillis = 99_000L, lastFailure = null,
        expectedPostcondition = "active", compensationUsed = false,
    )

    @Test
    fun `fresh install has no default disabled automation and no consent`() {
        val fixture = Fixture()
        assertEquals(AutomationConfig(), fixture.settings.config())
        assertEquals(AutomationDisposition.DISABLED, fixture.settings.disposition())
        assertNull(fixture.settings.request())
        assertNull(fixture.settings.recordOrGet("11"))
    }

    @Test
    fun `default alone allows manual use but records no request`() {
        val fixture = Fixture()
        val config = fixture.settings.setDefault(target)
        assertEquals(target, config.defaultPackage)
        assertFalse(config.autoCastEnabled)
        assertNull(fixture.settings.recordOrGet("11"))
        assertEquals(AutomationDisposition.DISABLED, fixture.settings.disposition())
    }

    @Test
    fun `accept arms automation and revoke disarms it`() {
        val fixture = Fixture()
        fixture.arm(target)
        assertEquals(AutomationDisposition.ARMED, fixture.settings.disposition())
        val revoked = fixture.settings.revoke()
        assertFalse(revoked.autoCastEnabled)
        assertNull(revoked.consentVersion)
        assertEquals(AutomationDisposition.DISABLED, fixture.settings.disposition())
    }

    @Test
    fun `record is idempotent for duplicate deliveries in the same boot`() {
        val fixture = Fixture()
        fixture.arm(target)
        val first = checkNotNull(fixture.settings.recordOrGet("11", UUID.randomUUID()))
        val second = checkNotNull(fixture.settings.recordOrGet("11", UUID.randomUUID()))
        assertEquals(first.requestId, second.requestId)
        assertEquals(AutomationRequestState.PENDING, second.state)
        assertEquals(target, first.targetPackage)
        assertEquals(AutomationDisposition.PENDING, fixture.settings.disposition())
    }

    @Test
    fun `a new boot archives the old request and records one fresh request`() {
        val fixture = Fixture()
        fixture.arm(target)
        val first = checkNotNull(fixture.settings.recordOrGet("11"))
        fixture.now = 5_000L
        val second = checkNotNull(fixture.settings.recordOrGet("12"))
        assertFalse(first.requestId == second.requestId)
        assertEquals("12", second.bootId)
        assertEquals(AutomationReason.BOOT_ROLLOVER, fixture.settings.outcome()?.reason)
        assertEquals(first.requestId, fixture.settings.outcome()?.requestId)
    }

    @Test
    fun `claim succeeds once and never again`() {
        val fixture = Fixture()
        fixture.arm(target)
        val request = checkNotNull(fixture.settings.recordOrGet("11"))
        fixture.now = 2_000L
        val claimed = fixture.settings.claim(request.requestId)
        assertTrue(claimed is CastAutomationSettings.ClaimResult.Claimed)
        assertEquals(1, (claimed as CastAutomationSettings.ClaimResult.Claimed).request.attempt)
        val again = fixture.settings.claim(request.requestId)
        assertTrue(again is CastAutomationSettings.ClaimResult.Rejected)
    }

    @Test
    fun `claim rejects a foreign request id`() {
        val fixture = Fixture()
        fixture.arm(target)
        fixture.settings.recordOrGet("11")
        assertTrue(fixture.settings.claim(UUID.randomUUID()) is CastAutomationSettings.ClaimResult.Rejected)
    }

    @Test
    fun `revocation before claim supersedes with consent revoked and zero mutation`() {
        val fixture = Fixture()
        fixture.arm(target)
        val request = checkNotNull(fixture.settings.recordOrGet("11"))
        fixture.now = 2_000L
        fixture.settings.revoke()
        val outcome = checkNotNull(fixture.settings.outcome())
        assertEquals(AutomationReason.CONSENT_REVOKED, outcome.reason)
        assertEquals(AutomationRequestState.SUPERSEDED, outcome.terminalState)
        assertEquals(request.requestId, outcome.requestId)
        assertTrue(fixture.settings.claim(request.requestId) is CastAutomationSettings.ClaimResult.Rejected)
    }

    @Test
    fun `changing the default before claim supersedes with config changed`() {
        val fixture = Fixture()
        fixture.arm(target)
        fixture.settings.recordOrGet("11")
        fixture.now = 2_000L
        fixture.settings.setDefault(other)
        assertEquals(AutomationReason.CONFIG_CHANGED, fixture.settings.outcome()?.reason)
    }

    @Test
    fun `clearing the default before claim supersedes and disables automation`() {
        val fixture = Fixture()
        fixture.arm(target)
        fixture.settings.recordOrGet("11")
        fixture.now = 2_000L
        val cleared = fixture.settings.setDefault(null)
        assertNull(cleared.defaultPackage)
        assertFalse(cleared.autoCastEnabled)
        assertEquals(AutomationReason.CONFIG_CHANGED, fixture.settings.outcome()?.reason)
    }

    @Test
    fun `stop before claim supersedes the request`() {
        val fixture = Fixture()
        fixture.arm(target)
        val request = checkNotNull(fixture.settings.recordOrGet("11"))
        fixture.store.locked { bumpEpoch { it.copy(stopRequested = true, pendingIntent = null) } }
        fixture.now = 2_000L
        val result = fixture.settings.claim(request.requestId)
        assertEquals(
            AutomationReason.STOP_SUPERSEDED,
            (result as CastAutomationSettings.ClaimResult.Superseded).reason,
        )
        assertEquals(AutomationReason.STOP_SUPERSEDED, fixture.settings.outcome()?.reason)
    }

    @Test
    fun `an explicit user pending target supersedes automation at claim`() {
        val fixture = Fixture()
        fixture.arm(target)
        val request = checkNotNull(fixture.settings.recordOrGet("11"))
        fixture.store.locked { update { it.withPendingPackage(other) } }
        fixture.now = 2_000L
        val result = fixture.settings.claim(request.requestId)
        assertEquals(
            AutomationReason.USER_SUPERSEDED,
            (result as CastAutomationSettings.ClaimResult.Superseded).reason,
        )
        assertEquals(other, fixture.envelope().pendingPackage)
        assertTrue(fixture.envelope().pendingIsUser)
    }

    @Test
    fun `defer records the prior journal reason and allows exactly one re-evaluation`() {
        val fixture = Fixture()
        fixture.arm(target)
        val request = checkNotNull(fixture.settings.recordOrGet("11"))
        val deferred = checkNotNull(fixture.settings.defer(request.requestId))
        assertEquals(AutomationReason.PRIOR_JOURNAL, deferred.reason)
        assertEquals(AutomationDisposition.DEFERRED, fixture.settings.disposition())
        assertEquals(1, checkNotNull(fixture.settings.consumeReevaluation(request.requestId)).reevaluationCount)
        assertNull(fixture.settings.consumeReevaluation(request.requestId))
    }

    @Test
    fun `a deferred request may still be claimed once`() {
        val fixture = Fixture()
        fixture.arm(target)
        val request = checkNotNull(fixture.settings.recordOrGet("11"))
        fixture.settings.defer(request.requestId)
        fixture.now = 2_000L
        assertTrue(fixture.settings.claim(request.requestId) is CastAutomationSettings.ClaimResult.Claimed)
    }

    @Test
    fun `terminalize archives the outcome and clears only matching auto pending`() {
        val fixture = Fixture()
        fixture.arm(target)
        val request = checkNotNull(fixture.settings.recordOrGet("11"))
        fixture.now = 2_000L
        fixture.settings.claim(request.requestId)
        assertTrue(fixture.settings.bindAutoPending(request.requestId))
        assertEquals(CastIntentOrigin.BOOT_AUTO, fixture.envelope().pendingIntent?.origin)
        fixture.now = 3_000L
        val outcome = checkNotNull(
            fixture.settings.terminalize(request.requestId, AutomationRequestState.COMPLETED, null),
        )
        assertEquals(AutomationRequestState.COMPLETED, outcome.terminalState)
        assertNull(outcome.reason)
        assertNull(fixture.envelope().pendingIntent)
        assertNull(fixture.settings.terminalize(request.requestId, AutomationRequestState.BLOCKED, AutomationReason.KNOWN_FAILURE))
    }

    @Test
    fun `bind refuses without a claim and refuses after stop`() {
        val fixture = Fixture()
        fixture.arm(target)
        val request = checkNotNull(fixture.settings.recordOrGet("11"))
        assertFalse(fixture.settings.bindAutoPending(request.requestId))
        fixture.now = 2_000L
        fixture.settings.claim(request.requestId)
        fixture.store.locked { bumpEpoch { it.copy(stopRequested = true, pendingIntent = null) } }
        assertFalse(fixture.settings.bindAutoPending(request.requestId))
    }

    @Test
    fun `planned only transaction still counts as effect free`() {
        val fixture = Fixture()
        fixture.arm(target)
        val request = checkNotNull(fixture.settings.recordOrGet("11"))
        fixture.now = 2_000L
        fixture.settings.claim(request.requestId)
        fixture.store.locked { update { it.copy(transaction = plannedTransaction(it.durableEpoch)) } }
        fixture.now = 3_000L
        val outcome = checkNotNull(fixture.settings.supersedeIfEffectFree(AutomationReason.CONFIG_CHANGED))
        assertEquals(AutomationRequestState.SUPERSEDED, outcome.terminalState)
    }

    @Test
    fun `an issued row prevents supersession and keeps the claimed record`() {
        val fixture = Fixture()
        fixture.arm(target)
        val request = checkNotNull(fixture.settings.recordOrGet("11"))
        fixture.now = 2_000L
        fixture.settings.claim(request.requestId)
        fixture.store.locked { update { it.copy(transaction = issuedTransaction(it.durableEpoch)) } }
        fixture.now = 3_000L
        assertNull(fixture.settings.supersedeIfEffectFree(AutomationReason.CONSENT_REVOKED))
        assertEquals(AutomationRequestState.CLAIMED, checkNotNull(fixture.settings.request()).state)
    }

    @Test
    fun `revocation after an issued effect persists config without a false terminal`() {
        val fixture = Fixture()
        fixture.arm(target)
        val request = checkNotNull(fixture.settings.recordOrGet("11"))
        fixture.now = 2_000L
        fixture.settings.claim(request.requestId)
        fixture.store.locked { update { it.copy(transaction = issuedTransaction(it.durableEpoch)) } }
        fixture.now = 3_000L
        val config = fixture.settings.revoke()
        assertFalse(config.autoCastEnabled)
        assertNull(config.consentVersion)
        assertEquals(AutomationRequestState.CLAIMED, checkNotNull(fixture.settings.request()).state)
        assertNull(fixture.settings.outcome())
    }

    @Test
    fun `legacy default migrates once without consent and never overwrites a chosen default`() {
        val fixture = Fixture()
        assertTrue(fixture.settings.migrateLegacyDefault(target))
        val config = fixture.settings.config()
        assertEquals(target, config.defaultPackage)
        assertFalse(config.autoCastEnabled)
        assertNull(config.consentVersion)
        assertFalse(fixture.settings.migrateLegacyDefault(other))
        assertEquals(target, fixture.settings.config().defaultPackage)
        assertFalse(fixture.settings.migrateLegacyDefault("not-a-package"))
    }

    @Test
    fun `a backwards clock cannot throw out of the claim CAS`() {
        val fixture = Fixture(now = 9_000L)
        fixture.arm(target)
        val request = checkNotNull(fixture.settings.recordOrGet("11"))
        fixture.now = 1_000L
        val claimed = fixture.settings.claim(request.requestId)
        assertTrue(claimed is CastAutomationSettings.ClaimResult.Claimed)
        val record = checkNotNull(fixture.settings.request())
        assertEquals(request.requestedAtEpochMillis, record.claimedAtEpochMillis)
    }

    @Test
    fun `a cleared default is never resurrected by a legacy candidate`() {
        val fixture = Fixture()
        fixture.settings.setDefault(target)
        fixture.settings.setDefault(null)
        assertNull(fixture.settings.config().defaultPackage)
        assertFalse(fixture.settings.migrateLegacyDefault(target))
        assertNull(fixture.settings.config().defaultPackage)
    }

    @Test
    fun `a claimed request whose target is already active is not superseded as effect free`() {
        val fixture = Fixture()
        fixture.arm(target)
        val request = checkNotNull(fixture.settings.recordOrGet("11"))
        fixture.now = 2_000L
        fixture.settings.claim(request.requestId)
        fixture.store.locked {
            update {
                it.copy(
                    stableSession = StableCastSession(
                        StableState.ACTIVE_VERIFIED, EngineVersion.V2, "runtime", null, "display",
                        CastBaseline(), CastTarget(target, 7, 2), null, null, 1_500L,
                    ),
                )
            }
        }
        fixture.now = 3_000L
        assertNull(fixture.settings.supersedeIfEffectFree(AutomationReason.CONSENT_REVOKED))
        assertEquals(AutomationRequestState.CLAIMED, checkNotNull(fixture.settings.request()).state)
    }

    @Test
    fun `only the current disclosure version may be accepted`() {
        val fixture = Fixture()
        fixture.settings.setDefault(target)
        assertThrows(IllegalArgumentException::class.java) {
            fixture.settings.accept(CURRENT_AUTOMATION_CONSENT_VERSION + 1)
        }
        assertFalse(fixture.settings.config().autoCastEnabled)
    }

    @Test
    fun `every config change increments revision monotonically`() {
        val fixture = Fixture()
        val revisions = mutableListOf(fixture.settings.config().revision)
        revisions += fixture.settings.setDefault(target).revision
        revisions += fixture.settings.accept().revision
        revisions += fixture.settings.revoke().revision
        revisions += fixture.settings.setDefault(other).revision
        assertEquals(revisions.sorted(), revisions)
        assertEquals(revisions.toSet().size, revisions.size)
    }
}

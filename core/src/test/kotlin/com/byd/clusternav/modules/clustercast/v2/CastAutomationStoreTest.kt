package com.byd.clusternav.modules.clustercast.v2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Envelope schema-3 codec, backward decode and authoritative boot-identity transitions. */
class CastAutomationStoreTest {

    private val target = "com.example.maps"
    private val id: UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

    private class Memory : AtomicBytes {
        var bytes: ByteArray? = null
        override fun exists() = bytes != null
        override fun read(): ByteArray = bytes ?: error("empty")
        override fun write(value: ByteArray) { bytes = value }
    }

    private fun store(): Pair<CastSessionStore, Memory> {
        val memory = Memory()
        return CastSessionStore(memory) to memory
    }

    private fun loaded(store: CastSessionStore): CastSessionEnvelope =
        (store.locked { read() } as StoreRead.Loaded).envelope

    private fun armed(revision: Long = 1L) =
        AutomationConfig(revision, target, true, CURRENT_AUTOMATION_CONSENT_VERSION)

    @Test
    fun `fresh store initializes with disabled automation defaults and schema 3`() {
        val (store, _) = store()
        val envelope = store.locked { initializeForBoot("11") }
        assertEquals(3, envelope.schemaVersion)
        assertEquals("11", envelope.bootId)
        assertEquals(AutomationConfig(), envelope.automationConfig)
        assertNull(envelope.bootAutomationRequest)
        assertNull(envelope.lastAutomationOutcome)
        assertNull(envelope.pendingIntent)
    }

    @Test
    fun `config request outcome and origin tagged pending round trip exactly`() {
        val (store, _) = store()
        store.locked { initializeForBoot("11") }
        val config = armed()
        val request = BootAutomationRequest.pending(id, "11", config, 5_000L).claimed(6_000L)
        val outcome = AutomationOutcome(
            UUID.randomUUID(), "10", "com.example.old", AutomationRequestState.BLOCKED,
            AutomationReason.DEADLINE_EXPIRED, 4_000L,
        )
        store.locked {
            update {
                it.copy(
                    automationConfig = config,
                    bootAutomationRequest = request,
                    lastAutomationOutcome = outcome,
                    pendingIntent = PendingCastIntent(target, CastIntentOrigin.BOOT_AUTO, id),
                )
            }
        }
        val reloaded = loaded(store)
        assertEquals(config, reloaded.automationConfig)
        assertEquals(request, reloaded.bootAutomationRequest)
        assertEquals(outcome, reloaded.lastAutomationOutcome)
        assertEquals(CastIntentOrigin.BOOT_AUTO, reloaded.pendingIntent?.origin)
        assertEquals(id, reloaded.pendingIntent?.automationRequestId)
        assertEquals(target, reloaded.pendingPackage)
        assertFalse(reloaded.pendingIsUser)
    }

    @Test
    fun `every legal terminal state round trips through the codec`() {
        val (store, _) = store()
        store.locked { initializeForBoot("11") }
        val config = armed()
        val base = BootAutomationRequest.pending(id, "11", config, 5_000L)
        val rows = listOf(
            base,
            base.deferred(),
            base.deferred().reevaluated(),
            base.claimed(6_000L),
            base.claimed(6_000L).terminalized(AutomationRequestState.COMPLETED, null, 7_000L),
            base.terminalized(AutomationRequestState.BLOCKED, AutomationReason.TARGET_INVALID, 6_500L),
            base.claimed(6_000L)
                .terminalized(AutomationRequestState.BLOCKED, AutomationReason.UNKNOWN_EFFECT, 7_000L),
            base.terminalized(AutomationRequestState.SUPERSEDED, AutomationReason.USER_SUPERSEDED, 6_500L),
            base.claimed(6_000L)
                .terminalized(AutomationRequestState.SUPERSEDED, AutomationReason.CONSENT_REVOKED, 7_000L),
        )
        rows.forEach { row ->
            store.locked { update { it.copy(automationConfig = config, bootAutomationRequest = row, pendingIntent = null) } }
            assertEquals(row, loaded(store).bootAutomationRequest, "round trip $row")
        }
    }

    @Test
    fun `v2 envelope decodes with disabled automation and user origin pending`() {
        val (store, memory) = store()
        val encoded = Base64.getUrlEncoder().withoutPadding()
        val payload = buildString {
            append("v=2\n")
            append("epoch=3\n")
            append("boot=${encoded.encodeToString("9".toByteArray(StandardCharsets.UTF_8))}\n")
            append("stop=0\n")
            append("pending=${encoded.encodeToString(target.toByteArray(StandardCharsets.UTF_8))}\n")
            append("effectiveUi=V2\n")
            append("pendingRollback=0\n")
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
        memory.bytes = "checksum=$digest\n$payload".toByteArray(StandardCharsets.UTF_8)
        val envelope = loaded(store)
        assertEquals(3, envelope.schemaVersion)
        assertEquals(target, envelope.pendingPackage)
        assertEquals(CastIntentOrigin.USER, envelope.pendingIntent?.origin)
        assertNull(envelope.pendingIntent?.automationRequestId)
        assertTrue(envelope.pendingIsUser)
        assertEquals(AutomationConfig(), envelope.automationConfig)
        assertNull(envelope.bootAutomationRequest)
    }

    @Test
    fun `automation fields are rejected in a declared v2 payload`() {
        val (store, memory) = store()
        val encoded = Base64.getUrlEncoder().withoutPadding()
        fun token(value: String) = encoded.encodeToString(value.toByteArray(StandardCharsets.UTF_8))
        val payload = buildString {
            append("v=2\n")
            append("epoch=0\n")
            append("boot=${token("9")}\n")
            append("stop=0\n")
            append("pending=~\n")
            append("effectiveUi=V2\n")
            append("pendingRollback=0\n")
            append("config=${token("1")}|${token(target)}|${token("true")}|${token("1")}\n")
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
        memory.bytes = "checksum=$digest\n$payload".toByteArray(StandardCharsets.UTF_8)
        val result = store.locked { read() }
        assertTrue(result is StoreRead.Corrupt, "expected corrupt, was $result")
    }

    @Test
    fun `auto origin pending without a matching request decodes as corrupt`() {
        val (store, memory) = store()
        store.locked { initializeForBoot("11") }
        val config = armed()
        store.locked {
            update {
                it.copy(
                    automationConfig = config,
                    bootAutomationRequest = BootAutomationRequest.pending(id, "11", config, 5_000L),
                    pendingIntent = PendingCastIntent(target, CastIntentOrigin.BOOT_AUTO, id),
                )
            }
        }
        val text = memory.bytes!!.toString(StandardCharsets.UTF_8)
        val payload = text.substringAfter('\n').lineSequence()
            .filter { it.isNotEmpty() && !it.startsWith("request=") }
            .joinToString(separator = "\n", postfix = "\n")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
        memory.bytes = "checksum=$digest\n$payload".toByteArray(StandardCharsets.UTF_8)
        assertTrue(store.locked { read() } is StoreRead.Corrupt)
    }

    @Test
    fun `request boot identity must equal the envelope boot`() {
        val (store, _) = store()
        store.locked { initializeForBoot("11") }
        val config = armed()
        assertThrows(IllegalArgumentException::class.java) {
            store.locked {
                update {
                    it.copy(
                        automationConfig = config,
                        bootAutomationRequest = BootAutomationRequest.pending(id, "12", config, 5_000L),
                    )
                }
            }
        }
    }

    @Test
    fun `request revision cannot exceed persisted config revision`() {
        val (store, _) = store()
        store.locked { initializeForBoot("11") }
        assertThrows(IllegalArgumentException::class.java) {
            store.locked {
                update {
                    it.copy(
                        automationConfig = AutomationConfig(),
                        bootAutomationRequest = BootAutomationRequest.pending(id, "11", armed(4L), 5_000L),
                    )
                }
            }
        }
    }

    @Test
    fun `same boot identity is idempotent and never bumps the epoch`() {
        val (store, _) = store()
        store.locked { initializeForBoot("11") }
        val config = armed()
        store.locked {
            update {
                it.copy(
                    automationConfig = config,
                    bootAutomationRequest = BootAutomationRequest.pending(id, "11", config, 5_000L),
                )
            }
        }
        val before = loaded(store)
        repeat(3) { store.locked { initializeForBoot("11") } }
        val after = loaded(store)
        assertEquals(before.durableEpoch, after.durableEpoch)
        assertEquals(before.bootAutomationRequest, after.bootAutomationRequest)
        assertNull(after.lastAutomationOutcome)
    }

    @Test
    fun `new boot bumps the epoch once and archives a nonterminal request as rollover`() {
        val (store, _) = store()
        store.locked { initializeForBoot("11") }
        val config = armed()
        store.locked {
            update {
                it.copy(
                    automationConfig = config,
                    bootAutomationRequest = BootAutomationRequest.pending(id, "11", config, 5_000L).claimed(6_000L),
                    pendingIntent = PendingCastIntent(target, CastIntentOrigin.BOOT_AUTO, id),
                )
            }
        }
        val before = loaded(store)
        val rolled = store.locked { initializeForBoot("12", atEpochMillis = 9_000L) }
        assertEquals(before.durableEpoch + 1, rolled.durableEpoch)
        assertEquals("12", rolled.bootId)
        assertNull(rolled.bootAutomationRequest)
        assertNull(rolled.pendingIntent)
        val archived = checkNotNull(rolled.lastAutomationOutcome)
        assertEquals(id, archived.requestId)
        assertEquals("11", archived.bootId)
        assertEquals(AutomationRequestState.SUPERSEDED, archived.terminalState)
        assertEquals(AutomationReason.BOOT_ROLLOVER, archived.reason)
        assertEquals(config, rolled.automationConfig)
        val again = store.locked { initializeForBoot("12", atEpochMillis = 9_500L) }
        assertEquals(rolled.durableEpoch, again.durableEpoch)
        assertEquals(archived, again.lastAutomationOutcome)
    }

    @Test
    fun `rollover preserves a user pending target and an unresolved transaction`() {
        val (store, _) = store()
        store.locked { initializeForBoot("11") }
        store.locked { update { it.withPendingPackage(target) } }
        val rolled = store.locked { initializeForBoot("12") }
        assertEquals(target, rolled.pendingPackage)
        assertEquals(CastIntentOrigin.USER, rolled.pendingIntent?.origin)
    }

    @Test
    fun `rollover keeps an existing terminal outcome when no request is pending`() {
        val (store, _) = store()
        store.locked { initializeForBoot("11") }
        val outcome = AutomationOutcome(
            id, "10", target, AutomationRequestState.COMPLETED, null, 4_000L,
        )
        store.locked { update { it.copy(lastAutomationOutcome = outcome) } }
        val rolled = store.locked { initializeForBoot("12") }
        assertEquals(outcome, rolled.lastAutomationOutcome)
    }

    @Test
    fun `rollover archives a terminal request without changing its reason`() {
        val (store, _) = store()
        store.locked { initializeForBoot("11") }
        val config = armed()
        val blocked = BootAutomationRequest.pending(id, "11", config, 5_000L)
            .terminalized(AutomationRequestState.BLOCKED, AutomationReason.TARGET_INVALID, 6_000L)
        store.locked { update { it.copy(automationConfig = config, bootAutomationRequest = blocked) } }
        val rolled = store.locked { initializeForBoot("12", atEpochMillis = 9_000L) }
        assertEquals(AutomationReason.TARGET_INVALID, rolled.lastAutomationOutcome?.reason)
        assertEquals(AutomationRequestState.BLOCKED, rolled.lastAutomationOutcome?.terminalState)
        assertEquals(6_000L, rolled.lastAutomationOutcome?.terminalAtEpochMillis)
    }

    @Test
    fun `an envelope whose required token is missing decodes as corrupt not a crash`() {
        val (store, memory) = store()
        store.locked { initializeForBoot("11") }
        val text = memory.bytes!!.toString(StandardCharsets.UTF_8)
        val payload = text.substringAfter('\n').replace("effectiveUi=LEGACY", "effectiveUi=LEGACY\nconfig=~")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
        memory.bytes = "checksum=$digest\n$payload".toByteArray(StandardCharsets.UTF_8)
        assertTrue(store.locked { read() } is StoreRead.Corrupt)
    }

    @Test
    fun `blank boot identity is rejected`() {
        val (store, _) = store()
        assertThrows(IllegalArgumentException::class.java) { store.locked { initializeForBoot(" ") } }
    }
}

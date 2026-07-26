package com.byd.clusternav.modules.clustercast.v2

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastSessionStoreTest {
    private class MemoryAtomicBytes : AtomicBytes {
        var bytes: ByteArray? = null
        var failNextWrite = false
        override fun exists() = bytes != null
        override fun read(): ByteArray = bytes?.copyOf() ?: throw IOException("missing")
        override fun write(bytes: ByteArray) {
            if (failNextWrite) {
                failNextWrite = false
                throw IOException("simulated interrupted write")
            }
            this.bytes = bytes.copyOf()
        }
    }

    @Test
    fun `round trips complete stable session transaction and ledger`() {
        val atomic = MemoryAtomicBytes()
        val store = CastSessionStore(atomic)
        val persisted = store.locked {
            initialize("boot-A")
            bumpEpoch { current -> current.copy(
                effectiveUiVersion = EngineVersion.V2,
                pendingUiRollback = true,
                stableSession = stable(),
                transaction = transaction(epoch = 1),
            ) }
        }
        assertTrue(persisted.checksum.matches(Regex("[0-9a-f]{64}")))
        val loaded = store.locked { read() } as StoreRead.Loaded
        assertEquals(persisted, loaded.envelope)
        assertEquals(CommandKind.START_FRESH_NORMAL, loaded.envelope.transaction!!.ledger.single().commandKind)
        assertEquals(101L, loaded.envelope.transaction!!.ledger.single().compensationIssuedAtEpochMillis)
        assertEquals(2L, loaded.envelope.transaction!!.ledger.single().compensationGatewayGeneration)
        assertEquals(LedgerEffect.ISSUED, loaded.envelope.transaction!!.ledger.single().compensationEffect)
        assertEquals(setOf("app.a", "app.b"), loaded.envelope.stableSession!!.baseline.occupants)
        assertEquals(EngineVersion.V2, loaded.envelope.effectiveUiVersion)
        assertTrue(loaded.envelope.pendingUiRollback)
    }

    @Test
    fun `legacy eight-field ledger decodes with no invented compensation attempt`() {
        val atomic = MemoryAtomicBytes()
        val store = CastSessionStore(atomic)
        store.locked {
            initialize("boot-A")
            bumpEpoch { it.copy(transaction = transaction(epoch = 1)) }
        }
        val original = atomic.bytes!!.toString(StandardCharsets.UTF_8)
        val payload = original.substringAfter('\n').lineSequence().filter(String::isNotEmpty).joinToString(
            separator = "\n",
            postfix = "\n",
        ) { line ->
            if (!line.startsWith("ledger=")) line
            else "ledger=" + line.substringAfter('=').split('|').dropLast(3).joinToString("|")
        }
        atomic.bytes = "checksum=${sha(payload)}\n$payload".toByteArray(StandardCharsets.UTF_8)

        val step = (store.locked { read() } as StoreRead.Loaded).envelope.transaction!!.ledger.single()
        assertNull(step.compensationIssuedAtEpochMillis)
        assertNull(step.compensationGatewayGeneration)
        assertEquals(LedgerEffect.PLANNED, step.compensationEffect)
    }

    @Test
    fun `checksum corruption fails closed without returning partial baseline`() {
        val atomic = MemoryAtomicBytes()
        val store = CastSessionStore(atomic)
        store.locked { initialize("boot-A") }
        atomic.bytes = atomic.bytes!!.copyOf().also { it[it.lastIndex - 2] = (it[it.lastIndex - 2].toInt() xor 1).toByte() }
        val result = store.locked { read() }
        assertInstanceOf(StoreRead.Corrupt::class.java, result)
        assertTrue((result as StoreRead.Corrupt).reason.contains("Checksum"))
    }

    @Test
    fun `valid checksum with unknown schema is reported separately and remains read only`() {
        val atomic = MemoryAtomicBytes()
        val store = CastSessionStore(atomic)
        store.locked { initialize("boot-A") }
        val original = atomic.bytes!!.toString(StandardCharsets.UTF_8)
        val payload = original.substringAfter('\n').replaceFirst("v=3\n", "v=99\n")
        atomic.bytes = "checksum=${sha(payload)}\n$payload".toByteArray(StandardCharsets.UTF_8)
        val result = store.locked { read() }
        assertEquals(StoreRead.UnsupportedSchema(99), result)
        assertThrows(IllegalStateException::class.java) { store.locked { bumpEpoch() } }
    }

    @Test
    fun `schema2 unknown in-flight enum fails closed without rewriting clean epoch0 store`() {
        val inFlightAtomic = MemoryAtomicBytes()
        val inFlight = CastSessionStore(inFlightAtomic)
        inFlight.locked {
            initialize("boot-A")
            bumpEpoch { it.copy(transaction = transaction(epoch = 1)) }
        }
        val original = inFlightAtomic.bytes!!.toString(StandardCharsets.UTF_8)
        val known = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(CommandKind.START_FRESH_NORMAL.name.toByteArray(StandardCharsets.UTF_8))
        val future = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("FUTURE_MUTATION_KIND".toByteArray(StandardCharsets.UTF_8))
        val payload = original.substringAfter('\n').replace(known, future)
        inFlightAtomic.bytes = "checksum=${sha(payload)}\n$payload".toByteArray(StandardCharsets.UTF_8)
        val beforeRead = inFlightAtomic.bytes!!.copyOf()

        assertTrue(inFlight.locked { read() } is StoreRead.Corrupt)
        assertTrue(beforeRead.contentEquals(inFlightAtomic.bytes!!), "failed decode must remain read only")

        val cleanAtomic = MemoryAtomicBytes()
        val clean = CastSessionStore(cleanAtomic)
        clean.locked { initialize("boot-clean") }
        val cleanBefore = cleanAtomic.bytes!!.copyOf()
        val loaded = clean.locked { read() } as StoreRead.Loaded
        assertEquals(0L, loaded.envelope.durableEpoch)
        assertNull(loaded.envelope.transaction)
        assertTrue(cleanBefore.contentEquals(cleanAtomic.bytes!!))
    }

    @Test
    fun `schema v1 envelope migrates read only with no invented adjustment draft`() {
        val atomic = MemoryAtomicBytes()
        val store = CastSessionStore(atomic)
        store.locked { initialize("boot-A") }
        val original = atomic.bytes!!.toString(StandardCharsets.UTF_8)
        val payload = original.substringAfter('\n').replaceFirst("v=2\n", "v=1\n")
        atomic.bytes = "checksum=${sha(payload)}\n$payload".toByteArray(StandardCharsets.UTF_8)
        val loaded = store.locked { read() } as StoreRead.Loaded
        assertEquals(CAST_ENVELOPE_SCHEMA_VERSION, loaded.envelope.schemaVersion)
        assertNull(loaded.envelope.adjustmentDraft)
    }

    @Test
    fun `failed atomic write leaves prior committed envelope intact`() {
        val atomic = MemoryAtomicBytes()
        val store = CastSessionStore(atomic)
        store.locked { initialize("boot-A") }
        val before = atomic.bytes!!.copyOf()
        atomic.failNextWrite = true
        assertThrows(IOException::class.java) { store.locked { bumpEpoch() } }
        assertTrue(before.contentEquals(atomic.bytes!!))
        assertEquals(0, (store.locked { read() } as StoreRead.Loaded).envelope.durableEpoch)
    }

    @Test
    fun `durable epoch survives process recreation and serializes concurrent callers`() {
        val atomic = MemoryAtomicBytes()
        CastSessionStore(atomic).locked { initialize("boot-A"); bumpEpoch(); bumpEpoch() }
        val recreated = CastSessionStore(atomic)
        assertEquals(2, (recreated.locked { read() } as StoreRead.Loaded).envelope.durableEpoch)

        val pool = Executors.newFixedThreadPool(4)
        try {
            pool.invokeAll((1..40).map { Callable { recreated.locked { bumpEpoch() } } }).forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(42, (recreated.locked { read() } as StoreRead.Loaded).envelope.durableEpoch)
    }

    @Test
    fun `transaction epoch cannot exceed durable epoch or move backwards`() {
        val atomic = MemoryAtomicBytes()
        val store = CastSessionStore(atomic)
        store.locked { initialize("boot-A"); bumpEpoch() }
        assertThrows(IllegalArgumentException::class.java) {
            store.locked { update { it.copy(transaction = transaction(epoch = 2)) } }
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.locked { update { it.copy(durableEpoch = 0) } }
        }
    }

    @Test
    fun `bump epoch transform cannot lower or over increment the durable epoch`() {
        val atomic = MemoryAtomicBytes()
        val store = CastSessionStore(atomic)
        store.locked { initialize("boot-A") }
        assertThrows(IllegalArgumentException::class.java) {
            store.locked { bumpEpoch { it.copy(durableEpoch = 0) } }
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.locked { bumpEpoch { it.copy(durableEpoch = 2) } }
        }
        assertEquals(0, (store.locked { read() } as StoreRead.Loaded).envelope.durableEpoch)
        assertEquals(1, store.locked { bumpEpoch() }.durableEpoch)
    }

    @Test
    fun `collection bearing Cast contracts defensively snapshot and reject downcast mutation`() {
        val occupantInput = linkedSetOf("app.a")
        val animationInput = linkedMapOf("transition" to "0")
        val ledgerInput = mutableListOf(
            LedgerStep("s1", "known", CommandKind.START_FRESH_NORMAL, 1, null,
                LedgerEffect.PLANNED, null, false)
        )
        val baseline = CastBaseline(occupantInput, animationPerKey = animationInput)
        val transaction = CastTransaction(
            operationId("collection-test"), 1, CastOperation.CAST, OperationPhase.PREPARING, null, "app.a", null,
            "display", baseline, ledgerInput, 0, 10, null, "visible", false,
        )
        occupantInput += "app.b"
        animationInput["transition"] = "1"
        ledgerInput.clear()
        assertEquals(setOf("app.a"), baseline.occupants)
        assertEquals(mapOf("transition" to "0"), baseline.animationPerKey)
        assertEquals(1, transaction.ledger.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (baseline.occupants as MutableSet).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (transaction.ledger as MutableList).clear()
        }

        val observedInput = linkedSetOf("app.a")
        val observed = ObservedState(ObservedCoarseState.ACTIVE_SINGLE, "display", null,
            observedInput, null, null)
        observedInput.clear()
        assertEquals(setOf("app.a"), observed.occupants)

        val stepInput = mutableListOf(PlannedStep("s1", CommandKind.START_FRESH_NORMAL, "known"))
        val actionInput = linkedSetOf(CastAction.STOP)
        val plan = CastPlan(CastOperation.CAST, 1, stepInput, "visible", actionInput)
        stepInput.clear()
        actionInput.clear()
        assertEquals(1, plan.steps.size)
        assertEquals(setOf(CastAction.STOP), plan.plannerAllowedActions)
    }

    private fun stable() = StableCastSession(
        StableState.ACTIVE_VERIFIED, EngineVersion.V2, "source-1", "profile-export", "display-x",
        CastBaseline(
            setOf("app.b", "app.a"),
            AcceptedGeometry(CastRect(0, 0, 1920, 720), 130, "seal"),
            mapOf("transition" to "0.0"), "allow", "seal",
        ),
        CastTarget("app.b", 3, 1), null,
        AcceptedGeometry(CastRect(0, 0, 1920, 720), 130, "seal"), 1234,
    )

    private fun transaction(epoch: Long) = CastTransaction(
        operationId("persisted-transaction"), epoch, CastOperation.CAST, OperationPhase.ACTIVATING, null, "app.b", "app.b/.Main",
        "display-x", CastBaseline(setOf("app.a")),
        listOf(LedgerStep("s1", "display known", CommandKind.START_FRESH_NORMAL, 1, 100,
            LedgerEffect.ISSUED, CommandKind.RETURN_NORMAL_TO_MAIN, false, 101, 2, LedgerEffect.ISSUED)),
        0, 60_000, null, "target visible", false,
    )

    private fun operationId(seed: String): UUID = UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8))

    private fun sha(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

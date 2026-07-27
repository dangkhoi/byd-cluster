package com.byd.clusternav.modules.clustercast.v2

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastUiStateProjectorTest {
    private val artifact by lazy { Json.parse(repoPath("docs/specs/cast-ui-state-v2.schema.json").toFile().readText()) as Map<*, *> }

    @Test
    fun `canonical JSON fixture hash is exact and bound to projector`() {
        val canonical = Json.canonical(artifact)
        val hash = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals("79595611424c083cca80b87002e65ee16097f668b09ea8f4d721235a2f060918", hash)
        assertEquals(hash, CAST_UI_SCHEMA_HASH)
        assertEquals(5L, (artifact["schemaVersion"] as Json.Number).raw.toLong())
    }

    @Test
    fun `all canonical enums exactly match Kotlin contracts`() {
        assertEnum("CastAction", CastAction.entries.map { it.name })
        assertEnum("CoarseState", CoarseState.entries.map { it.name })
        assertEnum("DisabledReason", DisabledReason.entries.map { it.name })
        assertEnum("InteractionContextValue", InteractionContextValue.entries.map { it.name })
        assertEnum("NextSafeAction", NextSafeAction.entries.map { it.name })
        assertEnum("OperationPhase", OperationPhase.entries.map { it.name })
        assertEnum("RecoverySubstate", RecoverySubstate.entries.map { it.name })
        assertEnum("StableState", StableState.entries.map { it.name })
        assertEnum("StopDispositionKind", StopDispositionKind.entries.map { it.name })
        assertEnum("UnavailableReason", UnavailableReason.entries.map { it.name })
    }

    @Test
    fun `all 19 recovery rows project exact singleton action translation and reason invariant`() {
        assertEquals(19, CastUiStateProjector.recoveryMappings.size)
        RecoverySubstate.entries.forEach { substate ->
            val state = CastUiStateProjector.project(base().copy(recoverySubstate = substate))
            assertEquals(substate, state.recoverySubstate)
            val translated = CastUiStateProjector.translate(state.nextSafeAction)
            // 2026-07-26 on-car: an empty action set left the whole screen dead, including the two
            // read-only actions that could explain or resolve the state. Every row now carries
            // Diagnostics, and a waiting row also carries the bounded reconnect. Still exact: nothing
            // beyond that may appear, so a drift into offering a mutating action is caught.
            val expectedActions: Set<CastAction> = buildSet {
                translated?.let(::add)
                add(CastAction.OPEN_DIAGNOSTICS)
                if (state.nextSafeAction == NextSafeAction.WAIT_AND_OBSERVE) add(CastAction.RETRY_CONNECT)
            }
            assertEquals(expectedActions + ALWAYS, state.allowedActions, substate.name)
            assertTrue(CastAction.OPEN_DIAGNOSTICS in state.allowedActions, substate.name)
            assertEquals(
                state.stopDisposition.kind == StopDispositionKind.UNAVAILABLE,
                state.unavailableReason != null,
                substate.name,
            )
            assertEquals(state.stopDisposition.reason, state.unavailableReason)
            assertEquals(CastAction.entries.toSet() - state.allowedActions, state.disabledReasons.keys)
        }
        val fixtureRows = (((artifact["projection"] as Map<*, *>)["recoveryMapping"]) as List<*>)
        val fixtureKeys = fixtureRows.associate {
            val row = it as Map<*, *>; row["recoverySubstate"] as String to row["messageKey"] as String
        }
        assertEquals(fixtureKeys, CastUiStateProjector.recoveryMappings.mapKeys { it.key.name })
    }

    @Test
    fun `decode failure wins first and unknown contract always fails closed`() {
        val state = CastUiStateProjector.project(
            base().copy(
                decodeValid = false,
                engineVersion = EngineVersion.LEGACY,
                observedNonIdle = true,
                stopRequested = true,
                recoverySubstate = RecoverySubstate.COMPENSATION_IN_PROGRESS,
            )
        )
        assertEquals(CoarseState.MANUAL_REQUIRED, state.coarseState)
        assertEquals(UnavailableReason.CONTRACT_UNMAPPED, state.unavailableReason)
        assertEquals(ALWAYS, state.allowedActions)
    }

    @Test
    fun `cold pristine is actionable UI only and Stop keeps precedence`() {
        val cold = CastUiStateProjector.project(base().copy(coldPristine = true))
        assertEquals(CoarseState.COLD_PRISTINE, cold.coarseState)
        assertNull(cold.stableState)
        assertEquals(StopDispositionKind.COMPLETED, cold.stopDisposition.kind)
        assertEquals(NextSafeAction.NONE, cold.nextSafeAction)
        assertEquals(
            ALWAYS + setOf(
                CastAction.CAST,
                CastAction.CHOOSE_ANOTHER_APP,
                CastAction.OPEN_APP_MANAGER,
            ),
            cold.allowedActions,
        )
        assertFalse(CastAction.RETRY_CONNECT in cold.allowedActions)

        val stopWins = CastUiStateProjector.project(base().copy(coldPristine = true, stopRequested = true))
        assertEquals(CoarseState.STOP_REQUESTED, stopWins.coarseState)
        val invalidStable = CastUiStateProjector.project(base().copy(
            coldPristine = true,
            stableState = StableState.IDLE_VERIFIED,
            stableConverged = true,
        ))
        assertEquals(UnavailableReason.CONTRACT_UNMAPPED, invalidStable.unavailableReason)
    }

    @Test
    fun `legacy non-idle wins before stop and exports no interactive Stop`() {
        val state = CastUiStateProjector.project(base().copy(
            engineVersion = EngineVersion.LEGACY, observedNonIdle = true, stopRequested = true,
        ))
        assertEquals(CoarseState.LEGACY_ACTIVE_READ_ONLY, state.coarseState)
        assertEquals(UnavailableReason.LEGACY_SESSION_UNSAFE, state.unavailableReason)
        assertFalse(CastAction.STOP in state.allowedActions)
    }

    @Test
    fun `both-present Stop guard rejects AVAILABLE recovery but accepts non-available row`() {
        val invalid = CastUiStateProjector.project(base().copy(
            stopRequested = true, recoverySubstate = RecoverySubstate.UNKNOWN_EFFECT_STOP_AVAILABLE,
        ))
        assertEquals(UnavailableReason.CONTRACT_UNMAPPED, invalid.unavailableReason)

        val valid = CastUiStateProjector.project(base().copy(
            stopRequested = true, recoverySubstate = RecoverySubstate.COMPENSATION_IN_PROGRESS,
        ))
        assertEquals(RecoverySubstate.COMPENSATION_IN_PROGRESS, valid.recoverySubstate)
        assertEquals(StopDispositionKind.IN_PROGRESS, valid.stopDisposition.kind)
    }

    @Test
    fun `standalone Stop suppresses duplicate actions before transaction and stable rows`() {
        val state = CastUiStateProjector.project(base().copy(
            stopRequested = true,
            transaction = PlannerUiProjection(
                OperationPhase.ACTIVATING, operationId("stop-precedence"), instant(9_999),
                StopDisposition(StopDispositionKind.AVAILABLE), NextSafeAction.REQUEST_STOP, setOf(CastAction.STOP),
            ),
            stableState = StableState.ACTIVE_VERIFIED,
            stableConverged = true,
            target = CastTarget("app", 1, 1),
        ))
        assertEquals(CoarseState.STOP_REQUESTED, state.coarseState)
        assertEquals(StopDispositionKind.REQUESTED, state.stopDisposition.kind)
        // 2026-07-27: Stop vẫn bị chặn để không phát trùng, nhưng trạng thái chờ KHÔNG được rỗng hành
        // động — quét vét cạn R14 bắt được đúng ca này. Giữ hai hành động chỉ-đọc.
        assertEquals(ALWAYS + CastAction.RETRY_CONNECT, state.allowedActions)
        assertTrue(CastAction.STOP !in state.allowedActions)
        assertNull(state.operationId)
    }

    @Test
    fun `transaction requires internally consistent exported action`() {
        val malformed = CastUiStateProjector.project(base().copy(
            transaction = PlannerUiProjection(
                OperationPhase.SWITCHING, operationId("malformed"), instant(5_000),
                StopDisposition(StopDispositionKind.AVAILABLE), NextSafeAction.REQUEST_STOP, emptySet(),
            )
        ))
        assertEquals(UnavailableReason.CONTRACT_UNMAPPED, malformed.unavailableReason)

        listOf(StopDispositionKind.REQUESTED, StopDispositionKind.IN_PROGRESS).forEach { kind ->
            val unsafeStop = CastUiStateProjector.project(base().copy(
                transaction = PlannerUiProjection(
                    OperationPhase.STOP_REQUESTED, operationId("unsafe-$kind"), instant(5_000),
                    StopDisposition(kind), NextSafeAction.WAIT_AND_OBSERVE, setOf(CastAction.STOP),
                )
            ))
            assertEquals(UnavailableReason.CONTRACT_UNMAPPED, unsafeStop.unavailableReason, kind.name)
            assertFalse(CastAction.STOP in unsafeStop.allowedActions)
        }

        val validOperationId = operationId("valid")
        val valid = CastUiStateProjector.project(base().copy(
            transaction = PlannerUiProjection(
                OperationPhase.SWITCHING, validOperationId, instant(5_000),
                StopDisposition(StopDispositionKind.AVAILABLE), NextSafeAction.REQUEST_STOP, setOf(CastAction.STOP),
            )
        ))
        assertEquals(CoarseState.SWITCHING, valid.coarseState)
        assertEquals(validOperationId, valid.operationId)
    }

    @Test
    fun `planner and UI collections are immutable snapshots`() {
        val actions = linkedSetOf(CastAction.STOP)
        val projection = PlannerUiProjection(
            OperationPhase.SWITCHING, operationId("immutable"), instant(5_000),
            StopDisposition(StopDispositionKind.AVAILABLE), NextSafeAction.REQUEST_STOP, actions,
        )
        actions.clear()
        val state = CastUiStateProjector.project(base().copy(transaction = projection))
        assertEquals(ALWAYS + CastAction.STOP, state.allowedActions)
        assertThrows(UnsupportedOperationException::class.java) {
            (projection.allowedActions as MutableSet).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (state.allowedActions as MutableSet).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (state.disabledReasons as MutableMap).clear()
        }
    }

    @Test
    fun `eligible destructive recovery is independent of interaction context`() {
        listOf(
            base().interactionContext.copy(value = InteractionContextValue.PARKED),
            base().interactionContext.copy(value = InteractionContextValue.MOVING),
            base().interactionContext.copy(value = InteractionContextValue.UNKNOWN),
            base().interactionContext.copy(provenance = "", freshUntil = instant(999), disagreementReason = "ignored"),
        ).forEach { context ->
            val state = CastUiStateProjector.project(base().copy(
                destructiveRecoveryEligible = true, interactionContext = context,
            ))
            assertEquals(ALWAYS + CastAction.TRY_ELIGIBLE_RECOVERY_ONCE, state.allowedActions)
            assertEquals(UnavailableReason.RECOVERY_ACTION_ONLY, state.unavailableReason)
        }
        val ineligible = CastUiStateProjector.project(base().copy(destructiveRecoveryEligible = false))
        assertEquals(UnavailableReason.CONTRACT_UNMAPPED, ineligible.unavailableReason)
    }

    @Test
    fun `stable convergence enforces target and residue shape`() {
        val active = CastUiStateProjector.project(base().copy(
            stableState = StableState.ACTIVE_VERIFIED, stableConverged = true, target = CastTarget("app", 1, 1),
        ))
        assertEquals(CoarseState.ACTIVE_VERIFIED, active.coarseState)
        assertTrue(CastAction.ADJUST in active.allowedActions)
        val unknownContext = base().interactionContext.copy(
            value = InteractionContextValue.UNKNOWN,
            disagreementReason = "parked not proven",
        )
        val activeUnknown = CastUiStateProjector.project(base().copy(
            stableState = StableState.ACTIVE_VERIFIED,
            stableConverged = true,
            target = CastTarget("app", 1, 1),
            interactionContext = unknownContext,
        ))
        assertTrue(CastAction.ADJUST in activeUnknown.allowedActions)
        assertNull(activeUnknown.disabledReasons[CastAction.ADJUST])
        val mismatched = CastUiStateProjector.project(base().copy(
            stableState = StableState.ACTIVE_DEGRADED, stableConverged = true, target = CastTarget("app", 1, 1),
        ))
        assertEquals(UnavailableReason.CONTRACT_UNMAPPED, mismatched.unavailableReason)
    }

    private fun base() = CastProjectionInput(
        decodeValid = true,
        engineVersion = EngineVersion.V2,
        observedNonIdle = false,
        stopRequested = false,
        recoverySubstate = null,
        transaction = null,
        destructiveRecoveryEligible = null,
        stableState = null,
        stableConverged = false,
        interactionContext = InteractionContext(
            InteractionContextValue.PARKED, "test", instant(1_000), instant(2_000), null,
        ),
        target = null,
        protectedResidue = null,
        acceptedGeometry = null,
        durableEpoch = 5,
        now = instant(1_500),
    )

    private fun instant(epochMillis: Long): Instant = Instant.ofEpochMilli(epochMillis)
    private fun operationId(seed: String): UUID = UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8))

    /**
     * Hai phép không bao giờ bị khoá vì không phát lệnh ra xe: xem Chẩn đoán, và chọn app để chuẩn bị.
     * Các bài kiểm dưới đây ghim phần action RIÊNG của từng trạng thái; việc nhóm này luôn có mặt ở mọi
     * trạng thái do NoDeadEndStateTest canh.
     */
    private val ALWAYS = setOf(CastAction.OPEN_DIAGNOSTICS, CastAction.SELECT_TARGET_APP, CastAction.OPEN_APP_MANAGER)

    private fun assertEnum(name: String, actual: List<String>) {
        val enums = artifact["enums"] as Map<*, *>
        assertEquals(enums[name] as List<*>, actual, name)
    }

    private fun repoPath(relative: String): Path {
        val current = Path.of(System.getProperty("user.dir"))
        return if (Files.exists(current.resolve("docs"))) current.resolve(relative) else current.parent.resolve(relative)
    }

    private object Json {
        data class Number(val raw: String)

        fun parse(text: String): Any? = Parser(text).parse()

        fun canonical(value: Any?): String = when (value) {
            null -> "null"
            is String -> quote(value)
            is Number -> value.raw
            is Boolean -> value.toString()
            is List<*> -> value.joinToString(",", "[", "]") { canonical(it) }
            is Map<*, *> -> value.entries.sortedBy { it.key as String }
                .joinToString(",", "{", "}") { quote(it.key as String) + ":" + canonical(it.value) }
            else -> error("Unsupported JSON value ${value.javaClass}")
        }

        private fun quote(value: String): String = buildString {
            append('"')
            value.forEach { c ->
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000c' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
                }
            }
            append('"')
        }

        private class Parser(private val source: String) {
            private var index = 0
            fun parse(): Any? = value().also { whitespace(); require(index == source.length) }
            private fun value(): Any? {
                whitespace()
                return when (source[index]) {
                    '{' -> objectValue()
                    '[' -> arrayValue()
                    '"' -> stringValue()
                    't' -> literal("true", true)
                    'f' -> literal("false", false)
                    'n' -> literal("null", null)
                    else -> numberValue()
                }
            }
            private fun objectValue(): Map<String, Any?> {
                index++; whitespace(); val out = linkedMapOf<String, Any?>()
                if (take('}')) return out
                while (true) {
                    val key = stringValue(); whitespace(); require(take(':')); require(key !in out); out[key] = value(); whitespace()
                    if (take('}')) return out
                    require(take(',')); whitespace()
                }
            }
            private fun arrayValue(): List<Any?> {
                index++; whitespace(); val out = mutableListOf<Any?>()
                if (take(']')) return out
                while (true) { out += value(); whitespace(); if (take(']')) return out; require(take(',')) }
            }
            private fun stringValue(): String {
                require(take('"')); val out = StringBuilder()
                while (true) {
                    val c = source[index++]
                    if (c == '"') return out.toString()
                    if (c != '\\') { out.append(c); continue }
                    when (val escaped = source[index++]) {
                        '"', '\\', '/' -> out.append(escaped)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> { out.append(source.substring(index, index + 4).toInt(16).toChar()); index += 4 }
                        else -> error("bad escape $escaped")
                    }
                }
            }
            private fun numberValue(): Number {
                val start = index
                while (index < source.length && source[index] in "-+0123456789.eE") index++
                require(index > start)
                return Number(source.substring(start, index))
            }
            private fun literal(word: String, result: Any?): Any? { require(source.startsWith(word, index)); index += word.length; return result }
            private fun whitespace() { while (index < source.length && source[index].isWhitespace()) index++ }
            private fun take(c: Char): Boolean = index < source.length && source[index] == c && (++index > 0)
        }
    }
}

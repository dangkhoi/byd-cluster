package com.byd.clusternav.modules.clustercast.v2

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

interface AtomicBytes {
    fun exists(): Boolean
    @Throws(IOException::class) fun read(): ByteArray
    @Throws(IOException::class) fun write(bytes: ByteArray)
}

sealed interface StoreRead {
    data object Empty : StoreRead
    data class Loaded(val envelope: CastSessionEnvelope) : StoreRead
    data class Corrupt(val reason: String) : StoreRead
    data class UnsupportedSchema(val version: Int) : StoreRead
}

/**
 * AtomicFile has no lock semantics. Every operation is available only in [locked], which owns the
 * single caller-visible critical section and prevents read/modify/write races in this process.
 */
class CastSessionStore(private val atomic: AtomicBytes) {
    // Constructor nhận File đã bỏ ngày 2026-07-27: :core không được biết dữ liệu ghi bằng gì. Caller
    // truyền impl của port vào — app dùng AndroidAtomicBytes, CLI dùng file thường, test dùng bộ nhớ.

    private val mutex = ReentrantLock()
    private val scopedAccess = Any()

    fun <T> locked(block: Locked.() -> T): T = mutex.withLock { Locked(scopedAccess).block() }

    inner class Locked internal constructor(token: Any) {
        init { require(token === scopedAccess) { "Locked store access must be created by CastSessionStore.locked" } }
        fun read(): StoreRead = readUnlocked()

        fun initialize(bootId: String): CastSessionEnvelope {
            require(bootId.isNotBlank())
            return when (val current = readUnlocked()) {
                StoreRead.Empty -> persist(
                    CastSessionEnvelope(
                        durableEpoch = 0,
                        bootId = bootId,
                        stopRequested = false,
                        pendingIntent = null,
                        effectiveUiVersion = EngineVersion.LEGACY,
                        pendingUiRollback = false,
                        stableSession = null,
                        transaction = null,
                    )
                )
                is StoreRead.Loaded -> current.envelope
                is StoreRead.Corrupt -> error("Cannot initialize corrupt Cast store: ${current.reason}")
                is StoreRead.UnsupportedSchema -> error("Unsupported Cast schema ${current.version}")
            }
        }

        /**
         * Authoritative boot transition. An empty store records the observed identity, the same
         * identity is idempotent, and a new identity bumps the durable epoch exactly once while
         * preserving any unresolved transaction for existing recovery and archiving a nonterminal
         * automation request as [AutomationReason.BOOT_ROLLOVER]. It never clears or replays a row.
         */
        fun initializeForBoot(
            observedBootId: String,
            atEpochMillis: Long = System.currentTimeMillis(),
        ): CastSessionEnvelope {
            require(observedBootId.isNotBlank()) { "observed boot identity must be present" }
            return when (val current = readUnlocked()) {
                StoreRead.Empty -> initialize(observedBootId)
                is StoreRead.Loaded ->
                    if (current.envelope.bootId == observedBootId) current.envelope
                    else bumpEpoch { envelope ->
                        val stale = envelope.bootAutomationRequest
                        val archived = stale?.takeIf { !it.terminal }
                            ?.terminalized(
                                AutomationRequestState.SUPERSEDED,
                                AutomationReason.BOOT_ROLLOVER,
                                atEpochMillis,
                            )?.outcome()
                            ?: stale?.outcome()
                        envelope.copy(
                            bootId = observedBootId,
                            bootAutomationRequest = null,
                            lastAutomationOutcome = archived ?: envelope.lastAutomationOutcome,
                            pendingIntent = envelope.pendingIntent?.takeIf {
                                it.origin == CastIntentOrigin.USER
                            },
                        )
                    }
                is StoreRead.Corrupt -> error("Cannot initialize corrupt Cast store: ${current.reason}")
                is StoreRead.UnsupportedSchema -> error("Unsupported Cast schema ${current.version}")
            }
        }

        fun update(transform: (CastSessionEnvelope) -> CastSessionEnvelope): CastSessionEnvelope {            val current = requireLoaded()
            val updated = transform(current).copy(checksum = "")
            require(updated.schemaVersion == CAST_ENVELOPE_SCHEMA_VERSION)
            require(updated.durableEpoch >= current.durableEpoch) { "durableEpoch cannot decrease" }
            require(updated.transaction?.epoch?.let { it <= updated.durableEpoch } != false)
            return persist(updated)
        }

        fun bumpEpoch(transform: (CastSessionEnvelope) -> CastSessionEnvelope = { it }): CastSessionEnvelope =
            update { current ->
                val nextEpoch = Math.addExact(current.durableEpoch, 1)
                transform(current.copy(durableEpoch = nextEpoch)).also {
                    require(it.durableEpoch == nextEpoch) { "bumpEpoch transform must preserve the exact increment" }
                }
            }

        private fun requireLoaded(): CastSessionEnvelope = when (val current = readUnlocked()) {
            is StoreRead.Loaded -> current.envelope
            StoreRead.Empty -> error("Cast store is not initialized")
            is StoreRead.Corrupt -> error("Cast store is corrupt: ${current.reason}")
            is StoreRead.UnsupportedSchema -> error("Unsupported Cast schema ${current.version}")
        }

        private fun persist(envelope: CastSessionEnvelope): CastSessionEnvelope {
            val encoded = CastEnvelopeCodec.encode(envelope)
            atomic.write(encoded)
            return (CastEnvelopeCodec.decode(encoded) as StoreRead.Loaded).envelope
        }
    }

    private fun readUnlocked(): StoreRead {
        if (!atomic.exists()) return StoreRead.Empty
        return try {
            CastEnvelopeCodec.decode(atomic.read())
        } catch (failure: IOException) {
            StoreRead.Corrupt("I/O read failure: ${failure.message ?: failure.javaClass.simpleName}")
        }
    }
}

object CastEnvelopeCodec {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val sha = Regex("[0-9a-f]{64}")

    fun encode(envelope: CastSessionEnvelope): ByteArray {
        require(envelope.schemaVersion == CAST_ENVELOPE_SCHEMA_VERSION)
        require(envelope.durableEpoch >= 0)
        require(envelope.bootId.isNotBlank())
        require(envelope.transaction?.epoch?.let { it <= envelope.durableEpoch } != false)
        envelope.bootAutomationRequest?.let { request ->
            require(request.bootId == envelope.bootId) { "request boot must equal envelope boot" }
            require(request.configRevision <= envelope.automationConfig.revision) {
                "request revision exceeds config revision"
            }
        }
        envelope.pendingIntent?.automationRequestId?.let { pendingRequest ->
            require(envelope.bootAutomationRequest?.requestId == pendingRequest) {
                "auto-origin pending intent must match the current boot request"
            }
        }
        val lines = mutableListOf(
            "v=${envelope.schemaVersion}",
            "epoch=${envelope.durableEpoch}",
            "boot=${token(envelope.bootId)}",
            "stop=${if (envelope.stopRequested) 1 else 0}",
            "pending=${encodePending(envelope.pendingIntent)}",
            "effectiveUi=${envelope.effectiveUiVersion.name}",
            "pendingRollback=${if (envelope.pendingUiRollback) 1 else 0}",
            "config=${encodeConfig(envelope.automationConfig)}",
        )
        envelope.bootAutomationRequest?.let { lines += "request=${encodeRequest(it)}" }
        envelope.lastAutomationOutcome?.let { lines += "outcome=${encodeOutcome(it)}" }
        envelope.stableSession?.let { stable ->
            lines += "stable=" + pack(
                stable.state.name, stable.engineVersion.name, stable.createdByBuild, stable.profileExport,
                stable.expectedDisplayIdentity, encodeTarget(stable.activeTarget), encodeResidue(stable.protectedResidue),
                encodeGeometry(stable.acceptedGeometry), stable.lastVerifiedAtEpochMillis.toString(),
            )
            lines += "stableBaseline=${encodeBaseline(stable.baseline)}"
        }
        envelope.adjustmentDraft?.let { draft ->
            lines += "adjustment=" + pack(
                draft.target.packageName, draft.target.taskId.toString(), draft.target.displayId.toString(),
                draft.expectedDisplayIdentity, draft.durableEpoch.toString(), encodeGeometry(draft.acceptedGeometry),
                encodeGeometry(draft.entrySnapshot), encodeGeometry(draft.localDraft),
                encodeGeometry(draft.previousVerifiedApply), encodeGeometry(draft.lastVerifiedApply),
                draft.operationId?.toString(), draft.dirty.toString(), draft.applyState.name, draft.validationError,
            )
        }
        envelope.transaction?.let { tx ->
            lines += "tx=" + pack(
                tx.operationId.toString(), tx.epoch.toString(), tx.operation.name, tx.phase.name, tx.sourcePkg,
                tx.targetPkg, tx.targetClass, tx.expectedDisplayIdentity, tx.retries.toString(),
                tx.deadlineAtEpochMillis.toString(), tx.lastFailure, tx.expectedPostcondition,
                tx.compensationUsed.toString(), encodeGeometry(tx.requestedGeometry),
            )
            lines += "txBaseline=${encodeBaseline(tx.baseline)}"
            tx.ledger.forEach { step ->
                lines += "ledger=" + pack(
                    step.stepId, step.precondition, step.commandKind.name, step.gatewayGeneration.toString(),
                    step.issuedAtEpochMillis?.toString(), step.effect.name, step.compensation?.name,
                    step.compensationObserved.toString(), step.compensationIssuedAtEpochMillis?.toString(),
                    step.compensationGatewayGeneration?.toString(), step.compensationEffect.name,
                )
            }
        }
        val payload = lines.joinToString(separator = "\n", postfix = "\n")
        return "checksum=${digest(payload.toByteArray(StandardCharsets.UTF_8))}\n$payload"
            .toByteArray(StandardCharsets.UTF_8)
    }

    fun decode(bytes: ByteArray): StoreRead {
        val text = bytes.toString(StandardCharsets.UTF_8)
        val firstBreak = text.indexOf('\n')
        if (firstBreak <= 0 || !text.endsWith('\n')) return StoreRead.Corrupt("Truncated envelope")
        val checksumLine = text.substring(0, firstBreak)
        if (!checksumLine.startsWith("checksum=")) return StoreRead.Corrupt("Missing checksum")
        val checksum = checksumLine.substringAfter('=')
        if (!sha.matches(checksum)) return StoreRead.Corrupt("Malformed checksum")
        val payload = text.substring(firstBreak + 1)
        if (digest(payload.toByteArray(StandardCharsets.UTF_8)) != checksum) return StoreRead.Corrupt("Checksum mismatch")
        return try {
            decodeVerified(payload, checksum)
        } catch (failure: IllegalArgumentException) {
            StoreRead.Corrupt("Invalid envelope: ${failure.message ?: failure.javaClass.simpleName}")
        } catch (failure: IllegalStateException) {
            StoreRead.Corrupt("Invalid envelope: ${failure.message ?: failure.javaClass.simpleName}")
        }
    }

    private fun decodeVerified(payload: String, checksum: String): StoreRead {
        val entries = payload.lineSequence().filter { it.isNotEmpty() }.map {
            require('=' in it) { "line without key" }
            it.substringBefore('=') to it.substringAfter('=')
        }.toList()
        val allowed = setOf(
            "v", "epoch", "boot", "stop", "pending", "effectiveUi", "pendingRollback",
            "stable", "stableBaseline", "adjustment", "tx", "txBaseline", "ledger",
            "config", "request", "outcome"
        )
        require(entries.all { it.first in allowed }) { "unknown field" }
        entries.filter { it.first != "ledger" }.groupingBy { it.first }.eachCount().forEach { (key, count) ->
            require(count == 1) { "duplicate $key" }
        }
        fun required(key: String) = entries.singleOrNull { it.first == key }?.second ?: throw IllegalArgumentException("missing $key")
        fun optional(key: String) = entries.singleOrNull { it.first == key }?.second
        val version = required("v").toInt()
        if (version !in 1..CAST_ENVELOPE_SCHEMA_VERSION) return StoreRead.UnsupportedSchema(version)
        val epoch = required("epoch").toLong().also { require(it >= 0) }
        val stableParts = optional("stable")?.let(::unpack)
        val stableBaseline = optional("stableBaseline")
        require((stableParts == null) == (stableBaseline == null)) { "partial stable session" }
        val stable = stableParts?.let { p ->
            require(p.size == 9)
            StableCastSession(
                StableState.valueOf(p.required(0)), EngineVersion.valueOf(p.required(1)), p.required(2), p[3],
                p.required(4), decodeBaseline(stableBaseline!!), decodeTarget(p[5]), decodeResidue(p[6]),
                decodeGeometry(p[7]), p.required(8).toLong(),
            )
        }
        val txParts = optional("tx")?.let(::unpack)
        val txBaseline = optional("txBaseline")
        require((txParts == null) == (txBaseline == null)) { "partial transaction" }
        val ledger = entries.filter { it.first == "ledger" }.map { decodeLedger(unpack(it.second)) }
        require(txParts != null || ledger.isEmpty()) { "ledger without transaction" }
        val tx = txParts?.let { p ->
            require(p.size == 13 || p.size == 14)
            CastTransaction(
                UUID.fromString(p.required(0)), p.required(1).toLong(), CastOperation.valueOf(p.required(2)),
                OperationPhase.valueOf(p.required(3)), p[4], p[5], p[6], p.required(7),
                decodeBaseline(txBaseline!!), ledger, p.required(8).toInt(), p.required(9).toLong(),
                p[10], p.required(11), p.required(12).toStrictBoolean(),
                p.getOrNull(13)?.let(::decodeGeometry),
            ).also { require(it.epoch <= epoch) { "transaction epoch exceeds durable epoch" } }
        }
        val adjustment = optional("adjustment")?.let { encoded ->
            val p = unpack(encoded); require(p.size == 14)
            AdjustmentDraft(
                CastTarget(p.required(0), p.required(1).toInt(), p.required(2).toInt()),
                p.required(3), p.required(4).toLong(), checkNotNull(decodeGeometry(p[5])),
                checkNotNull(decodeGeometry(p[6])), checkNotNull(decodeGeometry(p[7])),
                decodeGeometry(p[8]), decodeGeometry(p[9]), p[10]?.let(UUID::fromString),
                p.required(11).toStrictBoolean(), AdjustmentApplyState.valueOf(p.required(12)), p[13],
            )
        }
        val envelope = CastSessionEnvelope(
            CAST_ENVELOPE_SCHEMA_VERSION, checksum, epoch, required("boot").decodedRequired(), required("stop").toBitBoolean(),
            decodePending(required("pending"), version), EngineVersion.valueOf(required("effectiveUi")),
            required("pendingRollback").toBitBoolean(), stable, tx, adjustment,
            optional("config")?.let(::decodeConfig) ?: AutomationConfig(),
            optional("request")?.let(::decodeRequest),
            optional("outcome")?.let(::decodeOutcome),
        )
        if (version < 3) {
            require(entries.none { it.first == "config" || it.first == "request" || it.first == "outcome" }) {
                "automation fields require schema 3"
            }
        }
        envelope.bootAutomationRequest?.let { request ->
            require(request.configRevision <= envelope.automationConfig.revision) {
                "request revision exceeds config revision"
            }
        }
        envelope.pendingIntent?.automationRequestId?.let { pendingRequest ->
            require(envelope.bootAutomationRequest?.requestId == pendingRequest) {
                "auto-origin pending intent must match the current boot request"
            }
        }
        return StoreRead.Loaded(envelope)
    }

    private fun encodeBaseline(value: CastBaseline): String = pack(
        value.occupants.sorted().joinToString(",") { token(it) }, encodeGeometry(value.geometry),
        value.animationPerKey.toSortedMap().entries.joinToString(",") { "${token(it.key)}:${token(it.value)}" },
        value.pipMode, value.profile,
    )

    private fun decodeBaseline(value: String): CastBaseline {
        val p = unpack(value); require(p.size == 5)
        val occupants = p[0].orEmpty().split(',').filter { it.isNotEmpty() }.map { it.decodedRequired() }.toSet()
        val animations = p[2].orEmpty().split(',').filter { it.isNotEmpty() }.associate { item ->
            require(':' in item); item.substringBefore(':').decodedRequired() to item.substringAfter(':').decodedRequired()
        }
        return CastBaseline(occupants, decodeGeometry(p[1]), animations, p[3], p[4])
    }

    private fun decodeLedger(p: List<String?>): LedgerStep {
        require(p.size == 8 || p.size == 11)
        return LedgerStep(
            p.required(0), p.required(1), CommandKind.valueOf(p.required(2)), p.required(3).toLong(),
            p[4]?.toLong(), LedgerEffect.valueOf(p.required(5)), p[6]?.let(CommandKind::valueOf),
            p.required(7).toStrictBoolean(), p.getOrNull(8)?.toLong(), p.getOrNull(9)?.toLong(),
            p.getOrNull(10)?.let(LedgerEffect::valueOf) ?: LedgerEffect.PLANNED,
        )
    }

    private fun encodePending(value: PendingCastIntent?): String = value?.let {
        pack(it.packageName, it.origin.name, it.automationRequestId?.toString())
    } ?: "~"

    /** v1/v2 stored one opaque package token; it decodes as an explicit USER placement. */
    private fun decodePending(value: String, version: Int): PendingCastIntent? {
        if (value == "~") return null
        if (version < 3) return PendingCastIntent(value.decodedRequired(), CastIntentOrigin.USER)
        val p = unpack(value); require(p.size == 3) { "pending arity" }
        return PendingCastIntent(
            p.required(0), CastIntentOrigin.valueOf(p.required(1)), p[2]?.let(UUID::fromString),
        )
    }

    private fun encodeConfig(value: AutomationConfig): String = pack(
        value.revision.toString(), value.defaultPackage, value.autoCastEnabled.toString(),
        value.consentVersion?.toString(),
    )

    private fun decodeConfig(value: String): AutomationConfig {
        val p = unpack(value); require(p.size == 4) { "config arity" }
        return AutomationConfig(
            p.required(0).toLong(), p[1], p.required(2).toStrictBoolean(), p[3]?.toInt(),
        )
    }

    private fun encodeRequest(value: BootAutomationRequest): String = pack(
        value.requestId.toString(), value.bootId, value.targetPackage, value.configRevision.toString(),
        value.consentVersion.toString(), value.state.name, value.attempt.toString(),
        value.reevaluationCount.toString(), value.requestedAtEpochMillis.toString(),
        value.claimedAtEpochMillis?.toString(), value.terminalAtEpochMillis?.toString(), value.reason?.name,
    )

    private fun decodeRequest(value: String): BootAutomationRequest {
        val p = unpack(value); require(p.size == 12) { "request arity" }
        return BootAutomationRequest(
            UUID.fromString(p.required(0)), p.required(1), p.required(2), p.required(3).toLong(),
            p.required(4).toInt(), AutomationRequestState.valueOf(p.required(5)), p.required(6).toInt(),
            p.required(7).toInt(), p.required(8).toLong(), p[9]?.toLong(), p[10]?.toLong(),
            p[11]?.let(AutomationReason::valueOf),
        )
    }

    private fun encodeOutcome(value: AutomationOutcome): String = pack(
        value.requestId.toString(), value.bootId, value.targetPackage, value.terminalState.name,
        value.reason?.name, value.terminalAtEpochMillis.toString(),
    )

    private fun decodeOutcome(value: String): AutomationOutcome {
        val p = unpack(value); require(p.size == 6) { "outcome arity" }
        return AutomationOutcome(
            UUID.fromString(p.required(0)), p.required(1), p.required(2),
            AutomationRequestState.valueOf(p.required(3)), p[4]?.let(AutomationReason::valueOf),
            p.required(5).toLong(),
        )
    }

    private fun encodeTarget(v: CastTarget?) = v?.let { "${token(it.packageName)},${it.taskId},${it.displayId}" }
    private fun decodeTarget(v: String?): CastTarget? = v?.split(',')?.also { require(it.size == 3) }?.let {
        CastTarget(it[0].decodedRequired(), it[1].toInt(), it[2].toInt())
    }
    private fun encodeResidue(v: ProtectedResidue?) = v?.let { "${token(it.packageName)},${it.taskId},${it.visibility.name}" }
    private fun decodeResidue(v: String?): ProtectedResidue? = v?.split(',')?.also { require(it.size == 3) }?.let {
        ProtectedResidue(it[0].decodedRequired(), it[1].toInt(), ResidueVisibility.valueOf(it[2]))
    }
    private fun encodeGeometry(v: AcceptedGeometry?) = v?.let {
        listOf(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom).joinToString(",") +
            ",${it.densityDpi ?: "~"},${token(it.profileId)}"
    }
    private fun decodeGeometry(v: String?): AcceptedGeometry? = v?.split(',')?.also { require(it.size == 6) }?.let {
        AcceptedGeometry(CastRect(it[0].toInt(), it[1].toInt(), it[2].toInt(), it[3].toInt()), it[4].takeUnless { d -> d == "~" }?.toInt(), it[5].decodedRequired())
    }

    private fun pack(vararg values: String?): String = values.joinToString("|") { token(it) }
    private fun unpack(value: String): List<String?> = value.split('|').map { it.decodedNullable() }
    private fun token(value: String?): String = value?.let { encoder.encodeToString(it.toByteArray(StandardCharsets.UTF_8)) } ?: "~"
    private fun String.decodedNullable(): String? = if (this == "~") null else String(decoder.decode(this), StandardCharsets.UTF_8)
    private fun String.decodedRequired(): String = decodedNullable() ?: throw IllegalArgumentException("required token is null")
    private fun List<String?>.required(index: Int): String = get(index) ?: throw IllegalArgumentException("required token $index is null")
    private fun String.toStrictBoolean(): Boolean = when (this) { "true" -> true; "false" -> false; else -> throw IllegalArgumentException("invalid boolean") }
    private fun String.toBitBoolean(): Boolean = when (this) { "1" -> true; "0" -> false; else -> throw IllegalArgumentException("invalid bit") }
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

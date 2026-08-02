package com.byd.clusternav.modules.clustercast.v2

/**
 * Vehicle-generation identity for cold bootstrap, sibling to [CastColdBootstrap] in the same package.
 * Pure data/logic, no Android import -- matches `layering-rules.md:45` ("bảng opcode, profile đời máy"
 * belongs in `:core` as data, not behavior).
 *
 * [SealDl3BootstrapProfile] in `CastColdBootstrap.kt` is NOT modified: [SEAL_DL3] below references its
 * `forwardKinds`/`forwardKindsFor`/`compensationKinds`/`styleKinds`/`eligible` directly, so there is one
 * source of truth for the field-verified opcode/timing data and zero drift risk.
 */
enum class VehicleProfileId { SEAL_DL3, DL5, GENERIC_FALLBACK }

/**
 * One vehicle generation's cold-bootstrap dispatch shape. [dispatchImplemented] and [blockedReason] are
 * exact opposites by construction: a profile either has a real, field-verified opcode path, or it names
 * -- loudly -- why it does not, and never attempts to send anything.
 */
data class VehicleCastProfile(
    val id: VehicleProfileId,
    val forwardKinds: List<CommandKind>,
    /** RECT-style forward sequence; null = this vehicle generation cannot change cluster style. */
    val rectForwardKinds: List<CommandKind>?,
    val compensationKinds: List<CommandKind>,
    /** One entry per [forwardKinds] index. */
    val forwardSettlesMillis: List<Long>,
    val compensationSettleMillis: Long,
    val styleKinds: Set<CommandKind>,
    val dispatchImplemented: Boolean,
    val blockedReason: String?,
) {
    init {
        require(dispatchImplemented != (blockedReason != null)) {
            "dispatchImplemented and blockedReason must be exact opposites: " +
                "dispatchImplemented=$dispatchImplemented blockedReason=$blockedReason"
        }
    }

    /**
     * Whether this resolved vehicle generation can change cluster style (curved/rect) at all.
     *
     * Mirrors V1's precedent (`ClusterProfile.supportsStyle`, `app/.../ClusterProfile.kt:43`): a null
     * RECT sequence means the vehicle generation has no opcode path to change style, so a style control
     * shown for it would silently do nothing. [rectForwardKinds] is already null for every profile
     * whose dispatch is not implemented ([DL5], [GENERIC_FALLBACK]), so this single field generically
     * covers both "not implemented yet" and "implemented but style-incapable" without a second flag or
     * any profile-id branch.
     */
    val supportsStyle: Boolean get() = rectForwardKinds != null

    fun forwardKindsFor(style: ClusterStyle): List<CommandKind> =
        if (style == ClusterStyle.RECT) rectForwardKinds ?: forwardKinds else forwardKinds
}

/**
 * Resolution mirrors V1's `ClusterProfile.resolve`/`detectSeed` (`app/.../ClusterProfile.kt:157-174`)
 * in STRUCTURE only: auto-detect from [CastVehicleFacts] -> known profile; explicit override short-
 * circuits detection; safe fallback for anything unrecognized. It deliberately does NOT copy V1's
 * SAFETY SHAPE for the fallback case -- see [resolve] KDoc for why.
 */
object CastVehicleProfileCatalog {
    val SEAL_DL3 = VehicleCastProfile(
        id = VehicleProfileId.SEAL_DL3,
        forwardKinds = SealDl3BootstrapProfile.forwardKinds,
        rectForwardKinds = SealDl3BootstrapProfile.forwardKindsFor(ClusterStyle.RECT),
        compensationKinds = SealDl3BootstrapProfile.compensationKinds,
        forwardSettlesMillis = listOf(3_000L, 3_000L, 2_000L),
        compensationSettleMillis = 1_000L,
        styleKinds = SealDl3BootstrapProfile.styleKinds,
        dispatchImplemented = true,
        blockedReason = null,
    )

    val DL5 = VehicleCastProfile(
        id = VehicleProfileId.DL5,
        forwardKinds = emptyList(),
        rectForwardKinds = null,
        compensationKinds = emptyList(),
        forwardSettlesMillis = emptyList(),
        compensationSettleMillis = 0L,
        styleKinds = emptySet(),
        dispatchImplemented = false,
        blockedReason = "DiLink5 detected (V1's DL5 profile uses service auto_container / opcode 16) " +
            "but V2 bootstrap dispatch is not implemented for it yet -- needs new CommandKind entries " +
            "plus CastSealCommands/CastPlacementCommands branches targeting service auto_container, " +
            "verified on real DL5 hardware before enabling.",
    )

    /**
     * Any vehicle that is neither an exact Seal DL3 match nor a DL5 marker match. Unlike V1's
     * `GENERIC_FALLBACK` (which dispatches the Seal DL3 opcode sequence sight-unseen onto any hardware
     * that merely contains "byd"), this profile never dispatches: [dispatchImplemented] is false and
     * [blockedReason] says why. See [resolve] KDoc for the reasoning.
     */
    val GENERIC_FALLBACK = VehicleCastProfile(
        id = VehicleProfileId.GENERIC_FALLBACK,
        forwardKinds = emptyList(),
        rectForwardKinds = null,
        compensationKinds = emptyList(),
        forwardSettlesMillis = emptyList(),
        compensationSettleMillis = 0L,
        styleKinds = emptySet(),
        dispatchImplemented = false,
        blockedReason = "Vehicle facts are neither an exact match for the field-verified Seal DL3 tuple " +
            "nor a DiLink5 marker match. V1's GENERIC_FALLBACK dispatches the Seal DL3 opcode sequence " +
            "sight-unseen onto any hardware that merely contains \"byd\"; V2 does not, because V2 runs " +
            "the newer transactional/ledger/verification dispatch machinery and a wrong opcode sequence " +
            "there is a live shell command against a real cluster display. Cold bootstrap stays Blocked " +
            "here until this exact vehicle is field-verified and given its own catalog entry, or an " +
            "operator supplies an explicit profileOverride.",
    )

    /**
     * Auto-detect -> known profile; explicit [override] short-circuits detection (no persistence wiring
     * yet -- plain parameter, defaults to null so no existing call site changes behavior).
     *
     * SAFETY: only an EXACT match against [SealDl3BootstrapProfile.exactFacts] (reusing
     * [SealDl3BootstrapProfile.eligible] -- not a substring check) resolves to a dispatch-enabled
     * profile for Seal DL3. Everything else that is not a DL5 marker match -- including a vehicle that
     * merely contains "byd" the way V1's fuzzy `detectSeed` would accept -- resolves to
     * [GENERIC_FALLBACK], which has `dispatchImplemented = false`. Loosening the Seal DL3 gate to a
     * substring match, or shipping [GENERIC_FALLBACK] with `dispatchImplemented = true`, would let any
     * close-but-not-identical unit receive live opcodes through V2's ledger/verification dispatch
     * machinery without that hardware ever having been seen -- a fail-open gate this catalog is
     * specifically built to avoid.
     */
    fun resolve(facts: CastVehicleFacts, override: VehicleProfileId? = null): VehicleCastProfile {
        override?.let { return byId(it) }
        if (SealDl3BootstrapProfile.eligible(facts)) return SEAL_DL3
        val hay = listOf(facts.manufacturer, facts.brand, facts.product, facts.device, facts.buildProduct)
            .joinToString(" ").lowercase()
        if (listOf("dilink5", "dilink_5", "dilink 5").any(hay::contains)) return DL5
        return GENERIC_FALLBACK
    }

    private fun byId(id: VehicleProfileId): VehicleCastProfile = when (id) {
        VehicleProfileId.SEAL_DL3 -> SEAL_DL3
        VehicleProfileId.DL5 -> DL5
        VehicleProfileId.GENERIC_FALLBACK -> GENERIC_FALLBACK
    }
}

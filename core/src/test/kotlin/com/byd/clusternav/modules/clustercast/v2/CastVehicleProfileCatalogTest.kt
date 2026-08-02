package com.byd.clusternav.modules.clustercast.v2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Locks the fail-closed vehicle-identity gate this catalog exists to enforce: only the EXACT Seal
 * DL3 tuple dispatches. Everything else -- DL5-marker or fully unknown -- resolves to a profile with
 * dispatchImplemented = false, never a widened substring match.
 */
class CastVehicleProfileCatalogTest {
    private fun exactFacts() = SealDl3BootstrapProfile.exactFacts

    @Test
    fun `exact Seal DL3 tuple resolves to the dispatch-enabled SEAL_DL3 profile`() {
        val profile = CastVehicleProfileCatalog.resolve(exactFacts())
        assertEquals(VehicleProfileId.SEAL_DL3, profile.id)
        assertTrue(profile.dispatchImplemented)
        assertNull(profile.blockedReason)
        assertEquals(SealDl3BootstrapProfile.forwardKinds, profile.forwardKinds)
        assertEquals(SealDl3BootstrapProfile.forwardKindsFor(ClusterStyle.RECT), profile.rectForwardKinds)
        assertEquals(SealDl3BootstrapProfile.compensationKinds, profile.compensationKinds)
        assertEquals(listOf(3_000L, 3_000L, 2_000L), profile.forwardSettlesMillis)
        assertEquals(1_000L, profile.compensationSettleMillis)
        assertEquals(SealDl3BootstrapProfile.styleKinds, profile.styleKinds)
    }

    @Test
    fun `a single differing field is a near-miss, not an exact match -- resolves to GENERIC_FALLBACK, blocked`() {
        val nearMisses = listOf(
            exactFacts().copy(sdk = 30),
            exactFacts().copy(manufacturer = "BYD Auto"),
            exactFacts().copy(brand = "byd-auto"),
            exactFacts().copy(product = "DiLink3.0x"),
            exactFacts().copy(device = "DiLink3.0x"),
            exactFacts().copy(buildProduct = "DiLink3.0x"),
        )
        nearMisses.forEach { facts ->
            val profile = CastVehicleProfileCatalog.resolve(facts)
            assertEquals(VehicleProfileId.GENERIC_FALLBACK, profile.id, facts.toString())
            assertFalse(profile.dispatchImplemented, facts.toString())
            assertNotNull(profile.blockedReason, facts.toString())
        }
    }

    @Test
    fun `DL5 marker facts resolve to DL5, blocked pending real dispatch implementation`() {
        val dl5Facts = listOf(
            exactFacts().copy(product = "DiLink5", device = "DiLink5"),
            exactFacts().copy(product = "dilink_5"),
            exactFacts().copy(buildProduct = "DiLink 5"),
        )
        dl5Facts.forEach { facts ->
            val profile = CastVehicleProfileCatalog.resolve(facts)
            assertEquals(VehicleProfileId.DL5, profile.id, facts.toString())
            assertFalse(profile.dispatchImplemented, facts.toString())
            assertNotNull(profile.blockedReason, facts.toString())
        }
    }

    @Test
    fun `non-BYD facts resolve to GENERIC_FALLBACK, blocked -- never dispatched sight-unseen`() {
        val unknown = CastVehicleFacts(29, "Acme Corp", "ACME", "Widget", "Widget", "Widget")
        val profile = CastVehicleProfileCatalog.resolve(unknown)
        assertEquals(VehicleProfileId.GENERIC_FALLBACK, profile.id)
        assertFalse(profile.dispatchImplemented)
        assertNotNull(profile.blockedReason)
    }

    @Test
    fun `explicit override short-circuits detection regardless of facts`() {
        val unknown = CastVehicleFacts(29, "Acme Corp", "ACME", "Widget", "Widget", "Widget")
        assertEquals(VehicleProfileId.SEAL_DL3, CastVehicleProfileCatalog.resolve(unknown, VehicleProfileId.SEAL_DL3).id)
        assertEquals(VehicleProfileId.DL5, CastVehicleProfileCatalog.resolve(exactFacts(), VehicleProfileId.DL5).id)
        assertEquals(
            VehicleProfileId.GENERIC_FALLBACK,
            CastVehicleProfileCatalog.resolve(exactFacts(), VehicleProfileId.GENERIC_FALLBACK).id,
        )
    }

    @Test
    fun `supportsStyle is generically derived from rectForwardKinds, not a profile-id branch`() {
        // SEAL_DL3 has a real RECT sequence -> style-capable.
        assertTrue(CastVehicleProfileCatalog.SEAL_DL3.supportsStyle)
        // DL5 and GENERIC_FALLBACK both have rectForwardKinds = null (dispatch not implemented at all),
        // so the style control must never be offered for either -- covered by the same single field.
        assertFalse(CastVehicleProfileCatalog.DL5.supportsStyle)
        assertFalse(CastVehicleProfileCatalog.GENERIC_FALLBACK.supportsStyle)
        // A hypothetical future profile with dispatch implemented but no RECT sequence must also read
        // false: supportsStyle tracks rectForwardKinds, not dispatchImplemented.
        val implementedButStyleIncapable = VehicleCastProfile(
            VehicleProfileId.DL5, listOf(CommandKind.SEAL_DL3_BOOTSTRAP_30), null, emptyList(),
            listOf(0L), 0L, emptySet(), dispatchImplemented = true, blockedReason = null,
        )
        assertFalse(implementedButStyleIncapable.supportsStyle)
    }

    @Test
    fun `VehicleCastProfile requires dispatchImplemented and blockedReason to be exact opposites`() {
        assertThrowsIllegalArgument {
            VehicleCastProfile(
                VehicleProfileId.GENERIC_FALLBACK, emptyList(), null, emptyList(), emptyList(), 0L,
                emptySet(), dispatchImplemented = true, blockedReason = "should not coexist",
            )
        }
        assertThrowsIllegalArgument {
            VehicleCastProfile(
                VehicleProfileId.GENERIC_FALLBACK, emptyList(), null, emptyList(), emptyList(), 0L,
                emptySet(), dispatchImplemented = false, blockedReason = null,
            )
        }
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        var threw = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "expected IllegalArgumentException")
    }
}

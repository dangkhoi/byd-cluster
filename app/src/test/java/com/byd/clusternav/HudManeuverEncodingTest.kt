package com.byd.clusternav

import com.byd.clusternav.navigation.Maneuver
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * I1 (1.14) contract. INSTRUMENT_GUIDE_INFO_SIMPLE_SET (the windshield-HUD simple-nav feature) reads the
 * CAN turn-id table (Maneuver.toHudIcon: left=1, right=2), NOT the AMAP NEW_ICON table (Maneuver.toAmapIcon:
 * left=2, right=3 — used by the cluster lane via the AUTONAVI broadcast). Feeding the AMAP code into the
 * CAN feature shifts every turn by one enum slot → the HUD renders left↔right mirrored (owner report:
 * "rẽ trái → rẽ phải", 100%). The cluster stays correct because it is driven by the separate broadcast.
 *
 * Runtime needs Android, so — like the other boundary tests — this locks the wiring by reading the source.
 */
class HudManeuverEncodingTest {

    private fun app(rel: String): Path {
        val cur = Path.of(System.getProperty("user.dir"))
        return if (Files.exists(cur.resolve("src"))) cur.resolve(rel) else cur.resolve("app").resolve(rel)
    }

    private val navRepo by lazy {
        app("src/main/java/com/byd/clusternav/NavRepository.kt").toFile().readText()
    }

    @Test
    fun `HUD write encodes via CAN toHudIcon, not the AMAP maneuverCode`() {
        assertTrue(
            navRepo.contains("icon = frame.content.maneuver?.toHudIcon()"),
            "owner.push must encode the HUD icon via Maneuver.toHudIcon() (CAN turn-id table)",
        )
        assertFalse(
            navRepo.contains("icon = frame.content.maneuverCode"),
            "owner.push must NOT feed the AMAP maneuverCode into the CAN HUD feature (that mirrors L/R)",
        )
    }

    /**
     * Track B (2026-08-14): the enriched Maneuver values must encode to the correct CAN turn-id on the HUD
     * (values from RE docs/diagnostics/re-maneuver-icon-tables-2026-08-14.md §2 = TurnIdMapToCAN[toAmapIcon]).
     * The HUD write path (asserted above) feeds these through Maneuver.toHudIcon(), so locking the codes here
     * guards the windshield-HUD glyph for every new maneuver the classifiers can now emit.
     */
    @Test
    fun `Track B new maneuver values encode to the correct CAN HUD ids`() {
        assertEquals(11, Maneuver.MERGE.toHudIcon())            // no merge glyph → straight
        assertEquals(3, Maneuver.RAMP_LEFT.toHudIcon())         // ramp ≈ slight-left
        assertEquals(5, Maneuver.RAMP_RIGHT.toHudIcon())
        assertEquals(3, Maneuver.FORK_LEFT.toHudIcon())
        assertEquals(5, Maneuver.FORK_RIGHT.toHudIcon())
        assertEquals(3, Maneuver.KEEP_LEFT.toHudIcon())
        assertEquals(5, Maneuver.KEEP_RIGHT.toHudIcon())
        assertEquals(10, Maneuver.UTURN_RIGHT.toHudIcon())      // U-turn right (CAN 10)
        assertEquals(24, Maneuver.ROUNDABOUT_EXIT.toHudIcon())  // drive out of roundabout (CAN 24)
        assertEquals(49, Maneuver.TUNNEL.toHudIcon())           // enter tunnel (CAN 49)
        assertEquals(46, Maneuver.SERVICE_AREA.toHudIcon())     // service area (CAN 46)
        assertEquals(47, Maneuver.TOLL.toHudIcon())             // toll station (CAN 47)
        assertEquals(45, Maneuver.WAYPOINT.toHudIcon())         // waypoint arrival (CAN 45)
    }
}

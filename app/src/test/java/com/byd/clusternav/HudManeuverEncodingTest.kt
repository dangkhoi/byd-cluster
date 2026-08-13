package com.byd.clusternav

import java.nio.file.Files
import java.nio.file.Path
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
}

package com.byd.clusternav.vietmapwidget

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VietMapWidgetTextParserTest {
    @Test
    fun `speed parsers accept exact valid values and reject sentinels and ranges`() {
        assertEquals(0, VietMapWidgetTextParser.parseCurrentSpeed("0"))
        assertEquals(300, VietMapWidgetTextParser.parseCurrentSpeed(" 300 "))
        assertEquals(50, VietMapWidgetTextParser.parseSpeedLimit("50"))

        listOf(null, "", "--", "!", "-", "301", "50 km/h", "abc").forEach {
            assertNull(VietMapWidgetTextParser.parseCurrentSpeed(it), it)
        }
        listOf(null, "", "--", "!", "0", "301", "50.0").forEach {
            assertNull(VietMapWidgetTextParser.parseSpeedLimit(it), it)
        }
    }

    @Test
    fun `distance parser preserves text and only derives metres for known units`() {
        assertEquals(VietMapParsedDistance("100 m", 100), VietMapWidgetTextParser.parseDistance("100  m"))
        assertEquals(VietMapParsedDistance("1,25 km", 1_250), VietMapWidgetTextParser.parseDistance("1,25 km"))
        assertEquals(VietMapParsedDistance("near bridge", null), VietMapWidgetTextParser.parseDistance("near bridge"))
        assertNull(VietMapWidgetTextParser.parseDistance("--"))
        assertNull(VietMapWidgetTextParser.parseDistance("!"))
    }

    @Test
    fun `fresh snapshot parses at most two alerts and hides irrelevant image hashes`() {
        val snapshot = VietMapWidgetTextParser.parseSnapshot(
            raw = VietMapWidgetRawValues(
                currentSpeedText = "42",
                speedLimitText = "50",
                firstAlertSpeedLimitText = "40",
                firstAlertDistanceText = "250m",
                firstAlertImageVisible = false,
                firstAlertImageHash = "must-not-leak",
                secondAlertDistanceText = "1.5 km",
                secondAlertImageVisible = true,
                secondAlertImageHash = "abc123",
            ),
            providerVersion = "3.3.4",
            updatedAtElapsedMs = 10_000L,
            nowElapsedMs = 12_000L,
        )

        assertEquals(VietMapWidgetFreshness.FRESH, snapshot.freshness)
        assertEquals(42, snapshot.currentSpeedKph)
        assertEquals(50, snapshot.speedLimitKph)
        assertEquals(2, snapshot.alerts.size)
        assertEquals(250, snapshot.alerts[0].distanceMeters)
        assertNull(snapshot.alerts[0].imageHash)
        assertEquals(1_500, snapshot.alerts[1].distanceMeters)
        assertEquals("abc123", snapshot.alerts[1].imageHash)
    }

    @Test
    fun `stale and unavailable snapshots never expose old driving values`() {
        val raw = VietMapWidgetRawValues(currentSpeedText = "90", speedLimitText = "50")
        val stale = VietMapWidgetTextParser.parseSnapshot(raw, "3.3.4", 1_000L, 7_000L)
        val unavailable = VietMapWidgetTextParser.parseSnapshot(raw, "3.3.4", 1_000L, 40_000L)
        val missing = VietMapWidgetTextParser.parseSnapshot(
            raw,
            "3.3.4",
            40_000L,
            40_000L,
            VietMapWidgetUnavailableReason.PROVIDER_MISSING,
        )

        assertEquals(VietMapWidgetFreshness.STALE, stale.freshness)
        assertNull(stale.currentSpeedKph)
        assertNull(stale.speedLimitKph)
        assertTrue(stale.alerts.isEmpty())
        assertEquals(VietMapWidgetFreshness.UNAVAILABLE, unavailable.freshness)
        assertEquals(VietMapWidgetUnavailableReason.NO_UPDATE, unavailable.reason)
        assertEquals(VietMapWidgetUnavailableReason.PROVIDER_MISSING, missing.reason)
        assertNull(missing.currentSpeedKph)
    }

    @Test
    fun `shape checks require names not decompiled integer resource ids`() {
        assertTrue(VietMapWidgetTextParser.supportsSpeedShape(VietMapWidgetViewNames.speedRequired))
        assertFalse(VietMapWidgetTextParser.supportsSpeedShape(setOf(VietMapWidgetViewNames.CURRENT_SPEED)))
        assertTrue(VietMapWidgetTextParser.supportsAlertsShape(VietMapWidgetViewNames.alertsRequired))
        assertFalse(
            VietMapWidgetTextParser.supportsAlertsShape(
                VietMapWidgetViewNames.alertsRequired - VietMapWidgetViewNames.SECOND_ALERT_DISTANCE,
            ),
        )
    }
}

package com.byd.clusternav.modules.wazehud

import com.byd.clusternav.navigation.Maneuver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies WazeMod HLP/1 parsing + logcat-dump de-dup against the OFFICIAL protocol
 * (https://wazemod.chisadin.id.vn/tai-lieu/esp32, verified 2026-08-05). These are the boundary
 * that broke on the vehicle: the wire shape is fixed by the mod, so the parser must match it exactly.
 *
 * Uses the real org.json on the unit-test classpath (test-only dep). Shell is a no-op — parse/dedup
 * logic is pure.
 */
class WazeHudSourceTest {

    private fun source() = WazeHudSource { null }

    // ─── parseHlp vs the documented wire format ──────────────────────────────

    @Test
    fun `parseHlp parses the documented HLP-1 state sample`() {
        // Exact sample from the protocol doc's Serial monitor / logcat example.
        val line = """{"v":1,"t":"s","nav":1,"spd":47,"lim":50,"trn":3,"dst":120}"""
        val s = source().parseHlp(line)!!
        assertTrue(s.navigating)
        assertEquals(47, s.speedKmh)
        assertEquals(50, s.speedLimitKmh)
        assertEquals(3, s.turnCode)      // 3 = turn right
        assertEquals(120, s.distanceMeters)
    }

    @Test
    fun `parseHlp reads the full field set`() {
        val line = """{"v":1,"t":"s","nav":1,"spd":60,"lim":80,"over":1,"trn":2,"trn2":3,
            |"dst":300,"exit":2,"st":"Nguyen Trai","st2":"Tran Phu","eta":"14:30","rmin":12,
            |"rkm":8.4,"alr":2,"alrD":300,"alrV":40,"avg":1,"ts":124890}""".trimMargin().replace("\n", "")
        val s = source().parseHlp(line)!!
        assertEquals(80, s.speedLimitKmh)
        assertTrue(s.overSpeed)
        assertEquals("Nguyen Trai", s.currentStreet)
        assertEquals("Tran Phu", s.nextStreet)
        assertEquals("14:30", s.eta)
        assertEquals(12, s.remainingMinutes)
        assertEquals(8.4, s.remainingKm, 0.001)
        assertEquals(2, s.alertType)
        assertEquals(300, s.alertDistanceMeters)
        assertEquals(40, s.alertValue)
        assertTrue(s.avgZone)
        assertEquals(124890L, s.timestampMs)
    }

    @Test
    fun `parseHlp rejects wrong version and non-state messages`() {
        val src = source()
        assertNull(src.parseHlp("""{"v":2,"t":"s","spd":10}"""))   // wrong version
        assertNull(src.parseHlp("""{"v":1,"t":"dev"}"""))          // handshake, not state
        assertNull(src.parseHlp("""{"v":1}"""))                    // missing type
    }

    @Test
    fun `speed limit is present without an active route`() {
        // Driving with no route: nav=0 but lim is still delivered. The listener must NOT gate speed
        // on navigating (the on-car bug). This asserts the DATA carries the limit sans route.
        val s = source().parseHlp("""{"v":1,"t":"s","nav":0,"spd":30,"lim":50}""")!!
        assertFalse(s.navigating)
        assertEquals(50, s.speedLimitKmh)
    }

    @Test
    fun `zero or missing HLP limit is an explicit zero clear value`() {
        assertEquals(0, source().parseHlp("""{"v":1,"t":"s","nav":0,"lim":0}""")!!.speedLimitKmh)
        assertEquals(0, source().parseHlp("""{"v":1,"t":"s","nav":1}""")!!.speedLimitKmh)
    }

    @Test
    fun `dump availability transitions are idempotent and independent of de-dup`() {
        val src = source()
        val lifecycle = mutableListOf<WazeHudAvailability>()
        src.availabilityListener = lifecycle::add
        val frame = """{"v":1,"t":"s","nav":0,"lim":50,"ts":10}"""

        assertNotNull(src.processDump(frame))
        assertNull(src.processDump(frame), "same producer timestamp is de-duplicated")
        assertNull(src.processDump(null))
        assertNull(src.processDump(""))
        assertNotNull(src.processDump("""{"v":1,"t":"s","nav":0,"lim":0,"ts":11}"""))
        assertEquals(
            listOf(WazeHudAvailability.AVAILABLE, WazeHudAvailability.UNAVAILABLE, WazeHudAvailability.AVAILABLE),
            lifecycle,
        )
    }

    // ─── turn enum mapping ───────────────────────────────────────────────────

    @Test
    fun `hlpTurnToManeuver maps the documented turn enum to neutral Maneuver`() {
        assertNull(WazeHudSource.hlpTurnToManeuver(0))                            // none
        assertEquals(Maneuver.STRAIGHT, WazeHudSource.hlpTurnToManeuver(1))       // straight
        assertEquals(Maneuver.TURN_LEFT, WazeHudSource.hlpTurnToManeuver(2))      // left
        assertEquals(Maneuver.TURN_RIGHT, WazeHudSource.hlpTurnToManeuver(3))     // right
        assertEquals(Maneuver.SLIGHT_LEFT, WazeHudSource.hlpTurnToManeuver(4))    // slight left
        assertEquals(Maneuver.SLIGHT_RIGHT, WazeHudSource.hlpTurnToManeuver(5))   // slight right
        assertEquals(Maneuver.SHARP_LEFT, WazeHudSource.hlpTurnToManeuver(6))     // sharp left
        assertEquals(Maneuver.SHARP_RIGHT, WazeHudSource.hlpTurnToManeuver(7))    // sharp right
        assertEquals(Maneuver.UTURN, WazeHudSource.hlpTurnToManeuver(8))          // u-turn
        assertEquals(Maneuver.ROUNDABOUT, WazeHudSource.hlpTurnToManeuver(10))    // roundabout
        assertEquals(Maneuver.DESTINATION, WazeHudSource.hlpTurnToManeuver(17))   // arrived
        assertNull(WazeHudSource.hlpTurnToManeuver(99))                           // unknown → none
    }

    @Test
    fun `Waze roundabout is not confused with destination (magic-int class killed)`() {
        // Bug cũ: roundabout phát "15", HUD đọc "15" = destination. Enum tách bạch hai maneuver.
        val rab = WazeHudSource.hlpTurnToManeuver(10)!!
        val dst = WazeHudSource.hlpTurnToManeuver(17)!!
        assertEquals(Maneuver.ROUNDABOUT, rab)
        assertEquals(Maneuver.DESTINATION, dst)
        assertFalse(rab.toHudIcon() == dst.toHudIcon(), "roundabout và destination KHÔNG được cùng mã HUD")
    }

    @Test
    fun `Waze turns encode to non-straight HUD icons`() {
        // Cua thật KHÔNG được ra "đi thẳng" (11) trên HUD — đúng lớp lỗi đã sửa.
        for (trn in listOf(2, 3, 4, 5, 6, 7, 8)) {
            val m = WazeHudSource.hlpTurnToManeuver(trn)
            assertNotNull(m, "Waze turn $trn phải ra Maneuver")
            assertFalse(m!!.toHudIcon() == 11, "Waze turn $trn (cua) KHÔNG được ra 11/đi-thẳng trên HUD")
        }
    }

    // ─── pollOnce: newest-wins + de-dup by ts ────────────────────────────────

    @Test
    fun `pollOnce returns newest line and de-dups by ts`() {
        val src = source()
        val dump = """
            {"v":1,"t":"s","nav":1,"spd":40,"lim":50,"ts":100}
            {"v":1,"t":"s","nav":1,"spd":42,"lim":50,"ts":200}
        """.trimIndent()
        val first = src.pollOnce(dump)!!
        assertEquals(200L, first.timestampMs)   // last (newest) line wins
        assertEquals(42, first.speedKmh)
        // Same dump again → nothing newer than ts=200
        assertNull(src.pollOnce(dump))
        // A newer frame arrives
        val next = src.pollOnce("""{"v":1,"t":"s","nav":1,"spd":45,"lim":50,"ts":300}""")!!
        assertEquals(300L, next.timestampMs)
    }

    @Test
    fun `pollOnce skips non-JSON logcat noise`() {
        val src = source()
        val dump = """
            --------- beginning of main
            garbage line not json
            {"v":1,"t":"s","nav":1,"spd":33,"lim":60,"ts":10}
        """.trimIndent()
        val s = src.pollOnce(dump)!!
        assertEquals(33, s.speedKmh)
        assertEquals(60, s.speedLimitKmh)
    }

    @Test
    fun `pollOnce returns null for a dump with no state lines`() {
        assertNull(source().pollOnce("--------- beginning of system\nrandom\n"))
    }

    @Test
    fun `pollOnce recovers when the producer restarts and ts resets lower`() {
        val src = source()
        // WazeMod has been running a while: a large monotonic uptime.
        assertEquals(
            5_000_000L,
            src.pollOnce("""{"v":1,"t":"s","nav":1,"spd":40,"lim":50,"ts":5000000}""")!!.timestampMs,
        )
        // Producer restarts → uptime resets near zero. A `<=` guard would stall the feed until the new
        // uptime climbed back past 5,000,000; the fix accepts the lower ts as a fresh producer session.
        val afterRestart = src.pollOnce("""{"v":1,"t":"s","nav":1,"spd":41,"lim":50,"ts":1200}""")!!
        assertEquals(1200L, afterRestart.timestampMs)
        assertEquals(41, afterRestart.speedKmh)
        // A re-read of that same post-restart frame is still de-duped.
        assertNull(src.pollOnce("""{"v":1,"t":"s","nav":1,"spd":41,"lim":50,"ts":1200}"""))
    }

    // ─── toNavState mapping ──────────────────────────────────────────────────

    @Test
    fun `toNavState formats distance in km past 1000m and prefers next street`() {
        val src = source()
        val s = src.parseHlp("""{"v":1,"t":"s","nav":1,"trn":3,"dst":1200,"st":"A","st2":"B"}""")!!
        val nav = src.toNavState(s)
        assertTrue(nav.active)
        assertEquals("1.2 km", nav.distance)
        assertEquals("B", nav.road)   // next street preferred over current
        assertEquals(Maneuver.TURN_RIGHT, nav.maneuver)   // neutral truth
        assertEquals(3, nav.maneuverIcon)                 // AMAP suy từ maneuver cho làn cụm
    }
}

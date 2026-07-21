package com.byd.clusternav.modules.clustercast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Unit test (off-device, JUnit5) cho AppScale — serialize/parse/nudge/bounds. T-B verify. */
class AppScaleTest {

    @Test fun `default is auto with dpi 200`() {
        val s = AppScale()
        assertEquals(200, s.dpi)
        assertTrue(s.isAuto)
        assertEquals(-1, s.rectL)
    }

    @Test fun `isAuto true when any edge negative`() {
        assertTrue(AppScale(200, 0, 0, 100, -1).isAuto)
        assertTrue(AppScale(200, -1, 0, 100, 100).isAuto)
        assertFalse(AppScale(200, 0, 0, 100, 100).isAuto)
    }

    @Test fun `boundsOn auto returns full VD`() {
        val b = AppScale().boundsOn(1920, 720)
        assertEquals(listOf(0, 0, 1920, 720), b.toList())
    }

    @Test fun `boundsOn configured returns rect`() {
        val b = AppScale(210, 100, 90, 1200, 620).boundsOn(1920, 720)
        assertEquals(listOf(100, 90, 1200, 620), b.toList())
    }

    @Test fun `serialize then parse round-trips`() {
        val cases = listOf(
            AppScale(),
            AppScale(200, -1, -1, -1, -1),
            AppScale(240, 100, 90, 1200, 620),
            AppScale(120, 0, 0, 1920, 720),
        )
        for (s in cases) assertEquals(s, AppScale.parse(s.serialize()))
    }

    @Test fun `parse rejects malformed`() {
        assertNull(AppScale.parse(""))
        assertNull(AppScale.parse("200,-1,-1,-1"))       // 4 phần
        assertNull(AppScale.parse("200,-1,-1,-1,-1,-1")) // 6 phần
        assertNull(AppScale.parse("200,-1,-1,-1,x"))     // không phải số
    }

    // ── overscanOn (v0.35 — fallback size khi freeform chưa sống) ──

    @Test fun `overscanOn auto is zero insets`() {
        assertEquals(listOf(0, 0, 0, 0), AppScale().overscanOn(1920, 720).toList())
    }

    @Test fun `overscanOn maps rect to insets`() {
        // rect [100,40,1820,680] trên 1920×720 → insets [100, 40, 1920−1820, 720−680]
        assertEquals(listOf(100, 40, 100, 40), AppScale(200, 100, 40, 1820, 680).overscanOn(1920, 720).toList())
    }

    @Test fun `overscanOn clamps negative insets from oversized rect`() {
        // rect lưu từ cụm model khác TO HƠN VD hiện tại → không cho inset âm
        assertEquals(listOf(0, 0, 0, 0), AppScale(200, 0, 0, 2400, 900).overscanOn(1920, 720).toList())
    }

    @Test fun `overscanOn full rect is zero insets`() {
        assertEquals(listOf(0, 0, 0, 0), AppScale(200, 0, 0, 1920, 720).overscanOn(1920, 720).toList())
    }

    @Test fun `map serialize then parse round-trips`() {
        val m = linkedMapOf(
            "com.google.android.apps.maps" to AppScale(200, -1, -1, -1, -1),
            "com.apple.carplay" to AppScale(180, 360, 40, 1560, 680),
        )
        val round = AppScale.parseMap(AppScale.serializeMap(m))
        assertEquals(m, round)
    }

    @Test fun `parseMap tolerates blank and broken entries`() {
        val m = AppScale.parseMap("com.a=200,-1,-1,-1,-1||com.b=BAD|=100,0,0,0,0|com.c=210,10,20,30,40")
        assertEquals(2, m.size)                 // com.b (bad) + empty-key entry bỏ
        assertEquals(AppScale(200, -1, -1, -1, -1), m["com.a"])
        assertEquals(AppScale(210, 10, 20, 30, 40), m["com.c"])
    }

    @Test fun `serializeMap empty is blank and parseMap blank is empty`() {
        assertEquals("", AppScale.serializeMap(emptyMap()))
        assertTrue(AppScale.parseMap("").isEmpty())
    }

    @Test fun `nudgeRect taller expands height around center`() {
        // khung 900x500 tại (100,100) → cao +16 quanh tâm dọc
        val s = AppScale(200, 100, 100, 1000, 600).nudgeRect(1920, 720, 0, AppScale.STEP_WH)
        assertEquals(100, s.rectL)                     // ngang không đổi
        assertEquals(1000, s.rectR)
        assertEquals(92, s.rectT)                      // 100 - 8
        assertEquals(608, s.rectB)                     // 600 + 8
        assertEquals(516, s.rectB - s.rectT)           // cao 500 -> 516 (+16)
    }

    @Test fun `nudgeRect narrower shrinks width around center`() {
        val s = AppScale(200, 100, 100, 1000, 600).nudgeRect(1920, 720, -AppScale.STEP_WH, 0)
        assertEquals(108, s.rectL)                     // 100 + 8
        assertEquals(992, s.rectR)                     // 1000 - 8
        assertEquals(884, s.rectR - s.rectL)           // rộng 900 -> 884 (-16)
    }

    @Test fun `nudgeRect from auto materializes full VD then clamps`() {
        // auto + "cao lên" khi đã full → giữ full (không vượt VD)
        val s = AppScale().nudgeRect(1920, 720, 0, AppScale.STEP_WH)
        assertFalse(s.isAuto)
        assertEquals(listOf(0, 0, 1920, 720), s.boundsOn(1920, 720).toList())
    }

    @Test fun `nudgeRect respects MIN_PX floor`() {
        // khung đã rất hẹp, thu tiếp không được nhỏ hơn MIN_PX
        val start = AppScale(200, 800, 300, 800 + AppScale.MIN_PX, 500)
        val s = start.nudgeRect(1920, 720, -AppScale.STEP_WH, 0)
        assertTrue(s.rectR - s.rectL >= AppScale.MIN_PX)
    }

    @Test fun `nudgeDpi clamps to range`() {
        assertEquals(210, AppScale(200).nudgeDpi(10).dpi)
        assertEquals(AppScale.DPI_MIN, AppScale(200).nudgeDpi(-500).dpi)
        assertEquals(AppScale.DPI_MAX, AppScale(200).nudgeDpi(500).dpi)
    }

    @Test fun `nudgeDpi keeps rect`() {
        val s = AppScale(200, 10, 20, 30, 40).nudgeDpi(10)
        assertEquals(10, s.rectL); assertEquals(40, s.rectB)
    }

    @Test fun `nudgeEdge left edge moves independently and recalculates size`() {
        val s = AppScale(200, 100, 100, 1000, 600).nudgeEdge(1920, 720, AppScale.Edge.LEFT, -16)
        assertEquals(84, s.rectL)        // 100 - 16 (rộng ra)
        assertEquals(1000, s.rectR)      // phải GIỮ
        assertEquals(100, s.rectT); assertEquals(600, s.rectB)  // dọc GIỮ
    }

    @Test fun `nudgeEdge right edge inward shrinks width, left kept`() {
        val s = AppScale(200, 100, 100, 1000, 600).nudgeEdge(1920, 720, AppScale.Edge.RIGHT, -16)
        assertEquals(984, s.rectR); assertEquals(100, s.rectL)
    }

    @Test fun `nudgeEdge bottom edge down grows height, top kept`() {
        val s = AppScale(200, 100, 100, 1000, 600).nudgeEdge(1920, 720, AppScale.Edge.BOTTOM, 16)
        assertEquals(616, s.rectB); assertEquals(100, s.rectT)
    }

    @Test fun `nudgeEdge clamps within VD bounds`() {
        assertEquals(0, AppScale(200, 10, 100, 1000, 600).nudgeEdge(1920, 720, AppScale.Edge.LEFT, -999).rectL)
        assertEquals(1920, AppScale(200, 100, 100, 1900, 600).nudgeEdge(1920, 720, AppScale.Edge.RIGHT, 999).rectR)
    }

    @Test fun `nudgeEdge keeps MIN_PX between opposite edges`() {
        val s = AppScale(200, 100, 100, 100 + AppScale.MIN_PX, 600).nudgeEdge(1920, 720, AppScale.Edge.LEFT, 999)
        assertTrue(s.rectR - s.rectL >= AppScale.MIN_PX)
    }

    @Test fun `nudgeEdge from auto materializes full then adjusts one edge`() {
        val s = AppScale().nudgeEdge(1920, 720, AppScale.Edge.RIGHT, -100)
        assertFalse(s.isAuto)
        assertEquals(0, s.rectL); assertEquals(0, s.rectT); assertEquals(720, s.rectB)
        assertEquals(1820, s.rectR)      // full 1920 → 1820
    }

    @Test fun `nudgeMove shifts frame keeping size`() {
        val s = AppScale(200, 100, 100, 1000, 600).nudgeMove(1920, 720, 50, -40)
        assertEquals(150, s.rectL); assertEquals(1050, s.rectR)   // +50 ngang, cỡ 900 giữ
        assertEquals(60, s.rectT); assertEquals(560, s.rectB)     // -40 dọc, cỡ 500 giữ
        assertEquals(900, s.rectR - s.rectL); assertEquals(500, s.rectB - s.rectT)
    }

    @Test fun `nudgeMove clamps to left-top edge keeping size`() {
        val s = AppScale(200, 100, 100, 1000, 600).nudgeMove(1920, 720, -999, -999)
        assertEquals(0, s.rectL); assertEquals(0, s.rectT)        // dán mép trái-trên
        assertEquals(900, s.rectR); assertEquals(500, s.rectB)    // cỡ giữ nguyên
    }

    @Test fun `nudgeMove clamps to right-bottom edge keeping size`() {
        val s = AppScale(200, 100, 100, 1000, 600).nudgeMove(1920, 720, 9999, 9999)
        assertEquals(1920, s.rectR); assertEquals(720, s.rectB)   // dán mép phải-dưới
        assertEquals(1020, s.rectL); assertEquals(220, s.rectT)   // cỡ 900×500 giữ
    }

    @Test fun `nudgeMove from auto is full VD so no room to move`() {
        val s = AppScale().nudgeMove(1920, 720, 50, 50)
        assertFalse(s.isAuto)
        assertEquals(listOf(0, 0, 1920, 720), s.boundsOn(1920, 720).toList())  // full → clamp giữ full
    }
}

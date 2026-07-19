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
}

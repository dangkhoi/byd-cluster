package com.byd.clusternav.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit test THUẦN cho TurnDistanceInterpolator — khoá lại các kịch bản bug đã sửa:
 * dừng-đèn-đỏ phải GIỮ số (không tự trôi về 0), đang đi thì trừ dần, maneuver mới thì snap.
 */
class TurnDistanceInterpolatorTest {

    @BeforeEach fun setup() { TurnDistanceInterpolator.reset() }

    @Test fun `chưa anchor thì project trả -1`() {
        assertEquals(-1, TurnDistanceInterpolator.project(10.0, 1000L))
    }

    @Test fun `anchor đặt baseline`() {
        TurnDistanceInterpolator.anchor(1000, "k", 1000L)
        assertEquals(1000, TurnDistanceInterpolator.anchorMeters())
    }

    @Test fun `dừng (tốc độ 0) GIỮ số — không trôi về 0`() {
        TurnDistanceInterpolator.anchor(1000, "k", 1000L)
        assertEquals(1000, TurnDistanceInterpolator.project(0.0, 2000L))
        assertEquals(1000, TurnDistanceInterpolator.project(0.0, 5000L))   // đứng yên lâu vẫn giữ
    }

    @Test fun `đang đi thì cự ly trừ dần theo tốc độ thật`() {
        TurnDistanceInterpolator.anchor(1000, "k", 1000L)
        val out = TurnDistanceInterpolator.project(10.0, 2000L)   // 10 m/s trong 1s → ~9.5m (FACTOR 0.95)
        assertTrue(out in 985..995, "kỳ vọng ~990, thực tế $out")
        assertTrue(out < 1000, "phải giảm so với baseline")
    }

    @Test fun `maneuver mới (đổi key) SNAP thẳng sang cự ly mới`() {
        TurnDistanceInterpolator.anchor(1000, "k1", 1000L)
        TurnDistanceInterpolator.project(10.0, 2000L)
        TurnDistanceInterpolator.anchor(500, "k2", 3000L)          // key khác → snap
        assertEquals(500, TurnDistanceInterpolator.anchorMeters())
        assertEquals(500, TurnDistanceInterpolator.project(0.0, 3000L))
    }

    @Test fun `clearAnchor xoá track (frame chỉ-hướng)`() {
        TurnDistanceInterpolator.anchor(1000, "k", 1000L)
        TurnDistanceInterpolator.clearAnchor()
        assertEquals(-1, TurnDistanceInterpolator.project(10.0, 2000L))
    }

    @Test fun `refine ghi lại ground-truth đọc-màn cho log (kể cả chưa anchor)`() {
        assertEquals(-1, TurnDistanceInterpolator.lastRefined())
        TurnDistanceInterpolator.refine(250, 1234L)          // chưa anchor: không snap nhưng VẪN ghi ground-truth
        assertEquals(250, TurnDistanceInterpolator.lastRefined())
        assertEquals(1234L, TurnDistanceInterpolator.lastRefinedAt())
    }

    @Test fun `refine âm không đè ground-truth đã ghi`() {
        TurnDistanceInterpolator.refine(180, 1000L)
        TurnDistanceInterpolator.refine(-1, 2000L)           // đọc-màn thất bại → giữ giá trị cũ
        assertEquals(180, TurnDistanceInterpolator.lastRefined())
        assertEquals(1000L, TurnDistanceInterpolator.lastRefinedAt())
    }

    @Test fun `reset xoá ground-truth đọc-màn`() {
        TurnDistanceInterpolator.refine(180, 1000L)
        TurnDistanceInterpolator.reset()
        assertEquals(-1, TurnDistanceInterpolator.lastRefined())
        assertEquals(0L, TurnDistanceInterpolator.lastRefinedAt())
    }
}

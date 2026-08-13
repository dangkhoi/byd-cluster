package com.byd.clusternav.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit test THUẦN cho HudKeepAlivePolicy (J1) — khoá logic "khi nào re-assert giữ HUD sống":
 * chưa có frame → không bao giờ re-assert; sau khi ghi → chỉ re-assert khi đã ≥ interval;
 * mỗi lần ghi mới reset đồng hồ; clear thì dừng.
 */
class HudKeepAlivePolicyTest {

    @Test fun `chưa có frame — không bao giờ re-assert`() {
        val p = HudKeepAlivePolicy(400L)
        assertFalse(p.shouldReassert(0L))
        assertFalse(p.shouldReassert(10_000L))
    }

    @Test fun `sau khi ghi — chỉ re-assert khi đã đủ interval`() {
        val p = HudKeepAlivePolicy(400L)
        p.onFrameWritten(1_000L)
        assertFalse(p.shouldReassert(1_000L))    // cùng thời điểm
        assertFalse(p.shouldReassert(1_399L))    // vừa dưới ngưỡng
        assertTrue(p.shouldReassert(1_400L))     // đúng interval
        assertTrue(p.shouldReassert(5_000L))     // stale lâu
    }

    @Test fun `ghi frame mới reset đồng hồ stale`() {
        val p = HudKeepAlivePolicy(400L)
        p.onFrameWritten(1_000L)
        assertTrue(p.shouldReassert(1_400L))
        p.onFrameWritten(1_400L)                 // real push (hoặc re-assert) làm tươi lại
        assertFalse(p.shouldReassert(1_500L))
        assertTrue(p.shouldReassert(1_800L))
    }

    @Test fun `clear thì dừng re-assert`() {
        val p = HudKeepAlivePolicy(400L)
        p.onFrameWritten(1_000L)
        p.onCleared()
        assertFalse(p.shouldReassert(10_000L))
    }

    @Test fun `interval phải dương`() {
        assertThrows(IllegalArgumentException::class.java) { HudKeepAlivePolicy(0L) }
        assertThrows(IllegalArgumentException::class.java) { HudKeepAlivePolicy(-1L) }
    }

    @Test fun `default interval = 400ms (khớp nhịp làn cụm)`() {
        assertEquals(400L, HudKeepAlivePolicy().intervalMs())
        assertEquals(HudKeepAlivePolicy.DEFAULT_INTERVAL_MS, HudKeepAlivePolicy().intervalMs())
    }
}

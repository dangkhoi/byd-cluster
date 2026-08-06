package com.byd.clusternav.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Maneuver TRUNG LẬP + hai encoder — hợp đồng "một quyết định, hai đầu ra". Các test này khoá lại bất
 * biến chống lệch: cả làn cụm và HUD là HÀM của cùng một Maneuver, không đầu ra nào suy lại từ chữ/ảnh.
 */
class ManeuverTest {

    @Test fun `toAmapIcon khớp từ vựng AMAP NEW_ICON của làn cụm`() {
        assertEquals(2, Maneuver.TURN_LEFT.toAmapIcon())
        assertEquals(3, Maneuver.TURN_RIGHT.toAmapIcon())
        assertEquals(4, Maneuver.SLIGHT_LEFT.toAmapIcon())
        assertEquals(5, Maneuver.SLIGHT_RIGHT.toAmapIcon())
        assertEquals(6, Maneuver.SHARP_LEFT.toAmapIcon())
        assertEquals(7, Maneuver.SHARP_RIGHT.toAmapIcon())
        assertEquals(8, Maneuver.UTURN.toAmapIcon())
        assertEquals(9, Maneuver.STRAIGHT.toAmapIcon())
        assertEquals(11, Maneuver.ROUNDABOUT.toAmapIcon())
        assertEquals(15, Maneuver.DESTINATION.toAmapIcon())
        assertEquals(20, Maneuver.CONTINUE.toAmapIcon())
    }

    @Test fun `toHudIcon khớp mã CAN HUD`() {
        assertEquals(1, Maneuver.TURN_LEFT.toHudIcon())
        assertEquals(2, Maneuver.TURN_RIGHT.toHudIcon())
        assertEquals(3, Maneuver.SLIGHT_LEFT.toHudIcon())
        assertEquals(5, Maneuver.SLIGHT_RIGHT.toHudIcon())
        assertEquals(7, Maneuver.SHARP_LEFT.toHudIcon())
        assertEquals(8, Maneuver.SHARP_RIGHT.toHudIcon())
        assertEquals(9, Maneuver.UTURN.toHudIcon())
        assertEquals(11, Maneuver.STRAIGHT.toHudIcon())
        assertEquals(15, Maneuver.ROUNDABOUT.toHudIcon())
        assertEquals(48, Maneuver.DESTINATION.toHudIcon())
        assertEquals(11, Maneuver.CONTINUE.toHudIcon())
    }

    @Test fun `fromAmapIcon khứ hồi CHÍNH XÁC với từ vựng pipeline phát ra (làn cụm bất biến)`() {
        // Bảo chứng: maneuverIcon(out) = fromAmapIcon(x).toAmapIcon() = x → AmapFrameBuilder không đổi hành vi.
        for (x in listOf(2, 3, 4, 5, 6, 7, 8, 9, 11, 15, 20)) {
            assertEquals(x, Maneuver.fromAmapIcon(x)!!.toAmapIcon(), "AMAP $x phải khứ hồi chính xác")
        }
    }

    @Test fun `fromAmapIcon trả null cho none-unknown (caller coi như chưa có maneuver)`() {
        assertNull(Maneuver.fromAmapIcon(0))    // none
        assertNull(Maneuver.fromAmapIcon(-1))   // chưa phân loại
        assertNull(Maneuver.fromAmapIcon(1))    // start-pin (không dùng)
        assertNull(Maneuver.fromAmapIcon(28))   // mã ngoài từ vựng
    }

    @Test fun `mọi cua rẽ KHÔNG encode ra đi-thẳng (chống đúng lỗi HUD báo)`() {
        val turns = listOf(
            Maneuver.TURN_LEFT, Maneuver.TURN_RIGHT, Maneuver.SLIGHT_LEFT, Maneuver.SLIGHT_RIGHT,
            Maneuver.SHARP_LEFT, Maneuver.SHARP_RIGHT, Maneuver.UTURN,
        )
        for (m in turns) {
            assertFalse(m.toHudIcon() == 11, "$m KHÔNG được ra 11/đi-thẳng trên HUD")
            assertFalse(m.toAmapIcon() == 9, "$m KHÔNG được ra 9/đi-thẳng trên làn cụm")
        }
    }

    @Test fun `roundabout và destination KHÔNG trùng nghĩa (khử lớp bug magic-int '15')`() {
        // Bug cũ: "15" vừa là roundabout (Waze) vừa là destination (AMAP). Enum cho mỗi Maneuver đúng
        // MỘT cặp mã (amap, can) — không thể lẫn.
        assertEquals(11, Maneuver.ROUNDABOUT.toAmapIcon())
        assertEquals(15, Maneuver.ROUNDABOUT.toHudIcon())
        assertEquals(15, Maneuver.DESTINATION.toAmapIcon())
        assertEquals(48, Maneuver.DESTINATION.toHudIcon())
        assertFalse(Maneuver.ROUNDABOUT.toHudIcon() == Maneuver.DESTINATION.toHudIcon())
        assertFalse(Maneuver.ROUNDABOUT.toAmapIcon() == Maneuver.DESTINATION.toAmapIcon())
    }
}

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
        // Track B — reuse-mã (encode-only): merge→9(thẳng), ramp/fork/keep→4/5(chếch).
        assertEquals(9, Maneuver.MERGE.toAmapIcon())
        assertEquals(4, Maneuver.RAMP_LEFT.toAmapIcon())
        assertEquals(5, Maneuver.RAMP_RIGHT.toAmapIcon())
        assertEquals(4, Maneuver.FORK_LEFT.toAmapIcon())
        assertEquals(5, Maneuver.FORK_RIGHT.toAmapIcon())
        assertEquals(4, Maneuver.KEEP_LEFT.toAmapIcon())
        assertEquals(5, Maneuver.KEEP_RIGHT.toAmapIcon())
        // Track B — mã DUY NHẤT (NEW_ICON 0..28 chưa dùng trước đây).
        assertEquals(19, Maneuver.UTURN_RIGHT.toAmapIcon())
        assertEquals(12, Maneuver.ROUNDABOUT_EXIT.toAmapIcon())
        assertEquals(16, Maneuver.TUNNEL.toAmapIcon())
        assertEquals(13, Maneuver.SERVICE_AREA.toAmapIcon())
        assertEquals(14, Maneuver.TOLL.toAmapIcon())
        assertEquals(10, Maneuver.WAYPOINT.toAmapIcon())
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
        // Track B — CAN id = TurnIdMapToCAN[toAmapIcon] cho từng giá trị mới (bất biến hai-đầu-ra).
        assertEquals(11, Maneuver.MERGE.toHudIcon())
        assertEquals(3, Maneuver.RAMP_LEFT.toHudIcon())
        assertEquals(5, Maneuver.RAMP_RIGHT.toHudIcon())
        assertEquals(3, Maneuver.FORK_LEFT.toHudIcon())
        assertEquals(5, Maneuver.FORK_RIGHT.toHudIcon())
        assertEquals(3, Maneuver.KEEP_LEFT.toHudIcon())
        assertEquals(5, Maneuver.KEEP_RIGHT.toHudIcon())
        assertEquals(10, Maneuver.UTURN_RIGHT.toHudIcon())
        assertEquals(24, Maneuver.ROUNDABOUT_EXIT.toHudIcon())
        assertEquals(49, Maneuver.TUNNEL.toHudIcon())
        assertEquals(46, Maneuver.SERVICE_AREA.toHudIcon())
        assertEquals(47, Maneuver.TOLL.toHudIcon())
        assertEquals(45, Maneuver.WAYPOINT.toHudIcon())
    }

    @Test fun `fromAmapIcon khứ hồi CHÍNH XÁC với từ vựng pipeline phát ra (làn cụm bất biến)`() {
        // Bảo chứng: maneuverIcon(out) = fromAmapIcon(x).toAmapIcon() = x → AmapFrameBuilder không đổi hành vi.
        // Track B: mở rộng {…,10,12,13,14,16,19} — glyph 途经点/驶出环岛/服务区/收费站/隧道/右转掉头.
        for (x in listOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 19, 20)) {
            assertEquals(x, Maneuver.fromAmapIcon(x)!!.toAmapIcon(), "AMAP $x phải khứ hồi chính xác")
        }
    }

    @Test fun `fromAmapIcon mã DUY NHẤT Track B nghịch về đúng Maneuver`() {
        assertEquals(Maneuver.WAYPOINT, Maneuver.fromAmapIcon(10))
        assertEquals(Maneuver.ROUNDABOUT_EXIT, Maneuver.fromAmapIcon(12))
        assertEquals(Maneuver.SERVICE_AREA, Maneuver.fromAmapIcon(13))
        assertEquals(Maneuver.TOLL, Maneuver.fromAmapIcon(14))
        assertEquals(Maneuver.TUNNEL, Maneuver.fromAmapIcon(16))
        assertEquals(Maneuver.UTURN_RIGHT, Maneuver.fromAmapIcon(19))
    }

    @Test fun `fromAmapIcon KHÔNG nghịch reuse-mã về alias (9-4-5 giữ chủ sở hữu chuẩn)`() {
        // MERGE/RAMP_*/FORK_*/KEEP_* là encode-only: 9→STRAIGHT, 4→SLIGHT_LEFT, 5→SLIGHT_RIGHT (KHÔNG về alias).
        assertEquals(Maneuver.STRAIGHT, Maneuver.fromAmapIcon(9))
        assertEquals(Maneuver.SLIGHT_LEFT, Maneuver.fromAmapIcon(4))
        assertEquals(Maneuver.SLIGHT_RIGHT, Maneuver.fromAmapIcon(5))
    }

    @Test fun `fromAmapIcon trả null cho none-unknown (caller coi như chưa có maneuver)`() {
        assertNull(Maneuver.fromAmapIcon(0))    // none
        assertNull(Maneuver.fromAmapIcon(-1))   // chưa phân loại
        assertNull(Maneuver.fromAmapIcon(1))    // start-pin (không dùng) — VẪN ngoài từ vựng sau Track B
        assertNull(Maneuver.fromAmapIcon(17))   // enter-roundabout CW — chưa nạp
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

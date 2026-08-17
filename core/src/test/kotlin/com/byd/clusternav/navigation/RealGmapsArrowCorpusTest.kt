package com.byd.clusternav.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import javax.imageio.ImageIO

/**
 * CORPUS TEST — mũi tên GMaps THẬT (ground truth, không tổng hợp).
 *
 * Vá đúng lỗ mà [ManeuverSignatureTest] tự ghi: "Kiểm ĐÚNG tên hướng rẽ cần ảnh mũi thật lưu thành fixture
 * — chưa có". 6 ảnh trong `resources/arrows/` là largeIcon 72×72 RGBA bắt trực tiếp từ notification GMaps
 * (stock GMaps mới nhất trên emulator, GPS mock 1 tuyến ở TP.HCM, 2026-08-17) qua NavArrowLog. Xem
 * `arrows/README.txt`.
 *
 * Hai khẳng định:
 *  1) [ManeuverSignature.classify] (lớp CHÍNH, khớp chữ ký Hamming/NCC) phải đọc ĐÚNG cả 6 — chốt rằng
 *     registry (port OpenBYD) THỰC SỰ khớp mũi tên GMaps hiện tại (trước nay chưa ai kiểm).
 *  2) [ArrowClassifier] (lớp DỰ PHÒNG COM, chỉ dùng khi chữ ký + verb trượt) phải ra đúng HƯỚNG. Trước fix
 *     2026-08-17 nó đọc normal-left/right = "đi thẳng" (thân dọc mũi tên triệt tiêu trọng tâm) → đây là cơ
 *     chế "rẽ trái mà cụm đi thẳng" khi chữ ký trượt. Nó KHÔNG phân biệt được độ gắt (normal vs slight) nên
 *     chỉ khẳng định HƯỚNG; u-turn nằm ngoài từ vựng {2,3,4,5,9} của lớp này (chữ ký lo, xem #1).
 */
class RealGmapsArrowCorpusTest {

    private fun frame(file: String): PixelFrame {
        val url = requireNotNull(javaClass.getResource("/arrows/$file")) { "missing fixture arrows/$file" }
        val img = requireNotNull(ImageIO.read(url)) { "cannot decode arrows/$file" }
        val w = img.width; val h = img.height
        val px = IntArray(w * h)
        img.getRGB(0, 0, w, h, px, 0, w)   // TYPE_INT_ARGB — khớp PixelFrame.argb() (ARGB_8888)
        return ArrayPixelFrame(w, h, px)
    }

    /** Hướng thô: trái {2,4,6}, phải {3,5,7}, thẳng {9}. */
    private fun dir(icon: Int?): String = when (icon) {
        2, 4, 6 -> "L"; 3, 5, 7 -> "R"; 9 -> "S"; null -> "null"; else -> "other($icon)"
    }

    @Test
    fun `ManeuverSignature reads every real GMaps arrow correctly`() {
        assertEquals(9, ManeuverSignature.classify(frame("depart.png")), "depart → straight(9)")
        assertEquals(4, ManeuverSignature.classify(frame("slight_left.png")), "slight left(4)")
        assertEquals(5, ManeuverSignature.classify(frame("slight_right.png")), "slight right(5)")
        assertEquals(2, ManeuverSignature.classify(frame("normal_left.png")), "turn left(2)")
        assertEquals(3, ManeuverSignature.classify(frame("normal_right.png")), "turn right(3)")
        assertEquals(8, ManeuverSignature.classify(frame("u_turn_left.png")), "u-turn(8)")
    }

    @Test
    fun `ArrowClassifier fallback gets turn DIRECTION right on real arrows`() {
        assertEquals("S", dir(ArrowClassifier.classify(frame("depart.png"))), "depart is straight")
        assertEquals("L", dir(ArrowClassifier.classify(frame("slight_left.png"))), "slight-left is LEFT")
        assertEquals("R", dir(ArrowClassifier.classify(frame("slight_right.png"))), "slight-right is RIGHT")
        // Regression guards (were "S"=straight before the 2026-08-17 arrowhead-weight fix):
        assertEquals("L", dir(ArrowClassifier.classify(frame("normal_left.png"))), "normal-left must be LEFT, not straight")
        assertEquals("R", dir(ArrowClassifier.classify(frame("normal_right.png"))), "normal-right must be RIGHT, not straight")
    }
}

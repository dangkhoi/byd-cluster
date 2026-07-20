package com.byd.clusternav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Unit test THUẦN cho NavFormat — dọn/rút gọn tên đường VN + map lệnh rẽ → mã icon AMAP. */
class NavFormatTest {

    @Test fun `cleanRoadName bỏ động từ rẽ`() {
        assertEquals("Nguyễn Huệ", NavFormat.cleanRoadName("Rẽ phải vào Nguyễn Huệ"))
    }

    @Test fun `cleanRoadName bỏ tiền tố Đường-Phố`() {
        assertEquals("Nguyễn Huệ", NavFormat.cleanRoadName("Đường Nguyễn Huệ"))
    }

    @Test fun `cleanRoadName viết tắt loại đường có số`() {
        assertEquals("QL1A", NavFormat.cleanRoadName("Quốc lộ 1A"))
    }

    @Test fun `cleanRoadName GIỮ hầm-cầu (có nghĩa)`() {
        assertEquals("Hầm Thủ Thiêm", NavFormat.cleanRoadName("Hầm Thủ Thiêm"))
    }

    @Test fun `fitRoadName rút gọn về acronym khi quá dài`() {
        assertEquals("NHC", NavFormat.fitRoadName("Nguyễn Hữu Cảnh"))
    }

    @Test fun `fitRoadName giữ từ-loại hầm ở đầu`() {
        assertEquals("Hầm TT", NavFormat.fitRoadName("Hầm Thủ Thiêm"))
    }

    @Test fun `roadWindow tên ngắn trả nguyên, tên dài đúng bề rộng`() {
        assertEquals("ABC", NavFormat.roadWindow("ABC", 5, 7))
        val w = NavFormat.roadWindow("ABCDEFGHIJ", 0, 5)
        assertEquals(5, w.length)
        assertEquals("ABCDE", w)
    }

    @Test fun `maneuverVerbIcon map đúng hướng`() {
        assertEquals(3, NavFormat.maneuverVerbIcon("Rẽ phải vào X"))
        assertEquals(2, NavFormat.maneuverVerbIcon("rẽ trái"))
        assertEquals(8, NavFormat.maneuverVerbIcon("quay đầu"))
        assertEquals(9, NavFormat.maneuverVerbIcon("đi thẳng"))
        assertEquals(11, NavFormat.maneuverVerbIcon("vòng xuyến"))
        assertNull(NavFormat.maneuverVerbIcon("blah blah"))
    }

    @Test fun `maneuverToAmapIcon fallback đi thẳng`() {
        assertEquals(9, NavFormat.maneuverToAmapIcon("chuỗi không có động từ"))
    }

    @Test fun `roundaboutExit rút số nhánh`() {
        assertEquals(3, NavFormat.roundaboutExit("lối ra thứ 3"))
        assertEquals(2, NavFormat.roundaboutExit("take the 2nd exit"))
        assertEquals(-1, NavFormat.roundaboutExit("đi thẳng"))
    }

    @Test fun `asciiToken bỏ dấu + gộp ký tự lạ`() {
        assertEquals("Nguyen_Hue", NavFormat.asciiToken("Nguyễn Huệ"))
        assertEquals("Duong", NavFormat.asciiToken("Đường"))
        assertEquals("Road", NavFormat.asciiToken("!!!"))   // rỗng sau khi lọc → fallback
    }

    @Test fun `asciiToken an toàn cho shell-arg (chỉ chữ-số-gạch dưới)`() {
        val t = NavFormat.asciiToken("Cầu Sài Gòn (Q.2); rm -rf /")
        assertTrue(Regex("^[A-Za-z0-9_]+$").matches(t), "token phải an toàn shell: '$t'")
    }
}

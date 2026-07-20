package com.byd.clusternav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Unit test THUẦN (off-device) cho NavParse — giá trị đi thẳng lên cụm nên phải bám sát. */
class NavParseTest {

    @Test fun `parseMeters đọc m và km, dấu phẩy hay chấm`() {
        assertEquals(250, NavParse.parseMeters("250 m"))
        assertEquals(500, NavParse.parseMeters("500m"))
        assertEquals(1200, NavParse.parseMeters("1.2 km"))
        assertEquals(1200, NavParse.parseMeters("1,2 km"))
        assertEquals(2000, NavParse.parseMeters("2 km"))
    }

    @Test fun `parseMeters trả -1 khi không có cự ly`() {
        assertEquals(-1, NavParse.parseMeters("Nguyễn Huệ"))
        assertEquals(-1, NavParse.parseMeters(""))
    }

    @Test fun `quantizeDisplay bước theo độ xa`() {
        assertEquals(1200, NavParse.quantizeDisplay(1234))   // >=1km: bước 100
        assertEquals(325, NavParse.quantizeDisplay(347))     // 300..999: bước 25
        assertEquals(150, NavParse.quantizeDisplay(156))     // 100..299: bước 10
        assertEquals(45, NavParse.quantizeDisplay(47))       // <100: bước 5
        assertEquals(-1, NavParse.quantizeDisplay(-1))       // âm giữ nguyên
    }

    @Test fun `parseEta rút cự ly còn lại và giây`() {
        val (dis, sec) = NavParse.parseEta("10:32 · 5.2 km · 8 phút")
        assertEquals(5200, dis)
        assertEquals(8 * 60, sec)
    }

    @Test fun `parseEta có giờ cộng phút`() {
        val (_, sec) = NavParse.parseEta("2 giờ 5 phút")
        assertEquals(2 * 3600 + 5 * 60, sec)
    }

    @Test fun `parseEta thiếu thời gian trả -1`() {
        val (dis, sec) = NavParse.parseEta("5.2 km")
        assertEquals(5200, dis)
        assertEquals(-1, sec)
    }

    @Test fun `formatMeters khớp DashCast`() {
        assertEquals("1.5 km", NavParse.formatMeters(1500))
        assertEquals("500 m", NavParse.formatMeters(500))
    }

    @Test fun `formatSeconds giờ và phút`() {
        assertEquals("8 min", NavParse.formatSeconds(480))
        assertEquals("1h 2m", NavParse.formatSeconds(3720))
    }

    @Test fun `formatRemainTimeCn ra token tiếng Trung parseTime đọc được`() {
        assertEquals("8分", NavParse.formatRemainTimeCn(480))
        assertEquals("1时2分", NavParse.formatRemainTimeCn(3720))
        assertEquals("-1", NavParse.formatRemainTimeCn(-1))
    }

    @Test fun `extractArrivalClock chỉ nhận giờ hợp lệ`() {
        assertEquals("10:32", NavParse.extractArrivalClock("10:32 · 5.2 km"))
        assertNull(NavParse.extractArrivalClock("25:99 sai giờ"))
        assertNull(NavParse.extractArrivalClock("không có giờ"))
    }

    @Test fun `formatEtaCn bọc đúng khung 预计到达`() {
        assertEquals("预计今天10:32到达", NavParse.formatEtaCn("10:32"))
    }
}

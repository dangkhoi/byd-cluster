package com.byd.clusternav

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Khoá lại việc VietMap thật sự được LẮNG NGHE, không chỉ được PARSE được.
 *
 * NotificationParser đã biết đọc field-đảo của VietMap từ trước (xem NotificationParserTest), nhưng
 * NavNotificationListener.onNotificationPosted/onNotificationRemoved lọc ở MAPS_PACKAGES TRƯỚC KHI
 * gọi parser — một notification không nằm trong tập này không bao giờ tới được parser. Gói xác nhận
 * qua dump thật từ xe (WmParseTest.kt: "vn.vietmap.live/.MainActivity").
 */
class NavNotificationListenerTest {
    @Test
    fun `VietMap package is in the listened set, using the real confirmed package name`() {
        assertTrue("vn.vietmap.live" in NavNotificationListener.MAPS_PACKAGES)
    }
}

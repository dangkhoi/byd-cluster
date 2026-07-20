package com.byd.clusternav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Unit test THUẦN cho SourceArbiter — máy trạng thái chọn nguồn khi mở >1 app dẫn đường. */
class SourceArbiterTest {

    private val GMAPS = "com.google.android.apps.maps"

    @BeforeEach fun setup() { SourceArbiter.clear() }

    @Test fun `AUTO app dẫn TRƯỚC giữ khoá, app sau bị bỏ qua`() {
        assertTrue(SourceArbiter.shouldFeed(GMAPS, Prefs.AUTO, 1000L))
        assertEquals(GMAPS, SourceArbiter.activeSource)
        assertFalse(SourceArbiter.shouldFeed("vn.vietmap.app", Prefs.AUTO, 1100L))   // còn tươi → chặn
    }

    @Test fun `AUTO nhả khoá đúng nguồn thì app khác lên được`() {
        SourceArbiter.shouldFeed(GMAPS, Prefs.AUTO, 1000L)
        assertFalse(SourceArbiter.release("vn.vietmap.app"))   // không phải nguồn giữ
        assertTrue(SourceArbiter.release(GMAPS))               // đúng nguồn giữ
        assertTrue(SourceArbiter.shouldFeed("vn.vietmap.app", Prefs.AUTO, 1200L))
    }

    @Test fun `AUTO nguồn IM quá STALE thì nhường`() {
        SourceArbiter.shouldFeed(GMAPS, Prefs.AUTO, 1000L)
        val staleLater = 1000L + SourceArbiter.STALE_MS + 1
        assertTrue(SourceArbiter.shouldFeed("vn.vietmap.app", Prefs.AUTO, staleLater))
        assertEquals("vn.vietmap.app", SourceArbiter.activeSource)
    }

    @Test fun `PREFER_GMAPS chặn app khác khi GMaps còn tươi`() {
        // lastSeenByPkg là map singleton KHÔNG bị clear() reset (đúng ý production: giữ lịch sử "recently seen")
        // → dùng mốc thời gian LỚN, cô lập để test không phụ thuộc thứ tự chạy (mọi sighting cũ đều stale so base này).
        val t = 10_000_000L
        assertTrue(SourceArbiter.shouldFeed("vn.vietmap.app", Prefs.PREFER_GMAPS, t))         // GMaps chưa tươi → cho lên
        assertTrue(SourceArbiter.shouldFeed(GMAPS, Prefs.PREFER_GMAPS, t + 100))
        assertFalse(SourceArbiter.shouldFeed("vn.vietmap.app", Prefs.PREFER_GMAPS, t + 200))  // GMaps tươi → chặn
    }

    @Test fun `isFresh phản ánh độ tươi của nguồn đang giữ`() {
        assertFalse(SourceArbiter.isFresh(1000L))   // chưa có nguồn
        SourceArbiter.shouldFeed(GMAPS, Prefs.AUTO, 1000L)
        assertTrue(SourceArbiter.isFresh(2000L))
        assertFalse(SourceArbiter.isFresh(1000L + SourceArbiter.STALE_MS + 1))
    }
}

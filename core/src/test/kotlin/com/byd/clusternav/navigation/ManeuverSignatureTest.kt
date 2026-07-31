package com.byd.clusternav.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 226 dòng thuật toán chữ ký hướng rẽ, trước 2026-07-27 chưa từng có một test nào — vì nó nhận
 * `android.graphics.Bitmap` và gọi `android.util.Log`, hai thứ nó không thật sự cần.
 *
 * Test này chỉ khẳng định những gì đầu vào tổng hợp chứng minh được: biên, tính tất định, và seam log
 * không bắt buộc. Kiểm ĐÚNG tên hướng rẽ cần ảnh mũi thật lưu thành fixture — chưa có, và tôi không giả vờ
 * là đã phủ.
 */
class ManeuverSignatureTest {

    private fun frame(w: Int, h: Int, ink: (Int, Int) -> Boolean): PixelFrame =
        ArrayPixelFrame(w, h, IntArray(w * h) { i ->
            if (ink(i % w, i / w)) 0xFF000000.toInt() else 0x00FFFFFF
        })

    @Test
    fun `khung null hoac qua nho thi khong ket luan`() {
        assertNull(ManeuverSignature.classify(null))
        assertNull(ManeuverSignature.classify(frame(4, 4) { _, _ -> true }))
        assertNull(ManeuverSignature.classifyHal(frame(7, 7) { _, _ -> true }))
    }

    @Test
    fun `cung dau vao thi cung ket qua`() {
        val f = frame(24, 24) { x, y -> (x + y) % 3 == 0 }
        assertEquals(ManeuverSignature.classify(f), ManeuverSignature.classify(f))
    }

    @Test
    fun `khong can gan logger van chay duoc`() {
        // Seam mặc định không làm gì: :core không phụ thuộc logging của nền tảng nào.
        val previous = ManeuverSignature.note
        ManeuverSignature.note = {}
        try {
            ManeuverSignature.classify(frame(16, 16) { x, _ -> x < 8 })
        } finally {
            ManeuverSignature.note = previous
        }
    }

    /** [ManeuverSignature.classify] chỉ là [ManeuverSignature.classifyDetailed] bỏ tên — không được rẽ nhánh
     *  khác nhau, nếu không dòng vết chẩn đoán sẽ mô tả một quyết định khác với quyết định thật gửi ra cụm. */
    @Test
    fun `classifyDetailed va classify luon dong y ve ma icon`() {
        listOf(
            null,
            frame(4, 4) { _, _ -> true },                             // quá nhỏ
            frame(16, 16) { _, _ -> false },                          // trống trơn -> quá mờ
            frame(20, 20) { x, y -> x in 5..15 && y in 5..15 },
            frame(24, 24) { x, y -> (x + y) % 3 == 0 },
        ).forEach { f ->
            assertEquals(ManeuverSignature.classify(f), ManeuverSignature.classifyDetailed(f).amap)
        }
    }

    /** KHOÁ lỗi dữ liệu: trước 2026-07-30 call site chẩn đoán đọc [ManeuverSignature.lastName] SAU khi gọi
     *  classify — mà classify return sớm KHÔNG ghi field khi ảnh null/nhỏ, nên tên đọc được là tên CŨ còn
     *  sót của khung trước (và có thể là của luồng khác). Tên phải đi CÙNG mã trong một giá trị trả về. */
    @Test
    fun `khong co anh thi ten la NO_INPUT chu khong phai ten con sot cua khung truoc`() {
        ManeuverSignature.classify(frame(20, 20) { x, y -> x in 5..15 && y in 5..15 })  // để lại lastName
        val m = ManeuverSignature.classifyDetailed(null)
        assertNull(m.amap)
        assertEquals(ManeuverSignature.NO_INPUT, m.name)
    }

    @Test
    fun `logger duoc goi khi co gan`() {
        val lines = mutableListOf<String>()
        val previous = ManeuverSignature.note
        ManeuverSignature.note = { lines += it }
        try {
            ManeuverSignature.classify(frame(20, 20) { x, y -> x in 5..15 && y in 5..15 })
        } finally {
            ManeuverSignature.note = previous
        }
        assertTrue(lines.isNotEmpty(), "thuật toán phải nói được nó quyết định gì")
    }
}

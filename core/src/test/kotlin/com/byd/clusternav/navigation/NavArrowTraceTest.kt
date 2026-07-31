package com.byd.clusternav.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Khoá cho công cụ vết icon-rẽ dựng sau lỗi đo 2026-07-30 (rẽ trái, cụm hiện thẳng mãi, không lần ra
 * được lớp fallback nào sai vì trước đó chỉ log icon cuối cùng). */
class NavArrowTraceTest {

    private val header = NavArrowTrace.CSV_HEADER.split(",")

    /** Cột theo TÊN, không theo số: thêm/bớt cột không được làm test đi kiểm sai ô. */
    private fun col(row: String, name: String): String {
        val i = header.indexOf(name)
        assertNotEquals(-1, i, "CSV_HEADER thiếu cột '$name'")
        return row.split(",")[i]
    }

    private fun frame(width: Int, height: Int, fill: Int): PixelFrame =
        ArrayPixelFrame(width, height, IntArray(width * height) { fill })

    private fun entry(
        maneuverText: String = "Rẽ trái vào Nguyễn Văn Cừ",
        displayRoad: String = "Nguyễn Văn Cừ",
        rawRoad: String = "Rẽ trái vào Nguyễn Văn Cừ",
        distance: String = "250 m",
        smallIconAmap: Int = -1,
        sigName: String = "-",
        sigAmap: Int? = null,
        verbAmap: Int? = 2,
        heuristicAmap: Int? = null,
        finalIcon: Int = 9,
        arrowSource: ArrowSource = ArrowSource.FRAME,
        bitmapHash: Long? = 42L,
    ) = NavArrowTraceEntry(
        atEpochMillis = 1_785_000_000_000L, maneuverText = maneuverText, displayRoad = displayRoad,
        rawRoad = rawRoad, distance = distance, smallIconAmap = smallIconAmap, sigName = sigName,
        sigAmap = sigAmap, verbAmap = verbAmap, heuristicAmap = heuristicAmap, finalIcon = finalIcon,
        arrowSource = arrowSource, bitmapHash = bitmapHash,
    )

    @Test
    fun `header va so cot cua dong khop nhau`() {
        val row = NavArrowTrace.toCsvRow(entry()).split(",")
        assertEquals(header.size, row.size, "header='$header' row='$row'")
    }

    @Test
    fun `phay va xuong dong trong noti that khong lam lech cot`() {
        val row = NavArrowTrace.toCsvRow(entry(maneuverText = "Rẽ trái, vào\nNguyễn Văn Cừ"))
        assertEquals(header.size, row.split(",").size)
    }

    @Test
    fun `null o lop khong ket luan duoc ghi thanh -1, khong phai rong`() {
        val row = NavArrowTrace.toCsvRow(entry(sigAmap = null, verbAmap = null, heuristicAmap = null))
        assertEquals("-1", col(row, "sig_amap"))
        assertEquals("-1", col(row, "verb_amap"))
        assertEquals("-1", col(row, "heuristic_amap"))
    }

    @Test
    fun `khong co bitmap thi hash la hang so NO_BITMAP_HASH, khong phai -1 vo tinh trung voi null lop khac`() {
        val row = NavArrowTrace.toCsvRow(entry(bitmapHash = null))
        assertEquals(NavArrowTrace.NO_BITMAP_HASH.toString(), col(row, "bitmap_hash"))
    }

    /** Nguồn ảnh PHẢI ra file: `live` = chuỗi elvis thật KHÔNG thấy ảnh (s.arrow null từ 0.72), nên cột
     *  sig_amap/heuristic_amap hàng đó là "sẽ ra gì nếu được hỏi", không phải cái đã gửi cụm. Mất cột này
     *  là cả bảng bị đọc ngược ý. */
    @Test
    fun `nguon anh mui ten duoc ghi ro tung hang`() {
        assertEquals("frame", col(NavArrowTrace.toCsvRow(entry(arrowSource = ArrowSource.FRAME)), "arrow_src"))
        assertEquals("live", col(NavArrowTrace.toCsvRow(entry(arrowSource = ArrowSource.LIVE)), "arrow_src"))
        assertEquals("none", col(NavArrowTrace.toCsvRow(entry(arrowSource = ArrowSource.NONE)), "arrow_src"))
    }

    /** Lớp 3 đọc `maneuverText.ifBlank { rawRoad }` — road THÔ phải có trong file để dựng lại được input
     *  của nó khi maneuverText rỗng (nếu chỉ có display_road đã qua cleanRoadName thì suy ngược không ra). */
    @Test
    fun `road tho va road hien thi la hai cot rieng`() {
        val row = NavArrowTrace.toCsvRow(entry(displayRoad = "Nguyễn Văn Cừ", rawRoad = "Đường Nguyễn Văn Cừ"))
        assertEquals("Nguyễn Văn Cừ", col(row, "display_road"))
        assertEquals("Đường Nguyễn Văn Cừ", col(row, "raw_road"))
    }

    @Test
    fun `khung null thi khong co hash`() {
        assertNull(NavArrowTrace.bitmapHash(null))
    }

    @Test
    fun `cung noi dung pixel cho cung hash`() {
        val a = frame(8, 8, 0xFF112233.toInt())
        val b = frame(8, 8, 0xFF112233.toInt())
        assertEquals(NavArrowTrace.bitmapHash(a), NavArrowTrace.bitmapHash(b))
    }

    @Test
    fun `noi dung pixel khac nhau cho hash khac nhau`() {
        val a = frame(8, 8, 0xFF112233.toInt())
        val b = frame(8, 8, 0xFF445566.toInt())
        assertNotEquals(NavArrowTrace.bitmapHash(a), NavArrowTrace.bitmapHash(b))
    }

    /** KHOÁ lỗi thật: hash chỉ cộng dồn điểm ảnh thì 8×8 và 4×16 cùng nội dung ra hash Y HỆT -> hai tấm
     *  mũi tên khác hẳn nhau trùng tên file PNG, tấm sau bị bỏ ghi = mất đúng tấm cần xem. */
    @Test
    fun `khung khac kich co khong duoc trung hash du cung so pixel`() {
        val vuong = frame(8, 8, 0xFF112233.toInt())
        val det = frame(4, 16, 0xFF112233.toInt())
        assertNotEquals(NavArrowTrace.bitmapHash(vuong), NavArrowTrace.bitmapHash(det))
    }
}

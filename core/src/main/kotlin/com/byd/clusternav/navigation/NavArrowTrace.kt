package com.byd.clusternav.navigation

/**
 * Ảnh mũi tên mà dòng vết này đã chấm ĐẾN TỪ ĐÂU — quyết định cách ĐỌC hai cột [NavArrowTraceEntry.sigAmap]
 * và [NavArrowTraceEntry.heuristicAmap], nên PHẢI có trong file, không suy ra được từ chỗ khác:
 *
 *  - [FRAME]: ảnh đi CÙNG frame mà chuỗi elvis thật đã chấm → 2 cột đó ĐÚNG là verdict lớp 2/4 THẬT.
 *  - [LIVE] : frame KHÔNG mang ảnh (nên chuỗi thật đã bỏ qua lớp 2/4 vì null) — vết mượn ảnh MỚI NHẤT từ
 *             notification chỉ để trả lời "lớp 2/4 SẼ ra gì NẾU được đưa ảnh". KHÔNG phải cái đã gửi cụm.
 *  - [NONE] : không có ảnh ở đâu cả → lớp 2/4 vô nghĩa (ghi -1).
 */
enum class ArrowSource { NONE, FRAME, LIVE }

/**
 * Một dòng vết cho MỖI lần cụm nhận một icon rẽ — ghi lại verdict của TỪNG lớp phân loại độc lập
 * (không chỉ lớp thắng cuộc), để so sánh sau khi lái xong xem lớp nào đúng/sai cho từng ngã rẽ thật.
 *
 * Sinh ra sau lỗi đo 2026-07-30: "rẽ trái mà cụm hiện thẳng mãi" không lần ra được lớp nào trong 4 lớp
 * fallback (`AmapFrameBuilder.kt:41-50`) đã quyết định sai, vì trước đây chỉ log ĐÚNG icon cuối cùng
 * (`ClusterBroadcaster.sendFrame`) — không phải NGÕ RẼ mà mỗi lớp muốn đi.
 */
data class NavArrowTraceEntry(
    val atEpochMillis: Long,
    /** Lệnh rẽ THÔ từ notification GMaps ("Rẽ trái vào Nguyễn Văn Cừ") — nguồn cho lớp 3 (động từ chữ). */
    val maneuverText: String,
    /** Tên đường đang hiển thị trên cụm (sau [NavFormat.cleanRoadName]) — KHÁC road thô nếu có cắt chữ. */
    val displayRoad: String,
    /** Road THÔ như chuỗi elvis thật thấy — lớp 3 đọc `maneuverText.ifBlank { rawRoad }`, KHÔNG phải bản đã dọn. */
    val rawRoad: String,
    val distance: String,
    /** Lớp 1: tên small-icon (mã AMAP hoặc -1 nếu không đọc được — [smallIconAmap] chính là s.maneuverIcon). */
    val smallIconAmap: Int,
    /** Lớp 2: tên maneuver khớp gần nhất theo chữ ký tri giác, hoặc "-"/"(mờ)"/"(không khớp)"/"(không ảnh)". */
    val sigName: String,
    val sigAmap: Int?,
    /** Lớp 3: động từ chữ ("rẽ trái"...) trong maneuverText. */
    val verbAmap: Int?,
    /** Lớp 4: trọng-tâm pixel (heuristic cũ). */
    val heuristicAmap: Int?,
    /** Icon THẬT SỰ được gửi ra cụm (kết quả của toàn bộ chuỗi elvis, sau guard "hình ghim"). */
    val finalIcon: Int,
    /** Ảnh mũi tên đã chấm đến từ đâu — xem [ArrowSource]; thiếu cột này là đọc sai cả bảng. */
    val arrowSource: ArrowSource,
    /** Chữ ký nội dung ảnh mũi tên (null nếu không có bitmap) — dùng để dedup file PNG, không phải để so khớp. */
    val bitmapHash: Long?,
)

object NavArrowTrace {
    /** Cột CSV, một chỗ duy nhất để cột header và cột ghi không bao giờ lệch nhau. */
    const val CSV_HEADER =
        "t_ms,maneuver,display_road,raw_road,distance,small_amap,sig_name,sig_amap,verb_amap," +
            "heuristic_amap,final_icon,arrow_src,bitmap_hash"

    /** Không cho phẩy/xuống dòng của nội dung noti thật làm lệch cột hay bịa thêm dòng. */
    private fun csvSafe(s: String): String = s.replace(',', ' ').replace('\n', ' ').replace('\r', ' ')

    fun toCsvRow(e: NavArrowTraceEntry): String = buildString {
        append(e.atEpochMillis).append(',')
        append(csvSafe(e.maneuverText)).append(',')
        append(csvSafe(e.displayRoad)).append(',')
        append(csvSafe(e.rawRoad)).append(',')
        append(csvSafe(e.distance)).append(',')
        append(e.smallIconAmap).append(',')
        append(csvSafe(e.sigName)).append(',')
        append(e.sigAmap ?: -1).append(',')
        append(e.verbAmap ?: -1).append(',')
        append(e.heuristicAmap ?: -1).append(',')
        append(e.finalIcon).append(',')
        append(e.arrowSource.name.lowercase()).append(',')
        append(e.bitmapHash ?: NO_BITMAP_HASH)
    }

    const val NO_BITMAP_HASH = -1L

    /**
     * Chữ ký nội dung rẻ cho khung ảnh — CHỈ để nhận ra "khung này với khung trước có phải cùng một tấm
     * mũi tên hay không" (dùng dedup file PNG khi nhịp tim gửi lại y nguyên NavState mỗi ~1s), KHÔNG phải
     * một thuật toán khớp mẫu. Hai ảnh khác nhau trùng hash là chấp nhận được (chỉ tốn thêm 1 file PNG).
     */
    fun bitmapHash(frame: PixelFrame?): Long? {
        val f = frame ?: return null
        val px = f.argb() ?: return null
        var h = 1_125_899_906_842_597L
        // Trộn KÍCH CỠ vào TRƯỚC điểm ảnh: chỉ cộng dồn pixel thì 8×8 và 4×16 cùng nội dung ra hash Y HỆT
        // -> hai tấm mũi tên KHÁC nhau dùng chung một tên file PNG, tấm sau bị bỏ ghi = mất bằng chứng.
        h = h * 31 + f.width
        h = h * 31 + f.height
        h = h * 31 + px.size
        for (v in px) h = h * 31 + v
        return h
    }
}

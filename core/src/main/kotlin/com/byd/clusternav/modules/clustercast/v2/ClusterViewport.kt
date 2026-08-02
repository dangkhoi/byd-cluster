package com.byd.clusternav.modules.clustercast.v2

/**
 * Viewport thật của cụm — dải NHÌN THẤY trên màn hình vật lý.
 *
 * Display ảo có thể lớn hơn vùng hiển thị thật (gauge/info xe che phần trên/dưới).
 * Mỗi loại xe có viewport khác nhau — user có thể chỉnh qua settings.
 *
 * [width] × [height] = kích thước vùng NHÌN THẤY (pixel).
 * [top] = offset từ đỉnh display ảo xuống đỉnh vùng nhìn thấy.
 * [left] = offset từ trái display ảo sang trái vùng nhìn thấy (thường = 0).
 *
 * Ví dụ DiLink3: display 1920×720, overscan (0,90,0,90) → viewport 1920×540 tại top=90.
 */
data class ClusterViewport(
    val width: Int,
    val height: Int,
    val top: Int = 0,
    val left: Int = 0,
) {
    init {
        require(width > 0) { "viewport width must be positive" }
        require(height > 0) { "viewport height must be positive" }
        require(top >= 0) { "viewport top offset must be non-negative" }
        require(left >= 0) { "viewport left offset must be non-negative" }
    }

    val right: Int get() = left + width
    val bottom: Int get() = top + height

    /** Rect dùng cho `am task resize` — app chiếm TOÀN BỘ viewport. */
    val fullRect: CastRect get() = CastRect(left, top, right, bottom)

    /** Rect nửa TRÁI theo tỉ lệ cho trước. */
    fun leftRect(leftPercent: Int): CastRect {
        val splitX = left + width * leftPercent / 100
        return CastRect(left, top, splitX, bottom)
    }

    /** Rect nửa PHẢI theo tỉ lệ cho trước. */
    fun rightRect(leftPercent: Int): CastRect {
        val splitX = left + width * leftPercent / 100
        return CastRect(splitX, top, right, bottom)
    }

    companion object {
        /**
         * Default cho BYD DiLink3 (Atto 3, Dolphin, Seal, ...).
         * Display 1920×720, overscan (0,90,0,90) → viewport 1920×540 tại top=90.
         * Đo từ dumpsys display + task bounds thật 2026-07-31.
         */
        val DILINK3_DEFAULT = ClusterViewport(width = 1920, height = 540, top = 90)

        /**
         * Fallback khi chưa biết xe loại gì — dùng toàn bộ display (không cắt).
         * An toàn: app có thể bị che 1 phần nhưng không bao giờ bị đặt ngoài màn hình.
         */
        val FULL_DISPLAY = ClusterViewport(width = 1920, height = 720, top = 0)
    }
}

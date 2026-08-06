package com.byd.clusternav.navigation

/**
 * Maneuver TRUNG LẬP — quyết định hướng rẽ DUY NHẤT của một khung dẫn đường, độc lập với NGUỒN
 * (Google Maps / Waze / VietMap) và với ĐẦU RA (làn cụm / HUD).
 *
 * VÌ SAO tồn tại: kiến trúc "một đầu vào → nhiều đầu ra độc lập" chỉ chống lệch nếu đầu vào mang một
 * QUYẾT ĐỊNH đã chốt, không phải dữ liệu thô để mỗi đầu ra tự diễn giải lại. Trước đây khung mang mã
 * AMAP NEW_ICON (ngôn ngữ RIÊNG của làn cụm) nên làn cụm đọc thẳng đúng, còn HUD phải suy lại từ chữ →
 * mọi cua rớt về "đi thẳng". Giờ khung mang [Maneuver]; mỗi đầu ra là một ENCODER thuần:
 *   - [toAmapIcon] cho làn cụm (AmapService remap CAN qua TurnIdMapToCAN).
 *   - [toHudIcon] cho HUD (ghi thẳng mã CAN INSTRUMENT_GUIDE_INFO_SIMPLE_SET).
 * Hai đầu ra là HÀM của cùng một Maneuver → KHÔNG thể lệch hướng rẽ; không đầu ra nào "quyết định lại".
 *
 * Độ hạt trùng với từ vựng AMAP mà pipeline phân loại thực sự phát ra (không tách U-turn trái/phải,
 * gộp fork/merge vào slight) nên [fromAmapIcon] khứ hồi CHÍNH XÁC — làn cụm được bảo toàn nguyên trạng.
 */
enum class Maneuver {
    STRAIGHT,
    TURN_LEFT,
    TURN_RIGHT,
    SLIGHT_LEFT,
    SLIGHT_RIGHT,
    SHARP_LEFT,
    SHARP_RIGHT,
    UTURN,
    ROUNDABOUT,
    CONTINUE,
    DESTINATION;

    /** -> mã AMAP NEW_ICON cho làn cụm (0..28; AmapService tự remap CAN, KHÔNG remap ở đây). */
    fun toAmapIcon(): Int = when (this) {
        TURN_LEFT -> 2
        TURN_RIGHT -> 3
        SLIGHT_LEFT -> 4
        SLIGHT_RIGHT -> 5
        SHARP_LEFT -> 6
        SHARP_RIGHT -> 7
        UTURN -> 8
        STRAIGHT -> 9
        ROUNDABOUT -> 11
        DESTINATION -> 15
        CONTINUE -> 20
    }

    /** -> mã icon CAN HUD (INSTRUMENT_GUIDE_INFO_SIMPLE_SET, enum HudController 1..49). */
    fun toHudIcon(): Int = when (this) {
        TURN_LEFT -> 1
        TURN_RIGHT -> 2
        SLIGHT_LEFT -> 3
        SLIGHT_RIGHT -> 5
        SHARP_LEFT -> 7
        SHARP_RIGHT -> 8
        UTURN -> 9          // AMAP gộp T/P → mặc định trái (9), chuẩn RHT (VN)
        STRAIGHT -> 11
        ROUNDABOUT -> 15    // glyph vòng xuyến chung
        CONTINUE -> 11      // tiếp tục ≈ đi thẳng
        DESTINATION -> 48
    }

    companion object {
        /**
         * Cầu nối AMAP NEW_ICON -> [Maneuver] cho các classifier/nguồn còn phát mã AMAP
         * (ManeuverSignature/ArrowClassifier/IconResource/maneuverVerbIcon). null nếu là 0/none, -1
         * hoặc mã ngoài từ vựng (caller coi như "chưa có maneuver"). Khứ hồi:
         * `fromAmapIcon(x).toAmapIcon() == x` với mọi x pipeline phát ra {2,3,4,5,6,7,8,9,11,15,20}.
         */
        fun fromAmapIcon(amap: Int): Maneuver? = when (amap) {
            2 -> TURN_LEFT
            3 -> TURN_RIGHT
            4 -> SLIGHT_LEFT
            5 -> SLIGHT_RIGHT
            6 -> SHARP_LEFT
            7 -> SHARP_RIGHT
            8 -> UTURN
            9 -> STRAIGHT
            11 -> ROUNDABOUT
            15 -> DESTINATION
            20 -> CONTINUE
            else -> null
        }
    }
}

package com.byd.clusternav.voicekey

/**
 * LOGIC THUẦN (không Android): một sự kiện phím vật lý có (a) kích hoạt mở app đích không và (b) có "nuốt"
 * (consume) sự kiện đó không.
 *
 * Rework 1.19 (owner 2026-08-14): **BỎ khái niệm cử chỉ (Nhấn/Nhấn-giữ) + mốc thời-gian-giữ.** Trên xe này
 * nút phát KEYCODE KHÁC NHAU cho nhấn-ngắn vs nhấn-giữ, nên phân biệt bằng thời gian giữ là thừa và gây lỗi
 * (giữ > 500ms từng làm PRESS từ chối bắn → phím lọt → hệ thống mở nhầm trợ lý Gemini). Giờ đơn giản:
 * đúng keycode đã cấu hình → **BẮN 1 lần** (trên DOWN đầu của mỗi lần nhấn) + **NUỐT trọn** phím đó
 * (DOWN/UP/repeat); keycode khác hoặc tính năng tắt → **pass-through hoàn toàn** (giữ chức năng gốc).
 *
 * CÓ TRẠNG THÁI (mỗi lần nhấn chỉ bắn 1 lần, khoá theo downTime). Gọi tuần tự từ 1 luồng (onKeyEvent main).
 */
enum class VoiceKeyAction { DOWN, UP, OTHER }

/** Cấu hình tối thiểu: bật/tắt + keycode cần bắt. Đích (app/sentinel) do tầng app quyết định, KHÔNG ở :core. */
data class VoiceKeyConfig(val enabled: Boolean, val keyCode: Int)

/**
 * @property fire true → tầng app phóng intent mở app đích.
 * @property consume true → [android.accessibilityservice.AccessibilityService.onKeyEvent] trả true (chặn hệ
 *   thống xử lý phím). Chỉ true cho đúng keycode đã cấu hình.
 */
data class VoiceKeyDecision(val fire: Boolean, val consume: Boolean) {
    companion object { val IGNORE = VoiceKeyDecision(fire = false, consume = false) }
}

class VoiceKeyMatcher {

    // downTime của lần nhấn đã bắn — chống bắn lặp (DOWN-repeat rồi UP trong cùng một lần nhấn).
    private var firedDownTime = Long.MIN_VALUE

    fun onKey(cfg: VoiceKeyConfig, action: VoiceKeyAction, keyCode: Int, downTimeMs: Long): VoiceKeyDecision {
        if (!cfg.enabled || keyCode != cfg.keyCode) return VoiceKeyDecision.IGNORE
        return when (action) {
            VoiceKeyAction.DOWN -> {
                val fire = firedDownTime != downTimeMs      // bắn 1 lần cho mỗi lần nhấn (theo downTime)
                if (fire) firedDownTime = downTimeMs
                VoiceKeyDecision(fire = fire, consume = true)
            }
            // Nuốt UP/OTHER của đúng keycode để phím không lọt xuống hệ thống (khỏi mở nhầm trợ lý mặc định).
            VoiceKeyAction.UP, VoiceKeyAction.OTHER -> VoiceKeyDecision(fire = false, consume = true)
        }
    }

    /** Reset trạng thái (gọi khi service (re)connect) để một lần nhấn dở dang không dính sang phiên mới. */
    fun reset() { firedDownTime = Long.MIN_VALUE }
}

package com.byd.clusternav.voicekey

/**
 * LOGIC THUẦN (không Android) quyết định một sự kiện phím vật lý có (a) kích hoạt trợ lý giọng nói không và
 * (b) có "nuốt" (consume) sự kiện đó không.
 *
 * Nguyên tắc (owner 2026-08-13): **KHÔNG thay chức năng gốc của nút**. Chỉ can thiệp đúng tổ hợp
 * (keycode + cử chỉ) người dùng đã cấu hình; mọi phím/khác đều pass-through (consume=false). Khi tính năng
 * TẮT → luôn IGNORE (không đụng gì).
 *
 * Cử chỉ:
 *  - [VoiceKeyGesture.PRESS] (nhấn): bắn khi NHẢ phím trong thời gian ngắn (< [longPressMs]). Nuốt cả DOWN
 *    lẫn UP của đúng keycode để tap không lọt xuống chức năng gốc (người dùng đã chủ động chọn nút này).
 *  - [VoiceKeyGesture.HOLD] (nhấn giữ): bắn khi giữ >= [longPressMs]. KHÔNG nuốt DOWN (tap ngắn vẫn chạy
 *    chức năng gốc), chỉ nuốt UP khi đã bắn. Tap ngắn → pass-through hoàn toàn.
 *
 * Matcher CÓ TRẠNG THÁI (mỗi lần nhấn chỉ bắn 1 lần, khoá theo downTime). Gọi tuần tự từ một luồng
 * (AccessibilityService.onKeyEvent chạy trên main thread).
 */
enum class VoiceKeyGesture { PRESS, HOLD }

enum class VoiceKeyAction { DOWN, UP, OTHER }

/** Đích kích hoạt. Tầng app (AssistantLauncher) ánh xạ sang intent thật. */
enum class VoiceKeyTarget { ASSIST, BYD_VOICE, RECOGNIZER, KIKI }

data class VoiceKeyConfig(
    val enabled: Boolean,
    val keyCode: Int,
    val gesture: VoiceKeyGesture,
    val target: VoiceKeyTarget,
)

/**
 * @property fire true → tầng app phóng intent trợ lý.
 * @property consume true → [android.accessibilityservice.AccessibilityService.onKeyEvent] trả true (chặn
 *   không cho hệ thống xử lý phím). Chỉ true cho đúng tổ hợp đã cấu hình.
 */
data class VoiceKeyDecision(val fire: Boolean, val consume: Boolean) {
    companion object { val IGNORE = VoiceKeyDecision(fire = false, consume = false) }
}

class VoiceKeyMatcher(private val longPressMs: Long = DEFAULT_LONG_PRESS_MS) {

    // downTime của lần nhấn đã bắn — chống bắn lặp (DOWN-repeat rồi UP trong cùng một lần nhấn).
    private var firedDownTime = Long.MIN_VALUE

    fun onKey(
        cfg: VoiceKeyConfig,
        action: VoiceKeyAction,
        keyCode: Int,
        downTimeMs: Long,
        eventTimeMs: Long,
        repeatCount: Int,
    ): VoiceKeyDecision {
        if (!cfg.enabled || keyCode != cfg.keyCode) return VoiceKeyDecision.IGNORE
        val held = (eventTimeMs - downTimeMs).coerceAtLeast(0)
        return when (cfg.gesture) {
            VoiceKeyGesture.PRESS -> when (action) {
                VoiceKeyAction.DOWN -> VoiceKeyDecision(fire = false, consume = true)
                VoiceKeyAction.UP -> {
                    val fire = held < longPressMs && firedDownTime != downTimeMs
                    if (fire) firedDownTime = downTimeMs
                    VoiceKeyDecision(fire = fire, consume = true)
                }
                VoiceKeyAction.OTHER -> VoiceKeyDecision(fire = false, consume = true)
            }
            VoiceKeyGesture.HOLD -> when (action) {
                VoiceKeyAction.DOWN -> {
                    // Bắn NGAY khi vượt ngưỡng giữ (nếu phím có repeat); đã bắn → nuốt repeat còn lại;
                    // chưa đủ lâu → IGNORE để chức năng gốc vẫn thấy phím.
                    val fire = repeatCount > 0 && held >= longPressMs && firedDownTime != downTimeMs
                    when {
                        fire -> { firedDownTime = downTimeMs; VoiceKeyDecision(fire = true, consume = true) }
                        firedDownTime == downTimeMs -> VoiceKeyDecision(fire = false, consume = true)
                        else -> VoiceKeyDecision.IGNORE
                    }
                }
                VoiceKeyAction.UP -> {
                    val alreadyFired = firedDownTime == downTimeMs
                    val fireNow = !alreadyFired && held >= longPressMs
                    if (fireNow) firedDownTime = downTimeMs
                    // Nuốt UP nếu đã/đang bắn (giữ lâu); tap ngắn (chưa bắn) → pass-through cho chức năng gốc.
                    VoiceKeyDecision(fire = fireNow, consume = alreadyFired || fireNow)
                }
                VoiceKeyAction.OTHER -> VoiceKeyDecision.IGNORE
            }
        }
    }

    /** Reset trạng thái (gọi khi service (re)connect để một lần nhấn dở dang không dính sang phiên mới). */
    fun reset() { firedDownTime = Long.MIN_VALUE }

    companion object {
        /** Ngưỡng phân biệt nhấn / nhấn-giữ. Bằng long-press mặc định của Android (~500ms). */
        const val DEFAULT_LONG_PRESS_MS = 500L
    }
}

package com.byd.clusternav.navigation

/**
 * Chính sách KEEP-ALIVE THUẦN (không Android) cho đường ghi HAL cụm/HUD
 * (`INSTRUMENT_GUIDE_INFO_SIMPLE_SET` qua [com.byd.clusternav.NavigationHudOwner]).
 *
 * VẤN ĐỀ (J1, đo trên xe 1.14): HUD kính lái + nav "Giữa+ETA" của OEM TỰ TẮT nếu quá lâu không nhận frame
 * mới — trên đoạn dài không rẽ, GMaps giãn notification → `NavRepository.ingest` → `push()` không được gọi,
 * và frame trùng còn bị dedup nuốt → OEM chớp/mất ~1s rồi hiện lại khi có noti kế. Làn cụm KHÔNG bị vì có
 * nhịp tim 400ms riêng (AmapEmissionArbiter); đường HAL này thì CHƯA có nhịp tim nào.
 *
 * Policy này chỉ QUYẾT ĐỊNH "khi nào cần re-assert" — nó không tự đọc giờ và không tự ghi HAL (owner làm).
 * Thuần → JVM-testable. Clock (monotonic, vd `SystemClock.elapsedRealtime`) do caller truyền vào.
 *
 * Ngữ nghĩa: sau mỗi lần GHI frame thành công gọi [onFrameWritten]; khi CLEAR (hết dẫn / mode OFF / stop)
 * gọi [onCleared]. [shouldReassert] = ĐANG hiện 1 frame VÀ đã ≥ [intervalMs] kể từ lần ghi cuối. Nhờ vậy
 * push bị-dedup-nuốt KHÔNG reset đồng hồ → màn stale luôn được làm tươi lại trong vòng một interval.
 */
class HudKeepAlivePolicy(private val intervalMs: Long = DEFAULT_INTERVAL_MS) {
    init { require(intervalMs > 0L) { "intervalMs must be > 0 (was $intervalMs)" } }

    private val lock = Any()
    private var lastWriteAtMs = 0L
    private var hasFrame = false

    /** Gọi SAU mỗi lần ghi thành công 1 frame nav lên HAL (real push hoặc keep-alive re-assert). */
    fun onFrameWritten(nowMs: Long) = synchronized(lock) {
        lastWriteAtMs = nowMs
        hasFrame = true
    }

    /** Gọi khi frame bị CLEAR — không còn gì để giữ sống. */
    fun onCleared() = synchronized(lock) {
        hasFrame = false
        lastWriteAtMs = 0L
    }

    /** true khi đang hiện 1 frame và đã ≥ [intervalMs] kể từ lần ghi cuối → owner nên re-assert (bypass dedup). */
    fun shouldReassert(nowMs: Long): Boolean = synchronized(lock) {
        hasFrame && nowMs - lastWriteAtMs >= intervalMs
    }

    /** Chu kỳ tick khuyến nghị cho scheduler của owner (ms). */
    fun intervalMs(): Long = intervalMs

    companion object {
        /** 400ms = khớp nhịp tim làn-cụm đã proven trên xe, thoải mái dưới ngưỡng blank ~1s quan sát được. */
        const val DEFAULT_INTERVAL_MS = 400L
    }
}

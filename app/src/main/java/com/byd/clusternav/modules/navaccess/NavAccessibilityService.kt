package com.byd.clusternav.modules.navaccess

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.byd.clusternav.NavParse
import com.byd.clusternav.Prefs
import com.byd.clusternav.TurnDistanceInterpolator

/**
 * BOOSTER TẦNG 1 — đọc UI dẫn đường GMaps ĐANG HIỆN trên màn để lấy cự ly tới rẽ CHÍNH XÁC, TƯƠI hơn noti
 * (noti bước ~10m, trễ 1-2s), rồi TINH CHỈNH interpolator. GMaps KHÔNG có view-id sạch (xem OpenBYD
 * handleGoogleMapsEvent) -> phải dò theo MẪU CHỮ (cự ly m/km) + TOẠ ĐỘ (thẻ rẽ ở NỬA TRÊN màn, khác
 * thanh đáy = quãng tới đích). Chỉ là booster: KHÔNG tự khởi tạo nav (refine bỏ qua khi chưa có anchor noti),
 * GMaps bị YouTube che -> không có event -> tự câm, nội suy theo tốc độ gánh tiếp. KHÔNG root, chỉ xin quyền hỗ trợ.
 *
 * KEEP/KILL: xoá module = xoá modules/navaccess/ + dòng Registry + <service> trong Manifest + res/xml/nav_accessibility_config.xml.
 */
class NavAccessibilityService : AccessibilityService() {

    private var lastProcessed = 0L
    private val maps = setOf("com.google.android.apps.maps", "app.revanced.android.apps.maps")
    // Mẫu cự ly tới rẽ: "250 m", "1.2 km", "0,4 km". (ft/mi để nhận diện thẻ rẽ vùng imperial, parse vẫn metric.)
    private val dist = Regex("""\b\d+([.,]\d+)?\s?(km|m|ft|mi)\b""", RegexOption.IGNORE_CASE)
    private val timeTok = Regex("""\d+\s?(phút|min|giờ|h\b|hr)|\b\d{1,2}:\d{2}\b""", RegexOption.IGNORE_CASE)

    override fun onServiceConnected() {
        NavAccessibilitySource.connected = true
        Log.i(TAG, "accessibility booster connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        NavAccessibilitySource.connected = false
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in maps) return
        val now = SystemClock.elapsedRealtime()
        NavAccessibilitySource.lastEventAt = now
        if (now - lastProcessed < THROTTLE_MS) return         // GMaps bắn event dày -> tiết lưu 200ms
        lastProcessed = now
        if (!Prefs.enabled(applicationContext) || !Prefs.accBooster(applicationContext)) return

        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        runCatching { scan(root, now) }.onFailure { Log.e(TAG, "scan failed", it) }
        runCatching { root.recycle() }
    }

    /** Gom mọi node có text + toạ độ, phân vùng trên/đáy, rút cự ly tới rẽ + đường + info đáy. */
    private fun scan(root: AccessibilityNodeInfo, now: Long) {
        val items = ArrayList<Triple<String, Int, Int>>(64)   // (text, top, left)
        val screen = Rect(); root.getBoundsInScreen(screen)
        val h = if (screen.height() > 0) screen.height() else 1080
        collect(root, items, 0)
        if (items.isEmpty()) return

        val topBand = (h * 0.55).toInt()                      // thẻ rẽ nằm nửa trên; thanh đích nằm đáy
        // Cự ly tới rẽ = token cự ly Ở NỬA TRÊN, cao nhất (top nhỏ nhất).
        val turn = items.filter { it.second < topBand && dist.containsMatchIn(it.first) && !timeTok.containsMatchIn(it.first) }
            .minByOrNull { it.second }
        val meters = turn?.let { NavParse.parseMeters(it.first) } ?: -1

        // Đường/lệnh kế = chuỗi DÀI nhất ở nửa trên, không phải cự ly/giờ (vd "Nguyễn Huệ", "Rẽ phải vào ...").
        val road = items.filter { it.second < topBand && !dist.containsMatchIn(it.first) && !timeTok.containsMatchIn(it.first) && it.first.length >= 3 }
            .maxByOrNull { it.first.length }?.first.orEmpty()

        // Info đáy (giờ tới · còn lại · phút) — debug, chưa đẩy cụm (cụm không có slot ETA no-root).
        val bottom = items.filter { it.second > h * 0.78 && (dist.containsMatchIn(it.first) || timeTok.containsMatchIn(it.first)) }
            .sortedBy { it.third }.joinToString(" · ") { it.first }

        if (road.isNotEmpty()) NavAccessibilitySource.road = road
        if (bottom.isNotEmpty()) NavAccessibilitySource.bottomInfo = bottom

        if (meters in 0..50000) {
            NavAccessibilitySource.turnMeters = meters
            NavAccessibilitySource.lastReadAt = now
            // TINH CHỈNH: ghi đè anchor bằng cự ly đọc trên màn (chỉ khi noti đã mở nav -> refine tự bỏ qua nếu chưa).
            TurnDistanceInterpolator.refine(meters, now)
            NavAccessibilitySource.refines++
        }
    }

    private fun collect(node: AccessibilityNodeInfo?, out: ArrayList<Triple<String, Int, Int>>, depth: Int) {
        node ?: return
        if (out.size >= MAX_NODES || depth > MAX_DEPTH) return
        val t = node.text?.toString()?.trim()
        if (!t.isNullOrEmpty() && t.length <= 80) {
            val r = Rect(); node.getBoundsInScreen(r)
            out.add(Triple(t, r.top, r.left))
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            collect(c, out, depth + 1)
            runCatching { c.recycle() }
        }
    }

    companion object {
        private const val TAG = "NavAccess"
        private const val THROTTLE_MS = 200L
        private const val MAX_NODES = 250
        private const val MAX_DEPTH = 40
    }
}

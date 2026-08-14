package com.byd.clusternav.modules.navaccess

import com.byd.clusternav.navigation.ScreenTextItem
import com.byd.clusternav.navigation.NavScreenReading
import com.byd.clusternav.navigation.NavScreenScan
import com.byd.clusternav.navigation.NavParse
import com.byd.clusternav.navigation.TurnDistanceInterpolator
import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.byd.clusternav.Prefs
import com.byd.clusternav.modules.voicekey.AssistantLauncher
import com.byd.clusternav.voicekey.VoiceKeyAction
import com.byd.clusternav.voicekey.VoiceKeyConfig
import com.byd.clusternav.voicekey.VoiceKeyGesture
import com.byd.clusternav.voicekey.VoiceKeyMatcher
import com.byd.clusternav.voicekey.VoiceKeyTarget

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

    // T3: nút vật lý → trợ lý giọng nói. Matcher thuần ở :core; service chỉ map KeyEvent + phóng intent.
    private val voiceKeyMatcher = VoiceKeyMatcher()

    override fun onServiceConnected() {
        NavAccessibilitySource.connected = true
        voiceKeyMatcher.reset()
        Log.i(TAG, "accessibility booster connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        NavAccessibilitySource.connected = false
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    /**
     * T3 — nút vật lý → trợ lý giọng nói. Chỉ chạy khi service được cấp quyền hỗ trợ + config
     * `canRequestFilterKeyEvents` + flag `flagRequestFilterKeyEvents` (xem nav_accessibility_config.xml).
     *
     * KHÔNG thay chức năng gốc: chỉ trả true (nuốt phím) cho đúng tổ hợp (keycode + cử chỉ) người dùng cấu
     * hình — quyết định ở [VoiceKeyMatcher] (:core). Phím/khác → super (pass-through).
     * "Học phím": nếu bật, ghi lại keycode nút vừa bấm (trên DOWN) rồi tự tắt cờ.
     */
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        event ?: return super.onKeyEvent(event)
        val app = applicationContext

        if (Prefs.voiceKeyLearn(app)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                Prefs.setVoiceKeyCode(app, event.keyCode)
                Prefs.setVoiceKeyLearn(app, false)
                val name = KeyEvent.keyCodeToString(event.keyCode)
                Log.i(TAG, "learned voice keycode=${event.keyCode} ($name)")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching {
                        android.widget.Toast.makeText(app, "Đã gán nút: $name (${event.keyCode})", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            return true   // nuốt trong lúc học để không kích hoạt gì khác
        }

        if (!Prefs.voiceKeyEnabled(app)) return super.onKeyEvent(event)

        val cfg = VoiceKeyConfig(
            enabled = true,
            keyCode = Prefs.voiceKeyCode(app),
            gesture = if (Prefs.voiceKeyGesture(app) == 1) VoiceKeyGesture.HOLD else VoiceKeyGesture.PRESS,
            target = when (Prefs.voiceKeyTarget(app)) {
                1 -> VoiceKeyTarget.BYD_VOICE
                2 -> VoiceKeyTarget.RECOGNIZER
                3 -> VoiceKeyTarget.ASSIST
                else -> VoiceKeyTarget.KIKI   // 0 (mặc định) = Kiki (Zalo) — owner map nút mic 328 → Kiki
            },
        )
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> VoiceKeyAction.DOWN
            KeyEvent.ACTION_UP -> VoiceKeyAction.UP
            else -> VoiceKeyAction.OTHER
        }
        val decision = voiceKeyMatcher.onKey(
            cfg, action, event.keyCode, event.downTime, event.eventTime, event.repeatCount,
        )
        if (decision.fire) {
            Log.i(TAG, "voice-key fire → target=${cfg.target} key=${event.keyCode}")
            runCatching { AssistantLauncher.launch(app, cfg.target) }
                .onFailure { Log.e(TAG, "assistant launch failed", it) }
        }
        return if (decision.consume) true else super.onKeyEvent(event)
    }

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

    /**
     * Gom mọi node có text + toạ độ rồi giao phần QUYẾT ĐỊNH cho [NavScreenScan] trong `:core`.
     *
     * Trước 2026-07-27 heuristic chia dải trên/đáy, chọn token cự ly và chọn tên đường nằm ngay tại đây,
     * nên đúng đoạn quyết định con số tài xế thấy trên cụm lại không có bài kiểm nào. Ở đây giờ chỉ còn
     * việc đi cây `AccessibilityNodeInfo` và ghi kết quả — hai thứ thật sự cần Android.
     */
    private fun scan(root: AccessibilityNodeInfo, now: Long) {
        val items = ArrayList<Triple<String, Int, Int>>(64)
        val screen = Rect(); root.getBoundsInScreen(screen)
        collect(root, items, 0)
        if (items.isEmpty()) return

        val reading = NavScreenScan.scan(
            items.map { ScreenTextItem(it.first, it.second, it.third) },
            screen.height(),
        )

        if (reading.road.isNotEmpty()) NavAccessibilitySource.road = reading.road
        if (reading.bottomInfo.isNotEmpty()) NavAccessibilitySource.bottomInfo = reading.bottomInfo

        if (reading.turnMeters != NavScreenReading.UNKNOWN_METERS) {
            NavAccessibilitySource.turnMeters = reading.turnMeters
            NavAccessibilitySource.lastReadAt = now
            // Ghi đè anchor bằng cự ly đọc trên màn; refine tự bỏ qua nếu noti chưa mở nav.
            TurnDistanceInterpolator.refine(reading.turnMeters, now)
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

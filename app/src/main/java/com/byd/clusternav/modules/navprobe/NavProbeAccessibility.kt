package com.byd.clusternav.modules.navprobe

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * KÊNH 3 của máy dò — đọc CHỮ ĐANG HIỆN của Vietmap / Waze / CarPlay / Android Auto.
 *
 * Đây là ngả duy nhất còn cửa sau chuyến 22/07 (notification rỗng, HAL im). Và nó KHÔNG phải giả thuyết mới:
 * `modules/navaccess/NavAccessibilityService` đã đọc được UI Google Maps trên chính chiếc xe này.
 *
 * TÁCH RIÊNG khỏi service đó, có chủ đích:
 *   • `packageNames` của service kia chỉ khai 2 gói GMaps. Nới rộng nó = mọi event của Vietmap (vẽ rất dày)
 *     đổ vào hot-path của booster đang chạy tốt, và dùng chung biến throttle thì Vietmap nuốt luôn lượt của GMaps.
 *   • Service này bật `flagReportViewIds` (service kia cố ý bỏ vì không dùng). Có viewId thì bộ rút dữ liệu
 *     sau này bám được vào id thay vì dò toạ độ. Cờ này chỉ thêm một chuỗi vào NodeInfo.
 *   • KHÔNG bật `flagRetrieveInteractiveWindows` — đó mới là cờ đắt (system_server theo dõi mọi cửa sổ trên
 *     mọi display; đang chiếu cụm là gấp đôi). Không cần: dump đi từ `event.source`, không duyệt danh sách cửa sổ.
 */
class NavProbeAccessibility : AccessibilityService() {

    private val lastAt = HashMap<String, Long>()

    override fun onServiceConnected() { connected = true; Log.i(TAG, "navprobe accessibility connected") }

    override fun onUnbind(intent: android.content.Intent?): Boolean { connected = false; return super.onUnbind(intent) }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!NavProbe.isOn(applicationContext)) return
        val pkg = event.packageName?.toString() ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - (lastAt[pkg] ?: 0L) < THROTTLE_MS) return
        lastAt[pkg] = now
        runCatching { dump(pkg, event) }.onFailure { Log.e(TAG, "dump failed", it) }
    }

    /**
     * Dump NGUYÊN cây node đang hiện: class · viewId · text · contentDescription · toạ độ.
     * Cố ý KHÔNG đoán trước trường nào là cự ly/hướng rẽ — mục đích của bản này là để BIẾT chúng nằm đâu.
     *
     * Đi từ `event.source` rồi trèo lên gốc, KHÔNG dùng `rootInActiveWindow`: app chiếu lên cụm nằm ở
     * virtual display, cửa sổ "active" lúc đó là app trên màn chính → lấy nhầm cây.
     * contentDescription rất quan trọng: icon mũi tên rẽ thường không có text, chỉ có mô tả ("Rẽ phải").
     */
    private fun dump(pkg: String, event: AccessibilityEvent) {
        var root = runCatching { event.source }.getOrNull()
        if (root != null) {
            var guard = 0
            while (guard++ < MAX_DEPTH) { val p = runCatching { root!!.parent }.getOrNull() ?: break; root = p }
        }
        if (root == null) root = runCatching { rootInActiveWindow }.getOrNull()
        if (root == null) { NavProbe.recordNodes(applicationContext, pkg, "  (không lấy được cây node)"); return }
        val sb = StringBuilder()
        walk(root, sb, 0)
        NavProbe.recordNodes(applicationContext, pkg, if (sb.isEmpty()) "  (cây rỗng — không node nào có chữ)" else sb.toString())
        runCatching { root.recycle() }
    }

    private fun walk(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        node ?: return
        if (depth > MAX_DEPTH || sb.length > MAX_DUMP_CHARS) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val vid = runCatching { node.viewIdResourceName }.getOrNull().orEmpty()
        if (text.isNotEmpty() || desc.isNotEmpty() || vid.isNotEmpty()) {
            val r = Rect(); node.getBoundsInScreen(r)
            sb.append("  ".repeat(depth.coerceAtMost(12)))
                .append(node.className?.toString()?.substringAfterLast('.') ?: "?")
            if (vid.isNotEmpty()) sb.append(" #").append(vid.substringAfterLast('/'))
            if (text.isNotEmpty()) sb.append(" text=\"").append(text.take(90)).append('"')
            if (desc.isNotEmpty()) sb.append(" desc=\"").append(desc.take(90)).append('"')
            sb.append(" @").append(r.left).append(',').append(r.top)
                .append('-').append(r.right).append(',').append(r.bottom).append('\n')
        }
        for (i in 0 until node.childCount) {
            val c = runCatching { node.getChild(i) }.getOrNull() ?: continue
            walk(c, sb, depth + 1)
            runCatching { c.recycle() }
        }
    }

    companion object {
        private const val TAG = "NavProbeAcc"
        /** 2s/gói: đủ thấy cự ly đổi theo quãng đường, không đủ để một app lấp kín file. */
        private const val THROTTLE_MS = 2000L
        private const val MAX_DEPTH = 40
        private const val MAX_DUMP_CHARS = 8000

        @Volatile var connected = false
            internal set
    }
}

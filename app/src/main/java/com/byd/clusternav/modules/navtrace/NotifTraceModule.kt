package com.byd.clusternav.modules.navtrace

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.TextView
import com.byd.clusternav.NavDiag
import com.byd.clusternav.modules.ClusterModule
import com.byd.clusternav.modules.ModuleUi
import com.byd.clusternav.modules.SelfTest

/**
 * MODULE: soi NHỊP notification dẫn đường — chứng minh giả thuyết then chốt "noti GMaps vẫn cập nhật khi bị
 * YouTube che" + đo noti THÔ tới đâu (khoảng cách trung bình giữa 2 lần). Đọc ring-buffer NavDiag (read-only).
 *
 * Test trên xe: lái có dẫn đường, mở YouTube toàn màn ~30s, đỗ P -> mở module: nếu các dòng vẫn có mốc thời gian
 * SÁT nhau trong lúc che -> noti SỐNG khi nền (accessibility thì đã chết). Cũng dùng autotest mode=notiftrace để
 * dump qua adb sau khi lái. XOÁ: xoá modules/navtrace/ + dòng Registry (NavDiag là lõi, giữ).
 */
object NotifTraceModule : ClusterModule {
    override val title = "Soi nhịp noti (sống-khi-che)"

    @Volatile private var running = false
    private var view: TextView? = null
    private val main = Handler(Looper.getMainLooper())

    override fun selfTest(ctx: Context): SelfTest {
        val (n, avg) = NavDiag.cadence()
        val since = NavDiag.sinceLastMs()
        return if (n == 0) SelfTest.fail("chưa bắt được noti dẫn đường nào (mở GMaps dẫn đường trước)")
        else SelfTest.pass("60s gần nhất: $n mẫu · TB ${if (avg >= 0) "${avg}ms/lần" else "—"} · lần cuối ${since}ms trước")
    }

    override fun buildView(ctx: Context, parent: ViewGroup) {
        val ui = ModuleUi(ctx, parent)
        ui.text("Mỗi dòng = 1 lần GMaps đẩy noti. Lái + mở YouTube che ~30s rồi đỗ xem: nếu mốc thời gian vẫn " +
            "chạy đều trong lúc che -> noti SỐNG khi nền (đây là kênh duy nhất sống khi che). 'TB' = noti thô tới đâu.")
        ui.btn("Xoá buffer") { NavDiag.clear() }
        val dp = ctx.resources.displayMetrics.density
        view = TextView(ctx).apply {
            textSize = 12f; typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#1A1F24")); setBackgroundColor(Color.parseColor("#F1EFE8"))
            setPadding((14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt())
            setTextIsSelectable(true); text = "..."
        }
        parent.addView(view)
    }

    override fun onShow(ctx: Context) {
        if (running) return
        running = true
        Thread {
            while (running) {
                val t = runCatching { render() }.getOrElse { "lỗi: $it" }
                main.post { view?.text = t }
                try { Thread.sleep(500) } catch (_: InterruptedException) {}
            }
        }.start()
    }

    override fun onHide(ctx: Context) { running = false }

    private fun render(): String {
        val snap = NavDiag.snapshot()
        val (n, avg) = NavDiag.cadence()
        return buildString {
            append("nhịp 60s: $n mẫu · TB ${if (avg >= 0) "${avg}ms" else "—"} · cuối ${NavDiag.sinceLastMs()}ms trước\n")
            append("───── mới → cũ (Δ = cách dòng trước) ─────\n")
            if (snap.isEmpty()) { append("(chưa có noti dẫn đường)"); return@buildString }
            var prev = -1L
            snap.take(16).forEach { h ->
                val delta = if (prev < 0) 0L else prev - h.atMs
                prev = h.atMs
                val app = h.pkg.substringAfterLast('.')
                append("Δ${if (delta > 0) "${delta}ms" else "  ·  "}  [$app] '${h.title}' | ${h.text.take(18)}${if (h.hasIcon) " ◆" else ""}\n")
            }
        }
    }
}

package com.byd.clusternav.modules.navremoteviews

import android.app.Notification
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import com.byd.clusternav.NavDiag
import com.byd.clusternav.modules.ClusterModule
import com.byd.clusternav.modules.ModuleUi
import com.byd.clusternav.modules.SelfTest

/**
 * MODULE: RemoteViews-INTROSPECTION cho notification GMaps — vắt mọi field ẩn ngoài title/text/subText.
 * OpenBYD chỉ làm trò này cho Yandex, CHƯA làm cho GMaps -> đây là phần chưa ai khai thác. Mục tiêu: xem
 * custom view của noti GMaps có chứa cự ly MỊN hơn / làn / bước "then..." mà extras thường không lộ không.
 *
 * Cách: lấy Notification thô gần nhất (NavDiag.lastRaw) -> (1) dump TẤT CẢ extras, (2) inflate
 * contentView/bigContentView/headsUpContentView rồi duyệt cây View gom mọi TextView + ImageView desc.
 * Chạy trên MAIN thread (inflate). Read-only, không đụng feed cụm. XOÁ: xoá modules/navremoteviews/ + dòng Registry.
 */
object RemoteViewsModule : ClusterModule {
    override val title = "Vắt RemoteViews noti GMaps"

    override fun selfTest(ctx: Context): SelfTest {
        val n = NavDiag.lastRaw ?: return SelfTest.fail("chưa có noti thô (mở GMaps dẫn đường trước)")
        val cnt = runCatching { dump(ctx, n).lineSequence().count() }.getOrElse { 0 }
        return SelfTest.pass("noti '${NavDiag.lastRawPkg.substringAfterLast('.')}' · trích $cnt dòng field")
    }

    override fun buildView(ctx: Context, parent: ViewGroup) {
        val ui = ModuleUi(ctx, parent)
        ui.text("Bóc TẤT CẢ field trong noti GMaps gần nhất (extras + RemoteViews inflate). Tìm cự ly mịn/làn/bước " +
            "kế mà title/text không có. Mở GMaps dẫn đường rồi bấm Quét.")
        ui.logBox(320)
        ui.btn("Quét noti GMaps gần nhất") {
            val n = NavDiag.lastRaw
            if (n == null) ui.log("— chưa có noti thô. Mở GMaps dẫn đường (hoặc bấm test mẫu) trước.")
            else ui.log("\n===== ${NavDiag.lastRawPkg} =====\n" + dump(ctx, n))
        }
    }

    /** Cho autotest (ADB) gọi: bóc noti GMaps thô gần nhất. PHẢI gọi trên MAIN thread (inflate RemoteViews). */
    fun introspect(ctx: Context): String {
        val n = NavDiag.lastRaw ?: return "chưa có noti thô (mở GMaps dẫn đường trước)"
        return "===== ${NavDiag.lastRawPkg} =====\n" + dump(ctx, n)
    }

    /** Dump extras + mọi text/icon từ 3 RemoteViews. Gọi trên main thread (inflate). */
    private fun dump(ctx: Context, n: Notification): String = buildString {
        // 1) EXTRAS — moi BIG_TEXT / TEXT_LINES / INFO_TEXT / SUB_TEXT... ngoài title/text.
        append("· EXTRAS:\n")
        val ex = n.extras
        ex?.keySet()?.sorted()?.forEach { k ->
            val v = runCatching { ex.get(k) }.getOrNull() ?: return@forEach
            val s = when (v) {
                is CharSequence -> v.toString()
                is Array<*> -> v.joinToString(" ⏎ ") { it?.toString().orEmpty() }
                is Number, is Boolean -> v.toString()
                else -> v.javaClass.simpleName
            }.trim()
            if (s.isNotEmpty() && s.length < 200) append("   $k = $s\n")
        }
        // 2) REMOTEVIEWS — inflate từng cái rồi duyệt cây gom text + icon desc.
        listOf(
            "contentView" to n.contentView,
            "bigContentView" to n.bigContentView,
            "headsUpContentView" to n.headsUpContentView,
        ).forEach { (name, rv) ->
            if (rv == null) return@forEach
            append("· $name (${rvPackage(rv)}):\n")
            val texts = runCatching { inflateAndWalk(ctx, rv) }.getOrElse { listOf("   <inflate lỗi: ${it.javaClass.simpleName}>") }
            if (texts.isEmpty()) append("   (không thấy text)\n") else texts.forEach { append("   $it\n") }
        }
    }

    private fun rvPackage(rv: RemoteViews): String =
        runCatching { rv.`package` }.getOrNull()
            ?: runCatching { val f = RemoteViews::class.java.getDeclaredField("mPackage"); f.isAccessible = true; f.get(rv) as? String }.getOrNull()
            ?: "?"

    /** Inflate RemoteViews của app khác trong tiến trình mình rồi duyệt cây View -> list "TYPE: nội dung". */
    private fun inflateAndWalk(ctx: Context, rv: RemoteViews): List<String> {
        val host = FrameLayout(ctx)
        val v = rv.apply(ctx, host)     // RemoteViews tự dựng context của package nguồn -> đọc được layout app khác
        val out = ArrayList<String>()
        walk(v, out)
        return out
    }

    private fun walk(v: View, out: MutableList<String>) {
        when (v) {
            is TextView -> v.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add("TXT: $it") }
            is ImageView -> v.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add("IMG desc: $it") }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), out)
    }
}

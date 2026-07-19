package com.byd.clusternav.modules.navaccess

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.ViewGroup
import android.widget.TextView
import com.byd.clusternav.Prefs
import com.byd.clusternav.TurnDistanceInterpolator
import com.byd.clusternav.modules.ClusterModule
import com.byd.clusternav.modules.ModuleUi
import com.byd.clusternav.modules.SelfTest

/**
 * MODULE: điều khiển + soi BOOSTER accessibility (NavAccessibilityService). Bật quyền Hỗ trợ, bật/tắt booster,
 * và soi LIVE cự ly đọc trên màn vs cự ly đang đẩy lên cụm (để kiểm chứng booster sửa được lag GMaps).
 *
 * Trên xe (đỗ P): mở GMaps dẫn đường -> module hiện "đọc màn: NNNm", refines tăng dần. Mở YouTube toàn màn
 * -> foreground=false, booster câm, nội suy tốc độ gánh (đúng thiết kế). XOÁ: xem header NavAccessibilityService.
 */
object NavAccessibilityModule : ClusterModule {
    override val title = "Booster đọc màn (accessibility)"

    @Volatile private var running = false
    private var view: TextView? = null
    private val main = Handler(Looper.getMainLooper())

    override fun selfTest(ctx: Context): SelfTest {
        val on = isEnabledInSettings(ctx)
        val conn = NavAccessibilitySource.connected
        return when {
            on && conn -> SelfTest.pass("đã bật + service connected · booster ${onOff(ctx)}")
            on && !conn -> SelfTest.fail("đã bật trong Cài đặt nhưng service CHƯA connect (mở lại app/đợi vài giây)")
            else -> SelfTest.fail("CHƯA bật quyền Hỗ trợ — bấm nút mở Cài đặt > Hỗ trợ > ClusterNav")
        }
    }

    override fun buildView(ctx: Context, parent: ViewGroup) {
        val ui = ModuleUi(ctx, parent)
        ui.text("Đọc cự ly tới rẽ HIỂN THỊ trên Google Maps (chính xác/tươi hơn noti) -> tinh chỉnh số đẩy lên cụm. " +
            "Chỉ chạy khi GMaps đang HIỆN; bị app khác che thì tự câm, nội suy theo tốc độ gánh tiếp.")
        ui.btn("Mở Cài đặt > Hỗ trợ (bật ClusterNav)") {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { ui.log("không mở được Cài đặt Hỗ trợ: $it") }
        }
        ui.btn("Booster: BẬT ↔ TẮT") {
            Prefs.setAccBooster(ctx, !Prefs.accBooster(ctx))
            ui.log("booster → ${onOff(ctx)}")
        }
        val dp = ctx.resources.displayMetrics.density
        view = TextView(ctx).apply {
            textSize = 14f; typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#1A1F24")); setBackgroundColor(Color.parseColor("#F1EFE8"))
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
            text = "..."
        }
        parent.addView(view)
    }

    override fun onShow(ctx: Context) {
        if (running) return
        running = true
        val app = ctx.applicationContext
        Thread {
            while (running) {
                val t = runCatching { read(app) }.getOrElse { "lỗi: $it" }
                main.post { view?.text = t }
                try { Thread.sleep(500) } catch (_: InterruptedException) {}
            }
        }.start()
    }

    override fun onHide(ctx: Context) { running = false }

    private fun read(ctx: Context): String {
        val now = SystemClock.elapsedRealtime()
        val src = NavAccessibilitySource
        val proj = TurnDistanceInterpolator.lastProjected()
        return buildString {
            append("quyền Hỗ trợ : ${if (isEnabledInSettings(ctx)) "ĐÃ BẬT" else "CHƯA bật"}\n")
            append("service      : ${if (src.connected) "connected" else "—"}\n")
            append("booster      : ${onOff(ctx)}\n")
            append("GMaps màn    : ${if (src.foreground(now)) "đang HIỆN (đọc được)" else "ẩn/che (câm, nội suy gánh)"}\n")
            append("đọc màn      : ${if (src.fresh(now) && src.turnMeters >= 0) "${src.turnMeters} m" else "—"}  đường='${src.road}'\n")
            append("→ CỤM (nội suy): ${if (proj >= 0) "${proj} m" else "—"}\n")
            append("info đáy     : ${src.bottomInfo.ifBlank { "—" }}\n")
            append("đã tinh chỉnh: ${src.refines} lần")
        }
    }

    private fun onOff(ctx: Context) = if (Prefs.accBooster(ctx)) "BẬT" else "TẮT"

    private fun isEnabledInSettings(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return flat.split(":").any { it.contains("NavAccessibilityService") && it.contains(ctx.packageName) }
    }
}

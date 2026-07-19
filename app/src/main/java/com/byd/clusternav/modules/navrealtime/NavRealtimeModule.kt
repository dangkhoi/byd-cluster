package com.byd.clusternav.modules.navrealtime

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.TextView
import com.byd.clusternav.NavParse
import com.byd.clusternav.NavRepository
import com.byd.clusternav.Prefs
import com.byd.clusternav.SpeedProvider
import com.byd.clusternav.TurnDistanceInterpolator
import com.byd.clusternav.modules.ClusterModule
import com.byd.clusternav.modules.ModuleUi
import com.byd.clusternav.modules.SelfTest

/**
 * MODULE: Nav realtime — bật/tắt + theo dõi NỘI SUY cự ly-tới-rẽ (dead-reckoning theo tốc độ thật).
 * Hạ lag noti GMaps (30-50m) về ~0 mà chạy cả khi GMaps bị YouTube che (noti + tốc độ HAL đều chạy ngầm).
 * Logic nội suy nằm ở LÕI (TurnDistanceInterpolator + ClusterBroadcaster); module này chỉ điều khiển + soi.
 *
 * Test trên xe: lái có dẫn đường → bật/tắt nội suy, so cụm bám sát thực tế hơn không. XOÁ: xoá modules/navrealtime/ + dòng Registry.
 */
object NavRealtimeModule : ClusterModule {
    override val title = "Nav realtime (nội suy lag)"

    @Volatile private var running = false
    private var view: TextView? = null
    private val main = Handler(Looper.getMainLooper())

    override fun selfTest(ctx: Context): SelfTest {
        val kmh = SpeedProvider.mps() * 3.6
        return SelfTest.pass("đọc tốc độ = ${"%.0f".format(kmh)} km/h · nội suy ${onOff(ctx)} (đỗ yên tốc độ ~0 là đúng)")
    }

    override fun buildView(ctx: Context, parent: ViewGroup) {
        val ui = ModuleUi(ctx, parent)
        ui.text("Nội suy cự ly-tới-rẽ theo TỐC ĐỘ thật giữa 2 noti → cụm bám thực tế, hết miss-turn. " +
            "Chạy cả khi GMaps bị app khác che. Bật/tắt để so trên đường.")
        ui.btn("Nội suy: BẬT ↔ TẮT") {
            Prefs.setInterpolate(ctx, !Prefs.interpolate(ctx))
            ui.log("nội suy → ${onOff(ctx)} (đổi xong lái thử so độ trễ)")
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
                try { Thread.sleep(400) } catch (_: InterruptedException) {}
            }
        }.start()
    }

    override fun onHide(ctx: Context) { running = false }

    private fun read(ctx: Context): String {
        val st = NavRepository.state
        val raw = NavParse.parseMeters(st.distance)
        val anch = TurnDistanceInterpolator.anchorMeters()
        val proj = TurnDistanceInterpolator.lastProjected()
        val kmh = SpeedProvider.mps() * 3.6
        return buildString {
            append("nội suy : ${onOff(ctx)}\n")
            append("tốc độ  : ${"%.0f".format(kmh)} km/h\n")
            append("nav     : active=${st.active} đường='${st.road}'\n")
            append("noti thô: ${if (raw >= 0) "${raw} m" else "—"}\n")
            append("anchor  : ${if (anch >= 0) "${anch} m" else "—"}\n")
            append("→ CỤM   : ${if (proj >= 0) "${proj} m" else "—"}  (giá trị nội suy đang đẩy lên)\n")
            append("ETA     : ${st.eta.ifBlank { "—" }}")
        }
    }

    private fun onOff(ctx: Context) = if (Prefs.interpolate(ctx)) "BẬT" else "TẮT"
}

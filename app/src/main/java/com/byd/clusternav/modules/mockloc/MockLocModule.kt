package com.byd.clusternav.modules.mockloc

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.TextView
import com.byd.clusternav.modules.ClusterModule
import com.byd.clusternav.modules.ModuleUi
import com.byd.clusternav.modules.SelfTest

/**
 * MODULE: test MOCK-LOCATION có ĐÈ được GMaps không (go/no-go cho cả hướng dead-reckoning).
 * Bơm 1 toạ độ tĩnh (Hồ Gươm HN) → mở GMaps xem chấm có nhảy tới không. Đây là phiên bản UI của autotest mode=mockprobe.
 *
 * Cần TRƯỚC: Cài đặt > Tuỳ chọn nhà phát triển > Chọn ứng dụng vị trí mô phỏng = ClusterNav. XOÁ: xoá modules/mockloc/ + Registry.
 */
object MockLocModule : ClusterModule {
    override val title = "Mock GPS (test đè GMaps)"

    private const val LAT = 21.028511   // Hồ Gươm HN — xa HCM để thấy nhảy rõ
    private const val LON = 105.854167

    @Volatile private var pumping = false
    @Volatile private var polling = false
    private var view: TextView? = null
    private val main = Handler(Looper.getMainLooper())

    override fun selfTest(ctx: Context): SelfTest {
        val err = MockLoc.start(ctx)
        return if (err.isEmpty()) { MockLoc.stop(ctx); SelfTest.pass("addTestProvider OK — đã chọn ClusterNav làm mock app ✓") }
        else SelfTest.fail(err)
    }

    override fun buildView(ctx: Context, parent: ViewGroup) {
        val ui = ModuleUi(ctx, parent)
        ui.text("Bơm toạ độ giả (Hồ Gươm HN) → MỞ Google Maps trên head unit: chấm xe NHẢY về HN = mock ĐÈ được " +
            "(cả hướng dead-reckoning ĐI ✓). Đứng nguyên = head unit dùng GPS OEM riêng. Cần chọn ClusterNav làm mock app trong Developer Options.")
        ui.btn("▶ Bơm toạ độ giả (Hồ Gươm)") {
            val err = MockLoc.start(ctx)
            if (err.isNotEmpty()) { ui.log("❌ $err"); return@btn }
            pumping = true
            val app = ctx.applicationContext
            Thread {
                while (pumping) { MockLoc.push(app, LAT, LON, 0.0, 0.0); try { Thread.sleep(500) } catch (_: InterruptedException) {} }
            }.start()
            ui.log("đang bơm ($LAT,$LON) — mở GMaps xem chấm có nhảy về Hà Nội không")
        }
        ui.btn("■ Dừng + gỡ mock") { pumping = false; MockLoc.stop(ctx); ui.log("đã dừng, gỡ test provider") }
        val dp = ctx.resources.displayMetrics.density
        view = TextView(ctx).apply {
            textSize = 14f; typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#1A1F24")); setBackgroundColor(Color.parseColor("#F1EFE8"))
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt()); text = "..."
        }
        parent.addView(view)
    }

    override fun onShow(ctx: Context) {
        if (polling) return
        polling = true
        Thread {
            while (polling) {
                val t = "đang bơm: $pumping · MockLoc.active=${MockLoc.active}\nToạ độ giả: Hồ Gươm HN ($LAT, $LON)"
                main.post { view?.text = t }
                try { Thread.sleep(700) } catch (_: InterruptedException) {}
            }
        }.start()
    }

    override fun onHide(ctx: Context) { polling = false; pumping = false; MockLoc.stop(ctx) }
}

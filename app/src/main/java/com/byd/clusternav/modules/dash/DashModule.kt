package com.byd.clusternav.modules.dash

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.TextView
import com.byd.clusternav.modules.ClusterModule
import com.byd.clusternav.modules.SelfTest
import com.byd.clusternav.modules.hal.BydHal

/**
 * MODULE: Bảng dữ liệu xe LIVE — đọc HAL in-process mỗi 1.5s (no-root, qua getter trực tiếp + bypass-context).
 * Đây là tính năng MỚI từ phiên test xe: áp suất 4 lốp, tốc độ, số, EPB, nhiệt ngoài, pin còn.
 * (OEM-map-bị-cắt không đụng tới được phần đọc HAL này.)
 *
 * XOÁ: xoá modules/dash/ + dòng ModuleRegistry. (Dùng chung BydHal với vdmap — đừng xoá modules/hal/.)
 */
object DashModule : ClusterModule {
    override val title = "Bảng dữ liệu xe (live)"

    @Volatile private var running = false
    private var view: TextView? = null
    private val main = Handler(Looper.getMainLooper())

    override fun selfTest(ctx: Context): SelfTest {
        val t = BydHal.device(BydHal.TYRE, BydHal.systemBypassContext(), BydHal.bypass(ctx))
        val v = t?.let { BydHal.callGetter(it, "getTyrePressureValue", 1) }
        return if (v != null) SelfTest.pass("đọc được áp suất lốp trái-trước = $v kPa")
        else SelfTest.fail("không đọc được TyreDevice (HAL chặn?)")
    }

    override fun buildView(ctx: Context, parent: ViewGroup) {
        val dp = ctx.resources.displayMetrics.density
        view = TextView(ctx).apply {
            textSize = 15f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#1A1F24"))
            setBackgroundColor(Color.parseColor("#F1EFE8"))
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
            text = "Đang đọc dữ liệu xe..."
        }
        parent.addView(view)
    }

    override fun onShow(ctx: Context) {
        if (running) return
        running = true
        val app = ctx.applicationContext
        Thread {
            while (running) {
                val text = runCatching { read(app) }.getOrElse { "lỗi đọc: ${BydHal.root(it)}" }
                main.post { view?.text = text }
                try { Thread.sleep(1500) } catch (_: InterruptedException) {}
            }
        }.start()
    }

    override fun onHide(ctx: Context) { running = false }

    private fun read(ctx: Context): String {
        val sys = BydHal.systemBypassContext()
        fun dev(fqn: String) = BydHal.device(fqn, sys, BydHal.bypass(ctx))
        fun g(d: Any?, name: String, arg: Int? = null) = d?.let { BydHal.callGetter(it, name, arg) } ?: "—"
        val sb = StringBuilder()

        dev(BydHal.TYRE).let { d ->
            sb.append("LỐP (kPa)\n")
            sb.append("   trước:  T ${g(d, "getTyrePressureValue", 1)}    P ${g(d, "getTyrePressureValue", 2)}\n")
            sb.append("   sau:    T ${g(d, "getTyrePressureValue", 3)}    P ${g(d, "getTyrePressureValue", 4)}\n")
            sb.append("   hệ thống: ${tyreSys(g(d, "getTyreSystemState"))}\n\n")
        }
        dev(BydHal.SPEED).let { d -> sb.append("TỐC ĐỘ     ${g(d, "getCurrentSpeed")} km/h\n") }
        dev(BydHal.GEARBOX).let { d ->
            sb.append("SỐ         gear=${g(d, "getCurrentGear")}  state=${g(d, "getGearboxState")}  EPB=${g(d, "getEPBState")}\n")
        }
        dev(BydHal.INSTRUMENT).let { d -> sb.append("NHIỆT NGOÀI ${g(d, "getOutCarTemperature")}°C\n") }
        dev(BydHal.STATISTIC).let { d -> sb.append("PIN còn    ${g(d, "getRemainingBatteryPower")} (raw)\n") }

        sb.append("\n(cập nhật mỗi 1.5s · đọc in-process no-root)")
        return sb.toString()
    }

    private fun tyreSys(v: String): String = when (v) {
        "0" -> "bình thường"; "1" -> "đang tự kiểm"; "2" -> "tín hiệu bất thường"
        "3" -> "hỏng"; "4" -> "che/ẩn"; else -> v
    }
}

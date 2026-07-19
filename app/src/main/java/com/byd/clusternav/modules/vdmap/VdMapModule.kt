package com.byd.clusternav.modules.vdmap

import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import com.byd.clusternav.modules.ClusterModule
import com.byd.clusternav.modules.ModuleUi
import com.byd.clusternav.modules.SelfTest

/**
 * MODULE: VirtualDisplay map ở MÀN GIỮA (KHÔNG phải cụm). Chiếu app map THẬT (GMaps/Waze/VietMap) vào 1
 * SurfaceView qua VirtualDisplay (port kim.apk NavigationVirtualDisplaySession) → mode "toàn màn / cửa sổ nhỏ".
 *
 * NGOẠI LỆ kiến trúc (duy nhất): module này cần Activity riêng [VdMapActivity] → có 1 dòng <activity> trong Manifest.
 * XOÁ: xoá modules/vdmap/ + dòng ModuleRegistry + dòng <activity .modules.vdmap.VdMapActivity> trong Manifest.
 *
 * v1: CHỦ YẾU để HIỂN THỊ (chứng minh chiếu được). Chạm cần INJECT_EVENTS (app thường bị chặn) → nối qua dadb
 * 'input -d <id>' ở bản sau.
 */
object VdMapModule : ClusterModule {
    override val title = "Map màn giữa (VirtualDisplay)"

    val MAP_PKGS = listOf(
        "com.google.android.apps.maps",
        "app.revanced.android.apps.maps",
        "vn.vietmap.live",
        "com.waze",
    )

    private fun installed(ctx: Context) =
        MAP_PKGS.filter { ctx.packageManager.getLaunchIntentForPackage(it) != null }

    override fun selfTest(ctx: Context): SelfTest {
        val apps = installed(ctx)
        if (apps.isEmpty()) return SelfTest.fail("không có app map nào (GMaps/Waze/VietMap)")
        // Xanh CHỈ khi OS cho app TẠO VirtualDisplay (không tin mỗi 'app đã cài'): tạo 1 VD 64×64 rồi nhả.
        return runCatching {
            val reader = android.media.ImageReader.newInstance(64, 64, android.graphics.PixelFormat.RGBA_8888, 1)
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val vd = dm.createVirtualDisplay("byd-selftest-" + android.os.SystemClock.uptimeMillis(),
                64, 64, 160, reader.surface, 1 or 8)
            val id = vd?.display?.displayId ?: -1
            vd?.release(); reader.close()
            if (id >= 0) SelfTest.pass("tạo VirtualDisplay OK + app map: ${apps.joinToString()} — chiếu thật để xác nhận render/chạm")
            else SelfTest.fail("createVirtualDisplay không trả displayId")
        }.getOrElse { SelfTest.fail("OS chặn tạo VirtualDisplay: ${it.javaClass.simpleName}: ${it.message}") }
    }

    override fun buildView(ctx: Context, parent: ViewGroup) {
        val ui = ModuleUi(ctx, parent)
        val apps = installed(ctx)
        ui.text("Chiếu app map thật lên màn giữa qua VirtualDisplay. App có: ${apps.joinToString().ifEmpty { "(không có)" }}")
        fun open(full: Boolean, dadb: Boolean) {
            val pkg = apps.firstOrNull() ?: run { ui.log("không có app map để mở"); return }
            ui.log("mở $pkg (${if (full) "toàn màn" else "cửa sổ nhỏ"}, ${if (dadb) "qua dadb shell" else "in-process"})")
            ctx.startActivity(Intent(ctx, VdMapActivity::class.java)
                .putExtra("pkg", pkg).putExtra("full", full).putExtra("dadb", dadb)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        ui.btn("Mở map TOÀN màn — qua dadb (pin chắc, popup Allow)") { open(true, true) }
        ui.btn("Mở map NHỎ ~70% — qua dadb") { open(false, true) }
        ui.btn("Mở map TOÀN màn — in-process (hay rơi màn chính)") { open(true, false) }
        ui.text("dadb: pin map vào VirtualDisplay bằng 'am start --display' privileged (cách kim) — chắc ăn hơn in-process. " +
            "Lần đầu xe hiện Allow → bấm. Chạm điều khiển nối 'input -d' sau.")
        ui.logBox(120)
    }
}

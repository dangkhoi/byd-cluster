package com.byd.clusternav.modules.vdmap

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.Toast
import com.byd.clusternav.modules.hal.DadbBridge

/**
 * Chiếu app map (extra "pkg") lên VirtualDisplay backed bởi SurfaceView → hiện trên MÀN GIỮA.
 * LẤY Ý TƯỞNG từ kim.apk NavigationVirtualDisplaySession nhưng MỚI port phần tạo VD + setLaunchDisplayId.
 *
 * ⚠ CHƯA port 2 thứ load-bearing của kim → đây là v1 THĂM DÒ, có thể KHÔNG chiếu được:
 *   (1) kim launch in-process THẤT BẠI thì fallback `am start --display <id>` qua ADB privileged — ta CHƯA có,
 *       nên nếu OS chặn launch cross-display từ app thường thì activity rơi về màn mặc định / surface đen.
 *   (2) kim `am force-stop` app map khi đóng — ta CHƯA có (chỉ release VD), app map có thể còn chạy nền.
 * → Nếu trên xe chiếu được = kỹ thuật chạy, sẽ thêm fallback shell + force-stop (qua dadb). Không thì dẹp.
 * Chạm cũng cần INJECT_EVENTS (app thường bị chặn) → để sau qua dadb 'input -d'.
 */
class VdMapActivity : Activity(), SurfaceHolder.Callback {

    private var vd: VirtualDisplay? = null
    private var displayId: Int = -1
    private lateinit var pkg: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pkg = intent.getStringExtra("pkg") ?: "com.google.android.apps.maps"
        val full = intent.getBooleanExtra("full", true)

        val sv = SurfaceView(this).apply { holder.addCallback(this@VdMapActivity) }
        if (full) {
            setContentView(sv)
        } else {
            val m = resources.displayMetrics
            val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#202124")) }
            root.addView(sv, FrameLayout.LayoutParams(
                (m.widthPixels * 0.7).toInt(), (m.heightPixels * 0.7).toInt(), Gravity.CENTER))
            setContentView(root)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (vd == null) {
            val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
            val flags = 1 or 8   // VIRTUAL_DISPLAY_FLAG_PUBLIC | _OWN_CONTENT_ONLY (như kim.apk)
            vd = runCatching {
                dm.createVirtualDisplay("byd-clusternav-map-" + SystemClock.uptimeMillis(),
                    width, height, resources.displayMetrics.densityDpi, holder.surface, flags)
            }.onFailure { toastFinish("Tạo VirtualDisplay lỗi: ${it.javaClass.simpleName}: ${it.message}") }.getOrNull()
            displayId = vd?.display?.displayId ?: -1
            android.util.Log.i("CLNAV_AUTO", "VD displayId=$displayId (am start --display $displayId -n <map> để chiếu qua shell)")
            if (displayId < 0) { if (vd != null) toastFinish("VirtualDisplay không có displayId"); return }
            when {
                intent.getBooleanExtra("manual", false) -> {}          // chờ 'am start --display' từ adb ngoài
                intent.getBooleanExtra("dadb", false) -> launchViaDadb() // pin chắc qua shell privileged (cách kim)
                else -> launchMap()                                     // in-process best-effort (hay rơi về màn chính)
            }
        } else {
            runCatching { vd?.resize(width, height, resources.displayMetrics.densityDpi) }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // SurfaceView surface là tạm (khác texture bền của kim) — surface mất thì nhả VD, surfaceChanged sẽ tạo lại.
        runCatching { vd?.release() }
        vd = null
        displayId = -1
    }

    /** Pin map vào VD qua shell privileged `am start --display <vdId>` (cách kim — app thường không pin được in-process). */
    private fun launchViaDadb() {
        val comp = packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToShortString()
        val id = displayId
        Thread {
            if (!DadbBridge.ensure(applicationContext)) { toastMain("dadb chưa nối (bấm Allow trên xe?)"); return@Thread }
            val cmd = if (comp != null) "am start --display $id -n $comp"
            else "am start --display $id -a android.intent.action.VIEW -d 'geo:0,0?q=' $pkg"
            val out = DadbBridge.shell(cmd)
            android.util.Log.i("CLNAV_AUTO", "vdmap dadb: $cmd -> $out")
            if (out.contains("Error", true) || out.contains("Exception", true)) toastMain("am start --display lỗi (xem log)")
        }.start()
    }

    private fun toastMain(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    private fun launchMap() {
        val base = packageManager.getLaunchIntentForPackage(pkg)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")).setPackage(pkg)
        val intent = base.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val opts = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        runCatching { startActivity(intent, opts.toBundle()) }
            .onFailure { toastFinish("Launch $pkg lỗi: ${it.javaClass.simpleName}: ${it.message}") }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { vd?.release() }
        vd = null
    }

    private fun toastFinish(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }
}

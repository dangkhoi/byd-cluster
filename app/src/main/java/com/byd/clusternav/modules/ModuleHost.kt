package com.byd.clusternav.modules

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.byd.clusternav.NavRepository
import com.byd.clusternav.NavState

/**
 * Host CHUNG cho mọi [ClusterModule]. Đọc extra "module" (theo title) → tìm trong [ModuleRegistry] →
 * dựng UI module + nút Self-test + đăng ký NavState READ-ONLY khi panel mở.
 *
 * BẢO VỆ LÕI: mọi lời gọi module bọc trong runCatching → module lỗi CHỈ hỏng panel, KHÔNG đụng đường nav
 * broadcast (host là consumer thứ 2 của NavRepository, đứng SAU cụm, không có handle ghi broadcast).
 *
 * Đây + [ClusterModule] + [ModuleRegistry] = 3 file hạ tầng DUY NHẤT. Module không phải Activity nên
 * thêm/xoá module KHÔNG đụng Manifest/XML/MainActivity.
 */
class ModuleHost : Activity() {

    private var module: ClusterModule? = null
    private val listener: (NavState) -> Unit = { s ->
        module?.let { m -> runCatching { m.onNavState(applicationContext, s) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        module = ModuleRegistry.MODULES.firstOrNull { it.title == intent.getStringExtra("module") }
        val m = module ?: run { finish(); return }

        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        col.addView(TextView(this).apply { text = m.title; textSize = 20f })

        // hàng self-test: chấm trạng thái + nút + chi tiết (cổng keep-or-kill)
        val dot = TextView(this).apply { text = "●"; textSize = 18f; setTextColor(Color.parseColor("#9AA0A6")) }
        val status = TextView(this).apply { textSize = 12f }
        col.addView(Button(this).apply {
            text = "Self-test ▶"
            setOnClickListener {
                status.text = "đang chạy self-test..."
                Thread {
                    val r = runCatching { m.selfTest(applicationContext) }.getOrElse { SelfTest.fail(root(it)) }
                    runOnUiThread {
                        dot.setTextColor(Color.parseColor(if (r.ok) "#1E8E3E" else "#D93025"))
                        status.text = (if (r.ok) "PASS · " else "FAIL · ") + r.detail
                    }
                }.start()
            }
        })
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (4 * dp).toInt(), 0, (10 * dp).toInt())
            addView(dot); addView(status.apply { setPadding((6 * dp).toInt(), 0, 0, 0) })
        })

        // container cho module tự dựng UI
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(container)
        runCatching { m.buildView(this, container) }.onFailure { status.text = "buildView lỗi: ${root(it)}" }

        setContentView(ScrollView(this).apply { addView(col) }, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun onResume() {
        super.onResume()
        module?.let { m -> runCatching { m.onShow(applicationContext) } }
        NavRepository.addListener(listener)          // read-only; phát ngay state hiện tại
    }

    override fun onPause() {
        super.onPause()
        NavRepository.removeListener(listener)
        module?.let { m -> runCatching { m.onHide(applicationContext) } }
    }

    private fun root(t: Throwable): String {
        var c: Throwable = t
        while (c.cause != null && c.cause !== c) c = c.cause!!
        return "${c.javaClass.simpleName}: ${c.message}"
    }
}

package com.byd.clusternav

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Màn THU THẬP cho hội (crowdsource) — anh em chỉ cần cài app + cho phép lưu + chạy nav, rồi gửi
 * thư mục về. Gom 2 thứ: (1) icon mũi tên GMaps (mỗi loại 1 lần, tự lưu khi dẫn đường) để dựng template,
 * (2) bảng HUD feature-id của ROM xe đó (xác định kênh HUD trên từng xe / bản HUD cũ-mới).
 */
class CollectActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var hudOut: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        col.addView(TextView(this).apply { text = "Thu thập (cho hội)"; textSize = 20f })
        col.addView(TextView(this).apply {
            text = "1) Bấm 'Cho phép lưu vào Download'.\n" +
                "2) Mở Google Maps dẫn đường + chạy → app TỰ lưu mỗi kiểu MŨI TÊN 1 lần (để dựng template).\n" +
                "3) Bấm 'Dò HUD của xe này' → lưu hud_fields.txt.\n" +
                "4) Gửi thư mục ClusterNav-collect (trong Download) cho admin (kèm đời xe + có HUD kính lái không)."
            textSize = 13f; setPadding(0, (8 * dp).toInt(), 0, (12 * dp).toInt())
        })

        status = TextView(this).apply { textSize = 14f; setPadding(0, 0, 0, (10 * dp).toInt()) }
        col.addView(status)

        col.addView(Button(this).apply {
            text = "Cho phép lưu vào Download"
            setOnClickListener { requestStorage() }
        })
        col.addView(Button(this).apply {
            text = "Dò HUD của xe này (lưu hud_fields.txt)"
            setOnClickListener { hudOut.text = dumpHud(); refresh() }
        })

        hudOut = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#1A1F24"))
            setBackgroundColor(Color.parseColor("#F1EFE8"))
            setPadding(pad, pad, pad, pad)
            movementMethod = ScrollingMovementMethod()
            setTextIsSelectable(true)
        }
        col.addView(hudOut, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (180 * dp).toInt()
        ).apply { topMargin = (12 * dp).toInt() })

        setContentView(ScrollView(this).apply { addView(col) }, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun onResume() { super.onResume(); requestStorage(); refresh() }

    private fun refresh() {
        status.text = "Icon mũi tên đã thu: ${CollectStore.iconCount(applicationContext)}\n" +
            "Thư mục: ${CollectStore.pathHint(applicationContext)}"
    }

    private fun requestStorage() {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            runCatching { requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1) }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    /** Reflect BYDAutoFeatureIds, liệt kê mọi field có 'HUD' + lưu file. Không ghi HAL (chỉ đọc hằng số). */
    private fun dumpHud(): String {
        runCatching {
            val vm = Class.forName("dalvik.system.VMRuntime")
            val rt = vm.getMethod("getRuntime").invoke(null)
            vm.getMethod("setHiddenApiExemptions", Array<String>::class.java).invoke(rt, arrayOf("L") as Any)
        }
        val sb = StringBuilder()
        runCatching {
            val ids = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds")
            for (f in ids.fields) if (f.name.uppercase().contains("HUD")) runCatching {
                val v = f.getInt(null); sb.append("${f.name} = $v (0x${Integer.toHexString(v)})\n")
            }
        }.onFailure { sb.append("lỗi reflect BYDAutoFeatureIds: ${it.javaClass.simpleName}: ${it.message}\n") }
        val out = if (sb.isEmpty()) "(không có field HUD nào trong BYDAutoFeatureIds → xe/ROM này không có kênh HUD qua HAL)" else sb.toString()
        CollectStore.saveText(applicationContext, "hud_fields.txt", out)
        return out
    }
}

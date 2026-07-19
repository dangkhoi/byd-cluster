package com.byd.clusternav.modules

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Tiện ích dựng UI panel cho module (nút + dòng chữ + hộp log). INFRA dùng chung — không phải module.
 * Mỗi nút bọc runCatching (lỗi nút không giết panel). log() đổ vào hộp log (gọi logBox() trước).
 */
class ModuleUi(private val ctx: Context, private val parent: ViewGroup) {
    private val main = Handler(Looper.getMainLooper())
    private val dp = ctx.resources.displayMetrics.density
    private var logView: TextView? = null

    fun text(s: String): ModuleUi {
        parent.addView(TextView(ctx).apply { text = s; textSize = 12f })
        return this
    }

    fun btn(label: String, onClick: () -> Unit): ModuleUi {
        parent.addView(Button(ctx).apply {
            text = label
            setOnClickListener { runCatching(onClick).onFailure { log("ERR: $it") } }
        })
        return this
    }

    /** Hộp log cuộn được; mọi log() sau đó đổ vào đây. */
    fun logBox(heightDp: Int = 220): ModuleUi {
        logView = TextView(ctx).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#1A1F24"))
            setBackgroundColor(Color.parseColor("#F1EFE8"))
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
            setTextIsSelectable(true)
        }
        parent.addView(logView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (heightDp * dp).toInt()))
        return this
    }

    fun log(s: String) { main.post { logView?.append(s + "\n") } }
}

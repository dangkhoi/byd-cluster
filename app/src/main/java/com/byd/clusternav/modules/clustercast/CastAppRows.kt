package com.byd.clusternav.modules.clustercast

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.byd.clusternav.cast.platform.CastAppEntry
import com.byd.clusternav.modules.clustercast.AppScale

/**
 * Hàng app của màn Cluster Cast, dựng lại theo bản v0.3x mà chủ dự án dùng thật.
 *
 * Vì sao dựng lại thay vì sửa tiếp bản V2: V2 xé một cử chỉ thành ba chỗ — chạm ô chỉ chọn một target duy
 * nhất, danh sách cho nút nổi nằm trong hộp thoại khác, chỉnh khung/DPI lại ở hộp thoại thứ ba. Bản v0.3x
 * làm cả ba bằng một chỗ: **chạm app = tick xanh + đưa vào nút nổi**, và ngay dưới app vừa tick là panel
 * chỉnh kích thước / vị trí / DPI cùng nút chiếu riêng cho chính app đó.
 *
 * Khác v0.3x đúng một điểm, và là điểm phải khác: mọi lệnh đi qua façade V2, không gọi engine V1.
 */
object CastAppRows {

    /** Gộp loạt nhấn dồn dập thành một lần áp. v0.3x ghi rõ đây là bản vá LAG trên xe. */
    const val APPLY_DEBOUNCE_MS = 320L

    /** Những gì một hàng cần từ bên ngoài. Không tự đọc gì để hàng vẫn kiểm được và không giữ trạng thái. */
    interface Actions {
        fun chosen(packageName: String): Boolean
        fun setChosen(packageName: String, enabled: Boolean)
        fun scaleOf(packageName: String): AppScale
        fun setScale(packageName: String, scale: AppScale)

        /** Kích thước cụm tham chiếu để tính khung quanh tâm. */
        fun clusterSize(): Pair<Int, Int>

        /** Áp khung hiện tại của app lên cụm — gọi qua debounce, không gọi mỗi lần nhấn. */
        fun applySoon(packageName: String)

        /** Chiếu chính app này. v0.3x có nút chiếu ngay tại từng app. */
        fun cast(packageName: String)
    }

    fun build(context: Context, entry: CastAppEntry, actions: Actions): View {
        val holder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        lateinit var repaint: () -> Unit

        fun refreshPanel() {
            panel.removeAllViews()
            if (actions.chosen(entry.packageName)) panel.addView(scalePanel(context, entry, actions))
        }

        val label = TextView(context).apply {
            textSize = 15f
            maxLines = 2
            setPadding(dp(context, 10), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val icon = ImageView(context).apply {
            val side = dp(context, 40)
            layoutParams = LinearLayout.LayoutParams(side, side)
            val drawable = entry.icon
            if (drawable != null) setImageDrawable(drawable) else setImageResource(android.R.drawable.sym_def_app_icon)
        }
        val tile = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, 64)
            setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4)) }
            addView(icon)
            addView(label)
        }
        repaint = {
            val on = actions.chosen(entry.packageName)
            tile.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 12).toFloat()
                setColor(if (on) 0xFFE6F1FB.toInt() else Color.WHITE)
                setStroke(dp(context, if (on) 2 else 1), if (on) 0xFF378ADD.toInt() else 0xFFE3E6EB.toInt())
            }
            label.setTextColor(if (on) 0xFF185FA5.toInt() else 0xFF1A1F24.toInt())
            label.text = buildString {
                if (on) append("✓ ")
                append(entry.label)
                if (entry.isDefault) append(" · mặc định")
            }
            tile.isSelected = on
            tile.contentDescription = buildString {
                append(entry.label)
                append(if (on) ". Đang trong nút nổi, chạm để bỏ" else ". Chạm để đưa vào nút nổi")
            }
        }
        repaint()
        tile.setOnClickListener {
            val next = !actions.chosen(entry.packageName)
            actions.setChosen(entry.packageName, next)
            repaint()
            refreshPanel()
        }
        tile.tag = entry.packageName

        holder.addView(tile)
        holder.addView(panel)
        refreshPanel()
        return holder
    }

    /**
     * Panel inline dưới app đã tick: một dòng tóm tắt, một hàng nút lớn, và nút chiếu cho chính app này.
     *
     * Bốn nhóm giữ đúng v0.3x vì nó dễ hiểu hơn tám nút cạnh: Kích thước nới/thu quanh tâm · Vị trí dời giữ
     * cỡ · DPI (số nhỏ = chữ to) · Khôi phục về full cụm.
     */
    private fun scalePanel(context: Context, entry: CastAppEntry, actions: Actions): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(context, 4), 0, dp(context, 4), dp(context, 8)) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 12).toFloat()
                setColor(0xFFF7F8FA.toInt())
                setStroke(dp(context, 1), 0xFFE3E6EB.toInt())
            }
        }
        val summary = TextView(context).apply {
            textSize = 13f
            setTextColor(0xFF5B6470.toInt())
            setPadding(dp(context, 2), 0, dp(context, 2), dp(context, 8))
            text = summarise(actions, entry.packageName)
        }
        column.addView(summary)
        column.addView(
            Button(context).apply {
                text = "CHIẾU ${entry.label.uppercase()} LÊN CỤM"
                isAllCaps = false
                minimumHeight = dp(context, 52)
                contentDescription = "Chiếu ${entry.label} lên cụm"
                setOnClickListener { actions.cast(entry.packageName) }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(context, 10) }
            },
        )

        fun after() {
            summary.text = summarise(actions, entry.packageName)
            actions.applySoon(entry.packageName)
        }
        fun resize(deltaW: Int, deltaH: Int) {
            val (w, h) = actions.clusterSize()
            actions.setScale(entry.packageName, actions.scaleOf(entry.packageName).nudgeRect(w, h, deltaW, deltaH))
            after()
        }
        fun move(dx: Int, dy: Int) {
            val (w, h) = actions.clusterSize()
            actions.setScale(entry.packageName, actions.scaleOf(entry.packageName).nudgeMove(w, h, dx, dy))
            after()
        }
        fun percent(value: Int) {
            val (w, h) = actions.clusterSize()
            actions.setScale(entry.packageName, actions.scaleOf(entry.packageName).scaled(w, h, value))
            after()
        }
        fun dpi(delta: Int) {
            actions.setScale(entry.packageName, actions.scaleOf(entry.packageName).nudgeDpi(delta))
            after()
        }

        val step = AppScale.STEP_WH
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        // Nhóm % đứng ĐẦU, đúng thứ tự v0.36+: một chạm là ra tỉ lệ dùng được, khỏi bấm "Hẹp" hai mươi lần.
        // 100% = trả về full cụm nhưng GIỮ mức nội dung đang chọn.
        row.addView(
            group(context, "Khung cụm", listOf(
                "100%" to { actions.setScale(entry.packageName, AppScale(dpi = actions.scaleOf(entry.packageName).dpi)); after() },
                "90%" to { percent(90) }, "80%" to { percent(80) }, "70%" to { percent(70) },
            )),
            weight(4f),
        )
        row.addView(
            group(context, "Kích thước", listOf(
                "Hẹp" to { resize(-2 * step, 0) }, "Rộng" to { resize(2 * step, 0) },
                "Thấp" to { resize(0, -2 * step) }, "Cao" to { resize(0, 2 * step) },
            )),
            weight(4f),
        )
        row.addView(
            group(context, "Vị trí", listOf(
                "◀" to { move(-step, 0) }, "▲" to { move(0, -step) },
                "▼" to { move(0, step) }, "▶" to { move(step, 0) },
            )),
            weight(4f),
        )
        row.addView(
            // Nhãn theo Ý ĐỊNH, không theo số kỹ thuật. v0.57 ghi rõ lý do: "－/＋" trên một đại lượng mà
            // tài liệu từng ghi ngược chiều là công thức chắc chắn gây nhầm. DPI cao = nội dung TO.
            group(context, "Nội dung", listOf(
                "nhỏ" to { dpi(-AppScale.STEP_DPI) }, "to" to { dpi(AppScale.STEP_DPI) },
            )),
            weight(2f),
        )
        row.addView(
            group(context, "Khôi phục", listOf(
                "↺" to { actions.setScale(entry.packageName, AppScale()); after() },
            )),
            weight(1.4f),
        )
        column.addView(row)

        return column
    }

    private fun summarise(actions: Actions, packageName: String): String {
        val scale = actions.scaleOf(packageName)
        val (w, h) = actions.clusterSize()
        if (scale.isAuto) return "Khung: full cụm ${w}×$h · DPI ${scale.dpi}"
        val bounds = scale.boundsOn(w, h)
        return "Khung: ${bounds[2] - bounds[0]}×${bounds[3] - bounds[1]} tại (${bounds[0]},${bounds[1]}) · DPI ${scale.dpi}"
    }

    private fun group(context: Context, title: String, buttons: List<Pair<String, () -> Unit>>): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 3), 0, dp(context, 3), 0)
        }
        column.addView(
            TextView(context).apply {
                text = title
                textSize = 12f
                setTextColor(0xFF5B6470.toInt())
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(context, 4))
            },
        )
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.forEach { (caption, action) ->
            row.addView(
                Button(context).apply {
                    text = caption
                    isAllCaps = false
                    minimumHeight = dp(context, 52)
                    minimumWidth = 0
                    contentDescription = "$title $caption"
                    setPadding(dp(context, 4), 0, dp(context, 4), 0)
                    setOnClickListener { action() }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(context, 3)
                },
            )
        }
        column.addView(row)
        return column
    }

    private fun weight(value: Float) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, value)

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density + .5f).toInt()
}

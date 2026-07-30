package com.byd.clusternav.modules.clustercast

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.byd.clusternav.modules.clustercast.AppScale

/**
 * Panel chỉnh kích thước/vị trí/DPI cho ĐÚNG MỘT app đang chiếu — v0.72 gọn: không còn danh sách app,
 * không còn tick chọn cho nút nổi (nút nổi giờ tự dò app đang mở, xem FloatingBubbleService), không còn
 * nút "Chiếu" riêng (chỉ chiếu qua nút nổi). Panel này disable hẳn khi không có app nào đang chiếu.
 *
 * docs/specs/cast-simplified-active-app-toggle.html §2.3.1.
 */
object CastAppRows {

    /** Gộp loạt nhấn dồn dập thành một lần áp. v0.3x ghi rõ đây là bản vá LAG trên xe. */
    const val APPLY_DEBOUNCE_MS = 320L

    /** Những gì panel cần từ bên ngoài. Không tự đọc gì để panel vẫn kiểm được và không giữ trạng thái. */
    interface Actions {
        fun scaleOf(packageName: String): AppScale
        fun setScale(packageName: String, scale: AppScale)

        /** Kích thước cụm tham chiếu để tính khung quanh tâm. */
        fun clusterSize(): Pair<Int, Int>

        /** Áp khung hiện tại của app lên cụm — gọi qua debounce, không gọi mỗi lần nhấn. */
        fun applySoon(packageName: String)
    }

    /**
     * Panel cho [packageName]/[label] đang chiếu, hoặc `null` → panel rỗng, mọi nút disabled.
     *
     * Bốn nhóm giữ đúng v0.3x vì nó dễ hiểu hơn tám nút cạnh: Kích thước nới/thu quanh tâm · Vị trí dời giữ
     * cỡ · DPI (số nhỏ = chữ to) · Khôi phục về full cụm.
     */
    fun build(context: Context, target: Pair<String, String>?, actions: Actions): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val title = TextView(context).apply {
            textSize = 14f
            setTextColor(0xFF1A1F24.toInt())
            setPadding(dp(context, 2), 0, dp(context, 2), dp(context, 4))
        }
        val summary = TextView(context).apply {
            textSize = 13f
            setTextColor(0xFF5B6470.toInt())
            setPadding(dp(context, 2), 0, dp(context, 2), dp(context, 8))
        }
        column.addView(title)
        column.addView(summary)

        val packageName = target?.first
        fun refreshText() {
            if (packageName == null) {
                title.text = "Chưa có app nào đang chiếu"
                summary.text = "Mở nút nổi để chiếu một app, khung sẽ hiện ở đây"
                return
            }
            title.text = "Cấu hình kích thước cho ${target.second}"
            summary.text = summarise(actions, packageName)
        }
        refreshText()

        fun after() {
            refreshText()
            if (packageName != null) actions.applySoon(packageName)
        }
        fun resize(deltaW: Int, deltaH: Int) {
            if (packageName == null) return
            val (w, h) = actions.clusterSize()
            actions.setScale(packageName, actions.scaleOf(packageName).nudgeRect(w, h, deltaW, deltaH))
            after()
        }
        fun move(dx: Int, dy: Int) {
            if (packageName == null) return
            val (w, h) = actions.clusterSize()
            actions.setScale(packageName, actions.scaleOf(packageName).nudgeMove(w, h, dx, dy))
            after()
        }
        fun dpi(delta: Int) {
            if (packageName == null) return
            actions.setScale(packageName, actions.scaleOf(packageName).nudgeDpi(delta))
            after()
        }
        fun reset() {
            if (packageName == null) return
            actions.setScale(packageName, AppScale())
            after()
        }

        val step = AppScale.STEP_WH
        val enabled = packageName != null
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            group(context, "Kích thước", enabled, listOf(
                "Hẹp" to { resize(-2 * step, 0) }, "Rộng" to { resize(2 * step, 0) },
                "Thấp" to { resize(0, -2 * step) }, "Cao" to { resize(0, 2 * step) },
            )),
            weight(4f),
        )
        row.addView(
            group(context, "Vị trí", enabled, listOf(
                "◀" to { move(-step, 0) }, "▲" to { move(0, -step) },
                "▼" to { move(0, step) }, "▶" to { move(step, 0) },
            )),
            weight(4f),
        )
        row.addView(
            // Nhãn theo Ý ĐỊNH, không theo số kỹ thuật. "－/＋" trên một đại lượng mà tài liệu từng ghi ngược
            // chiều là công thức chắc chắn gây nhầm. DPI cao = nội dung TO.
            group(context, "Cỡ chữ", enabled, listOf(
                "nhỏ" to { dpi(-AppScale.STEP_DPI) }, "to" to { dpi(AppScale.STEP_DPI) },
            )),
            weight(2f),
        )
        row.addView(
            group(context, "Khôi phục", enabled, listOf("↺" to { reset() })),
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

    private fun group(
        context: Context,
        title: String,
        enabled: Boolean,
        buttons: List<Pair<String, () -> Unit>>,
    ): View {
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
                    isEnabled = enabled
                    alpha = if (enabled) 1f else .5f
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

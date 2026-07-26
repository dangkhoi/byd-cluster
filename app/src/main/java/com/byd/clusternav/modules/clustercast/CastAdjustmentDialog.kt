package com.byd.clusternav.modules.clustercast

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.byd.clusternav.modules.clustercast.v2.AcceptedGeometry
import com.byd.clusternav.modules.clustercast.v2.AdjustmentDraft
import com.byd.clusternav.modules.clustercast.v2.CastRect

/** View-only geometry workspace. All persistence and mutations remain outside this class. */
object CastAdjustmentDialog {
    fun show(
        activity: Activity,
        draft: AdjustmentDraft,
        onApply: (AcceptedGeometry) -> Unit,
        onUndo: () -> Unit,
        onRestore: () -> Unit,
        onReset: () -> Unit,
        onDone: () -> Unit,
    ) {
        val geometry = draft.localDraft
        val b = geometry.bounds
        val summary = TextView(activity).apply {
            text = buildString {
                append("${draft.target.packageName}\n")
                append("Task ${draft.target.taskId} · ${draft.expectedDisplayIdentity}\n")
                append("Khung ${b.left},${b.top} → ${b.right},${b.bottom}\n")
                append("DPI ${geometry.densityDpi ?: "mặc định"} · ${geometry.profileId}\n")
                append("${draft.applyState}${draft.validationError?.let { " · $it" }.orEmpty()}")
            }
            textSize = 16f
            setTextColor(Color.rgb(26, 31, 36))
            setPadding(dp(activity, 20), dp(activity, 16), dp(activity, 20), dp(activity, 16))
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(GeometryPreview(activity, draft), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 220),
            ))
            addView(summary)
        }
        val labels = arrayOf(
            "Preset 100%", "Preset 90%", "Preset 80%", "Preset 70%",
            "Hẹp hơn", "Rộng hơn", "Thấp hơn", "Cao hơn",
            "Sang trái", "Sang phải", "Lên", "Xuống",
            "DPI −", "DPI +", "Hoàn tác", "Khôi phục lúc mở", "Reset mặc định", "Xong",
        )
        AlertDialog.Builder(activity)
            .setTitle("Điều chỉnh Cluster Cast")
            .setView(container)
            .setItems(labels) { _, index ->
                when (index) {
                    0 -> onApply(scale(draft.entrySnapshot, 100))
                    1 -> onApply(scale(draft.entrySnapshot, 90))
                    2 -> onApply(scale(draft.entrySnapshot, 80))
                    3 -> onApply(scale(draft.entrySnapshot, 70))
                    4 -> onApply(geometry.copy(bounds = resize(b, -40, 0)))
                    5 -> onApply(geometry.copy(bounds = resize(b, 40, 0)))
                    6 -> onApply(geometry.copy(bounds = resize(b, 0, -40)))
                    7 -> onApply(geometry.copy(bounds = resize(b, 0, 40)))
                    8 -> onApply(geometry.copy(bounds = move(b, -20, 0)))
                    9 -> onApply(geometry.copy(bounds = move(b, 20, 0)))
                    10 -> onApply(geometry.copy(bounds = move(b, 0, -20)))
                    11 -> onApply(geometry.copy(bounds = move(b, 0, 20)))
                    12 -> onApply(geometry.copy(densityDpi = ((geometry.densityDpi ?: 160) - 10).coerceAtLeast(72)))
                    13 -> onApply(geometry.copy(densityDpi = ((geometry.densityDpi ?: 160) + 10).coerceAtMost(640)))
                    14 -> onUndo()
                    15 -> onRestore()
                    16 -> onReset()
                    17 -> onDone()
                }
            }
            .setNegativeButton("Tiếp tục sau", null)
            .show()
    }


    private class GeometryPreview(activity: Activity, private val draft: AdjustmentDraft) : View(activity) {
        private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(45, 55, 65); style = Paint.Style.FILL
        }
        private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(60, 145, 245); style = Paint.Style.FILL
        }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(activity, 2).toFloat()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val pad = dp(context as Activity, 18).toFloat()
            val left = pad
            val top = pad
            val right = width - pad
            val bottom = height - pad
            canvas.drawRoundRect(left, top, right, bottom, 18f, 18f, outer)
            val base = draft.entrySnapshot.bounds
            val current = draft.localDraft.bounds
            val baseWidth = (base.right - base.left).coerceAtLeast(1).toFloat()
            val baseHeight = (base.bottom - base.top).coerceAtLeast(1).toFloat()
            fun x(value: Int) = left + ((value - base.left) / baseWidth).coerceIn(0f, 1f) * (right - left)
            fun y(value: Int) = top + ((value - base.top) / baseHeight).coerceIn(0f, 1f) * (bottom - top)
            val il = x(current.left); val it = y(current.top)
            val ir = x(current.right); val ib = y(current.bottom)
            canvas.drawRect(il, it, ir, ib, inner)
            canvas.drawRect(il, it, ir, ib, border)
        }
    }

    private fun scale(entry: AcceptedGeometry, percent: Int): AcceptedGeometry {
        val b = entry.bounds
        val width = ((b.right - b.left) * percent / 100).coerceAtLeast(160)
        val height = ((b.bottom - b.top) * percent / 100).coerceAtLeast(160)
        val cx = (b.left + b.right) / 2
        val cy = (b.top + b.bottom) / 2
        return entry.copy(bounds = CastRect(cx - width / 2, cy - height / 2, cx + width / 2, cy + height / 2))
    }

    private fun resize(b: CastRect, dx: Int, dy: Int): CastRect {
        val width = (b.right - b.left + dx).coerceAtLeast(160)
        val height = (b.bottom - b.top + dy).coerceAtLeast(160)
        val cx = (b.left + b.right) / 2
        val cy = (b.top + b.bottom) / 2
        return CastRect((cx - width / 2).coerceAtLeast(0), (cy - height / 2).coerceAtLeast(0), cx + width / 2, cy + height / 2)
    }

    private fun move(b: CastRect, dx: Int, dy: Int): CastRect {
        val safeDx = if (b.left + dx < 0) -b.left else dx
        val safeDy = if (b.top + dy < 0) -b.top else dy
        return CastRect(b.left + safeDx, b.top + safeDy, b.right + safeDx, b.bottom + safeDy)
    }

    private fun dp(activity: Activity, value: Int) =
        (value * activity.resources.displayMetrics.density + .5f).toInt()
}

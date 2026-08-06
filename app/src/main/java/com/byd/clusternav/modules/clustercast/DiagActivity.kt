package com.byd.clusternav.modules.clustercast

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.byd.clusternav.Lang
import com.byd.clusternav.modules.clustercast.simplified.SimpleCastRuntime
import com.byd.clusternav.modules.clustercast.simplified.SimpleCastState

/**
 * Read-only Cast diagnostics screen.
 *
 * V2 (CastAndroidRuntime/CastFacade) removed — reads directly from SimpleCastRuntime state.
 * Shows current state, coordinator info, and last mutation outcome if available.
 */
class DiagActivity : Activity() {
    private lateinit var report: TextView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = { value: Int -> (value * resources.displayMetrics.density + .5f).toInt() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
            setBackgroundColor(0xFFF4F6F8.toInt())
        }
        root.addView(TextView(this).apply {
            text = "Cluster Cast · Chẩn đoán"
            textSize = 22f
            setTextColor(Color.rgb(26, 31, 36))
        })
        root.addView(Button(this).apply {
            text = "Làm mới"
            isAllCaps = false
            minimumHeight = dp(48)
            setOnClickListener { refresh() }
        })
        root.addView(Button(this).apply {
            text = "Sao chép báo cáo"
            isAllCaps = false
            minimumHeight = dp(48)
            setOnClickListener { copyReport() }
        })
        root.addView(Button(this).apply {
            text = Lang.t("⬇ Kiểm tra cập nhật", "⬇ Check update")
            isAllCaps = false
            minimumHeight = dp(48)
            setOnClickListener { com.byd.clusternav.UpdateFlow.start(this@DiagActivity) { t, warn -> setStatus(t, warn) } }
        })
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(0, 105, 92))
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(status)
        report = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(report)
        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    override fun onDestroy() { super.onDestroy() }

    private fun refresh() {
        val coordinator = SimpleCastRuntime.coordinator(applicationContext)
        val state = coordinator.state
        val value = buildString {
            appendLine("── Simplified Cast Diagnostics ──")
            appendLine()
            appendLine("state=$state")
            appendLine("architecture=SimplifiedV1 (single coordinator, no V2)")
            appendLine()

            // Display coordinator details
            when (state) {
                is SimpleCastState.CastingFull -> {
                    appendLine("target=${state.targetPkg}")
                    appendLine("appType=${state.appType}")
                    appendLine("displayConfig.wmSize=${state.displayConfig.wmSize}")
                    appendLine("displayConfig.overscan=${state.displayConfig.overscan}")
                    appendLine("displayConfig.density=${state.displayConfig.density}")
                    state.taskId?.let { appendLine("taskId=$it") }
                }
                is SimpleCastState.CastingSplit -> {
                    state.left?.let {
                        appendLine("left.pkg=${it.pkg}")
                        appendLine("left.wmSize=${it.displayConfig.wmSize}")
                    }
                    state.right?.let {
                        appendLine("right.pkg=${it.pkg}")
                        appendLine("right.wmSize=${it.displayConfig.wmSize}")
                    }
                }
                is SimpleCastState.Error -> {
                    appendLine("error=${state.message}")
                }
                else -> { /* no extra details for Off/Opening/Idle/Stopping/Closing */ }
            }

            appendLine()
            appendLine("── prefs ──")
            val prefs = coordinator.prefs
            appendLine("autoStart=${prefs.autoStartEnabled()}")
            appendLine("autoStartPkg=${prefs.autoStartPackage() ?: "(none)"}")
            appendLine("autoStartSplit=${prefs.autoStartSplitEnabled()}")
            appendLine("splitRatioLeft=${prefs.splitRatioLeftPercent()}%")
            appendLine("dozeWhitelist=${prefs.dozeWhitelistApplied()}")
        }
        report.text = value
    }

    private fun copyReport() {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Cluster Cast diagnostics", report.text))
        android.widget.Toast.makeText(this, "Đã sao chép", android.widget.Toast.LENGTH_SHORT).show()
    }

    // ─── Check-for-update status sink ────────────────────────────────────────
    // The interactive check → confirm → download → install flow lives in the shared [UpdateFlow]
    // helper (invoked by the button above and by MainActivity). This screen only renders status.
    private fun setStatus(text: String, warn: Boolean = false) {
        status.text = text
        status.setTextColor(if (warn) Color.rgb(176, 0, 32) else Color.rgb(0, 105, 92))
    }
}

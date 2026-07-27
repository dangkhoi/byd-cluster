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
import com.byd.clusternav.modules.clustercast.v2.CastAndroidRuntime

/** Read-only Cast V2 diagnostics. This screen intentionally exports no repair/reset mutation. */
class DiagActivity : Activity() {
    private lateinit var runtime: CastAndroidRuntime.Runtime
    private lateinit var report: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime = CastAndroidRuntime.create(applicationContext)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
            setBackgroundColor(0xFFF4F6F8.toInt())
        }
        root.addView(TextView(this).apply { text = "Cluster Cast V2 · Chẩn đoán"; textSize = 22f; setTextColor(Color.rgb(26, 31, 36)) })
        root.addView(Button(this).apply { text = "Làm mới"; isAllCaps = false; setOnClickListener { refresh() } })
        root.addView(Button(this).apply { text = "Sao chép báo cáo"; isAllCaps = false; setOnClickListener { copyReport() } })
        report = TextView(this).apply { textSize = 13f; setTextColor(Color.DKGRAY); setTextIsSelectable(true); setPadding(0, dp(12), 0, 0) }
        root.addView(report)
        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    override fun onDestroy() { super.onDestroy() }

    private fun refresh() {
        report.text = "Đang đọc…"
        Thread {
            val facade = CastFacade.wrapping(runtime)
            val observation = facade.observe()
            val value = buildString {
                appendLine("mode=READ_ONLY")
                appendLine("observation=$observation")
                val e = facade.envelope()
                if (e == null) {
                    appendLine(facade.storeStatusLine())
                } else {
                    appendLine("schema=${e.schemaVersion}")
                    appendLine("durableEpoch=${e.durableEpoch}")
                    appendLine("effectiveUi=${e.effectiveUiVersion}")
                    appendLine("transaction=${e.transaction}")
                    appendLine("stableSession=${e.stableSession}")
                    appendLine("ledgerEntries=${e.transaction?.ledger?.size ?: 0}")
                    e.transaction?.ledger?.forEach { appendLine("ledger=${it.effect}:${it.commandKind}:${it.stepId}") }
                }
                appendLine()
                appendLine("── nhật ký thao tác (mới nhất ở dưới) ──")
                val log = facade.operationLog()
                appendLine(if (log.isBlank()) "(chưa có thao tác nào trong tiến trình này)" else log)
            }
            runOnUiThread { report.text = value }
        }.start()
    }

    private fun copyReport() {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Cluster Cast V2 diagnostics", report.text))
        android.widget.Toast.makeText(this, "Đã sao chép", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
}

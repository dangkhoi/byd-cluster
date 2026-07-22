package com.byd.clusternav.modules.clustercast

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * ★ MÀN CHẨN ĐOÁN (v0.42) — MỘT CHỖ DUY NHẤT cho mọi thứ kỹ thuật.
 *
 * VÌ SAO TÁCH RA: màn "Chiếu app lên cụm" đang lẫn cả nút điều khiển (người dùng thường) lẫn log kỹ thuật
 * (chỉ dev cần) → người dùng rối. Và khi anh em báo lỗi thì cần một thứ CHỤP MÀN HÌNH LÀ ĐỦ, không phải
 * hướng dẫn từng bước gõ adb — nhất là khi cắm CarPlay/AA thì đầu xe TẮT WIFI, adb ngoài không vào được.
 *
 * Màn này chỉ ĐỌC, không đổi gì trên xe. Chữ chọn được (copy) và có nút chia sẻ file.
 */
class DiagActivity : Activity() {

    private lateinit var out: TextView
    private lateinit var status: TextView

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        ClusterCast.loadPrefs(this)
        // ★ W2-5: đo kích cụm THẬT ngay khi mở màn — để nút chỉnh khung không tính theo con số đoán
        ClusterCast.measureClusterInProcess(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(0xFFF4F6F8.toInt())
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(TextView(this).apply {
            text = "Chẩn đoán · gửi cho dev"; textSize = 20f; setTextColor(0xFF1A1F24.toInt())
        })
        root.addView(TextView(this).apply {
            text = "Bấm CHỤP, đợi vài giây rồi CHỤP MÀN HÌNH gửi cho dev. Không cần WiFi, không cần adb.\n" +
                "Muốn gửi đầy đủ hơn thì bấm CHIA SẺ FILE."
            textSize = 13f; setTextColor(0xFF5B6470.toInt()); setPadding(0, dp(4), 0, dp(12))
        })

        status = TextView(this).apply { textSize = 14f; setTextColor(0xFF1B7A34.toInt()); setPadding(0, 0, 0, dp(8)) }
        root.addView(status)

        root.addView(bigBtn("CHỤP CHẨN ĐOÁN", 0xFF1565C0.toInt()) { capture() })
        root.addView(bigBtn("CHIA SẺ FILE ĐẦY ĐỦ", 0xFF5B6470.toInt()) { share() })

        // ── Đường trả lại cho state sống ngoài tiến trình (§5) — sửa lỗi "app bị scale ở màn chính" ──
        // Lối vào DUY NHẤT của máy dò. Nó bật MẶC ĐỊNH mỗi lần nổ máy — bật sẵn mà không có nút xem/tắt
        // thì không được phép ship.
        root.addView(bigBtn("🔬 MÁY DÒ DẪN ĐƯỜNG (đang tự bật mỗi chuyến)", 0xFF7B4EA8.toInt()) {
            runCatching { startActivity(Intent(this, com.byd.clusternav.modules.navprobe.NavProbeActivity::class.java)) }
                .onFailure { status.text = "không mở được: ${it.message}" }
        })

        root.addView(bigBtn("GỠ CHẾ ĐỘ CỬA SỔ NỔI (nếu app bị scale ở màn chính)", 0xFF9A3412.toInt()) {
            status.text = "đang gỡ…"; status.setTextColor(0xFF5B6470.toInt())
            ClusterCast.unseedFreeform(applicationContext) { line ->
                runOnUiThread { status.text = line; status.setTextColor(0xFF2C6E49.toInt()) }
            }
        })
        root.addView(TextView(this).apply {
            text = (if (ClusterCast.freeformSeeded(this@DiagActivity)) "⚠ Cờ cửa sổ nổi ĐANG BẬT. " else "") +
                "Dùng khi Vietmap (hoặc app khác) bị thu nhỏ/lệch trên MÀN HÌNH GIỮA. " +
                "Gỡ xong phải TẮT MÁY XE hẳn 1 lần rồi mở lại mới hết. " +
                "Đánh đổi: chỉnh kích thước trên cụm sẽ dùng đường wm size/overscan thay vì resize mượt."
            textSize = 12f; setTextColor(0xFF8A6D3B.toInt()); setPadding(dp(2), dp(6), dp(2), 0)
        })

        out = TextView(this).apply {
            textSize = 12f; setTextColor(Color.parseColor("#1A1F24")); setBackgroundColor(Color.parseColor("#F1EFE8"))
            setPadding(dp(12), dp(12), dp(12), dp(12)); setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            text = summaryNow()
        }
        root.addView(out, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) })
    }

    override fun onResume() { super.onResume(); out.text = summaryNow() }

    /** Ảnh chụp trạng thái ĐỌC TỪ APP (không cần shell) — luôn hiện sẵn, không phải bấm gì. */
    private fun summaryNow(): String {
        val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        val p = ClusterProfile.resolve(this)
        val sb = StringBuilder()
        sb.appendLine("ClusterNav v$ver")
        sb.appendLine("xe: ${android.os.Build.MODEL} / ${android.os.Build.BRAND} · Android ${android.os.Build.VERSION.RELEASE}")
        sb.appendLine("hồ sơ cụm: ${p.summary()}")
        sb.appendLine("cụm đo được: ${if (ClusterCast.lastClusterW > 0) "${ClusterCast.lastClusterW}x${ClusterCast.lastClusterH}" else "chưa chiếu lần nào"}")
        sb.appendLine("đang chiếu: ${if (ClusterCast.casting) ClusterCast.lastCastApp.ifBlank { "?" } else "không"} · VD=${ClusterCast.lastDisplayId}")
        sb.appendLine("tự chiếu khi khởi động: ${ClusterCast.autoCastPkg.ifBlank { "tắt" }}")
        sb.appendLine()
        sb.appendLine("— cấu hình từng app —")
        val pkgs = (ClusterCast.castableApps + ClusterCast.autoCastPkg).filter { it.isNotBlank() }.distinct()
        if (pkgs.isEmpty()) sb.appendLine("(chưa tick app nào)")
        for (k in pkgs) {
            val s = ClusterCast.scaleOf(k)
            val mode = when {
                ClusterCast.isKeepSession(k) -> "◈giữ-phiên"
                ClusterCast.isT3(k) -> "⊞ép-freeform"
                else -> "thường"
            }
            sb.appendLine("$k")
            sb.appendLine("   ${if (s.isAuto) "auto" else "${s.rectR - s.rectL}x${s.rectB - s.rectT}@(${s.rectL},${s.rectT})"}" +
                " · dpi ${s.dpi} · ${if (ClusterCast.isRectProfile(k)) "▭thẳng" else "◠cong"} · $mode")
        }
        sb.appendLine()
        sb.appendLine("— file log đã lưu —")
        val dir = java.io.File(getExternalFilesDir(null), "diag")
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) sb.appendLine("(chưa có — bấm CHỤP CHẨN ĐOÁN)")
        else files.take(5).forEach { sb.appendLine("${it.name}  (${it.length() / 1024}KB)") }
        return sb.toString()
    }

    private fun capture() {
        val pkg = ClusterCast.lastCastApp.ifBlank { ClusterCast.castableApps.firstOrNull() ?: packageName }
        status.text = "⏳ đang chụp $pkg …"; status.setTextColor(0xFFB25000.toInt())
        val stamp = java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        Thread {
            val (path, sum) = ClusterDiag.capture(applicationContext, pkg, ClusterCast.lastDisplayId, stamp)
            runOnUiThread {
                status.text = "✅ xong — chụp màn hình gửi dev là đủ"; status.setTextColor(0xFF1B7A34.toInt())
                out.text = summaryNow() + "\n— KẾT QUẢ CHỤP —\n" + sum + "\n\n→ $path"
            }
        }.start()
    }

    /**
     * Gửi NỘI DUNG file mới nhất dưới dạng text qua bất kỳ app nào trên xe (Zalo/mail/Drive…) — khỏi cần adb pull.
     * Cố ý KHÔNG dùng FileProvider: dự án không có androidx.core, mà thêm thư viện chỉ để gửi một file text thì
     * không đáng. EXTRA_TEXT chịu được cỡ file log này (vài chục KB), cắt bớt cho chắc để không vỡ binder.
     */
    private fun share() {
        val dir = java.io.File(getExternalFilesDir(null), "diag")
        val f = dir.listFiles()?.maxByOrNull { it.lastModified() }
        if (f == null) { status.text = "chưa có file — bấm CHỤP trước"; status.setTextColor(0xFFB25000.toInt()); return }
        runCatching {
            val body = f.readText().let { if (it.length > MAX_SHARE_CHARS) it.take(MAX_SHARE_CHARS) + "\n…(cắt bớt, file đầy đủ ở ${f.absolutePath})" else it }
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "ClusterNav diag ${f.name}")
                putExtra(Intent.EXTRA_TEXT, body)
            }, "Gửi log cho dev"))
        }.onFailure {
            status.text = "không chia sẻ được: ${it.message}\nFile ở: ${f.absolutePath}"
            status.setTextColor(0xFFB25000.toInt())
        }
    }

    private companion object { const val MAX_SHARE_CHARS = 120_000 }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun bigBtn(t: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 15f; minHeight = dp(52); setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(color) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) }
        setOnClickListener { runCatching(onClick) }
    }
}

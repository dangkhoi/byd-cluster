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
        com.byd.clusternav.Lang.load(this)
        ClusterCast.loadPrefs(this)
        // ★ W2-5: đo kích cụm THẬT ngay khi mở màn — để nút chỉnh khung không tính theo con số đoán
        ClusterCast.measureClusterInProcess(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(0xFFF4F6F8.toInt())
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(TextView(this).apply {
            text = com.byd.clusternav.Lang.t("Chẩn đoán · gửi cho dev", "Diagnostics · send to dev"); textSize = 20f; setTextColor(0xFF1A1F24.toInt())
        })
        root.addView(TextView(this).apply {
            text = com.byd.clusternav.Lang.t(
                "Bấm CHỤP, đợi vài giây rồi CHỤP MÀN HÌNH gửi cho dev. Không cần WiFi, không cần adb.\n" +
                    "Muốn gửi đầy đủ hơn thì bấm CHIA SẺ FILE.",
                "Press CAPTURE, wait a few seconds then take a SCREENSHOT and send it to dev. No WiFi, no adb needed.\n" +
                    "To send more complete data, press SHARE FILE.")
            textSize = 13f; setTextColor(0xFF5B6470.toInt()); setPadding(0, dp(4), 0, dp(12))
        })

        status = TextView(this).apply { textSize = 14f; setTextColor(0xFF1B7A34.toInt()); setPadding(0, 0, 0, dp(8)) }
        root.addView(status)

        // ── Ngôn ngữ + Cập nhật (một hàng) ──
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(bigBtn(com.byd.clusternav.Lang.t("🌐 ${com.byd.clusternav.Lang.cur(this@DiagActivity).label} → đổi", "🌐 ${com.byd.clusternav.Lang.cur(this@DiagActivity).label} → change"), 0xFF455A64.toInt()) {
                com.byd.clusternav.Lang.toggle(this@DiagActivity); recreate()
            }.apply { (layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; rightMargin = dp(4) } })
            addView(bigBtn(com.byd.clusternav.Lang.t("⬇ Kiểm tra cập nhật", "⬇ Check update"), 0xFF00695C.toInt()) { checkUpdate() }
                .apply { (layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; leftMargin = dp(4) } })
        })

        root.addView(bigBtn(com.byd.clusternav.Lang.t("CHỤP CHẨN ĐOÁN", "CAPTURE DIAGNOSTICS"), 0xFF1565C0.toInt()) { capture() })
        root.addView(bigBtn(com.byd.clusternav.Lang.t("CHIA SẺ FILE ĐẦY ĐỦ", "SHARE FULL FILE"), 0xFF5B6470.toInt()) { share() })

        // ── Đường trả lại cho state sống ngoài tiến trình (§5) — sửa lỗi "app bị scale ở màn chính" ──
        // Lối vào DUY NHẤT của máy dò. Nó bật MẶC ĐỊNH mỗi lần nổ máy — bật sẵn mà không có nút xem/tắt
        // thì không được phép ship.
        root.addView(bigBtn(com.byd.clusternav.Lang.t("🔬 MÁY DÒ DẪN ĐƯỜNG (đang tự bật mỗi chuyến)", "🔬 NAVIGATION PROBE (auto-on every trip)"), 0xFF7B4EA8.toInt()) {
            runCatching { startActivity(Intent(this, com.byd.clusternav.modules.navprobe.NavProbeActivity::class.java)) }
                .onFailure { status.text = com.byd.clusternav.Lang.t("không mở được: ${it.message}", "can't open: ${it.message}") }
        })

        root.addView(bigBtn(com.byd.clusternav.Lang.t("GỠ CHẾ ĐỘ CỬA SỔ NỔI (nếu app bị scale ở màn chính)", "REMOVE FLOATING-WINDOW MODE (if the app is scaled on the center screen)"), 0xFF9A3412.toInt()) {
            status.text = com.byd.clusternav.Lang.t("đang gỡ…", "removing…"); status.setTextColor(0xFF5B6470.toInt())
            ClusterCast.unseedFreeform(applicationContext) { line ->
                runOnUiThread { status.text = line; status.setTextColor(0xFF2C6E49.toInt()) }
            }
        })
        root.addView(TextView(this).apply {
            text = (if (ClusterCast.freeformSeeded(this@DiagActivity)) com.byd.clusternav.Lang.t("⚠ Cờ cửa sổ nổi ĐANG BẬT. ", "⚠ Floating-window flag is ON. ") else "") +
                com.byd.clusternav.Lang.t("Dùng khi Vietmap (hoặc app khác) bị thu nhỏ/lệch trên MÀN HÌNH GIỮA. ", "Use when Vietmap (or another app) is shrunk/misaligned on the CENTER SCREEN. ") +
                com.byd.clusternav.Lang.t("Gỡ xong phải TẮT MÁY XE hẳn 1 lần rồi mở lại mới hết. ", "After removing, you must FULLY POWER OFF THE CAR once then reopen for it to take effect. ") +
                com.byd.clusternav.Lang.t("Đánh đổi: chỉnh kích thước trên cụm sẽ dùng đường wm size/overscan thay vì resize mượt.", "Trade-off: adjusting size on the cluster will use the wm size/overscan path instead of smooth resize.")
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

    /** Kiểm tra GitHub có bản mới không → hỏi → tải → cài qua dadb. Toàn bộ I/O chạy nền. */
    private fun checkUpdate() {
        val L = com.byd.clusternav.Lang
        status.text = L.t("đang kiểm tra GitHub…", "checking GitHub…"); status.setTextColor(0xFF5B6470.toInt())
        Thread {
            val r = com.byd.clusternav.UpdateChecker.check(applicationContext)
            runOnUiThread {
                when {
                    r.error != null ->
                        setStatus(L.t("không kiểm tra được: ${r.error}", "check failed: ${r.error}"), warn = true)
                    !r.hasUpdate ->
                        setStatus(L.t("đang dùng bản mới nhất (v${r.current})", "up to date (v${r.current})"))
                    else -> confirmUpdate(r.current, r.latest!!, r.downloadUrl!!)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun confirmUpdate(cur: String, latest: String, url: String) {
        val L = com.byd.clusternav.Lang
        android.app.AlertDialog.Builder(this)
            .setTitle(L.t("Có bản mới: v$latest", "New version: v$latest"))
            .setMessage(L.t("Đang dùng v$cur. Tải v$latest và cài đè? App sẽ tự khởi động lại.",
                "You have v$cur. Download v$latest and install? The app will restart."))
            .setPositiveButton(L.t("Tải & cài", "Download & install")) { _, _ -> doUpdate(url) }
            .setNegativeButton(L.t("Để sau", "Later"), null)
            .show()
    }

    private fun doUpdate(url: String) {
        val L = com.byd.clusternav.Lang
        setStatus(L.t("đang tải… 0%", "downloading… 0%"))
        Thread {
            val f = com.byd.clusternav.UpdateChecker.download(applicationContext, url) { pct ->
                runOnUiThread {
                    status.text = if (pct < 0) L.t("đang tải…", "downloading…") else L.t("đang tải… $pct%", "downloading… $pct%")
                }
            }
            if (f == null) { runOnUiThread { setStatus(L.t("tải thất bại", "download failed"), warn = true) }; return@Thread }
            runOnUiThread { setStatus(L.t("đang cài…", "installing…")) }
            val msg = com.byd.clusternav.UpdateChecker.install(applicationContext, f)
            runOnUiThread { setStatus(msg, warn = msg.contains("thất bại") || msg.contains("fail")) }
        }.apply { isDaemon = true }.start()
    }

    private fun setStatus(msg: String, warn: Boolean = false) {
        status.text = msg; status.setTextColor(if (warn) 0xFFB25000.toInt() else 0xFF1B7A34.toInt())
    }

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
        status.text = com.byd.clusternav.Lang.t("⏳ đang chụp $pkg …", "⏳ capturing $pkg …"); status.setTextColor(0xFFB25000.toInt())
        val stamp = java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        Thread {
            val (path, sum) = ClusterDiag.capture(applicationContext, pkg, ClusterCast.lastDisplayId, stamp)
            runOnUiThread {
                status.text = com.byd.clusternav.Lang.t("✅ xong — chụp màn hình gửi dev là đủ", "✅ done — a screenshot to dev is enough"); status.setTextColor(0xFF1B7A34.toInt())
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
        if (f == null) { status.text = com.byd.clusternav.Lang.t("chưa có file — bấm CHỤP trước", "no file yet — press CAPTURE first"); status.setTextColor(0xFFB25000.toInt()); return }
        runCatching {
            val body = f.readText().let { if (it.length > MAX_SHARE_CHARS) it.take(MAX_SHARE_CHARS) + "\n…(cắt bớt, file đầy đủ ở ${f.absolutePath})" else it }
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "ClusterNav diag ${f.name}")
                putExtra(Intent.EXTRA_TEXT, body)
            }, com.byd.clusternav.Lang.t("Gửi log cho dev", "Send log to dev")))
        }.onFailure {
            status.text = com.byd.clusternav.Lang.t("không chia sẻ được: ${it.message}\nFile ở: ${f.absolutePath}", "can't share: ${it.message}\nFile at: ${f.absolutePath}")
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

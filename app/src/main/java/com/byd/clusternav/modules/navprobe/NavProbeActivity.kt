package com.byd.clusternav.modules.navprobe

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.byd.clusternav.Lang

/**
 * Màn hình DUY NHẤT của máy dò — tách khỏi màn Chẩn đoán để xoá module không phải đụng vào đó.
 *
 * Người dùng chỉ cần: bật → lái một chuyến có dẫn đường → về bấm CHIA SẺ. Mọi thứ kỹ thuật gom hết vào đây.
 * Hiển thị thẳng trạng thái hai quyền, vì thiếu quyền là cả chuyến đi công cốc mà không hề có dấu hiệu gì.
 */
class NavProbeActivity : Activity() {

    private lateinit var out: TextView
    private lateinit var toggle: Button
    private var autoBtn: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = Lang.t("MÁY DÒ DẪN ĐƯỜNG", "NAVIGATION PROBE")
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = Lang.t(
                "Công cụ khảo sát: xem có lấy được cự ly / hướng rẽ / tên đường từ Vietmap, Waze, " +
                    "CarPlay, Android Auto không. Bật → lái một chuyến CÓ DẪN ĐƯỜNG → về bấm CHIA SẺ.",
                "Survey tool: check whether distance / turn direction / road name can be captured from " +
                    "Vietmap, Waze, CarPlay, Android Auto. Turn on → drive a trip WITH NAVIGATION → come back and tap SHARE.")
            textSize = 13f
            setPadding(0, dp(6), 0, dp(12))
        })

        toggle = bigBtn("", 0xFF7B4EA8.toInt()) {
            val turningOn = !NavProbe.isOn(this)
            // tắt tay thì PHẢI được nhớ, không thì rời màn quay lại là bị bật lại sau lưng
            if (turningOn) NavProbe.setOn(this, true) else NavProbe.stopByUser(this)
            refresh()
            Toast.makeText(this, when {
                !turningOn -> Lang.t("đã tắt", "turned off")
                !NavProbe.notifGranted(this) && !NavProbeAccessibility.connected ->
                    Lang.t(
                        "⚠ CHƯA CẤP QUYỀN NÀO — sẽ không bắt được gì. Cấp hai quyền bên dưới rồi bật lại.",
                        "⚠ NO PERMISSION GRANTED — nothing will be captured. Grant both permissions below then turn on again.")
                !NavProbeAccessibility.connected ->
                    Lang.t(
                        "⚠ Thiếu quyền đọc màn hình — đây là kênh quan trọng nhất. Cấp rồi bật lại.",
                        "⚠ Missing screen-reading permission — this is the most important channel. Grant it then turn on again.")
                else -> Lang.t(
                    "ĐANG GHI (tự tắt sau 2 giờ). Lái một chuyến có dẫn đường rồi CHIA SẺ.",
                    "RECORDING (auto-off after 2 hours). Drive a trip with navigation then SHARE.")
            }, Toast.LENGTH_LONG).show()
        }
        root.addView(toggle)

        root.addView(bigBtn(Lang.t("Cấp quyền ĐỌC MÀN HÌNH (quan trọng nhất)", "Grant SCREEN-READING permission (most important)"), 0xFF2C6E49.toInt()) {
            openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        })
        root.addView(bigBtn(Lang.t("Cấp quyền ĐỌC THÔNG BÁO", "Grant NOTIFICATION-READING permission"), 0xFF2C6E49.toInt()) {
            openSettings("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        })
        root.addView(bigBtn(Lang.t("📸 CHỤP NGAY trạng thái CP/AA (bấm khi ĐANG cắm)", "📸 CAPTURE CP/AA status NOW (tap while plugged in)"), 0xFF2C6E49.toInt()) {
            NavProbeSnap.capture(applicationContext, "bấm tay")
            Toast.makeText(this, Lang.t("đang chụp qua loopback — không cần WiFi", "capturing over loopback — no WiFi needed"), Toast.LENGTH_SHORT).show()
        })
        root.addView(bigBtn(Lang.t("XEM TRƯỚC (đọc kỹ rồi hãy chia sẻ)", "PREVIEW (read carefully before sharing)"), 0xFF5B6470.toInt()) {
            val f = NavProbe.latestFile(this)
            out.text = if (f == null) Lang.t("chưa có dữ liệu", "no data yet")
                       else NavProbe.digest(f.readText(), MAX_SHARE_CHARS).take(20000)
        })
        root.addView(bigBtn(Lang.t("CHIA SẺ KẾT QUẢ", "SHARE RESULTS"), 0xFF5B6470.toInt()) { share() })

        autoBtn = bigBtn("", 0xFF5B6470.toInt()) {
            val next = !NavProbe.autoArmEnabled(this)
            NavProbe.setAutoArm(this, next)
            if (next) com.byd.clusternav.RebindReceiver.armProbe(applicationContext)
            refresh()
            Toast.makeText(this, if (next) Lang.t("sẽ tự bật mỗi lần nổ máy", "will auto-start every ignition") else Lang.t("sẽ KHÔNG tự bật nữa", "will NOT auto-start anymore"), Toast.LENGTH_LONG).show()
        }
        root.addView(autoBtn)

        root.addView(bigBtn(Lang.t("GỠ HẲN QUYỀN CỦA MÁY DÒ", "FULLY REMOVE PROBE PERMISSIONS"), 0xFF9A3412.toInt()) {
            NavProbe.setAutoArm(this, false)
            NavProbe.setOn(this, false)
            Thread {
                val msg = runCatching {
                    dadb.Dadb.create("localhost", 5555, com.byd.clusternav.AdbKeys.ensure(applicationContext)).use { adb ->
                        NavProbeArm.disarm(applicationContext) { c -> adb.shell(c).output.trim() }
                    }
                }.getOrElse { Lang.t("không gỡ được qua dadb: ${it.message}\nGỡ tay trong Cài đặt → Trợ năng / Thông báo.", "couldn't remove via dadb: ${it.message}\nRemove manually in Settings → Accessibility / Notifications.") }
                runOnUiThread { refresh(); Toast.makeText(this, Lang.t("đã tắt máy dò\n$msg", "navigation probe turned off\n$msg"), Toast.LENGTH_LONG).show() }
            }.apply { isDaemon = true }.start()
        })

        root.addView(TextView(this).apply {
            text = Lang.t(
                "⚠ Khi bật, máy dò ghi lại nguyên văn thông báo của MỌI ứng dụng (có thể gồm tin nhắn) " +
                    "và chữ đang hiện trên màn hình của các app dẫn đường. Tự tắt sau 2 giờ. " +
                    "Xem file trước khi gửi nếu thấy cần.",
                "⚠ When on, the probe records the verbatim notifications of ALL apps (may include messages) " +
                    "and the text currently shown on screen by navigation apps. Auto-off after 2 hours. " +
                    "Review the file before sending if needed.")
            textSize = 12f
            setTextColor(0xFF8A6D3B.toInt())
            setPadding(0, dp(10), 0, dp(10))
        })

        out = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        root.addView(out)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        // Mở màn này là lên nòng luôn nếu chưa — người dùng vào đây tức là đang muốn đo.
        refresh()
    }

    private fun refresh() {
        val on = NavProbe.isOn(this)
        toggle.text = if (on) Lang.t("⏺ ĐANG GHI — còn ${NavProbe.expiresInMin(this)} phút · chạm để dừng", "⏺ RECORDING — ${NavProbe.expiresInMin(this)} min left · tap to stop")
                      else Lang.t("▶ BẮT ĐẦU DÒ", "▶ START PROBING")
        autoBtn?.text = if (NavProbe.autoArmEnabled(this)) Lang.t("⚙ TỰ BẬT mỗi lần nổ máy: ĐANG BẬT — chạm để tắt", "⚙ AUTO-START every ignition: ON — tap to turn off")
                        else Lang.t("⚙ Tự bật mỗi lần nổ máy: tắt — chạm để bật", "⚙ Auto-start every ignition: off — tap to turn on")
        val sb = StringBuilder()
        sb.appendLine(Lang.t("— trạng thái —", "— status —"))
        sb.appendLine(Lang.t("ghi              : ", "recording        : ") + if (on) Lang.t("ĐANG CHẠY", "RUNNING") else Lang.t("tắt", "off"))
        sb.appendLine(Lang.t("đọc thông báo    : ", "read notifications : ") + if (NavProbe.notifGranted(this)) Lang.t("ĐÃ BẬT ✓", "ON ✓") else Lang.t("CHƯA BẬT ✗", "OFF ✗"))
        sb.appendLine(Lang.t("đọc màn hình     : ", "read screen        : ") + if (NavProbeAccessibility.connected) Lang.t("ĐÃ BẬT ✓", "ON ✓") else Lang.t("CHƯA BẬT ✗", "OFF ✗"))
        sb.appendLine(Lang.t("quyền (Settings) : ", "permission (Settings) : ") + if (NavProbeArm.armed(this)) Lang.t("ĐÃ CẤP CẢ HAI ✓", "BOTH GRANTED ✓") else Lang.t("chưa đủ — app sẽ tự cấp qua dadb", "not enough — app will grant via dadb"))
        sb.appendLine(Lang.t("tự bật khi nổ máy: ", "auto-start on ignition : ") + if (NavProbe.autoArmEnabled(this)) Lang.t("BẬT", "ON") else Lang.t("tắt", "off"))
        sb.appendLine()
        sb.appendLine(Lang.t("— 5 kênh đang đo —", "— 5 channels being measured —"))
        sb.appendLine(Lang.t("  1 thông báo · 2 HAL cụm · 3 màn hình · 4 broadcast CP/AA · 5 MediaSession", "  1 notifications · 2 cluster HAL · 3 screen · 4 CP/AA broadcast · 5 MediaSession"))
        sb.appendLine()
        sb.appendLine(Lang.t("— app đang soi màn hình —", "— apps being screen-scanned —"))
        NavProbe.TARGETS.forEach { sb.appendLine("  $it") }
        sb.appendLine()
        sb.appendLine(Lang.t("— đã bắt được —", "— captured so far —"))
        sb.appendLine(NavProbe.seenSummary())
        sb.appendLine()
        NavProbe.latestPath(this)?.let { sb.appendLine("file: $it") }
        out.text = sb.toString()
    }

    /**
     * Gửi NỘI DUNG file dưới dạng text — cố ý không dùng FileProvider (dự án không có androidx.core).
     * Rút gọn bằng [NavProbe.digest] chứ KHÔNG cắt đuôi: app bật sau nằm cuối file, cắt đuôi là mất đúng
     * thứ cần so sánh.
     */
    private fun share() {
        val f = NavProbe.latestFile(this)
        if (f == null) { Toast.makeText(this, Lang.t("chưa có dữ liệu — bấm BẮT ĐẦU DÒ trước", "no data yet — tap START PROBING first"), Toast.LENGTH_LONG).show(); return }
        runCatching {
            val body = NavProbe.digest(f.readText(), MAX_SHARE_CHARS)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "ClusterNav navprobe ${f.name}")
                putExtra(Intent.EXTRA_TEXT, body)
            }, Lang.t("Gửi kết quả cho dev", "Send results to dev")))
        }.onFailure {
            Toast.makeText(this, Lang.t("không chia sẻ được: ${it.message}\nFile ở: ${f.absolutePath}", "couldn't share: ${it.message}\nFile at: ${f.absolutePath}"), Toast.LENGTH_LONG).show()
        }
    }

    private fun openSettings(action: String) {
        runCatching { startActivity(Intent(action)) }
            .onFailure { Toast.makeText(this, Lang.t("không mở được Cài đặt: ${it.message}", "couldn't open Settings: ${it.message}"), Toast.LENGTH_LONG).show() }
    }

    private fun bigBtn(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 15f
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(color)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object { const val MAX_SHARE_CHARS = 120_000 }
}

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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "MÁY DÒ DẪN ĐƯỜNG"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Công cụ khảo sát: xem có lấy được cự ly / hướng rẽ / tên đường từ Vietmap, Waze, " +
                "CarPlay, Android Auto không. Bật → lái một chuyến CÓ DẪN ĐƯỜNG → về bấm CHIA SẺ."
            textSize = 13f
            setPadding(0, dp(6), 0, dp(12))
        })

        toggle = bigBtn("", 0xFF7B4EA8.toInt()) {
            val turningOn = !NavProbe.isOn(this)
            // tắt tay thì PHẢI được nhớ, không thì rời màn quay lại là bị bật lại sau lưng
            if (turningOn) NavProbe.setOn(this, true) else NavProbe.stopByUser(this)
            refresh()
            Toast.makeText(this, when {
                !turningOn -> "đã tắt"
                !NavProbe.notifGranted(this) && !NavProbeAccessibility.connected ->
                    "⚠ CHƯA CẤP QUYỀN NÀO — sẽ không bắt được gì. Cấp hai quyền bên dưới rồi bật lại."
                !NavProbeAccessibility.connected ->
                    "⚠ Thiếu quyền đọc màn hình — đây là kênh quan trọng nhất. Cấp rồi bật lại."
                else -> "ĐANG GHI (tự tắt sau 2 giờ). Lái một chuyến có dẫn đường rồi CHIA SẺ."
            }, Toast.LENGTH_LONG).show()
        }
        root.addView(toggle)

        root.addView(bigBtn("Cấp quyền ĐỌC MÀN HÌNH (quan trọng nhất)", 0xFF2C6E49.toInt()) {
            openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        })
        root.addView(bigBtn("Cấp quyền ĐỌC THÔNG BÁO", 0xFF2C6E49.toInt()) {
            openSettings("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        })
        root.addView(bigBtn("📸 CHỤP NGAY trạng thái CP/AA (bấm khi ĐANG cắm)", 0xFF2C6E49.toInt()) {
            NavProbeSnap.capture(applicationContext, "bấm tay")
            Toast.makeText(this, "đang chụp qua loopback — không cần WiFi", Toast.LENGTH_SHORT).show()
        })
        root.addView(bigBtn("XEM TRƯỚC (đọc kỹ rồi hãy chia sẻ)", 0xFF5B6470.toInt()) {
            val f = NavProbe.latestFile(this)
            out.text = if (f == null) "chưa có dữ liệu"
                       else NavProbe.digest(f.readText(), MAX_SHARE_CHARS).take(20000)
        })
        root.addView(bigBtn("CHIA SẺ KẾT QUẢ", 0xFF5B6470.toInt()) { share() })

        autoBtn = bigBtn("", 0xFF5B6470.toInt()) {
            val next = !NavProbe.autoArmEnabled(this)
            NavProbe.setAutoArm(this, next)
            if (next) com.byd.clusternav.RebindReceiver.armProbe(applicationContext)
            refresh()
            Toast.makeText(this, if (next) "sẽ tự bật mỗi lần nổ máy" else "sẽ KHÔNG tự bật nữa", Toast.LENGTH_LONG).show()
        }
        root.addView(autoBtn)

        root.addView(bigBtn("GỠ HẲN QUYỀN CỦA MÁY DÒ", 0xFF9A3412.toInt()) {
            NavProbe.setAutoArm(this, false)
            NavProbe.setOn(this, false)
            Thread {
                val msg = runCatching {
                    dadb.Dadb.create("localhost", 5555, com.byd.clusternav.AdbKeys.ensure(applicationContext)).use { adb ->
                        NavProbeArm.disarm(applicationContext) { c -> adb.shell(c).output.trim() }
                    }
                }.getOrElse { "không gỡ được qua dadb: ${it.message}\nGỡ tay trong Cài đặt → Trợ năng / Thông báo." }
                runOnUiThread { refresh(); Toast.makeText(this, "đã tắt máy dò\n$msg", Toast.LENGTH_LONG).show() }
            }.apply { isDaemon = true }.start()
        })

        root.addView(TextView(this).apply {
            text = "⚠ Khi bật, máy dò ghi lại nguyên văn thông báo của MỌI ứng dụng (có thể gồm tin nhắn) " +
                "và chữ đang hiện trên màn hình của các app dẫn đường. Tự tắt sau 2 giờ. " +
                "Xem file trước khi gửi nếu thấy cần."
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
        toggle.text = if (on) "⏺ ĐANG GHI — còn ${NavProbe.expiresInMin(this)} phút · chạm để dừng"
                      else "▶ BẮT ĐẦU DÒ"
        autoBtn?.text = if (NavProbe.autoArmEnabled(this)) "⚙ TỰ BẬT mỗi lần nổ máy: ĐANG BẬT — chạm để tắt"
                        else "⚙ Tự bật mỗi lần nổ máy: tắt — chạm để bật"
        val sb = StringBuilder()
        sb.appendLine("— trạng thái —")
        sb.appendLine("ghi              : " + if (on) "ĐANG CHẠY" else "tắt")
        sb.appendLine("đọc thông báo    : " + if (NavProbe.notifGranted(this)) "ĐÃ BẬT ✓" else "CHƯA BẬT ✗")
        sb.appendLine("đọc màn hình     : " + if (NavProbeAccessibility.connected) "ĐÃ BẬT ✓" else "CHƯA BẬT ✗")
        sb.appendLine("quyền (Settings) : " + if (NavProbeArm.armed(this)) "ĐÃ CẤP CẢ HAI ✓" else "chưa đủ — app sẽ tự cấp qua dadb")
        sb.appendLine("tự bật khi nổ máy: " + if (NavProbe.autoArmEnabled(this)) "BẬT" else "tắt")
        sb.appendLine()
        sb.appendLine("— 5 kênh đang đo —")
        sb.appendLine("  1 thông báo · 2 HAL cụm · 3 màn hình · 4 broadcast CP/AA · 5 MediaSession")
        sb.appendLine()
        sb.appendLine("— app đang soi màn hình —")
        NavProbe.TARGETS.forEach { sb.appendLine("  $it") }
        sb.appendLine()
        sb.appendLine("— đã bắt được —")
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
        if (f == null) { Toast.makeText(this, "chưa có dữ liệu — bấm BẮT ĐẦU DÒ trước", Toast.LENGTH_LONG).show(); return }
        runCatching {
            val body = NavProbe.digest(f.readText(), MAX_SHARE_CHARS)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "ClusterNav navprobe ${f.name}")
                putExtra(Intent.EXTRA_TEXT, body)
            }, "Gửi kết quả cho dev"))
        }.onFailure {
            Toast.makeText(this, "không chia sẻ được: ${it.message}\nFile ở: ${f.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openSettings(action: String) {
        runCatching { startActivity(Intent(action)) }
            .onFailure { Toast.makeText(this, "không mở được Cài đặt: ${it.message}", Toast.LENGTH_LONG).show() }
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

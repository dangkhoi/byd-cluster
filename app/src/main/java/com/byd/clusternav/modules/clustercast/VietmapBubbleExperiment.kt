package com.byd.clusternav.modules.clustercast

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.CheckBox
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thăm dò CÔ LẬP câu hỏi CHƯA BIẾT (CLAUDE.md §2/§14): mở VietMap, chiếu nó sang cụm, rồi đẩy nó xuống
 * nền trên điện thoại — cụm có tự vẽ ra một "bong bóng" VietMap hay không, khi một app khác (CP/GMaps)
 * đang là target chính? Chưa có bằng chứng shell thật nào cho câu hỏi này, nên đây CHỈ là công cụ để
 * chạy đúng chuỗi thao tác đó một cách lặp lại được trên xe thật (đối chiếu §11 — app tự làm, không bắt
 * người dùng gõ lệnh) — không phải một tính năng đã có kết luận.
 *
 * Cờ + toàn bộ chuỗi thao tác nằm TÁCH BIỆT HOÀN TOÀN khỏi CastAutomationSettings/AutomationConfig (R6
 * tự-chiếu-khi-mở-app): SharedPreferences riêng, không đọc/ghi `armed`/`defaultPackage`/`revision`. Bật
 * hay tắt ô này không thể chạm vào đường tự-chiếu đã chạy ổn định trên xe.
 */
internal object VietmapBubbleExperiment {
    const val PACKAGE_NAME = "vn.vietmap.live"
    private const val PREFS = "vietmap_bubble_experiment"
    private const val KEY_ENABLED = "enabled"

    /** Đo lấy từ đêm 2026-07-31: đủ để launcher activity của VietMap vẽ xong trước khi chiếu nó. */
    private const val LAUNCH_TO_CAST_DELAY_MS = 1_500L
    private const val CAST_TO_BACKGROUND_DELAY_MS = 1_500L

    /**
     * Chặn hai lượt chồng nhau — bấm nút thủ công hai lần liền tay (hoặc bấm ngay sau khi ô tick vừa tự
     * chạy ở `onCreate`) mới khoá còn hai chuỗi Handler cùng gọi `castTarget`/`startActivity` xen kẽ.
     * KHÔNG khoá gì khác của app: cờ này chỉ canh đúng chuỗi 3 bước của chính thăm dò này.
     */
    private val inFlight = AtomicBoolean(false)

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    private fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Ô tick trên Home LÀ sự đồng ý — cùng quy ước với [CastAutoStartBinding], không hộp thoại riêng. */
    fun bind(checkbox: CheckBox, activity: Activity) {
        checkbox.isChecked = isEnabled(activity)
        checkbox.setOnCheckedChangeListener { _, checked -> setEnabled(activity, checked) }
    }

    /**
     * Chuỗi đo — mở VietMap → chiếu sang cụm → đẩy VietMap xuống nền trên điện thoại — chạy vô điều
     * kiện. Ô tick và nút bấm thủ công đều gọi đúng hàm này; ô tick chỉ thêm lớp "tự gọi lúc mở app" ở
     * [runOnAppStart] bên dưới, không có chuỗi thao tác thứ hai nào khác đi.
     *
     * [castTarget] là `executeCast` đã có sẵn của [MainActivityCastController] — thăm dò này KHÔNG tự
     * phát lệnh chiếu riêng, để không có hai đường mã cùng làm một việc. [log] là
     * `CastFacade.recordOperation` — đi qua façade thay vì import thẳng `CastOperationLog` (tầng
     * `:core`), để không kéo coupling UI→core mới (xem `CastArchitectureRatchetTest`).
     */
    fun trigger(activity: Activity, castTarget: (String) -> Unit, log: (String) -> Unit) {
        if (!inFlight.compareAndSet(false, true)) {
            log("vietmap-bubble-experiment: lượt trước chưa xong, bỏ qua lượt này")
            return
        }
        val launchIntent = activity.packageManager.getLaunchIntentForPackage(PACKAGE_NAME)
        if (launchIntent == null) {
            log("vietmap-bubble-experiment: $PACKAGE_NAME chưa cài, bỏ qua")
            inFlight.set(false)
            return
        }
        log("vietmap-bubble-experiment: bước 1/3 — mở $PACKAGE_NAME")
        activity.startActivity(launchIntent)
        val ui = Handler(Looper.getMainLooper())
        ui.postDelayed({
            log("vietmap-bubble-experiment: bước 2/3 — chiếu $PACKAGE_NAME sang cụm")
            castTarget(PACKAGE_NAME)
            ui.postDelayed({
                log("vietmap-bubble-experiment: bước 3/3 — đẩy $PACKAGE_NAME xuống nền trên điện thoại")
                activity.startActivity(
                    Intent(activity, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
                inFlight.set(false)
            }, CAST_TO_BACKGROUND_DELAY_MS)
        }, LAUNCH_TO_CAST_DELAY_MS)
    }

    /**
     * Lớp "tự gọi" duy nhất khác giữa ô tick và nút bấm: chỉ chạy [trigger] khi ô tick đang bật, và chỉ
     * một lần mỗi lần gọi (gọi từ `onCreate`, không lặp lại ở `onResume` như R6 — đây là một lượt đo thủ
     * công có điều kiện, không phải hành vi thường trực nền).
     */
    fun runOnAppStart(activity: Activity, castTarget: (String) -> Unit, log: (String) -> Unit) {
        if (!isEnabled(activity)) return
        trigger(activity, castTarget, log)
    }
}

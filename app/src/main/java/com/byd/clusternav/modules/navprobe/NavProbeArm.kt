package com.byd.clusternav.modules.navprobe

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * TỰ LÊN NÒNG máy dò — cấp quyền + bật sẵn, để lên xe chỉ việc lái, không phải nhớ bấm gì.
 *
 * Hai quyền máy dò cần (đọc thông báo · đọc màn hình) bình thường phải vào Cài đặt bấm tay. Nhưng ClusterNav
 * đã có sẵn đường dadb chạy dưới uid 2000 (shell) — đủ quyền ghi `Settings.Secure`. Nên tự cấp được.
 *
 * ⚠ QUY TẮC SỐNG CÒN: **CHỈ NỐI THÊM, KHÔNG BAO GIỜ GHI ĐÈ.** Hai khoá này là danh sách phân tách bằng dấu
 * hai chấm, và trong đó đang có `NavNotificationListener` + `NavAccessibilityService` của đường chạy THẬT
 * (booster dẫn đường Google Maps). Ghi đè là tắt luôn tính năng chính, và người dùng sẽ không hiểu vì sao
 * dẫn đường đột nhiên câm. Vì thế: đọc → kiểm đã có chưa → nối vào đuôi → đọc lại xác minh.
 *
 * State này sống ngoài tiến trình (§5) nên [disarm] gỡ đúng phần mình đã thêm, không đụng phần của người khác.
 */
object NavProbeArm {

    private const val K_NOTIF = "enabled_notification_listeners"
    private const val K_ACC = "enabled_accessibility_services"
    private const val K_ACC_ON = "accessibility_enabled"
    private const val PREF = "navprobe"
    private const val K_ACC_OLD = "acc_enabled_old"

    private fun flat(ctx: Context, key: String): String =
        runCatching { Settings.Secure.getString(ctx.contentResolver, key) }.getOrNull().orEmpty()

    private fun has(current: String, comp: String): Boolean =
        current.split(":").any { it.trim() == comp }

    fun notifComp(ctx: Context): String =
        ComponentName(ctx.packageName, NavProbeNotificationListener::class.java.name).flattenToString()

    fun accComp(ctx: Context): String =
        ComponentName(ctx.packageName, NavProbeAccessibility::class.java.name).flattenToString()

    /** Cả hai quyền đã có chưa. */
    fun armed(ctx: Context): Boolean =
        has(flat(ctx, K_NOTIF), notifComp(ctx)) && has(flat(ctx, K_ACC), accComp(ctx))

    /**
     * Cấp cả hai quyền qua [sh] (shell dadb). Trả về mô tả để ghi log.
     * Không ném; thiếu quyền ghi Settings.Secure thì báo rõ để người dùng còn cấp tay.
     */
    fun selfGrant(ctx: Context, sh: (String) -> String): String {
        val out = StringBuilder()
        listOf(K_NOTIF to notifComp(ctx), K_ACC to accComp(ctx)).forEach { (key, comp) ->
            val cur = flat(ctx, key)
            if (has(cur, comp)) { out.append("  ✓ $key đã có sẵn\n"); return@forEach }
            // NỐI THÊM — chuỗi rỗng thì đặt mới, không thì cur + ":" + comp
            val next = if (cur.isBlank()) comp else "$cur:$comp"
            sh("settings put secure $key '$next'")
            val after = flat(ctx, key)
            out.append(if (has(after, comp)) "  ✓ đã cấp $key\n" else "  ✗ KHÔNG cấp được $key (cần bấm tay trong Cài đặt)\n")
        }
        // accessibility_enabled=1: có service trong danh sách mà cờ này =0 thì framework vẫn không bind.
        // ★ LƯU GIÁ TRỊ CŨ TRƯỚC KHI ĐỔI (§5) — không lưu thì disarm() không có gì để trả lại.
        runCatching {
            val old = Settings.Secure.getString(ctx.contentResolver, K_ACC_ON)?.trim().orEmpty()
            val shown = old.ifBlank { "chưa đặt" }
            if (old != "1") {
                ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putString(K_ACC_OLD, old).commit()
                sh("settings put secure $K_ACC_ON 1")
                out.append("  ✓ bật ").append(K_ACC_ON).append(" (giá trị cũ '").append(shown).append("' đã lưu để trả lại)\n")
            }
        }
        return out.toString().trimEnd()
    }

    /** Gỡ ĐÚNG phần của máy dò khỏi hai danh sách — không đụng component của đường chạy thật. */
    fun disarm(ctx: Context, sh: (String) -> String): String {
        val out = StringBuilder()
        listOf(K_NOTIF to notifComp(ctx), K_ACC to accComp(ctx)).forEach { (key, comp) ->
            val cur = flat(ctx, key)
            if (!has(cur, comp)) return@forEach
            val next = cur.split(":").filter { it.trim() != comp && it.isNotBlank() }.joinToString(":")
            sh("settings put secure $key '$next'")
            out.append("  ✓ đã gỡ máy dò khỏi $key\n")
        }
        // trả accessibility_enabled về đúng giá trị trước khi máy dò đụng vào
        val sp = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        sp.getString(K_ACC_OLD, null)?.let { old ->
            // chỉ trả khi KHÔNG còn service trợ năng nào khác đang bật — không thì tắt nhầm của người khác
            if (flat(ctx, K_ACC).isBlank()) {
                sh(if (old.isBlank()) "settings delete secure $K_ACC_ON" else "settings put secure $K_ACC_ON $old")
                out.append("  ✓ trả ").append(K_ACC_ON).append(" về '").append(old.ifBlank { "chưa đặt" }).append("'\n")
            } else out.append("  ⏭ giữ $K_ACC_ON=1 vì còn service trợ năng khác đang dùng\n")
            sp.edit().remove(K_ACC_OLD).commit()
        }
        return out.toString().trimEnd()
    }
}

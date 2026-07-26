package com.byd.clusternav.modules.clustercast

import android.app.AlertDialog
import android.content.Context
import com.byd.clusternav.modules.clustercast.v2.CastManualIntentResult

/**
 * Escalation prompt for the destructive placement rung.
 *
 * The default cast path never force-stops a target, so an app whose task refuses to reparent simply
 * does not land on the cluster. Instead of failing silently the user is told exactly what the next
 * rung costs — the running session — and only an explicit tap escalates.
 */
object CastRetryPrompt {

    /** True when the gentle ladder finished without placing the target on the cluster. */
    fun escalatable(result: CastManualIntentResult, protectedTarget: Boolean): Boolean =
        !protectedTarget && (result is CastManualIntentResult.Blocked || result is CastManualIntentResult.RecoveryRequired)

    fun show(context: Context, label: String, onEscalate: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle("Chưa lên được cụm")
            .setMessage(
                "Đã thử giữ phiên: mở lại trên cụm, chuyển stack sang cụm, ép vẽ lại và co khung — " +
                    "\"$label\" vẫn không bám cụm.\n\n" +
                    "Bước tiếp theo phải TẮT app rồi mở lại trên cụm. Bạn sẽ MẤT phiên đang chạy " +
                    "(tuyến đang dẫn, nội dung đang phát). Với CarPlay/Android Auto bước này luôn bị chặn."
            )
            .setNegativeButton("Để nguyên", null)
            .setPositiveButton("Tắt app và chiếu lại") { _, _ -> onEscalate() }
            .show()
    }
}

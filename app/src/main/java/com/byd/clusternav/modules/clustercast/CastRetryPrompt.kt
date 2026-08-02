package com.byd.clusternav.modules.clustercast

import android.app.AlertDialog
import android.content.Context
import com.byd.clusternav.Lang
import com.byd.clusternav.modules.clustercast.v2.CastManualIntentResult

/**
 * Escalation prompt for the destructive placement rung.
 *
 * The default cast path never force-stops a target, so an app whose task refuses to reparent simply
 * does not land on the cluster. Instead of failing silently the user is told exactly what the next
 * rung costs — the running session — and only an explicit tap escalates.
 */
object CastRetryPrompt {

    /**
     * True when the gentle ladder finished without placing the target on the cluster.
     *
     * The caller must additionally confirm with a fresh observation that the target really is not the
     * cluster occupant: on the vehicle 2026-07-26 this prompt appeared during a cast that then
     * succeeded, because the intent result was decided before the placement was observable.
     */
    fun escalatable(result: CastManualIntentResult, protectedTarget: Boolean): Boolean =
        !protectedTarget && (result is CastManualIntentResult.Blocked || result is CastManualIntentResult.RecoveryRequired)

    fun show(context: Context, label: String, onEscalate: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(Lang.t("Chưa lên được cụm", "Could not reach cluster"))
            // One short sentence: the long transcript of attempted rungs belongs in Chẩn đoán, not in
            // a dialog the driver has to read. Only the cost of the next step matters here.
            .setMessage(Lang.t("\"$label\" không bám cụm. Tắt app rồi chiếu lại sẽ mất phiên đang chạy.", "\"$label\" did not stick to cluster. Force-stopping and recasting will lose the current session."))
            .setNegativeButton(Lang.t("Để nguyên", "Leave as is"), null)
            .setPositiveButton(Lang.t("Tắt app và chiếu lại", "Force-stop and recast")) { _, _ -> onEscalate() }
            .show()
    }
}

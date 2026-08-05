package com.byd.clusternav.modules.clustercast

import android.content.Context
import android.content.Intent
import android.os.Handler
import com.byd.clusternav.Lang
import com.byd.clusternav.modules.clustercast.simplified.AppMover
import com.byd.clusternav.modules.clustercast.simplified.ClusterSlotSide
import com.byd.clusternav.modules.clustercast.simplified.SimpleCastIntent
import com.byd.clusternav.modules.clustercast.simplified.SimpleCastRuntime
import com.byd.clusternav.modules.clustercast.simplified.SimpleCastState
import com.byd.clusternav.modules.clustercast.v2.BubbleZone

/**
 * Dispatches bubble zone taps to the simplified Cast coordinator.
 *
 * Extracted from FloatingBubbleService to keep that file ≤ 400 LOC.
 * All tap logic is here; the service only renders and forwards taps.
 *
 * NOTE: This class no longer manages its own threads. The caller (FloatingBubbleService)
 * invokes [onZoneTap] on the tap executor, which provides coalescing and the tap-token gate.
 */
internal class BubbleActionDispatcher(
    private val context: Context,
    private val handler: Handler,
    private val toast: (String) -> Unit,
) {
    /**
     * Handle a zone tap. Called on the tap executor (already token-guarded).
     * Disabled-zone filtering happens upstream in the service.
     */
    fun onZoneTap(zone: BubbleZone) {
        val coordinator = SimpleCastRuntime.coordinator(context)
        when (val state = coordinator.state) {
            is SimpleCastState.CastingFull -> {
                coordinator.dispatch(SimpleCastIntent.Stop())
                handler.post { toast(Lang.t("Đang trả app về…", "Returning app…")) }
            }
            is SimpleCastState.CastingSplit -> handleSplitTap(zone, state, coordinator)
            is SimpleCastState.Idle -> handleIdleTap(zone, coordinator)
            else -> handler.post { toast(Lang.t("Đang chuẩn bị cụm…", "Preparing cluster…")) }
        }
    }

    private fun handleSplitTap(
        zone: BubbleZone,
        state: SimpleCastState.CastingSplit,
        coordinator: com.byd.clusternav.modules.clustercast.simplified.SimpleCastCoordinator,
    ) {
        val slotSide = when (zone) {
            BubbleZone.LEFT -> ClusterSlotSide.LEFT
            BubbleZone.RIGHT -> ClusterSlotSide.RIGHT
            else -> null
        }
        val slotOccupied = when (slotSide) {
            ClusterSlotSide.LEFT -> state.left != null
            ClusterSlotSide.RIGHT -> state.right != null
            null -> true
        }
        if (slotOccupied) {
            coordinator.dispatch(SimpleCastIntent.Stop(slotSide))
            handler.post { toast(Lang.t("Đang trả app về…", "Returning app…")) }
        } else {
            // Already on tap executor — detect foreground inline (no nested thread needed)
            val foreground = detectForeground(coordinator)
            if (foreground != null) {
                coordinator.dispatch(SimpleCastIntent.CastSlot(foreground, slotSide!!))
                handler.post { toast(Lang.t("Chiếu ${foreground.substringAfterLast('.')}…", "Casting ${foreground.substringAfterLast('.')}…")) }
            } else {
                handler.post { toast(Lang.t("Không xác định được app đang mở", "Cannot determine foreground app")) }
            }
        }
    }

    private fun handleIdleTap(
        zone: BubbleZone,
        coordinator: com.byd.clusternav.modules.clustercast.simplified.SimpleCastCoordinator,
    ) {
        // Already on tap executor — detect foreground inline (no nested thread needed)
        val foreground = detectForeground(coordinator) ?: run {
            handler.post { toast(Lang.t("Không xác định được app đang mở", "Cannot determine foreground app")) }
            return
        }
        val appType = AppMover.classifyApp(foreground)
        when (zone) {
            BubbleZone.FULL -> coordinator.dispatch(SimpleCastIntent.CastFull(foreground, appType))
            BubbleZone.LEFT -> coordinator.dispatch(SimpleCastIntent.CastSlot(foreground, ClusterSlotSide.LEFT))
            BubbleZone.RIGHT -> coordinator.dispatch(SimpleCastIntent.CastSlot(foreground, ClusterSlotSide.RIGHT))
        }
        handler.post { toast(Lang.t("Chiếu ${foreground.substringAfterLast('.')}…", "Casting ${foreground.substringAfterLast('.')}…")) }
    }

    private fun detectForeground(coordinator: com.byd.clusternav.modules.clustercast.simplified.SimpleCastCoordinator): String? {
        val excluded = setOfNotNull(context.packageName, homePackage(), "com.byd.clusternav")
        return runCatching { coordinator.foregroundPackage(HOME_DISPLAY_ID, excluded) }.getOrNull()
    }

    private fun homePackage(): String? = runCatching {
        context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
    }.getOrNull()

    companion object {
        private const val HOME_DISPLAY_ID = 0
    }
}

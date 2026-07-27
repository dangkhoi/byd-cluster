package com.byd.clusternav.modules.clustercast.v2

/**
 * Pure canonical projection for the floating Bubble.
 *
 * The Bubble owns no policy: Stop visibility, action enablement, menu contents, focus order and
 * accessibility text are all derived from the same [CastRenderModel] and app presentation rows the
 * Activity uses. Nothing here dispatches or observes.
 */
enum class BubbleStopControl { HIDDEN, AVAILABLE, ACKNOWLEDGED }

enum class BubbleFocusTarget { STOP, APPS, SETTINGS }

data class BubbleMenuItem(
    val packageName: String,
    val label: String,
    val action: CastAction,
    val enabled: Boolean,
    val isDefault: Boolean,
    val disabledReason: DisabledReason?,
)

data class BubbleProjection(
    val title: String,
    val status: String,
    val stop: BubbleStopControl,
    val stopLabel: String,
    val menuLabel: String,
    val contentDescription: String,
    val menu: List<BubbleMenuItem>,
    /** Labeled one-tap cast of the default app while nothing is on the cluster. No hidden gesture. */
    val primary: BubbleMenuItem?,
    val focusOrder: List<BubbleFocusTarget>,
    val durableStatusPriority: Boolean,
)

object CastBubbleProjection {

    const val MENU_ITEM_LIMIT = 6

    /** A missing action row is treated as not exported instead of throwing inside a projection. */
    private fun CastRenderModel.action(action: CastAction): CastRenderAction =
        actions.firstOrNull { it.action == action }
            ?: CastRenderAction(action, false, DisabledReason.ACTION_NOT_EXPORTED)

    fun project(
        model: CastRenderModel,
        rows: List<CastAppRow>,
        defaultPackage: String?,
        localStopRequested: Boolean = false,
        activeTargetPackage: String? = null,
    ): BubbleProjection {
        val stopAction = model.action(CastAction.STOP)
        val switchAction = model.action(CastAction.SWITCH)
        val castAction = model.action(CastAction.CAST)
        val stop = when {
            localStopRequested -> BubbleStopControl.ACKNOWLEDGED
            stopAction.enabled -> BubbleStopControl.AVAILABLE
            else -> BubbleStopControl.HIDDEN
        }
        val statusText = when {
            localStopRequested -> "Đã yêu cầu dừng"
            model.durableStatusPriority -> model.status
            stopAction.enabled -> model.status
            else -> stopAction.disabledReason?.let { unavailable(it) } ?: model.status
        }
        val menu = menu(rows, defaultPackage, castAction, switchAction, activeTargetPackage)
        return BubbleProjection(
            title = model.title,
            status = statusText,
            stop = stop,
            stopLabel = if (stop == BubbleStopControl.ACKNOWLEDGED) "Đã yêu cầu dừng" else "Dừng chiếu",
            menuLabel = "Cast · Menu",
            contentDescription = "Cluster Cast. ${model.title}. $statusText." +
                (activeTargetPackage?.let { " Đang chiếu $it." } ?: ""),
            menu = menu,
            primary = menu.firstOrNull()
                ?.takeIf { it.enabled && it.isDefault && activeTargetPackage == null && stop == BubbleStopControl.HIDDEN },
            focusOrder = buildList {
                if (stop != BubbleStopControl.HIDDEN) add(BubbleFocusTarget.STOP)
                add(BubbleFocusTarget.APPS)
                add(BubbleFocusTarget.SETTINGS)
            },
            durableStatusPriority = model.durableStatusPriority,
        )
    }

    private fun menu(
        rows: List<CastAppRow>,
        defaultPackage: String?,
        castAction: CastRenderAction,
        switchAction: CastRenderAction,
        activeTargetPackage: String?,
    ): List<BubbleMenuItem> {
        val ordered = CastAppPresentation.rows(rows, defaultPackage)
        // Only apps the owner actually chose, plus whatever is casting right now so it is always
        // possible to switch away from it. The previous fallback to "every installed app" filled the
        // menu with six alphabetically-first apps nobody picked, which read as noise rather than a
        // shortcut; an unpinned state now yields an empty menu so the surface can say how to pick.
        val candidates = ordered
            .filter { it.isDefault || it.favorite || it.packageName == activeTargetPackage }
            .sortedByDescending { it.isDefault }
            .take(MENU_ITEM_LIMIT)
        return candidates.map { row ->
            val switching = activeTargetPackage != null && activeTargetPackage != row.packageName
            val source = if (switching) switchAction else castAction
            val eligible = CastAppPresentation.defaultEligible(row) && row.packageName != activeTargetPackage
            BubbleMenuItem(
                packageName = row.packageName,
                label = if (row.isDefault) "${row.label} · mặc định" else row.label,
                action = source.action,
                enabled = source.enabled && eligible,
                isDefault = row.isDefault,
                disabledReason = when {
                    !eligible && row.packageName == activeTargetPackage -> DisabledReason.NO_ACTIVE_TARGET
                    !eligible -> DisabledReason.PROTECTED_SESSION
                    else -> source.disabledReason
                },
            )
        }
    }

    /** Localized, non-mutating status text shared by the overlay and its accessibility nodes. */
    fun unavailable(reason: DisabledReason): String = when (reason) {
        DisabledReason.NO_ACTIVE_TARGET -> "Chưa có phiên đang chiếu"
        DisabledReason.OPERATION_IN_FLIGHT -> "Đang có thao tác chạy"
        DisabledReason.LEGACY_SESSION_UNSAFE -> "Phiên cũ · chỉ đọc"
        DisabledReason.CONTRACT_UNMAPPED -> "Trạng thái chưa xác định · mở điều khiển"
        DisabledReason.PROTECTED_SESSION -> "Phiên được bảo vệ"
        DisabledReason.GEOMETRY_LIMITED -> "Giới hạn hình học"
        DisabledReason.RECOVERY_PENDING -> "Đang chờ phục hồi"
        DisabledReason.ACTION_NOT_EXPORTED -> "Không khả dụng ở trạng thái này"
    }
}

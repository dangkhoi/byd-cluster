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
    /**
     * Gói đang nằm trên cụm, hoặc `null` khi cụm đang là đồng hồ.
     *
     * Bong bóng v0.57 vẽ ✓ lên app đang chiếu và tô đặc vòng tròn. Muốn vẽ được thứ đó mà KHÔNG dò chuỗi
     * `contentDescription` ("Đang chiếu com.x") — thứ đổi theo ngôn ngữ và bị cấm ở tầng view — thì phiên
     * đang chiếu phải là một trường dữ liệu. Đây là trường đó.
     */
    val activeTargetPackage: String? = null,
    /** Xem [CastRenderModel.sessionConfirmed] — có phiên đã XÁC MINH, không phải chỉ vì Stop đang khả dụng. */
    val sessionConfirmed: Boolean = false,
) {
    /**
     * Có gì đó đang trên cụm để mà dừng.
     *
     * Suy ra từ hợp đồng Stop chứ không từ [activeTargetPackage] một mình: khi phiên là legacy/không xác
     * định thì không có tên gói, nhưng Stop vẫn được xuất ra — lúc đó vòng tròn vẫn phải báo "đang chiếu".
     *
     * NHƯNG Stop cũng được xuất ra cho một nhánh phục hồi lỗi (RECOVERING/RECOVERY_PENDING) chưa từng xác
     * minh có phiên thật — đo trên DiLink3 2026-07-31 (docs/specs/cast-recovery-honesty-and-multi-occupant.html
     * §R3): CarPlay kẹt RECOVERING (am task resize bị từ chối) vẫn xuất Stop, bong bóng vẫn tô đặc như đã
     * chiếu xong. `sessionConfirmed` tách đúng hai ý nghĩa: Stop có nghĩa "đang chiếu thật" CHỈ khi kèm
     * theo xác nhận — nếu không, nó chỉ là lối thoát an toàn của một trạng thái lỗi.
     */
    val projecting: Boolean
        get() = activeTargetPackage != null || (stop != BubbleStopControl.HIDDEN && sessionConfirmed)

    /**
     * Một chạm phải làm gì: Dừng (true) hay Chiếu (false).
     *
     * TÁCH KHỎI [projecting] có chủ đích. `projecting` trả lời "có nên tô đặc không" và từ 2026-07-31 nó
     * đòi thêm [sessionConfirmed] (§R3). Nhưng cú chạm KHÔNG được hỏi câu đó: nó phải hỏi HỢP ĐỒNG —
     * "Dừng có đang được xuất ra không". Hai câu này khác nhau đúng ở ca quan trọng nhất: một nhánh phục
     * hồi (RECOVERING/RECOVERY_PENDING) xuất Stop mà chưa xác minh gì. Nếu cú chạm đi theo `projecting`,
     * đúng lúc cụm kẹt là lúc nút nổi KHÔNG còn phát được Stop nữa — hoặc nó im lặng ("đang có thao tác
     * khác chạy"), hoặc tệ hơn, nó phát một lượt chiếu MỚI đè lên một cụm đang có app lạ. Mà Stop chính
     * là đường thoát duy nhất của các trạng thái đó (`nextSafeAction = REQUEST_STOP`), và nút nổi thường
     * là bề mặt duy nhất nhìn thấy được khi CarPlay/AA đang chiếm màn chính. Đó đúng là kiểu vòng tròn
     * CLAUDE.md §3 cấm: khoá đường phục hồi bằng dữ liệu mà chỉ chính đường đó mới làm mới được.
     *
     * Công thức này = `projecting` NGUYÊN BẢN trước §R3, nên hành vi chạm không đổi một li so với bản
     * đã chạy tốt ngoài hiện trường (CLAUDE.md §6).
     */
    val stopOnTap: Boolean
        get() = activeTargetPackage != null || stop != BubbleStopControl.HIDDEN
}

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
            activeTargetPackage = activeTargetPackage,
            sessionConfirmed = model.sessionConfirmed,
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

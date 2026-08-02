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

/**
 * Ba ô của nút nổi — và đồng thời là BẢN ĐỒ của cụm.
 *
 * [FULL] là cả cụm (một app chiếm trọn), [LEFT]/[RIGHT] là nửa trái và nửa phải
 * (docs/specs/cast-one-mode-and-three-zone-bubble.html §R7, chủ dự án chốt 01/8). Nhìn nút nổi là biết
 * cụm đang có gì ở đâu, không phải mở app ra xem.
 */
enum class BubbleZone { FULL, LEFT, RIGHT }

/**
 * Chạm vào một ô thì chuyện gì xảy ra — đúng HAI khả năng, cố ý.
 *
 * §R7 chốt nguyên văn: "Chỉ có cast ↔ trả, không có switch app". Không có giá trị nào cho menu / đổi
 * app / cử chỉ ẩn, nên không có chỗ cho một cử chỉ thứ ba mọc lên sau này — cùng lý do mà
 * `CastAccessibilityTest` cấm `setOnLongClickListener` trong nút nổi.
 */
enum class BubbleZoneAction { CAST, RETURN }

/**
 * Vì sao một ô KHÔNG nhận được cú chạm ngay lúc này.
 *
 * Tách khỏi [DisabledReason] có chủ đích: [DisabledReason] trả lời "hợp đồng hành động canonical xuất
 * ra cái gì", còn ba lý do dưới đây là chuyện BỐ CỤC của cụm — hai câu hỏi khác nhau, trộn vào một
 * enum là mất luôn khả năng nói đúng câu cho người lái.
 */
enum class BubbleZoneDisabledReason {
    /** Không đo được app nào đang mở trên màn chính để mà chiếu (xem [BubbleCastSource]). */
    NOTHING_TO_CAST,

    /**
     * KHÔNG CÒN ĐƯỢC SINH RA từ 2026-08-01 — giữ lại làm hợp đồng, không phải nhánh sống.
     *
     * Trước đó: một app chiếm trọn cụm ⇒ hai ô nửa cụm CÂM với lý do này (§R7 hình 2). Chủ dự án thay
     * bằng một luật gọn hơn: "đang có app đang chiếu, nhấn bubble phải trả về màn chính, sau đó mới
     * start lại việc chiếu sang" — nên cả hai nửa nay là đường TRẢ VỀ, không còn ô khoá nào (xem
     * `CastBubbleProjection.half`). Bản cũ bắt người lái nhìn một ô khoá trong khi cụm rõ ràng đang có
     * thứ để trả về, tức hai câu trả lời khác nhau cho cùng một câu hỏi tuỳ chạm trúng ô nào.
     *
     * Giữ hằng số vì bảng chữ `zoneUnavailable` là `when` vét cạn — xoá đi là mất luôn dấu vết vì sao
     * luật đổi. Ai làm cho nó sống lại thì phải sửa cả `half` lẫn test
     * `a fullscreen session claims the whole map and every zone returns it`.
     */
    CLUSTER_TAKEN_FULLSCREEN,

    /**
     * Đang có app ở nửa cụm ⇒ chiếu full sẽ NUỐT nó.
     *
     * Đo trên xe thật đêm 31/7 (spec §Context, "Đè nhau thì khuất hẳn"): cho app A full màn trong khi
     * app B còn ở góc thì B biến mất hoàn toàn — task vẫn sống, chỉ bị vẽ đè, và không có cơ chế
     * tự-thu-nhỏ nào của hệ thống. Một cú chạm không được phép làm mất thứ đang hiển thị mà không nói.
     */
    HALVES_IN_USE,

    /**
     * KHÔNG CÒN ĐƯỢC SINH RA từ 2026-08-01 (cùng ngày, muộn hơn `CLUSTER_TAKEN_FULLSCREEN` vài giờ) —
     * giữ lại làm hợp đồng, không phải nhánh sống.
     *
     * Trước đó: một nửa cụm chỉ mời chiếu khi nửa KIA đã có app; cụm rảnh thì cả hai nửa câm. Lời từ chối
     * ấy đúng với tầng dưới CỦA LÚC ĐÓ — ba cửa độc lập cùng chặn lượt đặt app ĐẦU TIÊN vào một nửa:
     * `CastManualIntentRunner.run` đòi một phiên đang chiếu, `ClusterSplit.contentRect` không đọc nổi dải
     * vẽ của một cụm rỗng, và `CastPlanner` vì thế trả `Blocked`.
     *
     * Cả ba đã mở, và mở vì cùng MỘT nhận ra: chia cụm là quyết định NGANG, còn trục dọc là thứ hệ thống
     * phát cho chứ app này không chọn (xem [ClusterSpan] và [ClusterSplit.slotBand]). Cụm rỗng nay cắt ô
     * được từ khung display, cửa ở [CastManualIntentRunner] thu lại đúng ca bootstrap, và phép xác minh so
     * dải ngang thay vì cả rect. Nên luật sản phẩm nay gọn hơn hẳn: **ô trống thì mời chiếu, ô có app thì
     * trả về** — không còn ô nào câm vì "nửa kia chưa có ai".
     *
     * Giữ hằng số vì bảng chữ [zoneUnavailable] là `when` vét cạn — xoá đi là mất luôn dấu vết vì sao luật
     * đổi, đúng như đã làm với [CLUSTER_TAKEN_FULLSCREEN]. Ai làm cho nó sống lại thì phải sửa cả
     * `CastBubbleProjection.half` lẫn test `an empty cluster invites a first app into either half`.
     */
    SPLIT_NEEDS_THE_OTHER_HALF,
}

/**
 * Có gì đó đang mở trên màn chính để mà chiếu hay không — theo QUAN SÁT của bề mặt gọi.
 *
 * [UNMEASURED] là mặc định và KHÔNG có nghĩa "không có gì": nút nổi cố ý chỉ đọc app đang mở ĐÚNG LÚC
 * CHẠM (một lượt I/O cho mỗi cú chạm, luôn tươi) thay vì mỗi nhịp vẽ 15 giây (bốn lượt I/O mỗi phút,
 * và vẫn có thể cũ hơn cú chạm). Ở trạng thái chưa đo, ô trống vẫn mời chiếu và câu trả lời thật được
 * nói ra lúc chạm ("Không xác định được app đang mở") — đúng hành vi đã chạy ngoài hiện trường.
 */
enum class BubbleCastSource { UNMEASURED, PRESENT, ABSENT }

/**
 * Một chỗ đứng ĐÃ ĐO ĐƯỢC trên cụm.
 *
 * [packageName] có thể null: quan sát thấy có task chiếm ô nhưng chưa đọc ra tên gói. Ô vẫn phải tô
 * đặc — cùng bài học với phiên legacy không tên ở [BubbleProjection.projecting].
 */
data class ClusterOccupant(val zone: BubbleZone, val packageName: String?)

/**
 * Ai đang chiếm ô nào trên cụm.
 *
 * Hai trạng thái, KHÔNG phải một danh sách rỗng-hay-không: "chưa ai đo theo ô" và "đã đo, cụm trống"
 * là hai sự thật khác hẳn nhau, và CLAUDE.md §2 bắt phải nói được "chưa biết" thay vì đoán. Hôm nay
 * tầng dưới chỉ biết đọc MỘT occupant (`ObservedState.target`), nên nút nổi khai [Unmeasured] và ô
 * [BubbleZone.FULL] được suy ra từ hợp đồng phiên đơn đã field-proven. Task T8 của
 * docs/specs/cast-one-mode-and-three-zone-bubble.html (đọc bounds theo TASK từ `am stack list`) là chỗ
 * bắt đầu khai [Measured].
 */
sealed interface ClusterOccupancy {
    /** Tầng dưới chưa biết đọc cụm theo ô. Không được đọc thành "cụm trống". */
    data object Unmeasured : ClusterOccupancy

    /** Đã đo theo ô. Danh sách rỗng ở đây MỚI có nghĩa là cụm thật sự trống. */
    data class Measured(val occupants: List<ClusterOccupant>) : ClusterOccupancy
}

/**
 * Một ô của nút nổi: nó đang có gì, chạm vào thì làm gì, và vì sao (nếu) không chạm được.
 *
 * Mọi thứ tầng view cần đều là TRƯỜNG CÓ KIỂU. Tầng view bị cấm dò chuỗi đã bản địa hoá để suy ra
 * trạng thái (bài học của `activeTargetPackage`) — nó chỉ được HIỂN THỊ [message]/[contentDescription]
 * và rẽ nhánh theo [occupied]/[action]/[enabled].
 */
data class BubbleZoneCell(
    val zone: BubbleZone,
    /** Gói đang chiếm ô. null KHÔNG có nghĩa là ô trống — hỏi [occupied]. */
    val occupantPackage: String?,
    /** Tô đặc (true) hay để viền (false). Đây là câu hỏi VẼ, xem [BubbleProjection.projecting]. */
    val occupied: Boolean,
    /** Câu hỏi HÀNH ĐỘNG, tách khỏi [occupied] — xem [BubbleProjection.stopOnTap]. */
    val action: BubbleZoneAction,
    val enabled: Boolean,
    val disabledReason: BubbleZoneDisabledReason?,
    /** Câu ngắn đã bản địa hoá: việc sắp làm, hoặc lý do không làm được. */
    val message: String,
    val contentDescription: String,
)

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
    /**
     * Số ô ĐO ĐƯỢC là đang có app trên cụm.
     *
     * `0` nghĩa là "chưa ai đo theo ô" ([ClusterOccupancy.Unmeasured]) HOẶC "đã đo và cụm trống" — hai
     * ca này chỉ khác nhau ở chỗ nào cần phân biệt, và chỗ đó là [zones], không phải con số này. Ở đây
     * nó chỉ làm một việc: cho [projecting] biết có bằng chứng ĐO ĐƯỢC hay không.
     */
    val measuredOccupants: Int = 0,
    /**
     * Ba ô của nút nổi theo thứ tự FULL → LEFT → RIGHT, hoặc rỗng nếu ai đó dựng projection bằng tay.
     *
     * [CastBubbleProjection.project] luôn xuất đủ ba ô.
     */
    val zones: List<BubbleZoneCell> = emptyList(),
) {
    fun zone(zone: BubbleZone): BubbleZoneCell? = zones.firstOrNull { it.zone == zone }

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
     *
     * Vế thứ ba ([measuredOccupants]) thêm 2026-08-01 cùng bản đồ 3 ô: một occupant ĐO ĐƯỢC trên cụm là
     * bằng chứng mạnh hơn cả `sessionConfirmed` — nó là quan sát thật chứ không phải kết luận của máy
     * trạng thái. Nó chỉ CỘNG THÊM, không lấy đi ca nào của công thức cũ, nên đường phiên đơn đã chạy
     * ngoài hiện trường giữ nguyên từng li (CLAUDE.md §6).
     */
    val projecting: Boolean
        get() = activeTargetPackage != null ||
            (stop != BubbleStopControl.HIDDEN && sessionConfirmed) ||
            measuredOccupants > 0

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
     *
     * Bản đồ 3 ô (2026-08-01) giữ NGUYÊN sự phân đôi này thay vì gộp lại: ô [BubbleZone.FULL] TÔ theo
     * [projecting] nhưng CHẠM theo trường này, nên ca "kẹt RECOVERING, chưa xác minh gì" vẫn ra một ô
     * để viền mà chạm vào là Dừng — đúng thứ đã cứu được đường thoát duy nhất.
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

    /**
     * @param occupancy ai đang chiếm ô nào trên cụm. Mặc định [ClusterOccupancy.Unmeasured] = "chưa ai
     *   đo theo ô", KHÔNG phải "cụm trống" — ô [BubbleZone.FULL] khi đó suy ra từ hợp đồng phiên đơn.
     * @param castSource có app nào đang mở để chiếu không, theo quan sát của bề mặt gọi.
     */
    fun project(
        model: CastRenderModel,
        rows: List<CastAppRow>,
        defaultPackage: String?,
        localStopRequested: Boolean = false,
        activeTargetPackage: String? = null,
        occupancy: ClusterOccupancy = ClusterOccupancy.Unmeasured,
        castSource: BubbleCastSource = BubbleCastSource.UNMEASURED,
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
        val measured = (occupancy as? ClusterOccupancy.Measured)?.occupants.orEmpty()
        val base = BubbleProjection(
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
            measuredOccupants = measured.size,
        )
        // Dựng ô SAU khi có `base`, rồi `copy` — cố ý, để ba ô đọc thẳng `base.projecting` /
        // `base.stopOnTap` thay vì chép lại hai công thức đó lần thứ hai. Chép lại là cách chắc chắn
        // nhất để bản vá §R3 (tô ≠ chạm) bị mất đúng một nửa ở lần sửa sau.
        return base.copy(zones = zones(base, rows, measured, castSource))
    }

    /**
     * Ba ô, dựng từ ĐÚNG một luật, không ngoại lệ (spec §R7):
     * ô có app ⇒ chạm là TRẢ VỀ; ô trống ⇒ chạm là CHIẾU.
     *
     * Phần còn lại chỉ là MỘT cửa bố cục, và nó ở đó để giữ thứ đang hiển thị:
     *  - nửa cụm đang có app ⇒ ô full câm, vì chiếu full sẽ NUỐT chúng (đo 31/7, xem
     *    [BubbleZoneDisabledReason.HALVES_IN_USE]).
     *
     * Cụm rảnh thì CẢ BA ô đều mời chiếu (2026-08-01): chạm nửa trái là app đang mở vào nửa trái ngay,
     * nửa phải để trống chờ app sau — luật do chủ dự án chốt, và tầng dưới đã nhận được lượt đặt app đầu
     * tiên kể từ [ClusterSplit.slotBand]. Cửa cũ chặn ca này đã thành ngõ cụt, xem
     * [BubbleZoneDisabledReason.SPLIT_NEEDS_THE_OTHER_HALF].
     *
     * Không có nhánh nào theo tên gói (CLAUDE.md §7): mọi khác biệt đến từ occupancy đo được.
     */
    private fun zones(
        base: BubbleProjection,
        rows: List<CastAppRow>,
        measured: List<ClusterOccupant>,
        castSource: BubbleCastSource,
    ): List<BubbleZoneCell> {
        val left = measured.firstOrNull { it.zone == BubbleZone.LEFT }
        val right = measured.firstOrNull { it.zone == BubbleZone.RIGHT }
        val halvesInUse = left != null || right != null
        val fullOccupant = measured.firstOrNull { it.zone == BubbleZone.FULL }
        // Khi hai nửa đang có app, `activeTargetPackage` của phiên đơn vẫn trỏ vào MỘT trong hai app đó
        // (store chỉ ghi được một target) — nó KHÔNG có nghĩa là cụm đang bị chiếm trọn. Không có cửa
        // `!halvesInUse` này thì ô full tự tô đặc trong khi cụm đang chia đôi: bản đồ nói dối.
        val fullPainted = fullOccupant != null || (!halvesInUse && base.projecting)
        val fullClaimed = fullPainted || (!halvesInUse && base.stopOnTap)
        val fullPackage = fullOccupant?.packageName ?: base.activeTargetPackage.takeIf { !halvesInUse }
        val full = when {
            fullClaimed -> cell(BubbleZone.FULL, fullPackage, fullPainted, BubbleZoneAction.RETURN, rows)
            halvesInUse -> blocked(BubbleZone.FULL, BubbleZoneDisabledReason.HALVES_IN_USE)
            else -> castCell(BubbleZone.FULL, castSource)
        }
        return listOf(
            full,
            half(BubbleZone.LEFT, left, fullOccupant, fullClaimed, fullPainted, fullPackage, castSource, rows),
            half(BubbleZone.RIGHT, right, fullOccupant, fullClaimed, fullPainted, fullPackage, castSource, rows),
        )
    }

    /**
     * LUẬT DUY NHẤT, chủ dự án chốt 2026-08-01: **ô nào đang có app thì chạm là TRẢ VỀ màn chính; xong
     * rồi mới chiếu lại.** Không có thao tác "xếp lại app đang chiếu sang nửa khác", không có nước đi
     * thông minh nào cả.
     *
     * Vì sao luật này tốt hơn cái nó thay thế: bản trước cho một nửa câm bằng
     * `CLUSTER_TAKEN_FULLSCREEN` khi cụm đang bị chiếm trọn, tức người lái nhìn thấy một ô KHOÁ trong khi
     * cụm rõ ràng đang có thứ để trả về — hai câu trả lời khác nhau cho cùng một câu hỏi ("cụm đang có
     * gì?"), tuỳ chạm vào ô nào. Nay mọi ô trả lời như nhau: có app ⇒ trả về.
     *
     * ★ NỬA CỤM TRỐNG LUÔN MỜI CHIẾU, kể cả khi nửa kia cũng trống (2026-08-01). Trước đó ca ấy câm với
     * [BubbleZoneDisabledReason.SPLIT_NEEDS_THE_OTHER_HALF] — đúng với tầng dưới của lúc đó, vì chưa có
     * đường nào đặt app ĐẦU TIÊN vào một nửa. Nay có: [ClusterSplit.slotBand] cắt ô được từ khung display
     * khi cụm đã chứng minh là rỗng. Giữ cửa cũ lại bây giờ là câm đúng cái thao tác mà chủ dự án vừa đặt
     * làm hành vi chính ("chạm trái ⇒ app vào nửa trái, phải để trống chờ").
     *
     * Vẫn KHÔNG có cửa nào ở đây đoán thay tầng dưới: bản đồ này vẽ theo occupancy ĐO ĐƯỢC, còn lượt phát
     * lệnh tự đọc lại một quan sát TƯƠI (`CastFacade.runHalfIntent`) và planner kiểm lại lần nữa trên
     * `observed` của chính nó. Ô mời chiếu là "chưa thấy gì cản", không phải một lời hứa.
     *
     * @param clusterOccupant app đang chiếm cụm theo quan sát, bất kể nó nằm ở ô nào — dùng cho ca cụm
     *   đang bị chiếm TRỌN: lúc đó không ô nửa nào "có" app riêng, nhưng cả hai vẫn phải trả về được.
     */
    private fun half(
        zone: BubbleZone,
        occupant: ClusterOccupant?,
        clusterOccupant: ClusterOccupant?,
        fullClaimed: Boolean,
        fullPainted: Boolean,
        fullPackage: String?,
        castSource: BubbleCastSource,
        rows: List<CastAppRow>,
    ): BubbleZoneCell = when {
        occupant != null -> cell(zone, occupant.packageName, true, BubbleZoneAction.RETURN, rows)
        // Cụm đang bị chiếm trọn: chạm nửa nào cũng là "trả app đó về", đúng luật trên. Ô tô ĐẶC CHỈ
        // KHI BẢN ĐỒ ĐÃ ĐO ĐƯỢC (fullPainted = true) — nếu chỉ có Stop nhưng chưa xác minh chiếu thật
        // (fullClaimed && !fullPainted) thì ô nửa hiện VIỀN giống ô full: bản đồ nói thật "có đường thoát
        // nhưng chưa biết cụm đang có gì". Trước bản vá này hai ô nửa hardcode `occupied=true` nên chúng
        // tô xanh trong khi ô full tô viền — ba ô cùng bản đồ mà nói ba chuyện khác nhau.
        fullClaimed -> cell(zone, clusterOccupant?.packageName ?: fullPackage, fullPainted, BubbleZoneAction.RETURN, rows)
        else -> castCell(zone, castSource)
    }

    /**
     * Ô trống mời chiếu — trừ khi bề mặt gọi ĐÃ ĐO và thấy không có gì đang mở.
     *
     * Cố ý KHÔNG khoá theo hợp đồng `CastAction.CAST`: cửa duy nhất mà nút nổi thật sự dùng để từ chối
     * một lượt chiếu là "đang có transaction của bề mặt khác" (`foreignMutationInFlight`), và mọi kết
     * cục hỏng đều đã được nói ra bằng `statusMessage()`. Thêm một cửa nữa ở đây sẽ đổi hành vi của
     * đường chạm đã field-proven mà không có một phép đo nào đòi hỏi (CLAUDE.md §6).
     */
    private fun castCell(zone: BubbleZone, castSource: BubbleCastSource): BubbleZoneCell =
        if (castSource == BubbleCastSource.ABSENT) blocked(zone, BubbleZoneDisabledReason.NOTHING_TO_CAST)
        else cell(zone, null, false, BubbleZoneAction.CAST, emptyList())

    private fun blocked(zone: BubbleZone, reason: BubbleZoneDisabledReason): BubbleZoneCell {
        val message = zoneUnavailable(reason)
        return BubbleZoneCell(
            zone = zone,
            occupantPackage = null,
            occupied = false,
            // Vẫn là CAST: ô trống, chỉ là lúc này chưa chiếu được. Người lái đọc `message` để biết vì sao.
            action = BubbleZoneAction.CAST,
            enabled = false,
            disabledReason = reason,
            message = message,
            contentDescription = "Ô ${zoneName(zone)}. $message.",
        )
    }

    private fun cell(
        zone: BubbleZone,
        occupantPackage: String?,
        occupied: Boolean,
        action: BubbleZoneAction,
        rows: List<CastAppRow>,
    ): BubbleZoneCell {
        // Tên app cho người đọc, tên gói chỉ là lưới an toàn khi app không có trong danh sách đã chọn.
        val name = occupantPackage?.let { pkg -> rows.firstOrNull { it.packageName == pkg }?.label ?: pkg }
        val message = when (action) {
            BubbleZoneAction.RETURN -> "Trả ${name ?: "app trên cụm"} về màn chính"
            BubbleZoneAction.CAST -> "Chiếu app đang mở vào ${zoneName(zone)}"
        }
        return BubbleZoneCell(
            zone = zone,
            occupantPackage = occupantPackage,
            occupied = occupied,
            action = action,
            enabled = true,
            disabledReason = null,
            message = message,
            contentDescription = "Ô ${zoneName(zone)}. $message.",
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

    /**
     * Nhãn NGẮN vẽ trong ô. Tĩnh theo ô, không mang trạng thái — trạng thái nằm ở [BubbleZoneCell].
     *
     * Ở đây chứ không ở tầng view: một bộ chữ, một chỗ sửa. Tầng view chỉ vẽ nó ra.
     */
    fun zoneShortLabel(zone: BubbleZone): String = when (zone) {
        BubbleZone.FULL -> "Cả cụm"
        BubbleZone.LEFT -> "Trái"
        BubbleZone.RIGHT -> "Phải"
    }

    /** Tên ô trong câu nói ra cho người lái / trình đọc màn hình. */
    fun zoneName(zone: BubbleZone): String = when (zone) {
        BubbleZone.FULL -> "cả cụm"
        BubbleZone.LEFT -> "nửa trái"
        BubbleZone.RIGHT -> "nửa phải"
    }

    /**
     * Vì sao ô này không chạm được, nói bằng câu người lái đọc được ngay khi xe đang chạy.
     *
     * Hai câu của nhóm chia-đôi giữ lại vì bảng này là `when` vét cạn, nhưng cả hai lý do ấy đã KHÔNG CÒN
     * được sinh ra (xem KDoc từng hằng số). Câu duy nhất còn sống của nhóm là [HALVES_IN_USE], và nó chỉ
     * một hướng thật sự dẫn tới đâu: trả app về thì ô cả cụm dùng được ngay.
     */
    fun zoneUnavailable(reason: BubbleZoneDisabledReason): String = when (reason) {
        BubbleZoneDisabledReason.NOTHING_TO_CAST -> "Không có app nào đang mở để chiếu"
        BubbleZoneDisabledReason.CLUSTER_TAKEN_FULLSCREEN -> "Cụm đang chiếu full · chưa chia đôi được"
        BubbleZoneDisabledReason.HALVES_IN_USE -> "Cụm đang chia đôi · trả app về trước"
        BubbleZoneDisabledReason.SPLIT_NEEDS_THE_OTHER_HALF -> "Chưa chia đôi được · nửa kia chưa có app"
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

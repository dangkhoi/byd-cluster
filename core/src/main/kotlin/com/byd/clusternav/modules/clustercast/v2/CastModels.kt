package com.byd.clusternav.modules.clustercast.v2

import java.time.Instant
import java.util.Collections
import java.util.UUID

typealias Sha256LowerHex = String

/** Immutable Stage-2 contracts. Nothing in this package is wired to the legacy Cast runtime. */
const val CAST_ENVELOPE_SCHEMA_VERSION = 3
const val CAST_UI_SCHEMA_VERSION = 5
const val CAST_UI_SCHEMA_HASH =
    "79595611424c083cca80b87002e65ee16097f668b09ea8f4d721235a2f060918"

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))
private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))

private fun valueHash(vararg values: Any?): Int = values.contentHashCode()

enum class EngineVersion { LEGACY, V2 }
enum class StableState { IDLE_VERIFIED, ACTIVE_VERIFIED, ACTIVE_DEGRADED, RECOVERY_PENDING, MANUAL_REQUIRED }
enum class OperationPhase { PREPARING, ACTIVATING, SWITCHING, VERIFYING, STOP_REQUESTED, RESTORING, RECOVERING }
enum class CastOperation { BOOTSTRAP, CAST, SWITCH, STOP, RECOVER, APPLY_GEOMETRY }
enum class LedgerEffect { PLANNED, ISSUED, OBSERVED, REJECTED }
enum class TargetClass { NORMAL, PROJECTION_SINK, KEEP_SESSION, UNKNOWN_PROTECTED }

/**
 * Cluster style, chosen per application exactly as V1 proved necessary: one global flag made every
 * app inherit the previous app's cluster shape. CURVED keeps the km/h gauge, RECT uses the full pane.
 */
enum class ClusterStyle { CURVED, RECT }

enum class CommandKind(val mutating: Boolean) {
    AM_STACK_LIST(false), WM_DISPLAYS(false), DISPLAY_STATE(false), PROFILE_STATE(false),
    ANIMATION_STATE(false), APP_OPS_STATE(false), PHONE_SESSION_STATE(false), PACKAGE_RESOLVE(false),
    SEAL_DL3_BOOTSTRAP_30(true), SEAL_DL3_BOOTSTRAP_31(true),
    SEAL_DL3_BOOTSTRAP_16(true), SEAL_DL3_BOOTSTRAP_35(true),
    SEAL_DL3_COMPENSATE_18(true), SEAL_DL3_COMPENSATE_0(true),
    START_FRESH_NORMAL(true), RESUME_PROTECTED(true), RETURN_NORMAL_TO_MAIN(true),
    RETURN_PROTECTED_GENTLY(true), FORCE_STOP_NORMAL(true), APPLY_TASK_GEOMETRY(true),
    APPLY_DISPLAY_GEOMETRY(true), RESET_CLEAN_DISPLAY(true), DISCONNECTED_SINK_RECOVERY_ONCE(true),

    /**
     * Field-proven placement ladder (0.72). Every step is journaled like any other mutation, but the
     * order is the one verified on the vehicle: keep the running session first, escalate only when a
     * gentler rung provably failed to land the task on the cluster display.
     */
    SET_FORCE_RESIZABLE(true),
    DISABLE_TRANSITION_ANIMATION(true),
    RESTORE_TRANSITION_ANIMATION(true),
    BLOCK_PIP(true),
    RESTORE_PIP(true),
    PRE_OPEN_ON_MAIN(true),
    PLACE_KEEP_SESSION(true),
    MOVE_STACK_TO_CLUSTER(true),
    REASSERT_ON_CLUSTER(true),
    FIT_CLUSTER_COMPOSITE(true),

    /**
     * CarPlay/AA placement (2026-08-01, field-proven T1). `am stack move-task <taskId> <stackId> true`
     * di chuyển TASK vào stack đã nằm sẵn trên display cụm — không di chuyển cả stack qua display
     * (đường `am display move-stack` sập system_server 3/3 trên Android 10 do NPE trong
     * `DisplayContent.moveStackToDisplay`). Xem docs/diagnostics/carplay-move-task-success-2026-08-01.md.
     */
    MOVE_TASK_TO_CLUSTER_STACK(true),

    /**
     * Scale display density cho app unresizeable vừa cụm (2026-08-01, field-proven T2c).
     * `wm density <dpi> -d <display>` buộc app tự layout lại ở density mới → vừa khung 720p.
     * Cần đi KÈM move-task, và RESET khi trả app về.
     */
    SCALE_CLUSTER_DENSITY(true),

    /** Reset display density về mặc định khi trả app về display 0. */
    RESET_CLUSTER_DENSITY(true),

    /** Trả app về display 0 bằng move-task ngược (cho app not-exported như CarPlay). */
    RETURN_TASK_TO_MAIN(true),
}

data class CastTarget(val packageName: String, val taskId: Int, val displayId: Int)
data class ProtectedResidue(val packageName: String, val taskId: Int, val visibility: ResidueVisibility)
enum class ResidueVisibility { HIDDEN, UNKNOWN }
data class CastRect(val left: Int, val top: Int, val right: Int, val bottom: Int)
data class AcceptedGeometry(val bounds: CastRect, val densityDpi: Int?, val profileId: String)

// ── Chia đôi cụm: ô TRÁI/PHẢI, ba tỉ lệ, không có tỉ lệ tự do ──────────────────────────────────────

/**
 * Dải NGANG mà một ô chiếm trên cụm — và là điều DUY NHẤT mà phép chia đôi này quyết định.
 *
 * ★ VÌ SAO CÓ MỘT KIỂU RIÊNG CHO NỬA NGANG CỦA MỘT RECT (2026-08-01).
 *
 * Chia cụm là một quyết định MỘT CHIỀU: bề ngang (đo được 1920) bị cắt tại đúng MỘT ranh giới. Trục dọc
 * thì KHÔNG phải thứ app này chọn — hệ thống phát cho bao nhiêu thì nhận bấy nhiêu, và con số ấy đổi theo
 * overscan cấp display, một giá trị **không ổn định giữa các lần dump**: đo `overscan (0,90,0,90)` đêm
 * 31/7, trong khi `docs/diagnostics/carlog-2026-07-21/05-display.txt:121` cùng một display cụm lại ghi
 * `overscan (536,224,88,336)`.
 *
 * Mà hai app cạnh nhau KHÔNG THỂ đè nhau chừng nào hai dải NGANG của chúng rời nhau — bất kể hệ thống cho
 * chúng cao thấp thế nào. Vậy dải ngang là toàn bộ nội dung của lời hứa "hai app cùng hiện"; trục dọc chỉ
 * đi kèm vì `am task resize` cần đủ bốn số.
 *
 * MỨC BẰNG CHỨNG CỦA HAI TRỤC KHÔNG BẰNG NHAU, và đó là lý do kiểu này tồn tại (CLAUDE.md §2):
 *  - **Trục ngang — ĐÃ ĐO.** Đêm 31/7 gõ tay `am task resize <maps> 0 90 960 810` và
 *    `am task resize <vietmap> 960 90 1920 810`; `am stack list` đọc lại ra ĐÚNG `[0,90][960,810]` và
 *    `[960,90][1920,810]`. Ba con số 0 / 960 / 1920 chính là toạ độ NGANG của khung display
 *    (`app 1920 x 720`), và chúng quay về nguyên vẹn ⇒ trục ngang là ánh xạ đồng nhất trong không gian
 *    display. Vì thế dải ngang ĐƯỢC PHÉP so tuyệt đối lúc xác minh.
 *  - **Trục dọc — CHƯA ĐO.** Hai con số 90 / 810 nằm NGOÀI khung display `[0,720]`, nên phép đo trên
 *    không nói gì về việc xin `top=0, bottom=720` thì hệ thống đặt task ở đâu. Chưa đo thì không so —
 *    xem [ClusterSplit.slotBand] và `CastCoordinator.layoutMatchesObservation`.
 */
data class ClusterSpan(val left: Int, val right: Int) {
    init { require(right > left) { "a cluster span covers at least one column" } }

    /** Hai dải chạm nhau tại đúng một ranh giới thì KHÔNG chồng — đó là cách hai ô khít nhau. */
    fun overlaps(other: ClusterSpan): Boolean = left < other.right && other.left < right

    override fun toString() = "[$left..$right)"
}

/**
 * Dải ngang của khung này, hoặc `null` khi khung suy biến (bề ngang ≤ 0).
 *
 * Nullable chứ không ném: nguồn của nó là `ObservedState.taskBounds`, tức bốn con số đọc từ dump THẬT của
 * xe. Một dòng dump dị dạng phải trở thành "không xác minh được", không bao giờ thành một ngoại lệ ném ra
 * giữa lúc người lái vừa chạm.
 */
val CastRect.horizontalSpan: ClusterSpan? get() = if (right > left) ClusterSpan(left, right) else null

/**
 * Nửa cụm mà một app được đặt vào.
 *
 * Chỉ có TRÁI/PHẢI vì đó là điều DUY NHẤT đã đo được: tối 2026-07-31 trên DiLink3, hai app hiện cùng
 * lúc khi và chỉ khi hai rect KHÔNG chồng nhau, và phép chia đã đo là chia dọc
 * (`am task resize <maps> 0 90 960 810` + `am task resize <vietmap> 960 90 1920 810` → `screencap -d 1`
 * VÀ cụm vật lý đều thấy hai app vẽ thật). Chia ngang chưa từng đo ⇒ không có trong kiểu dữ liệu này;
 * thêm vào chỉ khi có phép đo mới (CLAUDE.md §2 — chưa đo thì không được có mặt trong contract).
 */
enum class ClusterSlotSide { LEFT, RIGHT }

/**
 * Ba tỉ lệ chia được phép, và CHỈ ba — luật sản phẩm do chủ sở hữu chốt: cụm hoặc một app toàn màn,
 * hoặc hai app trái/phải ở 50-50, 30-70, 70-30.
 *
 * [leftPercent] là phần trăm BỀ NGANG mà ô TRÁI chiếm; ô phải nhận đúng phần còn lại, nên hai ô luôn
 * khít nhau: không chồng (chồng thì một app che hẳn app kia — ĐÃ ĐO) và không hở (hở thì lộ khung
 * đen ra giữa cụm).
 */
enum class ClusterSplitRatio(val leftPercent: Int) {
    /** 50-50. Đúng tỉ lệ đã đo thật trên xe. */
    EVEN(50),

    /** 30-70: trái hẹp, phải rộng. Suy ra từ cùng một phép chia, chưa đo riêng. */
    LEFT_NARROW(30),

    /** 70-30: trái rộng, phải hẹp. Suy ra từ cùng một phép chia, chưa đo riêng. */
    LEFT_WIDE(70),
}

/** Một ô trên cụm. Là Ô, không phải pixel: rect được TÍNH RA từ khung cụm đo được + [ratio]. */
data class ClusterSlot(val side: ClusterSlotSide, val ratio: ClusterSplitRatio)

/**
 * Bố cục mà người lái YÊU CẦU, nói theo ô chứ không theo pixel: gói nào giữ ô nào.
 *
 * Vì sao là gói→ô chứ không phải gói→rect: cùng một bố cục phải có cùng ý nghĩa trên một cụm kích
 * thước khác (DL5, hoặc cùng DL3 nhưng `wm size` đã bị đổi). Rect chỉ sinh ra ở [resolve], từ khung
 * cụm ĐO ĐƯỢC tại thời điểm lập kế hoạch.
 *
 * Bất biến ép ngay trong `init`, mỗi dòng khoá một cách hỏng cụ thể:
 *  - tối đa HAI app: cụm chỉ chia đôi (luật sản phẩm), ba app không có ô nào để mà đặt vào;
 *  - mỗi bên đúng một app: hai app cùng đòi ô trái là hai rect TRÙNG NHAU ⇒ đo thật cho thấy một app
 *    che hẳn app kia, tức đúng cái hỏng mà cả kiểu dữ liệu này sinh ra để chặn;
 *  - hai ô phải cùng [ClusterSplitRatio]: 30% bên trái mà 30% bên phải thì ở giữa hở 40% khung đen,
 *    còn 70/70 thì chồng nhau 40%. Cùng một tỉ lệ là điều kiện cần và đủ để hai ô khít nhau.
 */
class ClusterSlotLayout(slots: Map<String, ClusterSlot>) {
    val slots: Map<String, ClusterSlot> = immutableMap(slots)

    init {
        require(slots.isNotEmpty()) { "a cluster layout names at least one app" }
        require(slots.size <= 2) { "the cluster holds at most two apps" }
        require(slots.keys.none(String::isBlank)) { "every occupant needs a package name" }
        require(slots.values.map { it.side }.toSet().size == slots.size) { "one app per side" }
        require(slots.values.map { it.ratio }.toSet().size == 1) { "both halves share one split ratio" }
    }

    val packages: Set<String> get() = slots.keys

    /**
     * Rect thật của từng app, tính từ [band] — dải để cắt ô (xem [ClusterSplit.slotBand]).
     * Trả `null` khi dải ấy hẹp tới mức không chia nổi thành hai ô rời nhau.
     */
    fun resolve(band: CastRect): IntendedClusterLayout? {
        val rects = LinkedHashMap<String, CastRect>(slots.size)
        slots.forEach { (packageName, slot) ->
            rects[packageName] = ClusterSplit.slotRect(band, slot) ?: return null
        }
        if (!ClusterSplit.tileable(rects.values)) return null
        return IntendedClusterLayout(rects)
    }

    override fun equals(other: Any?) = other is ClusterSlotLayout && slots == other.slots
    override fun hashCode() = slots.hashCode()
    override fun toString() = "ClusterSlotLayout(slots=$slots)"
}

/**
 * Bố cục đã quy ra rect: gói → khung mà task của gói đó nhận khi lệnh được phát.
 *
 * HAI NỬA CỦA MỘT RECT Ở ĐÂY KHÔNG CÙNG MỘT VAI TRÒ, và trộn chúng lại chính là chỗ tính năng chia đôi
 * từng tự bịt đường của mình (xem [ClusterSpan] để biết mức bằng chứng của từng trục):
 *  - [occupantRects] là thứ ĐI RA XE: `am task resize` cần đủ bốn số, nên rect phải đủ bốn số.
 *  - [occupantSpans] là thứ ĐƯỢC HỨA: dải ngang, và chỉ dải ngang. Đây mới là thứ phép xác minh đem so
 *    với quan sát thật, vì nó là thứ duy nhất app này quyết định.
 *
 * Bất biến ép trong `init` là bất biến THẬT của sản phẩm, không phải "rect phải khác nhau": hai dải ngang
 * phải ĐÔI MỘT RỜI NHAU. Chồng nhau là ca ĐÃ ĐO được rằng một app che hẳn app kia (task kia vẫn còn trong
 * `am stack list` với `visible=true`, chỉ bị vẽ đè) — và nếu lọt vào đây thì phép so "tập dải quan sát ==
 * tập dải dự kiến" sẽ nhận nhầm một cụm chỉ vẽ MỘT app là thành công. Bất biến cũ ("rect đôi một khác
 * nhau") yếu hơn đúng chỗ nguy hiểm: hai rect khác nhau vẫn có thể chồng nhau.
 */
class IntendedClusterLayout(occupantRects: Map<String, CastRect>) {
    val occupantRects: Map<String, CastRect> = immutableMap(occupantRects)

    /** Dải ngang của từng ô — thứ duy nhất bố cục này thật sự hứa hẹn. */
    val occupantSpans: Map<String, ClusterSpan>

    init {
        require(occupantRects.isNotEmpty()) { "an intended layout names at least one app" }
        require(ClusterSplit.tileable(occupantRects.values)) {
            "occupant slots must have positive width and must not overlap horizontally"
        }
        val spans = LinkedHashMap<String, ClusterSpan>(occupantRects.size)
        occupantRects.forEach { (packageName, rect) ->
            spans[packageName] = checkNotNull(rect.horizontalSpan)
        }
        occupantSpans = immutableMap(spans)
    }

    val packages: Set<String> get() = occupantRects.keys
    val spans: Set<ClusterSpan> get() = occupantSpans.values.toSet()
    fun rectOf(packageName: String): CastRect? = occupantRects[packageName]
    fun spanOf(packageName: String): ClusterSpan? = occupantSpans[packageName]

    override fun equals(other: Any?) = other is IntendedClusterLayout && occupantRects == other.occupantRects
    override fun hashCode() = occupantRects.hashCode()
    override fun toString() = "IntendedClusterLayout(occupantRects=$occupantRects)"
}

/**
 * Phép chia thuần: khung cụm + ô → rect. Không đọc thiết bị, không phát lệnh, test off-car được.
 *
 * ★ ĐÃ ĐO TRÊN XE (DiLink3, 2026-07-31, bằng adb thô — mức "đã chứng minh", CLAUDE.md §2):
 *  - freeform SỐNG trên display cụm: `am start --display 1 --windowingMode 1 -n <component>` ra cửa sổ
 *    không-toàn-màn, và `am task resize <taskId> L T R B` đổi khung thành công;
 *  - HAI app cùng vẽ khi hai rect KHÔNG chồng nhau — chuỗi lệnh và kết quả nguyên văn:
 *      am task resize <mapsTask>    0 90  960 810
 *      am task resize <vietmapTask> 960 90 1920 810
 *    `screencap -d 1` VÀ cụm vật lý đều hiện GMaps trái / VietMap phải, vẽ đầy đủ;
 *  - rect CHỒNG nhau thì KHÔNG ghép: app toàn màn che hẳn app kia (task kia vẫn còn trong
 *    `am stack list` với visible=true — nó chỉ bị vẽ đè). Đây là lý do mọi bất biến ở trên đòi hai
 *    rect rời nhau, chứ không phải vì đẹp;
 *  - Z-ORDER theo lần `am start` CUỐI CÙNG, KHÔNG theo lần resize cuối.
 *  - Hình học cụm: display 1 là 1920×720, còn vùng vẽ thật đọc từ khung task là `[0,90]–[1920,810]`
 *    (số 90 là thật, xuất hiện ở MỌI dòng task chụp được).
 */
object ClusterSplit {
    /**
     * Dấu nhận biết "geometry này là một Ô, không phải cả cụm", đặt vào `AcceptedGeometry.profileId`.
     *
     * Vì sao đi nhờ trường sẵn có thay vì thêm trường mới: `CastTransaction.requestedGeometry` là thứ
     * DUY NHẤT trong nhật ký bền mang được rect qua tới lúc xác minh (`CastSessionStore.kt` đóng gói
     * đúng trường này ở `encodeGeometry(tx.requestedGeometry)`), và mọi trường mới thêm vào
     * `CastTransaction`/`CastSessionEnvelope` mà codec chưa biết sẽ BIẾN MẤT ở lần ghi kế tiếp — im
     * lặng, đúng loại lỗi tệ nhất. Cùng lối đi mà bản yêu cầu mật độ đang dùng
     * (`"cluster-density-request"`, CastManualIntent.kt).
     */
    const val SLOT_PROFILE_ID = "cluster-slot-request"

    /**
     * Khung VẼ của cụm — thứ mà một ô được cắt ra từ đó. Trả `null` khi chưa đo được.
     *
     * Vì sao KHÔNG dùng thẳng `observed.geometry.bounds`: trường đó là khung của DISPLAY, đọc bằng regex
     * trên `wmDisplays + displays` (CastDeviceParsers.kt), và trên xe nó ra `[0,0]–[1920,720]` trong khi
     * MỌI khung task thật lại là `[0,90]–[1920,810]`. Hai đại lượng lệch nhau đúng 90px theo trục dọc —
     * chính con số của bug F3 (xem KDoc `ObservedState.taskBounds`). Cắt ô từ khung display thì rect yêu
     * cầu sẽ lệch 90px so với rect mà framework thật sự đặt ra, và phép xác minh nghiêm ngặt sẽ "diverged"
     * mọi lần.
     *
     * Cách lấy, và vì sao từng nửa lấy ở một nguồn khác nhau:
     *  - KÍCH THƯỚC (rộng × cao) lấy từ khung display: đó là `app W x H` của `dumpsys display`, đại lượng
     *    của phần cứng/cấu hình, không phụ thuộc app nào đang nằm đâu. Đây cũng chính là cặp số mà
     *    `FIT_CLUSTER_COMPOSITE` đang dùng để phóng app ra toàn cụm (CastPlacementCommands.kt).
     *  - GỐC theo trục DỌC lấy từ khung task thật (`min` của `top` trên các task đang ở cụm): sản phẩm chỉ
     *    chia DỌC, nên mọi ô đều cao hết cụm và mọi task trên cụm đều có cùng `top`. Đo thật xác nhận:
     *    một app toàn màn `[0,90][1920,810]`, và hai app chia đôi `[0,90][960,810]` + `[960,90][1920,810]`
     *    — cả ba dòng cùng `top=90`. Vậy `min(top)` là hằng số của cụm chứ không phải của bố cục, đúng
     *    thứ cần cho một điểm gốc.
     *  - GỐC theo trục NGANG lấy từ khung display (`left`): `min(left)` của task thì KHÔNG phải hằng số —
     *    một app nằm một mình ở nửa PHẢI có `left=960`, lấy nó làm gốc là trượt cả bố cục sang phải.
     *
     * Trả `null` khi cụm KHÔNG có task nào: lúc đó số 90 không đo được từ đâu cả, và mượn tạm `top` của
     * display (=0) chính là cách bug F3 sinh ra lần đầu. Hệ quả sản phẩm được nói thẳng ra: muốn chia đôi
     * thì phải có sẵn một app trên cụm (chiếu bình thường trước — đường đã kiểm thực địa), rồi mới xếp ô.
     * **Chưa đo** một `am task resize 0 0 W H` từ cụm rỗng rơi xuống đâu, nên ở đây chọn chiều an toàn:
     * từ chối lập kế hoạch, thay vì đoán một điểm gốc rồi hỏng lúc xác minh.
     *
     * HAI CHỐT CHẶN, và cả hai đều nhằm biến một suy đoán sai thành một lời TỪ CHỐI tường minh:
     *
     *  - **Mọi task trên cụm phải cao ĐÚNG BẰNG dải, không chỉ nằm lọt trong dải.** Sản phẩm chỉ chia dọc
     *    nên mọi ô đều cao hết cụm; ba dòng dump thật đều thoả (`[0,90][1920,810]`, `[0,90][960,810]`,
     *    `[960,90][1920,810]` — cùng `top=90`, cùng `bottom=810`). Một task cao khác đi nghĩa là cụm này
     *    không hành xử như cụm đã đo ⇒ không cắt ô từ một giả định đã biết là sai.
     *  - **Chiều cao dải phải khớp chiều cao khung display.** Đây là phép kiểm chéo cho một điểm YẾU CÓ
     *    SẴN mà đợt này không sửa: khi cụm KHÔNG rỗng, `observed.geometry` được lấy bằng regex `mBounds`
     *    chạy trên `wmDisplays + displays` (CastDeviceParsers.kt) — **chưa đo** trên xe rằng dòng khớp đầu
     *    tiên chắc chắn là của display CỤM chứ không phải của màn giữa. Nếu lỡ là màn giữa (1920×1080),
     *    chiều cao dải đo được (810−90=720) sẽ không khớp và hàm trả `null` ⇒ planner từ chối, thay vì
     *    lặng lẽ cắt ra một ô cao 1080 rồi để phép xác minh báo "diverged" mà không ai hiểu vì sao.
     */
    fun contentRect(observed: ObservedState): CastRect? {
        val display = observed.geometry?.bounds ?: return null
        val width = display.right - display.left
        val height = display.bottom - display.top
        if (width < 2 || height < 1) return null
        val tasks = observed.taskBounds.values
        val top = tasks.minOfOrNull { it.top } ?: return null
        val bottom = tasks.maxOf { it.bottom }
        if (bottom - top != height) return null
        if (tasks.any { it.top != top || it.bottom != bottom }) return null
        val content = CastRect(display.left, top, display.left + width, bottom)
        if (tasks.any { it.left < content.left || it.right > content.right }) return null
        return content
    }

    /**
     * Dải để CẮT Ô — dùng được cả khi cụm đang RỖNG, khác [contentRect] đúng ở một chỗ: trục dọc.
     *
     * ★ VÌ SAO HÀM NÀY TỒN TẠI (2026-08-01, chủ dự án chốt luật sản phẩm: chạm nửa TRÁI thì app đang mở
     * vào nửa trái NGAY, nửa phải để trống chờ app sau).
     *
     * [contentRect] đòi bằng được một khung task thật để đọc ra gốc dọc, nên cụm rỗng ⇒ `null` ⇒ planner
     * từ chối ⇒ **không có đường nào đặt app ĐẦU TIÊN vào một nửa**. Lời từ chối ấy đúng với câu hỏi mà
     * [contentRect] trả lời ("dải VẼ thật của cụm là gì"), nhưng SAI với việc chia đôi thật sự cần: chia
     * đôi là quyết định MỘT CHIỀU (xem [ClusterSpan]). Hai app không đè nhau chừng nào hai dải NGANG rời
     * nhau — trục dọc hệ thống cho bao nhiêu cũng được, miễn cả hai nhận như nhau. Đòi biết trước trục dọc
     * là đòi một thứ vừa không kiểm soát được, vừa không cần.
     *
     * HAI NGUỒN, THEO ĐÚNG THỨ TỰ NÀY, KHÔNG ĐẢO (CLAUDE.md §6):
     *
     *  1. **Dải VẼ đo được** ([contentRect]) khi cụm đang có task. Đây là đường đã có phép đo thật đứng
     *     sau: đêm 31/7 hai lệnh `am task resize … 0 90 960 810` / `… 960 90 1920 810` cho hai app cùng
     *     hiện. Rect phát đi lúc ấy nằm trong CHÍNH dải này, nên giữ nó ở rung đầu là giữ nguyên đường đã
     *     đo — không phải chọn cho đẹp.
     *  2. **Khung DISPLAY** (`observed.geometry.bounds`) khi cụm đã CHỨNG MINH là rỗng. Không gian toạ độ
     *     này là thứ đường toàn cụm đã field-proven vẫn gửi đi mỗi lần cast: `FIT_CLUSTER_COMPOSITE` phát
     *     `am task resize <task> 0 0 W H` với đúng `app W x H` của `dumpsys display`
     *     (CastPlacementCommands.kt). Nên đây KHÔNG phải một không gian toạ độ mới bịa ra cho tính năng
     *     này; nó là đầu vào mà chính động từ ấy đã nhận hàng chục lần ngoài hiện trường.
     *
     * VÌ SAO NHÁNH 2 CHỈ MỞ KHI CỤM RỖNG — và vì sao "rỗng" phải chứng minh bằng ba vế:
     *  - `coarseState == IDLE_CLEAN` + `occupants` rỗng + `taskBounds` rỗng. Đúng ở trạng thái đó (và chỉ
     *    ở đó) `DumpObservedStateParser` dựng `geometry` THẲNG từ display cụm đã định danh
     *    (`CastRect(0, 0, namedDisplay.appWidth, namedDisplay.appHeight)`, CastDeviceParsers.kt:157-162).
     *    Ở mọi trạng thái khác, `geometry` là kết quả một regex `mBounds` chạy trên `wmDisplays + displays`
     *    và **chưa đo** được rằng dòng khớp đầu tiên chắc chắn thuộc display CỤM chứ không phải màn giữa —
     *    đúng điểm yếu mà [contentRect] dựng phép kiểm chéo chiều cao để bắt. Cho nhánh 2 chạy ở đó là tự
     *    tay vô hiệu hoá phép kiểm chéo ấy: một cụm có task nhưng bố cục lạ sẽ lặng lẽ được cắt ô từ khung
     *    của MÀN GIỮA. Nên cụm có task mà [contentRect] không đọc nổi thì câu trả lời vẫn là `null`.
     *
     * MỨC BẰNG CHỨNG (CLAUDE.md §2):
     *  - **Đã đo**: trục NGANG là ánh xạ đồng nhất trong không gian display (0 / 960 / 1920 gõ vào, đọc
     *    lại ra đúng 0 / 960 / 1920 — xem [ClusterSpan]).
     *  - **Chưa đo**: xin `top=0, bottom=H` thì hệ thống đặt task ở dải dọc nào. Đó chính là lý do rect
     *    sinh ra ở đây KHÔNG được đem so trục dọc lúc xác minh — `CastCoordinator.layoutMatchesObservation`
     *    chỉ so dải ngang, và chỉ đòi các ô NHẤT QUÁN với nhau theo trục dọc. Đây là "chưa đo thì không
     *    khẳng định", không phải "đoán một con số rồi so với chính nó".
     */
    fun slotBand(observed: ObservedState): CastRect? {
        contentRect(observed)?.let { return it }
        if (observed.coarseState != ObservedCoarseState.IDLE_CLEAN ||
            observed.occupants.isNotEmpty() || observed.taskBounds.isNotEmpty()
        ) return null
        val display = observed.geometry?.bounds ?: return null
        if (display.right - display.left < 2 || display.bottom - display.top < 1) return null
        return display
    }

    /**
     * Bất biến "hai ô không đè nhau", một nguồn duy nhất cho cả [ClusterSlotLayout.resolve] (trả `null`)
     * lẫn `init` của [IntendedClusterLayout] (ném) — hai cách nói KHÔNG của cùng một luật.
     *
     * Chỉ xét trục NGANG: đó là trục duy nhất phép chia này quyết định, và cũng là trục duy nhất quyết
     * định hai app có che nhau hay không (xem [ClusterSpan]).
     */
    internal fun tileable(rects: Collection<CastRect>): Boolean {
        val spans = ArrayList<ClusterSpan>(rects.size)
        rects.forEach { spans += it.horizontalSpan ?: return false }
        for (i in spans.indices) {
            for (j in i + 1 until spans.size) if (spans[i].overlaps(spans[j])) return false
        }
        return true
    }

    /**
     * Rect của một ô bên trong [content]. Trả `null` khi khung hẹp tới mức không chia nổi hai ô dương.
     *
     * Ranh giới là DUY NHẤT cho cả hai ô (`left + rộng*percent/100`), nên ô trái kết thúc ĐÚNG chỗ ô phải
     * bắt đầu: không chồng một pixel nào (chồng ⇒ che nhau, đã đo) và không hở một pixel nào.
     * Kiểm bằng số đo thật: rộng 1920, 50% ⇒ ranh giới 960 ⇒ `[0,90][960,810]` và `[960,90][1920,810]`,
     * trùng khít hai dòng `am task resize` đã gõ trên xe.
     */
    fun slotRect(content: CastRect, slot: ClusterSlot): CastRect? {
        val width = content.right - content.left
        if (width < 2 || content.bottom <= content.top) return null
        val leftWidth = ((width.toLong() * slot.ratio.leftPercent) / 100L).toInt().coerceIn(1, width - 1)
        val boundary = content.left + leftWidth
        return when (slot.side) {
            ClusterSlotSide.LEFT -> CastRect(content.left, content.top, boundary, content.bottom)
            ClusterSlotSide.RIGHT -> CastRect(boundary, content.top, content.right, content.bottom)
        }
    }
}
enum class AdjustmentApplyState {
    SAVED_ONLY, APPLYING, APPLIED_VERIFIED, FAILED_UNVERIFIED, FAILED_RESTORED, FROZEN,
}
data class AdjustmentDraft(
    val target: CastTarget,
    val expectedDisplayIdentity: String,
    val durableEpoch: Long,
    val acceptedGeometry: AcceptedGeometry,
    val entrySnapshot: AcceptedGeometry,
    val localDraft: AcceptedGeometry,
    val previousVerifiedApply: AcceptedGeometry?,
    val lastVerifiedApply: AcceptedGeometry?,
    val operationId: UUID?,
    val dirty: Boolean,
    val applyState: AdjustmentApplyState,
    val validationError: String?,
)

class CastBaseline(
    occupants: Set<String> = emptySet(),
    val geometry: AcceptedGeometry? = null,
    animationPerKey: Map<String, String> = emptyMap(),
    val pipMode: String? = null,
    val profile: String? = null,
) {
    val occupants: Set<String> = immutableSet(occupants)
    val animationPerKey: Map<String, String> = immutableMap(animationPerKey)
    override fun equals(other: Any?) = other is CastBaseline && occupants == other.occupants &&
        geometry == other.geometry && animationPerKey == other.animationPerKey &&
        pipMode == other.pipMode && profile == other.profile
    override fun hashCode() = valueHash(occupants, geometry, animationPerKey, pipMode, profile)
    override fun toString() = "CastBaseline(occupants=$occupants, geometry=$geometry, animationPerKey=$animationPerKey, pipMode=$pipMode, profile=$profile)"
}

data class LedgerStep(
    val stepId: String, val precondition: String, val commandKind: CommandKind,
    val gatewayGeneration: Long, val issuedAtEpochMillis: Long?, val effect: LedgerEffect,
    val compensation: CommandKind?, val compensationObserved: Boolean,
    val compensationIssuedAtEpochMillis: Long? = null,
    val compensationGatewayGeneration: Long? = null,
    val compensationEffect: LedgerEffect = LedgerEffect.PLANNED,
)

data class StableCastSession(
    val state: StableState, val engineVersion: EngineVersion, val createdByBuild: String,
    val profileExport: String?, val expectedDisplayIdentity: String, val baseline: CastBaseline,
    val activeTarget: CastTarget?, val protectedResidue: ProtectedResidue?,
    val acceptedGeometry: AcceptedGeometry?, val lastVerifiedAtEpochMillis: Long,
)

class CastTransaction(
    val operationId: UUID, val epoch: Long, val operation: CastOperation, val phase: OperationPhase,
    val sourcePkg: String?, val targetPkg: String?, val targetClass: String?,
    val expectedDisplayIdentity: String, val baseline: CastBaseline, ledger: List<LedgerStep>,
    val retries: Int, val deadlineAtEpochMillis: Long, val lastFailure: String?,
    val expectedPostcondition: String, val compensationUsed: Boolean,
    val requestedGeometry: AcceptedGeometry? = null,
) {
    val ledger: List<LedgerStep> = immutableList(ledger)
    override fun equals(other: Any?) = other is CastTransaction && operationId == other.operationId &&
        epoch == other.epoch && operation == other.operation && phase == other.phase &&
        sourcePkg == other.sourcePkg && targetPkg == other.targetPkg && targetClass == other.targetClass &&
        expectedDisplayIdentity == other.expectedDisplayIdentity && baseline == other.baseline &&
        ledger == other.ledger && retries == other.retries && deadlineAtEpochMillis == other.deadlineAtEpochMillis &&
        lastFailure == other.lastFailure && expectedPostcondition == other.expectedPostcondition &&
        compensationUsed == other.compensationUsed && requestedGeometry == other.requestedGeometry
    override fun hashCode() = valueHash(operationId, epoch, operation, phase, sourcePkg, targetPkg, targetClass,
        expectedDisplayIdentity, baseline, ledger, retries, deadlineAtEpochMillis, lastFailure,
        expectedPostcondition, compensationUsed, requestedGeometry)
    override fun toString() = "CastTransaction(operationId=$operationId, epoch=$epoch, operation=$operation, phase=$phase, ledger=$ledger)"
}

data class CastSessionEnvelope(
    val schemaVersion: Int = CAST_ENVELOPE_SCHEMA_VERSION,
    val checksum: String = "",
    val durableEpoch: Long,
    val bootId: String,
    val stopRequested: Boolean,
    val pendingIntent: PendingCastIntent?,
    val effectiveUiVersion: EngineVersion,
    val pendingUiRollback: Boolean,
    val stableSession: StableCastSession?,
    val transaction: CastTransaction?,
    val adjustmentDraft: AdjustmentDraft? = null,
    val automationConfig: AutomationConfig = AutomationConfig(),
    val bootAutomationRequest: BootAutomationRequest? = null,
    val lastAutomationOutcome: AutomationOutcome? = null,
) {
    init {
        require(bootAutomationRequest == null || bootAutomationRequest.bootId == bootId) {
            "automation request must belong to the envelope boot"
        }
    }

    /** Target of the current pending placement regardless of origin. */
    val pendingPackage: String? get() = pendingIntent?.packageName

    /** True only for a pending placement created by the user, never by automation. */
    val pendingIsUser: Boolean get() = pendingIntent?.origin == CastIntentOrigin.USER

    fun withPendingPackage(packageName: String?): CastSessionEnvelope =
        copy(pendingIntent = packageName?.let { PendingCastIntent(it) })
}

sealed interface ObservationValue<out T> {
    data class Known<T>(val value: T) : ObservationValue<T>
    data class Unknown(val reason: String) : ObservationValue<Nothing>
    data class Unsupported(val reason: String) : ObservationValue<Nothing>
}

data class RawObservation(
    val amStacks: String,
    val wmDisplays: String,
    val displays: String,
    val profile: String,
    val animations: String = "",
    val appOps: String = "",
)

enum class ObservedCoarseState { UNKNOWN, IDLE_CLEAN, ACTIVE_SINGLE, ACTIVE_MULTI, DIRTY_IDLE, SPLIT_BRAIN }
class ObservedState(
    val coarseState: ObservedCoarseState,
    val displayIdentity: String?,
    val target: CastTarget?,
    occupants: Set<String>,
    val protectedResidue: ProtectedResidue?,
    val geometry: AcceptedGeometry?,
    animationPerKey: Map<String, String> = emptyMap(),
    val pipMode: String? = null,
    val profile: String? = null,
    val displayName: String? = null,
    /**
     * Khung THẬT của từng task đang đậu trên display cụm, khoá theo `taskId`, đọc từ `am stack list`.
     *
     * ★ VÌ SAO CÓ (B3 / T6, docs/specs/cast-one-mode-and-three-zone-bubble.html §F3 + §T6): [geometry]
     * ở trên là khung của DISPLAY (regex chạy trên `wmDisplays + displays`, xem
     * CastDeviceParsers.kt), trong khi thứ mà đường resize thật sự đổi là rect của MỘT TASK
     * (`am task resize <taskId> l t r b`, CastGeometry.kt:45). Hai đại lượng khác loại, nên
     * `CastCoordinator.completeVerificationLocked` (CastCoordinator.kt:379-381) đem so bằng
     * `observed.geometry == tx.requestedGeometry` thì chỉ đúng khi rect yêu cầu tình cờ bằng nguyên
     * khung display — mọi lần resize THẬT đều verify ra "diverged" rồi ghim transaction vào RECOVERING.
     * Đó là lý do tính năng chỉnh kích thước chưa từng chạy nổi một lần nào trên xe.
     *
     * Trường này chỉ làm cho sự thật đó QUAN SÁT ĐƯỢC; phép so ở CastCoordinator CỐ Ý chưa đổi trong
     * task này (T6 chỉ dựng nền, T7 mới tiêu thụ) — đổi cả hai cùng lúc thì lúc hồi quy không tách nổi
     * bên nào sai.
     *
     * Mức bằng chứng (CLAUDE.md §2): **đã chứng minh bằng đọc source** (hai file:line ở trên) —
     * **chưa đo lại trên xe** sau bản vá. Định dạng dòng task thì là dump THẬT lấy trên DiLink3 tối
     * 31/07, xem fixture trong CastTaskBoundsObservationTest.
     *
     * Chỉ chứa task trên ĐÚNG display cụm (CLAUDE.md §4: phạm vi tường minh, không quét mù cả máy), và
     * chỉ chứa task mà dòng dump có `bounds=` đọc được — dump thật có dòng task KHÔNG in bounds, khi đó
     * task ấy vắng mặt ở đây thay vì bịa ra một rect.
     */
    taskBounds: Map<Int, CastRect> = emptyMap(),
) {
    val occupants: Set<String> = immutableSet(occupants)
    val animationPerKey: Map<String, String> = immutableMap(animationPerKey)
    val taskBounds: Map<Int, CastRect> = immutableMap(taskBounds)

    /**
     * Khung thật của task đang được coi là mục tiêu — `null` khi chưa có mục tiêu (IDLE_CLEAN, hoặc
     * ACTIVE_MULTI mập mờ không ai thắng) hoặc khi dòng task ấy không in `bounds=`.
     *
     * Đây là đại lượng ĐÚNG LOẠI để đối chiếu với `CastTransaction.requestedGeometry.bounds`; xem
     * KDoc của [taskBounds].
     */
    val targetTaskBounds: CastRect? get() = target?.let { this.taskBounds[it.taskId] }

    fun copy(
        coarseState: ObservedCoarseState = this.coarseState,
        displayIdentity: String? = this.displayIdentity,
        target: CastTarget? = this.target,
        occupants: Set<String> = this.occupants,
        protectedResidue: ProtectedResidue? = this.protectedResidue,
        geometry: AcceptedGeometry? = this.geometry,
        animationPerKey: Map<String, String> = this.animationPerKey,
        pipMode: String? = this.pipMode,
        profile: String? = this.profile,
        displayName: String? = this.displayName,
        taskBounds: Map<Int, CastRect> = this.taskBounds,
    ) = ObservedState(
        coarseState, displayIdentity, target, occupants, protectedResidue, geometry,
        animationPerKey, pipMode, profile, displayName, taskBounds,
    )
    // [taskBounds] NẰM TRONG equals/hashCode có chủ ý: `completeVerificationLocked` chốt bằng hai mẫu
    // quan sát BẰNG NHAU liên tiếp (CastCoordinator.kt:391-392). Khung task đang là thứ vừa bị đổi bởi
    // `am task resize`, nên nó chính là đại lượng phải ĐỨNG YÊN mới được coi là đã lắng — bỏ nó khỏi
    // equals là tự cho phép chốt trong lúc khung còn đang trượt.
    override fun equals(other: Any?) = other is ObservedState && coarseState == other.coarseState &&
        displayIdentity == other.displayIdentity && target == other.target && occupants == other.occupants &&
        protectedResidue == other.protectedResidue && geometry == other.geometry &&
        animationPerKey == other.animationPerKey && pipMode == other.pipMode && profile == other.profile &&
        displayName == other.displayName && taskBounds == other.taskBounds
    override fun hashCode() = valueHash(
        coarseState, displayIdentity, target, occupants, protectedResidue, geometry,
        animationPerKey, pipMode, profile, displayName, taskBounds,
    )
    override fun toString() = "ObservedState(coarseState=$coarseState, displayIdentity=$displayIdentity, target=$target, occupants=$occupants, taskBounds=$taskBounds)"
}

enum class CastIntentKind { CAST, SWITCH, STOP, RECOVER, APPLY_GEOMETRY }
data class DisconnectedSinkRecoveryProof(
    val ownerPackage: String,
    val first: ObservedState,
    val second: ObservedState,
    val phoneDisconnected: Boolean,
    val projectionComponent: Boolean,
    val consequenceConfirmed: Boolean,
)
data class CastIntent(
    val kind: CastIntentKind,
    val targetPackage: String? = null,
    val targetComponent: String? = null,
    val geometry: AcceptedGeometry? = null,
    val expectedTarget: CastTarget? = null,
    val recoveryProof: DisconnectedSinkRecoveryProof? = null,
    /** Explicit user opt-in to the destructive R3 rung (force-stop + clear-task relaunch). */
    val allowDestructive: Boolean = false,
    /**
     * Bố cục ô mà lượt đặt này phục vụ, hoặc `null` = chiếu toàn cụm y như hôm nay.
     *
     * `null` là mặc định VÀ là đường đang chạy ngoài hiện trường: khi nó `null`, không một nhánh nào
     * trong [CastPlanner] rẽ khác đi so với trước bản vá — đó là điều kiện đầu tiên của đợt thay đổi này.
     * [targetPackage] bắt buộc phải có tên trong `slotLayout.packages`: lượt này đặt đúng MỘT app, và ô
     * của nó phải được gọi tên tường minh chứ không suy ra.
     */
    val slotLayout: ClusterSlotLayout? = null,
)
data class PlannerSnapshot(
    val observed: ObservationValue<ObservedState>, val stableSession: StableCastSession?,
    val targetClass: TargetClass, val installed: Boolean, val hasLauncher: Boolean, val plannerEpoch: Long,
)
data class PlannedStep(val id: String, val commandKind: CommandKind, val guard: String)
class CastPlan(
    val operation: CastOperation,
    val epoch: Long,
    steps: List<PlannedStep>,
    val expectedPostcondition: String,
    plannerAllowedActions: Set<CastAction>,
    val targetClass: TargetClass? = null,
    val expectedDisplayIdentity: String? = null,
    val expectedTarget: CastTarget? = null,
    val geometry: AcceptedGeometry? = null,
    /**
     * Bố cục đã quy ra rect mà lượt đặt này hứa hẹn, hoặc `null` = không hứa gì (đường toàn cụm hôm nay).
     *
     * Nằm ở CUỐI danh sách tham số và có mặc định `null` để mọi chỗ dựng [CastPlan] theo vị trí — kể cả
     * `CastCoordinator.clearCluster` và cả tá test hiện có — không phải sửa một chữ. [CastPlan] là vật
     * thể tạm trong RAM, không đi qua `CastEnvelopeCodec`, nên thêm trường ở đây không đụng tới định
     * dạng bền.
     */
    val intendedLayout: IntendedClusterLayout? = null,
) {
    val steps: List<PlannedStep> = immutableList(steps)
    val plannerAllowedActions: Set<CastAction> = immutableSet(plannerAllowedActions)
    override fun equals(other: Any?) = other is CastPlan && operation == other.operation && epoch == other.epoch &&
        steps == other.steps && expectedPostcondition == other.expectedPostcondition &&
        plannerAllowedActions == other.plannerAllowedActions && targetClass == other.targetClass &&
        expectedDisplayIdentity == other.expectedDisplayIdentity && expectedTarget == other.expectedTarget &&
        geometry == other.geometry && intendedLayout == other.intendedLayout
    override fun hashCode() = valueHash(operation, epoch, steps, expectedPostcondition, plannerAllowedActions,
        targetClass, expectedDisplayIdentity, expectedTarget, geometry, intendedLayout)
    override fun toString() = "CastPlan(operation=$operation, epoch=$epoch, steps=$steps, expectedPostcondition=$expectedPostcondition, plannerAllowedActions=$plannerAllowedActions, targetClass=$targetClass, expectedDisplayIdentity=$expectedDisplayIdentity, expectedTarget=$expectedTarget, geometry=$geometry, intendedLayout=$intendedLayout)"
}
sealed interface PlanResult {
    data class Ready(val plan: CastPlan) : PlanResult
    data class Blocked(val reason: String) : PlanResult
}

data class ManifestCase(
    val id: Int, val name: String, val successTerminal: String, val failureTerminal: String,
    val retryPolicy: String, val evidenceGate: String,
)

enum class CastAction {
    CAST, SWITCH, ADJUST, STOP, CHOOSE_ANOTHER_APP, OPEN_APP_MANAGER, RETRY_CONNECT,
    OPEN_DIAGNOSTICS, REQUEST_PHONE_DISCONNECT, TRY_ELIGIBLE_RECOVERY_ONCE,
    SHOW_PHYSICAL_INSTRUCTION, OPEN_PROFILE_SETUP,

    /**
     * Chọn app sẽ chiếu. Đây là phép ghi TUỲ CHỌN CỤC BỘ: không phát lệnh nào ra xe, không đụng
     * transaction, không đổi gì trên cụm — việc chiếu chỉ xảy ra khi bấm Chiếu. Vì thế nó nằm trong
     * nhóm luôn được phép, cùng với Chẩn đoán.
     *
     * Có mục này vì 2026-07-27 phát hiện tile app bị vô hiệu hoá theo `OPEN_APP_MANAGER ||
     * CHOOSE_ANOTHER_APP`; ở trạng thái "cần xử lý thủ công" projector không cấp hai phép đó, nên
     * người dùng không chọn nổi app để chuẩn bị — bị khoá đúng như sáng cùng ngày.
     */
    SELECT_TARGET_APP,
}

/**
 * Những phép KHÔNG phát lệnh nào ra xe.
 *
 * Một nguồn duy nhất, vì ngày 2026-07-27 danh sách này từng tồn tại ở hai chỗ — `alwaysAllowed` trong
 * projector và `NON_DISPATCHING` trong tầng UI — rồi lệch nhau ngay lần sửa kế tiếp. Ai cần biết "phép
 * này có đụng xe không" thì đọc ở đây.
 *
 * `OPEN_APP_MANAGER` nằm trong danh sách vì màn cấu hình chỉ ghi tuỳ chọn cục bộ; phép `cast` duy nhất
 * bên trong nó vẫn đi qua cổng engine như mọi lần chiếu khác.
 */
val NON_DISPATCHING_ACTIONS: Set<CastAction> = setOf(
    CastAction.OPEN_DIAGNOSTICS,
    CastAction.SELECT_TARGET_APP,
    CastAction.OPEN_APP_MANAGER,
)
enum class CoarseState {
    UNKNOWN, COLD_PRISTINE, LEGACY_ACTIVE_READ_ONLY, PREPARING, ACTIVATING, SWITCHING, VERIFYING,
    STOP_REQUESTED, RESTORING, RECOVERING, IDLE_VERIFIED, ACTIVE_VERIFIED,
    ACTIVE_DEGRADED, RECOVERY_PENDING, MANUAL_REQUIRED,
}
enum class DisabledReason {
    NO_ACTIVE_TARGET, OPERATION_IN_FLIGHT, LEGACY_SESSION_UNSAFE, CONTRACT_UNMAPPED,
    PROTECTED_SESSION, GEOMETRY_LIMITED, RECOVERY_PENDING, ACTION_NOT_EXPORTED,
}
enum class InteractionContextValue { PARKED, MOVING, UNKNOWN }
enum class NextSafeAction {
    NONE, REQUEST_STOP, RETRY_CONNECT_BOUNDED, WAIT_AND_OBSERVE, OPEN_DIAGNOSTICS,
    REQUEST_PHONE_DISCONNECT, TRY_ELIGIBLE_RECOVERY_ONCE,
    SHOW_PHYSICAL_INSTRUCTION, OPEN_PROFILE_SETUP,
}
enum class StopDispositionKind { AVAILABLE, REQUESTED, IN_PROGRESS, COMPLETED, UNAVAILABLE }
enum class UnavailableReason {
    LEGACY_SESSION_UNSAFE, OBSERVATION_EXHAUSTED, COMPENSATION_EXHAUSTED,
    RECOVERY_ACTION_ONLY, PROTECTED_SESSION, ONE_ATTEMPT_EXHAUSTED, OCCLUSION_NOT_PROVEN,
    BASELINE_UNKNOWN, PROFILE_UNSUPPORTED_ACTIVE, CONTRACT_UNMAPPED,
}
enum class RecoverySubstate {
    UNKNOWN_EFFECT_STOP_AVAILABLE, UNKNOWN_EFFECT_STOP_REQUESTED, UNKNOWN_EFFECT_STOP_IN_PROGRESS,
    TRANSPORT_PREMUTATION_IDLE, TRANSPORT_ACTIVE_STOP_AVAILABLE,
    OBSERVATION_DIVERGED_STOP_AVAILABLE, OBSERVATION_DIVERGED_WAITING,
    OBSERVATION_BUDGET_EXHAUSTED, COMPENSATION_IN_PROGRESS, COMPENSATION_EXHAUSTED,
    PROTECTED_SINK_CONNECTED, DISCONNECTED_SINK_ELIGIBLE,
    DISCONNECTED_SINK_ATTEMPT_EXHAUSTED, OCCLUSION_FAILED_CONNECTED,
    OCCLUSION_FAILED_DISCONNECTED, JOURNAL_CORRUPT_DIAGNOSTIC,
    JOURNAL_CORRUPT_MANUAL, UNSUPPORTED_PROFILE_IDLE, UNSUPPORTED_PROFILE_ACTIVE_UNKNOWN,
}
data class StopDisposition(val kind: StopDispositionKind, val reason: UnavailableReason? = null)
data class InteractionContext(
    val value: InteractionContextValue, val provenance: String, val observedAt: Instant,
    val freshUntil: Instant, val disagreementReason: String?,
)
class CastUiStateV2(
    val schemaVersion: Int,
    val schemaHash: Sha256LowerHex,
    val coarseState: CoarseState,
    val stableState: StableState?,
    val operationPhase: OperationPhase?,
    val recoverySubstate: RecoverySubstate?,
    val stopDisposition: StopDisposition,
    val nextSafeAction: NextSafeAction,
    val unavailableReason: UnavailableReason?,
    allowedActions: Set<CastAction>,
    disabledReasons: Map<CastAction, DisabledReason>,
    val interactionContext: InteractionContext,
    val target: CastTarget?,
    val protectedResidue: ProtectedResidue?,
    val acceptedGeometry: AcceptedGeometry?,
    val operationId: UUID?,
    val durableEpoch: Long,
    val deadlineAt: Instant?,
    val automationDisposition: AutomationDisposition? = null,
    val automationReason: AutomationReason? = null,
    val automationTargetPackage: String? = null,
) {
    val allowedActions: Set<CastAction> = immutableSet(allowedActions)
    val disabledReasons: Map<CastAction, DisabledReason> = immutableMap(disabledReasons)
    override fun equals(other: Any?) = other is CastUiStateV2 && schemaVersion == other.schemaVersion &&
        schemaHash == other.schemaHash && coarseState == other.coarseState && stableState == other.stableState &&
        operationPhase == other.operationPhase && recoverySubstate == other.recoverySubstate &&
        stopDisposition == other.stopDisposition && nextSafeAction == other.nextSafeAction &&
        unavailableReason == other.unavailableReason && allowedActions == other.allowedActions &&
        disabledReasons == other.disabledReasons && interactionContext == other.interactionContext &&
        target == other.target && protectedResidue == other.protectedResidue &&
        acceptedGeometry == other.acceptedGeometry && operationId == other.operationId &&
        durableEpoch == other.durableEpoch && deadlineAt == other.deadlineAt &&
        automationDisposition == other.automationDisposition && automationReason == other.automationReason &&
        automationTargetPackage == other.automationTargetPackage
    override fun hashCode() = valueHash(schemaVersion, schemaHash, coarseState, stableState, operationPhase,
        recoverySubstate, stopDisposition, nextSafeAction, unavailableReason, allowedActions, disabledReasons,
        interactionContext, target, protectedResidue, acceptedGeometry, operationId, durableEpoch, deadlineAt,
        automationDisposition, automationReason, automationTargetPackage)
}

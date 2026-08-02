package com.byd.clusternav.modules.clustercast

import com.byd.clusternav.modules.clustercast.v2.AdjustmentApplyState
import com.byd.clusternav.modules.clustercast.v2.AdjustmentDraft
import com.byd.clusternav.modules.clustercast.v2.AdjustmentResult
import android.content.Context
import com.byd.clusternav.modules.clustercast.v2.AutomationConfig
import com.byd.clusternav.cast.platform.CastAndroidRuntime
import com.byd.clusternav.modules.clustercast.v2.CastIntentOrigin
import com.byd.clusternav.modules.clustercast.v2.CastManualIntentResult
import com.byd.clusternav.modules.clustercast.v2.CastManualTargetReader
import com.byd.clusternav.modules.clustercast.v2.CastSessionEnvelope
import com.byd.clusternav.modules.clustercast.v2.ClusterStyle
import com.byd.clusternav.cast.platform.CastAppCatalog
import com.byd.clusternav.modules.clustercast.v2.CastIntent
import com.byd.clusternav.modules.clustercast.v2.CastRolloutFlags
import com.byd.clusternav.modules.clustercast.v2.ExecutionResult
import com.byd.clusternav.modules.clustercast.v2.PlanResult
import com.byd.clusternav.modules.clustercast.v2.TargetEvidence
import com.byd.clusternav.modules.clustercast.v2.CastOperationLog
import com.byd.clusternav.modules.clustercast.v2.CastRenderModel
import com.byd.clusternav.modules.clustercast.v2.CastRuntimeUi
import com.byd.clusternav.modules.clustercast.v2.AcceptedGeometry
import com.byd.clusternav.modules.clustercast.v2.CastIntentKind
import com.byd.clusternav.modules.clustercast.v2.CastRolloutRegistry
import com.byd.clusternav.modules.clustercast.v2.CastTarget
import com.byd.clusternav.modules.clustercast.v2.DisconnectedSinkRecoveryProof
import com.byd.clusternav.modules.clustercast.v2.EngineVersion
import com.byd.clusternav.modules.clustercast.v2.CastManualTargetEligibility
import com.byd.clusternav.modules.clustercast.v2.CastUiRenderer
import com.byd.clusternav.modules.clustercast.v2.CastVehicleProfileCatalog
import com.byd.clusternav.modules.clustercast.v2.eligibilityFor
import com.byd.clusternav.modules.clustercast.v2.BubbleProjection
import com.byd.clusternav.modules.clustercast.v2.BubbleZone
import com.byd.clusternav.modules.clustercast.v2.BubbleZoneAction
import com.byd.clusternav.modules.clustercast.v2.CastBubbleProjection
import com.byd.clusternav.modules.clustercast.v2.CastManualTargetSnapshot
import com.byd.clusternav.modules.clustercast.v2.CastRect
import com.byd.clusternav.modules.clustercast.v2.ClusterOccupancy
import com.byd.clusternav.modules.clustercast.v2.ClusterOccupant
import com.byd.clusternav.modules.clustercast.v2.ClusterSlot
import com.byd.clusternav.modules.clustercast.v2.ClusterSlotLayout
import com.byd.clusternav.modules.clustercast.v2.ClusterSlotSide
import com.byd.clusternav.modules.clustercast.v2.ClusterSplit
import com.byd.clusternav.modules.clustercast.v2.ClusterSplitRatio
import com.byd.clusternav.modules.clustercast.v2.DisabledReason
import com.byd.clusternav.modules.clustercast.v2.ObservationValue
import com.byd.clusternav.modules.clustercast.v2.ObservedState
import com.byd.clusternav.modules.clustercast.v2.StoreRead
import com.byd.clusternav.modules.clustercast.v2.CastAmStackParser
import com.byd.clusternav.modules.clustercast.v2.CastDumpParse
import com.byd.clusternav.modules.clustercast.v2.ClusterClearResult
import java.time.Instant
import java.util.UUID

/**
 * Bề mặt duy nhất mà UI được phép thấy của Cluster Cast.
 *
 * Lý do tồn tại: trước 2026-07-27, mười bốn file UI gọi thẳng vào `runtime.coordinator`,
 * `runtime.store` và `runtime.gateway`. Hệ quả đo được ngay trong ngày là UI có thể vô tình chạm vào
 * transport ngay trong đường vẽ màn hình và làm treo màn hình. Khi mọi truy cập đi qua một chỗ, ranh
 * giới trở thành thứ kiểm được, và tầng dưới có thể thay bằng bản giả để test mọi trạng thái mà không
 * cần xe.
 *
 * Ở giai đoạn này façade còn là lớp uỷ nhiệm mỏng — nói thẳng như vậy chứ không tô vẽ. Việc thu hẹp
 * thật sẽ diễn ra ở S1 khi các capability được định nghĩa lại; điều façade mua được ngay hôm nay là một
 * đường ranh duy nhất và đo được, thay vì mười bốn đường rò.
 */
class CastFacade private constructor(
    private val runtime: CastAndroidRuntime.Runtime,
    private val catalog: CastAppCatalog,
) {

    /**
     * Đọc trạng thái target cho tầng dưới.
     *
     * Trước 2026-07-27 ba file UI phải tự lắp `CastManualTargetReader { catalog.snapshot(...) }` rồi truyền
     * xuống — tức màn hình phải biết tầng dưới cần đọc gì về target. Façade tự lắp từ catalog nó đang giữ.
     */
    private fun targets(): CastManualTargetReader =
        CastManualTargetReader { catalog.snapshot(it, phoneSession(it)) }


    /** Quan sát trạng thái cụm. Đây là I/O: đừng gọi trên main thread. */
    fun observe(): ObservationValue<ObservedState> = runtime.coordinator.observe()

    fun observe(deadlineAtEpochMillis: Long): ObservationValue<ObservedState> =
        runtime.coordinator.observe(deadlineAtEpochMillis)

    /**
     * Trạng thái đã quan sát được, hoặc null khi không đọc được.
     *
     * Có mặt vì call site cũ phải tự bóc `as? ObservationValue.Known` bằng tên đầy đủ dài dòng ở sáu
     * chỗ khác nhau — mỗi chỗ là một dịp để bóc sai.
     */
    fun observedState(): ObservedState? = (observe() as? ObservationValue.Known)?.value

    /**
     * MỘT lần quan sát, trả cả giá trị thô lẫn bản đã bóc.
     *
     * Có mặt vì `CastActivityRefreshReader` cần cả hai cho cùng một khoảnh khắc: giá trị thô để render
     * (nó mang cả lý do khi không đọc được), bản đã bóc để đọc target/geometry. Trước 2026-07-29 nó gọi
     * `observe()` rồi `observedState()` — HAI vòng quan sát ra xe cho một lần vẽ, và hai sự thật có thể
     * lệch nhau. Trả cặp ở đây để tầng trên không phải tự bóc `ObservationValue` (kiểu của tầng dưới
     * không lọt qua ranh giới — xem `CastArchitectureRatchetTest`).
     */
    fun observeBoth(): Pair<ObservationValue<ObservedState>, ObservedState?> {
        val observation = observe()
        return observation to (observation as? ObservationValue.Known)?.value
    }

    /**
     * Gói đang thật sự hiện trên [displayId] lúc này, hoặc null khi không xác định được.
     *
     * Không phát thêm lệnh shell nào — đọc lại đúng `amStacks` thô mà mọi observation đã tự fetch,
     * rồi lọc theo `visible=true` (xem [CastAmStackSnapshot.foregroundPackage]). Dùng cho nút nổi:
     * chạm một lần là chiếu đúng app đang mở, không cần chọn từ danh sách.
     */
    fun foregroundPackage(displayId: Int, excluded: Set<String>): String? {
        val raw = (runtime.coordinator.inspectRaw() as? ObservationValue.Known)?.value ?: return null
        val snapshot = (CastAmStackParser.parse(raw.amStacks) as? CastDumpParse.Known)?.value ?: return null
        return snapshot.foregroundPackage(displayId, excluded)
    }

    /** Đọc trạng thái bền. */
    fun storeRead(): StoreRead = runtime.store.locked { read() }

    /**
     * Envelope bền hiện tại, hoặc null nếu store rỗng/hỏng.
     *
     * Có mặt vì bốn call site phải tự bóc `as? StoreRead.Loaded` — mỗi chỗ là một dịp bóc sai, và nó
     * buộc UI phải biết kiểu StoreRead của tầng dưới chỉ để lấy một envelope.
     */
    fun envelope(): CastSessionEnvelope? = (storeRead() as? StoreRead.Loaded)?.envelope

    /**
     * Dựng mô hình hiển thị từ trạng thái bền và một quan sát tươi.
     *
     * Có mặt để UI không phải tự ghép `StoreRead` với `ObservationValue` rồi gọi projector — ba kiểu của
     * tầng dưới cho một việc mà UI chỉ cần kết quả.
     */
    fun renderModel(
        now: Instant = Instant.now(),
        stopRequestedAt: Instant? = null,
        destructiveRecoveryEligible: Boolean? = null,
        phoneSessionConnected: Boolean? = null,
        observation: ObservationValue<ObservedState> = observe(),
    ): CastRenderModel = CastRuntimeUi.render(
        storeRead(), observation, now, stopRequestedAt, destructiveRecoveryEligible, phoneSessionConnected,
    )

    /**
     * Một dòng mô tả trạng thái store, dùng cho màn Chẩn đoán.
     *
     * Có mặt để màn chẩn đoán không phải biết bốn nhánh của kiểu StoreRead chỉ để in ra chữ.
     */
    fun storeStatusLine(): String = when (val read = storeRead()) {
        is StoreRead.Loaded -> "store=LOADED"
        StoreRead.Empty -> "store=EMPTY"
        is StoreRead.Corrupt -> "store=CORRUPT:${read.reason}"
        is StoreRead.UnsupportedSchema -> "store=UNSUPPORTED:${read.version}"
    }

    /** Ghi một dòng vào nhật ký thao tác. */
    fun recordOperation(line: String) = CastOperationLog.record(line)

    /** Nhật ký thao tác, chỉ đọc. */
    fun operationLog(): String = CastOperationLog.render()

    /** Điện thoại có đang giữ phiên chiếu của app này không. Null nghĩa là không xác định được. */
    fun phoneSession(packageName: String): Boolean? = runtime.gateway.connectedPhoneSession(packageName)

    fun automationConfig(): AutomationConfig = runtime.automation.config()

    /**
     * Có đổi được kiểu cụm (cong/thẳng) trên xe hiện tại không.
     *
     * Câu trả lời đến từ [CastVehicleProfileCatalog.resolve], KHÔNG hardcode profile-id nào ở đây: mọi
     * xe mà `resolve` gán `rectForwardKinds = null` (chưa dispatch được, hoặc dispatch được nhưng
     * không có chuỗi RECT) đều trả về false. UI dùng cờ này để ẨN nút đổi kiểu thay vì hiện rồi bấm
     * không có tác dụng gì — đúng tiền lệ V1 `ClusterProfile.supportsStyle` (`ClusterProfile.kt:43`).
     */
    fun supportsClusterStyle(): Boolean = CastVehicleProfileCatalog.resolve(runtime.vehicleFacts).supportsStyle

    /** Đưa một thao tác chưa kết luận về trạng thái kết thúc nếu quan sát cho phép. */
    fun observeAndComplete(operationId: UUID): Boolean = runtime.coordinator.observeAndComplete(operationId)

    /** Đóng một thao tác mồ côi khi xe chứng minh không còn gì phải hoàn tác. */
    fun reconcileAbandoned(): Boolean = runtime.coordinator.reconcileAbandoned()

    /**
     * Kết cục hẹp của phép "trả cụm về đồng hồ". Màn hình chỉ cần ba câu: xong, chưa xong (kèm lý do
     * đọc được), hoặc chưa phát lệnh nào.
     */
    sealed interface ClusterClearOutcome {
        /** Đã đóng đường chiếu VÀ một lần quan sát tươi xác nhận cụm không còn app nào. */
        data class Cleared(val returned: List<String>) : ClusterClearOutcome

        /**
         * Đã phát lệnh nhưng CHƯA chứng minh được cụm sạch. KHÔNG phải thành công — đừng vẽ như đã xong.
         * [remaining] là những app quan sát tươi vẫn thấy trên cụm; null nghĩa là không đọc nổi.
         */
        data class Incomplete(
            val returned: List<String>,
            val remaining: Set<String>?,
            val reason: String,
        ) : ClusterClearOutcome

        /** Chưa phát lệnh nào. Xe nguyên như cũ. */
        data class Blocked(val reason: String) : ClusterClearOutcome
    }

    /**
     * Trả cụm về đồng hồ gốc: đưa MỌI app đang đậu trên display cụm về display 0, rồi đóng đường chiếu
     * của OEM.
     *
     * **Khi nào bề mặt phải mời phép này.** Đây là đường thoát cuối cùng cho một phiên chiếu bị kẹt —
     * đêm 31/07 chủ xe phải xoá dữ liệu app NĂM lần vì trong sản phẩm không có gì gỡ được. Nút này phải
     * xuất hiện ở mọi trạng thái mà người lái không còn cách nào khác, cụ thể:
     *
     *  - `CoarseState.RECOVERING` / `MANUAL_REQUIRED` / `RECOVERY_PENDING` — nhất là khi
     *    `unavailableReason == COMPENSATION_EXHAUSTED`, vì thang đặt app KHÔNG khai báo bước bù hoàn nào
     *    (xem KDoc `CastCoordinator.clearCluster`), nên `recover()` ở đó chắc chắn không làm được gì.
     *  - `RecoverySubstate.OBSERVATION_DIVERGED_*` và mọi ca cụm còn app trong khi bản ghi bền nói khác
     *    — kể cả khi KHÔNG có transaction nào (người lái chỉ muốn dọn một cụm bẩn).
     *  - Khi `requestStop()` đã ghi nhận mà `continueStopAfterAcknowledgement` vẫn trả về "bị chặn":
     *    Stop bị từ chối thẳng nếu chưa journal được baseline geometry, còn phép này thì không cần
     *    baseline nào.
     *
     * KHÔNG mời nó như một nút ngang hàng với "Dừng chiếu" ở luồng bình thường: Stop mới là đường có
     * hoàn tác đầy đủ (trả app-op, trả animation, reset khung display). Phép này cố ý bỏ những bước đó
     * để đổi lấy việc chạy được kể cả khi bản ghi bền đã hỏng — nó là cứu hộ, không phải thao tác hằng ngày.
     *
     * Đây là I/O ra xe và có ngủ chờ trước lần đọc cuối: **đừng gọi trên main thread**.
     */
    fun clearCluster(): ClusterClearOutcome =
        when (val result = runtime.coordinator.clearCluster(runtime.vehicleFacts)) {
            is ClusterClearResult.Blocked -> ClusterClearOutcome.Blocked(result.reason)
            is ClusterClearResult.Ran -> if (result.cleared) {
                ClusterClearOutcome.Cleared(result.returned)
            } else {
                ClusterClearOutcome.Incomplete(result.returned, result.remainingOccupants, clearReason(result))
            }
        }

    /**
     * Câu giải thích cho một lần trả cụm chưa xong — ưu tiên đúng thứ tự hậu quả với người lái: đồng hồ
     * chưa về là nặng nhất, rồi mới tới app còn kẹt, rồi tới lệnh bị từ chối.
     */
    private fun clearReason(result: ClusterClearResult.Ran): String {
        // Bind trước rồi mới `when`: `remainingOccupants` là property khai báo ở module khác nên Kotlin
        // KHÔNG smart-cast nó sang non-null sau một nhánh `== null`.
        val remaining = result.remainingOccupants
        val projectionFailure = result.projectionFailure
        return when {
            projectionFailure != null -> "Chưa đóng được đường chiếu: $projectionFailure"
            remaining == null -> "Đã phát lệnh nhưng không đọc lại được trạng thái cụm"
            remaining.isNotEmpty() -> "Cụm vẫn còn: ${remaining.joinToString(", ")}" +
                result.failures.entries.joinToString("") { " · ${it.key}: ${it.value}" }
            else -> "Chưa xác minh được cụm đã sạch"
        }
    }

    /** Xoá một phiên idle không còn xác minh được sau khi boot đổi, để lần chiếu kế tiếp tự bootstrap lại. */
    fun reconcileUnobservableIdleSession(): Boolean = runtime.coordinator.reconcileUnobservableIdleSession()

    fun reconcileStaleRecovery(): Boolean = runtime.coordinator.reconcileStaleRecovery()

    /**
     * Xoá stableSession nếu nó KHÔNG CÒN HỢP LỆ — chỉ khi:
     * - Không có transaction đang chạy
     * - Session không có seal (= chưa từng bootstrap thành công trong boot này)
     * - Hoặc session đang RECOVERY_PENDING (đã hỏng, cần reset)
     *
     * KHÔNG xoá khi session có seal (bootstrap đã mở projection) — vì projection vẫn đang mở,
     * xoá seal sẽ khiến cast tiếp chạy lại bootstrap TRÊN MỘT PROJECTION ĐÃ MỞ (vô hại nhưng chậm).
     */
    fun resetSessionForFreshBoot() {
        runtime.store.locked {
            val loaded = read() as? StoreRead.Loaded ?: return@locked
            val envelope = loaded.envelope
            // Đang có transaction → KHÔNG đụng, để nó tự chạy xong.
            if (envelope.transaction != null) return@locked
            val stable = envelope.stableSession
            // Không có session → đã pristine, không cần làm gì.
            if (stable == null) return@locked
            // Có seal → projection đã mở, KHÔNG xoá.
            if (stable.profileExport != null) return@locked
            // RECOVERY_PENDING hoặc session không seal → xoá để bootstrap lại.
            bumpEpoch { it.copy(stableSession = null, transaction = null, stopRequested = false) }
        }
    }

    /** Yêu cầu dừng. Trả về envelope đã ghi nhận, hoặc null nếu không đọc được trạng thái bền. */
    fun requestStop(): CastSessionEnvelope? = runtime.coordinator.requestStop()

    /**
     * Chạy một ý định chiếu do người dùng bấm. Facts của xe do façade tự cấp — UI không cần biết.
     */
    fun runManualIntent(
        packageName: String,
        origin: CastIntentOrigin = CastIntentOrigin.USER,
        automationRequestId: UUID? = null,
        allowDestructive: Boolean = false,
        preferredDensityDpi: Int? = null,
        clusterStyle: ClusterStyle = ClusterStyle.CURVED,
    ): CastManualIntentResult = runtime.coordinator.runManualIntent(
        packageName, runtime.vehicleFacts, targets(), origin, automationRequestId,
        allowDestructive, preferredDensityDpi, clusterStyle,
    )

    /**
     * Đặt [packageName] vào MỘT NỬA cụm ([zone] = [BubbleZone.LEFT]/[BubbleZone.RIGHT]).
     *
     * Khác [runManualIntent] đúng một thứ: nó đính kèm một `ClusterSlotLayout`, tức nói với tầng quyết
     * định "app này giữ ô này, app kia giữ ô kia". Mọi cổng còn lại (trạng thái bền, policy, evidence,
     * hàng đợi, bootstrap) đi nguyên đường cũ — CLAUDE.md §6: đường mới xuống cuối, không đảo đường đã
     * chạy tốt ngoài hiện trường.
     *
     * Bố cục được dựng từ MỘT quan sát tươi ngay tại đây ([ClusterZoneReading.layout]) chứ không nhận từ
     * bề mặt: app giữ nửa kia là dữ kiện của XE tại thời điểm phát lệnh, không phải thứ một cửa sổ vẽ 15
     * giây một lần được phép khẳng định. Planner còn kiểm lại lần nữa trên `observed` của chính nó (app
     * kia phải ĐANG giữ đúng rect của ô kia), nên lời từ chối ở đây chỉ là nói sớm hơn và nói bằng ngôn
     * ngữ người lái đọc được.
     *
     * [leftPercent] là tỉ lệ người lái đã chọn ở panel Home (xem [SplitRatioChoice]).
     *
     * Đây là I/O ra xe: **đừng gọi trên main thread**.
     */
    fun runHalfIntent(
        packageName: String,
        zone: BubbleZone,
        leftPercent: Int,
        preferredDensityDpi: Int? = null,
        clusterStyle: ClusterStyle = ClusterStyle.CURVED,
    ): CastManualIntentResult {
        val side = when (zone) {
            BubbleZone.LEFT -> ClusterSlotSide.LEFT
            BubbleZone.RIGHT -> ClusterSlotSide.RIGHT
            // Không im lặng đổi thành một lượt chiếu toàn cụm: chiếu full trong khi hai nửa đang có app
            // là NUỐT chúng (đo 31/7). Ô cả cụm có đường riêng của nó ([runManualIntent]).
            BubbleZone.FULL -> return CastManualIntentResult.Blocked(
                "Ô cả cụm không phải một nửa cụm",
            )
        }
        val observed = observedState() ?: return CastManualIntentResult.Blocked(
            "Chưa đọc được cụm — không xếp ô khi chưa biết cụm đang có gì",
        )
        val layout = ClusterZoneReading.layout(observed, packageName, ClusterSlot(side, splitRatio(leftPercent)))
            ?: return CastManualIntentResult.Blocked(
                "Cụm đang có nhiều hơn một app khác — trả bớt về màn chính rồi mới chia đôi",
            )
        return runtime.coordinator.runManualIntent(
            packageName, runtime.vehicleFacts, targets(), CastIntentOrigin.USER,
            allowDestructive = false,
            preferredDensityDpi = preferredDensityDpi,
            clusterStyle = clusterStyle,
            slotLayout = layout,
        )
    }

    // --- vòng đời phiên ---

    fun initialize(bootId: String): CastSessionEnvelope = runtime.coordinator.initialize(bootId)

    fun applyRollout(flags: CastRolloutFlags): CastSessionEnvelope = runtime.coordinator.applyRollout(flags)

    fun queueLatestTarget(packageName: String): Boolean = runtime.coordinator.queueLatestTarget(packageName)

    fun resumePendingIntent(): CastManualIntentResult? =
        runtime.coordinator.resumePendingIntent(targets())

    /** Ngân sách chờ xác nhận Stop, tính bằng ms. UI chỉ cần con số, không cần biết ai định nghĩa. */
    fun stopAcknowledgementGraceMillis(): Long = CastUiRenderer.STOP_ACK_GRACE_MILLIS

    /**
     * Bản chiếu cho nút nổi, dựng từ ĐÚNG MỘT lần đọc bền + ĐÚNG MỘT lần quan sát tươi.
     *
     * Vì sao façade tự đọc thay vì nhận sẵn `model` như bản trước (2026-08-01, T8): từ nay nút nổi phải
     * vẽ occupancy THẬT theo từng ô (§R7), mà thứ duy nhất nói được điều đó là [ObservedState] — kiểu
     * bên trong của tầng quan sát. Nếu bề mặt phải tự cầm `ObservedState` để dựng occupancy thì nó lại
     * phải biết hình dạng bên trong tầng dưới, đúng thứ façade sinh ra để chặn
     * (`CastArchitectureRatchetTest` đo chính khoảng cách đó).
     *
     * Và nó sửa luôn một điểm yếu thật của bản trước: bề mặt gọi `renderModel()` (tự quan sát bên trong)
     * rồi sẽ phải quan sát LẦN HAI để có occupancy — hai sự thật khác nhau cho cùng một lần vẽ, đúng lớp
     * lỗi mà `observeBoth()` đã phải sinh ra để chữa cho màn Home. Ở đây một quan sát nuôi cả hai.
     *
     * [localStopAcknowledged] là chốt xác nhận Stop CỤC BỘ của bề mặt: nó sống trong RAM của cửa sổ đó,
     * có hạn 500 ms, và nhận vào sự thật bền `stopRequested` để tự nhả. Nó ở lại bề mặt chứ không xuống
     * đây vì đó là trạng thái hiển thị của MỘT cửa sổ, không phải của phiên chiếu.
     *
     * Đây là I/O (một vòng quan sát ra xe): **đừng gọi trên main thread**.
     */
    fun bubbleProjection(
        localStopAcknowledged: (durableStopRequested: Boolean) -> Boolean,
    ): BubbleProjection {
        val envelope = envelope()
        val durableStop = envelope?.stopRequested == true
        val (observation, observed) = observeBoth()
        return CastBubbleProjection.project(
            model = renderModel(observation = observation),
            rows = emptyList(),
            defaultPackage = envelope?.automationConfig?.defaultPackage,
            localStopRequested = localStopAcknowledged(durableStop) || durableStop,
            activeTargetPackage = envelope?.stableSession?.activeTarget?.packageName,
            occupancy = ClusterZoneReading.occupancy(observed),
        )
    }

    /**
     * Chạm vào ô này là TRẢ app về (true) hay CHIẾU vào ô (false).
     *
     * Hỏi `action` chứ KHÔNG hỏi `occupied`, và đó không phải chi tiết: `occupied` là câu hỏi VẼ (tô đặc
     * hay để viền), `action` là câu hỏi HÀNH ĐỘNG — xem KDoc `BubbleProjection.stopOnTap` để biết vì sao
     * hai câu ấy cố ý tách nhau (ô cả cụm ở nhánh phục hồi: chưa xác minh gì nên KHÔNG tô đặc, nhưng
     * chạm vào vẫn phải là Dừng, vì Stop là đường thoát duy nhất của trạng thái đó).
     *
     * Ở façade chứ không ở tầng view để `BubbleZoneAction` không phải lọt qua ranh giới chỉ vì một phép
     * so sánh (`CastArchitectureRatchetTest`).
     */
    fun zoneReturnsOnTap(projection: BubbleProjection, zone: BubbleZone): Boolean =
        projection.zone(zone)?.action == BubbleZoneAction.RETURN

    /**
     * Một tỉ lệ chia cụm, ở dạng bề mặt đọc được: phần trăm bề ngang của ô TRÁI + nhãn đã dựng sẵn.
     *
     * Vì sao không đưa thẳng `ClusterSplitRatio` ra UI: `TwoPipelineStaticBoundaryTest` (trong `:core`)
     * chốt cứng DANH SÁCH file `:app` được phép nhắc tới `…clustercast.v2`, nên một file binding mới
     * chạm vào enum đó là phá hợp đồng phân tầng. [leftPercent] không phải "số ma": nó chính là trường
     * `ClusterSplitRatio.leftPercent`, và [splitRatio] dịch ngược lại đúng một chỗ ngay dưới đây.
     */
    data class SplitRatioChoice(val leftPercent: Int, val label: String)

    /** Diễn giải lý do một mục trong menu nút nổi không khả dụng. */
    fun unavailableReason(reason: DisabledReason): String = CastBubbleProjection.unavailable(reason)

    /** App đang chọn có đủ điều kiện để chiếu ngay không. */
    fun selectionReady(packageName: String, envelope: CastSessionEnvelope): Boolean =
        targets().read(packageName).eligibilityFor(envelope) is CastManualTargetEligibility.Ready

    /** Chiếu do tự động hoá lúc khởi động, không phải do người bấm. */
    fun runBootAutomationIntent(
        packageName: String,
        automationRequestId: UUID,
    ): CastManualIntentResult = runManualIntent(
        packageName, CastIntentOrigin.BOOT_AUTO, automationRequestId,
    )

    /** Ý định đang treo có phải do người bấm không. */
    fun pendingIntentIsUserRequested(envelope: CastSessionEnvelope): Boolean =
        envelope.pendingIntent?.origin == CastIntentOrigin.USER

    // --- các ý định dựng sẵn ---
    //
    // Trước đây UI tự tạo CastIntent, tự resolve rollout và tự dựng TargetEvidence. Nghĩa là màn hình
    // phải biết hình dạng bên trong của tầng quyết định chỉ để bấm một nút. Bốn kiểu đó giờ không lọt
    // qua ranh giới nữa; UI nói ý muốn, façade dựng ý định.

    fun applyVehicleTestRollout(): CastSessionEnvelope =
        applyRollout(CastRolloutRegistry.vehicleTestCandidate)

    /** Chủ sở hữu hành động theo rollout hiện tại có phải V2 không. */
    fun v2OwnsActions(envelope: CastSessionEnvelope): Boolean =
        CastRolloutRegistry.resolve(envelope, CastRolloutRegistry.vehicleTestCandidate).actionOwner == EngineVersion.V2

    fun planStop(packageName: String?, evidence: TargetEvidence?): PlanResult = plan(
        CastIntent(CastIntentKind.STOP, packageName),
        evidence ?: TargetEvidence(false, null, false),
        installed = true,
        hasLauncher = true,
    )

    fun planRecover(
        packageName: String,
        recoveryProof: DisconnectedSinkRecoveryProof,
        evidence: TargetEvidence,
    ): PlanResult = plan(
        CastIntent(CastIntentKind.RECOVER, packageName, recoveryProof = recoveryProof),
        evidence,
        installed = true,
        hasLauncher = true,
    )

    fun planGeometry(
        packageName: String,
        geometry: AcceptedGeometry,
        expectedTarget: CastTarget?,
        evidence: TargetEvidence,
        installed: Boolean,
        hasLauncher: Boolean,
    ): PlanResult = plan(
        CastIntent(CastIntentKind.APPLY_GEOMETRY, packageName, geometry = geometry, expectedTarget = expectedTarget),
        evidence,
        installed = installed,
        hasLauncher = hasLauncher,
    )

    // --- đường plan/execute, dùng cho Stop và recovery ---

    fun plan(
        intent: CastIntent,
        targetEvidence: TargetEvidence,
        installed: Boolean,
        hasLauncher: Boolean,
    ): PlanResult = runtime.coordinator.plan(intent, targetEvidence, installed, hasLauncher)

    fun execute(result: PlanResult, targetPackage: String?): ExecutionResult =
        runtime.coordinator.execute(result, targetPackage)

    /** Bù trừ một lần theo chính sách của :core. */
    fun recover(): ExecutionResult = runtime.coordinator.recover()

    /** Nháp điều chỉnh khung/DPI. UI không cần biết nó nằm ở đâu. */
    fun markAdjustmentApplyFailed(reason: String) = runtime.adjustment.markApplyFailed(reason)

    // ── nháp điều chỉnh khung / DPI ──
    //
    // Cả cụm phép này từng gọi thẳng `runtime.adjustment` từ Activity: mười chỗ, trong đó `applyGeometry`
    // là một bộ điều phối 60 dòng có cả lập kế hoạch, phát lệnh, ngủ chờ, đọc lại và đánh dấu thất bại.
    // Ratchet không thấy vì nó đi qua `runtime.`, không qua import kiểu. Đưa xuống đây để màn hình chỉ
    // còn việc hỏi và hiển thị.

    /**
     * Kết cục hẹp cho nháp điều chỉnh. Màn hình chỉ cần biết "có nháp để vẽ" hoặc "không, vì lý do
     * này"; `AdjustmentResult` của tầng dưới không cần lộ ra UI.
     */
    sealed interface DraftOutcome {
        data class Ready(val draft: AdjustmentDraft) : DraftOutcome
        data class Rejected(val reason: String) : DraftOutcome
    }

    private fun AdjustmentResult.narrow(): DraftOutcome = when (this) {
        is AdjustmentResult.Ready -> DraftOutcome.Ready(draft)
        is AdjustmentResult.Rejected -> DraftOutcome.Rejected(reason)
    }

    /** Mở nháp điều chỉnh từ quan sát hiện tại. */
    fun openAdjustment(): DraftOutcome =
        observedState()?.let(runtime.adjustment::open)?.narrow()
            ?: DraftOutcome.Rejected("Không đọc được target/geometry")

    fun undoAdjustment(): DraftOutcome = runtime.adjustment.undoLast().narrow()

    fun restoreAdjustmentEntry(): DraftOutcome = runtime.adjustment.restoreEntry().narrow()

    /** Đưa nháp về mặc định: ưu tiên baseline đã lưu, không có thì về ảnh chụp lúc mở. */
    fun resetAdjustment(entrySnapshot: AcceptedGeometry): DraftOutcome =
        runtime.adjustment.resetDefault(envelope()?.stableSession?.baseline?.geometry ?: entrySnapshot).narrow()

    /** Chốt nháp bằng hai lần đọc lại. Trả về lý do nếu chưa lưu được. */
    fun finishAdjustment(): String? {
        val first = observedState() ?: return "Không đọc được geometry"
        val second = observedState() ?: return "Không đọc được geometry lần hai"
        return (runtime.adjustment.done(first, second) as? AdjustmentResult.Rejected)?.reason
    }

    /**
     * Áp một khung: sửa nháp → mở phép ghi → lập kế hoạch → phát → BIND nháp vào transaction bằng cả
     * operationId và epoch → chờ hội tụ → đọc lại hai lần.
     *
     * Thứ tự bind-trước-khi-chờ là lý do đường này không dùng được `executeAndSettle`; giờ nó nằm cùng
     * chỗ với phần còn lại của máy móc nên trình tự không còn là việc của tầng UI.
     */
    fun applyGeometry(
        geometry: AcceptedGeometry,
        installed: (String) -> Boolean,
        hasLauncher: (String) -> Boolean,
        cancelAfterVerified: Boolean = false,
        settleMillis: Long = 250,
    ): GeometryOutcome {
        val observed = observedState() ?: return GeometryOutcome.Rejected("Không đọc được target hiện tại")
        (runtime.adjustment.edit(geometry) as? AdjustmentResult.Rejected)
            ?.let { return GeometryOutcome.Rejected(it.reason) }
        // `beginApply` không luôn trả `Rejected` khi từ chối: `CastGeometry.validate` thất bại
        // (GEOMETRY_LIMITED/PROTECTED_SESSION) hoặc identity đổi giữa chừng (FROZEN) đều trả về
        // `Ready` bọc một draft KHÔNG chuyển sang APPLYING, kèm `validationError`. Ép kiểu
        // `as? Rejected` bỏ sót cả hai nhánh này — draft vẫn "Ready" về mặt kiểu — nên phải đọc
        // đúng trạng thái thật của draft, không chỉ dựa vào kiểu bọc ngoài. Nếu không, plan/execute
        // bên dưới vẫn phát submitting một lệnh mutating (APPLY_TASK_GEOMETRY) dù nhánh từ chối đã
        // có kết luận đúng, đúng lỗi round-1 nghi ngờ.
        val begun = runtime.adjustment.beginApply(observed)
        if (begun is AdjustmentResult.Rejected) return GeometryOutcome.Rejected(begun.reason)
        val beganDraft = (begun as AdjustmentResult.Ready).draft
        if (beganDraft.applyState != AdjustmentApplyState.APPLYING) {
            return GeometryOutcome.Rejected(
                beganDraft.validationError ?: "Geometry apply không được chấp nhận",
            )
        }
        val pkg = observed.target?.packageName ?: return GeometryOutcome.Rejected("Target không xác định")
        val plan = planGeometry(
            pkg, geometry, observed.target, catalog.evidence(pkg, phoneSession(pkg)),
            installed = installed(pkg), hasLauncher = hasLauncher(pkg),
        )
        return when (val execution = execute(plan, pkg)) {
            is ExecutionResult.RecoveryRequired -> {
                markAdjustmentApplyFailed(execution.reason)
                GeometryOutcome.RecoveryRequired(execution.reason)
            }
            is ExecutionResult.Blocked -> {
                markAdjustmentApplyFailed(execution.reason)
                GeometryOutcome.Blocked(execution.reason)
            }
            is ExecutionResult.AwaitingVerification -> {
                val bound = runtime.adjustment.bindExecution(execution.operationId, execution.epoch)
                if (bound is AdjustmentResult.Rejected) {
                    return GeometryOutcome.Rejected("Không bind được geometry transaction: ${bound.reason}")
                }
                Thread.sleep(settleMillis)
                if (!observeAndComplete(execution.operationId)) {
                    markAdjustmentApplyFailed("Geometry chưa hội tụ")
                    return GeometryOutcome.NotVerified("Geometry chưa hội tụ")
                }
                val first = observedState()
                val second = observedState()
                if (first == null || second == null ||
                    runtime.adjustment.recordVerifiedApply(first, second) is AdjustmentResult.Rejected
                ) {
                    markAdjustmentApplyFailed("Read-back geometry thất bại")
                    return GeometryOutcome.NotVerified("Read-back geometry thất bại")
                }
                if (cancelAfterVerified) runtime.adjustment.cancelAfterRestore(first, second)
                GeometryOutcome.Applied
            }
        }
    }

    /** Kết cục hẹp cho đường geometry: chỉ những gì màn hình cần biết để nói đúng sự thật. */
    sealed interface GeometryOutcome {
        /** Đã áp và đọc lại khớp. */
        data object Applied : GeometryOutcome

        /** Đã phát lệnh nhưng đọc lại không xác nhận. KHÔNG phải thành công. */
        data class NotVerified(val reason: String) : GeometryOutcome

        /** Nháp bị từ chối trước khi phát lệnh nào. */
        data class Rejected(val reason: String) : GeometryOutcome

        data class Blocked(val reason: String) : GeometryOutcome
        data class RecoveryRequired(val reason: String) : GeometryOutcome
    }

    /**
     * Kết cục hẹp dành cho UI.
     *
     * Trước 2026-07-27 Activity match trực tiếp `ExecutionResult` ở tám nhánh để dựng câu thông báo, tức
     * màn hình phải biết taxonomy kết cục của tầng dưới. Nó cũng phải tự `Thread.sleep(250)` chờ hội tụ —
     * một chi tiết thi hành không có việc gì ở tầng UI.
     */
    sealed interface Outcome {
        /** Đã phát lệnh và quan sát xác nhận. */
        data class Verified(val operationId: UUID) : Outcome

        /** Đã phát lệnh nhưng quan sát chưa xác nhận. KHÔNG phải thành công. */
        data class NotVerified(val operationId: UUID, val reason: String) : Outcome

        /** Không phát được, kèm lý do đọc được. */
        data class Blocked(val reason: String) : Outcome

        /** Cần phục hồi trước khi làm tiếp. */
        data class RecoveryRequired(val reason: String) : Outcome
    }

    /**
     * Chạy một plan rồi chờ hội tụ, trả về kết cục hẹp.
     *
     * [settleMillis] là chi tiết thi hành, để ở đây chứ không để UI tự ngủ.
     */
    fun executeAndSettle(plan: PlanResult, targetPackage: String?, settleMillis: Long = 250): Outcome =
        when (val result = execute(plan, targetPackage)) {
            is ExecutionResult.AwaitingVerification -> {
                Thread.sleep(settleMillis)
                if (observeAndComplete(result.operationId)) {
                    Outcome.Verified(result.operationId)
                } else {
                    Outcome.NotVerified(result.operationId, "quan sát chưa hội tụ")
                }
            }
            is ExecutionResult.RecoveryRequired -> Outcome.RecoveryRequired(result.reason)
            is ExecutionResult.Blocked -> Outcome.Blocked(result.reason)
            else -> Outcome.Blocked("kết cục không xác định")
        }

    /**
     * Phát Stop thật sau khi `requestStop()` đã ghi nhận và không transaction nào đang chạy chờ hội tụ:
     * đọc envelope, từ chối nếu V2 không sở hữu hành động, lập kế hoạch Stop với evidence tươi, thực
     * thi và chờ hội tụ.
     *
     * 2026-07-28: trước đây Activity và nút nổi mỗi bên tự viết lại đúng bốn dòng này -- và nút nổi
     * chưa bao giờ viết lại chúng, nên tắt "Dừng chiếu" ở đó chỉ ghi ý định chứ không phát lệnh dọn dẹp
     * thật. Gộp về một chỗ duy nhất trong façade để cả hai bề mặt gọi cùng một implementation (một ý
     * định, mọi bề mặt chỉ là renderer/dispatcher cho nó) thay vì hai bản sao có thể trôi lệch nhau.
     * [evidenceFor] để UI tự cấp evidence (nó cần `catalog`, façade không giữ catalog cho mọi caller).
     */
    fun continueStopAfterAcknowledgement(evidenceFor: (String) -> TargetEvidence?): String {
        val envelope = envelope()
        val pkg = envelope?.stableSession?.activeTarget?.packageName
        if (envelope == null) return "Durable store unavailable"
        if (!v2OwnsActions(envelope)) return "Stop owner is not V2"
        val plan = planStop(pkg, pkg?.let(evidenceFor))
        return when (val outcome = executeAndSettle(plan, pkg)) {
            is Outcome.Verified -> "Đã trả đồng hồ"
            is Outcome.NotVerified -> "Stop chưa xác minh"
            is Outcome.RecoveryRequired -> "Stop cần phục hồi: ${outcome.reason}"
            is Outcome.Blocked -> "Stop bị chặn: ${outcome.reason}"
        }
    }

    companion object {
        fun of(context: Context): CastFacade =
            CastFacade(CastAndroidRuntime.create(context), CastAppCatalog(context.applicationContext))

        /** Dùng khi caller đã giữ Runtime; không tạo control plane thứ hai. */
        fun wrapping(runtime: CastAndroidRuntime.Runtime, catalog: CastAppCatalog): CastFacade =
            CastFacade(runtime, catalog)

        /**
         * Ba tỉ lệ chia cụm mà sản phẩm cho phép, theo đúng thứ tự người lái thấy trong dropdown.
         *
         * Thứ tự đến từ `ClusterSplitRatio.entries`, và entry đầu tiên là `EVEN` (50-50) — tỉ lệ DUY
         * NHẤT đã đo thật trên xe (DiLink3, 31/07: `am task resize … 0 90 960 810` + `… 960 90 1920
         * 810`, hai app cùng vẽ). Nhãn được DỰNG từ `leftPercent` chứ không chép tay, nên thêm/bớt một
         * tỉ lệ trong `:core` là dropdown tự đúng, không có bộ chữ thứ hai để quên đồng bộ.
         */
        fun splitRatioChoices(): List<SplitRatioChoice> = ClusterSplitRatio.entries.map {
            SplitRatioChoice(it.leftPercent, "Trái ${it.leftPercent}% · Phải ${100 - it.leftPercent}%")
        }

        /** Mặc định 50-50 — tỉ lệ đã đo thật; hai tỉ lệ còn lại mới chỉ suy ra từ cùng phép chia. */
        val DEFAULT_SPLIT_LEFT_PERCENT: Int = ClusterSplitRatio.EVEN.leftPercent

        /**
         * Phần trăm đã lưu → tỉ lệ có kiểu. Giá trị lạ (enum đổi, prefs bị sửa tay) lùi về 50-50 thay vì
         * ném: chiều lùi an toàn là tỉ lệ đã đo, không phải một ngoại lệ giữa lúc người lái vừa chạm.
         */
        private fun splitRatio(leftPercent: Int): ClusterSplitRatio =
            ClusterSplitRatio.entries.firstOrNull { it.leftPercent == leftPercent } ?: ClusterSplitRatio.EVEN
    }
}

/**
 * Đọc BẢN ĐỒ cụm từ một quan sát, và dựng yêu cầu chia ô từ chính bản đồ đó. Thuần, không I/O.
 *
 * Sống trong file này (không phải một file riêng) vì hai hợp đồng tĩnh cùng chỉ vào đây:
 * `TwoPipelineStaticBoundaryTest` chốt danh sách file `:app` được nhắc tới `…clustercast.v2`, còn
 * `LayeringRulesTest` cấm thêm file THUẦN mới vào `:app`. Façade là chỗ duy nhất thoả cả hai.
 *
 * Nguyên tắc xuyên suốt (CLAUDE.md §2): mọi ca không mô tả được TRUNG THỰC đều trả
 * [ClusterOccupancy.Unmeasured] — "chưa biết" — chứ không bao giờ trả một bản đồ thiếu, vì một ô báo
 * "trống" nhầm là một cú chạm chiếu đè lên app đang hiển thị.
 */
internal object ClusterZoneReading {

    /**
     * Ai đang chiếm ô nào, suy từ KHUNG TASK THẬT (`ObservedState.taskBounds`) chứ không từ máy trạng
     * thái. Đây là bằng chứng mạnh nhất mà bản chiếu có được — xem KDoc `BubbleProjection.projecting`.
     *
     * Bốn cửa từ chối, mỗi cửa khoá một cách nói dối cụ thể:
     *  1. **Không cắt được dải vẽ** ([ClusterSplit.contentRect] trả null) ⇒ chưa biết. Cửa này cũng bắt
     *     luôn ca cụm rỗng-nhưng-không-chứng-minh-được và ca `geometry` đọc nhầm display (xem KDoc của
     *     `contentRect`).
     *  2. **Một task không nằm gọn trong ô nào** ⇒ chưa biết. Rect lạ nghĩa là cụm không ở một trong bốn
     *     bố cục sản phẩm biết (full, hoặc trái/phải theo ba tỉ lệ); vẽ bừa nó vào một ô là bịa.
     *  3. **Hai task cùng một ô** ⇒ chưa biết. Đo thật: rect chồng nhau thì một app che hẳn app kia, nên
     *     bản đồ "một ô một app" không mô tả nổi trạng thái đó.
     *  4. **Số task có bounds ≠ số gói trên cụm** ⇒ chưa biết. Dump thật CÓ dòng task không in `bounds=`
     *     (KDoc `ObservedState.taskBounds`), và khi đó một app có mặt trên cụm sẽ không có ô nào — bản
     *     đồ thiếu đúng một app, tức mời chiếu vào một ô đang có người.
     *
     * Cụm ĐÃ CHỨNG MINH là rỗng (`occupants` rỗng VÀ không task nào) mới được trả `Measured(emptyList())`.
     * Đó là khác biệt mà [ClusterOccupancy] sinh ra để giữ: "đã đo, cụm trống" ≠ "chưa ai đo".
     */
    fun occupancy(observed: ObservedState?): ClusterOccupancy {
        if (observed == null) return ClusterOccupancy.Unmeasured
        if (observed.occupants.isEmpty() && observed.taskBounds.isEmpty()) {
            return ClusterOccupancy.Measured(emptyList())
        }
        val content = ClusterSplit.contentRect(observed) ?: return ClusterOccupancy.Unmeasured
        val zones = LinkedHashMap<Int, BubbleZone>(observed.taskBounds.size)
        observed.taskBounds.forEach { (taskId, bounds) ->
            zones[taskId] = zoneOf(content, bounds) ?: return ClusterOccupancy.Unmeasured
        }
        if (zones.values.toSet().size != zones.size) return ClusterOccupancy.Unmeasured
        if (zones.size != observed.occupants.size) return ClusterOccupancy.Unmeasured
        val names = attribution(observed, zones)
        return ClusterOccupancy.Measured(zones.map { (taskId, zone) -> ClusterOccupant(zone, names[taskId]) })
    }

    /**
     * Task nào của gói nào — chỉ khi câu trả lời là DUY NHẤT, không suy đoán.
     *
     * Quan sát hôm nay không mang `taskId → package` (đó là món nợ đã ghi trong KDoc
     * `CastAmTaskBoundsParser`), nên chỉ hai điều là chắc chắn: task của `observed.target` mang tên gói
     * của target, và nếu sau khi trừ nó ra còn ĐÚNG một task và ĐÚNG một gói thì cặp ấy buộc phải khớp.
     * Mọi ca khác để tên gói `null` — [ClusterOccupant] cho phép đúng vì lý do này, và ô vẫn tô đặc nên
     * người lái không mất thông tin nào về việc ô ấy đang có app.
     */
    private fun attribution(observed: ObservedState, zones: Map<Int, BubbleZone>): Map<Int, String> {
        val targetTask = observed.target?.taskId
        val targetPackage = observed.target?.packageName
        val named = if (targetTask != null && targetPackage != null && zones.containsKey(targetTask)) {
            mapOf(targetTask to targetPackage)
        } else emptyMap()
        val remainingTasks = zones.keys - named.keys
        val remainingPackages = observed.occupants - named.values.toSet()
        if (remainingTasks.size != 1 || remainingPackages.size != 1) return named
        return named + mapOf(remainingTasks.first() to remainingPackages.first())
    }

    /**
     * Ô mà một khung task đang chiếm, hoặc `null` nếu nó không phải ô nào cả.
     *
     * So BẰNG NHAU tuyệt đối, không "nằm gần" hay "chứa trong": rect của ô được sinh ra từ đúng phép
     * chia mà lệnh `am task resize` gửi đi ([ClusterSplit.slotRect]), nên một khung khớp ô là khung do
     * chính chúng ta đặt — còn lệch một pixel nghĩa là ai đó khác đã đặt nó, và đoán tiếp là bịa.
     */
    private fun zoneOf(content: CastRect, bounds: CastRect): BubbleZone? {
        if (bounds == content) return BubbleZone.FULL
        ClusterSplitRatio.entries.forEach { ratio ->
            if (bounds == ClusterSplit.slotRect(content, ClusterSlot(ClusterSlotSide.LEFT, ratio))) {
                return BubbleZone.LEFT
            }
            if (bounds == ClusterSplit.slotRect(content, ClusterSlot(ClusterSlotSide.RIGHT, ratio))) {
                return BubbleZone.RIGHT
            }
        }
        return null
    }

    /**
     * Bố cục ô cho một lượt đặt: app được đặt giữ [slot], app còn lại trên cụm (nếu có) giữ nửa kia.
     *
     * "Nửa kia" không phải một lựa chọn: `ClusterSlotLayout` chỉ cho MỘT app mỗi bên và CÙNG một tỉ lệ,
     * nên khi ô của app được đặt đã chốt thì ô của app kia là bắt buộc. Nếu thực tế nó đang giữ một
     * rect khác, planner từ chối kèm rect thật — thà từ chối đọc được còn hơn resize nhầm app kia.
     *
     * Từ hai app khác trở lên ⇒ `null`: cụm chỉ chia đôi, và `am start`/`am task resize` của một lượt
     * chỉ chạm được đúng một gói (`CastMutationRequest.targetPackage`), nên không có đường nào dọn giúp.
     */
    fun layout(observed: ObservedState, targetPackage: String, slot: ClusterSlot): ClusterSlotLayout? {
        val others = observed.occupants - targetPackage
        val slots = when (others.size) {
            0 -> mapOf(targetPackage to slot)
            1 -> mapOf(
                targetPackage to slot,
                others.first() to ClusterSlot(opposite(slot.side), slot.ratio),
            )
            else -> return null
        }
        return runCatching { ClusterSlotLayout(slots) }.getOrNull()
    }

    private fun opposite(side: ClusterSlotSide): ClusterSlotSide =
        if (side == ClusterSlotSide.LEFT) ClusterSlotSide.RIGHT else ClusterSlotSide.LEFT
}

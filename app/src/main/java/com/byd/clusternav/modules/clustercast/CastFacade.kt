package com.byd.clusternav.modules.clustercast

import android.content.Context
import com.byd.clusternav.modules.clustercast.v2.AutomationConfig
import com.byd.clusternav.modules.clustercast.v2.CastAndroidRuntime
import com.byd.clusternav.modules.clustercast.v2.CastIntentOrigin
import com.byd.clusternav.modules.clustercast.v2.CastManualIntentResult
import com.byd.clusternav.modules.clustercast.v2.CastManualTargetReader
import com.byd.clusternav.modules.clustercast.v2.CastSessionEnvelope
import com.byd.clusternav.modules.clustercast.v2.ClusterStyle
import com.byd.clusternav.modules.clustercast.v2.CastAppCatalog
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
import com.byd.clusternav.modules.clustercast.v2.eligibilityFor
import com.byd.clusternav.modules.clustercast.v2.BubbleProjection
import com.byd.clusternav.modules.clustercast.v2.CastAppRow
import com.byd.clusternav.modules.clustercast.v2.CastBubbleProjection
import com.byd.clusternav.modules.clustercast.v2.CastManualTargetSnapshot
import com.byd.clusternav.modules.clustercast.v2.DisabledReason
import com.byd.clusternav.modules.clustercast.v2.ObservationValue
import com.byd.clusternav.modules.clustercast.v2.ObservedState
import com.byd.clusternav.modules.clustercast.v2.StoreRead
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

    /** Đưa một thao tác chưa kết luận về trạng thái kết thúc nếu quan sát cho phép. */
    fun observeAndComplete(operationId: UUID): Boolean = runtime.coordinator.observeAndComplete(operationId)

    /** Đóng một thao tác mồ côi khi xe chứng minh không còn gì phải hoàn tác. */
    fun reconcileAbandoned(): Boolean = runtime.coordinator.reconcileAbandoned()

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

    // --- vòng đời phiên ---

    fun initialize(bootId: String): CastSessionEnvelope = runtime.coordinator.initialize(bootId)

    fun applyRollout(flags: CastRolloutFlags): CastSessionEnvelope = runtime.coordinator.applyRollout(flags)

    fun queueLatestTarget(packageName: String): Boolean = runtime.coordinator.queueLatestTarget(packageName)

    fun resumePendingIntent(): CastManualIntentResult? =
        runtime.coordinator.resumePendingIntent(targets())

    /** Ngân sách chờ xác nhận Stop, tính bằng ms. UI chỉ cần con số, không cần biết ai định nghĩa. */
    fun stopAcknowledgementGraceMillis(): Long = CastUiRenderer.STOP_ACK_GRACE_MILLIS

    /** Mô hình hiển thị cho nút nổi. */
    fun bubbleProjection(
        model: CastRenderModel,
        rows: List<CastAppRow>,
        defaultPackage: String?,
        localStopRequested: Boolean,
        activeTargetPackage: String?,
    ): BubbleProjection = CastBubbleProjection.project(
        model, rows, defaultPackage, localStopRequested, activeTargetPackage,
    )

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

    companion object {
        fun of(context: Context): CastFacade =
            CastFacade(CastAndroidRuntime.create(context), CastAppCatalog(context.applicationContext))

        /** Dùng khi caller đã giữ Runtime; không tạo control plane thứ hai. */
        fun wrapping(runtime: CastAndroidRuntime.Runtime, catalog: CastAppCatalog): CastFacade =
            CastFacade(runtime, catalog)
    }
}

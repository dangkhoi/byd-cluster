package com.byd.clusternav.modules.clustercast.v2

import java.util.UUID

private val MANUAL_ANDROID_PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

/**
 * "Bản ghi bền này KHÔNG chứng minh được đường chiếu OEM đang mở."
 *
 * `profileExport` là dấu seal `seal-dl3-cold-bootstrap-v1`, và chỉ có ĐÚNG MỘT chỗ ghi ra nó: kết cục
 * BOOTSTRAP, tức ngay sau khi ba opcode `service call AutoContainer 2 i32 1000 i32 30/16/35` đã thật sự
 * phát đi (`CastColdBootstrap.succeed` và `CastCoordinator.completeVerificationLocked` nhánh BOOTSTRAP).
 * Đọc được trực tiếp trên xe ở trường thứ tư của dòng `stable=` trong session.env (`CastSessionStore.kt:176`
 * đóng gói `stable.createdByBuild` rồi `stable.profileExport`) — đúng thứ tự ưu tiên debug CLAUDE.md §15.
 * Đo thật trên DiLink3 đêm 31/7–1/8:
 *
 *   stable=IDLE_VERIFIED|V2|runtime-migration |~                         |display-1|…  ← đường tắt
 *   stable=IDLE_VERIFIED|V2|runtime-bootstrap |seal-dl3-cold-bootstrap-v1|display-1|…  ← đường thật
 *
 * ★ VÌ SAO KHÔNG CÒN ĐÒI THÊM TIỀN TỐ `runtime-migration` (sửa trong review 2026-08-01, Pass 1).
 * Bản đầu của cổng này viết `profileExport == null && createdByBuild.startsWith("runtime-migration")`,
 * với lý lẽ rằng gác bằng một mình `profileExport` sẽ nuốt luôn đường ĐỔI APP. Lý lẽ đó SAI, và cái giá
 * của nó là dựng lại đúng con bug của cả đêm:
 *
 *  - Đường đổi app đã bị loại bởi ba guard KHÁC trong [CastManualIntentRunner.retireUnprovenClusterClaim]
 *    (`IDLE_VERIFIED`, `activeTarget == null`, `protectedResidue == null`). Một phiên vừa cast xong là
 *    `ACTIVE_VERIFIED` + có `activeTarget` (CastCoordinator.kt:786-790), nên nó không bao giờ chạm tới
 *    tiền tố này. Chính fixture `runtimeCastSession()` của test `warm session written by a real cast
 *    still switches without re-running bootstrap` là bằng chứng: nó ACTIVE_VERIFIED, và test vẫn xanh
 *    sau khi bỏ tiền tố.
 *  - Còn ca mà tiền tố đó LOẠI NHẦM là ca nguy hiểm nhất: kết cục STOP/RECOVER ghi
 *    `IDLE_VERIFIED | createdByBuild="runtime" | profileExport=null | activeTarget=null`
 *    (CastCoordinator.kt:774-777). Mà thang STOP vừa phát `SEAL_DL3_COMPENSATE_18` rồi `_0`
 *    (CastPlanner.kt:119-128) — hai opcode ĐÓNG đường chiếu, đo thật đêm 31/7: gửi 18 rồi 0 là cụm trở
 *    lại đồng hồ native. `"runtime".startsWith("runtime-migration")` = false ⇒ cổng không bắt ⇒ lượt
 *    chiếu NGAY SAU một lần Dừng đi thẳng `executeOrdinary`, đặt app lên display ảo mà đường chiếu vừa
 *    bị đóng, và cụm vật lý ở nguyên đồng hồ. Không một chỗ nào khác trong repo phát lại 30/16/35 (grep
 *    `SEAL_DL3_BOOTSTRAP` = chỉ CastColdBootstrap), nên đó là ngõ cụt vĩnh viễn cho tới lần xoá dữ liệu
 *    app kế tiếp — đúng triệu chứng phải `pm clear` năm lần, chỉ khác là nó xuất hiện từ lượt chiếu THỨ
 *    HAI thay vì lượt đầu. Đây cũng chính là tiêu chí nghiệm thu T5 ("10 lượt chiếu/trả liên tiếp").
 *
 * Nên cổng quay về đúng nguyên văn §Design của spec: KHÔNG có seal ⇒ chưa chứng minh được cụm đang mở ⇒
 * chạy lại thang thật. Gửi lại 30/16/35 khi đường chiếu tình cờ vẫn mở là an toàn (đo thật: lệnh trả
 * `Parcel(00000000 00000000)` kể cả khi display đã có sẵn — xem khối lý do trong `CastColdBootstrap.run`),
 * còn KHÔNG gửi khi nó đã đóng thì chắc chắn mất cụm. Đánh đổi một chiều, chọn chiều an toàn.
 *
 * Mức bằng chứng (CLAUDE.md §2): **đã chứng minh bằng đọc source** cho toàn bộ chuỗi suy luận ở trên
 * (mọi file:line đã trích); **đã đo trên xe** cho hai mệnh đề vật lý (18/0 đóng cụm, 30/16/35 mở cụm).
 * Chưa đo lại trên xe sau bản vá này — T5 là chỗ chốt.
 */
private fun StableCastSession.isUnprovenClusterClaim(): Boolean = profileExport == null

/**
 * Lượt đặt sắp tới có rẽ vào nhánh cold-bootstrap không — THUẦN, không đụng bản ghi bền.
 *
 * Là bản chép ĐÚNG hai vế của cổng trong [CastManualIntentRunner.run]
 * (`current.stableSession == null || retireUnprovenClusterClaim()`), tách ra thành vị từ để trả lời được
 * câu hỏi ấy mà không phải GỌI `retireUnprovenClusterClaim()` — hàm đó xoá `stableSession` như một tác
 * dụng phụ của việc trả về true, nên dùng nó làm phép thử là phá bản ghi bền của một cú chạm rồi mới từ
 * chối cú chạm ấy.
 *
 * Ai sửa danh sách điều kiện của [CastManualIntentRunner.retireUnprovenClusterClaim] phải sửa ở đây —
 * mà chính vì thế nó chỉ tồn tại MỘT bản: hàm kia gọi thẳng vị từ này chứ không viết lại lần thứ hai.
 */
private fun CastSessionEnvelope.wouldRunColdBootstrap(): Boolean =
    stableSession == null || retiresUnprovenClusterClaim()

/** Xem KDoc [CastManualIntentRunner.retireUnprovenClusterClaim] để biết vì sao từng dòng có mặt. */
private fun CastSessionEnvelope.retiresUnprovenClusterClaim(): Boolean {
    val stable = stableSession ?: return false
    if (!stable.isUnprovenClusterClaim()) return false
    if (stable.state != StableState.IDLE_VERIFIED || stable.activeTarget != null ||
        stable.protectedResidue != null
    ) return false
    return transaction == null && !stopRequested && pendingIntent == null &&
        adjustmentDraft == null && !pendingUiRollback
}

data class CastManualTargetSnapshot(
    val evidence: TargetEvidence,
    val installed: Boolean,
    val hasLauncher: Boolean,
)

fun interface CastManualTargetReader {
    fun read(packageName: String): CastManualTargetSnapshot
}

sealed interface CastManualIntentResult {
    data class Succeeded(val stableSession: StableCastSession) : CastManualIntentResult
    data class Queued(val packageName: String) : CastManualIntentResult
    data class VerificationPending(val operationId: UUID, val reason: String) : CastManualIntentResult
    data class RecoveryRequired(val operationId: UUID, val reason: String) : CastManualIntentResult
    data class Blocked(val reason: String) : CastManualIntentResult
}

sealed interface CastManualTargetEligibility {
    data class Ready(val targetClass: TargetClass) : CastManualTargetEligibility
    data class Blocked(val reason: String) : CastManualTargetEligibility
}

fun CastManualTargetSnapshot.eligibilityFor(
    current: CastSessionEnvelope,
): CastManualTargetEligibility {
    if (!installed) return CastManualTargetEligibility.Blocked("Target is not installed")
    if (!hasLauncher) return CastManualTargetEligibility.Blocked("Target has no launcher")
    val targetClass = CastPolicy.classify(evidence)
    if (targetClass == TargetClass.UNKNOWN_PROTECTED) {
        return CastManualTargetEligibility.Blocked("Target policy is unknown")
    }
    val rollout = CastRolloutRegistry.resolve(current, CastRolloutRegistry.vehicleTestCandidate)
    if (rollout.effectiveUiVersion != EngineVersion.V2 || targetClass !in rollout.enabledSlices) {
        return CastManualTargetEligibility.Blocked("V2 slice is disabled for $targetClass")
    }
    return CastManualTargetEligibility.Ready(targetClass)
}

/** High-level composition only: no shell/opcode knowledge and no mutation owner beyond CastExecutor. */
internal class CastManualIntentRunner(
    private val store: CastSessionStore,
    private val observation: ObservedStateReader,
    private val executor: CastExecutor,
    private val completeVerification: (UUID, ObservedState) -> Boolean,
    private val resetVerificationSample: (UUID) -> Unit,
    private val queueLatestTarget: (String) -> Boolean,
    private val sleeper: CastSleeper,
    private val verificationDelayMillis: Long,
    /** Cùng đồng hồ tường mà chủ sở hữu dùng, để [awaitSettle] đọc được hạn còn lại của transaction. */
    private val nowEpochMillis: () -> Long,
    /**
     * Gửi bố cục ô của lượt vừa phát lệnh về cho chủ sở hữu giữ (`CastCoordinator.rememberIntendedLayout`),
     * vì phép xác minh sống ở đó. Không làm gì khi kế hoạch không có ô — đường chiếu toàn cụm không đi qua.
     */
    private val rememberIntendedLayout: (ExecutionResult, IntendedClusterLayout?) -> Unit = { _, _ -> },
) {
    init { require(verificationDelayMillis >= 0) }

    /**
     * [automation] = lượt chạy này là tự-chiếu-lúc-khởi-động (BOOT_AUTO), không phải người bấm.
     *
     * Hai luật chỉ áp cho tự động hoá, và cả hai PHẢI nằm ở đây chứ không chỉ ở tầng gọi (review vòng 3,
     * 2026-07-30, docs/specs/cast-simplified-active-app-toggle.html):
     *
     * 1. **Không cướp phiên đang sống.** Cửa sớm ở `CastCoordinator.runManualIntent` đọc envelope TRƯỚC
     *    khi vào lease, nên mọi thứ nó thấy còn kịp đổi: một cú chạm nút nổi bắt đầu chiếu ngay sau đó
     *    sẽ giữ lease vài giây, và khi tự động hoá giành được lease thì `activeTarget` đã có — lúc ấy
     *    `executeOrdinary` lập kế hoạch `SWITCH` và đổi cụm khỏi thứ người lái vừa chọn. Cửa ràng buộc
     *    phải đọc CÙNG một `current` mà kế hoạch được dựng từ đó, tức bên trong lease. Đây đúng là điều
     *    CLAUDE.md §5 đòi: "Guard cứng đặt ở tầng thi hành".
     * 2. **Không xếp hàng.** Tự động hoá có ngân sách MỘT claim cho mỗi lần khởi động; xếp hàng là khái
     *    niệm của người dùng. `queueLatestTarget` ghi `pendingIntent` gốc **USER** (mặc định của
     *    `CastModels.withPendingPackage`) — nên một lượt tự động hoá bị xếp hàng sẽ để lại một hàng đợi
     *    gốc USER bền mà `CastAutomationSettings.terminalize` KHÔNG dọn (nó chỉ dọn hàng đợi khớp
     *    `requestId`), và `initializeForBoot` giữ nó qua mọi lần khởi động ⇒ tự động hoá tự tay tắt
     *    chính mình cho mọi lần nổ máy sau. Từ chối thẳng, kết cục hữu hạn, không để lại dấu vết bền.
     */
    fun run(
        packageName: String,
        facts: CastVehicleFacts,
        targets: CastManualTargetReader,
        allowDestructive: Boolean = false,
        preferredDensityDpi: Int? = null,
        clusterStyle: ClusterStyle = ClusterStyle.CURVED,
        automation: Boolean = false,
        slotLayout: ClusterSlotLayout? = null,
    ): CastManualIntentResult {
        if (!MANUAL_ANDROID_PACKAGE.matches(packageName)) {
            return CastManualIntentResult.Blocked("Target package name is invalid")
        }
        if (slotLayout != null && packageName !in slotLayout.packages) {
            return CastManualIntentResult.Blocked("The placed app must own one of the requested slots")
        }
        val before = envelope() ?: return CastManualIntentResult.Blocked("Durable store unavailable")
        if (before.transaction != null) {
            // Yêu cầu chia ô KHÔNG được xếp hàng: `PendingCastIntent` chỉ mang được TÊN GÓI qua tầng bền
            // (`CastEnvelopeCodec.encodePending`), nên một lượt xếp hàng sẽ sống lại ở `continuePending`
            // như một lượt chiếu TOÀN CỤM — tức lặng lẽ đổi thứ người lái yêu cầu thành một thứ khác, và
            // đổi đúng theo hướng che mất app kia. Từ chối thẳng, kết cục hữu hạn, không để lại dấu bền.
            if (slotLayout != null) return CastManualIntentResult.Blocked(SLOT_NEVER_QUEUES)
            if (automation) return CastManualIntentResult.Blocked(AUTOMATION_NEVER_QUEUES)
            val target = checkTarget(packageName, before, targets)
            if (target is CastManualTargetEligibility.Blocked) return CastManualIntentResult.Blocked(target.reason)
            return if (queueLatestTarget(packageName)) CastManualIntentResult.Queued(packageName)
            else CastManualIntentResult.Blocked("Target could not be queued in the current state")
        }
        return executor.withMutationLease {
            val current = envelope() ?: return@withMutationLease blockedStore()
            if (current.stopRequested) {
                return@withMutationLease CastManualIntentResult.Blocked("Stop requested; manual intent is fenced")
            }
            if (current.transaction != null) {
                if (slotLayout != null) return@withMutationLease CastManualIntentResult.Blocked(SLOT_NEVER_QUEUES)
                if (automation) return@withMutationLease CastManualIntentResult.Blocked(AUTOMATION_NEVER_QUEUES)
                return@withMutationLease if (queueLatestTarget(packageName)) {
                    CastManualIntentResult.Queued(packageName)
                } else CastManualIntentResult.Blocked("Operation already active")
            }
            val target = checkTarget(packageName, current, targets)
            if (target is CastManualTargetEligibility.Blocked) return@withMutationLease CastManualIntentResult.Blocked(target.reason)
            // ── Yêu cầu ô KHÔNG sống sót qua nhánh bootstrap ────────────────────────────────────────
            //
            // Cửa này CHỈ gác đúng một điều, và điều đó thuần cơ học: cổng ngay dưới có thể rẽ sang
            // `bootstrapForManualIntent` rồi `continuePending`, mà `continuePending` tái lập kế hoạch từ
            // `pendingIntent` — thứ chỉ mang được TÊN GÓI qua tầng bền (`CastEnvelopeCodec.encodePending`).
            // Yêu cầu ô sẽ bốc hơi và lượt đặt biến thành chiếu TOÀN CỤM: đúng cái đánh tráo im lặng mà
            // [SLOT_NEVER_QUEUES] vừa từ chối ở trên, và đánh tráo theo hướng NUỐT app đang ở nửa kia.
            //
            // ★ VÌ SAO KHÔNG CÒN GÁC BẰNG `stableSession?.activeTarget == null` (2026-08-01).
            // Bản đầu viết đúng như thế, với lý lẽ thứ hai rằng "ô cắt từ khung task thật nên cụm phải có
            // sẵn app". Lý lẽ đó chết cùng ngày mà [ClusterSplit.slotBand] mở khoá lượt đặt app ĐẦU TIÊN:
            // cụm rỗng nay cắt ô được từ khung display. Nhưng điều kiện cũ thì loại thẳng CHÍNH ca đó —
            // cụm rỗng + phiên IDLE_VERIFIED = không có `activeTarget` — nên nó đã trở thành cửa duy nhất
            // còn chặn tính năng, mà lại chặn vì một lý do không còn đúng.
            //
            // Điều kiện mới là ĐÚNG mệnh đề cần gác, không hơn: "lượt này có rẽ vào nhánh bootstrap không".
            // Nó đọc đúng hai vế của cổng ngay dưới, nhưng bằng một vị từ THUẦN, KHÔNG TÁC DỤNG PHỤ —
            // `retireUnprovenClusterClaim()` XOÁ `stableSession` khi nó trả true, nên gọi nó để "thử xem"
            // là tự tay phá bản ghi bền rồi mới từ chối cú chạm.
            //
            // TOCTOU, và vì sao nó chỉ lệch về phía an toàn: `current` đọc trong lease, còn
            // `retireUnprovenClusterClaim()` đọc lại dưới store lock, nên giữa hai lần đọc vẫn có khe —
            // `queueLatestTarget` ghi `pendingIntent` chỉ cần store lock (CastCoordinator.kt:93-104), và
            // `requestStop` ghi `stopRequested` cũng vậy. Cả hai đều làm vị từ retire thành FALSE, tức chỉ
            // có thể biến "sẽ bootstrap" thành "không bootstrap" — nghĩa là cửa này có thể từ chối oan một
            // lượt lẽ ra chạy được (người lái chạm lại là xong), nhưng KHÔNG BAO GIỜ thả một yêu cầu ô đi
            // vào nhánh bootstrap để bị đánh tráo. Một chiều, và là chiều an toàn.
            var effectiveSlotLayout = slotLayout
            if (effectiveSlotLayout != null && current.wouldRunColdBootstrap()) {
                // 2026-08-02: thay vì block hoàn toàn, bỏ slot và cast FULL. Lý do: projection cần
                // mở trước (bootstrap), mà bootstrap replan từ pendingIntent mất thông tin ô. Sau khi
                // cast full thành công (tạo seal), lần cast slot tiếp theo sẽ đi executeOrdinary OK.
                // UX: user bấm nửa trái → app lên toàn cụm (lần đầu) → bấm lại nửa trái → đúng nửa.
                effectiveSlotLayout = null
            }
            // ── Cổng "một chế độ duy nhất" (R1, docs/specs/cast-one-mode-and-three-zone-bubble.html) ──
            //
            // Trước bản vá này điều kiện chỉ là `stableSession == null`. Đo trên DiLink3 đêm 31/7–1/8:
            // `CastLifecycleMigration.migratePristine` (CastAndroidLifecycle.kt:55-74) thấy cụm đang rảnh
            // là ghi thẳng một stableSession IDLE_VERIFIED (reason=`runtime-migration`, profileExport=`~`)
            // mà KHÔNG phát một opcode AutoContainer nào. Kể từ đó mọi lượt cast rơi vào `executeOrdinary`
            // chỉ vì `stableSession != null`, nên thang bootstrap thật KHÔNG BAO GIỜ chạy. Hệ quả đo được:
            // WindowManager đặt app lên display cụm hoàn toàn đúng (`am stack list` xác nhận, `screencap
            // -d 1` chụp được app đang vẽ thật) nhưng CỤM VẬT LÝ vẫn hiện đồng hồ gốc — vì phần cứng chưa
            // hề được bảo chuyển sang Android. Chứng minh ngược ngay tại xe: gõ tay đúng ba opcode
            // (`service call AutoContainer 2 i32 1000 i32 30` / `16` / `35`) là ảnh hiện lên cụm tức thì.
            //
            // Cổng gác bằng ĐÚNG nguyên văn §Design của spec: không có dấu seal `profileExport` ⇒ chưa
            // chứng minh được đường chiếu OEM đang mở ⇒ chạy lại thang thật. `completeVerificationLocked`
            // ghi `profileExport = null` cho MỌI kết cục CAST/SWITCH (CastCoordinator.kt:786-790) và
            // STOP/RECOVER (:774-777); chỉ BOOTSTRAP (:768-773) và APPLY_GEOMETRY (:778-785, kế thừa
            // `prior.profileExport`) giữ lại chuỗi seal.
            //
            // Điều đó KHÔNG làm hỏng đường đổi app, và đây là chỗ dễ kết luận nhầm nhất (review 2026-08-01
            // đã phải sửa lại đúng chỗ này): một phiên vừa cast xong là ACTIVE_VERIFIED và CÓ activeTarget,
            // nên nó bị `retireUnprovenClusterClaim()` loại ngay ở guard trạng thái, không bao giờ đi tới
            // `bootstrapForManualIntent` để mà bị `CastColdBootstrapPreflight` từ chối vì envelope
            // không-pristine (CastColdBootstrap.kt:91-94). Khoá bằng
            // `warm session written by a real cast still switches without re-running bootstrap`.
            //
            // Ngược lại, ca BẮT BUỘC phải lọt cổng là phiên do một lần DỪNG ghi ra (IDLE_VERIFIED,
            // createdByBuild=`runtime`, không seal, không activeTarget): thang STOP vừa phát 18 rồi 0 để
            // ĐÓNG đường chiếu, nên lượt chiếu kế tiếp phải mở lại cụm trước khi đặt app — nếu không, app
            // lên đúng display ảo mà cụm vật lý vẫn là đồng hồ. Khoá bằng
            // `a session written by a Stop must reopen the projection before the next placement`.
            //
            // `retireUnprovenClusterClaim()` chỉ chạy khi vế trái sai (đã có phiên), và chỉ trả true khi nó
            // THẬT SỰ đã gỡ tuyên bố đó ra — xem KDoc của hàm để biết vì sao bắt buộc phải gỡ chứ không
            // được chỉ rẽ nhánh.
            if (current.stableSession == null || retireUnprovenClusterClaim()) {
                when (val bootstrap = executor.bootstrapForManualIntent(
                    facts,
                    packageName,
                    { deadline -> observation.inspectRaw(deadline) },
                    { deadline -> observation.read(deadline) },
                    clusterStyle,
                )) {
                    is ColdBootstrapResult.Blocked -> CastManualIntentResult.Blocked(bootstrap.reason)
                    is ColdBootstrapResult.RecoveryRequired -> CastManualIntentResult.RecoveryRequired(
                        bootstrap.operationId,
                        bootstrap.reason,
                    )
                    is ColdBootstrapResult.Succeeded -> continuePending(targets, automation)
                }
            } else {
                executeOrdinary(
                    packageName,
                    (target as CastManualTargetEligibility.Ready).targetClass,
                    consumePending = false,
                    allowDestructive = allowDestructive,
                    preferredDensityDpi = preferredDensityDpi,
                    automation = automation,
                    slotLayout = slotLayout,
                )
            }
        }
    }

    fun resumePending(targets: CastManualTargetReader): CastManualIntentResult? = executor.withMutationLease {
        val current = envelope() ?: return@withMutationLease blockedStore()
        if (current.pendingIntent == null) return@withMutationLease null
        if (current.stopRequested) return@withMutationLease CastManualIntentResult.Blocked("Stop requested; pending target is fenced")
        if (current.transaction != null) return@withMutationLease CastManualIntentResult.Blocked("Operation already active")
        if (current.stableSession == null) {
            return@withMutationLease CastManualIntentResult.Blocked("Pending target has no verified stable session")
        }
        continuePending(targets, automation = false)
    }

    private fun continuePending(
        targets: CastManualTargetReader,
        automation: Boolean,
    ): CastManualIntentResult {
        repeat(MAX_PENDING_REPLANS) {
            val current = envelope() ?: return blockedStore()
            if (current.stopRequested) return CastManualIntentResult.Blocked("Stop requested; pending target is fenced")
            if (current.transaction != null) return CastManualIntentResult.Blocked("Operation already active")
            if (current.stableSession == null) {
                return CastManualIntentResult.Blocked("Pending target has no verified stable session")
            }
            val packageName = current.pendingPackage ?: return CastManualIntentResult.Blocked("Pending target disappeared")
            when (val target = checkTarget(packageName, current, targets)) {
                is CastManualTargetEligibility.Blocked -> {
                    if (discardPending(packageName)) {
                        return CastManualIntentResult.Blocked("Pending target is no longer eligible: ${target.reason}")
                    }
                }
                is CastManualTargetEligibility.Ready -> {
                    val result = executeOrdinary(
                        packageName, target.targetClass, consumePending = true, automation = automation,
                    )
                    if (!pendingChangedAfterBlocked(packageName, result)) return result
                }
            }
        }
        return CastManualIntentResult.Blocked("Pending target changed repeatedly; latest selection remains durable")
    }

    private fun executeOrdinary(
        packageName: String,
        targetClass: TargetClass,
        consumePending: Boolean,
        allowDestructive: Boolean = false,
        preferredDensityDpi: Int? = null,
        automation: Boolean = false,
        /** `null` ở MỌI caller cũ (kể cả [continuePending]) ⇒ nhánh dưới đây chạy y nguyên như trước. */
        slotLayout: ClusterSlotLayout? = null,
    ): CastManualIntentResult {
        val current = envelope() ?: return blockedStore()
        val stable = current.stableSession ?: return CastManualIntentResult.Blocked("Verified stable session is required")
        if (current.stopRequested || current.transaction != null) {
            return CastManualIntentResult.Blocked("Operation is fenced or already active")
        }
        // Cửa ràng buộc của luật 1 (xem KDoc của [run]): đọc trên CHÍNH `current` mà kế hoạch dưới đây
        // dựng từ đó, và đang giữ mutation lease — nên không còn khe nào để một cú chạm chen vào giữa
        // "thấy cụm rảnh" và "phát lệnh SWITCH".
        if (automation && stable.activeTarget != null) {
            return CastManualIntentResult.Blocked(CastCoordinator.LIVE_SESSION_REFUSAL)
        }
        val kind = if (stable.activeTarget == null) CastIntentKind.CAST else CastIntentKind.SWITCH
        CastOperationLog.record(
            "$kind $packageName · class=$targetClass · destructive=$allowDestructive · epoch=${current.durableEpoch}" +
                (slotLayout?.let { " · slot=${it.slots[packageName]}" } ?: ""),
        )
        val plan = CastPlanner.plan(
            CastIntent(
                kind,
                packageName,
                geometry = preferredDensityDpi?.let {
                    AcceptedGeometry(CastRect(0, 0, 0, 0), it, "cluster-density-request")
                },
                allowDestructive = allowDestructive,
                slotLayout = slotLayout,
            ),
            PlannerSnapshot(
                observed = observation.read(),
                stableSession = stable,
                targetClass = targetClass,
                installed = true,
                hasLauncher = true,
                plannerEpoch = current.durableEpoch,
            ),
        )
        val ready = plan as? PlanResult.Ready
            ?: return CastManualIntentResult.Blocked((plan as PlanResult.Blocked).reason)
        val execution = if (consumePending) {
            executor.executePendingTarget(ready.plan, stable.baseline, packageName)
        } else executor.execute(ready.plan, stable.baseline, packageName)
        // Ngay sau khi lệnh đã phát và TRƯỚC khi chờ xác minh: phép xác minh (sống ở CastCoordinator) phải
        // có bố cục trong tay trước mẫu quan sát đầu tiên, nếu không nó sẽ trả `false` đúng như khi bố cục
        // đã mất. Không có ô thì lời gọi này là no-op.
        rememberIntendedLayout(execution, ready.plan.intendedLayout)
        return when (execution) {
            is ExecutionResult.Blocked -> CastManualIntentResult.Blocked(execution.reason)
            is ExecutionResult.RecoveryRequired -> CastManualIntentResult.RecoveryRequired(
                execution.operationId,
                execution.reason,
            )
            is ExecutionResult.AwaitingVerification -> verify(execution.operationId)
        }
    }

    private fun verify(operationId: UUID): CastManualIntentResult {
        // Thử observation-based verification TRƯỚC (đường cũ, chính xác hơn).
        // Nếu observation Unknown → fallback sang ledger-based.
        awaitSettle(operationId)?.let { return it }
        repeat(2) { sampleIndex ->
            when (val sample = observation.read()) {
                is ObservationValue.Known -> if (completeVerification(operationId, sample.value)) {
                    val stable = envelope()?.stableSession
                    if (stable != null && stable.state in ACTIVE_STATES) {
                        return CastManualIntentResult.Succeeded(stable)
                    }
                    resetVerificationSample(operationId)
                    return CastManualIntentResult.VerificationPending(operationId, "Active stable state was not committed")
                }
                is ObservationValue.Unknown -> {
                    // 2026-08-01: Observation Unknown thường do ADB key chưa trust (prompt "Allow USB
                    // debugging" đang hiện). Nếu đây là mẫu đầu (sampleIndex=0) → chờ 5s rồi thử lại
                    // (user có thể bấm Allow trong lúc đó). Nếu mẫu thứ 2 cũng Unknown → ledger fallback.
                    if (sampleIndex == 0) {
                        try { sleeper.sleep(5_000L) } catch (_: Exception) { }
                        // Loop tiếp sang sampleIndex=1
                    } else {
                        // Mẫu thứ 2 vẫn Unknown → fallback ledger-based verification.
                        val envNow = envelope()
                        val txNow = envNow?.transaction
                        if (txNow != null && txNow.operationId == operationId &&
                            txNow.operation in setOf(CastOperation.CAST, CastOperation.SWITCH) &&
                            txNow.ledger.isNotEmpty() && txNow.ledger.all { it.effect == LedgerEffect.OBSERVED }
                        ) {
                            val committed = commitFromLedger(operationId, txNow, envNow)
                            if (committed != null) return CastManualIntentResult.Succeeded(committed)
                        }
                        resetVerificationSample(operationId)
                        return verificationPending(operationId, sample.reason)
                    }
                }
                is ObservationValue.Unsupported -> {
                    resetVerificationSample(operationId)
                    return verificationPending(operationId, sample.reason)
                }
            }
            val transaction = envelope()?.transaction
            if (transaction?.phase == OperationPhase.RECOVERING) {
                // 2026-08-02: Ledger fallback — nếu mọi step đã OBSERVED thì app ĐÃ lên cụm,
                // completeVerification ghi RECOVERING vì terminal check strict (appops/geometry/animation
                // không khớp chính xác). Nhưng cast thực tế ĐÃ thành công — commit ACTIVE từ ledger.
                val envNow = envelope()
                val txNow = envNow?.transaction
                if (txNow != null && txNow.operationId == operationId &&
                    txNow.operation in setOf(CastOperation.CAST, CastOperation.SWITCH) &&
                    txNow.ledger.isNotEmpty() && txNow.ledger.all { it.effect == LedgerEffect.OBSERVED }
                ) {
                    val committed = commitFromLedger(operationId, txNow, envNow)
                    if (committed != null) return CastManualIntentResult.Succeeded(committed)
                }
                resetVerificationSample(operationId)
                return CastManualIntentResult.RecoveryRequired(
                    operationId,
                    transaction.lastFailure ?: "Verification diverged at sample ${sampleIndex + 1}",
                )
            }
            // Khoảng nghỉ THẬT giữa hai mẫu xác nhận — trước bản vá này hai mẫu đọc liền tay gần như
            // cùng một thời điểm, nên "hai mẫu giống nhau" gần như luôn đúng ngay cả khi cụm chưa ổn
            // định thật. Chỉ nghỉ sau mẫu đầu (sampleIndex=0), không nghỉ sau mẫu cuối.
            if (sampleIndex == 0) awaitSettle(operationId)?.let { return it }
        }
        resetVerificationSample(operationId)
        return verificationPending(operationId, "Two equal active observations did not converge")
    }

    /**
     * Nghỉ cho cụm lắng trước khi tin một lần quan sát — nhưng KHÔNG BAO GIỜ nghỉ hết hạn của chính
     * transaction đang chờ xác minh. Trả `null` khi đã nghỉ xong (hoặc không cần nghỉ).
     *
     * ★ VÌ SAO PHẢI KẸP THEO HẠN (review 2026-08-01, Pass 1 — [P1]). Ngày 31/7 con số nghỉ được nâng
     * 500ms → 3000ms và thêm một lần nghỉ THỨ HAI giữa hai mẫu, với lý lẽ "ngân sách thật của cả
     * transaction là 15s nên 6s vẫn thừa chỗ". Lý lẽ đó chỉ cộng phần NGỦ. Cùng một hạn
     * (`deadline = now + operationTimeoutMillis`, `CastExecutor.executeLocked`, mặc định 15s) còn phải
     * nuôi:
     *
     *   • cả thang đặt app — 9 bước, mỗi bước một round-trip adb/dadb riêng (`CastPlanner.placementSteps`);
     *   • HAI lần quan sát, mỗi lần SÁU lệnh shell (`ObservedStateReader.OBSERVATION_ORDER`).
     *
     * Tức 21 round-trip cộng 6 giây ngủ, trong 15 giây. Vượt hạn thì `completeVerificationLocked` ghi
     * `copyForRecovery("verification deadline exceeded")` → RECOVERING — đúng cái kết cục mà con số 3000ms
     * sinh ra để tránh. Mức bằng chứng (CLAUDE.md §2): **đã chứng minh bằng đọc source** rằng ba khoản
     * trên chia CHUNG một hạn; **chưa đo** độ trễ thật mỗi round-trip trên xe, nên ở đây không hardcode
     * một con số mới nào — chỉ ràng buộc theo tỉ lệ.
     *
     * Luật: ngủ nhiều nhất MỘT NỬA thời gian còn lại. Bất biến rút ra được ngay: sau khi ngủ vẫn còn ít
     * nhất một nửa ngân sách cho lần quan sát kế tiếp, dù thang có chạy chậm tới đâu. Ngân sách rộng thì
     * hàm này ngủ đủ [verificationDelayMillis] như đã hiệu chỉnh trên xe (15s còn lại ⇒ trần 7,5s ⇒ ngủ
     * đủ 3s, không đổi một li so với bản 31/7); ngân sách hẹp thì nó tự co lại thay vì cầm chắc vượt hạn.
     * Không còn transaction nữa thì cũng chẳng còn gì để chờ ⇒ không ngủ.
     */
    /**
     * Commit ACTIVE_VERIFIED trực tiếp từ ledger, KHÔNG cần observation.
     *
     * Điều kiện: mọi bước trong ledger đều OBSERVED (gateway confirm shell exit 0). Đó là bằng chứng
     * đủ rằng placement đã thành công. Trả `null` nếu store write fail.
     */
    private fun commitFromLedger(
        operationId: UUID,
        tx: CastTransaction,
        currentEnvelope: CastSessionEnvelope,
    ): StableCastSession? {
        val targetPkg = tx.targetPkg ?: return null
        val displayIdentity = tx.expectedDisplayIdentity ?: return null
        // Nếu ledger chứa bootstrap opcodes (30/16/35) → bootstrap ĐÃ chạy thành công → ghi seal.
        // Nếu không → đây là lần cast warm (seal đã có từ trước) → copy seal từ session hiện tại.
        val hasBootstrapInLedger = tx.ledger.any { it.commandKind in SealDl3BootstrapProfile.forwardKinds }
        val seal = if (hasBootstrapInLedger) "seal-dl3-cold-bootstrap-v1"
            else currentEnvelope.stableSession?.profileExport
        val stable = StableCastSession(
            StableState.ACTIVE_VERIFIED,
            EngineVersion.V2,
            "runtime-ledger",
            seal,
            displayIdentity,
            tx.baseline,
            CastTarget(targetPkg, 0, 0), // taskId/displayId unknown without observation
            null,
            currentEnvelope.stableSession?.acceptedGeometry,
            nowEpochMillis(),
        )
        val written = store.locked {
            val envelope = (read() as? StoreRead.Loaded)?.envelope ?: return@locked false
            val current = envelope.transaction
            if (current?.operationId != operationId) return@locked false
            update { it.copy(stableSession = stable, transaction = null, stopRequested = false) }
            true
        }
        if (!written) return null
        CastOperationLog.record(
            "CAST verified from ledger (all ${tx.ledger.size} steps OBSERVED): $targetPkg on $displayIdentity",
        )
        return stable
    }

    private fun awaitSettle(operationId: UUID): CastManualIntentResult? {
        if (verificationDelayMillis <= 0) return null
        val deadline = envelope()?.transaction?.takeIf { it.operationId == operationId }
            ?.deadlineAtEpochMillis ?: return null
        val millis = minOf(verificationDelayMillis, (deadline - nowEpochMillis()) / 2)
        if (millis <= 0) return null
        try {
            sleeper.sleep(millis)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            resetVerificationSample(operationId)
            return CastManualIntentResult.VerificationPending(operationId, "Verification wait was interrupted")
        } catch (failure: RuntimeException) {
            resetVerificationSample(operationId)
            return CastManualIntentResult.VerificationPending(
                operationId,
                "Verification wait failed: ${failure.message.orEmpty()}",
            )
        }
        return null
    }

    private fun verificationPending(operationId: UUID, reason: String) =
        CastManualIntentResult.VerificationPending(operationId, reason)

    private fun checkTarget(
        packageName: String,
        current: CastSessionEnvelope,
        targets: CastManualTargetReader,
    ): CastManualTargetEligibility {
        val snapshot = try {
            targets.read(packageName)
        } catch (failure: Exception) {
            return CastManualTargetEligibility.Blocked("Target validation failed: ${failure.message ?: failure.javaClass.simpleName}")
        }
        return snapshot.eligibilityFor(current)
    }

    /**
     * Gỡ một tuyên bố "cụm đã sẵn sàng" chưa từng phát opcode nào, để bootstrap thật chạy được — trả về
     * true CHỈ KHI đã gỡ xong; mọi trường hợp khác trả false và không đụng gì vào bản ghi bền.
     *
     * **Bắt buộc phải GỠ, không thể chỉ rẽ nhánh** — đây là câu trả lời cho "bootstrapForManualIntent có
     * chạy đúng khi đã có sẵn stableSession không?": KHÔNG. `bootstrapForManualIntent` → `CastColdBootstrap
     * .run` → `createTransaction` → `CastColdBootstrapPreflight.inspect`, và preflight chặn thẳng khi
     * `envelope.stableSession != null` (CastColdBootstrap.kt:91-94, lý do "durable envelope is not
     * pristine"). Nếu chỉ đổi điều kiện rẽ nhánh mà không đưa envelope về pristine thì cú chạm chiếu trả về
     * Blocked, tức thay một lỗi hiển thị bằng một lỗi mất hẳn chức năng.
     *
     * Xoá `stableSession` không phải sáng kiến mới ở đây: `CastCoordinator.reconcileUnobservableIdleSession`
     * (CastCoordinator.kt:221-242) làm đúng thế cho một claim idle mà đời máy này không còn xác minh lại
     * được, và KDoc của chính preflight (CastColdBootstrap.kt:69-77) đã viết sẵn cho tình huống "claim bị
     * xoá rồi bootstrap lại từ đầu".
     *
     * Guard hẹp có chủ đích, mỗi dòng khoá một cách hỏng cụ thể:
     * - `IDLE_VERIFIED` + không `activeTarget` + không `protectedResidue`: biến thể "unowned" của cùng đường
     *   di trú (`runtime-migration-unowned`, CastAndroidLifecycle.kt:36-53) là RECOVERY_PENDING và GHI NHỚ
     *   app đang chiếm cụm — đó là hồ sơ DUY NHẤT để Stop biết phải trả cái gì về đâu. Xoá nó là tự cắt
     *   đường lùi (CLAUDE.md §5). Ca đó cứ đi tiếp `executeOrdinary` và bị planner chặn tường minh
     *   ("SWITCH requires matching durable and observed V2 active state", CastPlanner.kt:20-24).
     * - `transaction`/`stopRequested`/`pendingIntent`/`adjustmentDraft`/`pendingUiRollback`: đúng danh sách
     *   pristine của preflight (CastColdBootstrap.kt:91-94). Chỉ gỡ khi việc gỡ TỰ NÓ đã đủ để đạt pristine
     *   — cùng kỷ luật mà CastCoordinator.kt:209-219 đã rút ra: đừng bao giờ vứt hồ sơ đi rồi vẫn nhận
     *   Blocked. Danh sách bên preflight mà đổi thì chỗ này phải đổi theo.
     * - đọc và ghi trong CÙNG một `store.locked`: `queueLatestTarget` ghi `pendingIntent` chỉ với store lock
     *   chứ không cần mutation lease (CastCoordinator.kt:36-47), nên đọc ngoài rồi ghi trong vẫn còn khe.
     *
     * Dùng `bumpEpoch` chứ không `update` để một hồi đáp muộn của gateway thuộc epoch cũ không thể bị nhận
     * nhầm là của phiên mới — giống hệt CastCoordinator.kt:237.
     */
    private fun retireUnprovenClusterClaim(): Boolean = store.locked {
        val current = (read() as? StoreRead.Loaded)?.envelope ?: return@locked false
        val stable = current.stableSession ?: return@locked false
        // Vị từ sống ở đầu file và được [wouldRunColdBootstrap] dùng chung — một luật, một chỗ sửa.
        if (!current.retiresUnprovenClusterClaim()) return@locked false
        bumpEpoch { it.copy(stableSession = null) }
        CastOperationLog.record(
            "retired unproven cluster claim (${stable.createdByBuild}, no bootstrap profile export): " +
                "the AutoContainer ladder must run before any placement",
        )
        true
    }

    private fun discardPending(packageName: String): Boolean = store.locked {
        val current = (read() as? StoreRead.Loaded)?.envelope ?: return@locked false
        if (current.pendingPackage != packageName || current.transaction != null || current.stopRequested) {
            return@locked false
        }
        update { it.copy(pendingIntent = null) }
        true
    }

    private fun pendingChangedAfterBlocked(
        packageName: String,
        result: CastManualIntentResult,
    ): Boolean {
        if (result !is CastManualIntentResult.Blocked) return false
        val current = envelope() ?: return false
        return current.transaction == null && !current.stopRequested &&
            current.pendingPackage != null && current.pendingPackage != packageName
    }

    private fun envelope(): CastSessionEnvelope? =
        store.locked { (read() as? StoreRead.Loaded)?.envelope }

    private fun blockedStore() = CastManualIntentResult.Blocked("Durable store unavailable")

    companion object {
        private const val MAX_PENDING_REPLANS = 4
        private val ACTIVE_STATES = setOf(StableState.ACTIVE_VERIFIED, StableState.ACTIVE_DEGRADED)
        internal const val AUTOMATION_NEVER_QUEUES =
            "Operation already active; boot automation never queues a durable placement"

        /** Xếp hàng một yêu cầu chia ô là đánh tráo nó thành chiếu toàn cụm — xem chỗ dùng trong [run]. */
        internal const val SLOT_NEVER_QUEUES =
            "Operation already active; a cluster slot placement is never queued as a full-screen cast"

        /**
         * Nhánh cold-bootstrap tái lập kế hoạch từ `pendingIntent`, thứ chỉ mang được TÊN GÓI — yêu cầu ô
         * đi qua đó sẽ lặng lẽ thành một lượt chiếu toàn cụm. Xem chỗ dùng trong [run].
         *
         * KHÔNG phải "cụm phải có sẵn app": từ [ClusterSplit.slotBand], một cụm rỗng đã đặt được app đầu
         * tiên vào một nửa. Chỉ còn đúng ràng buộc cơ học này.
         */
        internal const val SLOT_NEVER_SURVIVES_BOOTSTRAP =
            "A cluster slot cannot survive the cold-bootstrap replan; cast one app full-screen first to " +
                "open the projection"
    }
}

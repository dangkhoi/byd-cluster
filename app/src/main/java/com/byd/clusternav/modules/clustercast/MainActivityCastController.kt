package com.byd.clusternav.modules.clustercast

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.byd.clusternav.R
import com.byd.clusternav.cast.platform.CastAndroidLifecycle
import com.byd.clusternav.cast.platform.CastAndroidRuntime
import com.byd.clusternav.cast.platform.CastAppCatalog
import com.byd.clusternav.modules.clustercast.v2.AcceptedGeometry
import com.byd.clusternav.modules.clustercast.v2.CastAction
import com.byd.clusternav.modules.clustercast.v2.DisconnectedSinkRecoveryProof
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Toàn bộ máy móc Cluster Cast của Home — mọi mutation vẫn đi qua CastCoordinator qua CastFacade.
 *
 * Ở riêng file vì `MainActivity` gánh cả Navigation+HUD lẫn Cast; gộp cả hai vào một file sẽ phá ngưỡng
 * LOC (đúng lý do `CastRowActions` từng tách khỏi `ClusterCastActivity`). Nhận `Activity` trực tiếp —
 * giống `CastAppListView`/`CastScreenChrome` — vì cần `findViewById`/`startActivity`/`runOnUiThread` ở
 * nhiều chỗ; không giữ trạng thái nào ngoài field của chính nó nên vẫn kiểm được qua các test tĩnh.
 *
 * docs/specs/cast-simplified-active-app-toggle.html — màn "Cấu hình" riêng đã bị xoá (§2.2); mọi thứ ở
 * đây sống trong panel bên phải của Home.
 */
internal class MainActivityCastController(private val activity: Activity) {
    private lateinit var runtime: CastAndroidRuntime.Runtime
    private lateinit var facade: CastFacade
    private lateinit var catalog: CastAppCatalog
    private lateinit var refreshReader: CastActivityRefreshReader
    private val operationStatus = CastOperationStatus()
    private val work = CastActivityWork()
    private val statusTimers = CastActivityStatusTimers(operationStatus, ::refresh)
    @Volatile private var destroyed = false
    private var stopSequence = 0L
    @Volatile private var stopRequestedAt: Instant? = null

    /**
     * Ảnh chụp quan sát mới nhất, luôn do LÀN NỀN của [refresh] ghi. Mọi thứ chạy trên main thread
     * (vẽ panel, guard của [CastRowActions.applySoon]) chỉ được đọc từ đây.
     *
     * Vì sao không gọi thẳng `facade.observedState()`: nó là `observe()`, tức I/O ra xe — KDoc của
     * façade ghi thẳng "đừng gọi trên main thread", và façade sinh ra chính là để chặn lớp lỗi "UI
     * chạm vào transport ngay trong đường vẽ". Với nhịp tick 1s của Home thì mỗi giây là một lần treo
     * luồng vẽ chờ dumpsys.
     *
     * Đánh đổi đã biết: ảnh chụp trễ tối đa một nhịp refresh. Chấp nhận được vì mọi phép PHÁT LỆNH đều
     * đọc lại quan sát tươi ở tầng nền ([CastFacade.applyGeometry] lập kế hoạch theo `observed.target`
     * của chính nó), nên ảnh chụp cũ chỉ ảnh hưởng phần hiển thị/gating, không quyết định lệnh gửi ra xe.
     */
    @Volatile private var observedTarget: String? = null
    @Volatile private var observedClusterSize: Pair<Int, Int> = DEFAULT_CLUSTER_SIZE
    @Volatile private var observedProfileId: String = DEFAULT_PROFILE_ID

    /** Panel kích thước chỉ dựng lại khi app đang chiếu ĐỔI — xem [refreshGeometryPanel]. */
    private var renderedGeometryTarget: String? = null
    private var geometryPanelRendered = false

    /** Gộp nhịp: tick 1s không được xếp thêm lượt refresh khi lượt trước còn đang chạy. */
    private val refreshInFlight = AtomicBoolean(false)

    /**
     * Chống HAI lượt tự-chiếu chạy CHỒNG nhau — không phải chống mọi lượt về sau.
     *
     * Khác biệt này là cả vấn đề: cờ một-lần `autoStartAttempted` trước đây chặn VĨNH VIỄN mọi lần
     * tự-chiếu tiếp theo trong đời tiến trình, nên sau lần mở app đầu tiên thì R6 chết. Chốt này chỉ
     * sống đúng khoảng thời gian một lượt đang đọc store; phần chống trùng thật sự nằm ở cửa cuối trên
     * LUỒNG VẼ (`work.mutationSnapshot().pending`), vì làn `misc` là pool 2 luồng, không serialize.
     */
    private val autoStartInFlight = AtomicBoolean(false)

    private lateinit var status: TextView
    private lateinit var statusDot: View
    private lateinit var geometryContainer: FrameLayout
    private lateinit var autoStartCheckbox: CheckBox
    private lateinit var autoStartSpinner: Spinner
    private lateinit var recoveryToggle: TextView
    private lateinit var recoveryActions: View
    private lateinit var stopButton: Button
    private lateinit var diagnosticsButton: Button
    private lateinit var retryButton: Button
    private lateinit var phoneDisconnectButton: Button
    private lateinit var recoverOnceButton: Button
    private lateinit var physicalInstructionButton: Button
    private lateinit var profileSetupButton: Button
    /** Chủ sở hữu ô tick + dropdown tự-chiếu, và là chỗ DUY NHẤT giữ danh sách app đã cài. */
    private lateinit var autoStart: CastAutoStartBinding

    fun onCreate() {
        catalog = CastAppCatalog(activity.applicationContext)
        runtime = CastAndroidRuntime.create(activity.applicationContext)
        facade = CastFacade.wrapping(runtime, catalog)
        refreshReader = CastActivityRefreshReader(runtime, catalog, operationStatus)

        status = activity.findViewById(R.id.txt_cast_status)
        statusDot = activity.findViewById(R.id.dot_cast)
        geometryContainer = activity.findViewById(R.id.cast_geometry_container)
        autoStartCheckbox = activity.findViewById(R.id.cb_autostart)
        autoStartSpinner = activity.findViewById(R.id.spinner_autostart_app)
        recoveryToggle = activity.findViewById(R.id.cast_recovery_toggle)
        recoveryActions = activity.findViewById(R.id.cast_recovery_actions)
        recoveryToggle.setOnClickListener {
            recoveryActions.visibility = if (recoveryActions.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        stopButton = activity.findViewById<Button>(R.id.cast_stop).apply { setOnClickListener { executeStop() } }
        diagnosticsButton = activity.findViewById<Button>(R.id.cast_diagnostics).apply {
            setOnClickListener { activity.startActivity(Intent(activity, DiagActivity::class.java)) }
        }
        retryButton = activity.findViewById<Button>(R.id.cast_retry).apply { setOnClickListener { retryConnect() } }
        phoneDisconnectButton = activity.findViewById<Button>(R.id.cast_phone_disconnect).apply {
            setOnClickListener { showPhoneDisconnectGuidance() }
        }
        recoverOnceButton = activity.findViewById<Button>(R.id.cast_recover_once).apply {
            setOnClickListener { confirmEligibleRecovery() }
        }
        physicalInstructionButton = activity.findViewById<Button>(R.id.cast_physical_instruction).apply {
            setOnClickListener { showPhysicalInstruction() }
        }
        profileSetupButton = activity.findViewById<Button>(R.id.cast_profile_setup).apply {
            setOnClickListener { openProfileSetup() }
        }
        listOf(stopButton, diagnosticsButton, retryButton, phoneDisconnectButton,
            recoverOnceButton, physicalInstructionButton, profileSetupButton).forEach { it.isEnabled = false }

        autoStart = CastAutoStartBinding(
            activity, catalog, runtime.automation,
            background = { work.misc(it) },
            postUi = { postUi(it) },
            toast = { toast(it) },
            // Nhãn app chỉ có sau lần nạp này; panel nào đã dựng trước đó đang hiện tên gói thô.
            onAppsLoaded = { geometryPanelRendered = false },
        )
        autoStart.bind(autoStartCheckbox, autoStartSpinner)
        work.misc {
            runCatching {
                facade.initialize(bootId())
                facade.applyVehicleTestRollout()
                CastAndroidLifecycle.rehydrate(activity.applicationContext)
            }
            // Cố ý chạy TRÊN LÀN NỀN, không trong postUi: cả hai đều đọc store bền (`facade.envelope()`)
            // và có thể phát lệnh chiếu. Bản đầu 2026-07-29 gọi chúng trong `postUi` — tức đọc file
            // store trên luồng vẽ ngay lúc mở app.
            autoStartOnOpen()
            drainPendingTarget()
            postUi { refresh() }
        }
    }

    /**
     * Tự-chiếu-khi-mở-app phải chạy ở CẢ đây, không chỉ `onCreate`.
     *
     * `MainActivity` KHÔNG khai `launchMode` trong manifest ⇒ `standard`. Mở lại ClusterNav từ launcher
     * khi task của nó đã tồn tại thì hệ thống chỉ đưa task lên trước và gọi `onResume` — `onCreate`
     * không chạy lần nữa, và với `standard` thì `onNewIntent` cũng không. Nếu R6 chỉ treo ở `onCreate`
     * thì sau lần mở đầu tiên của một tiến trình, tự-chiếu không bao giờ bắn lại được nữa dù cụm đang
     * rảnh thật — và đúng lúc đó nó lại là đường lui DUY NHẤT khi nút nổi không dò ra app đang mở
     * (dropdown chọn app → thoát ra → mở lại). Không cần cờ một-lần: [autoStartTarget] tự giới hạn —
     * chiếu xong là `activeTarget != null` nên lượt sau tự trả `null`.
     */
    fun onResume() {
        work.misc { autoStartOnOpen() }
        refresh()
    }

    /**
     * Cùng nhịp 1s với refresh() của Navigation+HUD, để panel bám đúng target bubble vừa đổi.
     *
     * Bỏ nhịp khi lượt trước còn đang chạy: mỗi lượt là một vòng quan sát ra xe, và làn refresh là
     * executor MỘT luồng — nếu một vòng lâu hơn 1s thì tick sẽ xếp hàng vô hạn phía sau nó.
     */
    fun tick() { if (!refreshInFlight.get()) refresh() }

    fun onDestroy() {
        destroyed = true
        statusTimers.close()
        operationStatus.clearAll()
        work.close()
    }

    // --- Auto-start khi mở app (R6) ---

    /** Chạy trên làn nền — đọc store bền và có thể phát lệnh chiếu. */
    private fun autoStartOnOpen() {
        if (!autoStartInFlight.compareAndSet(false, true)) return
        try {
            if (work.mutationSnapshot().pending) return
            val target = runCatching {
                val config = runtime.automation.config()
                autoStartTarget(config.defaultPackage, config.armable, facade.envelope())
            }.getOrNull() ?: return
            // Cửa cuối nằm trên LUỒNG VẼ, nơi `beginMutation()` cũng chạy: hai lượt cùng đọc được "rảnh"
            // (onCreate và onResume của lần mở đầu tiên) thì lượt sau vẫn thấy `pending` do lượt trước
            // vừa đặt, nên chỉ đúng một lệnh chiếu được phát.
            postUi { if (!work.mutationSnapshot().pending) executeCast(target) }
        } finally {
            autoStartInFlight.set(false)
        }
    }

    /**
     * Tiếp tục một ý định chiếu đã bị xếp hàng (`pendingIntent`) — đường này TỪNG sống trong
     * `ClusterCastActivity.drainPendingTarget()` và bị xoá cùng màn đó tối 2026-07-29.
     *
     * Vì sao phải giữ: `CastManualIntent.run()` xếp hàng target khi có transaction đang chạy
     * (`CastManualIntentResult.Queued`), ghi `pendingIntent` gốc USER vào store bền.
     * `CastSessionStore.initializeForBoot` CỐ Ý giữ lại pendingIntent gốc USER qua mọi lần khởi động
     * lại, còn `CastAutomationSettings` từ chối GHI yêu cầu tự-chiếu khi
     * `pendingIntent?.origin == USER` và `CastAutomationService.evaluate` terminalize nó thành
     * `USER_SUPERSEDED`. Không có ai rút hàng đợi thì một lần chạm trúng lúc bận sẽ tắt vĩnh viễn
     * tính năng tự-chiếu-khi-khởi-động-xe (R7) cho tới khi gỡ app.
     */
    private fun drainPendingTarget() {
        work.operation {
            if (facade.envelope()?.pendingPackage == null) return@operation
            val message = runCatching { facade.resumePendingIntent() }.getOrNull()?.statusMessage()
            postUi { message?.let { toast(it) }; refresh() }
        }
    }

    // --- Panel kích thước cho app đang chiếu (R5/2.3.1) ---

    // Cả ba lambda dưới đây chạy trên main thread (nút bấm của panel, và bộ debounce của
    // CastRowActions dùng Handler của main looper) nên chúng chỉ được đọc ảnh chụp, không quan sát.
    private val rowActions: CastRowActions by lazy {
        CastRowActions(
            catalog = catalog,
            background = { work.misc(it) },
            clusterSize = { observedClusterSize },
            activeTarget = { observedTarget },
            profileId = { observedProfileId },
            stillAlive = { !activity.isFinishing && !destroyed },
            applyGeometry = { geometry -> applyGeometry(geometry) },
        )
    }

    /**
     * Dựng lại panel CHỈ khi app đang chiếu đổi.
     *
     * Bản đầu 2026-07-29 gọi `removeAllViews()` + dựng lại 11 nút ở MỌI lần refresh — tức mỗi giây một
     * lần vì Home tick 1s. Ngoài rác cấp phát, nó nuốt thao tác: một cú chạm dài 80–150 ms mà rơi trúng
     * lúc panel bị thay thì `ACTION_DOWN` và `ACTION_UP` không còn cùng một View, click không bao giờ
     * nổ. Trên xe, đó là "bấm nút chỉnh khung mà thỉnh thoảng không ăn".
     */
    private fun refreshGeometryPanel(activeTarget: String?) {
        if (geometryPanelRendered && renderedGeometryTarget == activeTarget) return
        renderedGeometryTarget = activeTarget
        geometryPanelRendered = true
        geometryContainer.removeAllViews()
        geometryContainer.addView(
            CastAppRows.build(activity, activeTarget?.let { it to autoStart.labelOf(it) }, rowActions),
        )
    }

    // --- Cast/Stop ---

    private fun executeCast(pkg: String) = runOperation(
        "Đang xác minh target và chuẩn bị Cluster Cast…",
        block = { facade.runManualIntent(pkg, preferredDensityDpi = catalog.clusterDensityDpi(pkg), clusterStyle = catalog.clusterStyle(pkg)).statusMessage() },
        // Một ý định bị xếp hàng (transaction cũ còn chạy) phải được rút ngay sau khi lượt này xong,
        // nếu không nó nằm lại trong store bền và chặn tự-chiếu-khi-khởi-động mãi mãi — xem
        // [drainPendingTarget].
        after = { drainPendingTarget() },
    )

    private fun executeStop() {
        operationStatus.clearAll(); statusTimers.cancelStatusExpiry()
        status.text = "Đã nhận yêu cầu dừng · đang fence transport…"
        val requestedAt = Instant.now(); val requestSequence = ++stopSequence
        stopRequestedAt = requestedAt
        statusTimers.scheduleStopAckRefresh(requestedAt, facade.stopAcknowledgementGraceMillis()) { stopRequestedAt == it }
        refresh()
        work.stop {
            val accepted = runCatching { facade.requestStop() }.getOrNull()
            if (accepted == null) {
                postUi {
                    if (requestSequence != stopSequence) return@postUi
                    if (stopRequestedAt == requestedAt) stopRequestedAt = null
                    statusTimers.cancelStopAckRefresh(); toast("Không thể lưu yêu cầu Stop")
                }
                return@stop
            }
            postUi {
                if (requestSequence != stopSequence) return@postUi
                if (stopRequestedAt == requestedAt) stopRequestedAt = null
                statusTimers.cancelStopAckRefresh(); toast("Đã ghi yêu cầu Stop"); refresh()
            }
            val message = if (accepted.transaction != null) "Đã ghi Stop · đang chờ hiệu ứng lệnh cũ hội tụ"
                else continueStopAfterAcknowledgement()
            postUi { if (requestSequence == stopSequence) toast(message); refresh() }
        }
    }

    /**
     * Delegates to the ONE real plan+execute+settle implementation shared with the bubble
     * ([FloatingBubbleService.continueStopAfterAcknowledgement]) — not a per-surface reimplementation
     * of the same four lines (see `CastFieldParityTest`/`CastRendererContractTest`).
     */
    private fun continueStopAfterAcknowledgement(): String =
        facade.continueStopAfterAcknowledgement { pkg -> catalog.evidence(pkg, facade.phoneSession(pkg)) }

    private fun applyGeometry(geometry: AcceptedGeometry) = runOperation("Đang áp geometry…", {
        when (val outcome = facade.applyGeometry(
            geometry,
            installed = { pkg -> runCatching { activity.packageManager.getPackageInfo(pkg, 0) }.isSuccess },
            hasLauncher = { pkg -> activity.packageManager.getLaunchIntentForPackage(pkg) != null },
        )) {
            CastFacade.GeometryOutcome.Applied -> "Đã áp và đọc lại được geometry"
            is CastFacade.GeometryOutcome.NotVerified -> "Geometry chưa xác minh: ${outcome.reason}"
            is CastFacade.GeometryOutcome.Rejected -> outcome.reason
            is CastFacade.GeometryOutcome.Blocked -> "Geometry bị chặn: ${outcome.reason}"
            is CastFacade.GeometryOutcome.RecoveryRequired -> "Geometry cần phục hồi: ${outcome.reason}"
        }
    })

    private fun retryConnect() {
        work.misc {
            val envelope = facade.envelope()
            val pkg = envelope?.pendingPackage ?: envelope?.stableSession?.activeTarget?.packageName
            postUi { if (pkg == null) toast("Chưa có app nào để thử kết nối lại") else executeCast(pkg) }
        }
    }

    private fun showPhoneDisconnectGuidance() {
        android.app.AlertDialog.Builder(activity)
            .setTitle("Ngắt kết nối điện thoại")
            .setMessage("Ngắt CarPlay/Android Auto trên điện thoại hoặc rút cáp. Quay lại đây sau khi phiên báo đã ngắt; ClusterNav không tự tắt phiên điện thoại.")
            .setPositiveButton("Đã hiểu", null)
            .show()
    }

    private fun confirmEligibleRecovery() {
        android.app.AlertDialog.Builder(activity)
            .setTitle("Phục hồi một lần?")
            .setMessage("Chỉ tiếp tục sau khi điện thoại đã ngắt. Thao tác có thể force-stop đúng tiến trình projection bị kẹt và không được lặp lại trong transaction này.")
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Xác nhận phục hồi") { _, _ -> executeEligibleRecovery() }
            .show()
    }

    private fun executeEligibleRecovery() = runOperation("Đang xác minh điều kiện phục hồi…", {
        val first = facade.observedState() ?: return@runOperation "Không đọc được mẫu WM/AM thứ nhất"
        Thread.sleep(250)
        val second = facade.observedState() ?: return@runOperation "Không đọc được mẫu WM/AM thứ hai"
        if (first != second) return@runOperation "Hai mẫu chưa ổn định; không phát lệnh phục hồi"
        val pkg = second.protectedResidue?.packageName ?: second.target?.packageName
            ?: return@runOperation "Không xác định được owner projection"
        val disconnected = facade.phoneSession(pkg)
        if (disconnected != false) return@runOperation "Chưa chứng minh phiên điện thoại đã ngắt"
        val proof = DisconnectedSinkRecoveryProof(
            pkg, first, second, phoneDisconnected = true,
            projectionComponent = true, consequenceConfirmed = true,
        )
        val plan = facade.planRecover(pkg, proof, catalog.evidence(pkg, disconnected))
        when (val outcome = facade.executeAndSettle(plan, pkg)) {
            is CastFacade.Outcome.Verified -> "Đã phục hồi và xác minh"
            is CastFacade.Outcome.NotVerified -> "Phục hồi chưa hội tụ; transaction được giữ để xử lý an toàn"
            is CastFacade.Outcome.RecoveryRequired -> "Cần phục hồi an toàn: ${outcome.reason}"
            is CastFacade.Outcome.Blocked -> "Phục hồi bị chặn: ${outcome.reason}"
        }
    })

    private fun showPhysicalInstruction() {
        android.app.AlertDialog.Builder(activity)
            .setTitle("Khôi phục thủ công")
            .setMessage("Dừng xe ở vị trí an toàn, dùng nút nguồn vật lý của màn hình để power-cycle hoàn toàn, sau đó mở lại Chẩn đoán. Không dùng adb reboot làm bằng chứng.")
            .setPositiveButton("Đã hiểu", null)
            .show()
    }

    private fun openProfileSetup() {
        val opened = runCatching { activity.startActivity(Intent("android.settings.USER_SETTINGS")) }
            .recoverCatching { activity.startActivity(Intent(Settings.ACTION_SETTINGS)) }.isSuccess
        if (!opened) toast("Thiết bị không cung cấp màn hình thiết lập hồ sơ")
    }

    private fun runOperation(initial: String, block: () -> String, after: () -> Unit = {}) {
        val token = operationStatus.begin(initial)
        val mutationRevision = work.beginMutation()
        statusTimers.cancelStatusExpiry()
        refresh()
        work.operation {
            val message = runCatching(block).getOrElse { "Lỗi: ${it.message}" }
            work.finishMutation(mutationRevision)
            if (!operationStatus.complete(token, message, Instant.now())) return@operation
            postUi {
                val snapshot = operationStatus.snapshot(token, Instant.now())
                    ?.takeIf { it.phase == CastOperationStatusPhase.COMPLETED } ?: return@postUi
                after()
                if (!operationStatus.isCurrent(token, Instant.now())) return@postUi
                refresh()
                // Hẹn giờ hết hạn cho dòng trạng thái đã xong. Bản transplant đầu 2026-07-29 đánh rơi
                // dòng này, làm `CastActivityStatusTimers.scheduleStatusExpiry` không còn call site nào
                // (đúng lớp lỗi CLAUDE.md §8 canh) — nhịp tick 1s che mất hậu quả chứ không chữa nó.
                snapshot.expiresAt?.let { statusTimers.scheduleStatusExpiry(token, it) }
            }
        }
    }

    private fun refresh() {
        val stopSnapshot = stopRequestedAt
        val mutationSnapshot = work.mutationSnapshot()
        refreshInFlight.set(true)
        work.refresh { revision ->
            val result = try {
                refreshReader.read(null, stopSnapshot)
            } finally {
                refreshInFlight.set(false)
            }
            val model = result.model
            // Ảnh chụp quan sát do CHÍNH lần đọc nền này lấy — main thread chỉ đọc lại, không quan sát.
            observedTarget = result.activeTarget
            observedClusterSize = result.clusterSize ?: DEFAULT_CLUSTER_SIZE
            observedProfileId = result.geometryProfileId ?: DEFAULT_PROFILE_ID
            postUi {
                if (!work.isCurrentRefresh(revision) || !work.isCurrentMutation(mutationSnapshot) ||
                    stopRequestedAt != stopSnapshot
                ) return@postUi
                status.text = result.visibleStatus
                val effectiveActions = model.activityActions(mutationSnapshot)
                fun enabled(action: CastAction) = action in effectiveActions
                stopButton.isEnabled = enabled(CastAction.STOP)
                diagnosticsButton.isEnabled = enabled(CastAction.OPEN_DIAGNOSTICS)
                retryButton.isEnabled = enabled(CastAction.RETRY_CONNECT)
                phoneDisconnectButton.isEnabled = enabled(CastAction.REQUEST_PHONE_DISCONNECT)
                recoverOnceButton.isEnabled = enabled(CastAction.TRY_ELIGIBLE_RECOVERY_ONCE)
                physicalInstructionButton.isEnabled = enabled(CastAction.SHOW_PHYSICAL_INSTRUCTION)
                profileSetupButton.isEnabled = enabled(CastAction.OPEN_PROFILE_SETUP)
                // Chấm trạng thái của thẻ Cast. Nó vốn được Home V1 tô theo `ClusterCast.casting`; khi
                // phần đọc V1 bị gỡ tối 2026-07-29 thì không còn ai tô, nên nó đứng im một màu mặc định
                // và NÓI SAI trạng thái. Ba màu lấy đúng vốn từ của chấm Navigation ngay cạnh nó, và
                // đều từ sự thật đo được: xanh = cụm đang có app, đỏ = xe cần một thao tác trực tiếp
                // (chỉ hai trạng thái legacy-read-only / journal-corrupt mới export phép này), vàng =
                // đang rảnh, y như chấm Navigation lúc chưa gửi.
                statusDot.tint(
                    when {
                        observedTarget != null -> R.color.ok_green
                        enabled(CastAction.SHOW_PHYSICAL_INSTRUCTION) -> R.color.err_red
                        else -> R.color.warn_amber
                    },
                )
                refreshGeometryPanel(observedTarget)
                if (stopSnapshot != null && model.operationAcknowledged) {
                    stopRequestedAt = null
                    statusTimers.cancelStopAckRefresh()
                }
            }
        }
    }

    private fun View.tint(color: Int) {
        backgroundTintList = android.content.res.ColorStateList.valueOf(activity.getColor(color))
    }

    private fun postUi(block: () -> Unit) =
        activity.runOnUiThread { if (!destroyed && !activity.isFinishing && !activity.isDestroyed) block() }
    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    private fun bootId(): String = Settings.Global.getInt(activity.contentResolver, Settings.Global.BOOT_COUNT, 0).toString()

    private companion object {
        /** Khung tham chiếu khi chưa đọc được cụm — đúng con số panel cũ dùng, không đoán lại. */
        val DEFAULT_CLUSTER_SIZE = 1920 to 720
        const val DEFAULT_PROFILE_ID = "android-user-0"
    }
}

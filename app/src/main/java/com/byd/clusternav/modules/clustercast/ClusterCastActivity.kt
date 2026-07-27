package com.byd.clusternav.modules.clustercast

import android.app.Activity
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.GridLayout
import android.widget.ScrollView
import android.widget.TextView
import com.byd.clusternav.R
import com.byd.clusternav.modules.clustercast.v2.CastAction
import com.byd.clusternav.modules.clustercast.v2.CastAppCatalog
import com.byd.clusternav.modules.clustercast.v2.CastAndroidLifecycle
import com.byd.clusternav.modules.clustercast.v2.CastAndroidRuntime
import com.byd.clusternav.modules.clustercast.v2.CastManualIntentResult
import com.byd.clusternav.modules.clustercast.v2.CastManualTargetReader
import com.byd.clusternav.modules.clustercast.v2.EngineVersion
import com.byd.clusternav.modules.clustercast.v2.CastIntent
import com.byd.clusternav.modules.clustercast.v2.CastIntentKind
import com.byd.clusternav.modules.clustercast.v2.ExecutionResult
/** Cluster Cast V2 controller. All mutations route through the durable coordinator. */
class ClusterCastActivity : Activity() {
    private lateinit var runtime: CastAndroidRuntime.Runtime
    private lateinit var facade: CastFacade
    private lateinit var catalog: CastAppCatalog
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var selected: TextView
    private lateinit var apps: GridLayout
    private lateinit var castButton: Button
    private lateinit var stopButton: Button
    private lateinit var adjustButton: Button
    private lateinit var appManagerButton: Button
    private lateinit var diagnosticsButton: Button
    private lateinit var retryButton: Button
    private lateinit var phoneDisconnectButton: Button
    private lateinit var recoverOnceButton: Button
    private lateinit var physicalInstructionButton: Button
    private lateinit var profileSetupButton: Button
    private lateinit var refreshReader: CastActivityRefreshReader
    private var selectedPackage: String? = null
    private val operationStatus = CastOperationStatus()
    private val work = CastActivityWork()
    private lateinit var appBinding: CastAppManagerBinding
    @Volatile private var castActionExported = false
    @Volatile private var selectionExplicit = false
    private val statusTimers = CastActivityStatusTimers(operationStatus, ::refresh)
    @Volatile private var destroyed = false
    private var stopSequence = 0L
    @Volatile private var stopRequestedAt: java.time.Instant? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        catalog = CastAppCatalog(applicationContext)
        runtime = CastAndroidRuntime.create(applicationContext); facade = CastFacade.wrapping(runtime)
        refreshReader = CastActivityRefreshReader(runtime, catalog, operationStatus)
        appBinding = CastAppManagerBinding(applicationContext, catalog, runtime.automation, { work.misc(it) }, ::executeCast)
        setContentView(R.layout.activity_cluster_cast)
        scroll = findViewById(R.id.cast_scroll)
        status = findViewById(R.id.cast_status)
        selected = findViewById(R.id.cast_selected)
        apps = findViewById<GridLayout>(R.id.cast_apps).apply { columnCount = resources.getInteger(R.integer.cast_app_columns) }
        castButton = findViewById<Button>(R.id.cast_start).apply { setOnClickListener { selectedPackage?.let(::executeCast) ?: show("Chọn app trước") } }
        stopButton = findViewById<Button>(R.id.cast_stop).apply { setOnClickListener { executeStop() } }
        adjustButton = findViewById<Button>(R.id.cast_adjust).apply { setOnClickListener { openAdjustment() } }
        appManagerButton = findViewById<Button>(R.id.cast_app_manager).apply { setOnClickListener { openAppManager() } }
        diagnosticsButton = findViewById<Button>(R.id.cast_diagnostics).apply { setOnClickListener { startActivity(android.content.Intent(this@ClusterCastActivity, DiagActivity::class.java)) } }
        retryButton = findViewById<Button>(R.id.cast_retry).apply { setOnClickListener { retryConnect() } }
        CastBubbleControl.bind(this, findViewById(R.id.cast_bubble)) { show(it) }
        CastScreenChrome.bind(this)
        phoneDisconnectButton = findViewById<Button>(R.id.cast_phone_disconnect).apply { setOnClickListener { showPhoneDisconnectGuidance() } }
        recoverOnceButton = findViewById<Button>(R.id.cast_recover_once).apply { setOnClickListener { confirmEligibleRecovery() } }
        physicalInstructionButton = findViewById<Button>(R.id.cast_physical_instruction).apply { setOnClickListener { showPhysicalInstruction() } }
        profileSetupButton = findViewById<Button>(R.id.cast_profile_setup).apply { setOnClickListener { openProfileSetup() } }
        selectedPackage = savedInstanceState?.getString(STATE_SELECTED_PACKAGE)
        selectionExplicit = selectedPackage != null
        selectedPackage?.let { selected.text = "Đã chọn: $it" }
        val savedScrollY = savedInstanceState?.getInt(STATE_SCROLL_Y, 0) ?: 0
        val savedFocusId = savedInstanceState?.getInt(STATE_FOCUS_ID, android.view.View.NO_ID)
            ?: android.view.View.NO_ID
        scroll.post {
            if (destroyed || isFinishing || isDestroyed) return@post
            scroll.scrollTo(0, savedScrollY)
            if (savedFocusId != android.view.View.NO_ID) findViewById<android.view.View>(savedFocusId)?.requestFocus()
        }
        loadApps()
        listOf(castButton, stopButton, adjustButton, appManagerButton, diagnosticsButton, retryButton,
            phoneDisconnectButton, recoverOnceButton, physicalInstructionButton, profileSetupButton)
            .forEach { it.isEnabled = false }
        work.misc {
            runCatching {
                facade.initialize(bootId())
                facade.applyVehicleTestRollout()
                CastAndroidLifecycle.rehydrate(applicationContext)
            }
            postUi {
                handleIntent(intent)
                refresh()
                reconcileSelectionAndDrain()
            }
        }
    }
    override fun onNewIntent(intent: android.content.Intent?) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }
    private fun handleIntent(value: android.content.Intent?) {
        if (value?.action == ACTION_STOP) {
            value.action = null
            executeStop()
        }
    }
    override fun onResume() { super.onResume(); CastBubbleControl.rebind(this, findViewById(R.id.cast_bubble)) { show(it) }; refresh() }
    override fun onDestroy() { destroyed = true; statusTimers.close(); operationStatus.clearAll(); work.close(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SELECTED_PACKAGE, selectedPackage)
        outState.putInt(STATE_SCROLL_Y, scroll.scrollY)
        outState.putInt(STATE_FOCUS_ID, currentFocus?.id ?: android.view.View.NO_ID)
        super.onSaveInstanceState(outState)
    }
    private fun loadApps() {
        work.misc {
            val values = catalog.installed(runtime.automation.config().defaultPackage)
            val preselected = appBinding.preselect(selectedPackage, values)
            postUi {
                apps.removeAllViews()
                values.forEach { app ->
                    apps.addView(appButton(app.label) {}.also { CastAppTiles.bind(it, app) { selectApp(app.label, app.packageName) } })
                }
                if (values.isEmpty()) apps.addView(text("Không đọc được danh sách app", 13f, 0xFF555555.toInt()))
                CastAppTiles.markSelected(apps, selectedPackage)
                if (selectedPackage == null && preselected != null) {
                    selectedPackage = preselected
                    selected.text = "Mặc định: " + (values.firstOrNull { it.packageName == preselected }?.label ?: preselected)
                }
                refresh()
            }
        }
    }
    private fun selectApp(label: String, packageName: String) {
        selectionExplicit = true
        selectedPackage = packageName
        selected.text = "Đã chọn: $label"
        CastAppTiles.markSelected(apps, packageName)
        refresh()
        work.selection { revision ->
            val queued = facade.queueLatestTarget(packageName)
            postUi {
                if (!work.isCurrentSelection(revision) || selectedPackage != packageName) return@postUi
                if (queued) toast("Đã cập nhật lựa chọn mới nhất: $label"); refresh()
            }
        }
    }
    private fun openAppManager() = work.misc { val model = appBinding.model(castActionExported)
        postUi { CastAppManagerDialog.show(this, model, appBinding) { loadApps() } } }
    private fun executeCast(pkg: String) { executeCast(pkg, destructive = false) }
    private fun executeCast(pkg: String, destructive: Boolean): Unit = runOperation(
        if (destructive) "Đang tắt app rồi chiếu lại…" else "Đang xác minh target và chuẩn bị Cluster Cast…",
        block = {
            val r = facade.runManualIntent(pkg, manualTargetReader(), allowDestructive = destructive, preferredDensityDpi = catalog.clusterDensityDpi(pkg), clusterStyle = catalog.clusterStyle(pkg))
            if (!destructive && CastRetryPrompt.escalatable(r, protectedTarget(pkg)) && facade.observedState()?.target?.packageName != pkg) {
                postUi { if (!destroyed) CastRetryPrompt.show(this, pkg) { executeCast(pkg, true) } }
            }
            r.statusMessage()
        }, after = { drainPendingTarget() },
    )
    private fun protectedTarget(pkg: String): Boolean =
        catalog.evidence(pkg, facade.phoneSession(pkg)).projectionComponent == true
    private fun reconcileSelectionAndDrain() {
        if (!selectionExplicit) return drainPendingTarget()
        val packageName = selectedPackage ?: return drainPendingTarget()
        work.selection { revision -> val queued = facade.queueLatestTarget(packageName)
            postUi { if (queued && work.isCurrentSelection(revision) && selectedPackage == packageName) refresh(); drainPendingTarget() }
        } }
    private fun drainPendingTarget() {
        val mutationRevision = work.beginMutation()
        refresh()
        work.operation {
            val pending = facade.envelope()?.pendingPackage
            if (pending == null) {
                work.finishMutation(mutationRevision)
                postUi { refresh() }
                return@operation
            }
            val token = operationStatus.begin("Đang tiếp tục lựa chọn mới nhất: $pending…")
            postUi { statusTimers.cancelStatusExpiry(); refresh() }
            val selectionRevision = work.currentSelectionRevision()
            val result = runCatching { facade.resumePendingIntent(manualTargetReader()) }
                .getOrElse {
                    work.finishMutation(mutationRevision)
                    completeOperation(token, "Lỗi: ${it.message}")
                    return@operation
                }
            work.finishMutation(mutationRevision)
            if (result == null) {
                if (operationStatus.clear(token)) postUi { refresh() }
                return@operation
            }
            completeOperation(token, result.statusMessage()) {
                val target = (result as? CastManualIntentResult.Succeeded)?.stableSession?.activeTarget?.packageName
                if (work.isCurrentSelection(selectionRevision) && target == pending &&
                    (selectedPackage == null || selectedPackage == pending)
                ) {
                    selectedPackage = target
                    selected.text = "Đã chọn: $target"
                }
            }
        }
    }
    private fun manualTargetReader() = CastManualTargetReader { catalog.snapshot(it, facade.phoneSession(it)) }
    private fun openAdjustment() {
        work.misc {
            val observed = facade.observedState()
            val result = observed?.let(runtime.adjustment::open)
                ?: com.byd.clusternav.modules.clustercast.v2.AdjustmentResult.Rejected("Không đọc được target/geometry")
            postUi {
                when (result) {
                    is com.byd.clusternav.modules.clustercast.v2.AdjustmentResult.Rejected -> show("Không thể điều chỉnh: ${result.reason}")
                    is com.byd.clusternav.modules.clustercast.v2.AdjustmentResult.Ready -> showAdjustment(result.draft)
                }
            }
        }
    }
    private fun showAdjustment(draft: com.byd.clusternav.modules.clustercast.v2.AdjustmentDraft) {
        CastAdjustmentDialog.show(
            this,
            draft,
            onApply = { applyGeometry(it) },
            onUndo = { editAdjustment(runtime.adjustment::undoLast) },
            onRestore = { editAdjustment(runtime.adjustment::restoreEntry, cancelAfterVerified = true) },
            onReset = { editAdjustment(mutation = {
                val envelope = facade.envelope()
                runtime.adjustment.resetDefault(envelope?.stableSession?.baseline?.geometry ?: draft.entrySnapshot)
            }) },
            onDone = { finishAdjustment() },
        )
    }
    private fun editAdjustment(mutation: () -> com.byd.clusternav.modules.clustercast.v2.AdjustmentResult,
        cancelAfterVerified: Boolean = false) = work.misc {
        when (val result = mutation()) {
            is com.byd.clusternav.modules.clustercast.v2.AdjustmentResult.Ready ->
                postUi { applyGeometry(result.draft.localDraft, cancelAfterVerified) }
            is com.byd.clusternav.modules.clustercast.v2.AdjustmentResult.Rejected -> postUi { show(result.reason) }
        }
    }
    private fun applyGeometry(geometry: com.byd.clusternav.modules.clustercast.v2.AcceptedGeometry,
        cancelAfterVerified: Boolean = false) = runOperation("Đang áp geometry…", {
        val observed = facade.observedState()
            ?: return@runOperation "Không đọc được target hiện tại"
        val edited = runtime.adjustment.edit(geometry)
        if (edited is com.byd.clusternav.modules.clustercast.v2.AdjustmentResult.Rejected) return@runOperation edited.reason
        val applying = runtime.adjustment.beginApply(observed)
        if (applying is com.byd.clusternav.modules.clustercast.v2.AdjustmentResult.Rejected) return@runOperation applying.reason
        val pkg = observed.target?.packageName ?: return@runOperation "Target không xác định"
        val plan = facade.planGeometry(
            pkg, geometry, observed.target, catalog.evidence(pkg, facade.phoneSession(pkg)),
            installed = runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess,
            hasLauncher = packageManager.getLaunchIntentForPackage(pkg) != null,
        )
        when (val execution = facade.execute(plan, pkg)) {
            is ExecutionResult.AwaitingVerification -> {
                val bound = runtime.adjustment.bindExecution(execution.operationId, execution.epoch)
                if (bound is com.byd.clusternav.modules.clustercast.v2.AdjustmentResult.Rejected) {
                    return@runOperation "Không bind được geometry transaction: ${bound.reason}"
                }
                Thread.sleep(250)
                if (!facade.observeAndComplete(execution.operationId)) {
                    facade.markAdjustmentApplyFailed("Geometry chưa hội tụ")
                    "Geometry chưa xác minh"
                } else {
                    val first = facade.observedState()
                    val second = facade.observedState()
                    if (first == null || second == null || runtime.adjustment.recordVerifiedApply(first, second) is com.byd.clusternav.modules.clustercast.v2.AdjustmentResult.Rejected) {
                        facade.markAdjustmentApplyFailed("Read-back geometry thất bại")
                        "Geometry chưa xác minh"
                    } else {
                        if (cancelAfterVerified) runtime.adjustment.cancelAfterRestore(first, second)
                        "Đã áp và xác minh geometry"
                    }
                }
            }
            is ExecutionResult.RecoveryRequired -> {
                facade.markAdjustmentApplyFailed(execution.reason)
                "Geometry cần phục hồi: ${execution.reason}"
            }
            is ExecutionResult.Blocked -> {
                facade.markAdjustmentApplyFailed(execution.reason)
                "Geometry bị chặn: ${execution.reason}"
            }
        }
    }, after = { if (!cancelAfterVerified) openAdjustment() })
    private fun finishAdjustment() = runOperation("Đang chốt geometry…", {
        val first = facade.observedState()
            ?: return@runOperation "Không đọc được geometry"
        val second = facade.observedState()
            ?: return@runOperation "Không đọc được geometry lần hai"
        when (val result = runtime.adjustment.done(first, second)) {
            is com.byd.clusternav.modules.clustercast.v2.AdjustmentResult.Ready -> "Đã lưu geometry"
            is com.byd.clusternav.modules.clustercast.v2.AdjustmentResult.Rejected -> "Chưa thể lưu: ${result.reason}"
        }
    })
    private fun retryConnect() {
        selectedPackage?.let { executeCast(it); return }
        work.misc {
            val envelope = facade.envelope()
            val pkg = envelope?.pendingPackage ?: envelope?.stableSession?.activeTarget?.packageName
            postUi { if (pkg == null) show("Chọn ứng dụng để thử kết nối lại") else executeCast(pkg) }
        }
    }
    private fun showPhoneDisconnectGuidance() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Ngắt kết nối điện thoại")
            .setMessage("Ngắt CarPlay/Android Auto trên điện thoại hoặc rút cáp. Quay lại đây sau khi phiên báo đã ngắt; ClusterNav không tự tắt phiên điện thoại.")
            .setPositiveButton("Đã hiểu", null)
            .show()
    }
    private fun confirmEligibleRecovery() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Phục hồi một lần?")
            .setMessage("Chỉ tiếp tục sau khi điện thoại đã ngắt. Thao tác có thể force-stop đúng tiến trình projection bị kẹt và không được lặp lại trong transaction này.")
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Xác nhận phục hồi") { _, _ -> executeEligibleRecovery() }
            .show()
    }
    private fun executeEligibleRecovery() = runOperation("Đang xác minh điều kiện phục hồi…", {
        val first = facade.observedState()
            ?: return@runOperation "Không đọc được mẫu WM/AM thứ nhất"
        Thread.sleep(250)
        val second = facade.observedState()
            ?: return@runOperation "Không đọc được mẫu WM/AM thứ hai"
        if (first != second) return@runOperation "Hai mẫu chưa ổn định; không phát lệnh phục hồi"
        val pkg = second.protectedResidue?.packageName ?: second.target?.packageName
            ?: return@runOperation "Không xác định được owner projection"
        val disconnected = facade.phoneSession(pkg)
        if (disconnected != false) return@runOperation "Chưa chứng minh phiên điện thoại đã ngắt"
        val proof = com.byd.clusternav.modules.clustercast.v2.DisconnectedSinkRecoveryProof(
            pkg, first, second, phoneDisconnected = true,
            projectionComponent = true, consequenceConfirmed = true,
        )
        val plan = facade.planRecover(pkg, proof, catalog.evidence(pkg, disconnected))
        when (val result = facade.execute(plan, pkg)) {
            is ExecutionResult.AwaitingVerification -> {
                Thread.sleep(250)
                if (facade.observeAndComplete(result.operationId)) "Đã phục hồi và xác minh"
                else "Phục hồi chưa hội tụ; transaction được giữ để xử lý an toàn"
            }
            is ExecutionResult.RecoveryRequired -> "Cần phục hồi an toàn: ${result.reason}"
            is ExecutionResult.Blocked -> "Phục hồi bị chặn: ${result.reason}"
        }
    })
    private fun showPhysicalInstruction() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Khôi phục thủ công")
            .setMessage("Dừng xe ở vị trí an toàn, dùng nút nguồn vật lý của màn hình để power-cycle hoàn toàn, sau đó mở lại Chẩn đoán. Không dùng adb reboot làm bằng chứng.")
            .setPositiveButton("Đã hiểu", null)
            .show()
    }
    private fun openProfileSetup() {
        val intent = android.content.Intent("android.settings.USER_SETTINGS")
        val opened = runCatching { startActivity(intent) }.recoverCatching {
            startActivity(android.content.Intent(Settings.ACTION_SETTINGS))
        }.isSuccess
        if (!opened) show("Thiết bị không cung cấp màn hình thiết lập hồ sơ")
    }
    private fun executeStop() {
        operationStatus.clearAll(); statusTimers.cancelStatusExpiry()
        status.text = "Đã nhận yêu cầu dừng · đang fence transport…"
        val requestedAt = java.time.Instant.now(); val requestSequence = ++stopSequence
        stopRequestedAt = requestedAt; statusTimers.scheduleStopAckRefresh(requestedAt, facade.stopAcknowledgementGraceMillis()) { stopRequestedAt == it }
        refresh()
        work.stop {
            val accepted = runCatching { facade.requestStop() }.getOrNull()
            if (accepted == null) {
                postUi {
                    if (requestSequence != stopSequence) return@postUi
                    if (stopRequestedAt == requestedAt) stopRequestedAt = null
                    statusTimers.cancelStopAckRefresh(); show("Không thể lưu yêu cầu Stop")
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
    private fun continueStopAfterAcknowledgement(): String {
        val envelope = facade.envelope()
        val pkg = envelope?.stableSession?.activeTarget?.packageName
        if (envelope == null) return "Durable store unavailable"
        if (!facade.v2OwnsActions(envelope)) return "Stop owner is not V2"
        val plan = facade.planStop(pkg, pkg?.let { catalog.evidence(it, facade.phoneSession(it)) })
        return when (val result = facade.execute(plan, pkg)) {
            is ExecutionResult.AwaitingVerification -> {
                Thread.sleep(250)
                if (facade.observeAndComplete(result.operationId)) "Đã trả đồng hồ" else "Stop chưa xác minh"
            }
            is ExecutionResult.RecoveryRequired -> "Stop cần phục hồi: ${result.reason}"
            is ExecutionResult.Blocked -> "Stop bị chặn: ${result.reason}"
        }
    }
    private fun runOperation(initial: String, block: () -> String, after: () -> Unit = {}) {
        val token = operationStatus.begin(initial)
        val mutationRevision = work.beginMutation()
        statusTimers.cancelStatusExpiry()
        refresh()
        work.operation {
            val message = runCatching(block).getOrElse { "Lỗi: ${it.message}" }
            work.finishMutation(mutationRevision)
            completeOperation(token, message, after)
        }
    }
    private fun completeOperation(token: CastOperationToken, message: String, after: () -> Unit = {}) {
        if (!operationStatus.complete(token, message, java.time.Instant.now())) return
        postUi {
            val snapshot = operationStatus.snapshot(token, java.time.Instant.now())
                ?.takeIf { it.phase == CastOperationStatusPhase.COMPLETED }
                ?: return@postUi
            after()
            if (!operationStatus.isCurrent(token, java.time.Instant.now())) return@postUi
            refresh()
            snapshot.expiresAt?.let { statusTimers.scheduleStatusExpiry(token, it) }
        }
    }
    private fun refresh() {
        val selectedSnapshot = selectedPackage
        val stopSnapshot = stopRequestedAt
        val mutationSnapshot = work.mutationSnapshot()
        work.refresh { revision ->
            val result = refreshReader.read(selectedSnapshot, stopSnapshot)
            val model = result.model
            postUi {
                if (!work.isCurrentRefresh(revision) || !work.isCurrentMutation(mutationSnapshot) ||
                    selectedPackage != selectedSnapshot || stopRequestedAt != stopSnapshot
                ) return@postUi
                status.text = result.visibleStatus
                val effectiveActions = model.activityActions(mutationSnapshot)
                fun enabled(action: CastAction) = action in effectiveActions
                castActionExported = enabled(CastAction.CAST) || enabled(CastAction.SWITCH)
                castButton.isEnabled = castActionExported && result.selectedEligible
                stopButton.isEnabled = enabled(CastAction.STOP)
                adjustButton.isEnabled = enabled(CastAction.ADJUST)
                diagnosticsButton.isEnabled = enabled(CastAction.OPEN_DIAGNOSTICS)
                appManagerButton.isEnabled = enabled(CastAction.OPEN_APP_MANAGER)
                retryButton.isEnabled = enabled(CastAction.RETRY_CONNECT)
                phoneDisconnectButton.isEnabled = enabled(CastAction.REQUEST_PHONE_DISCONNECT)
                recoverOnceButton.isEnabled = enabled(CastAction.TRY_ELIGIBLE_RECOVERY_ONCE)
                physicalInstructionButton.isEnabled = enabled(CastAction.SHOW_PHYSICAL_INSTRUCTION)
                profileSetupButton.isEnabled = enabled(CastAction.OPEN_PROFILE_SETUP)
                val appManagementEnabled = enabled(CastAction.OPEN_APP_MANAGER) || enabled(CastAction.CHOOSE_ANOTHER_APP)
                for (index in 0 until apps.childCount) apps.getChildAt(index).isEnabled = appManagementEnabled
                if (stopSnapshot != null && model.operationAcknowledged) {
                    stopRequestedAt = null
                    statusTimers.cancelStopAckRefresh()
                }
            }
        }
    }
    private fun postUi(block: () -> Unit) = runOnUiThread { if (!destroyed && !isFinishing && !isDestroyed) block() }
    private fun toast(message: String) = android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show(); private fun show(message: String) { toast(message); refresh() }
    private fun bootId(): String = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, 0).toString()
    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setPadding(0, dp(6), 0, dp(8))
    }
    private fun appButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; isEnabled = false; minimumHeight = dp(56)
        contentDescription = label
        setOnClickListener { action() }
        layoutParams = GridLayout.LayoutParams().apply {
            width = 0; height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(dp(4), dp(4), dp(4), dp(4))
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    companion object {
        const val ACTION_STOP = "com.byd.clusternav.action.CAST_V2_STOP"
        private const val STATE_SELECTED_PACKAGE = "cast-selected-package"
        private const val STATE_SCROLL_Y = "cast-scroll-y"
        private const val STATE_FOCUS_ID = "cast-focus-id"
    }
}

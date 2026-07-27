package com.byd.clusternav.modules.clustercast

import com.byd.clusternav.modules.clustercast.v2.AcceptedGeometry
import com.byd.clusternav.modules.clustercast.v2.AdjustmentDraft
import android.app.Activity
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.GridLayout
import android.widget.ScrollView
import android.widget.TextView
import com.byd.clusternav.R
import com.byd.clusternav.modules.clustercast.v2.CastAction
import com.byd.clusternav.cast.platform.CastAppCatalog
import com.byd.clusternav.cast.platform.CastAndroidLifecycle
import com.byd.clusternav.cast.platform.CastAndroidRuntime
import com.byd.clusternav.modules.clustercast.v2.CastManualIntentResult
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
        runtime = CastAndroidRuntime.create(applicationContext); facade = CastFacade.wrapping(runtime, catalog)
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
                    apps.addView(appButton(app.label) {}.also {
                        CastAppTiles.bind(it, app) { toggleApp(app.label, app.packageName, app.favorite) }
                    })
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
    /**
     * Chạm một ô app = BẬT/TẮT app đó trong menu nút nổi, đúng hành vi v0.3x mà chủ dự án dùng thật:
     * tick app nào thì app đó vào nút nổi, có dấu ✓ ngay trên ô.
     *
     * V2 trước đây tách làm hai chỗ — chạm ô chỉ chọn target để chiếu, còn danh sách nút nổi nằm trong một
     * hộp thoại riêng. Hai chỗ cho một ý định là lý do màn hình có cảm giác nhiều nút.
     *
     * Bật thì đồng thời chọn làm target, vì đó gần như luôn là ý của người bấm; tắt thì chỉ rút khỏi nút nổi
     * và KHÔNG đụng gì tới cụm.
     */
    private fun toggleApp(label: String, packageName: String, wasFavorite: Boolean) {
        appBinding.setFavorite(packageName, !wasFavorite)
        if (wasFavorite) {
            loadApps()
            return
        }
        selectApp(label, packageName)
        loadApps()
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
            val r = facade.runManualIntent(pkg, allowDestructive = destructive, preferredDensityDpi = catalog.clusterDensityDpi(pkg), clusterStyle = catalog.clusterStyle(pkg))
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
            val result = runCatching { facade.resumePendingIntent() }
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
    private fun openAdjustment() {
        work.misc {
            val result = facade.openAdjustment()
            postUi {
                when (result) {
                    is CastFacade.DraftOutcome.Rejected -> show("Không thể điều chỉnh: ${result.reason}")
                    is CastFacade.DraftOutcome.Ready -> showAdjustment(result.draft)
                }
            }
        }
    }
    private fun showAdjustment(draft: AdjustmentDraft) {
        CastAdjustmentDialog.show(
            this,
            draft,
            onApply = { applyGeometry(it) },
            onUndo = { editAdjustment(facade::undoAdjustment) },
            onRestore = { editAdjustment(facade::restoreAdjustmentEntry, cancelAfterVerified = true) },
            onReset = { editAdjustment(mutation = { facade.resetAdjustment(draft.entrySnapshot) }) },
            onDone = { finishAdjustment() },
        )
    }
    private fun editAdjustment(mutation: () -> CastFacade.DraftOutcome,
        cancelAfterVerified: Boolean = false) = work.misc {
        when (val result = mutation()) {
            is CastFacade.DraftOutcome.Ready ->
                postUi { applyGeometry(result.draft.localDraft, cancelAfterVerified) }
            is CastFacade.DraftOutcome.Rejected -> postUi { show(result.reason) }
        }
    }
    private fun applyGeometry(geometry: AcceptedGeometry, cancelAfterVerified: Boolean = false) =
        runOperation("Đang áp geometry…", {
            when (val outcome = facade.applyGeometry(
                geometry,
                installed = { pkg -> runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess },
                hasLauncher = { pkg -> packageManager.getLaunchIntentForPackage(pkg) != null },
                cancelAfterVerified = cancelAfterVerified,
            )) {
                CastFacade.GeometryOutcome.Applied -> "Đã áp và đọc lại được geometry"
                is CastFacade.GeometryOutcome.NotVerified -> "Geometry chưa xác minh: ${outcome.reason}"
                is CastFacade.GeometryOutcome.Rejected -> outcome.reason
                is CastFacade.GeometryOutcome.Blocked -> "Geometry bị chặn: ${outcome.reason}"
                is CastFacade.GeometryOutcome.RecoveryRequired -> "Geometry cần phục hồi: ${outcome.reason}"
            }
        }, after = { if (!cancelAfterVerified) openAdjustment() })

    private fun finishAdjustment() = runOperation("Đang chốt geometry…", {
        facade.finishAdjustment()?.let { "Chưa thể lưu: $it" } ?: "Đã lưu geometry"
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
        when (val outcome = facade.executeAndSettle(plan, pkg)) {
            is CastFacade.Outcome.Verified -> "Đã phục hồi và xác minh"
            is CastFacade.Outcome.NotVerified -> "Phục hồi chưa hội tụ; transaction được giữ để xử lý an toàn"
            is CastFacade.Outcome.RecoveryRequired -> "Cần phục hồi an toàn: ${outcome.reason}"
            is CastFacade.Outcome.Blocked -> "Phục hồi bị chặn: ${outcome.reason}"
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
        return when (val outcome = facade.executeAndSettle(plan, pkg)) {
            is CastFacade.Outcome.Verified -> "Đã trả đồng hồ"
            is CastFacade.Outcome.NotVerified -> "Stop chưa xác minh"
            is CastFacade.Outcome.RecoveryRequired -> "Stop cần phục hồi: ${outcome.reason}"
            is CastFacade.Outcome.Blocked -> "Stop bị chặn: ${outcome.reason}"
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
                // Chọn app là tuỳ chọn cục bộ, không phát lệnh ra xe → không khoá theo trạng thái cụm.
                // Trước 27/7 chỗ này khoá theo OPEN_APP_MANAGER || CHOOSE_ANOTHER_APP nên ở trạng thái
                // "cần xử lý thủ công" người dùng không chọn nổi app để chuẩn bị.
                val appManagementEnabled = enabled(CastAction.SELECT_TARGET_APP)
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

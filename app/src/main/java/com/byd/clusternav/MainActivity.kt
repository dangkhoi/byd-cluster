package com.byd.clusternav

import com.byd.clusternav.modules.clustercast.MainActivityCastController
import com.byd.clusternav.vietmapwidget.VietMapWidgetDiagActivity
import com.byd.clusternav.navigation.NavigationOutputFailureReason
import com.byd.clusternav.navigation.NavigationSourceReason
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.byd.clusternav.navigation.NavigationFreshness
import com.byd.clusternav.navigation.NavigationOutputStatus
import com.byd.clusternav.navigation.NavigationOutputTarget
import com.byd.clusternav.navigation.NavigationPermission
import com.byd.clusternav.navigation.SpeedSignOutput

/**
 * Home — MÀN HÌNH DUY NHẤT của app (docs/specs/cast-simplified-active-app-toggle.html): trái là
 * Navigation + HUD (không đổi), phải là toàn bộ Cluster Cast (trước đây là màn riêng
 * `ClusterCastActivity`, đã xoá). Renderer/dispatcher — nó không tự lập kế hoạch gì cho Cast, mọi
 * mutation đi qua [MainActivityCastController].
 */
class MainActivity : Activity() {
    private lateinit var navEnabled: Switch
    private lateinit var navDot: View
    private lateinit var navStatus: TextView
    private lateinit var laneStatus: TextView
    private lateinit var hudStatus: TextView
    private val cast = MainActivityCastController(this)
    private val navClusterStatus = com.byd.clusternav.modules.clustercast.NavClusterOp39Status(this)
    private val speedSign by lazy { NavigationSpeedSignOwner.get(applicationContext) }

    private val ui = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() { refresh(); cast.tick(); ui.postDelayed(this, 1_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // CLAUDE.md §9: mỗi bản đã báo cho user phải tự hiện số hiệu — không ai phải đoán xe đang chạy bản nào.
        val versionName = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        findViewById<TextView>(R.id.txt_app_title).text = "ClusterNav" + (versionName?.let { " · v$it" } ?: "")

        navEnabled = findViewById(R.id.switch_enabled)
        navDot = findViewById(R.id.dot_status)
        navStatus = findViewById(R.id.txt_status)
        laneStatus = findViewById(R.id.txt_lane_status)
        hudStatus = findViewById(R.id.txt_hud_status)
        // Cluster-lane output follows the master Navigation+HUD switch — the redundant cb_lane
        // checkbox is removed (owner 2026-08-11). Force lane ON once so it always tracks the master
        // (migrates anyone who had unchecked the old lane box).
        Prefs.setLane(this, true)
        cast.onCreate()
        speedSign.syncFromPrefs()

        navEnabled.isChecked = Prefs.enabled(this)
        navEnabled.setOnCheckedChangeListener { _, enabled ->
            Prefs.setEnabled(this, enabled)
            speedSign.onMasterEnabled(enabled)
            if (enabled) {
                // Lane always on when Navigation+HUD is on (no separate lane toggle anymore).
                Prefs.setLane(this, true)
                NavRepository.setOutputEnabled(this, NavigationOutputTarget.CLUSTER_LANE, true)
                speedSign.onOutputEnabled(SpeedSignOutput.CLUSTER, true)
            } else {
                NavRepository.stop(this)
            }
            refresh()
        }
        // The master listener above only fires on CHANGE, so apply the current master state to the
        // cluster-lane output at startup too. The notification listener runs in THIS process and, on
        // bind, calls NavRepository.setPermission(GRANTED) → connect(), which may create the
        // coordinator before this Activity opens. A migrated user whose old cb_lane was unchecked has
        // lane=false persisted; the forced Prefs.setLane(true) above fixes the pref, but a coordinator
        // already built from the stale pref keeps CLUSTER_LANE OFF (connect() reads Prefs.lane only at
        // creation) and GMaps/VietMap nav would never reach the cluster. Re-assert it here (idempotent).
        if (Prefs.enabled(this)) {
            NavRepository.setOutputEnabled(this, NavigationOutputTarget.CLUSTER_LANE, true)
            speedSign.onOutputEnabled(SpeedSignOutput.CLUSTER, true)
        }
        // #6 (R1 · docs/specs/cast-nav-ux-release-v104.html): the independent nav→HUD output is
        // hidden from the UI (cb_hud/txt_hud_status = gone) and force-disabled here exactly once.
        // There is no user-reachable path to re-enable it. Navigation still flows to the cluster
        // lane (unchanged); NavigationOutputTarget.HUD stays in the enum for the isolation contract —
        // it is only kept OFF, not removed.
        Prefs.setHud(this, false)
        NavRepository.setOutputEnabled(this, NavigationOutputTarget.HUD, false)
        speedSign.onOutputEnabled(SpeedSignOutput.HUD, false)

        // ★ 2026-08-12 (owner "1B"): BẬT lại "tự bù theo tốc độ" cho mượt. MỘT cơ chế 2 nửa:
        //   (1) nội suy trừ dần cự ly theo TỐC ĐỘ XE thật giữa 2 notification (TurnDistanceInterpolator + SpeedProvider),
        //   (2) bộ đọc màn Maps (accessibility) kéo mốc về số thật (refine()).
        // Noti GMaps thưa → gửi RAW làm cụm "trễ khi tới ngã rẽ/điểm đến"; nội suy lấp khoảng giữa cho mượt.
        // Ép BẬT ở đây để migrate cả bản cài cũ từng bị ép TẮT (2026-07-13). Không có nút UI (giữ UI gọn);
        // muốn TẮT nếu overlay cụm tự animate rồi đánh nhau (verify trên xe) → đổi 2 dòng dưới thành false.
        Prefs.setInterpolate(this, true)
        Prefs.setAccBooster(this, true)

        // Navigation source selector (turn-by-turn direction)
        val navSourceSpinner = findViewById<android.widget.Spinner>(R.id.spinner_nav_source)
        val navSources = arrayOf("Tự động (app dẫn trước)", "Google Maps", "Waze Mod")
        val navSourceModes = intArrayOf(Prefs.AUTO, Prefs.PREFER_GMAPS, Prefs.PREFER_WAZE)
        navSourceSpinner.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, navSources)
        val currentNavMode = Prefs.sourceMode(this)
        navSourceSpinner.setSelection(navSourceModes.indexOf(currentNavMode).coerceAtLeast(0))
        navSourceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                Prefs.setSourceMode(this@MainActivity, navSourceModes[pos])
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Speed + Alert source selector
        val speedSourceSpinner = findViewById<android.widget.Spinner>(R.id.spinner_speed_source)
        val speedSources = arrayOf("VietMap (widget)", "Waze Mod (HLP)")
        val speedSourceModes = intArrayOf(
            com.byd.clusternav.navigation.NavSourceMode.SPEED_VIETMAP,
            com.byd.clusternav.navigation.NavSourceMode.SPEED_WAZE,
        )
        speedSourceSpinner.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, speedSources)
        val currentSpeedMode = Prefs.speedSource(this)
        speedSourceSpinner.setSelection(speedSourceModes.indexOf(currentSpeedMode).coerceAtLeast(0))
        speedSourceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                Prefs.setSpeedSource(this@MainActivity, speedSourceModes[pos])
                speedSign.onSourceSelected(Prefs.speedLimitSource(this@MainActivity))
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Chế độ hiển thị nav trên CỤM — ghi SET_NAVI_SCREEN_STATUS_SET (0x4C10E015) qua NavigationHudOwner
        // (đọc pref mỗi frame → áp dụng LIVE khi đang dẫn). ⚠️ value↔menu OEM chưa map chắc: dò trên xe rồi chốt.
        val clusterModeSpinner = findViewById<android.widget.Spinner>(R.id.spinner_cluster_mode)
        val clusterModes = arrayOf("Đơn giản (Giữa + ETA)", "Toàn màn hình", "Màn hình nhỏ", "OFF")
        val clusterModeValues = intArrayOf(
            Prefs.NAV_SCREEN_SIMPLE, Prefs.NAV_SCREEN_FULL, Prefs.NAV_SCREEN_SMALL, Prefs.NAV_SCREEN_OFF,
        )
        clusterModeSpinner.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, clusterModes)
        clusterModeSpinner.setSelection(clusterModeValues.indexOf(Prefs.navClusterScreenMode(this)).coerceAtLeast(0))
        clusterModeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                Prefs.setNavClusterScreenMode(this@MainActivity, clusterModeValues[pos])
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Nav trên cụm chỉ còn op 39 "Giữa + ETA" (owner chốt 2026-08-12) — bỏ nút chọn mode + nút test.
        // Chỉ còn dòng trạng thái op39 (ASSERTED / Cast đang bật / chưa gửi được) để chẩn đoán.
        navClusterStatus.bind()

        findViewById<Button>(R.id.btn_reconnect_nav).setOnClickListener {
            if (notificationAccessGranted()) {
                NavConnect.reconnect(applicationContext)
                Toast.makeText(this, Lang.t("Đang kết nối lại nguồn dẫn đường…", "Reconnecting navigation source…"), Toast.LENGTH_SHORT).show()
            } else {
                // Thiếu quyền đọc thông báo → mở màn hệ thống "Truy cập thông báo" (đường CHUẨN của
                // Android, KHÔNG cần adb — dùng được cho bản release gửi người khác). Xem
                // promptNotificationAccess(); listener bind lại tự động ở onResume() khi user quay về.
                promptNotificationAccess()
            }
        }
        findViewById<Button>(R.id.btn_nav_stop).setOnClickListener {
            NavRepository.stop(applicationContext)
            refresh()
        }
        findViewById<Button>(R.id.btn_vietmap_widget_diag).setOnClickListener {
            startActivity(Intent(this, VietMapWidgetDiagActivity::class.java))
        }
        findViewById<Button?>(R.id.btn_check_update)?.setOnClickListener {
            val btn = it as Button
            UpdateFlow.start(this) { text, _ -> btn.text = text }
        }

        NavConnect.ensureConnected(applicationContext)
        runCatching { RebindReceiver.scheduleWatchdog(applicationContext) }
        // Nút nổi + chiếu cụm chỉ khởi động khi master switch "Cluster Cast" đang BẬT (MẶC ĐỊNH TẮT —
        // nav-only là mặc định; cụm giữ native + nav hiện ngay, không projection/cong/đen). Tắt Cast ⇒
        // không start service (service cũng tự đứng xuống nếu bị boot khởi động). startForegroundService idempotent.
        runCatching {
            if (com.byd.clusternav.modules.clustercast.simplified.SimpleCastRuntime
                    .coordinator(applicationContext).prefs.castEnabled()
            ) {
                startForegroundService(Intent(this, com.byd.clusternav.modules.clustercast.FloatingBubbleService::class.java))
            }
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        runCatching { RebindReceiver.rebind(applicationContext) }
        // Quay lại từ màn "Truy cập thông báo": vừa bật quyền nhưng listener chưa bind (firmware BYD
        // bỏ qua requestRebind) → ép bind qua dadb. Chỉ chạy khi ĐÃ có quyền mà CHƯA bound (rẻ, không
        // đụng nav đang chạy tốt).
        if (notificationAccessGranted() && !NavNotificationListener.connected) {
            NavConnect.ensureConnected(applicationContext)
        }
        cast.onResume()
        // Nút nổi hiện NGAY sau khi bật Cast + cấp quyền overlay, không cần mở lại app. onCreate() chỉ
        // start service khi overlay ĐÃ có; nếu user vừa cấp quyền ở màn hệ thống rồi quay lại, luồng về
        // đây qua onResume — start lại service để onStartCommand → showBubble() (idempotent, no-op nếu
        // bubble đã hiện). Service tự đứng xuống nếu Cast tắt hoặc overlay vẫn thiếu. runCatching để một
        // ROM thiếu Settings.canDrawOverlays không làm văng Home.
        runCatching {
            if (com.byd.clusternav.modules.clustercast.simplified.SimpleCastRuntime
                    .coordinator(applicationContext).prefs.castEnabled() &&
                Settings.canDrawOverlays(this)
            ) {
                startForegroundService(Intent(this, com.byd.clusternav.modules.clustercast.FloatingBubbleService::class.java))
            }
        }
        ui.post(refresher)
    }

    override fun onPause() {
        ui.removeCallbacks(refresher)
        super.onPause()
    }

    override fun onDestroy() {
        cast.onDestroy()
        super.onDestroy()
    }

    private fun refresh() {
        val permission = if (notificationAccessGranted()) NavigationPermission.GRANTED else NavigationPermission.MISSING
        NavRepository.setPermission(applicationContext, permission)
        val navigation = NavRepository.snapshot(applicationContext)
        val source = navigation.source
        val sourceText = when (val freshness = source.freshness) {
            is NavigationFreshness.Fresh -> source.identity?.displayName ?: source.identity?.packageName ?: Lang.t("Đang dẫn đường", "Navigating")
            is NavigationFreshness.Stale -> Lang.t("Nguồn đã cũ", "Source stale") + " · ${freshness.reason.readable()}"
            is NavigationFreshness.Unknown -> when (permission) {
                NavigationPermission.MISSING -> getString(R.string.status_need_perm)
                else -> freshness.reason.readable()
            }
        }
        navStatus.text = sourceText
        navDot.tint(if (source.freshness is NavigationFreshness.Fresh) R.color.ok_green else if (permission == NavigationPermission.MISSING) R.color.err_red else R.color.warn_amber)
        laneStatus.text = "${Lang.t("Cụm", "Cluster")}: ${navigation.clusterLane.status.label()}"
        hudStatus.text = "HUD: ${navigation.hud.status.label()}"
        findViewById<Button>(R.id.btn_reconnect_nav).visibility =
            if (permission != NavigationPermission.GRANTED) View.VISIBLE else View.GONE
        navClusterStatus.refresh()
    }

    private fun View.tint(color: Int) {
        backgroundTintList = ColorStateList.valueOf(getColor(color))
    }

    private fun NavigationOutputStatus.label(): String = when (this) {
        NavigationOutputStatus.OFF -> Lang.t("tắt", "off")
        NavigationOutputStatus.STARTING -> Lang.t("đang khởi động", "starting")
        NavigationOutputStatus.EMITTING -> Lang.t("đang gửi", "emitting")
        // Trạng thái này KHÔNG BAO GIỜ xảy ra khi chạy thật: `markDisplayVerified` chỉ được gọi từ test,
        // không có producer nào trong `:app`. Giữ nhánh cho `when` vét cạn, nhưng nói đúng cơ sở — theo Q1
        // (đóng ngày 2026-07-27) không có tín hiệu nào của Android xác nhận cụm đang hiện gì, nên chữ
        // "đã xác minh" ở đây sẽ là tuyên bố không ai đặt được.
        NavigationOutputStatus.DISPLAY_VERIFIED -> Lang.t("cụm báo đã nhận", "cluster acknowledged")
        NavigationOutputStatus.STALE -> Lang.t("đã cũ", "stale")
        is NavigationOutputStatus.FAULT -> Lang.t("lỗi: ${reason.readable()}", "error: ${reason.readable()}")
    }

    /**
     * Lý do nguồn dẫn đường, viết cho người đọc.
     *
     * Trước 2026-07-27 chỗ này in `reason.name.replace('_',' ').lowercase()`, nên trên màn tiếng Việt hiện
     * ra "no active session". Cùng lỗi đã sửa ở màn Cast trong ngày: tên hằng trong mã không phải câu cho
     * người dùng. `when` vét cạn nên thêm giá trị mới là trình dịch bắt ngay, không lặng lẽ rơi về tên thô.
     */
    private fun NavigationSourceReason.readable(): String = when (this) {
        NavigationSourceReason.PERMISSION_UNKNOWN -> Lang.t("Chưa rõ quyền notification", "Notification permission unknown")
        NavigationSourceReason.PERMISSION_MISSING -> Lang.t("Cần cấp quyền notification", "Notification permission required")
        NavigationSourceReason.NO_ACTIVE_SESSION -> Lang.t("Chưa có phiên dẫn đường", "No active navigation session")
        NavigationSourceReason.WAITING_FOR_FRAME -> Lang.t("Đang chờ dữ liệu đầu tiên", "Waiting for first data frame")
        NavigationSourceReason.PROCESS_REHYDRATED_UNVERIFIED -> Lang.t("App vừa khởi động lại, chưa xác nhận nguồn", "App just restarted, source unverified")
        NavigationSourceReason.FRAME_EXPIRED -> Lang.t("Dữ liệu quá hạn", "Data expired")
        NavigationSourceReason.SOURCE_DISCONNECTED -> Lang.t("Mất kết nối với app dẫn đường", "Navigation app disconnected")
        NavigationSourceReason.SOURCE_CHANGED -> Lang.t("Nguồn dẫn đường vừa đổi", "Navigation source changed")
    }

    /** Lý do đầu ra lỗi, viết cho người đọc — cùng lý do như trên. */
    private fun NavigationOutputFailureReason.readable(): String = when (this) {
        NavigationOutputFailureReason.DELIVERY_THROWN -> Lang.t("gửi thất bại", "delivery failed")
        NavigationOutputFailureReason.DEADLINE_EXCEEDED -> Lang.t("quá thời gian chờ", "deadline exceeded")
        NavigationOutputFailureReason.QUEUE_SATURATED -> Lang.t("hàng chờ đã đầy", "queue saturated")
        NavigationOutputFailureReason.EXECUTOR_REJECTED -> Lang.t("luồng gửi đã dừng", "executor rejected")
        NavigationOutputFailureReason.DISPLAY_ACK_REJECTED -> Lang.t("cụm từ chối xác nhận", "cluster acknowledgement rejected")
        NavigationOutputFailureReason.INTERNAL_CONTRACT_ERROR -> Lang.t("sai hợp đồng nội bộ", "internal contract error")
    }

    /** Service trợ năng đã được hệ thống bật chưa — công tắc chỉ ghi tuỳ chọn, quyền thì do người dùng cấp. */
    private fun accessibilityBoosterGranted(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_accessibility_services") ?: return false
        return flat.split(':').any { it.contains("com.byd.clusternav") }
    }

    /**
     * Mở màn hệ thống "Truy cập thông báo" để user tự bật ClusterNav — đường CHUẨN Android, KHÔNG cần
     * adb, nên bản release gửi người khác vẫn dùng được. Kèm hướng dẫn ngắn; listener bind lại tự động
     * ở onResume() khi user quay về (ép qua dadb vì firmware BYD bỏ qua requestRebind).
     */
    private fun promptNotificationAccess() {
        android.app.AlertDialog.Builder(this)
            .setTitle(Lang.t("Cần quyền đọc thông báo", "Notification access needed"))
            .setMessage(
                Lang.t(
                    "ClusterNav cần quyền “Truy cập thông báo” để đọc chỉ dẫn từ Google Maps / Waze / VietMap.\n\n" +
                        "Bấm “Mở cài đặt” → tìm ClusterNav trong danh sách → bật lên → quay lại app.",
                    "ClusterNav needs “Notification access” to read guidance from Google Maps / Waze / VietMap.\n\n" +
                        "Tap “Open settings” → find ClusterNav in the list → turn it on → return to the app.",
                ),
            )
            .setNegativeButton(Lang.t("Hủy", "Cancel"), null)
            .setPositiveButton(Lang.t("Mở cài đặt", "Open settings")) { _, _ -> openNotificationAccessSettings() }
            .show()
    }

    /**
     * Điều hướng tới màn Notification-access theo thứ tự ưu tiên: deep-link thẳng entry ClusterNav
     * (API 30+) → màn danh sách → trang chi tiết app. Mọi bước bọc try để không văng nếu ROM thiếu
     * activity nào; hết đường thì toast hướng dẫn tay.
     */
    private fun openNotificationAccessSettings() {
        val comp = ComponentName(this, NavNotificationListener::class.java).flattenToString()
        if (android.os.Build.VERSION.SDK_INT >= 30 && tryStartActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, comp),
            )
        ) {
            return
        }
        if (tryStartActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) return
        if (tryStartActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:$packageName"),
                ),
            )
        ) {
            return
        }
        Toast.makeText(
            this,
            Lang.t(
                "Không mở được cài đặt. Vào Cài đặt → Ứng dụng → Truy cập đặc biệt → Truy cập thông báo → bật ClusterNav.",
                "Couldn't open settings. Go to Settings → Apps → Special access → Notification access → enable ClusterNav.",
            ),
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun tryStartActivity(intent: Intent): Boolean =
        runCatching { startActivity(intent); true }.getOrDefault(false)

    private fun notificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val expected = ComponentName(this, NavNotificationListener::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it.trim()) == expected }
    }
}

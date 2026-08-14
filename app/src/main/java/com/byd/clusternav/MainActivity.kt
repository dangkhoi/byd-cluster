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
                // Option B (1.13): user chủ động bật → GIỜ mới đụng adb. Thiếu quyền → tự cấp qua dadb;
                // đã có → chỉ ensure bind. (Mặc định TẮT nên onCreate không tự chạy nhánh này.)
                if (notificationAccessGranted()) {
                    NavConnect.ensureConnected(applicationContext)
                } else {
                    Toast.makeText(this, Lang.t("Đang cấp quyền đọc thông báo…", "Granting notification access…"), Toast.LENGTH_SHORT).show()
                    NavConnect.selfGrant(applicationContext) { ok ->
                        if (isFinishing) return@selfGrant
                        if (ok) refresh() else promptNotificationAccessFallback()
                    }
                }
                // Bộ đọc màn GMaps (screenRead ground-truth cho tinh chỉnh nội suy) + nút vật lý → trợ lý
                // cần accessibility service. Đây là quyền ADB (settings secure enabled_accessibility_services)
                // → tự cấp qua dadb như notification, CHỈ khi chưa có (tránh phiên dadb thừa). Trước đây chỉ
                // UI voice-key cấp; voice-key gỡ đi → grantAccessibility mồ côi → 2 chuyến screenRead RỖNG
                // (đo 2026-08-14: enabled_accessibility_services mất service sau reboot). Wire vào công tắc
                // Nav+HUD để tự lành sau mỗi state-reset.
                if (!accessibilityBoosterGranted()) NavConnect.grantAccessibility(applicationContext)
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
                // I4 (1.14): áp NGAY (re-assert 4→2) thay vì chờ reboot / frame kế bị dedup nuốt.
                NavRepository.reapplyClusterMode(applicationContext)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // I2 (1.14): toggle marquee (chạy chữ tên đường dài). Mặc định BẬT (Prefs.marquee=true).
        findViewById<android.widget.CheckBox>(R.id.cb_marquee).also { cb ->
            cb.isChecked = Prefs.marquee(this)
            cb.setOnCheckedChangeListener { _, on -> Prefs.setMarquee(this, on) }
        }

        // ── Nút vật lý → Trợ lý giọng nói (switch + nút + cử chỉ + đích + học phím). Owner 2026-08-14:
        // map nút mic vô-lăng (NHẤN-GIỮ = keycode 328) → Kiki (ai.zalo.kiki.car). Chỉ "nuốt" đúng tổ hợp,
        // KHÔNG đổi chức năng gốc của nút. Service Hỗ trợ tự bật qua dadb khi bật công tắc. ──
        setupVoiceKeyControls()

        // Nav trên cụm chỉ còn op 39 "Giữa + ETA" (owner chốt 2026-08-12) — bỏ nút chọn mode + nút test.
        // Chỉ còn dòng trạng thái op39 (ASSERTED / Cast đang bật / chưa gửi được) để chẩn đoán.
        navClusterStatus.bind()

        findViewById<Button>(R.id.btn_reconnect_nav).setOnClickListener {
            if (notificationAccessGranted()) {
                NavConnect.reconnect(applicationContext)
                Toast.makeText(this, Lang.t("Đang kết nối lại nguồn dẫn đường…", "Reconnecting navigation source…"), Toast.LENGTH_SHORT).show()
            } else {
                // Quyền đọc thông báo là quyền ADB (settings secure enabled_notification_listeners) — KHÔNG cần
                // màn Settings (IVI khoá không mở được → toast hệ thống "không hỗ trợ hoạt động này"). Cấp THẲNG
                // qua dadb uid-shell (NavConnect.selfGrant, y như DashCast). Chỉ khi dadb lỗi mới hiện fallback.
                Toast.makeText(this, Lang.t("Đang cấp quyền đọc thông báo…", "Granting notification access…"), Toast.LENGTH_SHORT).show()
                NavConnect.selfGrant(applicationContext) { ok ->
                    if (isFinishing) return@selfGrant
                    if (ok) {
                        Toast.makeText(this, Lang.t("Đã cấp quyền — đã kết nối nguồn dẫn đường.", "Access granted — navigation connected."), Toast.LENGTH_SHORT).show()
                        refresh()
                    } else {
                        promptNotificationAccessFallback()
                    }
                }
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

        // Option B (1.13): chỉ đụng adb khi Navigation+HUD đang BẬT. Mặc định TẮT → mở app KHÔNG chạy dadb
        // (tránh đua nhiều client dadb + popup Allow khi user chưa cần nav). Bật công tắc mới grant+connect.
        if (Prefs.enabled(this)) {
            NavConnect.ensureConnected(applicationContext)
            // Reboot / state-reset xoá enabled_accessibility_services (đo 2026-08-14) → mở app khi Nav+HUD
            // đã BẬT thì tự cấp lại (chỉ khi thiếu) để screenRead booster sống lại, không cần thao tác tay.
            if (!accessibilityBoosterGranted()) NavConnect.grantAccessibility(applicationContext)
        }
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
        if (Prefs.enabled(this) && notificationAccessGranted() && !NavNotificationListener.connected) {
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
        // Tránh rò Activity: VoiceKeyLearnBus là singleton (app-scoped) giữ lambda bắt `this`. Chỉ gỡ khi
        // FINISH thật (đóng app) — KHÔNG gỡ lúc config-change (isFinishing=false) để listener mà onCreate
        // của Activity mới vừa set không bị null oan. Genuine-finish thì không có Activity kế → gỡ = hết rò.
        if (isFinishing) com.byd.clusternav.modules.voicekey.VoiceKeyLearnBus.setListener(null)
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
        updateVoiceKeyLabel()
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

    /**
     * FALLBACK khi tự cấp quyền qua dadb THẤT BẠI (thường vì chưa bấm "Allow USB debugging" trên xe lần
     * đầu). Cho THỬ LẠI selfGrant, hoặc mở màn Settings hệ thống để bật tay (một số máy IVI không có màn này
     * → openNotificationAccessSettings tự toast hướng dẫn). Đây KHÔNG còn là đường chính: đường chính là
     * NavConnect.selfGrant (cấp qua dadb), gọi khi bấm nút / bật công tắc lúc thiếu quyền.
     */
    private fun promptNotificationAccessFallback() {
        if (isFinishing) return
        android.app.AlertDialog.Builder(this)
            .setTitle(Lang.t("Chưa cấp được quyền", "Couldn't grant access"))
            .setMessage(
                Lang.t(
                    "Chưa tự cấp được quyền đọc thông báo qua ADB nội bộ. Lần đầu cần bật gỡ lỗi USB: khi màn " +
                        "xe hiện hộp thoại “Allow USB debugging?”, bấm Allow rồi Thử lại.\n\nHoặc mở cài đặt hệ " +
                        "thống để bật ClusterNav thủ công (một số máy không có màn này).",
                    "Couldn't self-grant notification access over local ADB. First time, allow USB debugging: " +
                        "when the car screen shows “Allow USB debugging?”, tap Allow, then Retry.\n\nOr open " +
                        "system settings to enable ClusterNav manually (some units lack this screen).",
                ),
            )
            .setPositiveButton(Lang.t("Thử lại", "Retry")) { _, _ ->
                Toast.makeText(this, Lang.t("Đang cấp quyền…", "Granting…"), Toast.LENGTH_SHORT).show()
                NavConnect.selfGrant(applicationContext) { ok ->
                    if (isFinishing) return@selfGrant
                    if (ok) refresh()
                    else Toast.makeText(this, Lang.t("Vẫn chưa được — kiểm tra hộp thoại Allow trên xe.", "Still failed — check the Allow dialog on the car."), Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton(Lang.t("Mở cài đặt", "Open settings")) { _, _ -> openNotificationAccessSettings() }
            .setNegativeButton(Lang.t("Đóng", "Close"), null)
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

    // ── Nút vật lý → Trợ lý giọng nói ───────────────────────────────────────────────────────────
    // Ứng viên keycode cho nút voice/steering. "Học phím" = sentinel -1: bấm nút THẬT trên xe để gán
    // keycode chưa biết. Nút mic vô-lăng trên xe này NHẤN-GIỮ = 328 (đo on-car 2026-08-13) → để sẵn làm
    // ứng viên đầu; nhấn ngắn phát mã KHÁC nên trợ lý gốc (小迪) giữ nguyên.
    // Preset keycode ứng viên (nút vô-lăng/táp-lô). Nút tự học thêm từ Prefs.voiceKeyCustomButtons.
    // Nút mic vô-lăng xe này giữ = 328 (đo on-car 2026-08-13); nhấn ngắn ra mã KHÁC nên native (小迪) giữ nguyên.
    private val voiceKeyPresets: List<Pair<String, Int>> = listOf(
        "Nút mic vô-lăng — giữ (328)" to 328,
        "Trợ lý giọng nói (VOICE_ASSIST · 231)" to 231,
        "Trợ lý (ASSIST · 219)" to 219,
        "Play/Pause (85)" to 85,
        "Bài trước (PREVIOUS · 88)" to 88,
        "Bài sau (NEXT · 87)" to 87,
        "Headset hook (79)" to 79,
        "Gọi (CALL · 5)" to 5,
        "Tìm kiếm (SEARCH · 84)" to 84,
    )
    /** Dropdown nút = preset + nút tự học (persist). */
    private fun voiceKeyButtonList(): List<Pair<String, Int>> = voiceKeyPresets + Prefs.voiceKeyCustomButtons(this)

    private fun updateVoiceKeyLabel() {
        val current = findViewById<TextView>(R.id.txt_voicekey_current) ?: return
        val kc = Prefs.voiceKeyCode(this)
        current.text = Lang.t("Nút hiện tại: ", "Current button: ") + android.view.KeyEvent.keyCodeToString(kc) + " ($kc)"
    }

    /** (Re)nạp dropdown nút, chọn keycode [selectCode]. */
    private fun rebuildVoiceKeyButtonSpinner(selectCode: Int = Prefs.voiceKeyCode(this)) {
        val spinner = findViewById<android.widget.Spinner>(R.id.spinner_voicekey_button) ?: return
        val list = voiceKeyButtonList()
        spinner.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, list.map { it.first })
        spinner.setSelection(list.indexOfFirst { it.second == selectCode }.coerceAtLeast(0))
        updateVoiceKeyLabel()
    }

    /** Sau khi service bắt keycode mới: hỏi tên → lưu nút custom → nạp lại dropdown + chọn. */
    private fun showLearnNameDialog(code: Int) {
        if (isFinishing || isDestroyed) return
        val default = android.view.KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").replace('_', ' ')
        val input = android.widget.EditText(this).apply { setText(default); setSelection(text.length) }
        android.app.AlertDialog.Builder(this)
            .setTitle(Lang.t("Đặt tên nút (mã $code)", "Name this button (code $code)"))
            .setView(input)
            .setPositiveButton(Lang.t("Lưu", "Save")) { _, _ ->
                val name = input.text.toString().ifBlank { default }
                Prefs.addVoiceKeyCustomButton(this, "$name (mã $code)", code)
                Prefs.setVoiceKeyCode(this, code)
                rebuildVoiceKeyButtonSpinner(code)
            }
            .setNegativeButton(Lang.t("Huỷ", "Cancel"), null)
            .show()
    }

    private fun setupVoiceKeyControls() {
        val vkSwitch = findViewById<Switch>(R.id.switch_voicekey_enabled)
        val targetSpinner = findViewById<android.widget.Spinner>(R.id.spinner_voicekey_target)

        vkSwitch.isChecked = Prefs.voiceKeyEnabled(this)
        vkSwitch.setOnCheckedChangeListener { _, on ->
            Prefs.setVoiceKeyEnabled(this, on)
            // Cần service Hỗ trợ bật thì onKeyEvent mới nhận phím. Bật qua dadb (như selfGrant) nếu chưa bật.
            if (on && !accessibilityBoosterGranted()) {
                Toast.makeText(this, Lang.t("Đang bật dịch vụ Hỗ trợ…", "Enabling accessibility service…"), Toast.LENGTH_SHORT).show()
                NavConnect.grantAccessibility(applicationContext) { ok ->
                    if (isFinishing) return@grantAccessibility
                    Toast.makeText(
                        this,
                        if (ok) Lang.t("Đã bật. Bấm nút đã gán để mở app.", "Enabled. Press the mapped button to open the app.")
                        else Lang.t("Chưa bật được Hỗ trợ — bấm Allow USB debugging trên xe rồi thử lại, hoặc bật tay ở Cài đặt > Hỗ trợ.", "Couldn't enable accessibility — tap Allow USB debugging on the car and retry, or enable it in Settings > Accessibility."),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }

        // Dropdown nút (preset + custom). Chọn 1 nút = đặt keycode đó.
        rebuildVoiceKeyButtonSpinner()
        val btnSpinner = findViewById<android.widget.Spinner>(R.id.spinner_voicekey_button)
        val vkBtnInitialPos = voiceKeyButtonList().indexOfFirst { it.second == Prefs.voiceKeyCode(this) }.coerceAtLeast(0)
        var vkBtnFirstCallback = true
        btnSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                if (vkBtnFirstCallback) { vkBtnFirstCallback = false; if (pos == vkBtnInitialPos) return }
                val kc = voiceKeyButtonList().getOrNull(pos)?.second ?: return
                Prefs.setVoiceKeyCode(this@MainActivity, kc)
                updateVoiceKeyLabel()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        // Nhấn-giữ 1 mục để XOÁ nút tự học (preset không xoá).
        btnSpinner.onItemLongClickListener = android.widget.AdapterView.OnItemLongClickListener { _, _, pos, _ ->
            val item = voiceKeyButtonList().getOrNull(pos)
            if (item != null && Prefs.voiceKeyCustomButtons(this).any { it.second == item.second }) {
                Prefs.removeVoiceKeyCustomButton(this, item.second)
                rebuildVoiceKeyButtonSpinner()
                Toast.makeText(this, Lang.t("Đã xoá nút", "Button removed"), Toast.LENGTH_SHORT).show()
                true
            } else false
        }

        // Nút "Học phím mới" — NGOÀI dropdown: bấm → chờ bấm nút vật lý → hiện ô đặt tên.
        findViewById<Button>(R.id.btn_voicekey_learn).setOnClickListener {
            Prefs.setVoiceKeyLearn(this, true)
            if (!accessibilityBoosterGranted()) NavConnect.grantAccessibility(applicationContext)
            Toast.makeText(this, Lang.t("Giữ màn hình này mở rồi bấm nút vật lý muốn dùng…", "Keep this screen open, then press the physical button…"), Toast.LENGTH_LONG).show()
        }

        // Đích = 2 mục đặc biệt (ghim đầu) + toàn bộ app có launcher (reuse ClusterCast.listInstalledApps).
        val targetSpecs: List<Pair<String, String>> = listOf(
            Lang.t("Trợ lý mặc định hệ thống", "System default assistant") to Prefs.VK_TARGET_ASSIST,
            Lang.t("Nhận dạng giọng nói", "Speech recognizer") to Prefs.VK_TARGET_RECOGNIZER,
        ) + com.byd.clusternav.modules.clustercast.ClusterCast.listInstalledApps(this).map { it.label to it.pkg }
        targetSpinner.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, targetSpecs.map { it.first })
        val vkTgtInitialPos = targetSpecs.indexOfFirst { it.second == Prefs.voiceKeyTargetSpec(this) }.coerceAtLeast(0)
        targetSpinner.setSelection(vkTgtInitialPos)
        var vkTgtFirstCallback = true
        targetSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                if (vkTgtFirstCallback) { vkTgtFirstCallback = false; if (pos == vkTgtInitialPos) return }
                targetSpecs.getOrNull(pos)?.let { Prefs.setVoiceKeyTargetSpec(this@MainActivity, it.second) }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Cầu học-phím: service bắt keycode → hiện dialog đặt tên (Activity foreground).
        com.byd.clusternav.modules.voicekey.VoiceKeyLearnBus.setListener { code -> runOnUiThread { showLearnNameDialog(code) } }
    }

    private fun tryStartActivity(intent: Intent): Boolean =
        runCatching { startActivity(intent); true }.getOrDefault(false)

    private fun notificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val expected = ComponentName(this, NavNotificationListener::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it.trim()) == expected }
    }

    /**
     * Accessibility booster (đọc màn GMaps → screenRead ground-truth) đã được bật chưa. Đọc THẲNG secure
     * setting (mọi app đọc được — KHÔNG cần dadb), y như [notificationAccessGranted]. Chỉ khi thiếu mới gọi
     * [NavConnect.grantAccessibility] (dadb) để append → tránh mở phiên dadb thừa mỗi lần bật Nav+HUD / mở app.
     */
    private fun accessibilityBoosterGranted(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_accessibility_services") ?: return false
        val expected = ComponentName(this, com.byd.clusternav.modules.navaccess.NavAccessibilityService::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it.trim()) == expected }
    }
}

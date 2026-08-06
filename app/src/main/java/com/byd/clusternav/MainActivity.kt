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
import android.widget.CheckBox
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.byd.clusternav.navigation.NavigationFreshness
import com.byd.clusternav.navigation.NavigationOutputStatus
import com.byd.clusternav.navigation.NavigationOutputTarget
import com.byd.clusternav.navigation.NavigationPermission

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
    private lateinit var laneEnabled: CheckBox
    private lateinit var hudEnabled: CheckBox
    private val cast = MainActivityCastController(this)

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
        laneEnabled = findViewById(R.id.cb_lane)
        hudEnabled = findViewById(R.id.cb_hud)
        cast.onCreate()

        navEnabled.isChecked = Prefs.enabled(this)
        navEnabled.setOnCheckedChangeListener { _, enabled ->
            Prefs.setEnabled(this, enabled)
            if (enabled) {
                NavRepository.setOutputEnabled(this, NavigationOutputTarget.CLUSTER_LANE, Prefs.lane(this))
            } else {
                NavRepository.stop(this)
            }
            refresh()
        }
        laneEnabled.isChecked = Prefs.lane(this)
        laneEnabled.setOnCheckedChangeListener { _, enabled ->
            Prefs.setLane(this, enabled)
            NavRepository.setOutputEnabled(this, NavigationOutputTarget.CLUSTER_LANE, enabled)
            refresh()
        }
        hudEnabled.isChecked = Prefs.hud(this)
        hudEnabled.setOnCheckedChangeListener { _, enabled ->
            Prefs.setHud(this, enabled)
            NavRepository.setOutputEnabled(this, NavigationOutputTarget.HUD, enabled)
            refresh()
        }

        // MỘT công tắc cho một mục đích: "Hỗ trợ cự ly" bật cả hai nửa của cùng cơ chế — nội suy trừ dần cự
        // ly theo tốc độ xe giữa hai lần notification, và bộ đọc màn Maps kéo mốc về đúng số thật.
        //
        // Không tách hai checkbox nữa: tắt nội suy thì bộ đọc màn vẫn chạy nhưng VÔ NGHĨA (`refine()` bỏ qua
        // khi chưa có mốc, mà mốc chỉ do nội suy đặt). Hai công tắc độc lập cho một cơ chế là cách chắc chắn
        // để người dùng bật một nửa rồi tưởng đã bật cả.
        //
        // Bộ đọc màn còn cần quyền trợ năng do HỆ THỐNG cấp. Bật công tắc mà chưa cấp thì nó im lặng không
        // làm gì, nên đưa người dùng sang đúng trang đó — giống cách nút nổi xử lý quyền overlay.
        // Distance assist removed — firmware handles count-down natively; app interpolation causes jumpy numbers.
        // Force-disable any previously saved preference.
        Prefs.setInterpolate(this, false)
        Prefs.setAccBooster(this, false)

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
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btn_reconnect_nav).setOnClickListener {
            NavConnect.reconnect(applicationContext)
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
        // Nút nổi mặc định BẬT (v0.72, không còn công tắc): thiếu quyền overlay thì tự xin ngay lúc mở
        // app lần đầu, không cần user tự bật gì — startForegroundService là idempotent nếu đã chạy.
        runCatching { startForegroundService(Intent(this, com.byd.clusternav.modules.clustercast.FloatingBubbleService::class.java)) }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        runCatching { RebindReceiver.rebind(applicationContext) }
        cast.onResume()
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

    private fun notificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val expected = ComponentName(this, NavNotificationListener::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it.trim()) == expected }
    }
}

package com.byd.clusternav

import android.widget.Toast
import com.byd.clusternav.modules.clustercast.CastBubbleControl
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
import com.byd.clusternav.modules.clustercast.ClusterCast
import com.byd.clusternav.modules.clustercast.ClusterCastActivity
import com.byd.clusternav.navigation.NavigationFreshness
import com.byd.clusternav.navigation.NavigationOutputStatus
import com.byd.clusternav.navigation.NavigationOutputTarget
import com.byd.clusternav.navigation.NavigationPermission

/** Two-card Home renderer/dispatcher. It never orchestrates Navigation and Cast together. */
class MainActivity : Activity() {
    private lateinit var navEnabled: Switch
    private lateinit var navDot: View
    private lateinit var navStatus: TextView
    private lateinit var laneStatus: TextView
    private lateinit var hudStatus: TextView
    private lateinit var laneEnabled: CheckBox
    private lateinit var hudEnabled: CheckBox
    private lateinit var distanceAssist: CheckBox
    private lateinit var castDot: View
    private lateinit var castStatus: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 1_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ClusterCast.loadPrefs(this)

        navEnabled = findViewById(R.id.switch_enabled)
        navDot = findViewById(R.id.dot_status)
        navStatus = findViewById(R.id.txt_status)
        laneStatus = findViewById(R.id.txt_lane_status)
        hudStatus = findViewById(R.id.txt_hud_status)
        laneEnabled = findViewById(R.id.cb_lane)
        hudEnabled = findViewById(R.id.cb_hud)
        distanceAssist = findViewById(R.id.cb_distance_assist)
        castDot = findViewById(R.id.dot_cast)
        castStatus = findViewById(R.id.txt_cast_status)

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
        distanceAssist.isChecked = Prefs.interpolate(this)
        distanceAssist.setOnCheckedChangeListener { _, enabled ->
            Prefs.setInterpolate(this, enabled)
            Prefs.setAccBooster(this, enabled)
            if (enabled && !accessibilityBoosterGranted()) {
                Toast.makeText(this, "Cần bật ClusterNav trong Trợ năng để đọc cự ly từ màn Maps", Toast.LENGTH_LONG).show()
                runCatching {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }

        findViewById<Button>(R.id.btn_reconnect_nav).setOnClickListener {
            NavConnect.reconnect(applicationContext)
        }
        findViewById<Button>(R.id.btn_nav_stop).setOnClickListener {
            NavRepository.stop(applicationContext)
            refresh()
        }
        findViewById<Button>(R.id.btn_cast_details).setOnClickListener {
            startActivity(Intent(this, ClusterCastActivity::class.java))
        }

        // Khối Cast trên Home, đúng v0.3x/v0.57: thao tác chính bấm được NGAY, chi tiết mới phải đi sâu.
        // Home vẫn là renderer/dispatcher — nó không tự lập kế hoạch gì, chỉ chuyển ý định sang màn Cast
        // (nơi có façade + trạng thái) hoặc bật/tắt nút nổi, một việc thuần tuỳ chọn cục bộ.
        findViewById<Button>(R.id.btn_cast_toggle).setOnClickListener {
            startActivity(
                Intent(this, ClusterCastActivity::class.java)
                    .putExtra(ClusterCastActivity.EXTRA_CAST_NOW, true),
            )
        }
        findViewById<Button>(R.id.btn_bubble).setOnClickListener {
            val wanted = !CastBubbleControl.optedIn(this)
            if (!CastBubbleControl.apply(this, wanted) && wanted) {
                CastBubbleControl.requestOverlay(this)
            }
            refresh()
        }

        NavConnect.ensureConnected(applicationContext)
        runCatching { RebindReceiver.scheduleWatchdog(applicationContext) }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        runCatching { RebindReceiver.rebind(applicationContext) }
        ui.post(refresher)
    }

    override fun onPause() {
        ui.removeCallbacks(refresher)
        super.onPause()
    }

    private fun refresh() {
        val permission = if (notificationAccessGranted()) NavigationPermission.GRANTED else NavigationPermission.MISSING
        NavRepository.setPermission(applicationContext, permission)
        val navigation = NavRepository.snapshot(applicationContext)
        val source = navigation.source
        val sourceText = when (val freshness = source.freshness) {
            is NavigationFreshness.Fresh -> source.identity?.displayName ?: source.identity?.packageName ?: "Đang dẫn đường"
            is NavigationFreshness.Stale -> "Nguồn đã cũ · ${freshness.reason.readable()}"
            is NavigationFreshness.Unknown -> when (permission) {
                NavigationPermission.MISSING -> getString(R.string.status_need_perm)
                else -> freshness.reason.readable()
            }
        }
        navStatus.text = sourceText
        navDot.tint(if (source.freshness is NavigationFreshness.Fresh) R.color.ok_green else if (permission == NavigationPermission.MISSING) R.color.err_red else R.color.warn_amber)
        laneStatus.text = "Cụm: ${navigation.clusterLane.status.label()}"
        hudStatus.text = "HUD: ${navigation.hud.status.label()}"
        findViewById<Button>(R.id.btn_reconnect_nav).visibility =
            if (permission != NavigationPermission.GRANTED) View.VISIBLE else View.GONE

        val casting = ClusterCast.casting
        castDot.tint(if (casting) R.color.ok_green else R.color.off_gray)
        castStatus.text = if (casting) {
            val pkg = ClusterCast.lastCastApp
            "Đang chiếu: ${pkg.takeIf(String::isNotBlank)?.let { ClusterCast.labelOf(this, it) } ?: "app"}"
        } else {
            "Sẵn sàng · mở chi tiết để điều khiển"
        }
    }

    private fun View.tint(color: Int) {
        backgroundTintList = ColorStateList.valueOf(getColor(color))
    }

    private fun NavigationOutputStatus.label(): String = when (this) {
        NavigationOutputStatus.OFF -> "tắt"
        NavigationOutputStatus.STARTING -> "đang khởi động"
        NavigationOutputStatus.EMITTING -> "đang gửi"
        // Trạng thái này KHÔNG BAO GIỜ xảy ra khi chạy thật: `markDisplayVerified` chỉ được gọi từ test,
        // không có producer nào trong `:app`. Giữ nhánh cho `when` vét cạn, nhưng nói đúng cơ sở — theo Q1
        // (đóng ngày 2026-07-27) không có tín hiệu nào của Android xác nhận cụm đang hiện gì, nên chữ
        // "đã xác minh" ở đây sẽ là tuyên bố không ai đặt được.
        NavigationOutputStatus.DISPLAY_VERIFIED -> "cụm báo đã nhận"
        NavigationOutputStatus.STALE -> "đã cũ"
        is NavigationOutputStatus.FAULT -> "lỗi: ${reason.readable()}"
    }

    /**
     * Lý do nguồn dẫn đường, viết cho người đọc.
     *
     * Trước 2026-07-27 chỗ này in `reason.name.replace('_',' ').lowercase()`, nên trên màn tiếng Việt hiện
     * ra "no active session". Cùng lỗi đã sửa ở màn Cast trong ngày: tên hằng trong mã không phải câu cho
     * người dùng. `when` vét cạn nên thêm giá trị mới là trình dịch bắt ngay, không lặng lẽ rơi về tên thô.
     */
    private fun NavigationSourceReason.readable(): String = when (this) {
        NavigationSourceReason.PERMISSION_UNKNOWN -> "Chưa rõ quyền notification"
        NavigationSourceReason.PERMISSION_MISSING -> "Cần cấp quyền notification"
        NavigationSourceReason.NO_ACTIVE_SESSION -> "Chưa có phiên dẫn đường"
        NavigationSourceReason.WAITING_FOR_FRAME -> "Đang chờ dữ liệu đầu tiên"
        NavigationSourceReason.PROCESS_REHYDRATED_UNVERIFIED -> "App vừa khởi động lại, chưa xác nhận nguồn"
        NavigationSourceReason.FRAME_EXPIRED -> "Dữ liệu quá hạn"
        NavigationSourceReason.SOURCE_DISCONNECTED -> "Mất kết nối với app dẫn đường"
        NavigationSourceReason.SOURCE_CHANGED -> "Nguồn dẫn đường vừa đổi"
    }

    /** Lý do đầu ra lỗi, viết cho người đọc — cùng lý do như trên. */
    private fun NavigationOutputFailureReason.readable(): String = when (this) {
        NavigationOutputFailureReason.DELIVERY_THROWN -> "gửi thất bại"
        NavigationOutputFailureReason.DEADLINE_EXCEEDED -> "quá thời gian chờ"
        NavigationOutputFailureReason.QUEUE_SATURATED -> "hàng chờ đã đầy"
        NavigationOutputFailureReason.EXECUTOR_REJECTED -> "luồng gửi đã dừng"
        NavigationOutputFailureReason.DISPLAY_ACK_REJECTED -> "cụm từ chối xác nhận"
        NavigationOutputFailureReason.INTERNAL_CONTRACT_ERROR -> "sai hợp đồng nội bộ"
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

package com.byd.clusternav

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.content.res.ColorStateList
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import com.byd.clusternav.modules.clustercast.ClusterCast
import com.byd.clusternav.modules.clustercast.ClusterCastActivity
import com.byd.clusternav.modules.clustercast.FloatingBubbleService
import com.byd.clusternav.modules.deadreckon.DeadReckonService
import com.byd.clusternav.modules.deadreckon.DeadReckonState

/**
 * Màn điều khiển 3 chức năng (redesign 2026-07-13): mỗi card 1 việc + 1 dòng trạng thái [dot + chữ].
 *  Card 1 = chỉ dẫn lên cụm (công tắc + trạng thái + reconnect + khắc phục thu gọn).
 *  Card 2 = GPS trong hầm.  Card 3 = chiếu app lên cụm (chiếu/tắt + nút nổi + cài đặt).
 */
class MainActivity : Activity() {

    private lateinit var swEnabled: Switch
    private lateinit var dot: View
    private lateinit var txtStatus: TextView
    private lateinit var btnReconnect: Button
    private lateinit var llTrouble: View
    private lateinit var btnGpsToggle: Button
    private lateinit var dotGps: View
    private lateinit var txtGpsStatus: TextView
    private lateinit var dotCast: View
    private lateinit var txtCastStatus: TextView
    private lateinit var btnCastToggle: Button

    private val ui = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() { refreshStatus(); ui.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ClusterCast.loadPrefs(this)

        swEnabled = findViewById(R.id.switch_enabled)
        dot = findViewById(R.id.dot_status)
        txtStatus = findViewById(R.id.txt_status)
        btnReconnect = findViewById(R.id.btn_reconnect_nav)
        llTrouble = findViewById(R.id.ll_troubleshoot)
        btnGpsToggle = findViewById(R.id.btn_gps_toggle)
        dotGps = findViewById(R.id.dot_gps)
        txtGpsStatus = findViewById(R.id.txt_gps_status)
        dotCast = findViewById(R.id.dot_cast)
        txtCastStatus = findViewById(R.id.txt_cast_status)
        btnCastToggle = findViewById(R.id.btn_cast_toggle)

        // ── CARD 1: chỉ dẫn lên cụm ──
        swEnabled.isChecked = Prefs.enabled(this)
        swEnabled.setOnCheckedChangeListener { _, on ->
            Prefs.setEnabled(this, on)
            if (!on) ClusterBroadcaster.stop(applicationContext)
            refreshStatus()
        }
        btnReconnect.setOnClickListener { NavConnect.reconnect(applicationContext) }
        val btnTrouble = findViewById<TextView>(R.id.btn_troubleshoot)
        btnTrouble.setOnClickListener {
            val show = llTrouble.visibility != View.VISIBLE
            llTrouble.visibility = if (show) View.VISIBLE else View.GONE
            btnTrouble.text = if (show) "▾ Khắc phục sự cố" else "▸ Khắc phục sự cố"
        }
        findViewById<Button>(R.id.btn_clear).setOnClickListener { ClusterBroadcaster.stop(applicationContext) }
        findViewById<Button>(R.id.btn_reset).setOnClickListener { ClusterBroadcaster.resetBydNaving(applicationContext) }
        findViewById<android.widget.CheckBox>(R.id.cb_interpolate).apply {
            isChecked = Prefs.interpolate(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setInterpolate(this@MainActivity, v) }
        }
        findViewById<android.widget.CheckBox>(R.id.cb_hud).apply {
            isChecked = Prefs.hud(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setHud(this@MainActivity, v); if (!v) ClusterBroadcaster.onHudOff(applicationContext) }
        }

        // ── CARD 2: GPS hầm ──
        btnGpsToggle.setOnClickListener {
            if (DeadReckonState.running) {
                Prefs.setGpsAuto(this, false)
                stopService(Intent(this, DeadReckonService::class.java))
            } else {
                Prefs.setGpsAuto(this, true)
                if (hasLocPerm()) startDr() else requestLocPerm()
            }
            updateGpsUI()
        }

        // ── CARD 3: chiếu app lên cụm ──
        btnCastToggle.setOnClickListener {
            // nổi lý do lỗi lên toast (❌/⚠) thay vì nuốt vào {} — trước đây chiếu fail (vd chưa bấm Allow USB) im re
            val castLog: (String) -> Unit = { s -> if (s.startsWith("❌") || s.startsWith("⚠")) ui.post { toast(s) } }
            if (ClusterCast.casting) {
                ClusterCast.stop(applicationContext, castLog)
            } else if (ClusterCast.castableApps.isEmpty() && ClusterCast.lastCastApp.isBlank()) {
                toast("Chưa chọn app — mở Cài đặt chiếu để tick app")
                openCastSettings()
            } else {
                toast("Đang chiếu… (nếu hiện popup Allow USB debugging thì bấm Allow)")
                ClusterCast.cast(applicationContext, "", log = castLog)
            }
            updateCastUI()
        }
        findViewById<Button>(R.id.btn_cluster_map).setOnClickListener { openCastSettings() }
        findViewById<Button>(R.id.btn_bubble).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                toast("Cấp quyền 'Hiển thị trên ứng dụng khác' rồi bấm lại")
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))) }
            } else {
                FloatingBubbleService.start(this)
                toast("Đã bật nút nổi — chạm: bật/tắt chiếu · giữ: chọn app")
            }
        }

        // ── AUTO: nav connect + GPS hầm nếu bật ──
        NavConnect.ensureConnected(applicationContext)
        if (Prefs.gpsAuto(this)) {
            when { !hasLocPerm() -> requestLocPerm(); !DeadReckonState.running -> runCatching { startDr() } }
        }
        runCatching { RebindReceiver.scheduleWatchdog(applicationContext) }
        // AUTO hiện NÚT NỔI khi mở app (user: "luôn hiện bubble") — cần quyền overlay (chưa có thì bỏ qua, user bấm nút Bubble để cấp).
        if (Prefs.bubbleAuto(this) && Settings.canDrawOverlays(this)) runCatching { FloatingBubbleService.start(this) }
        // "Mượt UI": set 3 animation scale = 0.5 global qua dadb (tweak hội BYD; mặc định bật). Idempotent, chạy nền.
        if (Prefs.animOpt(this)) ClusterCast.applyGlobalAnim(applicationContext, "0.5")

        refreshStatus()
    }

    override fun onResume() {
        super.onResume(); ui.post(refresher)
        runCatching { RebindReceiver.rebind(applicationContext) }
        refreshStatus()
    }
    override fun onPause() { super.onPause(); ui.removeCallbacks(refresher) }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()
    private fun openCastSettings() = runCatching { startActivity(Intent(this, ClusterCastActivity::class.java)) }

    // ── quyền vị trí runtime (gốc rễ GPS DR trên bản release) ──
    private fun hasLocPerm() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun requestLocPerm() { runCatching { requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC) } }
    private fun startDr() { runCatching { startForegroundService(Intent(this, DeadReckonService::class.java)) } }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOC) {
            if (hasLocPerm()) { if (Prefs.gpsAuto(this)) startDr() }
            else { Prefs.setGpsAuto(this, false); toast("GPS hầm cần quyền Vị trí — bị từ chối") }
            updateGpsUI()
        }
    }

    private fun updateGpsUI() {
        val on = DeadReckonState.running
        btnGpsToggle.text = if (on) "Tắt GPS hầm" else "Bật GPS hầm"
        val (colorRes, text) = when {
            on -> R.color.ok_green to "Đang bù GPS trong hầm"
            !hasLocPerm() -> R.color.warn_amber to "Thiếu quyền Vị trí — chạm Bật để cấp"
            else -> R.color.off_gray to "Chưa bật"
        }
        dotGps.backgroundTintList = ColorStateList.valueOf(getColor(colorRes))
        txtGpsStatus.text = text
    }

    private fun updateCastUI() {
        val c = ClusterCast.casting
        dotCast.backgroundTintList = ColorStateList.valueOf(getColor(if (c) R.color.ok_green else R.color.off_gray))
        val app = ClusterCast.lastCastApp
        txtCastStatus.text = if (c) "Đang chiếu: " + (if (app.isBlank()) "app" else ClusterCast.labelOf(this, app)) else "Chưa chiếu"
        btnCastToggle.text = if (c) "Tắt — trả đồng hồ" else "Chiếu lên cụm"
    }

    private fun refreshStatus() {
        val granted = notifAccessGranted()
        val on = Prefs.enabled(this)
        val now = System.currentTimeMillis()
        val st = NavRepository.state
        val fresh = SourceArbiter.isFresh(now) && st.active && now - st.updatedAt < SourceArbiter.STALE_MS
        val (colorRes, text) = when {
            !on -> R.color.off_gray to getString(R.string.status_off)
            !granted -> R.color.err_red to getString(R.string.status_need_perm)
            fresh -> R.color.ok_green to "${st.road} · ${st.distance}"
            else -> R.color.warn_amber to getString(R.string.status_waiting)
        }
        dot.backgroundTintList = ColorStateList.valueOf(getColor(colorRes))
        txtStatus.text = text
        // reconnect chỉ hiện khi ĐANG bật mà chưa dẫn được (amber/đỏ) — bình thường ẩn cho gọn
        btnReconnect.visibility = if (on && colorRes != R.color.ok_green) View.VISIBLE else View.GONE
        updateGpsUI()
        updateCastUI()
    }

    private fun notifAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        // So KHỚP ĐÚNG component (unflatten) thay contains(packageName) lỏng — "com.byd.clusternav2" hay app khác
        // trùng chuỗi sẽ KHÔNG còn false-positive; cũng đòi đúng NavNotificationListener được bật.
        val want = android.content.ComponentName(this, NavNotificationListener::class.java)
        return flat.split(":").any { android.content.ComponentName.unflattenFromString(it.trim()) == want }
    }

    companion object { private const val REQ_LOC = 4712 }
}

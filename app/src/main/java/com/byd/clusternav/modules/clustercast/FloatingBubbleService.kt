package com.byd.clusternav.modules.clustercast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * NÚT NỔI (bong bóng overlay) — bật/tắt chiếu app lên cụm mà không cần mở app.
 *  - CHẠM  = TOGGLE: đang chiếu → trả về màn chính; chưa → chiếu app gần nhất (lastCastApp / app đầu danh sách).
 *  - GIỮ   = MENU các app đã tick trong Cài đặt chiếu → chọn 1 để chiếu (KHÔNG mở Activity to khi đang lái).
 *  - KÉO   = di chuyển (nhớ vị trí).
 * Bong bóng PHẢN ÁNH TRẠNG THÁI: viền xanh (chưa chiếu, mũi tên ↑) ↔ đặc xanh (đang chiếu, mũi tên ↓) — hết "toggle mù".
 */
class FloatingBubbleService : Service() {
    private var wm: WindowManager? = null
    private var bubble: TextView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var menu: View? = null
    private val ui = Handler(Looper.getMainLooper())

    private val BRAND = 0xFF1565C0.toInt()
    private val BRAND_LIGHT = 0xFFE6F1FB.toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIF_ID, buildNotif())
        ClusterCast.loadPrefs(applicationContext)
        if (bubble == null) {
            if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) { notifyNeedOverlay(); stopSelf(); return START_NOT_STICKY }
            runCatching { addBubble() }.onFailure { notifyNeedOverlay(); stopSelf(); return START_NOT_STICKY }
        }
        // observer: đổi trạng thái chiếu → cập nhật bong bóng (post về UI thread vì cast/stop chạy nền)
        ClusterCast.onCastingChanged = { ui.post { updateVisual() } }
        updateVisual()
        return START_STICKY
    }

    private fun addBubble() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val btn = TextView(this).apply { gravity = Gravity.CENTER; setTextColor(Color.WHITE) }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                   else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val sz = dp(56)
        val p = WindowManager.LayoutParams(sz, sz, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            val pf = getSharedPreferences("bubble", MODE_PRIVATE)
            x = pf.getInt("x", dp(12)); y = pf.getInt("y", dp(240))
        }
        lp = p
        btn.setOnTouchListener(makeDragTap())
        wm?.addView(btn, p)
        bubble = btn
    }

    /** Bong bóng đổi hình theo trạng thái chiếu — điểm mấu chốt để "1 chạm không nhầm". */
    private fun updateVisual() {
        val b = bubble ?: return
        val casting = ClusterCast.casting
        b.textSize = 24f
        b.text = if (casting) "▣" else "▢"   // ô-màn-hình: đặc = đang chiếu, rỗng = chưa (tap = mở menu)
        b.setTextColor(if (casting) Color.WHITE else BRAND)
        b.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (casting) BRAND else BRAND_LIGHT)
            setStroke(dp(2), BRAND)
        }
    }

    private fun makeDragTap(): View.OnTouchListener {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false; var downAt = 0L
        return View.OnTouchListener { v, e ->
            val p = lp ?: return@OnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moved = false; downAt = System.currentTimeMillis(); true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > 14 || abs(dy) > 14) moved = true
                    p.x = startX + dx.toInt(); p.y = startY + dy.toInt()
                    runCatching { wm?.updateViewLayout(v, p) }; true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); showMenu() }   // TAP = MENU (bỏ long-press khó xài)
                    else runCatching { getSharedPreferences("bubble", MODE_PRIVATE).edit().putInt("x", p.x).putInt("y", p.y).apply() }
                    true
                }
                else -> false
            }
        }
    }

    private fun toast(s: String) = runCatching { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show() }

    private fun castApp(pkg: String) {
        toast("Chiếu ${ClusterCast.labelOf(applicationContext, pkg)}…")
        ClusterCast.cast(applicationContext, pkg) { android.util.Log.i("ClusterCast", it) }
    }

    /** TAP bong bóng = MENU: bảng app dễ nhấn (app đang chiếu tô xanh + ✓) + Về màn chính + Cấu hình. */
    private fun showMenu() {
        removeMenu()
        val wmm = wm ?: return
        val apps = ClusterCast.castableApps
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(Color.WHITE); cornerRadius = dp(16).toFloat(); setStroke(dp(1), 0xFFD3D1C7.toInt()) }
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        panel.addView(TextView(this).apply { text = "Chiếu app lên cụm"; textSize = 15f; setTextColor(0xFF1A1F24.toInt()); setPadding(dp(4), dp(2), dp(4), dp(4)) })
        panel.addView(TextView(this).apply {
            text = if (ClusterCast.casting) "● Đang chiếu: ${ClusterCast.labelOf(applicationContext, ClusterCast.lastCastApp)}" else "○ Chưa chiếu"
            textSize = 13f; setTextColor(if (ClusterCast.casting) 0xFF1D9E75.toInt() else 0xFF5B6470.toInt()); setPadding(dp(4), 0, dp(4), dp(8))
        })
        if (apps.isEmpty()) {
            panel.addView(TextView(this).apply { text = "Chưa chọn app — mở Cấu hình để tick app"; textSize = 14f; setTextColor(0xFF5B6470.toInt()); setPadding(dp(4), dp(6), dp(4), dp(6)) })
        } else {
            var row: LinearLayout? = null
            apps.forEachIndexed { i, p ->
                if (i % 2 == 0) { row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; panel.addView(row) }
                val active = ClusterCast.casting && ClusterCast.lastCastApp == p
                row!!.addView(appTile(p, active))
            }
            if (apps.size % 2 == 1) row!!.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })   // ô trống cho cân cột lẻ
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        actions.addView(actBtn("⏹ Về màn chính", 0xFF8A1C1C.toInt()) { removeMenu(); toast("Trả về…"); ClusterCast.stop(applicationContext) { android.util.Log.i("ClusterCast", it) } })
        actions.addView(actBtn("⚙ Cấu hình", 0xFF37474F.toInt()) { removeMenu(); openSettings() })
        panel.addView(actions)

        val backdrop = FrameLayout(this).apply { setBackgroundColor(0x66000000); setOnClickListener { removeMenu() } }
        val p = lp; val w = dp(300)
        val maxLeft = (resources.displayMetrics.widthPixels - w).coerceAtLeast(dp(8))
        val margin = FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = (p?.x ?: dp(12)).coerceIn(dp(8), maxLeft)
            topMargin = ((p?.y ?: dp(240)) + dp(64)).coerceIn(dp(8), (resources.displayMetrics.heightPixels - dp(340)).coerceAtLeast(dp(8)))
        }
        backdrop.addView(panel, margin)
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val blp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
        runCatching { wmm.addView(backdrop, blp); menu = backdrop }
    }

    /** 1 ô app trong bảng 2 cột: ICON + tên — nhấn = chiếu (warm switch nếu đang chiếu). Ô đang chiếu tô nền xanh + ✓. */
    private fun appTile(pkg: String, active: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; minimumHeight = dp(78)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = dp(10).toFloat()
            setColor(if (active) 0xFFE6F1FB.toInt() else 0xFFF4F6F8.toInt()); setStroke(dp(if (active) 2 else 1), if (active) 0xFF378ADD.toInt() else 0xFFD3D1C7.toInt())
        }
        setPadding(dp(6), dp(8), dp(6), dp(8))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
        addView(ImageView(this@FloatingBubbleService).apply {
            val s = dp(36); layoutParams = LinearLayout.LayoutParams(s, s)
            ClusterCast.iconOf(applicationContext, pkg)?.let { setImageDrawable(it) } ?: setImageResource(android.R.drawable.sym_def_app_icon)
        })
        addView(TextView(this@FloatingBubbleService).apply {
            text = (if (active) "✓ " else "") + ClusterCast.labelOf(applicationContext, pkg)
            textSize = 12f; gravity = Gravity.CENTER; maxLines = 2; setPadding(0, dp(4), 0, 0)
            setTextColor(if (active) 0xFF185FA5.toInt() else 0xFF1A1F24.toInt())
        })
        setOnClickListener { removeMenu(); castApp(pkg) }
    }

    private fun actBtn(label: String, color: Int, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); minHeight = dp(52)
        background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(10).toFloat(); setColor(color) }
        setPadding(dp(8), dp(8), dp(8), dp(8))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), 0, dp(3), 0) }
        setOnClickListener { runCatching(onClick) }
    }
    private fun removeMenu() { menu?.let { m -> runCatching { wm?.removeView(m) } }; menu = null }

    private fun openSettings() = runCatching { startActivity(Intent(this, ClusterCastActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        ClusterCast.onCastingChanged = null
        removeMenu()
        runCatching { bubble?.let { wm?.removeView(it) } }; bubble = null
        super.onDestroy()
    }

    private fun notifyNeedOverlay() {
        val i = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, ensureChannel())
            .setContentTitle("Nút nổi cần quyền hiển thị")
            .setContentText("Chạm để cấp quyền 'Hiển thị trên ứng dụng khác'")
            .setSmallIcon(android.R.drawable.ic_menu_mapmode).setContentIntent(pi).setAutoCancel(true).build()
        runCatching { (getSystemService(NotificationManager::class.java)).notify(NOTIF_ID + 1, n) }
    }

    private fun ensureChannel(): String {
        val ch = "cluster_bubble"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(ch) == null)
                nm.createNotificationChannel(NotificationChannel(ch, "Nút nổi Map-cluster", NotificationManager.IMPORTANCE_LOW))
        }
        return ch
    }

    private fun buildNotif(): Notification {
        val stopI = Intent(this, FloatingBubbleService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(this, 1, stopI, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, ensureChannel())
            .setContentTitle("Nút nổi Chiếu-cụm đang bật")
            .setContentText("Chạm bong bóng → menu chọn app / về màn chính")
            .setSmallIcon(android.R.drawable.ic_menu_mapmode)
            .addAction(Notification.Action.Builder(null, "Tắt nút nổi", stopPi).build())
            .setOngoing(true).build()
    }

    companion object {
        private const val NOTIF_ID = 4720
        private const val ACTION_STOP = "com.byd.clusternav.BUBBLE_STOP"
        fun start(ctx: Context) { runCatching { ctx.startForegroundService(Intent(ctx, FloatingBubbleService::class.java)) } }
        fun stop(ctx: Context) { runCatching { ctx.stopService(Intent(ctx, FloatingBubbleService::class.java)) } }
    }
}

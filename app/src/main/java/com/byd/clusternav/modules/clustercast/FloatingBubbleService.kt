package com.byd.clusternav.modules.clustercast

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.byd.clusternav.R
import com.byd.clusternav.modules.clustercast.v2.BubbleFocusTarget
import com.byd.clusternav.modules.clustercast.v2.BubbleProjection
import com.byd.clusternav.modules.clustercast.v2.BubbleStopControl
import com.byd.clusternav.cast.platform.CastAndroidLifecycle
import com.byd.clusternav.cast.platform.CastAndroidRuntime
import com.byd.clusternav.cast.platform.CastAppCatalog
import com.byd.clusternav.cast.platform.CastAppEntry
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Presentation-only overlay host for the canonical Cast model.
 *
 * It renders the same [com.byd.clusternav.modules.clustercast.v2.CastRenderModel] the Activity uses,
 * dispatches only currently exported canonical actions through the one process runtime, and sends at
 * most one typed Stop request. It never infers policy from localized text and never opens the
 * Activity to send a second Stop.
 */
class FloatingBubbleService : Service() {
    private lateinit var runtime: CastAndroidRuntime.Runtime
    private lateinit var facade: CastFacade
    /**
     * Khởi tạo TRỄ, không phải `lateinit`.
     *
     * `catalog` cần `facade` (để hỏi phiên điện thoại) còn `facade` cần `catalog` — vòng tròn. Nó chạy được
     * vì lambda hoãn việc đọc `facade` tới lúc gọi thật. Nhưng với `lateinit` thì thứ tự hai dòng gán trở
     * thành thứ quyết định app sống hay chết: 2026-07-27 commit 00e5438 chèn `facade = ...` LÊN TRÊN dòng
     * gán `catalog`, và nút nổi crash ngay khi bật —
     * `UninitializedPropertyAccessException: lateinit property catalog has not been initialized`.
     *
     * `by lazy` làm sai thứ tự đó thành KHÔNG THỂ: ai đọc trước thì nó dựng lúc đó.
     */
    private val catalog: CastAppCatalog by lazy {
        CastAppCatalog(applicationContext) { facade.phoneSession(it) }
    }
    private var windowManager: WindowManager? = null
    private var bubble: LinearLayout? = null
    private var menuButton: TextView? = null
    private var primaryButton: TextView? = null
    private var stopButton: TextView? = null
    private var statusLabel: TextView? = null
    private var menuPanel: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null

    // Mờ khi rảnh để không đè map; chạm hoặc đổi trạng thái chiếu thì rõ ngay rồi tự mờ lại (v0.57).
    private val fade = Runnable { setBubbleAlpha(IDLE_ALPHA) }
    private fun setBubbleAlpha(a: Float) {
        val layout = params ?: return
        val view = bubble ?: return
        if (layout.alpha == a) return
        layout.alpha = a
        runCatching { windowManager?.updateViewLayout(view, layout) }
    }
    private fun wakeBubble() {
        handler.removeCallbacks(fade)
        setBubbleAlpha(ACTIVE_ALPHA)
        handler.postDelayed(fade, FADE_DELAY_MS)
    }
    private fun goHome() {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val projecting = AtomicBoolean(false)
    private val stopInFlight = AtomicBoolean(false)
    private val dispatchInFlight = AtomicBoolean(false)
    @Volatile private var stopRequestedAtMs = 0L
    private val generation = AtomicLong(0)
    @Volatile private var destroyed = false
    @Volatile private var entries: List<CastAppEntry> = emptyList()
    private var lastProjection: BubbleProjection? = null
    private var foregroundStarted = false
    private val refresh = object : Runnable {
        override fun run() { projectState(); handler.postDelayed(this, REFRESH_INTERVAL_MS) }
    }

    override fun onCreate() {
        super.onCreate()
        runtime = CastAndroidRuntime.create(applicationContext)
        facade = CastFacade.wrapping(runtime, catalog)
        if (!catalog.bubbleEnabled()) { stopSelf(); return }
        if (!startForegroundOnce()) { stopSelf(); return }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        showBubble()
        Thread {
            runCatching { CastAndroidLifecycle.rehydrate(applicationContext) }
            val loaded = runCatching { catalog.installed(runtime.automation.config().defaultPackage) }
                .getOrDefault(emptyList())
            handler.post {
                if (destroyed) return@post
                entries = loaded
                handler.post(refresh)
            }
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!catalog.bubbleEnabled()) { stopSelf(startId); return START_NOT_STICKY }
        if (!startForegroundOnce()) { stopSelf(startId); return START_NOT_STICKY }
        if (!Settings.canDrawOverlays(this)) { stopSelf(startId); return START_NOT_STICKY }
        // An explicit Retry/Start after a permission grant may attach the overlay, still with zero dispatch.
        showBubble()
        return START_STICKY
    }

    private fun startForegroundOnce(): Boolean = foregroundStarted || runCatching {
        startForeground(NOTIFICATION_ID, notification())
        foregroundStarted = true
        true
    }.getOrElse {
        android.util.Log.e("ClusterCastBubble", "startForeground denied", it)
        false
    }

    override fun onDestroy() {
        destroyed = true
        generation.incrementAndGet()
        handler.removeCallbacksAndMessages(null)
        closeMenu()
        bubble?.let { view -> runCatching { windowManager?.removeView(view) } }
        bubble = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showBubble() {
        if (bubble != null) return
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = manager
        stopButton = bubbleText("Dừng chiếu", STOP_MIN_DP).apply {
            id = STOP_VIEW_ID
            setBackgroundResource(com.byd.clusternav.R.drawable.bubble_panel)
            visibility = View.GONE
            setOnClickListener { requestStopOnce() }
        }
        // V1 shape: a small translucent glyph, not a text label. The label lives in
        // contentDescription so TalkBack and the rotary still announce the real state.
        menuButton = bubbleText("", MENU_MIN_DP).apply {
            id = MENU_BUTTON_ID
            contentDescription = "Mở menu Cluster Cast"
            gravity = Gravity.CENTER
            setBackgroundResource(com.byd.clusternav.R.drawable.bubble_round)
            val glyph = resources.getDrawable(com.byd.clusternav.R.drawable.ic_cast_bubble, theme)
            val side = dp(22)
            glyph?.setBounds(0, 0, side, side)
            setCompoundDrawablesRelative(glyph, null, null, null)
            compoundDrawablePadding = 0
            setOnClickListener { toggleMenu() }
        }
        primaryButton = bubbleText("", MENU_MIN_DP).apply { visibility = View.GONE }
        statusLabel = bubbleText("", 0).apply { textSize = 10f; visibility = View.GONE }
        // Nghỉ = ĐÚNG MỘT vòng tròn, như v0.57. Bản V2 trước xếp cả "Dừng chiếu" + nhãn trạng thái thành một
        // hàng chữ nhật nằm đè lên map — đó là cái chủ dự án nói xấu. Stop giờ nằm trong menu (chạm để mở),
        // còn stopButton/primaryButton/statusLabel vẫn được tạo để applyProjection và test bám vào, nhưng
        // KHÔNG nằm trên hàng nghỉ.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.TRANSPARENT)
            addView(menuButton)
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val saved = catalog.bubblePosition()
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = IDLE_ALPHA
            // Default clear of the screen's own content instead of on top of the status card.
            x = clampX(saved?.first ?: (resources.displayMetrics.widthPixels - dp(84)))
            y = clampY(saved?.second ?: (resources.displayMetrics.heightPixels / 2 - dp(28)))
        }
        params = layout
        listOfNotNull<View>(root, stopButton, menuButton, statusLabel)
            .forEach { attachDrag(it, root, layout, manager) }
        primaryButton?.let { attachDrag(it, root, layout, manager) }
        bubble = root
        runCatching { manager.addView(root, layout) }
        handler.postDelayed(fade, FADE_DELAY_MS)
    }

    /** Dragging changes only presentation preferences; the clamped position is persisted off-main. */
    private fun attachDrag(
        handle: View,
        root: View,
        layout: WindowManager.LayoutParams,
        manager: WindowManager,
    ) {
        var downX = 0f
        var downY = 0f
        var originX = 0
        var originY = 0
        var dragging = false
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    wakeBubble()
                    downX = event.rawX; downY = event.rawY
                    originX = layout.x; originY = layout.y; dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (!dragging && kotlin.math.abs(dx) + kotlin.math.abs(dy) < dp(8)) return@setOnTouchListener false
                    dragging = true
                    layout.x = clampX(originX + dx)
                    layout.y = clampY(originY + dy)
                    runCatching { manager.updateViewLayout(root, layout) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) return@setOnTouchListener false
                    val x = layout.x
                    val y = layout.y
                    Thread { runCatching { catalog.setBubblePosition(x, y) } }.start()
                    true
                }
                else -> false
            }
        }
    }

    private fun clampX(value: Int): Int {
        val width = resources.displayMetrics.widthPixels
        return value.coerceIn(0, (width - dp(72)).coerceAtLeast(0))
    }

    private fun clampY(value: Int): Int {
        val height = resources.displayMetrics.heightPixels
        return value.coerceIn(0, (height - dp(72)).coerceAtLeast(0))
    }

    /** Bounded worker projection fenced by lifecycle generation, so stale callbacks cannot paint. */
    private fun projectState() {
        if (destroyed || !projecting.compareAndSet(false, true)) return
        val token = generation.get()
        Thread {
            try {
                runCatching { project(token) }
                    .onFailure { android.util.Log.e("ClusterCastBubble", "projection failed", it) }
            } finally {
                projecting.set(false)
            }
        }.start()
    }

    private fun project(token: Long) {
        val envelope = facade.envelope()
        val durableStop = envelope?.stopRequested == true
        val model = facade.renderModel()
        val projection = facade.bubbleProjection(
            model,
            entries.map { it.row() },
            envelope?.automationConfig?.defaultPackage,
            localStopRequested = localAckActive(durableStop) || durableStop,
            activeTargetPackage = envelope?.stableSession?.activeTarget?.packageName,
        )
        handler.post { applyProjection(projection, token) }
    }

    /**
     * The local acknowledgement latch releases as soon as durable truth owns the Stop or the 500 ms
     * acknowledgement budget expires, so the canonical control can never be pinned disabled.
     */
    private fun localAckActive(durableStop: Boolean): Boolean {
        if (!stopInFlight.get()) return false
        if (durableStop) { stopInFlight.set(false); return false }
        if (android.os.SystemClock.elapsedRealtime() - stopRequestedAtMs > STOP_ACK_DEADLINE_MS) {
            stopInFlight.set(false)
            return false
        }
        return true
    }

    private fun applyProjection(projection: BubbleProjection, token: Long) {
        if (destroyed || token != generation.get() || bubble == null) return
        lastProjection = projection
        stopButton?.apply {
            visibility = if (projection.stop == BubbleStopControl.HIDDEN) View.GONE else View.VISIBLE
            isEnabled = projection.stop == BubbleStopControl.AVAILABLE
            alpha = if (isEnabled) 1f else .65f
            text = projection.stopLabel
            contentDescription = "${projection.stopLabel}. ${projection.status}"
        }
        menuButton?.apply {
            contentDescription = "${projection.menuLabel}. ${projection.contentDescription}"
        }
        statusLabel?.apply {
            text = projection.status
            visibility = if (projection.stop == BubbleStopControl.HIDDEN) View.VISIBLE else View.GONE
            contentDescription = projection.status
        }
        primaryButton?.apply {
            val target = projection.primary
            visibility = if (target == null) View.GONE else View.VISIBLE
            text = target?.let { "Chiếu ${it.label.removeSuffix(" · mặc định")}" }.orEmpty()
            contentDescription = text
            setOnClickListener { target?.let { dispatchTarget(it.packageName) } }
        }
        if (menuPanel != null) renderMenu(projection)
        applyFocusOrder(projection)
        wakeBubble()
    }

    /** Deterministic Stop → apps → settings traversal for keyboard, rotary and TalkBack. */
    private fun applyFocusOrder(projection: BubbleProjection) {
        val views = projection.focusOrder.mapNotNull { target ->
            when (target) {
                BubbleFocusTarget.STOP -> stopButton
                BubbleFocusTarget.APPS -> menuButton
                BubbleFocusTarget.SETTINGS -> menuPanel?.findViewById<View>(SETTINGS_VIEW_ID)
            }
        }
        views.forEachIndexed { index, view ->
            view.isFocusable = true
            view.nextFocusForwardId = views.getOrNull(index + 1)?.id ?: View.NO_ID
        }
    }

    /** One tap renders local acknowledgement immediately and dispatches exactly one typed Stop. */
    private fun requestStopOnce() {
        val button = stopButton ?: return
        if (!button.isEnabled) return
        if (!stopInFlight.compareAndSet(false, true)) return
        stopRequestedAtMs = android.os.SystemClock.elapsedRealtime()
        button.isEnabled = false
        button.alpha = .65f
        button.text = "Đã yêu cầu dừng"
        closeMenu()
        val token = generation.get()
        Thread {
            val accepted = runCatching { facade.requestStop() }.getOrNull()
            handler.post {
                if (destroyed || token != generation.get()) return@post
                if (accepted == null) {
                    stopInFlight.set(false)
                    stopButton?.apply { isEnabled = true; alpha = 1f; text = "Không lưu được Stop" }
                }
                projectState()
            }
        }.start()
    }

    /**
     * Re-reads the app catalogue when the menu is opened.
     *
     * The list was loaded once when the service started and never again, so an app pinned afterwards
     * never appeared: on the vehicle 2026-07-27 the screen showed four starred apps while the menu
     * still said nothing was chosen. Opening the menu is the moment the list is about to be read, and
     * it is operator-initiated, so it is the right place to refresh rather than polling the package
     * manager on a timer.
     */
    private fun reloadEntries() {
        Thread {
            val loaded = runCatching { catalog.installed(runtime.automation.config().defaultPackage) }
                .getOrDefault(emptyList())
            handler.post {
                if (destroyed || loaded.isEmpty()) return@post
                entries = loaded
                lastProjection?.let { if (menuPanel != null) renderMenu(it) }
                handler.post(refresh)
            }
        }.start()
    }

    private fun toggleMenu() {
        wakeBubble()
        if (menuPanel != null) { closeMenu(); return }
        val manager = windowManager ?: return
        reloadEntries()
        val panel = LinearLayout(this).apply {
            id = MENU_VIEW_ID
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(com.byd.clusternav.R.drawable.bubble_panel)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { closeMenu(); true } else false
            }
        }
        val layout = WindowManager.LayoutParams(
            dp(MENU_PANEL_WIDTH_DP),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = params?.x ?: dp(18)
            y = (params?.y ?: dp(160)) + dp(76)
        }
        panel.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) { closeMenu(); true } else false
        }
        menuPanel = panel
        val attached = runCatching { manager.addView(panel, layout) }.isSuccess
        if (!attached) { menuPanel = null; return }
        lastProjection?.let { renderMenu(it) }
        panel.post { if (menuPanel === panel) panel.requestFocus() }
        projectState()
    }

    private fun renderMenu(projection: BubbleProjection) {
        val panel = menuPanel ?: return
        panel.removeAllViews()
        // Stop nằm ĐẦU menu, đúng v0.57. Chỉ hiện khi thực sự có gì để dừng (stop != HIDDEN), và chữ lấy
        // thẳng từ projection để không lệch với màn chính.
        if (projection.stop != BubbleStopControl.HIDDEN) {
            panel.addView(TextView(this).apply {
                text = "⏹ ${projection.stopLabel}"
                textSize = 13f
                setTextColor(Color.WHITE)
                minimumHeight = dp(MENU_MIN_DP)
                isFocusable = true
                isEnabled = projection.stop == BubbleStopControl.AVAILABLE
                alpha = if (isEnabled) 1f else .55f
                contentDescription = "${projection.stopLabel}. ${projection.status}"
                if (projection.stop == BubbleStopControl.AVAILABLE) {
                    setOnClickListener { closeMenu(); requestStopOnce() }
                }
            })
        }
        projection.menu.forEach { item ->
            val entry = entries.firstOrNull { it.packageName == item.packageName }
            panel.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(MENU_MIN_DP)
                isFocusable = true
                isEnabled = item.enabled
                alpha = if (item.enabled) 1f else .55f
                contentDescription = buildString {
                    append(item.label)
                    if (!item.enabled) item.disabledReason?.let {
                        append(". Không khả dụng: ").append(facade.unavailableReason(it))
                    }
                }
                entry?.icon?.let { icon ->
                    addView(ImageView(this@FloatingBubbleService).apply {
                        setImageDrawable(icon)
                        layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
                            .apply { rightMargin = dp(10) }
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    })
                }
                addView(TextView(this@FloatingBubbleService).apply {
                    text = item.label
                    textSize = 13f
                    setTextColor(Color.WHITE)
                })
                if (item.enabled) setOnClickListener { dispatchTarget(item.packageName) }
            })
        }
        if (projection.menu.isEmpty()) {
            panel.addView(TextView(this).apply {
                text = "Chưa chọn app nào — mở Cấu hình để chọn app cho nút nổi"
                textSize = 12f
                setTextColor(Color.WHITE)
            })
        }
        panel.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                .apply { topMargin = dp(6); bottomMargin = dp(6) }
            setBackgroundColor(0x3DFFFFFF)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        panel.addView(TextView(this).apply {
            text = "Về màn chính"
            textSize = 13f
            setTextColor(Color.WHITE)
            minimumHeight = dp(MENU_MIN_DP)
            isFocusable = true
            contentDescription = "Về màn hình chính của xe"
            setOnClickListener { closeMenu(); goHome() }
        })
        panel.addView(TextView(this).apply {
            id = SETTINGS_VIEW_ID
            text = "Cấu hình Cluster Cast"
            textSize = 13f
            setTextColor(Color.WHITE)
            minimumHeight = dp(MENU_MIN_DP)
            isFocusable = true
            contentDescription = "Mở cấu hình Cluster Cast"
            setOnClickListener { closeMenu(); openCastControls() }
        })
    }

    private fun closeMenu() {
        val panel = menuPanel ?: return
        menuPanel = null
        runCatching { windowManager?.removeView(panel) }
        params?.let { layout ->
            layout.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            bubble?.let { view -> runCatching { windowManager?.updateViewLayout(view, layout) } }
        }
    }

    /** Exported canonical action dispatch through the one process runtime and manual-intent owner. */
    private fun dispatchTarget(packageName: String) {
        closeMenu()
        if (!dispatchInFlight.compareAndSet(false, true)) return
        val token = generation.get()
        Thread {
            runCatching {
                facade.runManualIntent(
                    packageName,
                    preferredDensityDpi = catalog.clusterDensityDpi(packageName),
                    clusterStyle = catalog.clusterStyle(packageName),
                )
            }
            dispatchInFlight.set(false)
            handler.post { if (!destroyed && token == generation.get()) projectState() }
        }.start()
    }

    private fun openCastControls() = startActivity(
        Intent(this, ClusterCastActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    )

    private fun bubbleText(value: String, minDp: Int) = TextView(this).apply {
        text = value
        textSize = 12f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        isFocusable = true
        minimumWidth = dp(minDp)
        minimumHeight = dp(minDp)
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }

    private fun notification(): android.app.Notification {
        val channel = "cluster_cast_v2"
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(channel, "Cluster Cast", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, ClusterCastActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        @Suppress("DEPRECATION")
        return android.app.Notification.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Cluster Cast V2")
            .setContentText("Nhấn để mở điều khiển")
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    companion object {
        private val STOP_VIEW_ID = View.generateViewId()
        private val MENU_BUTTON_ID = View.generateViewId()
        private const val NOTIFICATION_ID = 1042
        private const val REFRESH_INTERVAL_MS = 15_000L
        private const val STOP_MIN_DP = 64
        private const val MENU_MIN_DP = 56
        private val MENU_VIEW_ID = View.generateViewId()
        /** A menu wider than this covers the screen it floats over instead of sitting beside it. */
        private const val MENU_PANEL_WIDTH_DP = 300
        private val SETTINGS_VIEW_ID = View.generateViewId()
        internal const val STOP_ACK_DEADLINE_MS = 500L
        private const val IDLE_ALPHA = 0.55f
        private const val ACTIVE_ALPHA = 1.0f
        private const val FADE_DELAY_MS = 3_000L
    }
}

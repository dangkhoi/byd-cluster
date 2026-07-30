package com.byd.clusternav.modules.clustercast

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.byd.clusternav.R
import com.byd.clusternav.modules.clustercast.v2.BubbleProjection
import com.byd.clusternav.cast.platform.CastAndroidLifecycle
import com.byd.clusternav.cast.platform.CastAndroidRuntime
import com.byd.clusternav.cast.platform.CastAppCatalog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Presentation-only overlay host for the canonical Cast model. One circle, one action.
 *
 * v0.72 (docs/specs/cast-simplified-active-app-toggle.html): a single tap does exactly one of two
 * things, decided purely by [BubbleProjection.projecting] — never a menu, never a second gesture.
 * Idle → cast whatever app is genuinely foregrounded on the home display right now (no app picker).
 * Projecting → Stop, the same typed request/dispatch the old Activity used. It renders the same
 * [com.byd.clusternav.modules.clustercast.v2.CastRenderModel] the rest of the app uses and never
 * infers policy from localized text.
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
    private var circle: TextView? = null
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
    /**
     * Vòng tròn đổi hình theo trạng thái chiếu — điểm mấu chốt của v0.57 để "một chạm không nhầm":
     * rỗng viền xanh = cụm đang là đồng hồ, đặc xanh = đang có app trên cụm.
     *
     * Trạng thái vào đây từ [BubbleProjection.projecting] (dữ liệu chiếu), không phải từ cờ RAM hay từ
     * việc dò chữ trong nhãn.
     */
    private fun paintBubble(projecting: Boolean) {
        val view = circle ?: return
        view.text = if (projecting) "▣" else "▢"
        view.setTextColor(if (projecting) Color.WHITE else BRAND)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (projecting) BRAND else BRAND_LIGHT)
            setStroke(dp(2), BRAND)
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Bốn chốt độc lập, mỗi chốt canh ĐÚNG MỘT lượt việc nền. Tên nói rõ nó canh cái gì:
     * [projectionInFlight] KHÔNG phải "cụm đang chiếu" — chỗ đó là [BubbleProjection.projecting], và tên
     * cũ (`projecting`) đứng ngay cạnh `projection.projecting` là một cái bẫy đọc nhầm chờ sẵn.
     */
    private val projectionInFlight = AtomicBoolean(false)

    /**
     * Có ai đó xin vẽ lại trong lúc lượt trước còn chạy.
     *
     * Trước 2026-07-29 lượt xin đó bị NUỐT (`compareAndSet` thất bại là return luôn), mà hai chỗ xin
     * quan trọng nhất lại là "vừa chiếu xong" và "vừa dừng xong". Rơi trúng nhịp 15s là vòng tròn giữ
     * nguyên hình cũ tới 15 giây — và cú chạm kế tiếp làm ĐÚNG CÁI NGƯỢC LẠI với thứ đang ở trên cụm.
     * Gộp nhịp thay vì bỏ nhịp: lượt đang chạy xong thì chạy bù đúng một lượt.
     */
    private val projectionPending = AtomicBoolean(false)
    private val stopInFlight = AtomicBoolean(false)
    private val dispatchInFlight = AtomicBoolean(false)

    /** Một cú chạm đang đọc sự thật. Không có nó, chạm dồn dập là bấy nhiêu vòng I/O ra xe song song. */
    private val tapInFlight = AtomicBoolean(false)
    @Volatile private var stopRequestedAtMs = 0L
    private val generation = AtomicLong(0)
    @Volatile private var destroyed = false

    /** Ghi trên luồng vẽ, đọc trên làn nền lúc chạm — phải `@Volatile` để lần đọc kia không thấy bản cũ. */
    @Volatile private var lastProjection: BubbleProjection? = null
    private var foregroundStarted = false
    private val refresh = object : Runnable {
        override fun run() { projectState(); handler.postDelayed(this, REFRESH_INTERVAL_MS) }
    }

    override fun onCreate() {
        super.onCreate()
        runtime = CastAndroidRuntime.create(applicationContext)
        facade = CastFacade.wrapping(runtime, catalog)
        if (!requestOverlayIfMissing()) { stopSelf(); return }
        if (!startForegroundOnce()) { stopSelf(); return }
        showBubble()
        background("cluster-cast-bubble-rehydrate") {
            runCatching { CastAndroidLifecycle.rehydrate(applicationContext) }
            handler.post { if (!destroyed) handler.post(refresh) }
        }
    }

    /**
     * Một lượt việc nền có TÊN, và nói được là nó có khởi động được hay không.
     *
     * Bốn chỗ trong file này tự `Thread { … }.start()`. Nếu `start()` ném (hết luồng/bộ nhớ) NGAY SAU khi
     * một chốt `compareAndSet` đã được giữ thì chốt đó không bao giờ được nhả — nút nổi im lặng vĩnh viễn
     * cho tới khi khởi động lại app, đúng cái ngõ cụt mà CLAUDE.md §5 bắt phải có đường trả lại. Trả về
     * `false` để chỗ gọi tự hoàn tác chốt của nó.
     */
    private fun background(name: String, block: () -> Unit): Boolean =
        runCatching { Thread(block, name).start() }.isSuccess

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!requestOverlayIfMissing()) { stopSelf(startId); return START_NOT_STICKY }
        if (!startForegroundOnce()) { stopSelf(startId); return START_NOT_STICKY }
        showBubble()
        return START_STICKY
    }

    /** Nút nổi mặc định BẬT (v0.72) — không còn công tắc enable/disable. Thiếu quyền thì tự xin luôn. */
    private fun requestOverlayIfMissing(): Boolean {
        if (Settings.canDrawOverlays(this)) return true
        CastBubbleControl.requestOverlay(this)
        return false
    }

    private fun startForegroundOnce(): Boolean = foregroundStarted || runCatching {
        startForeground(NOTIFICATION_ID, notification())
        foregroundStarted = true
        true
    }.getOrElse {
        android.util.Log.e(TAG, "startForeground denied", it)
        false
    }

    override fun onDestroy() {
        destroyed = true
        generation.incrementAndGet()
        handler.removeCallbacksAndMessages(null)
        bubble?.let { view -> runCatching { windowManager?.removeView(view) } }
        bubble = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showBubble() {
        if (bubble != null) return
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = manager
        // v0.57: vòng tròn 56dp, chữ tượng hình ô-màn-hình, KHÔNG nhãn chữ. Nhãn nằm ở contentDescription
        // để TalkBack và núm xoay vẫn đọc đúng trạng thái thật. v0.72: đây là toàn bộ UI của nút nổi —
        // không còn menu, không còn nút Dừng riêng — một chạm quyết định bởi trạng thái chiếu hiện tại.
        circle = bubbleText("", BUBBLE_SIZE_DP).apply {
            contentDescription = "Cluster Cast"
            gravity = Gravity.CENTER
            textSize = 24f
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(dp(BUBBLE_SIZE_DP), dp(BUBBLE_SIZE_DP))
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onPrimaryTap()
            }
        }
        paintBubble(projecting = false)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.TRANSPARENT)
            addView(circle)
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
        attachDrag(root, root, layout, manager)
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
                    background("cluster-cast-bubble-position") { runCatching { catalog.setBubblePosition(x, y) } }
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
        if (destroyed) return
        if (!projectionInFlight.compareAndSet(false, true)) {
            // Đừng nuốt lượt xin: chạy bù sau khi lượt đang chạy kết thúc (xem [projectionPending]).
            projectionPending.set(true)
            return
        }
        val token = generation.get()
        val started = background("cluster-cast-bubble-projection") {
            try {
                runCatching { project(token) }
                    .onFailure { android.util.Log.e(TAG, "projection failed", it) }
            } finally {
                projectionInFlight.set(false)
                if (projectionPending.compareAndSet(true, false)) {
                    handler.post { if (!destroyed) projectState() }
                }
            }
        }
        if (!started) projectionInFlight.set(false)
    }

    /** Trả về chính bản chiếu vừa dựng, để đường CHẠM quyết định bằng nó thay vì bằng ảnh chụp cũ. */
    private fun project(token: Long): BubbleProjection {
        val envelope = facade.envelope()
        val durableStop = envelope?.stopRequested == true
        val model = facade.renderModel()
        val projection = facade.bubbleProjection(
            model,
            emptyList(),
            envelope?.automationConfig?.defaultPackage,
            localStopRequested = localAckActive(durableStop) || durableStop,
            activeTargetPackage = envelope?.stableSession?.activeTarget?.packageName,
        )
        handler.post { applyProjection(projection, token) }
        return projection
    }

    /**
     * The local acknowledgement latch releases as soon as durable truth owns the Stop or the 500 ms
     * acknowledgement budget expires, so the canonical control can never be pinned disabled.
     */
    private fun localAckActive(durableStop: Boolean): Boolean {
        if (durableStop) { stopInFlight.set(false); return false }
        return stopAckPending()
    }

    /**
     * Một lượt Stop vừa phát và CÒN trong ngân sách xác nhận — không phải một cái chốt sống mãi.
     *
     * Hết hạn thì tự nhả ngay tại đây, nên không có cửa nào để `requestStopOnce()` bị chốt cũ khoá vĩnh
     * viễn (CLAUDE.md §3: không bao giờ gate một đường phục hồi bằng dữ liệu mà chỉ chính đường đó mới
     * làm mới được).
     */
    private fun stopAckPending(): Boolean {
        if (!stopInFlight.get()) return false
        if (android.os.SystemClock.elapsedRealtime() - stopRequestedAtMs > STOP_ACK_DEADLINE_MS) {
            stopInFlight.set(false)
            return false
        }
        return true
    }

    private fun applyProjection(projection: BubbleProjection, token: Long) {
        if (destroyed || token != generation.get() || bubble == null) return
        lastProjection = projection
        circle?.contentDescription = projection.contentDescription
        paintBubble(projection.projecting)
        wakeBubble()
    }

    /**
     * Một chạm, không cử chỉ ẩn nào khác (dự án cấm long-press/double-tap ẩn — xem
     * `CastAccessibilityTest`): đang rảnh (đồng hồ) → chiếu đúng app đang mở trên màn chính, không cần
     * chọn từ danh sách. Đang chiếu → về đồng hồ, tái dùng nguyên luồng Stop đã có. Không xác định được
     * app đang mở → báo ngắn, không đoán mò, không mở gì thêm (docs/specs/cast-simplified-active-app-toggle.html).
     */
    private fun onPrimaryTap() {
        // Đang có lượt chạy dở thì NÓI ra, đừng nuốt cú chạm: nút không phản hồi là thứ khiến người lái
        // bấm dồn thêm trong lúc xe đang chạy.
        if (dispatchInFlight.get() || stopAckPending()) { toast("Đang xử lý…"); return }
        if (!tapInFlight.compareAndSet(false, true)) return
        val token = generation.get()
        val started = background("cluster-cast-bubble-tap") {
            try {
                // CLAUDE.md §5: "Cấm quyết định bằng cờ RAM. Kiểm bằng sự thật. Cờ chỉ để hiển thị."
                // `lastProjection` chỉ được làm mới mỗi 15s, mà từ v0.72 cụm còn được đổi từ panel Home
                // và từ tự-chiếu-lúc-khởi-động — tức nó có thể đi sau sự thật cả 15 giây. Quyết định
                // Dừng-hay-Chiếu bằng ảnh chụp đó nghĩa là cú chạm có thể làm ĐÚNG CÁI NGƯỢC LẠI: đang
                // chiếu mà lại phát một lượt cold-cast mới lên cụm đồng hồ lúc xe đang lăn bánh.
                // Đọc lại đúng một lần ngay tại đây; [project] cũng vẽ lại vòng tròn nên hình và hành vi
                // luôn cùng một sự thật. Không đọc được thì lùi về ảnh chụp — vẫn tốt hơn không làm gì.
                val fresh = runCatching { project(token) }
                    .onFailure { android.util.Log.e(TAG, "tap projection failed", it) }
                    .getOrNull()
                if (fresh?.projecting ?: (lastProjection?.projecting == true)) {
                    handler.post { if (!destroyed) requestStopOnce() }
                    return@background
                }
                if (foreignMutationInFlight()) {
                    handler.post { if (!destroyed) toast("Đang có thao tác khác chạy — thử lại") }
                    return@background
                }
                val excluded = setOfNotNull(packageName, homePackage())
                val foreground = runCatching { facade.foregroundPackage(HOME_DISPLAY_ID, excluded) }
                    .onFailure { android.util.Log.e(TAG, "foreground read failed", it) }
                    .getOrNull()
                handler.post {
                    if (destroyed) return@post
                    if (foreground != null) {
                        dispatchTarget(foreground)
                    } else {
                        toast("Không xác định được app đang mở")
                    }
                }
            } finally {
                tapInFlight.set(false)
            }
        }
        if (!started) tapInFlight.set(false)
    }

    /**
     * Một transaction của bề mặt KHÁC đang chạy (panel Home, tự-chiếu-lúc-khởi-động, watchdog).
     *
     * Vì sao nút nổi phải tự hỏi câu này thay vì cứ phát rồi để tầng dưới từ chối: khi có transaction,
     * `CastManualIntentRunner.run` KHÔNG từ chối — nó XẾP HÀNG, và `queueLatestTarget` ghi một
     * `pendingIntent` gốc USER (`CastModels.withPendingPackage` mặc định `CastIntentOrigin.USER`) vào
     * store BỀN. `CastSessionStore.initializeForBoot` CỐ Ý giữ lại pendingIntent gốc USER qua mọi lần
     * khởi động, và `CastAutomationService.evaluate` terminalize yêu cầu tự-chiếu-lúc-khởi-động thành
     * `USER_SUPERSEDED` khi thấy nó ⇒ MỘT cú chạm rơi trúng lúc bận sẽ tắt tự-chiếu-khi-khởi-động (R7)
     * của MỌI lần khởi động sau đó.
     *
     * Chỗ rút hàng đợi duy nhất là `MainActivityCastController.drainPendingTarget()`, chạy lúc MỞ màn
     * Home. Nút nổi là một service sống mãi — nó không có khoảnh khắc "mở app" nào để rút, và rút trên
     * nhịp hẹn giờ thì thành một cú chiếu bất ngờ lúc nổ máy. Vậy nên nút nổi không được phép TẠO ra
     * hàng đợi: một chạm là một hành động ngay, hoặc là một câu từ chối đọc được.
     *
     * Đây là cửa lịch sự, KHÔNG phải guard cứng: cửa cứng vẫn nằm ở tầng thi hành (`CastExecutor`
     * kiểm lại `transaction == null` trong chính `store.locked`). Còn lại một khe TOCTOU vài mili giây
     * — chấp nhận được, vì hậu quả khe đó đúng bằng hành vi hôm nay.
     */
    private fun foreignMutationInFlight(): Boolean = runCatching { facade.envelope()?.transaction != null }
        .onFailure { android.util.Log.e(TAG, "durable read failed", it) }
        .getOrDefault(false)

    /** Gói launcher hiện tại, dò qua Intent chuẩn — không hardcode tên launcher (khác theo đời xe/ROM). */
    private fun homePackage(): String? = runCatching {
        packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
    }.getOrNull()

    /** Exported canonical action dispatch through the one process runtime and manual-intent owner. */
    private fun dispatchTarget(packageName: String) {
        if (!dispatchInFlight.compareAndSet(false, true)) return
        // v0.57 báo ngay "đang chiếu…" vì lệnh mất vài giây; không có nó thì chạm xong tưởng máy treo.
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString() }
            .getOrDefault(packageName)
        toast("Chiếu $label…")
        val token = generation.get()
        val started = background("cluster-cast-bubble-dispatch") {
            // Kết cục PHẢI nói ra. Bản trước `runCatching { … }` rồi bỏ cả kết quả lẫn lỗi: chiếu hỏng thì
            // người lái chỉ thấy "Chiếu X…" rồi cụm không đổi gì, không một dòng log. Dùng đúng câu mà
            // panel Home dùng cho cùng kết cục (`statusMessage()`), không tự viết bộ chữ thứ hai.
            val message = runCatching {
                facade.runManualIntent(
                    packageName,
                    preferredDensityDpi = catalog.clusterDensityDpi(packageName),
                    clusterStyle = catalog.clusterStyle(packageName),
                ).statusMessage()
            }.onFailure { android.util.Log.e(TAG, "cast dispatch failed", it) }
                .getOrElse { "Không chiếu được $label" }
            dispatchInFlight.set(false)
            handler.post {
                if (destroyed || token != generation.get()) return@post
                toast(message)
                projectState()
            }
        }
        if (!started) dispatchInFlight.set(false)
    }

    /**
     * One tap renders local acknowledgement immediately and dispatches exactly one typed Stop.
     *
     * The durable Stop request only journals `stopRequested=true` and fences any in-flight mutation --
     * it never tears anything down by itself. Once the request is accepted with no in-flight
     * transaction to wait out, continue exactly like the old Activity's Stop button did.
     */
    private fun requestStopOnce() {
        // Một lượt Stop đang thật sự chạy trong ngân sách thì thôi; quá hạn thì [stopAckPending] tự nhả
        // chốt ngay tại đây, nếu không `compareAndSet` bên dưới nuốt cú chạm mà không báo gì.
        if (stopAckPending()) return
        // Ghi mốc TRƯỚC khi giữ chốt: ai thấy chốt đang giữ thì cũng thấy mốc của đúng lượt đó, không
        // phải mốc cũ còn sót. Ngược lại thì làn vẽ có thể coi lượt vừa phát là đã quá hạn và nhả sớm.
        stopRequestedAtMs = android.os.SystemClock.elapsedRealtime()
        if (!stopInFlight.compareAndSet(false, true)) return
        val token = generation.get()
        val started = background("cluster-cast-bubble-stop") {
            val accepted = runCatching { facade.requestStop() }.getOrNull()
            var dispatchFailed = false
            var outcome: String? = null
            if (accepted != null && accepted.transaction == null) {
                outcome = runCatching { continueStopAfterAcknowledgement() }
                    .onFailure { android.util.Log.e(TAG, "stop dispatch failed", it); dispatchFailed = true }
                    .getOrElse { "Dừng chiếu thất bại" }
            }
            handler.post {
                if (destroyed || token != generation.get()) return@post
                if (accepted == null) {
                    stopInFlight.set(false)
                    toast("Không lưu được Stop")
                } else {
                    // Phát lệnh dọn dẹp hỏng thì chốt cục bộ phải nhả NGAY: giữ nó là khoá luôn cú chạm
                    // kế tiếp trong khi cụm vẫn còn app trên đó.
                    if (dispatchFailed) stopInFlight.set(false)
                    // Kết cục nói ra bằng đúng câu panel Home dùng cho cùng đường Stop.
                    outcome?.let { toast(it) }
                }
                projectState()
            }
        }
        if (!started) stopInFlight.set(false)
    }

    /**
     * Builds and executes the actual Stop plan once the request is durably acknowledged and no
     * older mutation is still in flight. Delegates to the one shared implementation in
     * [CastFacade.continueStopAfterAcknowledgement] so the raw mutation call lives in exactly one
     * place instead of being copied per surface.
     */
    private fun continueStopAfterAcknowledgement(): String =
        facade.continueStopAfterAcknowledgement { pkg -> catalog.evidence(pkg, facade.phoneSession(pkg)) }

    /** Chỉ gọi trên luồng vẽ. `runCatching` vì Toast có thể bị chặn bởi cài đặt thông báo của xe. */
    private fun toast(message: String) {
        runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

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
            this, 0, Intent(this, com.byd.clusternav.MainActivity::class.java),
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
        private const val TAG = "ClusterCastBubble"
        private const val NOTIFICATION_ID = 1042
        /** Xe này luôn dùng display 0 cho màn chính (đã xác nhận qua nhiều dump thật — xem CastAmStackForegroundTest). */
        private const val HOME_DISPLAY_ID = 0
        private const val REFRESH_INTERVAL_MS = 15_000L
        private const val BUBBLE_SIZE_DP = 56
        internal const val STOP_ACK_DEADLINE_MS = 500L
        // Giá trị v0.57 đã chạy ngoài xe: mờ hẳn lúc rảnh để không đè map, 2,5s là đủ lâu để nhìn thấy.
        private const val IDLE_ALPHA = 0.35f
        private const val ACTIVE_ALPHA = 1.0f
        private const val FADE_DELAY_MS = 2_500L
        private val BRAND = 0xFF1565C0.toInt()
        private val BRAND_LIGHT = 0xFFE6F1FB.toInt()
    }
}

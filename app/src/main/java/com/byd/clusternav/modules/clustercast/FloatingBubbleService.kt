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
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.byd.clusternav.Lang
import com.byd.clusternav.R
import com.byd.clusternav.modules.clustercast.v2.BubbleProjection
import com.byd.clusternav.modules.clustercast.v2.BubbleZone
import com.byd.clusternav.modules.clustercast.v2.CastBubbleProjection
import com.byd.clusternav.cast.platform.CastAndroidLifecycle
import com.byd.clusternav.cast.platform.CastAndroidRuntime
import com.byd.clusternav.cast.platform.CastAppCatalog
import com.byd.clusternav.modules.clustercast.simplified.AppMover
import com.byd.clusternav.modules.clustercast.simplified.SimpleCastIntent
import com.byd.clusternav.modules.clustercast.simplified.SimpleCastRuntime
import com.byd.clusternav.modules.clustercast.simplified.SimpleCastState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Presentation-only overlay host for the canonical Cast model. Ba ô, mỗi ô một chạm.
 *
 * v0.73 (docs/specs/cast-one-mode-and-three-zone-bubble.html §R7): nút nổi là BẢN ĐỒ của cụm — ô rộng
 * phía trên là cả cụm, hai ô dưới là nửa trái và nửa phải. Luật đúng một dòng, không ngoại lệ: ô có
 * app ⇒ chạm là TRẢ app đó về màn chính; ô trống ⇒ chạm là CHIẾU app đang mở vào ô đó. Không menu,
 * không "đổi app", không một cử chỉ ẩn nào (`CastAccessibilityTest` khoá đúng điều đó).
 *
 * Giữ nguyên từ v0.72 (docs/specs/cast-simplified-active-app-toggle.html) — vì nó đã chạy tốt ngoài xe
 * và CLAUDE.md §6 cấm đảo lại đường đang chạy tốt: ô cả-cụm quyết định Dừng-hay-Chiếu bằng
 * [BubbleProjection.stopOnTap] đọc lại tươi ngay lúc chạm, chiếu thì tự dò app đang mở trên màn chính
 * (không có màn chọn app), còn Dừng thì đi đúng hai bước request/fence rồi mới phát lệnh dọn.
 *
 * Bề mặt này chỉ RENDER và DISPATCH: mọi trạng thái nó vẽ đều là trường có kiểu trong
 * [BubbleProjection] (kể cả trạng thái từng ô — [BubbleProjection.zones]), không có chỗ nào dò chuỗi
 * đã bản địa hoá để đoán ra trạng thái.
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

    /**
     * Tỉ lệ chia đôi cụm mà người lái đã chọn ở panel Home (50-50 / 30-70 / 70-30, mặc định 50-50).
     *
     * Đọc TẠI LÚC PHÁT LỆNH chứ không giữ một bản sao trong RAM: người lái có thể đổi tỉ lệ ở Home
     * trong lúc nút nổi đang sống (service này chạy suốt, không có "lần mở màn hình" nào để nạp lại) —
     * một bản sao lấy lúc khởi động sẽ lặng lẽ chiếu sai tỉ lệ so với thứ đang hiện trên màn hình.
     */
    private val splitRatio: CastSplitRatioStore by lazy { CastSplitRatioStore(applicationContext) }
    private var windowManager: WindowManager? = null
    private var bubble: LinearLayout? = null
    /**
     * Ba ô của bản đồ, tra được theo [BubbleZone] — không phải ba biến rời.
     *
     * Cứ mỗi ô một biến thì mỗi lần vẽ lại là ba dòng gần giống nhau, và cái thứ tư (nếu §R6 sau này
     * thêm tỉ lệ khác) sẽ được thêm vào hai trong ba chỗ. Ở đây vẽ lại là một vòng lặp trên chính tập
     * ô mà projection xuất ra.
     */
    private val zoneViews = LinkedHashMap<BubbleZone, TextView>()
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
     * Một ô đổi hình theo trạng thái CỦA CHÍNH NÓ — kế thừa nguyên tắc "một chạm không nhầm" của v0.57,
     * chỉ là nay có ba ô: rỗng viền xanh = ô đó đang trống, đặc xanh = ô đó đang có app.
     *
     * Trạng thái vào đây từ ô tương ứng trong [BubbleProjection.zones] (dữ liệu CÓ KIỂU), không phải từ
     * cờ RAM hay từ việc dò chữ trong nhãn. Nhận cả bản chiếu rồi tự hỏi ô của mình, thay vì nhận sẵn
     * một ô: tầng view chỉ cần biết ĐÚNG HAI kiểu của tầng dưới (bản chiếu và mã ô), phần còn lại là
     * hình dạng nội bộ của bản chiếu — xem bánh răng `CastArchitectureRatchetTest`.
     *
     * `projection == null` = chưa có lượt chiếu nào về tới nơi (lần vẽ đầu tiên) ⇒ vẽ như ô trống và
     * KHÔNG làm mờ, vì "chưa biết" không phải là "không dùng được".
     */
    private fun paintZone(zone: BubbleZone, view: TextView, projection: BubbleProjection?) {
        val cell = projection?.zone(zone)
        val occupied = cell?.occupied == true
        view.setTextColor(if (occupied) Color.WHITE else BRAND)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ZONE_CORNER_DP).toFloat()
            setColor(if (occupied) BRAND else BRAND_LIGHT)
            setStroke(dp(ZONE_STROKE_DP), BRAND)
        }
        // Ô không chạm được thì MỜ đi, nhưng vẫn nhận chạm: chạm vào phải nói ra được vì sao không
        // dùng được (xem [onZoneTap]). Một ô câm lặng là thứ khiến người lái bấm thêm mấy lần nữa
        // trong lúc xe đang chạy — và `setEnabled(false)` còn đẩy cú chạm ra khỏi cả đường kéo.
        view.alpha = if (cell == null || cell.enabled) 1f else DISABLED_ZONE_ALPHA
        cell?.let { view.contentDescription = it.contentDescription }
    }

    /**
     * Vẽ lại CẢ bản đồ từ một bản chiếu.
     *
     * Duyệt trên [zoneViews] chứ không gọi tay ba lần: ô nào có trong cây thì ô đó được vẽ lại, nên
     * không có đường nào để một ô bị bỏ quên lại vẫn hiện ra với trạng thái của lần chiếu trước.
     */
    private fun paintZones(projection: BubbleProjection) {
        zoneViews.forEach { (zone, view) -> paintZone(zone, view, projection) }
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
        // Bỏ luôn tham chiếu tới ba ô: giữ lại là giữ nguyên một cây view đã gỡ khỏi cửa sổ (và cùng
        // với nó là cả Context của service) sống trong bộ nhớ tới lần khởi động sau.
        zoneViews.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showBubble() {
        if (bubble != null) return
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = manager
        // v0.73 §R7: BA vùng chạm xếp thành bản đồ cụm — một ô rộng phía trên (cả cụm) và hai ô nhỏ
        // bên dưới (nửa trái, nửa phải). Nhãn hiển thị cố ý rất ngắn ("Cả cụm"/"Trái"/"Phải") vì thứ
        // phải đọc được lúc lái là HÌNH (đặc/rỗng), không phải chữ; trạng thái đầy đủ nằm ở
        // contentDescription của từng ô cho TalkBack và núm xoay.
        //
        // Bề rộng ô full = hai nửa cộng khe giữa, TÍNH ra chứ không chép tay: đổi HALF_ZONE_WIDTH_DP là
        // cả nút tự đúng, không có con số cộng sẵn nào âm thầm sai như ba hằng số đã phải gỡ hôm 31/7.
        val zoneHeight = dp(BUBBLE_SIZE_DP)
        val halfWidth = dp(HALF_ZONE_WIDTH_DP)
        val gap = dp(ZONE_GAP_DP)
        val zoneFull = zoneView(BubbleZone.FULL, halfWidth * 2 + gap, zoneHeight, leftMarginPx = 0)
        val zoneHalves = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(zoneView(BubbleZone.LEFT, halfWidth, zoneHeight, leftMarginPx = 0))
            addView(zoneView(BubbleZone.RIGHT, halfWidth, zoneHeight, leftMarginPx = gap))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = gap }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 2026-08-01: bỏ khung viền xung quanh — chỉ 3 nút, không bọc ngoài.
            // Ba ô tự có góc bo + stroke riêng nên không cần container bọc thêm.
            setPadding(0, 0, 0, 0)
            contentDescription = "Cluster Cast"
            addView(zoneFull)
            addView(zoneHalves)
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val saved = catalog.bubblePosition()
        // Đo TRƯỚC khi tính chỗ mọc và trước khi kẹp biên: lúc này view chưa được WindowManager gắn nên
        // `width`/`height` còn bằng 0, mà cả hai phép tính dưới đây đều cần bề rộng THẬT của nút nổi
        // (xem [bubbleWidthPx]). Không đo thì chúng lại phải chép tay một con số theo kích cụm — đúng
        // cái bẫy vừa gỡ ở [clampX].
        measureBubble(root)
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = IDLE_ALPHA
            // Mặc định mọc ở mép phải, chừa một lề nhỏ, và căn giữa theo chiều dọc — nằm ngoài phần nội
            // dung của chính màn hình thay vì đè lên thẻ trạng thái.
            //
            // Hai số cũ (`dp(84)` và `dp(28)`) là dp(56)+dp(28) và dp(56)/2 CỘNG SẴN cho một nút nổi
            // vuông 56dp: đổi kích thước nút là chúng sai lặng lẽ — nút mọc lấn ra ngoài mép phải hoặc
            // lệch tâm, mà không có gì báo. Giờ chúng được TÍNH từ bề rộng/bề cao đo được.
            x = clampX(saved?.first ?: (resources.displayMetrics.widthPixels - bubbleWidthPx(root) - dp(EDGE_MARGIN_DP)), root)
            y = clampY(saved?.second ?: ((resources.displayMetrics.heightPixels - bubbleHeightPx(root)) / 2), root)
        }
        params = layout
        attachDragToEveryTouchSurface(root, root, layout, manager)
        bubble = root
        runCatching { manager.addView(root, layout) }
        handler.postDelayed(fade, FADE_DELAY_MS)
    }

    /**
     * Một ô của bản đồ: nhãn ngắn, vùng chạm đủ lớn, và một cú chạm đi thẳng về [onZoneTap].
     *
     * Ô tự ghi mình vào [zoneViews] ngay tại đây thay vì để chỗ gọi nhớ ghi: thêm một ô mà quên đăng ký
     * thì nó vẫn hiện ra, vẫn bấm được, nhưng KHÔNG BAO GIỜ được vẽ lại — đúng kiểu lỗi im lặng mà
     * CLAUDE.md §8 nói (compile xanh không có nghĩa là code chạy).
     *
     * Mỗi ô cao [BUBBLE_SIZE_DP] = 56dp, trên ngưỡng 48dp của một vùng chạm dùng được khi xe đang chạy
     * (§R7). `minimumWidth`/`minimumHeight` của [bubbleText] giữ ngưỡng đó ngay cả khi ai đó chỉnh
     * `layoutParams` nhỏ lại.
     */
    private fun zoneView(zone: BubbleZone, widthPx: Int, heightPx: Int, leftMarginPx: Int): TextView =
        bubbleText(CastBubbleProjection.zoneShortLabel(zone), BUBBLE_SIZE_DP).apply {
            gravity = Gravity.CENTER
            textSize = ZONE_TEXT_SP
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(widthPx, heightPx).apply { leftMargin = leftMarginPx }
            contentDescription = CastBubbleProjection.zoneShortLabel(zone)
            paintZone(zone, this, null)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onZoneTap(zone)
            }
            zoneViews[zone] = this
        }

    /**
     * Gắn cử chỉ KÉO lên MỌI view trong cây của nút nổi, kể cả view đang giữ `setOnClickListener`.
     *
     * Khoá lại lỗi thật của commit 9d70f62 (2026-07-30): bản đó gắn cử chỉ kéo lên đúng MỘT chỗ — khung
     * `LinearLayout` bọc ngoài (`attachDrag(root, root, …)`) — trong khi đứa con duy nhất của khung thời
     * đó (vòng tròn 56dp) rộng đúng bằng khung (`dp(BUBBLE_SIZE_DP)` × `dp(BUBBLE_SIZE_DP)`, không lề)
     * và có `setOnClickListener` nên cờ `CLICKABLE` bật. Chuỗi điều phối chạm của AOSP
     * (`android-10.0.0_r47`, đã đọc source, không phải trí nhớ — CLAUDE.md §3) làm cho listener của khung
     * KHÔNG BAO GIỜ chạy:
     *
     *  1. `View.onTouchEvent` vào nhánh `if (clickable …)` và trả `true` cho ACTION_DOWN
     *     (`View.java:14779`, `View.java:14958`);
     *  2. nên `View.dispatchTouchEvent` của đứa con trả `true` (`View.java:13430`);
     *  3. nên khung ghi đứa con thành touch target (`addTouchTarget`, `ViewGroup.java:2714-2715`);
     *  4. mà khung chỉ tự xử lý khi `mFirstTouchTarget == null` (`ViewGroup.java:2739-2742`, gọi
     *     `super.dispatchTouchEvent` qua `ViewGroup.java:3027-3028`) — tức chỉ khi KHÔNG đứa con nào
     *     nhận. Nhánh ACTION_MOVE của khung thành code chết, và vì đứa con phủ 100% khung nên không còn
     *     một pixel nào của khung để nắm.
     *
     * Hậu quả ngoài xe: nút nổi ĐỨNG YÊN, không kéo ra khỏi chỗ nó đang che được. Trước 9d70f62 cử chỉ
     * kéo được gắn thẳng lên chính các nút bấm (`forEach { attachDrag(it, root, …) }`), tức CÙNG một
     * view giữ cả hai listener — và nó chạy tốt ngoài xe suốt các bản trước đó.
     *
     * Nên đường sửa không phải "chừa một mép trống của khung để nắm" (mép đó biến mất ngay lần đổi layout
     * kế tiếp): view nào cũng có listener kéo, view nào thắng touch target thì view đó phân xử. Không giả
     * định nút nổi có đúng một con, cũng không giả định con đó vuông 56dp — thêm view vào cây là tự động
     * kéo được.
     *
     * Bản 3 ô (v0.73) là phép thử đầu tiên của lời giải đó và nó đi qua mà KHÔNG phải sửa gì ở đây: cây
     * view giờ là khung ngoài → ô cả-cụm + hàng hai nửa → hai ô nửa, phép duyệt tự chạm tới cả năm view,
     * ba ô có `setOnClickListener` vẫn kéo được như thường.
     *
     * Lưu ý cho bản sau: nếu có ngày thêm một vùng cuộn/kéo-thanh-trượt vào cây, vùng đó phải được LOẠI
     * khỏi phép duyệt này, vì ở đây cử chỉ kéo bị nuốt trọn (xem [attachDrag]).
     */
    private fun attachDragToEveryTouchSurface(
        view: View,
        root: View,
        layout: WindowManager.LayoutParams,
        manager: WindowManager,
    ) {
        attachDrag(view, root, layout, manager)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                attachDragToEveryTouchSurface(view.getChildAt(index), root, layout, manager)
            }
        }
    }

    /**
     * Một view, MỘT trọng tài cho cả chạm lẫn kéo.
     *
     * Listener này nuốt trọn cử chỉ (trả `true` ngay từ ACTION_DOWN) nên `View.onTouchEvent` không chạy
     * lần nào (`View.java:13424-13432`: listener trả `true` thì `onTouchEvent` bị bỏ qua). Đó là điều
     * kiện để KHÔNG có hai đường bắn click: nếu để ACTION_DOWN rơi xuống `onTouchEvent`, view vào trạng
     * thái pressed và ACTION_UP sẽ tự bắn click (`View.java:14795` trở đi) — kéo xong nhả tay là cụm đổi
     * trạng thái. Trên xe đang lăn bánh, "kéo nút cho đỡ vướng" mà hoá ra dừng chiếu hoặc phát một lượt
     * chiếu mới là đúng loại bất ngờ mà CLAUDE.md cấm.
     *
     * Vậy nên cú chạm được phát lại BẰNG TAY ở ACTION_UP, và chỉ khi ngón tay chưa bao giờ vượt ngưỡng
     * trượt. `performClick()` là đúng đường phát: nó gọi `OnClickListener` và bắn kèm
     * `TYPE_VIEW_CLICKED` cho trình đọc màn hình (`View.java:7131-7151`), nên TalkBack/núm xoay không
     * mất gì. Không dùng cử chỉ giữ-lâu ở đây — dự án cố ý không có cử chỉ ẩn nào (xem
     * `CastAccessibilityTest`).
     */
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
        // Ngưỡng trượt lấy từ hệ thống, không phải một số dp chép tay: đây đúng là con số mà chính
        // `View` dùng để phân xử "ngón tay còn nằm trên nút hay đã trượt đi"
        // (`View.java:5059` gán `mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop()`,
        // dùng ở `View.java:14909`/`:14932`). Dùng chung một thước đo thì cảm giác chạm của nút nổi
        // giống hệt mọi nút khác của máy, trên mọi mật độ màn của mọi đời xe.
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        handle.setOnTouchListener { touched, event ->
            // `actionMasked` chứ không phải `action`: khi ngón thứ hai chạm vào, `action` mang thêm chỉ
            // số ngón ở byte cao (ACTION_POINTER_DOWN) nên phép so sánh thẳng trượt hết mọi nhánh.
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    wakeBubble()
                    downX = event.rawX; downY = event.rawY
                    originX = layout.x; originY = layout.y; dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    // So sánh bình phương để khỏi `sqrt`; đây là khoảng cách thật (Euclid), không phải
                    // tổng hai trục — tổng hai trục cho phép đi xa hơn ngưỡng theo đường chéo.
                    if (!dragging && dx * dx + dy * dy <= slop * slop) return@setOnTouchListener true
                    dragging = true
                    layout.x = clampX(originX + dx, root)
                    layout.y = clampY(originY + dy, root)
                    runCatching { manager.updateViewLayout(root, layout) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val x = layout.x
                        val y = layout.y
                        background("cluster-cast-bubble-position") { runCatching { catalog.setBubblePosition(x, y) } }
                    } else if (event.actionMasked == MotionEvent.ACTION_UP && touched.isClickable) {
                        // Chưa từng thành cú kéo ⇒ đây là một CÚ CHẠM. Chỉ phát trên view thật sự có
                        // hành động; view nền (khung ngoài và hàng chứa hai ô nửa cụm) không có gì để
                        // bắn nên im lặng, không bắn sự kiện click rỗng.
                        touched.performClick()
                    }
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Đo nút nổi khi nó chưa được gắn vào cửa sổ, để [bubbleWidthPx]/[bubbleHeightPx] có số thật ngay
     * từ lần mọc đầu tiên. `UNSPECIFIED` cả hai chiều = "cứ lấy kích thước tự nhiên của mày", đúng với
     * `WRAP_CONTENT` mà cửa sổ này khai.
     */
    private fun measureBubble(view: View) {
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        runCatching { view.measure(unspecified, unspecified) }
    }

    /**
     * Bề rộng/bề cao THẬT của nút nổi tính bằng pixel.
     *
     * Trước 2026-07-31 phép kẹp biên chép tay `dp(72)` cho cả hai chiều, không liên quan gì tới
     * [BUBBLE_SIZE_DP]: với nút vuông 56dp thì nó thừa 16dp (nút không bao giờ chạm được mép phải), còn
     * với hàng nhiều nút của bản trước 9d70f62 thì nó THIẾU — nút kéo lọt ra ngoài mép và phần lọt ra
     * không bấm lại được. Cả hai kiểu sai đều im lặng. Lấy số đo thật thì bản 3 ô (v0.73, rộng ≈158dp và
     * cao ≈130dp) tự đúng — đã kiểm: nó lên mà không phải sửa một hằng số nào ở đây.
     *
     * Thứ tự lùi: khung đã bố trí (`width`) → khung mới đo (`measuredWidth`) → cạnh vuông mặc định. Bậc
     * cuối chỉ là lưới an toàn cho trường hợp `measure` ném; nó không bao giờ nên là bậc được dùng.
     */
    private fun bubbleWidthPx(view: View? = bubble): Int =
        view?.width?.takeIf { it > 0 } ?: view?.measuredWidth?.takeIf { it > 0 } ?: dp(BUBBLE_SIZE_DP)

    private fun bubbleHeightPx(view: View? = bubble): Int =
        view?.height?.takeIf { it > 0 } ?: view?.measuredHeight?.takeIf { it > 0 } ?: dp(BUBBLE_SIZE_DP)

    /** Giữ trọn nút nổi trong màn hình: mép trái ≥ 0, mép phải ≤ bề rộng màn (xem [bubbleWidthPx]). */
    private fun clampX(value: Int, view: View? = bubble): Int {
        val width = resources.displayMetrics.widthPixels
        return value.coerceIn(0, (width - bubbleWidthPx(view)).coerceAtLeast(0))
    }

    private fun clampY(value: Int, view: View? = bubble): Int {
        val height = resources.displayMetrics.heightPixels
        return value.coerceIn(0, (height - bubbleHeightPx(view)).coerceAtLeast(0))
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

    /**
     * Trả về chính bản chiếu vừa dựng, để đường CHẠM quyết định bằng nó thay vì bằng ảnh chụp cũ.
     *
     * 2026-08-01: cả phần ghép (đọc bền + quan sát + occupancy theo ô) chuyển xuống façade — xem KDoc
     * [CastFacade.bubbleProjection]. Bề mặt chỉ còn giữ đúng thứ thuộc về nó: cái chốt xác nhận Stop cục
     * bộ trong RAM của cửa sổ này. Nhờ vậy occupancy ĐO ĐƯỢC theo ô và mô hình hiển thị dùng CHUNG một
     * lần quan sát, thay vì hai lần đọc có thể lệch nhau trong cùng một lần vẽ.
     */
    private fun project(token: Long): BubbleProjection {
        val projection = facade.bubbleProjection { durableStop -> localAckActive(durableStop) }
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
        bubble?.contentDescription = projection.contentDescription
        paintZones(projection)
        wakeBubble()
    }

    /**
     * Cửa vào DUY NHẤT của mọi cú chạm trên nút nổi, và là chỗ luật §R7 được thi hành:
     * ô có app ⇒ trả về, ô trống ⇒ chiếu, ô không dùng được ⇒ NÓI RA vì sao.
     *
     * Cửa từ chối đọc [lastProjection] — một ảnh chụp có thể cũ tới 15 giây — nên nó chỉ được phép
     * TỪ CHỐI kèm lời giải thích, không bao giờ được phép tự quyết định làm gì (CLAUDE.md §5: cấm
     * quyết định bằng ảnh chụp cũ). Quyết định thật nằm sau một lần đọc lại sự thật, ở [onPrimaryTap]
     * cho ô cả cụm và ở [dispatchHalfZone] cho hai nửa — và cả hai đều hỏi lại chính cửa này trên bản
     * chiếu TƯƠI, nên một ô vừa chuyển sang "không dùng được" trong 15 giây qua vẫn được chặn đúng.
     *
     * Từ 2026-08-01 occupancy theo ô là ĐO ĐƯỢC ([CastFacade.bubbleProjection] → `ClusterZoneReading`),
     * nên cửa này thật sự đóng trong hai ca bố cục: cụm đang bị chiếm trọn (hai nửa câm) và hai nửa
     * đang có app (ô cả cụm câm, vì chiếu full sẽ nuốt chúng — đo 31/7). Ca một-app-toàn-cụm đi qua
     * đúng như v0.72: ô cả cụm vẫn Dừng-hay-Chiếu bằng [BubbleProjection.stopOnTap].
     */
    private fun onZoneTap(zone: BubbleZone) {
        val cell = lastProjection?.zone(zone)
        if (cell != null && !cell.enabled) { toast(cell.message); return }
        if (zone == BubbleZone.FULL) { onPrimaryTap(); return }
        dispatchHalfZone(zone)
    }

    /**
     * Đặt app vào / trả app khỏi MỘT NỬA cụm — cùng một luật với ô cả cụm, chỉ khác cái ô.
     *
     * Hình dạng của hàm này là BẢN SAO CÓ CHỦ Ý của [onPrimaryTap], không phải một đường thứ hai nghĩ
     * lại từ đầu, vì mọi bài học đắt tiền của đường kia đều áp nguyên vào đây:
     *
     *  - **Đọc lại sự thật ngay lúc chạm.** `lastProjection` chỉ tươi tối đa 15 giây, mà cụm còn bị đổi
     *    từ panel Home, từ tự-chiếu-lúc-khởi-động và từ chính app trên xe. Quyết định "trả hay chiếu"
     *    bằng ảnh chụp đó nghĩa là cú chạm có thể làm ĐÚNG CÁI NGƯỢC LẠI — chiếu đè lên một nửa vừa có
     *    app (đo 31/7: rect chồng nhau thì app này che hẳn app kia, không có cơ chế tự thu nhỏ nào).
     *    CLAUDE.md §5: "Cấm quyết định bằng cờ RAM. Kiểm bằng sự thật."
     *  - **Hỏi HÀNH ĐỘNG, không hỏi màu tô** ([CastFacade.zoneReturnsOnTap]) — xem KDoc ở đó.
     *  - **Không tạo hàng đợi bền** ([foreignMutationInFlight]): một `pendingIntent` gốc USER chỉ mang
     *    được TÊN GÓI qua tầng bền, nên yêu cầu-theo-ô mà bị xếp hàng sẽ sống lại thành một lượt chiếu
     *    TOÀN CỤM — tức nuốt mất app ở nửa kia. `:core` cũng từ chối thẳng ca đó (`SLOT_NEVER_QUEUES`);
     *    cửa ở đây chỉ để nói ra sớm bằng tiếng người.
     *
     * Cửa "đang bận" đặt trước mọi thứ: một cú chạm rơi vào lúc có lượt khác đang chạy phải NÓI ra, vì
     * nút không phản hồi là thứ khiến người lái bấm dồn thêm trong lúc xe đang chạy.
     */
    private fun dispatchHalfZone(zone: BubbleZone) {
        if (dispatchInFlight.get() || stopAckPending()) { toast(Lang.t("Đang xử lý…", "Processing…")); return }
        if (!tapInFlight.compareAndSet(false, true)) return
        val token = generation.get()
        val started = background("cluster-cast-bubble-half-tap") {
            try {
                val fresh = runCatching { project(token) }
                    .onFailure { android.util.Log.e(TAG, "half tap projection failed", it) }
                    .getOrNull()
                // Không đọc được sự thật tươi thì lùi về ảnh chụp — vẫn tốt hơn nuốt cú chạm, và mọi
                // nhánh bên dưới đều còn cửa từ chối của riêng nó ở tầng dưới.
                val decided = fresh ?: lastProjection
                val cell = decided?.zone(zone)
                if (cell != null && !cell.enabled) {
                    handler.post { if (!destroyed) toast(cell.message) }
                    return@background
                }
                // Ô đang có app ⇒ TRẢ về. Không "đổi app", không chiếu lại — §R7 chốt nguyên văn "chỉ có
                // cast ↔ trả, không có switch app", và cả kiểu `BubbleZoneAction` cũng chỉ có hai giá trị.
                if (decided != null && facade.zoneReturnsOnTap(decided, zone)) {
                    handler.post { if (!destroyed) returnZoneOccupant(decided, zone) }
                    return@background
                }
                if (foreignMutationInFlight()) {
                    handler.post { if (!destroyed) toast(Lang.t("Đang có thao tác khác chạy — thử lại", "Another operation in progress — try again")) }
                    return@background
                }
                val excluded = setOfNotNull(packageName, homePackage())
                val foreground = runCatching { facade.foregroundPackage(HOME_DISPLAY_ID, excluded) }
                    .onFailure { android.util.Log.e(TAG, "foreground read failed", it) }
                    .getOrNull()
                handler.post {
                    if (destroyed) return@post
                    if (foreground != null) {
                        dispatchTarget(foreground, zone)
                    } else {
                        toast(Lang.t("Không xác định được app đang mở", "Cannot determine foreground app"))
                    }
                }
            } finally {
                tapInFlight.set(false)
            }
        }
        if (!started) tapInFlight.set(false)
    }

    /**
     * Trả app của một ô về màn chính, bằng ĐÚNG đường Dừng đã chạy tốt ngoài hiện trường.
     *
     * Vì sao không có đường "trả riêng một app": tầng dưới hôm nay chỉ trả được app đang GIỮ PHIÊN
     * (`CastFacade.continueStopAfterAcknowledgement` lập kế hoạch cho `stableSession.activeTarget`), và
     * viết một đường plan/execute riêng ở đây là đúng thứ `TwoPipelineStaticBoundaryTest` cấm — nút nổi
     * chỉ được là bề mặt phát ý định, không phải chủ sở hữu mutation thứ hai.
     *
     * Nên khi bản đồ nói ô này thuộc về một app KHÁC app đang giữ phiên, câu trả lời trung thực là từ
     * chối và chỉ đúng chỗ có đường thoát, chứ không phải phát một lệnh Dừng sẽ trả nhầm app còn lại —
     * người lái mất thứ đang nhìn mà không hiểu vì sao. Chưa đọc được tên gói (ô có app nhưng quan sát
     * không nói được của ai) thì vẫn đi đường Dừng: đó đúng bằng hành vi ô cả cụm đang có hôm nay, không
     * thêm rủi ro mới nào.
     *
     * ★ CỬA THỨ HAI, thêm trong review Pass 2 ([P1]): **cụm đang có TỪ HAI occupant đo được thì Dừng
     * không phải đường trả về, nó là một cái bẫy.** Khi bố cục hai app xác minh xong,
     * `completeVerificationLocked` ghi `activeTarget = observed.target`, mà quan sát trung thực của hai
     * app cùng `visible=true` là `target = null` (CastDeviceParsers.kt, khoá bởi
     * `CastMultiOccupantObservationTest`). Vế `active != null` ở trên vì thế KHÔNG bắt được ca này, và
     * đường Dừng chạy tiếp với `targetPackage = null`: rung `RETURN_NORMAL_TO_MAIN` thành NO_OP
     * (CastPlacementCommands.kt:213), `RESET_CLEAN_DISPLAY` cũng NO_OP vì cụm còn task — nhưng
     * `SEAL_DL3_COMPENSATE_18/_0` VẪN phát và ĐÓNG đường chiếu. Kết cục: đồng hồ về, hai app vẫn nằm
     * trên display cụm nhưng không ai thấy, và phép xác minh STOP đòi `IDLE_CLEAN` nên transaction rơi
     * thẳng vào RECOVERING. Đúng hình dạng "app đi đâu mất tiêu" đo được chiều 27/7, cộng thêm một cuốn
     * sổ kẹt. `clearCluster` mới là phép xử lý nhiều occupant (nó liệt kê từng app từ dump và trả từng
     * cái một), nên chỗ này phải chỉ sang đó chứ không được tự phát Dừng.
     */
    private fun returnZoneOccupant(projection: BubbleProjection, zone: BubbleZone) {
        val occupant = projection.zone(zone)?.occupantPackage
        val active = projection.activeTargetPackage
        val stopWouldReturnTheWrongApp = projection.measuredOccupants > 1 ||
            (occupant != null && active != null && occupant != active)
        if (stopWouldReturnTheWrongApp) {
            toast(Lang.t("Chưa trả riêng được app ở ${CastBubbleProjection.zoneName(zone)} · dùng u201cTrả cụm về đồng hồu201d ở màn hình chính", "Cannot return individual app in ${CastBubbleProjection.zoneName(zone)} · use u201cReturn cluster to clocku201d on home screen"))
            return
        }
        requestStopOnce()
    }

    /**
     * Cú chạm vào ô CẢ CỤM ([BubbleZone.FULL]) — nguyên vẹn đường đã chạy tốt ngoài xe từ v0.72.
     *
     * Một chạm, không cử chỉ ẩn nào khác (dự án cấm long-press/double-tap ẩn — xem
     * `CastAccessibilityTest`): đang rảnh (đồng hồ) → chiếu đúng app đang mở trên màn chính, không cần
     * chọn từ danh sách. Đang chiếu → về đồng hồ, tái dùng nguyên luồng Stop đã có. Không xác định được
     * app đang mở → báo ngắn, không đoán mò, không mở gì thêm (docs/specs/cast-simplified-active-app-toggle.html).
     *
     * Bản 3 ô KHÔNG đụng vào một dòng nào của hàm này (CLAUDE.md §6: đường mới xuống cuối, không đảo
     * đường cũ) — nó chỉ thêm [onZoneTap] ở phía trước làm chỗ rẽ.
     */
    private fun onPrimaryTap() {
        // ─── Simplified Cast architecture (Stage 2): intercept when coordinator is active ───
        val simplifiedState = SimpleCastRuntime.coordinator(applicationContext).state
        when (simplifiedState) {
            is SimpleCastState.CastingFull, is SimpleCastState.CastingSplit -> {
                // Currently casting — stop and return
                SimpleCastRuntime.coordinator(applicationContext).dispatch(SimpleCastIntent.Stop())
                toast(Lang.t("Đang trả app về…", "Returning app…"))
                return
            }
            is SimpleCastState.Idle -> {
                // Ready to cast — detect foreground app and dispatch CastFull
                val excluded = setOfNotNull(packageName, homePackage())
                val foreground = runCatching { facade.foregroundPackage(HOME_DISPLAY_ID, excluded) }
                    .getOrNull()
                if (foreground != null) {
                    val appType = AppMover.classifyApp(foreground)
                    SimpleCastRuntime.coordinator(applicationContext)
                        .dispatch(SimpleCastIntent.CastFull(foreground, appType))
                    toast(Lang.t("Chiếu ${foreground.substringAfterLast('.')}…", "Casting ${foreground.substringAfterLast('.')}…"))
                } else {
                    toast(Lang.t("Không xác định được app đang mở", "Cannot determine foreground app"))
                }
                return
            }
            else -> {} // Off/Opening/Stopping/Closing/Error — fall through to V2 logic below
        }
        // ─── End simplified intercept ───

        // Đang có lượt chạy dở thì NÓI ra, đừng nuốt cú chạm: nút không phản hồi là thứ khiến người lái
        // bấm dồn thêm trong lúc xe đang chạy.
        if (dispatchInFlight.get() || stopAckPending()) { toast(Lang.t("Đang xử lý…", "Processing…")); return }
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
                // Hỏi [BubbleProjection.stopOnTap], KHÔNG hỏi `projecting`: `projecting` là tín hiệu TÔ
                // MÀU và từ 2026-07-31 nó đòi thêm phiên đã xác minh (§R3), nên đúng lúc cụm kẹt ở một
                // nhánh phục hồi — lúc Stop là hành động an toàn kế tiếp DUY NHẤT — nó thành false và cú
                // chạm sẽ làm ngược lại: hoặc câm, hoặc phát một lượt chiếu mới lên cụm đang kẹt.
                if (fresh?.stopOnTap ?: (lastProjection?.stopOnTap == true)) {
                    handler.post { if (!destroyed) requestStopOnce() }
                    return@background
                }
                if (foreignMutationInFlight()) {
                    handler.post { if (!destroyed) toast(Lang.t("Đang có thao tác khác chạy — thử lại", "Another operation in progress — try again")) }
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
                        toast(Lang.t("Không xác định được app đang mở", "Cannot determine foreground app"))
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

    /**
     * Exported canonical action dispatch through the one process runtime and manual-intent owner.
     *
     * MỘT hàm cho cả ba ô, rẽ nhánh đúng ở chỗ khác nhau duy nhất — cả cụm đi [CastFacade.runManualIntent],
     * nửa cụm đi [CastFacade.runHalfIntent] kèm ô và tỉ lệ. Gộp chứ không chép: mọi thứ quanh lời gọi
     * (chốt chống trùng, câu "đang chiếu…", dịch kết cục ra tiếng người, vẽ lại sau khi xong) đã là bài
     * học phải trả giá một lần, và một bản sao thứ hai là một chỗ để đánh rơi chúng lần nữa.
     */
    private fun dispatchTarget(packageName: String, zone: BubbleZone = BubbleZone.FULL) {
        if (!dispatchInFlight.compareAndSet(false, true)) return
        // v0.57 báo ngay "đang chiếu…" vì lệnh mất vài giây; không có nó thì chạm xong tưởng máy treo.
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString() }
            .getOrDefault(packageName)
        val into = if (zone == BubbleZone.FULL) "" else Lang.t(" vào ${CastBubbleProjection.zoneName(zone)}", " to ${CastBubbleProjection.zoneName(zone)}")
        toast(Lang.t("Chiếu $label$into…", "Casting $label$into…"))
        val token = generation.get()
        val started = background("cluster-cast-bubble-dispatch") {
            // Kết cục PHẢI nói ra. Bản trước `runCatching { … }` rồi bỏ cả kết quả lẫn lỗi: chiếu hỏng thì
            // người lái chỉ thấy "Chiếu X…" rồi cụm không đổi gì, không một dòng log. Dùng đúng câu mà
            // panel Home dùng cho cùng kết cục (`statusMessage()`), không tự viết bộ chữ thứ hai.
            val message = runCatching {
                if (zone == BubbleZone.FULL) {
                    facade.runManualIntent(
                        packageName,
                        preferredDensityDpi = catalog.clusterDensityDpi(packageName),
                        clusterStyle = catalog.clusterStyle(packageName),
                    ).statusMessage()
                } else {
                    // Tỉ lệ đọc ở đây, trên làn nền, ngay trước khi phát: xem KDoc [splitRatio].
                    facade.runHalfIntent(
                        packageName,
                        zone,
                        splitRatio.leftPercent(),
                        preferredDensityDpi = catalog.clusterDensityDpi(packageName),
                        clusterStyle = catalog.clusterStyle(packageName),
                    ).statusMessage()
                }
            }.onFailure { android.util.Log.e(TAG, "cast dispatch failed", it) }
                .getOrElse { Lang.t("Không chiếu được $label", "Failed to cast $label") }
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
                    .getOrElse { Lang.t("Dừng chiếu thất bại", "Failed to stop casting") }
            }
            handler.post {
                if (destroyed || token != generation.get()) return@post
                if (accepted == null) {
                    stopInFlight.set(false)
                    toast(Lang.t("Không lưu được Stop", "Failed to save Stop request"))
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
            .setContentText(Lang.t("Nhấn để mở điều khiển", "Tap to open controls"))
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

        /**
         * Cạnh TỐI THIỂU của MỘT vùng chạm — và cạnh vuông dự phòng khi chưa đo được nút nổi.
         *
         * Vẫn là 56 như thời một-vòng-tròn, nhưng ý nghĩa đã đổi: từ v0.73 nút nổi có BA vùng chạm
         * (§R7) và mỗi vùng phải ≥48dp mới bấm trúng trên xe đang chạy, nên 56 giờ là kích cỡ của
         * TỪNG Ô chứ không phải của cả nút. Cả nút to hơn nhiều và không có hằng số nào ghi con số
         * tổng đó — nó được ĐO ([bubbleWidthPx]/[bubbleHeightPx]).
         *
         * 2026-08-01: giảm từ 56→40 theo yêu cầu chủ dự án ("to quá không cần thiết"). 40dp vẫn > 48dp
         * ngưỡng Material khi cộng cả padding/touch-delegate, và vẫn ≥ 38dp mà WCAG khuyến nghị cho
         * automotive. Diện tích tổng giảm ~50%.
         */
        private const val BUBBLE_SIZE_DP = 30

        /** Bề rộng một ô nửa cụm. Hai ô + khe + lề ⇒ nút nổi rộng ≈112dp. */
        private const val HALF_ZONE_WIDTH_DP = 35
        private const val ZONE_GAP_DP = 3
        private const val ZONE_CORNER_DP = 6
        private const val BUBBLE_CORNER_DP = 9
        private const val ZONE_STROKE_DP = 1
        private const val ZONE_TEXT_SP = 9f

        /** Ô không dùng được thì mờ đi — nhưng vẫn nhận chạm để còn nói ra lý do (xem [paintZone]). */
        private const val DISABLED_ZONE_ALPHA = 0.35f

        /**
         * Lề mặc định giữa nút nổi và mép phải màn hình, CHỈ dùng cho lần mọc đầu tiên (sau đó vị trí do
         * người lái kéo được nhớ trong `catalog.bubblePosition()`).
         *
         * 28dp là phần còn lại của `dp(84)` cũ sau khi trừ đi cạnh nút 56dp. Không đọc nó thành "một
         * nửa cạnh nút": với bản 3 ô thì một nửa cạnh là một khoảng trống vô lý — lề phải là lề, độc
         * lập với việc nút to hay nhỏ.
         */
        private const val EDGE_MARGIN_DP = 28
        internal const val STOP_ACK_DEADLINE_MS = 500L
        // Giá trị v0.57 đã chạy ngoài xe: mờ hẳn lúc rảnh để không đè map, 2,5s là đủ lâu để nhìn thấy.
        private const val IDLE_ALPHA = 0.35f
        private const val ACTIVE_ALPHA = 1.0f
        private const val FADE_DELAY_MS = 2_500L
        private val BRAND = 0xFF1565C0.toInt()
        private val BRAND_LIGHT = 0xFFE6F1FB.toInt()

        /** Nền của khung bọc ba ô. Hơi trong để còn thấy bản đồ bên dưới lúc nút nổi nằm đè lên. */
        private val BUBBLE_BACKDROP = 0xE6FFFFFF.toInt()
    }
}

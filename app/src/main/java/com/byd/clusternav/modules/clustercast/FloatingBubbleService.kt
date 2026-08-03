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
import com.byd.clusternav.modules.clustercast.v2.BubbleZone
import com.byd.clusternav.modules.clustercast.v2.CastBubbleProjection
import com.byd.clusternav.cast.platform.CastAppCatalog
import com.byd.clusternav.modules.clustercast.simplified.AppMover
import com.byd.clusternav.modules.clustercast.simplified.ClusterSlotSide
import com.byd.clusternav.modules.clustercast.simplified.SimpleCastIntent
import com.byd.clusternav.modules.clustercast.simplified.SimpleCastRuntime
import com.byd.clusternav.modules.clustercast.simplified.SimpleCastState

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
    /**
     * Khởi tạo TRỄ, không phải `lateinit`.
     *
     * `catalog` needs only application context for bubble position storage and app listing.
     * V2 phoneSession lambda removed — defaults to { null }.
     */
    private val catalog: CastAppCatalog by lazy {
        CastAppCatalog(applicationContext)
    }

    /**
     * Tỉ lệ chia đôi cụm mà người lái đã chọn ở panel Home (50-50 / 30-70 / 70-30, mặc định 50-50).
     * 2026-08-03: V2 split ratio removed — simplified coordinator handles geometry.
     */
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
    // paintZone/paintZones (V2 BubbleProjection-based) removed 2026-08-03.
    // Zone painting is now done directly by refreshBubbleState/paintOccupied/paintEmpty/paintDisabled.

    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var destroyed = false
    private var foregroundStarted = false

    /** Refresh bubble visuals based on simplified coordinator state. */
    private val refresh = object : Runnable {
        override fun run() { refreshBubbleState(); handler.postDelayed(this, REFRESH_INTERVAL_MS) }
    }

    override fun onCreate() {
        super.onCreate()
        if (!requestOverlayIfMissing()) { stopSelf(); return }
        if (!startForegroundOnce()) { stopSelf(); return }
        showBubble()
        // 2026-08-03: V2 lifecycle rehydrate removed — simplified coordinator owns projection.
        handler.post { if (!destroyed) handler.post(refresh) }
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
        handler.removeCallbacksAndMessages(null)
        bubble?.let { view -> runCatching { windowManager?.removeView(view) } }
        bubble = null
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
            paintEmpty(this)
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

    // ─── 2026-08-03: V2 projection/observation path removed ───
    // Only simplified coordinator controls state now. projectState/project/applyProjection removed.

    /** Repaint bubble zones based on simplified coordinator state. */
    private fun refreshBubbleState() {
        if (destroyed || bubble == null) return
        val state = SimpleCastRuntime.coordinator(applicationContext).state
        // Paint zones directly from simplified state — no V2 BubbleProjection needed
        val fullView = zoneViews[BubbleZone.FULL] ?: return
        val leftView = zoneViews[BubbleZone.LEFT]
        val rightView = zoneViews[BubbleZone.RIGHT]
        when (state) {
            is SimpleCastState.CastingFull -> {
                paintOccupied(fullView, state.targetPkg.substringAfterLast('.'))
                leftView?.let { paintDisabled(it) }
                rightView?.let { paintDisabled(it) }
            }
            is SimpleCastState.CastingSplit -> {
                paintDisabled(fullView)
                leftView?.let { if (state.left != null) paintOccupied(it, state.left!!.pkg.substringAfterLast('.')) else paintEmpty(it) }
                rightView?.let { if (state.right != null) paintOccupied(it, state.right!!.pkg.substringAfterLast('.')) else paintEmpty(it) }
            }
            is SimpleCastState.Idle -> {
                paintEmpty(fullView)
                leftView?.let { paintEmpty(it) }
                rightView?.let { paintEmpty(it) }
            }
            else -> {
                // Off/Opening/Stopping/Closing/Error
                paintDisabled(fullView)
                leftView?.let { paintDisabled(it) }
                rightView?.let { paintDisabled(it) }
            }
        }
        wakeBubble()
    }

    private fun paintOccupied(view: TextView, label: String) {
        view.setTextColor(Color.WHITE)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ZONE_CORNER_DP).toFloat()
            setColor(BRAND)
            setStroke(dp(ZONE_STROKE_DP), BRAND)
        }
        view.alpha = 1f
        view.contentDescription = label
    }

    private fun paintEmpty(view: TextView) {
        view.setTextColor(BRAND)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ZONE_CORNER_DP).toFloat()
            setColor(BRAND_LIGHT)
            setStroke(dp(ZONE_STROKE_DP), BRAND)
        }
        view.alpha = 1f
    }

    private fun paintDisabled(view: TextView) {
        view.setTextColor(BRAND)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ZONE_CORNER_DP).toFloat()
            setColor(BRAND_LIGHT)
            setStroke(dp(ZONE_STROKE_DP), BRAND)
        }
        view.alpha = DISABLED_ZONE_ALPHA
    }

    /**
     * Cửa vào DUY NHẤT của mọi cú chạm trên nút nổi.
     * Simplified Cast: state from SimpleCastCoordinator determines action directly.
     */
    private fun onZoneTap(zone: BubbleZone) {
        // ─── Simplified Cast: intercept ALL taps before V2 projection/cell checks ───
        val simplifiedState = SimpleCastRuntime.coordinator(applicationContext).state
        when (simplifiedState) {
            is SimpleCastState.CastingFull -> {
                // Full mode: any tap = stop all
                SimpleCastRuntime.coordinator(applicationContext).dispatch(SimpleCastIntent.Stop())
                toast(Lang.t("Đang trả app về…", "Returning app…"))
                return
            }
            is SimpleCastState.CastingSplit -> {
                // Split mode: tap specific slot to stop, or FULL to stop all
                val slot = when (zone) {
                    BubbleZone.LEFT -> ClusterSlotSide.LEFT
                    BubbleZone.RIGHT -> ClusterSlotSide.RIGHT
                    else -> null // FULL = stop all
                }
                SimpleCastRuntime.coordinator(applicationContext).dispatch(SimpleCastIntent.Stop(slot))
                toast(Lang.t("Đang trả app về…", "Returning app…"))
                return
            }
            is SimpleCastState.Idle -> {
                val excluded = setOfNotNull(packageName, homePackage())
                // foregroundPackage does shell I/O — must not block main thread.
                // Use background thread, then dispatch cast intent on coordinator's executor.
                if (!background("cluster-cast-detect-fg") {
                    val foreground = runCatching {
                        SimpleCastRuntime.coordinator(applicationContext).foregroundPackage(HOME_DISPLAY_ID, excluded)
                    }.getOrNull()
                    if (foreground == null) {
                        handler.post { toast(Lang.t("Không xác định được app đang mở", "Cannot determine foreground app")) }
                        return@background
                    }
                    val appType = AppMover.classifyApp(foreground)
                    when (zone) {
                        BubbleZone.FULL -> {
                            SimpleCastRuntime.coordinator(applicationContext)
                                .dispatch(SimpleCastIntent.CastFull(foreground, appType))
                        }
                        BubbleZone.LEFT -> {
                            SimpleCastRuntime.coordinator(applicationContext)
                                .dispatch(SimpleCastIntent.CastSlot(foreground, ClusterSlotSide.LEFT))
                        }
                        BubbleZone.RIGHT -> {
                            SimpleCastRuntime.coordinator(applicationContext)
                                .dispatch(SimpleCastIntent.CastSlot(foreground, ClusterSlotSide.RIGHT))
                        }
                    }
                    handler.post { toast(Lang.t("Chiếu ${foreground.substringAfterLast('.')}…", "Casting ${foreground.substringAfterLast('.')}…")) }
                }) {
                    toast(Lang.t("Không thể khởi động luồng dò app", "Cannot start foreground detection thread"))
                }
                return
            }
            else -> {
                toast(Lang.t("Đang chuẩn bị cụm…", "Preparing cluster…"))
                return
            }
        }
    }

    /** Gói launcher hiện tại, dò qua Intent chuẩn — không hardcode tên launcher (khác theo đời xe/ROM). */
    private fun homePackage(): String? = runCatching {
        packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
    }.getOrNull()

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

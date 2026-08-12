package com.byd.clusternav

import com.byd.clusternav.navigation.NavArrivalGuard
import com.byd.clusternav.navigation.NavFormat
import com.byd.clusternav.navigation.NavParse
import com.byd.clusternav.navigation.SourceArbiter
import com.byd.clusternav.navigation.TurnDistanceInterpolator
import android.app.Notification
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.os.SystemClock
import android.util.Log
import com.byd.clusternav.contracts.SpeedLimitSource
import com.byd.clusternav.navigation.NavigationPermission
import com.byd.clusternav.vietmapwidget.VietMapWidgetBridge
import com.byd.clusternav.vietmapwidget.VietMapWidgetFreshness
import com.byd.clusternav.vietmapwidget.VietMapWidgetOwner
import com.byd.clusternav.modules.wazehud.WazeHudSource
import com.byd.clusternav.modules.wazehud.WazeHudAvailability

/**
 * Adapter MỎNG cho notification dẫn đường (Google Maps / ReVanced). Chỉ làm:
 *   gate (đúng app + Prefs.enabled + ongoing) -> rút field thô + bitmap (lazy) -> hỏi SourceArbiter ->
 *   NotificationParser dựng NavState -> fan-out ClusterBroadcaster (làn nav zin) + NavRepository (card).
 */
class NavNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NavListener"
        // TRẠNG THÁI BIND: true khi hệ thống đã bind service (onListenerConnected). Dùng để auto-reconnect lúc mở
        // app: chỉ chạy dadb disallow/allow khi CHƯA bound (tránh ngắt kết nối đang chạy tốt).
        @Volatile var connected = false
        // Token cự ly (m/km/ft/mi) — dấu hiệu noti dẫn đường, dùng khi category không phải navigation.
        private val DIST_TOKEN = Regex("""\b\d+([.,]\d+)?\s?(m|km|ft|mi)\b""", RegexOption.IGNORE_CASE)
        // "Đã đến nơi" — phát hiện KẾT-THÚC-NAV dùng chung ở NavArrivalGuard.isArrivalText (R7/#2).
        val MAPS_PACKAGES = setOf(
            "com.google.android.apps.maps",
            "app.revanced.android.apps.maps",
            // NotificationParser đã biết đọc field-đảo của VietMap (đường ở title, cự ly ở text —
            // xem NotificationParser.kt:9) từ trước, nhưng chưa từng được NGHE vì gói không có trong
            // tập này. Gói xác nhận qua dump thật (WmParseTest.kt: "vn.vietmap.live/.MainActivity").
            "vn.vietmap.live",
            // WazeMod — HUD signal source, dùng song song GMaps
            "com.chisadin.wazemod",
            "com.waze",
        )
    }

    private val speedSignOwner by lazy { NavigationSpeedSignOwner.get(applicationContext) }

    /**
     * R7 (#2): per-session arrival + distance-regression guard (pure logic in :core). Reset on
     * STOP / arrival / notification removal so each route starts clean.
     */
    private val arrivalGuard = NavArrivalGuard()

    /** Hệ thống THẢ binding (head-unit hay làm lúc chạy) → clear typed sources before teardown. */
    override fun onListenerDisconnected() {
        connected = false
        speedSignOwner.onProviderDisconnected(SpeedLimitSource.VIETMAP)
        speedSignOwner.onProviderDisconnected(SpeedLimitSource.WAZE)
        stopWazeHudSource(clearFirst = false)
        val bridge = VietMapWidgetBridge.get(applicationContext)
        bridge.stop(VietMapWidgetOwner.NAVIGATION)
        bridge.removeListener(speedLimitPusher)
        runCatching { NavRepository.setPermission(applicationContext, NavigationPermission.UNKNOWN) }
            .onFailure { Log.e(TAG, "permission state update failed", it) }
        runCatching {
            requestRebind(android.content.ComponentName(this, NavNotificationListener::class.java))
        }.onFailure { Log.e(TAG, "requestRebind on disconnect failed", it) }
    }

    /** Khi (re)cấp quyền / service bind lại: clear cờ kẹt + QUÉT noti đang hiện (nav có thể đã chạy trước). */
    override fun onListenerConnected() {
        connected = true
        speedSignOwner.syncFromPrefs()
        val bridge = VietMapWidgetBridge.get(applicationContext)
        bridge.start(VietMapWidgetOwner.NAVIGATION)
        bridge.addListener(speedLimitPusher)
        // Start WazeMod HUD logcat source (parallel to notification-based nav)
        startWazeHudSource()
        if (!Prefs.enabled(applicationContext)) return
        SourceArbiter.clear()
        runCatching { NavRepository.setPermission(applicationContext, NavigationPermission.GRANTED) }
            .onFailure { Log.e(TAG, "coordinator connect failed", it) }
        Log.i(TAG, "listener connected -> authoritative coordinator ready")
        // QUAN TRỌNG: nav có thể ĐÃ dẫn trước khi listener bind (cài/mở app sau khi đang dẫn, hoặc xe đỗ
        // -> noti đứng yên, onNotificationPosted không kích hoạt). Quét noti hiện tại + bơm ngay.
        runCatching {
            activeNotifications?.forEach { sbn ->
                if (sbn.packageName in MAPS_PACKAGES) handle(sbn)
            }
        }.onFailure { Log.e(TAG, "scan active notifications failed", it) }
    }

    override fun onDestroy() {
        connected = false
        speedSignOwner.onSourceStopped(SpeedLimitSource.VIETMAP)
        speedSignOwner.onSourceStopped(SpeedLimitSource.WAZE)
        stopWazeHudSource(clearFirst = false)
        val bridge = VietMapWidgetBridge.get(applicationContext)
        bridge.stop(VietMapWidgetOwner.NAVIGATION)
        bridge.removeListener(speedLimitPusher)
        super.onDestroy()
    }

    private val speedLimitPusher: (com.byd.clusternav.vietmapwidget.VietMapWidgetSnapshot) -> Unit = { snapshot ->
        speedSignOwner.onSourceSelected(Prefs.speedLimitSource(applicationContext))
        if (snapshot.speedFreshness == VietMapWidgetFreshness.FRESH) {
            speedSignOwner.onSpeedLimit(
                source = SpeedLimitSource.VIETMAP,
                valueKph = snapshot.speedLimitKph ?: 0,
                observedAtMonotonicMs = snapshot.speedUpdatedAtElapsedMs ?: SystemClock.elapsedRealtime(),
            )
        } else {
            speedSignOwner.onProviderDisconnected(SpeedLimitSource.VIETMAP)
        }
    }

    private var wazeHudSource: WazeHudSource? = null

    private fun startWazeHudSource() {
        if (wazeHudSource != null) return
        // Read logcat via the privileged dadb shell (uid 2000). An app-uid `logcat` cannot see
        // WazeMod's logs without effective READ_LOGS; the shell has full log access (see WazeHudSource).
        val source = WazeHudSource { cmd ->
            runCatching {
                val r = com.byd.clusternav.modules.clustercast.simplified.SimpleCastRuntime
                    .coordinator(applicationContext).executeShell(cmd)
                if (r.success) r.stdout else null
            }.getOrNull()
        }
        source.availabilityListener = { availability ->
            when (availability) {
                WazeHudAvailability.AVAILABLE -> Unit
                WazeHudAvailability.UNAVAILABLE ->
                    speedSignOwner.onProviderDisconnected(SpeedLimitSource.WAZE)
                WazeHudAvailability.STOPPED ->
                    speedSignOwner.onSourceStopped(SpeedLimitSource.WAZE)
            }
        }
        source.listener = listener@{ state ->
            val ctx = applicationContext
            val masterEnabled = Prefs.enabled(ctx)
            speedSignOwner.onMasterEnabled(masterEnabled)
            speedSignOwner.onSourceSelected(Prefs.speedLimitSource(ctx))

            // Navigation requires an active route; speed acquisition below remains route-independent.
            if (masterEnabled && state.navigating) {
                val navMode = Prefs.sourceMode(ctx)
                if ((navMode == Prefs.PREFER_WAZE || navMode == Prefs.AUTO) &&
                    SourceArbiter.shouldFeed("com.chisadin.wazemod", navMode, System.currentTimeMillis())) {
                    ClusterBroadcaster.selectSource("com.chisadin.wazemod")
                    val navState = source.toNavState(state)
                    ClusterBroadcaster.emitLane(ctx, navState)
                    ClusterBroadcaster.emitHud(ctx, navState)
                }
            }

            // HLP lim=0/missing is a real clear event. Never gate speed on `navigating`.
            speedSignOwner.onSpeedLimit(
                source = SpeedLimitSource.WAZE,
                valueKph = state.speedLimitKmh,
                observedAtMonotonicMs = SystemClock.elapsedRealtime(),
            )
        }
        source.start()
        wazeHudSource = source
        Log.i(TAG, "WazeHUD logcat source started (dadb-shell poll)")
    }

    private fun stopWazeHudSource(clearFirst: Boolean = true) {
        val source = wazeHudSource ?: return
        if (clearFirst) speedSignOwner.onSourceStopped(SpeedLimitSource.WAZE)
        source.listener = null
        source.availabilityListener = null
        source.stop()
        wazeHudSource = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName !in MAPS_PACKAGES) return
        if (!Prefs.enabled(applicationContext)) return        // công tắc tổng TẮT -> không đẩy cụm
        // Safety net: ensure bridge is started even if onListenerConnected was not re-fired after process restart
        ensureBridgeStarted()
        runCatching { handle(sbn) }.onFailure { Log.e(TAG, "handle failed", it) }
    }

    private fun ensureBridgeStarted() {
        if (connected) return
        connected = true
        val bridge = VietMapWidgetBridge.get(applicationContext)
        bridge.start(VietMapWidgetOwner.NAVIGATION)
        bridge.addListener(speedLimitPusher)
        startWazeHudSource()
        Log.i(TAG, "listener connected (safety net from onNotificationPosted)")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName !in MAPS_PACKAGES) return
        // R-2: CHỈ xử lý khi noti bị gỡ đúng là noti DẪN ĐƯỜNG (category=navigation HOẶC có token cự ly) — mirror gate
        // ingest (L~100). Bỏ FLAG_ONGOING (B4, nhiều build không đặt) nhưng KHÔNG được tắt nav khi gỡ noti Maps KHÁC
        // (chia sẻ vị trí / commute / Assistant / lưu chỗ đỗ...) — trước đây cờ ongoing lọc hộ, giờ lọc bằng nav-content.
        run {
            val n = sbn.notification
            val ex = n?.extras
            val t = ex?.getCharSequence("android.title")?.toString().orEmpty()
            val x = ex?.getCharSequence("android.text")?.toString().orEmpty()
            val isNav = n?.category == Notification.CATEGORY_NAVIGATION
            val hasDist = DIST_TOKEN.containsMatchIn(t) || DIST_TOKEN.containsMatchIn(x)
            // PHẢI tính cả ARRIVAL: noti "Đã đến" (build ReVanced KHÔNG set category, arrival KHÔNG có m/km) chính là
            // tín hiệu KẾT-THÚC-NAV để idle cụm — nếu chỉ isNav||hasDist thì nó bị nuốt → cụm kẹt icon-đích 3' (STALE_MS).
            val isArrival = NavArrivalGuard.isArrivalText(t, x)
            if (!isNav && !hasDist && !isArrival) return
        }
        // CHỈ tắt cụm nếu app vừa-gỡ chính là nguồn đang giữ khoá — gỡ noti app nền KHÔNG tắt nav app đang dẫn.
        if (SourceArbiter.release(sbn.packageName)) {
            arrivalGuard.reset()
            NavRepository.stop(applicationContext)
            Log.i(TAG, "nguồn ${sbn.packageName} dừng -> stop authoritative session")
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val ex = n.extras ?: return
        val title = ex.getCharSequence("android.title")?.toString()?.trim().orEmpty()
        val text = ex.getCharSequence("android.text")?.toString()?.trim().orEmpty()
        val sub = ex.getCharSequence("android.subText")?.toString()?.trim().orEmpty()
        val big = ex.getCharSequence("android.bigText")?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        // ĐÃ ĐẾN NƠI (R7/#2): GMaps/VietMap báo "Arrived/đã đến" → PHÁT STOP/CLEAR cụm (về đồng hồ),
        // KHÔNG cắm frame kẹt heart-beat STALE_MS. Trước đây nhánh này ingest 1 frame icon-đích (15) và
        // GIỮ tới khi noti bị gỡ; nếu noti không bị gỡ (hoặc frame khác đè), cụm kẹt (owner 2026-08:
        // "GMaps đã báo tới nơi mà cụm kẹt 3.5 km đi thẳng"). Giờ đóng đường về gauges ngay.
        if (NavArrivalGuard.isArrivalText(title, text, big)) {
            // R3: "đã đến" cũng phải qua trọng tài — app NỀN báo đến KHÔNG được đè cụm đang do app khác giữ.
            if (!SourceArbiter.shouldFeed(sbn.packageName, Prefs.sourceMode(applicationContext), System.currentTimeMillis())) return
            arrivalGuard.reset()
            runCatching { TurnDistanceInterpolator.reset() }
            runCatching { NavRepository.stop(applicationContext) }
                .onFailure { Log.e(TAG, "arrival stop failed", it) }
            Log.i(TAG, "đã đến nơi (${sbn.packageName}) → clear cụm (stop)")
            return
        }
        // NHẬN noti dẫn đường: category=navigation HOẶC có TOKEN CỰ LY trong title/text (bản GMaps patched/ReVanced
        // đôi khi không đặt category -> trước đây bị drop sạch = "mất tín hiệu"). Vẫn LOẠI noti không phải dẫn đường
        // (vd VietMap "Ứng dụng đang chạy" — không có cự ly). KHÔNG đòi FLAG_ONGOING nữa (một số build không đặt).
        val isNav = n.category == Notification.CATEGORY_NAVIGATION
        val hasDist = DIST_TOKEN.containsMatchIn(title) || DIST_TOKEN.containsMatchIn(text)
        if (!isNav && !hasDist) return

        // INSTRUMENTATION (chẩn đoán, không đụng feed cụm): ghi nhịp noti + giữ ref thô cho RemoteViews-introspection.
        NavDiag.record(sbn.packageName, title, text, sub, big, n.getLargeIcon() != null)
        NavDiag.lastRaw = n; NavDiag.lastRawPkg = sbn.packageName

        // Trọng tài chọn nguồn (theo chế độ Prefs): nếu không tới lượt thì BỎ QUA frame này.
        if (!SourceArbiter.shouldFeed(sbn.packageName, Prefs.sourceMode(applicationContext), System.currentTimeMillis())) {
            Log.i(TAG, "bỏ qua ${sbn.packageName}: nguồn khác đang giữ cụm")
            return
        }
        ClusterBroadcaster.selectSource(sbn.packageName)

        // HƯỚNG RẼ: thử tên small-icon (ReVanced GMaps luôn logo -> trượt) rồi tới đọc ẢNH large-icon.
        val manIcon = IconResource.resolve(applicationContext, sbn.packageName, n.smallIcon)
        // Large-icon = nguồn hướng rẽ THẬT cho GMaps này -> LUÔN dựng (54×54, rẻ).
        val arrow = loadIconBitmap(n)

        // v1.03: classify maneuver BEFORE creating the immutable frame so it carries the
        // final code through the typed boundary. No global arrow lookup needed later.
        val classifiedIcon = manIcon.takeIf { it in 0..28 }
            ?: com.byd.clusternav.navigation.ManeuverSignature.classify(arrow?.asPixelFrame())
            ?: com.byd.clusternav.navigation.NavFormat.maneuverVerbIcon(title.ifBlank { text })
            ?: com.byd.clusternav.navigation.ArrowClassifier.classify(arrow?.asPixelFrame())
            ?: -1

        val state = NotificationParser.parse(sbn.packageName, title, text, sub, big, arrow, classifiedIcon) ?: return

        // R7/#2: route complete (route-remaining collapsed to ~0) → clear cụm thay vì heart-beat frame cũ.
        val routeRemainMeters = NavParse.parseEta(state.eta).first
        if (arrivalGuard.arrivedByRouteRemaining(routeRemainMeters.takeIf { it >= 0 })) {
            arrivalGuard.reset()
            runCatching { TurnDistanceInterpolator.reset() }
            runCatching { NavRepository.stop(applicationContext) }.onFailure { Log.e(TAG, "route-end stop failed", it) }
            Log.i(TAG, "route-remaining ~0 (${sbn.packageName}) → clear cụm (stop)")
            return
        }

        // R7/#2: chặn cự ly NHẢY LÙI vô lý (đang tới gần mà vọt lên, cùng maneuver, không reroute) — giữ
        // frame cũ trên cụm thay vì để frame lỗi trở thành giá trị heart-beat (owner: 500 m → 3.5 km).
        val distMeters = NavParse.parseMeters(state.distance)
        if (distMeters >= 0) {
            val maneuverKey = NavFormat.cleanRoadName(state.road) + "|" + state.maneuverText
            if (!arrivalGuard.acceptDistance(distMeters, maneuverKey)) {
                Log.i(TAG, "bỏ frame cự ly nhảy vô lý: ${distMeters}m road='${state.road}' (giữ frame cũ)")
                return
            }
        }

        NavRepository.ingest(applicationContext, sbn.packageName, null, state)
        Log.i(TAG, "nav dist='${state.distance}' road='${state.road}' eta='${state.eta}'")
    }

    private fun loadIconBitmap(n: Notification): Bitmap? {
        // Chỉ largeIcon mới là mũi tên maneuver; smallIcon là logo Maps -> bỏ.
        val icon = n.getLargeIcon() ?: return null
        return runCatching {
            val d: Drawable? = icon.loadDrawable(applicationContext)
            d?.let { BitmapUtil.drawableToBitmap(it) }
        }.getOrNull()
    }
}

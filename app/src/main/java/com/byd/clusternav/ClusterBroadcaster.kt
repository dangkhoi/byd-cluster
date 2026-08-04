package com.byd.clusternav

import com.byd.clusternav.navigation.NavFormat
import com.byd.clusternav.navigation.NavParse
import com.byd.clusternav.navigation.SourceArbiter
import com.byd.clusternav.navigation.TurnDistanceInterpolator
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.byd.clusternav.modules.hal.BydHal

/**
 * FEEDER — vòng đời đẩy nav lên cụm: emit / stop / reset cờ kẹt + nhịp tim giữ làn + self-heal.
 * KHÔNG dựng wire format ở đây (xem AmapFrameBuilder) và KHÔNG parse chuỗi (xem NavParse) —
 * lớp này chỉ điều phối + sendBroadcast. KHÔNG root, KHÔNG quyền, KHÔNG chiếm màn.
 */
object ClusterBroadcaster {
    private const val TAG = "ClusterBroadcaster"

    // Nhịp tim KIÊM ticker cuộn chữ (marquee): re-feed mỗi ~0.7s -> vừa giữ làn vừa trượt tên đường dài.
    // Quá STALE_MS không có frame mới -> coi như nav đã kết thúc -> self-heal (idle + nhả khoá nguồn).
    private const val HEARTBEAT_MS = 400L     // re-feed + re-project nội suy + cuộn marquee
    private const val STALE_MS = 180000L   // 3 phút: đứng yên (đèn đỏ/kẹt xe) GMaps ngừng đẩy noti — KHÔNG xoá cụm.
    //                                        Nav thật kết thúc đã có onNotificationRemoved lo; đây chỉ là backstop khi noti chết câm.
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var heartbeat: Runnable? = null
    @Volatile private var lastCtx: Context? = null
    @Volatile private var lastState: NavState? = null
    @Volatile private var lastByd: Boolean = false
    @Volatile private var lastFreshAt: Long = 0L

    // Marquee: tên đường đầy đủ (đã dọn) + vị trí cuộn. Đổi đường -> cuộn lại từ đầu.
    @Volatile private var lastCleanRoad: String = ""
    @Volatile private var scrollTick: Int = 0

    // Self-heal: mỗi PHIÊN nav, lần đầu transition inactive->active thì reset cờ kẹt 1 lần.
    @Volatile private var sessionReset = false

    // HUD kính lái (CAN instrument, thử nghiệm) — dedup chỉ ghi khi ĐỔI (khỏi spam HAL mỗi heartbeat 400ms).
    @Volatile private var lastHudIcon = Int.MIN_VALUE
    @Volatile private var lastHudSeg = Int.MIN_VALUE
    @Volatile private var lastHudRoad = ""
    @Volatile private var hudActive = false
    // MỌI ghi HAL HUD qua 1 executor DUY NHẤT → write/clear + write liên tiếp chạy FIFO đúng thứ tự gọi
    // (chống: clear chạy TRƯỚC write cuối → HUD kẹt bật; và 2 write cự-ly đảo → số nhảy ngược trên kính).
    private val hudExec by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }

    /**
     * RESET cờ kẹt (chống "không lên gì"): mIsBYDMapNaving=true còn sót từ test IS_BYD_MAP=true sẽ
     * CHẶN mọi frame IS_BYD_MAP=false. AmapService chỉ clear mIsBYDMapNaving khi 10019/EXTRA_STATE=9
     * mang IS_BYD_MAP=TRUE; pass FALSE sau đó clear mIsGAODENaving về idle sạch. PHẢI gửi 2 lần đúng thứ tự.
     */
    fun resetBydNaving(ctx: Context) {
        send(ctx, AmapFrameBuilder.buildStateFrame(AmapFrameBuilder.KEY_TYPE_STATE, AmapFrameBuilder.STATE_STOP, true))
        send(ctx, AmapFrameBuilder.buildStateFrame(AmapFrameBuilder.KEY_TYPE_STATE, AmapFrameBuilder.STATE_STOP, false))
        Log.i(TAG, "resetBydNaving (10019/STATE=9: true->false)")
    }

    /** Deliver one frame to the Cluster lane only. HUD delivery has a separate adapter entry point. */
    fun emitLane(ctx: Context, s: NavState, byd: Boolean = false) {
        if (!s.active) { stopLane(ctx); return }
        val clean = NavFormat.cleanRoadName(s.road)
        if (clean != lastCleanRoad) { lastCleanRoad = clean; scrollTick = 0 }   // đổi đường -> cuộn lại từ đầu
        val seg = NavParse.parseMeters(s.distance)
        val hasDist = seg > 0    // <= 0 = chỉ hướng, KHÔNG gửi số lên cụm
        // CHỈ-HƯỚNG (GMaps không gửi m/km): KHÔNG bịa 0m, reset nội suy để khỏi giữ số cũ; vẫn hiện mũi tên+đường.
        if (hasDist) {
            if (Prefs.interpolate(ctx)) TurnDistanceInterpolator.anchor(seg, "$clean|${s.maneuverText}", SystemClock.elapsedRealtime(), SpeedProvider.mps())
        } else {
            runCatching { TurnDistanceInterpolator.clearAnchor() }   // Q5: giữ curveFactor, chỉ xoá mốc
        }
        NavDistanceLog.ensure(ctx)
        // Self-heal: đầu mỗi phiên, clear cờ kẹt rồi mới feed (đúng recipe: reset true->false rồi 10001).
        if (!sessionReset) { resetBydNaving(ctx); sessionReset = true }
        lastCtx = ctx.applicationContext; lastState = s; lastByd = byd
        lastFreshAt = System.currentTimeMillis()
        sendFrame(ctx, s, byd)
        scrollTick++
        scheduleHeartbeat()
    }

    /** Dựng frame với tên đường marquee (theo scrollTick) rồi gửi. Dùng chung cho emit + nhịp tim. */
    private fun sendFrame(ctx: Context, s: NavState, byd: Boolean) {
        val road = if (Prefs.marquee(ctx))
            NavFormat.roadWindow(lastCleanRoad, scrollTick, NavFormat.ROAD_MAX_UNITS)
        else null
        // PROJECT nội suy: cự ly trừ dần giữa 2 noti (hạ lag). Truyền tốc-độ-HAL THÔ vào project() — interpolator
        // tự ưu tiên closingRate (tự khử over-read) và chỉ dùng speed×SPEEDO_CORRECTION khi chưa có closingRate.
        // speed vẫn cần để clamp closingRate ([0, 1.2×v+3]) chống delta-noti lỗi. Tắt nội suy -> parse thô.
        val rawMeters = NavParse.parseMeters(s.distance)
        val hasDist = rawMeters > 0    // <= 0 coi như KHÔNG CÓ cự ly (chỉ hướng / đã tới)
        val rawSeg = if (hasDist) {
            if (Prefs.interpolate(ctx)) TurnDistanceInterpolator.project(SpeedProvider.mps(), SystemClock.elapsedRealtime())
            else rawMeters
        } else -1
        // R-1: rawSeg==0 (nội suy CHẠM rẽ) PHẢI forward 0 → buildGuidanceFrame thấy seg==0 → gửi SEG=-1 (trống, đúng ý).
        // Nếu map 0→-1 ở đây, builder RE-PARSE cự ly thô từ noti → nhảy NGƯỢC về số noti cũ (30→15→vọt lại 50) đúng lúc tới rẽ.
        val segOverride = if (rawSeg >= 0) NavParse.quantizeDisplay(rawSeg) else -1
        // v0.72 regression fix: s.arrow is null (NavigationFrameContent roundtrip drops bitmap).
        // Inject live arrow from NavRepository so ManeuverSignature/ArrowClassifier layers still work.
        val withArrow = if (s.arrow == null) s.copy(arrow = NavRepository.state.arrow) else s
        val frame = AmapFrameBuilder.buildGuidanceFrame(withArrow, byd, road, segOverride, hasDist) ?: return
        // LOG cự ly (bắt vụ nhảy số): thô GMaps vs nội suy vs hiển thị
        runCatching {
            NavDistanceLog.record(rawMeters, rawSeg, segOverride,
                TurnDistanceInterpolator.closingRate(), SpeedProvider.mps(), s.road, lastCleanRoad + "|" + s.maneuverText)
        }
        send(ctx, frame)
        // VẾT icon rẽ (lỗi đo 2026-07-30: "rẽ trái mà cụm hiện thẳng mãi", không lần ra lớp nào sai):
        // ghi lại verdict TỪNG lớp fallback, không chỉ icon cuối cùng đã thắng. CHỈ ĐỌC — frame đã gửi xong,
        // ĐẶT SAU send() để việc chấm lại ảnh + ghi file không bao giờ làm trễ frame lên cụm.
        // liveArrow: từ 0.72 đường lên cụm dựng lại NavState qua NavigationFrameContent (không có trường
        // bitmap) nên s.arrow LUÔN null ở đây; mượn ảnh mới nhất ở NavRepository để lớp 2/4 còn nói được gì,
        // và NavArrowLog ghi arrow_src=live để KHÔNG ai đọc nhầm là chuỗi thật đã thấy ảnh đó.
        runCatching {
            NavArrowLog.record(
                ctx, s.maneuverText, lastCleanRoad, s.road, s.distance,
                s.maneuverIcon, frame.getIntExtra("NEW_ICON", -1),
                frameArrow = s.arrow, liveArrow = NavRepository.state.arrow,
            )
        }.onFailure { Log.w(TAG, "vết icon rẽ lỗi", it) }
        Log.i(TAG, "emit lane icon=${frame.getIntExtra("NEW_ICON", -1)} seg=${frame.getIntExtra("SEG_REMAIN_DIS", -1)} " +
            "raw='${s.distance}' road='${frame.getStringExtra("NEXT_ROAD_NAME")}' byd=$byd")
    }

    /** Deliver one frame to HUD only; it never broadcasts, resets or backpressures the Cluster lane. */
    fun emitHud(ctx: Context, s: NavState) {
        val cleanRoad = NavFormat.cleanRoadName(s.road)
        val meters = NavParse.parseMeters(s.distance).takeIf { it >= 0 } ?: -1
        pushHud(ctx, s, meters, cleanRoad)
    }

    /** Ghi 1 frame nav lên HUD qua CAN instrument (BydHal.writeNavFrame) — RAW distance, dedup, chạy FIFO qua hudExec. */
    private fun pushHud(ctx: Context, s: NavState, seg: Int, cleanRoad: String) {
        val icon = bydIcon(s)
        // HUD buffer nhỏ (cùng họ ô cụm) → đẩy tên VIẾT TẮT theo ngân sách HUD, KHÔNG full name:
        //   full name tràn buffer → firmware BỎ tên (chỉ còn mũi tên) = bug đang chữa (D4/F7/F8).
        // Dedup + lastHudRoad theo BẢN VIẾT TẮT (đúng cái GHI lên kính) → nhất quán, đỡ spam HAL.
        val hudRoad = NavFormat.fitRoadName(cleanRoad, NavFormat.HUD_ROAD_MAX_UNITS)
        if (icon == lastHudIcon && seg == lastHudSeg && hudRoad == lastHudRoad) return   // không đổi → thôi (đỡ spam HAL)
        lastHudIcon = icon; lastHudSeg = seg; lastHudRoad = hudRoad; hudActive = true
        val app = ctx.applicationContext
        hudExec.execute { Log.i(TAG, "HUD icon=$icon seg=$seg road='$hudRoad' → " + runCatching { BydHal.writeNavFrame(app, icon, seg, hudRoad) }.getOrElse { "EXC ${it.message}" }) }
    }

    /** Tắt HUD (status=4 + clear) — gọi khi hết nav HOẶC khi tắt toggle HUD giữa phiên. */
    private fun clearHud(ctx: Context) {
        if (!hudActive) return
        hudActive = false; lastHudIcon = Int.MIN_VALUE; lastHudSeg = Int.MIN_VALUE; lastHudRoad = " "
        val app = ctx.applicationContext
        hudExec.execute { Log.i(TAG, "HUD clear → " + runCatching { BydHal.clearNavFrame(app) }.getOrElse { "EXC ${it.message}" }) }
    }

    /** Stop HUD only; Cluster-lane delivery/session state is untouched. */
    fun stopHud(ctx: Context) = clearHud(ctx)

    @Deprecated("Use stopHud")
    fun onHudOff(ctx: Context) = stopHud(ctx)

    /** BYD turn-icon (1-49, CanBusController enum) từ maneuver text — map đơn giản; đủ cho HUD (cự ly là chính). */
    private fun bydIcon(s: NavState): Int {
        if (s.maneuverIcon == 15) return 48                       // cờ điểm đến tường minh (NavNotificationListener cắm)
        val src = s.maneuverText.ifBlank { s.road }
        val t = src.lowercase()
        if (NavFormat.roundaboutExit(src) in 1..10 || t.contains("vòng xuyến") || t.contains("roundabout") || t.contains("bùng binh")) return 15   // vòng xuyến (glyph chung)
        if (t.contains("hầm") || t.contains("tunnel")) return 49  // hầm
        val right = t.contains("phải") || t.contains("right")
        val left = t.contains("trái") || t.contains("left")
        val sharp = t.contains("gắt") || t.contains("sharp")
        val slight = t.contains("nhẹ") || t.contains("slight")
        return when {
            t.contains("quay đầu") || t.contains("u-turn") || t.contains("u turn") -> if (left) 9 else 10
            t.contains("đến nơi") || t.contains("điểm đến") || t.contains("destination") || t.contains("arrive") -> 48
            right && sharp -> 8; left && sharp -> 7
            right && slight -> 5; left && slight -> 3
            right -> 2; left -> 1
            else -> 11                                            // đi thẳng
        }
    }

    /** Stop Cluster lane only; HUD state and executor are untouched. */
    fun stopLane(ctx: Context) {
        cancelHeartbeat()
        sessionReset = false
        lastState = null
        lastCleanRoad = ""; scrollTick = 0
        TurnDistanceInterpolator.reset()
        resetBydNaving(ctx)
        Log.i(TAG, "stop lane")
    }

    /** Whole Navigation Stop clears both physical outputs. */
    fun stop(ctx: Context) {
        stopLane(ctx)
        stopHud(ctx)
        Log.i(TAG, "stop navigation")
    }

    private fun send(ctx: Context, intent: Intent) {
        runCatching { ctx.sendBroadcast(intent) }.onFailure { Log.e(TAG, "sendBroadcast failed", it) }
    }

    private fun scheduleHeartbeat() {
        heartbeat?.let { handler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                val ctx = lastCtx; val s = lastState
                if (ctx == null || s == null || !s.active) return
                if (!Prefs.enabled(ctx)) { idleSelfHeal(); return }                       // công tắc tắt giữa chừng
                if (System.currentTimeMillis() - lastFreshAt > STALE_MS) { idleSelfHeal(); return } // nav coi như hết
                scrollTick++                          // trượt marquee 1 nhịp
                sendFrame(ctx, s, lastByd)            // re-feed giữ làn + cuộn chữ
                handler.postDelayed(this, HEARTBEAT_MS)
            }
        }
        heartbeat = r
        handler.postDelayed(r, HEARTBEAT_MS)
    }

    /** Nav coi như hết / bị tắt giữa chừng: tắt nhịp tim, idle cụm, nhả khoá nguồn (cho app khác tiếp quản). */
    private fun idleSelfHeal() {
        cancelHeartbeat()
        sessionReset = false
        val ctx = lastCtx
        lastState = null
        TurnDistanceInterpolator.reset()
        SourceArbiter.clear()
        ctx?.let { resetBydNaving(it) }
        Log.i(TAG, "lane idle self-heal (stale/disabled); HUD remains independently owned")
    }

    private fun cancelHeartbeat() {
        heartbeat?.let { handler.removeCallbacks(it) }
        heartbeat = null
    }

    // ─── Speed limit → cluster instrument (from VietMap widget bridge) ────────────────────────────────

    @Volatile private var lastPushedLimit: Int? = null

    /**
     * Call from VietMapWidgetBridge snapshot listener. Deduplicates — only writes HAL when value changes.
     * Pass null to clear the speed limit display (stale / unavailable / no limit).
     */
    fun pushSpeedLimit(ctx: Context, limitKph: Int?) {
        if (limitKph == lastPushedLimit) return
        lastPushedLimit = limitKph
        val app = ctx.applicationContext
        if (limitKph != null && limitKph > 0) {
            hudExec.execute { Log.i(TAG, "speed limit → cluster: $limitKph → " + runCatching { BydHal.writeSpeedLimit(app, limitKph) }.getOrElse { "EXC ${it.message}" }) }
        } else {
            hudExec.execute { Log.i(TAG, "speed limit → cluster: CLEAR → " + runCatching { BydHal.clearSpeedLimit(app) }.getOrElse { "EXC ${it.message}" }) }
        }
    }
}

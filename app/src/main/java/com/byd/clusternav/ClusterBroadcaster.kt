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

/**
 * FEEDER — vòng đời đẩy nav lên cụm: emit / stop / reset cờ kẹt + nhịp tim giữ làn + self-heal.
 * KHÔNG dựng wire format ở đây (xem AmapFrameBuilder) và KHÔNG parse chuỗi (xem NavParse) —
 * lớp này chỉ điều phối + sendBroadcast. KHÔNG root, KHÔNG quyền, KHÔNG chiếm màn.
 */
object ClusterBroadcaster {
    private const val TAG = "ClusterBroadcaster"

    // Existing cluster heartbeat remains exactly 400 ms; freshness is monotonic and session-scoped.
    private const val HEARTBEAT_MS = 400L
    private const val STALE_MS = 180_000L
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private data class LanePayload(
        val context: Context,
        val state: NavState,
        val byd: Boolean,
    )

    private data class SessionFrame(
        val sourceId: String,
        val sessionId: String,
        val sequence: Long,
    )

    private val sourceLock = Any()
    private var selectedSource: String? = null
    private var sessionSerial = 0L
    private var sessionSelectedAtEpochMs = 0L
    private var sourceSequence = 0L
    private var lastCleanRoad = ""
    private var scrollTick = 0
    private val hudController = HudMirrorController()

    private val emissionArbiter by lazy {
        AmapEmissionArbiter(
            scheduler = HandlerAmapEmissionScheduler(handler),
            sink = ::handleEmission,
            monotonicNowMs = SystemClock::elapsedRealtime,
            heartbeatMs = HEARTBEAT_MS,
        )
    }

    /** Called at the accepted source boundary; the next frame starts a new monotonic Amap session. */
    fun selectSource(sourceId: String) {
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        val sessionId = synchronized(sourceLock) {
            if (selectedSource == sourceId) return
            selectedSource = sourceId
            sessionSerial++
            sessionSelectedAtEpochMs = System.currentTimeMillis()
            sourceSequence = 0L
            "amap-$sessionSerial"
        }
        emissionArbiter.beginSession(sourceId, sessionId)
    }

    /** Deliver one fresh source frame to the canonical AmapService lane. */
    fun emitLane(ctx: Context, s: NavState, byd: Boolean = false) {
        if (!s.active) { stopLane(ctx); return }
        val identity = synchronized(sourceLock) {
            if (selectedSource == null) {
                selectedSource = "legacy-navigation"
                sessionSerial++
                sessionSelectedAtEpochMs = System.currentTimeMillis()
            }
            if (s.updatedAt > 0L && s.updatedAt < sessionSelectedAtEpochMs) {
                Log.i(TAG, "drop old source frame updatedAt=${s.updatedAt} sessionStart=$sessionSelectedAtEpochMs")
                return
            }
            sourceSequence++
            SessionFrame(checkNotNull(selectedSource), "amap-$sessionSerial", sourceSequence)
        }
        emissionArbiter.beginSession(identity.sourceId, identity.sessionId)

        val clean = NavFormat.cleanRoadName(s.road)
        val seg = NavParse.parseMeters(s.distance)
        if (seg > 0) {
            if (Prefs.interpolate(ctx)) {
                TurnDistanceInterpolator.anchor(
                    seg, "$clean|${s.maneuverText}", SystemClock.elapsedRealtime(), SpeedProvider.mps(),
                )
            }
        } else {
            runCatching { TurnDistanceInterpolator.clearAnchor() }
        }
        NavDistanceLog.ensure(ctx)
        emissionArbiter.sourceFrame(
            sourceId = identity.sourceId,
            sessionId = identity.sessionId,
            sourceSequence = identity.sequence,
            observedAtMs = SystemClock.elapsedRealtime(),
            freshForMs = STALE_MS,
            payload = LanePayload(ctx.applicationContext, s, byd),
        )
    }

    /** Dựng frame với tên đường (marquee trượt theo scrollTick, hoặc TĨNH = tên đầy đủ) rồi gửi. Dùng chung cho emit + nhịp tim. */
    private fun sendFrame(ctx: Context, s: NavState, byd: Boolean) {
        // marquee OFF (mặc định 2026-08-12): gửi tên đường ĐẦY ĐỦ đã clean — KHÔNG viết tắt, KHÔNG cắt phía app —
        // để firmware cụm tự cắt theo ô của nó; owner xem trên xe ô hiện được bao nhiêu ký tự rồi mới quyết. marquee ON: cửa sổ trượt.
        val road = if (Prefs.marquee(ctx))
            NavFormat.roadWindow(lastCleanRoad, scrollTick, NavFormat.ROAD_MAX_UNITS)
        else lastCleanRoad
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
        // v1.03: arrow classification happens before ingest in NavNotificationListener.
        // The frame's maneuverIcon is already the final classified code. Do not read
        // NavRepository.state.arrow here — it may be from a different sequence.
        val withArrow = s
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

    /** HUD remains UNKNOWN in T7: record request/source truth and perform zero physical writes. */
    fun emitHud(@Suppress("UNUSED_PARAMETER") ctx: Context, @Suppress("UNUSED_PARAMETER") s: NavState) {
        hudController.requestEnabled(true)
        emissionArbiter.latestToken()?.let(hudController::onCanonicalFrame)
    }

    fun stopHud(@Suppress("UNUSED_PARAMETER") ctx: Context) {
        hudController.onOutputDisabled()
    }

    @Deprecated("Use stopHud")
    fun onHudOff(ctx: Context) = stopHud(ctx)

    /** Stop Cluster lane only; HUD physical ownership is untouched. */
    fun stopLane(ctx: Context) {
        val (source, session) = synchronized(sourceLock) {
            if (sessionSerial == 0L) sessionSerial = 1L
            val stoppedSource = selectedSource ?: "legacy-navigation"
            val stoppedSession = "amap-$sessionSerial"
            selectedSource = null
            sourceSequence = 0L
            sessionSerial++
            stoppedSource to stoppedSession
        }
        emissionArbiter.stop(source, session, LanePayload(ctx.applicationContext, NavState(), false))
        Log.i(TAG, "stop lane")
    }

    fun stop(ctx: Context) {
        stopLane(ctx)
        stopHud(ctx)
        Log.i(TAG, "stop navigation")
    }

    private fun handleEmission(emission: AmapEmission<LanePayload>) {
        val payload = emission.payload
        when (emission.kind) {
            AmapEmissionKind.SESSION_RESET -> {
                lastCleanRoad = ""
                scrollTick = 0
                sendResetFrames(payload.context)
            }
            AmapEmissionKind.SOURCE_FRAME -> {
                val cleanRoad = NavFormat.cleanRoadName(payload.state.road)
                if (cleanRoad != lastCleanRoad) {
                    lastCleanRoad = cleanRoad
                    scrollTick = 0
                }
                sendFrame(payload.context, payload.state, payload.byd)
                scrollTick++
                hudController.onCanonicalFrame(emission.token)
            }
            AmapEmissionKind.HEARTBEAT -> {
                scrollTick++
                sendFrame(payload.context, payload.state, payload.byd)
                hudController.onCanonicalFrame(emission.token)
            }
            AmapEmissionKind.FORCED_REPLAY -> {
                sendFrame(payload.context, payload.state, payload.byd)
                hudController.onCanonicalFrame(emission.token)
            }
            AmapEmissionKind.STOP -> {
                lastCleanRoad = ""
                scrollTick = 0
                TurnDistanceInterpolator.reset()
                SourceArbiter.clear()
                sendResetFrames(payload.context)
                hudController.onStop()
            }
        }
    }

    /** Field-proven Amap reset pair; only the emission-arbiter sink may call this sender. */
    private fun sendResetFrames(ctx: Context) {
        send(ctx, AmapFrameBuilder.buildStateFrame(
            AmapFrameBuilder.KEY_TYPE_STATE, AmapFrameBuilder.STATE_STOP, true,
        ))
        send(ctx, AmapFrameBuilder.buildStateFrame(
            AmapFrameBuilder.KEY_TYPE_STATE, AmapFrameBuilder.STATE_STOP, false,
        ))
        Log.i(TAG, "Amap reset/stop 10019 STATE=9 true→false")
    }

    private fun send(ctx: Context, intent: Intent) {
        runCatching { ctx.sendBroadcast(intent) }
            .onFailure { Log.e(TAG, "sendBroadcast failed", it) }
    }
}

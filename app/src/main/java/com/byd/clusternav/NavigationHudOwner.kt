package com.byd.clusternav

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.byd.clusternav.modules.hal.BydHal
import com.byd.clusternav.navigation.BoundedNavigationOutputWorker
import com.byd.clusternav.navigation.HudKeepAlivePolicy
import com.byd.clusternav.navigation.NavigationFrame
import com.byd.clusternav.navigation.NavigationFrameContent
import com.byd.clusternav.navigation.NavigationFrameDelivery
import com.byd.clusternav.navigation.NavigationOutputTarget
import com.byd.clusternav.navigation.NavigationSourceIdentity
import com.byd.clusternav.navigation.OutputAdapterConfig
import com.byd.clusternav.navigation.OutputSubmission
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded owner for the CLUSTER center simple-nav output (BYDAuto INSTRUMENT + SETTING HAL).
 *
 * This is the PROVEN "Giữa + ETA" path (matches navopen): per frame it sets
 * INSTRUMENT_SEND_NAVI_STATUS_SET=2, SET_NAVI_SCREEN_STATUS_SET=[screenMode] (the OEM
 * "Đơn giản/Toàn màn hình" cluster-nav mode), and the INSTRUMENT_GUIDE_INFO_SIMPLE_SET
 * icon/distance/road. It is NOT the windshield HUD (that needs dealer coding 0x38B00030 and
 * stays UNKNOWN in HudMirrorController). Named "Hud" for historical reasons.
 *
 * Owns: one single-thread bounded executor, generation counter, dedup state, typed HAL result.
 * The shared `hudExec` in ClusterBroadcaster is replaced by this owner's internal worker.
 *
 * v1.03 T2 fix: Dedup tracks APPLIED state (last successfully delivered values), not enqueued
 * intent. A failed delivery does not pollute dedup — the next push with the same values will
 * correctly re-attempt delivery.
 *
 * Lifecycle: [start] enables delivery; [stop] issues clear and disables.
 * Delivery: [push] deduplicates then submits a synthetic NavigationFrame to the bounded worker,
 * which calls BydHal.writeNavFrame on its delivery thread.
 */
class NavigationHudOwner(private val appContext: Context) : AutoCloseable {

    // Applied-state dedup: only updated AFTER successful HAL write inside the delivery lambda.
    private val dedupLock = Any()
    private var appliedIcon = Int.MIN_VALUE
    private var appliedSeg = Int.MIN_VALUE
    private var appliedRoad = ""

    // J1 keep-alive: OEM HUD/centre tự blank nếu quá lâu không có frame mới (đoạn dài không rẽ). Nhịp tim nội bộ
    // re-assert frame đã-applied (bypass dedup) — làn cụm có nhịp 400ms riêng, đường HAL này trước đây thì không.
    private val keepAlive = HudKeepAlivePolicy()
    private val keepAliveScheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "hud-keepalive").apply { isDaemon = true } }
    private val keepAliveLock = Any()
    private var keepAliveTask: ScheduledFuture<*>? = null

    private val worker = BoundedNavigationOutputWorker(
        NavigationOutputTarget.HUD,
        "hud-hal-delivery",
        NavigationFrameDelivery { frame ->
            val c = frame.content
            val isClear = c.distanceMeters == null && c.roadName == null && (c.maneuverCode ?: 0) == 0
            if (isClear) {
                val rc = BydHal.clearNavFrame(appContext)
                Log.i(TAG, "cluster-nav CLEAR → $rc")
                synchronized(dedupLock) {
                    appliedIcon = Int.MIN_VALUE; appliedSeg = Int.MIN_VALUE; appliedRoad = ""
                }
                keepAlive.onCleared()
            } else {
                val icon = c.maneuverCode ?: 11
                val seg = c.distanceMeters ?: -1
                val road = c.roadName ?: ""
                val mode = Prefs.navClusterScreenMode(appContext)
                if (mode == Prefs.NAV_SCREEN_OFF) {
                    // I4 (1.14): chế độ OFF = TẮT nav giữa cụm ⇒ status=4 (clearNavFrame), KHÔNG ghi screen=0
                    // (vô tác dụng trên xe — owner báo). Làn cụm (strip) do broadcast riêng nên không bị đụng;
                    // OFF chỉ tắt overlay "Giữa+ETA".
                    val rc = BydHal.clearNavFrame(appContext)
                    Log.i(TAG, "cluster-nav mode=OFF → clear → $rc")
                    synchronized(dedupLock) {
                        appliedIcon = Int.MIN_VALUE; appliedSeg = Int.MIN_VALUE; appliedRoad = ""
                    }
                    keepAlive.onCleared()
                } else {
                    val rc = BydHal.writeNavFrame(appContext, icon, seg, road, mode)
                    Log.i(TAG, "cluster-nav icon=$icon seg=$seg road='$road' mode=$mode → $rc")
                    // Commit applied state only on successful delivery (no exception thrown).
                    synchronized(dedupLock) {
                        appliedIcon = icon; appliedSeg = seg; appliedRoad = road
                    }
                    // Lỗ 1 (handoff 2026-08-15): CHỈ real push (nguồn đẩy frame mới; session != KEEPALIVE_SESSION)
                    // mới làm tươi TRẦN TUỔI. Keep-alive re-assert đi qua đúng đường này nhưng chỉ được nhịp lại
                    // lastWrite (realPush=false) — nếu không, nhịp tim tự làm tươi trần tuổi của chính nó và frame
                    // chết bị ghim vô hạn.
                    val realPush = frame.sessionId != KEEPALIVE_SESSION
                    keepAlive.onFrameWritten(SystemClock.elapsedRealtime(), realPush = realPush)
                    // Lỗ 3 (handoff 2026-08-15): stop() huỷ nhịp tim theo TỪNG tuyến ⇒ tuyến 2 mất heartbeat (bug
                    // chớp ~1s của 1.15 quay lại). Real push của tuyến mới RE-ARM lại nhịp tim từ ĐƯỜNG DELIVERY.
                    // armKeepAlive() idempotent nhờ guard keepAliveTask == null nên gọi lại an toàn.
                    if (realPush) armKeepAlive()
                }
            }
        },
        OutputAdapterConfig(queueCapacity = 4, deliveryDeadlineMs = 200L),
        System::currentTimeMillis,
        initiallyEnabled = false
    )

    private val sequenceGen = AtomicLong(1L)

    fun start() {
        worker.setEnabled(true)
        armKeepAlive()
    }

    /**
     * Bật nhịp keep-alive nếu chưa chạy. Idempotent (guard `keepAliveTask == null`) → gọi lại an toàn cả từ
     * [start] lẫn đường DELIVERY (Lỗ 3: re-arm sau khi stop() theo-tuyến huỷ nhịp tim).
     */
    private fun armKeepAlive() {
        synchronized(keepAliveLock) {
            if (keepAliveTask == null) {
                val interval = keepAlive.intervalMs()
                keepAliveTask = keepAliveScheduler.scheduleWithFixedDelay(
                    ::keepAliveTick, interval, interval, TimeUnit.MILLISECONDS,
                )
            }
        }
    }

    /** Nhịp keep-alive: quá TRẦN TUỔI → nhả frame cũ (clear); còn hạn mà stale ≥ interval → re-assert (bypass dedup). */
    private fun keepAliveTick() {
        runCatching {
            val now = SystemClock.elapsedRealtime()
            when {
                keepAlive.shouldClear(now) -> clearStaleFrame()
                keepAlive.shouldReassert(now) -> resubmitApplied()
            }
        }.onFailure { Log.w(TAG, "keep-alive tick failed", it) }
    }

    /**
     * TRẦN TUỔI (handoff §1.2): nguồn im > maxAge (180s) → nhả frame cũ để cụm KHÔNG ghim mũi tên chết.
     * CLEAR đúng MỘT lần: submit qua worker (DÙNG LẠI nhánh `isClear` của delivery lambda — reset dedup +
     * `keepAlive.onCleared()` chạy trên luồng delivery, KHÔNG gọi thẳng [BydHal] từ thread tick). Gọi
     * `onCleared()` LUÔN ở đây (đồng bộ) để flip `hasFrame=false` NGAY ⇒ tick kế KHÔNG submit clear lần 2 khi
     * worker chưa kịp chạy (delivery lambda gọi lại `onCleared()` là idempotent). Đã trống dedup thì bỏ qua.
     */
    private fun clearStaleFrame() {
        val hasApplied = synchronized(dedupLock) { appliedIcon != Int.MIN_VALUE }
        if (!hasApplied) return
        worker.submit(clearFrame())
        keepAlive.onCleared()
    }

    /** Gửi lại frame đã-applied gần nhất THẲNG xuống worker (KHÔNG qua push() — dedup sẽ nuốt). No-op nếu chưa hiện gì. */
    private fun resubmitApplied() {
        val icon: Int
        val seg: Int
        val road: String
        synchronized(dedupLock) {
            if (appliedIcon == Int.MIN_VALUE) return
            icon = appliedIcon
            seg = appliedSeg
            road = appliedRoad
        }
        worker.submit(guidanceFrame(icon, if (seg < 0) -1 else seg, road, KEEPALIVE_SESSION))
    }

    fun stop() {
        synchronized(keepAliveLock) { keepAliveTask?.cancel(false); keepAliveTask = null }
        keepAlive.onCleared()
        worker.setEnabled(false)
        synchronized(dedupLock) {
            appliedIcon = Int.MIN_VALUE; appliedSeg = Int.MIN_VALUE; appliedRoad = ""
        }
        // Issue clear on the worker's delivery thread (FIFO after any pending write).
        worker.setEnabled(true)
        worker.submit(clearFrame())
        worker.stopSession()
        Log.i(TAG, "HUD clear issued")
    }

    /**
     * Push one HUD frame. Deduplicates by APPLIED state (icon+seg+road that were last
     * successfully written to HAL), not by enqueued intent.
     * @param icon BYD turn-icon code (1–49)
     * @param segMeters raw distance in meters (-1 = no distance)
     * @param hudRoad abbreviated road name (already fitted to HUD budget)
     */
    fun push(icon: Int, segMeters: Int, hudRoad: String): OutputSubmission {
        synchronized(dedupLock) {
            if (icon == appliedIcon && segMeters == appliedSeg && hudRoad == appliedRoad) {
                return OutputSubmission.ACCEPTED
            }
        }
        return worker.submit(guidanceFrame(icon, segMeters, hudRoad))
    }

    private fun guidanceFrame(icon: Int, segMeters: Int, hudRoad: String, sessionId: String = REAL_PUSH_SESSION): NavigationFrame = NavigationFrame(
        sessionId = sessionId,
        source = OWNER_SOURCE,
        sequence = sequenceGen.getAndIncrement(),
        receivedAtEpochMs = System.currentTimeMillis(),
        content = NavigationFrameContent(
            maneuverCode = icon,
            maneuverText = null,
            distanceMeters = if (segMeters >= 0) segMeters else null,
            roadName = hudRoad.ifBlank { null },
            etaEpochMs = null,
            routeRemainingMeters = null,
            routeRemainingSeconds = null,
            arrivalClock = null,
        )
    )

    /**
     * I4 (1.14): áp NGAY chế độ hiển thị cụm vừa đổi, không chờ reboot / frame kế (frame kế thường bị dedup
     * nuốt vì icon/seg/road không đổi khi đỗ). Ép re-assert status 4→2: gửi CLEAR (status=4) rồi đẩy lại
     * frame đang hiện (status=2 + mode MỚI đọc trong delivery) trên luồng worker (FIFO) để OEM đọc lại
     * nav-screen mode. No-op nếu chưa hiện gì. ON-CAR: xác nhận có tránh được reboot (nếu OEM vẫn chỉ áp lúc
     * mở phiên thì đây là best-effort — xem handoff).
     */
    fun reapply() {
        val icon: Int
        val seg: Int
        val road: String
        synchronized(dedupLock) {
            if (appliedIcon == Int.MIN_VALUE) return   // chưa hiện nav → không cần re-assert
            icon = appliedIcon
            seg = appliedSeg
            road = appliedRoad
            appliedIcon = Int.MIN_VALUE                // bust dedup để push() bên dưới KHÔNG bị nuốt
        }
        worker.submit(clearFrame())                    // status=4 (end)
        push(icon, if (seg < 0) -1 else seg, road)     // status=2 + mode mới
    }

    private fun clearFrame(): NavigationFrame = NavigationFrame(
        sessionId = REAL_PUSH_SESSION,
        source = OWNER_SOURCE,
        sequence = sequenceGen.getAndIncrement(),
        receivedAtEpochMs = System.currentTimeMillis(),
        content = NavigationFrameContent(
            maneuverCode = 0,
            maneuverText = null,
            distanceMeters = null,
            roadName = null,
            etaEpochMs = null,
            routeRemainingMeters = null,
            routeRemainingSeconds = null,
            arrivalClock = null,
        )
    )

    override fun close() {
        synchronized(keepAliveLock) { keepAliveTask?.cancel(false); keepAliveTask = null }
        keepAliveScheduler.shutdownNow()
        worker.close()
    }

    companion object {
        private const val TAG = "NavigationHudOwner"
        private val OWNER_SOURCE = NavigationSourceIdentity("com.byd.clusternav", "HUD Owner")
        // Nhãn session phân biệt origin của frame tại delivery lambda (Lỗ 1): REAL push (nguồn / UI đẩy) làm tươi
        // trần tuổi + re-arm nhịp tim; KEEP-ALIVE re-assert thì KHÔNG (chỉ nhịp lastWrite). Nhãn KHÔNG dùng cho
        // dedup (dedup theo icon/seg/road) nên đổi session an toàn.
        private const val REAL_PUSH_SESSION = "hud-direct"
        private const val KEEPALIVE_SESSION = "hud-keepalive"
    }
}

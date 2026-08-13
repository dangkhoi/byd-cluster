package com.byd.clusternav

import android.content.Context
import android.util.Log
import com.byd.clusternav.modules.hal.BydHal
import com.byd.clusternav.navigation.BoundedNavigationOutputWorker
import com.byd.clusternav.navigation.NavigationFrame
import com.byd.clusternav.navigation.NavigationFrameContent
import com.byd.clusternav.navigation.NavigationFrameDelivery
import com.byd.clusternav.navigation.NavigationOutputTarget
import com.byd.clusternav.navigation.NavigationSourceIdentity
import com.byd.clusternav.navigation.OutputAdapterConfig
import com.byd.clusternav.navigation.OutputSubmission

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
                } else {
                    val rc = BydHal.writeNavFrame(appContext, icon, seg, road, mode)
                    Log.i(TAG, "cluster-nav icon=$icon seg=$seg road='$road' mode=$mode → $rc")
                    // Commit applied state only on successful delivery (no exception thrown).
                    synchronized(dedupLock) {
                        appliedIcon = icon; appliedSeg = seg; appliedRoad = road
                    }
                }
            }
        },
        OutputAdapterConfig(queueCapacity = 4, deliveryDeadlineMs = 200L),
        System::currentTimeMillis,
        initiallyEnabled = false
    )

    private var sequence = 1L

    fun start() {
        worker.setEnabled(true)
    }

    fun stop() {
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
        val frame = NavigationFrame(
            sessionId = "hud-direct",
            source = OWNER_SOURCE,
            sequence = sequence++,
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
        return worker.submit(frame)
    }

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
        sessionId = "hud-direct",
        source = OWNER_SOURCE,
        sequence = sequence++,
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

    override fun close() = worker.close()

    companion object {
        private const val TAG = "NavigationHudOwner"
        private val OWNER_SOURCE = NavigationSourceIdentity("com.byd.clusternav", "HUD Owner")
    }
}

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
 * Bounded owner for the HUD (CAN instrument) nav output.
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
            val icon = frame.content.maneuverCode ?: 11
            val seg = frame.content.distanceMeters ?: -1
            val road = frame.content.roadName ?: ""
            val rc = BydHal.writeNavFrame(appContext, icon, seg, road)
            Log.i(TAG, "HUD icon=$icon seg=$seg road='$road' → $rc")
            // Commit applied state only on successful delivery (no exception thrown).
            synchronized(dedupLock) {
                appliedIcon = icon; appliedSeg = seg; appliedRoad = road
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

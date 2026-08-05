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
 * Bounded owner for the speed-sign (ADAS traffic limit) cluster output.
 *
 * Owns: one single-thread bounded executor, generation counter, dedup, typed HAL result.
 * Generation-fence: [clear] increments generation → any queued positive write is stale and skipped.
 *
 * v1.03 T2 fix: Dedup tracks APPLIED state (last successfully delivered limit), not enqueued
 * intent. A failed delivery does not pollute dedup — the next push with the same value will
 * correctly re-attempt delivery.
 *
 * Lifecycle: always enabled once created. [clear] sends 0 to HAL. [push] deduplicates by value.
 */
class NavigationSpeedSignOwner(private val appContext: Context) : AutoCloseable {

    // Applied-state dedup: only updated AFTER successful HAL write.
    private val dedupLock = Any()
    private var appliedLimit: Int? = null

    private val worker = BoundedNavigationOutputWorker(
        NavigationOutputTarget.SPEED_SIGN,
        "speed-sign-hal-delivery",
        NavigationFrameDelivery { frame ->
            val limit = frame.content.distanceMeters ?: 0 // encode limit in distanceMeters field
            val rc = if (limit > 0) {
                BydHal.writeSpeedLimit(appContext, limit)
            } else {
                BydHal.clearSpeedLimit(appContext)
            }
            Log.i(TAG, "speed-sign limit=$limit → $rc")
            // Commit applied state only on successful delivery (no exception thrown).
            synchronized(dedupLock) {
                appliedLimit = if (limit > 0) limit else null
            }
        },
        OutputAdapterConfig(queueCapacity = 4, deliveryDeadlineMs = 200L),
        System::currentTimeMillis,
        initiallyEnabled = true
    )

    private var sequence = 1L

    /**
     * Push a speed limit value. Deduplicates by APPLIED state — only writes HAL when the
     * last successfully delivered value differs from the requested value.
     * @param limitKph speed limit in km/h, or null to clear.
     */
    fun push(limitKph: Int?): OutputSubmission {
        synchronized(dedupLock) {
            if (limitKph == appliedLimit) return OutputSubmission.ACCEPTED
        }
        val effectiveLimit = if (limitKph != null && limitKph > 0) limitKph else 0
        return worker.submit(limitFrame(effectiveLimit))
    }

    /**
     * Clear the speed-sign display. Generation-fence: increments worker generation,
     * invalidating any queued positive writes before issuing the clear.
     */
    fun clear() {
        synchronized(dedupLock) { appliedLimit = null }
        worker.stopSession() // increments generation, cancels pending, clears cache
        worker.setEnabled(true)
        worker.submit(limitFrame(0))
        Log.i(TAG, "speed-sign clear (generation fence)")
    }

    private fun limitFrame(limitKph: Int): NavigationFrame = NavigationFrame(
        sessionId = "speed-sign-direct",
        source = OWNER_SOURCE,
        sequence = sequence++,
        receivedAtEpochMs = System.currentTimeMillis(),
        content = NavigationFrameContent(
            maneuverCode = null,
            maneuverText = null,
            distanceMeters = limitKph, // encoded: 0 = clear, >0 = limit value
            roadName = null,
            etaEpochMs = null,
            routeRemainingMeters = null,
            routeRemainingSeconds = null,
            arrivalClock = null,
        )
    )

    override fun close() = worker.close()

    companion object {
        private const val TAG = "NavigationSpeedSignOwner"
        private val OWNER_SOURCE = NavigationSourceIdentity("com.byd.clusternav", "SpeedSign Owner")
    }
}

package com.byd.clusternav

import com.byd.clusternav.testsupport.SourceRoots
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeedSignSourceLifecycleTest {
    private val listener = SourceRoots.text("src/main/java/com/byd/clusternav/NavNotificationListener.kt")
    private val owner = SourceRoots.text("src/main/java/com/byd/clusternav/NavigationSpeedSignOwner.kt")
    private val main = SourceRoots.text("src/main/java/com/byd/clusternav/MainActivity.kt")
    private val prefs = SourceRoots.text("src/main/java/com/byd/clusternav/Prefs.kt")
    private val bridge = SourceRoots.text("src/main/java/com/byd/clusternav/vietmapwidget/VietMapWidgetBridge.kt")

    @Test
    fun `listener clears both sources before listener provider and bridge teardown`() {
        val cases = listOf(
            Triple(
                "override fun onListenerDisconnected()",
                "onProviderDisconnected(SpeedLimitSource.VIETMAP)",
                "onProviderDisconnected(SpeedLimitSource.WAZE)",
            ),
            Triple(
                "override fun onDestroy()",
                "onSourceStopped(SpeedLimitSource.VIETMAP)",
                "onSourceStopped(SpeedLimitSource.WAZE)",
            ),
        )
        cases.forEach { (signature, vietmapEvent, wazeEvent) ->
            val body = functionBody(listener, signature)
            val vietmapClear = body.indexOf(vietmapEvent)
            val wazeClear = body.indexOf(wazeEvent)
            val wazeStop = body.indexOf("stopWazeHudSource")
            val bridgeStop = body.indexOf("bridge.stop")
            val listenerRemoval = body.indexOf("bridge.removeListener")
            assertTrue(vietmapClear in 0 until bridgeStop, "$signature VietMap clear must precede bridge stop")
            assertTrue(wazeClear in 0 until wazeStop, "$signature Waze clear must precede source stop")
            assertTrue(bridgeStop in 0 until listenerRemoval, "$signature bridge publishes clear before listener removal")
        }
    }

    @Test
    fun `Waze speed remains route independent and forwards zero`() {
        val start = functionBody(listener, "private fun startWazeHudSource()")
        assertTrue(start.contains("if (masterEnabled && state.navigating)"), "navigation keeps its route gate")
        assertTrue(start.contains("valueKph = state.speedLimitKmh"), "all HLP values must reach lifecycle")
        assertFalse(start.contains("state.speedLimitKmh > 0"), "zero must not be dropped")
        assertTrue(start.indexOf("valueKph = state.speedLimitKmh") > start.indexOf("if (masterEnabled && state.navigating)"))
    }

    @Test
    fun `VietMap fresh null is zero while unavailable is provider disconnect`() {
        val pusher = listener.substring(
            listener.indexOf("private val speedLimitPusher"),
            listener.indexOf("private var wazeHudSource"),
        )
        assertTrue(pusher.contains("snapshot.speedLimitKph ?: 0"))
        assertTrue(pusher.contains("snapshot.speedUpdatedAtElapsedMs"))
        assertTrue(pusher.contains("onProviderDisconnected(SpeedLimitSource.VIETMAP)"))
    }

    @Test
    fun `VietMap publishes clear before host stop and drops late callbacks`() {
        val stop = functionBody(bridge, "fun stop(owner: VietMapWidgetOwner)")
        assertTrue(stop.indexOf("clearRuntimeValues()") < stop.indexOf("publishSnapshot()"))
        assertTrue(stop.indexOf("publishSnapshot()") < stop.indexOf("listening = false"))
        assertTrue(stop.indexOf("listening = false") < stop.indexOf("host.stopListening()"))
        val callback = functionBody(bridge, "private fun onHostViewUpdated")
        assertTrue(callback.contains("if (!listening) return@onMain"))
        assertTrue(callback.contains("if (views[slot] !== view) return@onMain"))
    }

    @Test
    fun `runtime ports are independent NOOP and old distance ADAS encoding is absent`() {
        assertTrue(owner.contains("NoopSpeedSignPort(SpeedSignOutput.CLUSTER)"))
        assertTrue(owner.contains("NoopSpeedSignPort(SpeedSignOutput.HUD)"))
        assertFalse(owner.contains("distanceMeters"))
        assertFalse(owner.contains("BydHal"))
        assertFalse(owner.contains("writeSpeedLimit"))
        assertFalse(owner.contains("clearSpeedLimit"))
    }

    @Test
    fun `existing controls only feed master source and output events with typed Prefs mapping`() {
        assertTrue(main.contains("speedSign.onMasterEnabled(enabled)"))
        assertTrue(main.contains("speedSign.onSourceSelected(Prefs.speedLimitSource"))
        // Owner 2026-08-11: cluster-lane output (incl. its speed-sign CLUSTER output) follows the
        // master switch now — cb_lane removed — so it is enabled with the master (constant `true` on
        // enable), not a separate checkbox listener's `enabled`.
        assertTrue(main.contains("speedSign.onOutputEnabled(SpeedSignOutput.CLUSTER, true)"))
        // R1 (#6, docs/specs/cast-nav-ux-release-v104.html): the nav→HUD output toggle is hidden and
        // force-disabled once at init — there is no user-driven HUD-enable path anymore, so the
        // control feeds a constant `false` (not the old `enabled` from a checkbox listener).
        assertTrue(main.contains("speedSign.onOutputEnabled(SpeedSignOutput.HUD, false)"))
        assertTrue(prefs.contains("fun speedLimitSource(ctx: Context): SpeedLimitSource"))
        assertFalse(main.contains("SignCandidateGateway"))
        assertFalse(main.contains("vehicleTest"))
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "missing $signature" }
        var depth = 0
        var opened = false
        for (index in start until source.length) {
            when (source[index]) {
                '{' -> { depth++; opened = true }
                '}' -> if (opened && --depth == 0) return source.substring(start, index + 1)
            }
        }
        error("unterminated $signature")
    }
}

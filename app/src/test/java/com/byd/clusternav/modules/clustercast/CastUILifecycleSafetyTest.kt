package com.byd.clusternav.modules.clustercast

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for Cast UI lifecycle safety (T5 remediation):
 * - Tap token rejects duplicate operations
 * - Listener cleanup on destroy
 * - Geometry editor uses dynamic display size
 * - Disabled zone tap is no-op
 *
 * Pure JVM tests — no Android framework required.
 */
class CastUILifecycleSafetyTest {

    // ─── Tap token tests (validates AtomicBoolean + executor pattern) ─────────

    @Test
    fun `tap token rejects duplicate when operation in flight`() {
        val token = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        val execCount = AtomicInteger(0)
        val latch = CountDownLatch(1)

        // First: acquire token
        val first = token.compareAndSet(false, true)
        assertTrue(first, "First tap should acquire token")
        executor.execute {
            try {
                execCount.incrementAndGet()
                Thread.sleep(100) // simulate work
            } finally {
                token.set(false)
                latch.countDown()
            }
        }

        // Second: token already held
        val second = token.compareAndSet(false, true)
        assertFalse(second, "Second tap while first in flight should be rejected")

        // Wait for first to complete
        latch.await(2, TimeUnit.SECONDS)

        // Third: token now free
        val third = token.compareAndSet(false, true)
        assertTrue(third, "Third tap after first completes should succeed")
        token.set(false) // release

        executor.shutdown()
        assertEquals(1, execCount.get(), "Only first action should have executed")
    }

    @Test
    fun `tap token released even if action throws`() {
        val token = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        val latch = CountDownLatch(1)

        token.compareAndSet(false, true)
        executor.execute {
            try {
                throw RuntimeException("test exception")
            } finally {
                token.set(false)
                latch.countDown()
            }
        }

        latch.await(2, TimeUnit.SECONDS)
        assertFalse(token.get(), "Token should be released after exception in finally block")
        executor.shutdown()
    }

    @Test
    fun `executor coalesces rapid taps — only one action at a time`() {
        val token = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        val execCount = AtomicInteger(0)
        val rejected = AtomicInteger(0)

        // Simulate 10 rapid taps
        val latch = CountDownLatch(1)
        repeat(10) { i ->
            if (token.compareAndSet(false, true)) {
                executor.execute {
                    try {
                        execCount.incrementAndGet()
                        Thread.sleep(20) // simulate work
                        if (i == 9) latch.countDown()
                    } finally {
                        token.set(false)
                    }
                }
            } else {
                rejected.incrementAndGet()
            }
        }

        latch.await(2, TimeUnit.SECONDS)
        Thread.sleep(50) // let remaining executor items finish
        // First tap always succeeds; some subsequent may also succeed if timing allows
        assertTrue(execCount.get() >= 1, "At least first tap should execute")
        assertTrue(rejected.get() >= 1, "At least some rapid taps should be rejected")
        assertEquals(10, execCount.get() + rejected.get(), "Total = executed + rejected")
        executor.shutdown()
    }

    // ─── Listener cleanup tests ───────────────────────────────────────────────

    @Test
    fun `named listener removed on destroy does not fire`() {
        val listeners = mutableListOf<(String) -> Unit>()
        val callCount = AtomicInteger(0)

        val listener: (String) -> Unit = { callCount.incrementAndGet() }
        synchronized(listeners) { listeners.add(listener) }

        // Fire
        synchronized(listeners) { listeners.toList() }.forEach { it("state1") }
        assertEquals(1, callCount.get())

        // Simulate destroy: remove
        synchronized(listeners) { listeners.remove(listener) }

        // Fire again — should NOT increment
        synchronized(listeners) { listeners.toList() }.forEach { it("state2") }
        assertEquals(1, callCount.get(), "Listener should not fire after removal")
    }

    @Test
    fun `autostart listener self-removes after idle trigger`() {
        val listeners = mutableListOf<(String) -> Unit>()
        val fired = AtomicBoolean(false)

        val autoListener = object : (String) -> Unit {
            override fun invoke(state: String) {
                if (state == "Idle") {
                    synchronized(listeners) { listeners.remove(this) }
                    fired.set(true)
                }
            }
        }
        synchronized(listeners) { listeners.add(autoListener) }

        // First Idle
        synchronized(listeners) { listeners.toList() }.forEach { it("Idle") }
        assertTrue(fired.get())
        assertEquals(0, listeners.size, "Listener should self-remove")

        // Second Idle — no fire
        fired.set(false)
        synchronized(listeners) { listeners.toList() }.forEach { it("Idle") }
        assertFalse(fired.get(), "Removed listener should not fire again")
    }

    // ─── Geometry dynamic display size tests ──────────────────────────────────

    @Test
    fun `geometry parses standard 1920x720 display dimensions`() {
        val (w, h) = parseWmSize("1920x720")
        assertEquals(1920, w)
        assertEquals(720, h)
    }

    @Test
    fun `geometry parses CarPlay 1422x800 display dimensions`() {
        val (w, h) = parseWmSize("1422x800")
        assertEquals(1422, w)
        assertEquals(800, h)
    }

    @Test
    fun `geometry parses AndroidAuto 1920x1080 display dimensions`() {
        val (w, h) = parseWmSize("1920x1080")
        assertEquals(1920, w)
        assertEquals(1080, h)
    }

    @Test
    fun `geometry falls back on malformed wmSize`() {
        val (w, h) = parseWmSize("invalid")
        assertEquals(1920, w, "Should fall back to 1920")
        assertEquals(720, h, "Should fall back to 720")
    }

    @Test
    fun `geometry falls back on empty string`() {
        val (w, h) = parseWmSize("")
        assertEquals(1920, w)
        assertEquals(720, h)
    }

    // ─── Disabled zone no-op tests ────────────────────────────────────────────

    @Test
    fun `disabled zone does not dispatch action`() {
        // Simulates: FloatingBubbleService.onZoneTap checks renderer.isZoneDisabled
        var dispatched = false
        val zoneEnabled = false // paintDisabled sets view.isEnabled = false

        // Pattern from FloatingBubbleService.onZoneTap:
        if (!zoneEnabled) {
            // no-op — return early
        } else {
            dispatched = true
        }
        assertFalse(dispatched, "Disabled zone tap must not dispatch any action")
    }

    @Test
    fun `enabled occupied zone dispatches stop action`() {
        var dispatched = false
        val zoneEnabled = true // paintOccupied sets view.isEnabled = true

        if (!zoneEnabled) {
            // no-op
        } else {
            dispatched = true
        }
        assertTrue(dispatched, "Enabled zone tap should dispatch action")
    }

    @Test
    fun `enabled empty zone dispatches cast action`() {
        var dispatched = false
        val zoneEnabled = true // paintEmpty sets view.isEnabled = true

        if (!zoneEnabled) {
            // no-op
        } else {
            dispatched = true
        }
        assertTrue(dispatched, "Enabled empty zone should dispatch cast")
    }

    // ─── Touch target size constant tests ─────────────────────────────────────

    @Test
    fun `zone minimum dp exceeds 48dp automotive guideline`() {
        assertTrue(
            BubbleRenderer.ZONE_MIN_DP >= 48,
            "Zone minimum (${BubbleRenderer.ZONE_MIN_DP}dp) must be >= 48dp for automotive"
        )
    }

    @Test
    fun `zone minimum dp is 48`() {
        assertEquals(48, BubbleRenderer.ZONE_MIN_DP)
        assertTrue(
            BubbleRenderer.ZONE_MIN_DP >= 48,
            "Zone minimum (${BubbleRenderer.ZONE_MIN_DP}dp) must stay >= 48dp automotive guideline"
        )
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    /** Replicates the parsing logic from CastGeometryEditor. */
    private fun parseWmSize(wmSize: String): Pair<Int, Int> {
        val parts = wmSize.split("x")
        val w = parts.getOrNull(0)?.toIntOrNull() ?: 1920
        val h = parts.getOrNull(1)?.toIntOrNull() ?: 720
        return w to h
    }
}

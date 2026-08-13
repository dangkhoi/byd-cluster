package com.byd.clusternav.voicekey

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VoiceKeyMatcherTest {

    private val KEY = 231

    private fun cfg(
        enabled: Boolean = true,
        keyCode: Int = KEY,
        gesture: VoiceKeyGesture = VoiceKeyGesture.PRESS,
        target: VoiceKeyTarget = VoiceKeyTarget.ASSIST,
    ) = VoiceKeyConfig(enabled, keyCode, gesture, target)

    @Test
    fun `disabled ignores everything`() {
        val m = VoiceKeyMatcher(500)
        assertEquals(VoiceKeyDecision.IGNORE, m.onKey(cfg(enabled = false), VoiceKeyAction.DOWN, KEY, 0, 0, 0))
        assertEquals(VoiceKeyDecision.IGNORE, m.onKey(cfg(enabled = false), VoiceKeyAction.UP, KEY, 0, 10, 0))
    }

    @Test
    fun `wrong keycode is ignored (never touches other buttons)`() {
        val m = VoiceKeyMatcher(500)
        assertEquals(VoiceKeyDecision.IGNORE, m.onKey(cfg(), VoiceKeyAction.DOWN, 99, 0, 0, 0))
        assertEquals(VoiceKeyDecision.IGNORE, m.onKey(cfg(gesture = VoiceKeyGesture.HOLD), VoiceKeyAction.UP, 99, 0, 800, 0))
    }

    @Test
    fun `press fires once on short release and consumes both edges`() {
        val m = VoiceKeyMatcher(500)
        val down = m.onKey(cfg(), VoiceKeyAction.DOWN, KEY, 0, 0, 0)
        assertFalse(down.fire); assertTrue(down.consume)
        val up = m.onKey(cfg(), VoiceKeyAction.UP, KEY, 0, 120, 0)
        assertTrue(up.fire); assertTrue(up.consume)
    }

    @Test
    fun `press held too long does not fire but still consumes`() {
        val m = VoiceKeyMatcher(500)
        m.onKey(cfg(), VoiceKeyAction.DOWN, KEY, 0, 0, 0)
        val up = m.onKey(cfg(), VoiceKeyAction.UP, KEY, 0, 900, 0)
        assertFalse(up.fire); assertTrue(up.consume)
    }

    @Test
    fun `hold lets a short tap pass through untouched (no native override)`() {
        val m = VoiceKeyMatcher(500)
        val down = m.onKey(cfg(gesture = VoiceKeyGesture.HOLD), VoiceKeyAction.DOWN, KEY, 0, 0, 0)
        assertEquals(VoiceKeyDecision.IGNORE, down)          // native sees DOWN
        val up = m.onKey(cfg(gesture = VoiceKeyGesture.HOLD), VoiceKeyAction.UP, KEY, 0, 120, 0)
        assertFalse(up.fire); assertFalse(up.consume)        // native sees UP → native tap intact
    }

    @Test
    fun `hold fires on long release for a non-repeating key`() {
        val m = VoiceKeyMatcher(500)
        m.onKey(cfg(gesture = VoiceKeyGesture.HOLD), VoiceKeyAction.DOWN, KEY, 0, 0, 0)
        val up = m.onKey(cfg(gesture = VoiceKeyGesture.HOLD), VoiceKeyAction.UP, KEY, 0, 700, 0)
        assertTrue(up.fire); assertTrue(up.consume)
    }

    @Test
    fun `hold fires once on repeat then swallows the rest of that press`() {
        val m = VoiceKeyMatcher(500)
        val g = VoiceKeyGesture.HOLD
        assertEquals(VoiceKeyDecision.IGNORE, m.onKey(cfg(gesture = g), VoiceKeyAction.DOWN, KEY, 0, 0, 0))
        assertEquals(VoiceKeyDecision.IGNORE, m.onKey(cfg(gesture = g), VoiceKeyAction.DOWN, KEY, 0, 300, 1)) // below threshold
        val fired = m.onKey(cfg(gesture = g), VoiceKeyAction.DOWN, KEY, 0, 550, 2)
        assertTrue(fired.fire); assertTrue(fired.consume)
        val more = m.onKey(cfg(gesture = g), VoiceKeyAction.DOWN, KEY, 0, 800, 3)
        assertFalse(more.fire); assertTrue(more.consume)     // already fired → swallow repeats
        val up = m.onKey(cfg(gesture = g), VoiceKeyAction.UP, KEY, 0, 900, 0)
        assertFalse(up.fire); assertTrue(up.consume)
    }

    @Test
    fun `reset lets the next press fire again`() {
        val m = VoiceKeyMatcher(500)
        m.onKey(cfg(), VoiceKeyAction.DOWN, KEY, 0, 0, 0)
        assertTrue(m.onKey(cfg(), VoiceKeyAction.UP, KEY, 0, 100, 0).fire)
        m.reset()
        m.onKey(cfg(), VoiceKeyAction.DOWN, KEY, 1000, 1000, 0)
        assertTrue(m.onKey(cfg(), VoiceKeyAction.UP, KEY, 1000, 1100, 0).fire)
    }
}

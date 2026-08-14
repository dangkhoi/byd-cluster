package com.byd.clusternav.voicekey

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VoiceKeyMatcherTest {

    private val KEY = 328
    private fun cfg(enabled: Boolean = true, keyCode: Int = KEY) = VoiceKeyConfig(enabled, keyCode)

    @Test
    fun `disabled ignores everything (native untouched)`() {
        val m = VoiceKeyMatcher()
        assertEquals(VoiceKeyDecision.IGNORE, m.onKey(cfg(enabled = false), VoiceKeyAction.DOWN, KEY, 0))
        assertEquals(VoiceKeyDecision.IGNORE, m.onKey(cfg(enabled = false), VoiceKeyAction.UP, KEY, 0))
    }

    @Test
    fun `wrong keycode is ignored (never touches other buttons)`() {
        val m = VoiceKeyMatcher()
        assertEquals(VoiceKeyDecision.IGNORE, m.onKey(cfg(), VoiceKeyAction.DOWN, 99, 0))
        assertEquals(VoiceKeyDecision.IGNORE, m.onKey(cfg(), VoiceKeyAction.UP, 99, 0))
    }

    @Test
    fun `fires once on down and consumes the whole press`() {
        val m = VoiceKeyMatcher()
        val down = m.onKey(cfg(), VoiceKeyAction.DOWN, KEY, 100)
        assertTrue(down.fire); assertTrue(down.consume)
        val repeat = m.onKey(cfg(), VoiceKeyAction.DOWN, KEY, 100)   // auto-repeat, same press
        assertFalse(repeat.fire); assertTrue(repeat.consume)
        val up = m.onKey(cfg(), VoiceKeyAction.UP, KEY, 100)
        assertFalse(up.fire); assertTrue(up.consume)                 // swallow the UP of our key
    }

    @Test
    fun `fires regardless of hold duration (no 500ms gate)`() {
        val m = VoiceKeyMatcher()
        assertTrue(m.onKey(cfg(), VoiceKeyAction.DOWN, KEY, 0).fire) // long-held pulse still fires
        assertFalse(m.onKey(cfg(), VoiceKeyAction.UP, KEY, 0).fire)  // late UP only consumed
    }

    @Test
    fun `each distinct press fires again`() {
        val m = VoiceKeyMatcher()
        assertTrue(m.onKey(cfg(), VoiceKeyAction.DOWN, KEY, 100).fire)
        m.onKey(cfg(), VoiceKeyAction.UP, KEY, 100)
        assertTrue(m.onKey(cfg(), VoiceKeyAction.DOWN, KEY, 200).fire)  // new downTime → new press
    }

    @Test
    fun `reset lets the next press fire again`() {
        val m = VoiceKeyMatcher()
        assertTrue(m.onKey(cfg(), VoiceKeyAction.DOWN, KEY, 100).fire)
        m.reset()
        assertTrue(m.onKey(cfg(), VoiceKeyAction.DOWN, KEY, 100).fire) // same downTime but reset cleared
    }
}

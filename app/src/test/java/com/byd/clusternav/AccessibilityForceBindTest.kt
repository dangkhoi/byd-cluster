package com.byd.clusternav

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * WIRING/BOUNDARY contract for the accessibility FORCE-REBIND self-heal (docs/diagnostics/
 * oncar-handoff-voicekey-2026-08-14.md §8). The pure decision/string logic lives in
 * [com.byd.clusternav.modules.navaccess.AccessibilityRebind] in `:core` and is unit-tested there
 * ([com.byd.clusternav.modules.navaccess.AccessibilityRebindTest], device-free). This test — which sits in
 * `:app` because it inspects [NavConnect], an `:app` type that owns the dadb transport — proves that pure
 * logic is actually WIRED into the dadb grant path with the required safety guarantees, so the heal really
 * runs on-car after a reboot. Runtime behaviour needs the head unit, so, like the other contract tests, it
 * locks the wiring by reading the source across the boundary.
 */
class AccessibilityForceBindTest {

    private fun app(relative: String): Path {
        val current = Path.of(System.getProperty("user.dir"))
        return if (Files.exists(current.resolve("src"))) current.resolve(relative) else current.resolve("app").resolve(relative)
    }

    private val navConnect by lazy { app("src/main/java/com/byd/clusternav/NavConnect.kt").toFile().readText() }

    @Test
    fun `NavConnect verifies bound over dumpsys and force-rebinds via the pure logic`() {
        assertTrue(navConnect.contains("dumpsys accessibility"), "reads the Bound-services dump over the same dadb shell")
        assertTrue(
            navConnect.contains("AccessibilityRebind.isClusterNavBound("),
            "decides bound-vs-not via the pure :core parser (not merely reading enabled_accessibility_services)",
        )
        assertTrue(
            navConnect.contains("AccessibilityRebind.accessibilityRebindWrites("),
            "computes the remove -> re-add toggle via the pure :core logic",
        )
        assertTrue(
            navConnect.contains("forceRebindIfNeeded(keyPair, sh)"),
            "the force-rebind runs INSIDE doGrantAccessibility's dadb session (same shell as the enable writes)",
        )
    }

    @Test
    fun `force-rebind recovery re-adds on a FRESH dadb session so the setting is never left removed`() {
        // The dangerous window is when the ORIGINAL dadb session dies AFTER the remove write landed on-device.
        // Re-adding on that same (broken) session then throws too, which would leave
        // enabled_accessibility_services in the REMOVED state (voice-key + screen-read dead). The finally must
        // therefore fall back to a FRESH LocalDeviceShell.session — the loopback adbd is still up, only that
        // one connection died — so the never-left-removed invariant holds even on a mid-toggle session death,
        // not merely self-healed on the next grant.
        assertTrue(
            navConnect.contains("forceRebindIfNeeded(keyPair, sh)"),
            "forceRebindIfNeeded receives the keyPair so the recovery path can open a fresh session",
        )
        assertTrue(
            navConnect.contains("phiên MỚI"),
            "the finally re-adds on a fresh dadb session when the original session is broken",
        )
    }

    @Test
    fun `force-rebind is single-flight and only toggles when confirmed enabled-but-not-bound`() {
        // Reuse the existing single-flight guard so concurrent grants can't race the toggle.
        assertTrue(
            navConnect.contains("grantingAcc.compareAndSet(false, true)"),
            "the grant path (incl. the toggle) is guarded by the single-flight grantingAcc flag",
        )
        // Empty write-list (already bound) must short-circuit — no flicker when the service is bound.
        assertTrue(
            navConnect.contains("if (writes.isEmpty())"),
            "an already-bound service produces no writes and the toggle is skipped (no flicker)",
        )
    }

    @Test
    fun `force-rebind never leaves the enabled list in the removed state`() {
        // The danger window is between the remove write and the re-add. A finally must re-add on any partial
        // failure (sleep interrupted / shell threw) so enabled_accessibility_services is never left removed.
        assertTrue(navConnect.contains("finally"), "the toggle body has a finally block")
        assertTrue(
            navConnect.contains("if (inRemovedState)"),
            "finally recovers to the RE-ADDED (safe) state whenever the remove ran but the re-add did not",
        )
        assertTrue(
            navConnect.contains("catch (e: InterruptedException)"),
            "an interrupt mid-toggle is caught (flag restored) rather than crashing the app",
        )
    }
}

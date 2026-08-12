package com.byd.clusternav

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * WIRING contract for the three UI features shipped with the nav-display / split-ratio work
 * (owner 2026-08-12). Their runtime behaviour needs Android (Activity/View/SharedPreferences), so —
 * like [CastEnableToggleContractTest], [FloatingBubbleFirstLaunchContractTest] and
 * [NotificationAccessFlowContractTest] — this locks the wiring by reading the source across the whole
 * boundary. Unit tests cover the pure logic ([com.byd.clusternav.modules.clustercast.ClusterNavLaneWidget.shouldAssert]
 * and [com.byd.clusternav.modules.clustercast.simplified.SimpleCastCoordinator.applySplitRatioLive]);
 * this proves those pieces are actually BOUND so a user can reach them. On-car visual checks live in
 * the spec.
 *
 * Covers:
 *  • Item 1 — nav-display 2-button selector: both layouts carry the buttons, MainActivity binds the
 *    controller, Prefs declares the two modes and defaults to centre+ETA, and the NAV track drives the
 *    op-39 gate (nav-only + centre mode only) via the notification listener.
 *  • Item 2 — 9 visual split-ratio buttons replacing the old spinner: both layouts, controller
 *    bind/teardown, the removed spinner population, and the live coordinator path the buttons call.
 *  • Item 4 — bubble immediate-show on onResume, gated on castEnabled && canDrawOverlays.
 *  • Renderer LOC guard — MainActivityCastController stays a thin renderer (< 501 lines).
 */
class NavCastUiWiringContractTest {

    // ── source helpers (mirror CastEnableToggleContractTest) ─────────────────
    private fun app(relative: String): Path {
        val current = Path.of(System.getProperty("user.dir"))
        return if (Files.exists(current.resolve("src"))) current.resolve(relative)
        else current.resolve("app").resolve(relative)
    }

    private fun core(relative: String): Path {
        val current = Path.of(System.getProperty("user.dir"))
        return if (Files.exists(current.resolve("src"))) current.resolve("..").resolve("core").resolve(relative)
        else current.resolve("core").resolve(relative)
    }

    private fun read(path: Path): String = path.toFile().readText()

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "missing $signature" }
        val after = start + signature.length
        val next = listOf("\n    fun ", "\n    private fun ", "\n    override fun ", "\n    companion object", "\n}")
            .mapNotNull { source.indexOf(it, after).takeIf { i -> i >= 0 } }
            .minOrNull() ?: source.length
        return source.substring(start, next)
    }

    private val mainActivity by lazy { read(app("src/main/java/com/byd/clusternav/MainActivity.kt")) }
    private val controllerPath by lazy { app("src/main/java/com/byd/clusternav/modules/clustercast/MainActivityCastController.kt") }
    private val controller by lazy { read(controllerPath) }
    private val autostart by lazy { read(app("src/main/java/com/byd/clusternav/modules/clustercast/CastAutostart.kt")) }
    private val splitButtons by lazy { read(app("src/main/java/com/byd/clusternav/modules/clustercast/CastSplitRatioButtons.kt")) }
    private val listener by lazy { read(app("src/main/java/com/byd/clusternav/NavNotificationListener.kt")) }
    private val laneWidget by lazy { read(app("src/main/java/com/byd/clusternav/modules/clustercast/ClusterNavLaneWidget.kt")) }
    private val prefs by lazy { read(app("src/main/java/com/byd/clusternav/Prefs.kt")) }
    private val coordinator by lazy { read(core("src/main/kotlin/com/byd/clusternav/modules/clustercast/simplified/SimpleCastCoordinator.kt")) }
    private val layoutNarrow by lazy { read(app("src/main/res/layout/activity_main.xml")) }
    private val layoutWide by lazy { read(app("src/main/res/layout-w960dp/activity_main.xml")) }

    // ── Item 1: nav-display 2-button selector ────────────────────────────────
    @Test
    fun `both layouts carry the two nav-mode buttons`() {
        for ((name, xml) in listOf("narrow" to layoutNarrow, "wide" to layoutWide)) {
            assertTrue(xml.contains("@+id/btn_nav_mode_small"), "$name: small/top button present")
            assertTrue(xml.contains("@+id/btn_nav_mode_center"), "$name: centre+ETA button present")
        }
    }

    @Test
    fun `main activity binds the nav-mode buttons`() {
        assertTrue(
            mainActivity.contains("NavClusterModeButtons(this).bind()"),
            "MainActivity.onCreate must bind NavClusterModeButtons so the selector is reachable",
        )
    }

    @Test
    fun `prefs declares the two nav modes and defaults to centre+ETA`() {
        assertTrue(prefs.contains("NAV_MODE_CENTER_ETA = \"center_eta\""), "centre mode constant present")
        assertTrue(prefs.contains("NAV_MODE_SMALL_TOP = \"small_top\""), "small/top mode constant present")
        assertTrue(
            prefs.contains("getString(K_NAV_CLUSTER_MODE, NAV_MODE_CENTER_ETA)"),
            "navClusterMode default is centre+ETA",
        )
    }

    @Test
    fun `nav track drives the op39 gate only in nav-only centre mode`() {
        // The listener (NAV track) tells the widget when nav starts/stops.
        assertTrue(listener.contains("ClusterNavLaneWidget.onNavActive("), "listener asserts the widget on nav active")
        assertTrue(listener.contains("ClusterNavLaneWidget.onNavIdle()"), "listener clears the widget on nav idle")
        // The pure gate withholds op39 unless nav-only (cast OFF) AND centre mode.
        assertTrue(
            laneWidget.contains("navActive && !castEnabled && centerMode"),
            "shouldAssert gates on nav active + cast OFF + centre mode",
        )
        // op39 is the OEM 'simple navigation' opcode on the AutoContainer 1000-channel.
        assertTrue(laneWidget.contains("OP_SIMPLE_NAV = 39"), "opcode is 39")
        assertTrue(laneWidget.contains("AutoContainer 2 i32 1000 i32"), "asserted via the 1000-channel service call")
        // Two-track boundary: the gate reads the persisted castEnabled config + the nav-mode pref.
        assertTrue(laneWidget.contains("castEnabled()"), "gate reads the persisted cast-enabled flag")
        assertTrue(laneWidget.contains("NAV_MODE_CENTER_ETA"), "gate reads the nav-mode pref")
    }

    // ── Item 2: 9 split-ratio buttons replace the spinner ────────────────────
    @Test
    fun `both layouts replace the split-ratio spinner with the buttons container`() {
        for ((name, xml) in listOf("narrow" to layoutNarrow, "wide" to layoutWide)) {
            assertTrue(xml.contains("@+id/split_ratio_buttons"), "$name: split-ratio buttons container present")
            assertTrue(!xml.contains("spinner_split_ratio"), "$name: old split-ratio spinner removed")
        }
    }

    @Test
    fun `controller binds and tears down the split-ratio buttons`() {
        assertTrue(controller.contains("CastSplitRatioButtons(activity, coordinator)"), "controller constructs the buttons")
        assertTrue(controller.contains("splitRatioButtons.bind()"), "controller binds the buttons")
        assertTrue(controller.contains("splitRatioButtons.destroy()"), "controller releases the buttons on destroy")
    }

    @Test
    fun `autostart no longer populates a split-ratio spinner`() {
        assertTrue(!autostart.contains("populateSplitRatioSpinner"), "the spinner population is removed from autostart")
        assertTrue(!autostart.contains("spinner_split_ratio"), "no reference to the removed spinner id")
    }

    @Test
    fun `split buttons wire to the live coordinator path`() {
        assertTrue(coordinator.contains("fun applySplitRatioLive("), "coordinator exposes the live split-ratio path")
        assertTrue(
            splitButtons.contains("coordinator.applySplitRatioLive("),
            "buttons call the LIVE simplified path (not the disconnected legacy v2 store)",
        )
    }

    // ── Item 4: bubble immediate-show on resume ──────────────────────────────
    @Test
    fun `onResume shows the bubble gated on castEnabled and overlay permission`() {
        val resume = functionBody(mainActivity, "override fun onResume()")
        assertTrue(resume.contains(".castEnabled()"), "reads the master enable")
        assertTrue(resume.contains("Settings.canDrawOverlays(this)"), "checks the overlay permission")
        assertTrue(resume.contains("startForegroundService("), "starts the bubble service")
        assertTrue(resume.contains("FloatingBubbleService"), "the started service is the bubble")
        assertTrue(
            resume.indexOf(".castEnabled()") < resume.indexOf("startForegroundService("),
            "castEnabled guard precedes the service start",
        )
        assertTrue(
            resume.indexOf("Settings.canDrawOverlays(this)") < resume.indexOf("startForegroundService("),
            "overlay-permission guard precedes the service start",
        )
    }

    // ── Renderer LOC guard (thin renderer/dispatcher, not an orchestrator) ────
    @Test
    fun `cast controller stays a thin renderer under 501 lines`() {
        val lines = controllerPath.toFile().readLines().size
        assertTrue(lines < 501, "MainActivityCastController must stay a thin renderer (< 501 lines); was $lines")
    }
}

package com.byd.clusternav.modules.clustercast.simplified

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SimpleCastCoordinatorTest {

    private lateinit var shell: FakeShell
    private lateinit var prefs: FakePrefs
    private lateinit var coordinator: SimpleCastCoordinator

    @BeforeEach
    fun setup() {
        shell = FakeShell()
        prefs = FakePrefs()
        val projection = ProjectionManager(shell, sleepMs = {}) // no actual sleep in tests
        val configurator = DisplayConfigurator(shell)
        val mover = AppMover(shell, sleepMs = {})
        coordinator = SimpleCastCoordinator(projection, configurator, mover, prefs, shell, displayId = 1)
    }

    // ─── Projection lifecycle ─────────────────────────────────────────────────

    @Test
    fun `initial state is Off`() {
        assertEquals(SimpleCastState.Off, coordinator.state)
    }

    @Test
    fun `openProjection transitions Off to Idle`() {
        coordinator.openProjection()
        awaitState<SimpleCastState.Idle>()
        assertEquals(SimpleCastState.Idle, coordinator.state)
    }

    @Test
    fun `openProjection failure produces Error state`() {
        shell.shouldFail = true
        coordinator.openProjection()
        awaitState<SimpleCastState.Error>()
        assertTrue(coordinator.state is SimpleCastState.Error)
    }

    @Test
    fun `openProjection issues seal commands 30, 16, 35`() {
        coordinator.openProjection()
        awaitState<SimpleCastState.Idle>()
        val cmds = shell.history.filter { it.contains("AutoContainer") }
        assertEquals(3, cmds.size)
        assertTrue(cmds[0].contains("i32 30"))
        assertTrue(cmds[1].contains("i32 16"))
        assertTrue(cmds[2].contains("i32 35"))
    }

    @Test
    fun `closeProjection transitions to Off`() {
        coordinator.openProjection()
        awaitState<SimpleCastState.Idle>()
        coordinator.closeProjection()
        awaitState<SimpleCastState.Off>()
        assertEquals(SimpleCastState.Off, coordinator.state)
    }

    @Test
    fun `closeProjection issues commands 18, 0`() {
        coordinator.openProjection()
        awaitState<SimpleCastState.Idle>()
        shell.history.clear()
        coordinator.closeProjection()
        awaitState<SimpleCastState.Off>()
        val cmds = shell.history.filter { it.contains("AutoContainer") }
        assertEquals(2, cmds.size)
        assertTrue(cmds[0].contains("i32 18"))
        assertTrue(cmds[1].contains("i32 0"))
    }

    // ─── Cast full (CP/AA) ────────────────────────────────────────────────────

    @Test
    fun `cast CP full from Idle`() {
        coordinator.openProjection()
        awaitState<SimpleCastState.Idle>()
        coordinator.dispatch(SimpleCastIntent.CastFull("com.byd.autolink.carplay", AppType.CARPLAY))
        awaitState<SimpleCastState.CastingFull>()
        val s = coordinator.state as SimpleCastState.CastingFull
        assertEquals("com.byd.autolink.carplay", s.targetPkg)
        assertEquals(AppType.CARPLAY, s.appType)
        assertEquals(DisplayConfig.CARPLAY, s.displayConfig)
    }

    @Test
    fun `cast CP sets correct wm size`() {
        coordinator.openProjection()
        awaitState<SimpleCastState.Idle>()
        shell.history.clear()
        coordinator.dispatch(SimpleCastIntent.CastFull("com.byd.autolink.carplay", AppType.CARPLAY))
        awaitState<SimpleCastState.CastingFull>()
        assertTrue(shell.history.any { it.contains("wm size 1422x800 -d 1") })
        assertTrue(shell.history.any { it.contains("wm overscan 10,-120,10,50 -d 1") })
    }

    @Test
    fun `cast AA sets correct wm size`() {
        coordinator.openProjection()
        awaitState<SimpleCastState.Idle>()
        shell.history.clear()
        coordinator.dispatch(
            SimpleCastIntent.CastFull("com.google.android.projection.gearhead", AppType.ANDROID_AUTO)
        )
        awaitState<SimpleCastState.CastingFull>()
        assertTrue(shell.history.any { it.contains("wm size 1920x1080 -d 1") })
        assertTrue(shell.history.any { it.contains("wm overscan 0,0,0,0 -d 1") })
    }

    // ─── Stop ─────────────────────────────────────────────────────────────────

    @Test
    fun `stop from CastingFull returns to Idle`() {
        coordinator.openProjection()
        awaitState<SimpleCastState.Idle>()
        coordinator.dispatch(SimpleCastIntent.CastFull("com.test.app", AppType.NORMAL))
        awaitState<SimpleCastState.CastingFull>()
        coordinator.dispatch(SimpleCastIntent.Stop())
        awaitState<SimpleCastState.Idle>()
        assertEquals(SimpleCastState.Idle, coordinator.state)
    }

    @Test
    fun `stop from Idle is no-op`() {
        coordinator.openProjection()
        awaitState<SimpleCastState.Idle>()
        coordinator.dispatch(SimpleCastIntent.Stop())
        Thread.sleep(50)
        assertEquals(SimpleCastState.Idle, coordinator.state)
    }

    // ─── Split mode ───────────────────────────────────────────────────────────

    @Test
    fun `cast to left slot creates CastingSplit`() {
        coordinator.openProjection()
        awaitState<SimpleCastState.Idle>()
        coordinator.dispatch(SimpleCastIntent.CastSlot("com.test.left", ClusterSlotSide.LEFT))
        awaitState<SimpleCastState.CastingSplit>()
        val s = coordinator.state as SimpleCastState.CastingSplit
        assertEquals("com.test.left", s.left?.pkg)
        assertNull(s.right)
    }

    @Test
    fun `cast to slot applies display config before move`() {
        coordinator.openProjection()
        awaitState<SimpleCastState.Idle>()
        shell.history.clear()
        coordinator.dispatch(SimpleCastIntent.CastSlot("com.test.left", ClusterSlotSide.LEFT))
        awaitState<SimpleCastState.CastingSplit>()
        // Should apply wm size and overscan for NORMAL type before issuing am start
        val wmSizeIndex = shell.history.indexOfFirst { it.contains("wm size") }
        val amStartIndex = shell.history.indexOfFirst { it.contains("am start") }
        assertTrue(wmSizeIndex >= 0, "wm size command should be issued")
        assertTrue(amStartIndex >= 0, "am start command should be issued")
        assertTrue(wmSizeIndex < amStartIndex, "wm size must come before am start")
    }

    @Test
    fun `cast both slots creates full split`() {
        coordinator.openProjection()
        awaitState<SimpleCastState.Idle>()
        coordinator.dispatch(SimpleCastIntent.CastSlot("com.test.left", ClusterSlotSide.LEFT))
        awaitState<SimpleCastState.CastingSplit>()
        coordinator.dispatch(SimpleCastIntent.CastSlot("com.test.right", ClusterSlotSide.RIGHT))
        // wait for the second slot
        Thread.sleep(100)
        val s = coordinator.state as SimpleCastState.CastingSplit
        assertEquals("com.test.left", s.left?.pkg)
        assertEquals("com.test.right", s.right?.pkg)
    }

    @Test
    fun `stop left slot keeps right`() {
        coordinator.openProjection()
        awaitState<SimpleCastState.Idle>()
        coordinator.dispatch(SimpleCastIntent.CastSlot("com.test.left", ClusterSlotSide.LEFT))
        awaitState<SimpleCastState.CastingSplit>()
        coordinator.dispatch(SimpleCastIntent.CastSlot("com.test.right", ClusterSlotSide.RIGHT))
        Thread.sleep(100)
        coordinator.dispatch(SimpleCastIntent.Stop(slot = ClusterSlotSide.LEFT))
        Thread.sleep(100)
        val s = coordinator.state as SimpleCastState.CastingSplit
        assertNull(s.left)
        assertEquals("com.test.right", s.right?.pkg)
    }

    @Test
    fun `stop only occupied slot returns to Idle without invariant crash`() {
        coordinator.openProjection()
        awaitState<SimpleCastState.Idle>()
        // Only left slot occupied, right is null
        coordinator.dispatch(SimpleCastIntent.CastSlot("com.test.left", ClusterSlotSide.LEFT))
        awaitState<SimpleCastState.CastingSplit>()
        // Stop the only occupied slot specifically → should go to Idle, not crash
        coordinator.dispatch(SimpleCastIntent.Stop(slot = ClusterSlotSide.LEFT))
        awaitState<SimpleCastState.Idle>()
        assertEquals(SimpleCastState.Idle, coordinator.state)
    }

    @Test
    fun `stop both slots in split returns to Idle`() {
        coordinator.openProjection()
        awaitState<SimpleCastState.Idle>()
        coordinator.dispatch(SimpleCastIntent.CastSlot("com.test.left", ClusterSlotSide.LEFT))
        awaitState<SimpleCastState.CastingSplit>()
        coordinator.dispatch(SimpleCastIntent.Stop(slot = null)) // stop all
        awaitState<SimpleCastState.Idle>()
        assertEquals(SimpleCastState.Idle, coordinator.state)
    }

    // ─── Invalid transitions ──────────────────────────────────────────────────

    @Test
    fun `cast from Off is ignored`() {
        coordinator.dispatch(SimpleCastIntent.CastFull("com.test", AppType.NORMAL))
        Thread.sleep(50)
        assertEquals(SimpleCastState.Off, coordinator.state)
    }

    @Test
    fun `cast split from CastingFull is ignored`() {
        coordinator.openProjection()
        awaitState<SimpleCastState.Idle>()
        coordinator.dispatch(SimpleCastIntent.CastFull("com.test", AppType.NORMAL))
        awaitState<SimpleCastState.CastingFull>()
        coordinator.dispatch(SimpleCastIntent.CastSlot("com.other", ClusterSlotSide.LEFT))
        Thread.sleep(50)
        assertTrue(coordinator.state is SimpleCastState.CastingFull)
    }

    // ─── Repeated cast ────────────────────────────────────────────────────────

    @Test
    fun `cast - stop - cast cycle works multiple times`() {
        coordinator.openProjection()
        awaitState<SimpleCastState.Idle>()
        repeat(3) {
            coordinator.dispatch(SimpleCastIntent.CastFull("com.test.app", AppType.NORMAL))
            awaitState<SimpleCastState.CastingFull>()
            coordinator.dispatch(SimpleCastIntent.Stop())
            awaitState<SimpleCastState.Idle>()
        }
        assertEquals(SimpleCastState.Idle, coordinator.state)
    }

    // ─── State listener ───────────────────────────────────────────────────────

    @Test
    fun `state listener receives transitions`() {
        val states = CopyOnWriteArrayList<SimpleCastState>()
        coordinator.addStateListener { states.add(it) }
        coordinator.openProjection()
        awaitState<SimpleCastState.Idle>()
        // Should have: Off (initial emit), Opening, Idle
        assertTrue(states.size >= 2)
        assertTrue(states.last() == SimpleCastState.Idle)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private inline fun <reified T : SimpleCastState> awaitState(timeoutMs: Long = 2000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (coordinator.state !is T && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(coordinator.state is T, "Expected ${T::class.simpleName} but got ${coordinator.state}")
    }
}

// ─── Fakes ────────────────────────────────────────────────────────────────────

class FakeShell : SimpleCastShell {
    val history = CopyOnWriteArrayList<String>()
    var shouldFail = false
    /** Packages to simulate as already running (with taskIds) for am stack list. */
    val runningTasks = mutableMapOf<String, Int>()

    init {
        // CP/AA are always already running when user requests cast (they're system apps)
        runningTasks["com.byd.autolink.carplay"] = 10
        runningTasks["com.google.android.projection.gearhead"] = 11
    }

    override fun execute(command: String): ShellResult {
        history.add(command)
        if (shouldFail) return ShellResult(1, "", "fake error")
        // Simulate am stack list output for task/stack discovery
        if (command == "am stack list") {
            return ShellResult(0, fakeStackListOutput(), "")
        }
        return ShellResult(0, "", "")
    }

    /** Simulates am stack list with tasks on display 0 and a freeform stack on display 1. */
    private fun fakeStackListOutput(): String {
        val sb = StringBuilder()
        // Home stack on display 0
        sb.appendLine("Stack id=0 bounds=[0,0][1920,720] displayId=0 userId=0")
        sb.appendLine("  taskId=1: com.android.launcher3/com.android.launcher3.Launcher visible=true")
        // Running tasks on display 0
        for ((pkg, taskId) in runningTasks) {
            sb.appendLine("  taskId=$taskId: $pkg/.MainActivity visible=true")
        }
        // If settings was launched to display 1 (for stack creation), show a stack there
        if (history.any { it.contains("--display 1") }) {
            sb.appendLine("Stack id=2 bounds=[0,0][1920,720] displayId=1 userId=0")
            sb.appendLine("  taskId=99: com.android.settings/.Settings visible=true")
        }
        return sb.toString()
    }
}

class FakePrefs : SimpleCastPrefs {
    private val configs = mutableMapOf<String, DisplayConfig>()
    private var lastDisplay: Int? = null
    private var _autoStartPackage: String? = null
    private var _autoStartEnabled: Boolean = false
    private var _splitRatioLeftPercent: Int = 50
    private var _dozeWhitelistApplied: Boolean = false
    private var _autoStartLeftPackage: String? = null
    private var _autoStartRightPackage: String? = null
    private var _autoStartSplitEnabled: Boolean = false

    override fun displayConfigFor(pkg: String): DisplayConfig? = configs[pkg]
    override fun saveDisplayConfig(pkg: String, config: DisplayConfig) { configs[pkg] = config }
    override fun lastDisplayId(): Int? = lastDisplay
    override fun saveLastDisplayId(id: Int) { lastDisplay = id }

    override fun autoStartPackage(): String? = _autoStartPackage
    override fun setAutoStartPackage(pkg: String?) { _autoStartPackage = pkg }
    override fun autoStartEnabled(): Boolean = _autoStartEnabled
    override fun setAutoStartEnabled(enabled: Boolean) { _autoStartEnabled = enabled }

    override fun splitRatioLeftPercent(): Int = _splitRatioLeftPercent
    override fun setSplitRatioLeftPercent(pct: Int) { _splitRatioLeftPercent = pct }

    override fun dozeWhitelistApplied(): Boolean = _dozeWhitelistApplied
    override fun setDozeWhitelistApplied(applied: Boolean) { _dozeWhitelistApplied = applied }

    override fun autoStartLeftPackage(): String? = _autoStartLeftPackage
    override fun setAutoStartLeftPackage(pkg: String?) { _autoStartLeftPackage = pkg }
    override fun autoStartRightPackage(): String? = _autoStartRightPackage
    override fun setAutoStartRightPackage(pkg: String?) { _autoStartRightPackage = pkg }
    override fun autoStartSplitEnabled(): Boolean = _autoStartSplitEnabled
    override fun setAutoStartSplitEnabled(enabled: Boolean) { _autoStartSplitEnabled = enabled }
}

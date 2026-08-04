package com.byd.clusternav.modules.clustercast.simplified

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Simplified Cluster Cast coordinator.
 *
 * Single owner of projection/cast state. All mutations run on a dedicated serial executor.
 * UI observes [state] and emits [SimpleCastIntent]. No complex verification, no recovery chains.
 *
 * Flow:
 * 1. App starts → [openProjection] called → state = IDLE
 * 2. User taps cast → [dispatch(CastFull/CastSlot)] → state = CASTING_FULL/SPLIT
 * 3. User taps stop → [dispatch(Stop)] → state = IDLE
 * 4. User exits app → [closeProjection] → state = OFF
 */
class SimpleCastCoordinator(
    private val projection: ProjectionManager,
    private val configurator: DisplayConfigurator,
    private val mover: AppMover,
    val prefs: SimpleCastPrefs,
    private val shell: SimpleCastShell,
    private val displayId: Int,
) {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SimpleCast").apply { isDaemon = true }
    }

    private fun log(msg: String) {
        println("[SimpleCast] $msg")
    }

    private val _state = AtomicReference<SimpleCastState>(SimpleCastState.Off)
    val state: SimpleCastState get() = _state.get()

    private val listeners = mutableListOf<(SimpleCastState) -> Unit>()

    fun addStateListener(listener: (SimpleCastState) -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
        listener(state) // emit current immediately
    }

    fun removeStateListener(listener: (SimpleCastState) -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private fun setState(new: SimpleCastState) {
        _state.set(new)
        val copy = synchronized(listeners) { listeners.toList() }
        copy.forEach { it(new) }
    }

    /** Set error state with auto-recovery to Idle (or Off) after 3 seconds. */
    private fun setError(message: String) {
        setState(SimpleCastState.Error(message))
        // Auto-recover after 3s — projection likely still open
        executor.execute {
            Thread.sleep(3000)
            if (state is SimpleCastState.Error) {
                setState(if (projection.isOpen) SimpleCastState.Idle else SimpleCastState.Off)
            }
        }
    }

    // ─── Projection lifecycle ─────────────────────────────────────────────────

    /** Opens projection. Called on app start (onCreate). */
    fun openProjection() {
        executor.execute {
            if (state is SimpleCastState.CastingFull || state is SimpleCastState.CastingSplit) return@execute
            setState(SimpleCastState.Opening)

            // One-time: whitelist VietMap from Doze (IVI "không hỗ trợ" error).
            // Persists across reboots — only need to run once ever.
            if (!prefs.dozeWhitelistApplied()) {
                shell.execute("cmd deviceidle whitelist +vn.vietmap.live")
                prefs.setDozeWhitelistApplied(true)
            }

            // Step 0: Clean slate — return any leftover apps from display 1 to display 0.
            // After reboot/crash, previous cast session may leave apps on cluster.
            cleanDisplay1()

            // Step 1: Set display config BEFORE opening projection
            configurator.apply(displayId, DisplayConfig.NORMAL_DEFAULT)

            // Step 2: Open projection — resetState forces re-send even if local state thinks open
            projection.resetState(false)
            val ok = projection.open(displayId)
            if (!ok) { setError("Projection open failed"); return@execute }

            // Step 3: Launch black placeholder to keep projection alive
            shell.execute("am start --display $displayId --windowingMode 5" +
                " -n 'com.byd.clusternav/.modules.clustercast.ClusterBlackActivity'")

            // Step 4: Resize black activity to full display (freeform default is too small)
            Thread.sleep(1000)
            val stackResult = shell.execute("am stack list")
            if (stackResult.success) {
                val taskMatch = Regex("taskId=(\\d+):[^\\n]*ClusterBlackActivity").find(stackResult.stdout)
                taskMatch?.groupValues?.get(1)?.let { taskId ->
                    shell.execute("am task resize $taskId 0 0 1920 720")
                }
            }

            // Detect if an external app already occupies display 1 (e.g. CP left from previous session)
            // Adopt it as CastingFull so bubble shows Stop instead of Cast
            val adoptResult = shell.execute("am stack list")
            var adopted = false
            if (adoptResult.success) {
                var checkDisplayId = -1
                for (line in adoptResult.stdout.lines()) {
                    val sm = Regex("""Stack id=\d+.*displayId=(\d+)""").find(line)
                    if (sm != null) { checkDisplayId = sm.groupValues[1].toIntOrNull() ?: -1; continue }
                    if (checkDisplayId == displayId && line.contains("visible=true")) {
                        val tm = Regex("""taskId=\d+:\s*([^/\s]+)/""").find(line)
                        val pkg = tm?.groupValues?.get(1)
                        if (pkg != null && pkg != "com.byd.clusternav" && !pkg.startsWith("com.android.")) {
                            val appType = AppMover.classifyApp(pkg)
                            setState(SimpleCastState.CastingFull(pkg, appType, DisplayConfig.forAppType(appType)))
                            adopted = true; break
                        }
                    }
                }
            }
            if (!adopted) setState(SimpleCastState.Idle)
        }
    }

    /** Closes projection. Called on app exit. */
    fun closeProjection() {
        executor.execute {
            // Return any active apps first (known state)
            returnAllApps()
            // Safety net: also clean any unknown leftovers from display 1
            cleanDisplay1()
            setState(SimpleCastState.Closing)
            val ok = projection.close(displayId)
            setState(if (ok) SimpleCastState.Off else SimpleCastState.Error("Projection close failed"))
        }
    }

    // ─── Intent dispatch ──────────────────────────────────────────────────────

    /** Dispatch a cast intent. Thread-safe — queued on serial executor. */
    fun dispatch(intent: SimpleCastIntent) {
        executor.execute { handleIntent(intent) }
    }

    private fun handleIntent(intent: SimpleCastIntent) {
        when (intent) {
            is SimpleCastIntent.CastFull -> handleCastFull(intent)
            is SimpleCastIntent.CastSlot -> handleCastSlot(intent)
            is SimpleCastIntent.Stop -> handleStop(intent)
            is SimpleCastIntent.Close -> closeProjectionSync()
        }
    }

    private fun handleCastFull(intent: SimpleCastIntent.CastFull) {
        val current = state
        // If projection is still opening, wait and retry once
        if (current == SimpleCastState.Opening) {
            Thread.sleep(1500) // projection open takes ~1.1s
            if (state != SimpleCastState.Idle) return // still not ready — give up
        }
        val afterWait = state
        // Only cast from IDLE or replace current full cast
        if (afterWait != SimpleCastState.Idle && afterWait !is SimpleCastState.CastingFull) {
            return // invalid transition — ignore
        }

        // If currently casting something else full, stop it first
        if (afterWait is SimpleCastState.CastingFull) {
            returnApp(afterWait.targetPkg, afterWait.appType)
        }

        // If app is ALREADY on display 1, just adopt state — don't re-cast (prevents infinite loop)
        if (isAppOnDisplay(intent.pkg, displayId)) {
            setState(SimpleCastState.CastingFull(intent.pkg, intent.appType, DisplayConfig.forAppType(intent.appType)))
            return
        }

        val config = configurator.resolveConfig(intent.pkg, intent.appType, prefs)
        if (!configurator.apply(displayId, config)) {
            setError("Display config failed")
            return
        }

        val castTaskId = mover.castToCluster(
            pkg = intent.pkg,
            activity = null, // TODO: resolve from catalog at app layer
            displayId = displayId,
            appType = intent.appType,
        )
        if (castTaskId != null) {
            val savedTaskId = if (castTaskId > 0) castTaskId else null
            setState(SimpleCastState.CastingFull(intent.pkg, intent.appType, config, savedTaskId))
            // Apply saved bounds + density per-app (if user previously resized/set DPI)
            if (intent.appType == AppType.NORMAL) {
                val savedConfig = prefs.displayConfigFor(intent.pkg)
                if (savedConfig?.bounds != null) {
                    val b = savedConfig.bounds
                    val taskId = findTaskIdForPkg(intent.pkg)
                    if (taskId != null) {
                        shell.execute("am task resize $taskId ${b.left} ${b.top} ${b.right} ${b.bottom}")
                    }
                }
                if (savedConfig?.density != null && savedConfig.density != "reset") {
                    shell.execute("wm density ${savedConfig.density} -d $displayId")
                }
            }
        } else {
            val msg = if (intent.appType.isProtected) {
                "Open ${intent.appType.name} app first / Mở app trước rồi chiếu"
            } else {
                "Cast failed / Không chiếu được"
            }
            setError(msg)
        }
    }

    private fun handleCastSlot(intent: SimpleCastIntent.CastSlot) {
        val current = state
        // If projection is still opening, wait and retry once
        if (current == SimpleCastState.Opening) {
            Thread.sleep(1500)
            if (state != SimpleCastState.Idle) return
        }
        val afterWait = state
        if (afterWait != SimpleCastState.Idle && afterWait !is SimpleCastState.CastingSplit) {
            return // can only split from idle or existing split
        }

        val config = configurator.resolveConfig(intent.pkg, AppType.NORMAL, prefs)
        val slot = SlotState(intent.pkg, config)

        // Split mode: display config (wm size/overscan) is DISPLAY-GLOBAL on Android.
        // Cannot set different wm sizes per task. Use the config from the first cast app.
        // Both split apps share the same display resolution.
        if (afterWait is SimpleCastState.CastingSplit) {
            // Don't re-apply display config — would affect the existing app
        } else {
            if (!configurator.apply(displayId, config)) {
                setError("Display config failed for slot")
                return
            }
        }

        val leftPercent = prefs.splitRatioLeftPercent()
        val ok = mover.castToCluster(
            pkg = intent.pkg,
            activity = null, // resolved at app layer
            displayId = displayId,
            appType = AppType.NORMAL,
            slotSide = intent.side,
            leftPercent = leftPercent,
        )
        if (ok == null) {
            setError("Cast to slot failed")
            return
        }

        val newState = when {
            afterWait is SimpleCastState.CastingSplit && intent.side == ClusterSlotSide.LEFT ->
                afterWait.copy(left = slot)
            afterWait is SimpleCastState.CastingSplit && intent.side == ClusterSlotSide.RIGHT ->
                afterWait.copy(right = slot)
            intent.side == ClusterSlotSide.LEFT ->
                SimpleCastState.CastingSplit(left = slot, right = null)
            else ->
                SimpleCastState.CastingSplit(left = null, right = slot)
        }
        setState(newState)

        // Apply saved DPI for the newly cast app (per-app restore on re-cast)
        val savedConfig = prefs.displayConfigFor(intent.pkg)
        if (savedConfig?.density != null && savedConfig.density != "reset") {
            shell.execute("wm density ${savedConfig.density} -d $displayId")
        }
    }

    private fun handleStop(intent: SimpleCastIntent.Stop) {
        val current = state

        when {
            // Full mode: any stop = return everything
            current is SimpleCastState.CastingFull -> {
                setState(SimpleCastState.Stopping)
                log("handleStop: returning ${current.targetPkg} (${current.appType}) taskId=${current.taskId}")
                returnApp(current.targetPkg, current.appType, current.taskId)
                if (current.appType.isProtected) {
                    // CP/AA: only reset density. NO refreshCluster() — suspected crash cause.
                    // Shell manual recipe: move-task only, no service calls.
                    log("handleStop: CP/AA path — wm density reset, skip refreshCluster")
                    shell.execute("wm density reset -d $displayId")
                } else {
                    refreshCluster() // normal apps: clear stale frame from display 1
                }
                setState(SimpleCastState.Idle)
            }
            // Split mode: stop specific slot
            current is SimpleCastState.CastingSplit && intent.slot != null -> {
                val slotToStop = when (intent.slot) {
                    ClusterSlotSide.LEFT -> current.left
                    ClusterSlotSide.RIGHT -> current.right
                }
                if (slotToStop != null) {
                    returnApp(slotToStop.pkg, AppType.NORMAL)
                }
                // Determine remaining occupant after removing the stopped slot
                val remainingLeft = if (intent.slot == ClusterSlotSide.LEFT) null else current.left
                val remainingRight = if (intent.slot == ClusterSlotSide.RIGHT) null else current.right
                // If both slots empty → idle (avoid CastingSplit invariant violation)
                if (remainingLeft == null && remainingRight == null) {
                    setState(SimpleCastState.Idle)
                } else {
                    setState(SimpleCastState.CastingSplit(left = remainingLeft, right = remainingRight))
                }
            }
            // Split mode: stop all
            current is SimpleCastState.CastingSplit && intent.slot == null -> {
                setState(SimpleCastState.Stopping)
                current.left?.let { returnApp(it.pkg, AppType.NORMAL) }
                current.right?.let { returnApp(it.pkg, AppType.NORMAL) }
                refreshCluster() // clear stale frame
                setState(SimpleCastState.Idle)
            }
            else -> {} // already idle or off, ignore
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun returnApp(pkg: String, appType: AppType, taskId: Int? = null) {
        if (pkg == "com.byd.clusternav") return // never return our own black activity
        log("returnApp: pkg=$pkg, appType=$appType, taskId=$taskId")
        val ok = mover.returnToMain(
            pkg = pkg,
            activity = null,
            appType = appType,
            taskId = taskId,
            clusterDisplayId = displayId,
        )
        log("returnApp result: $ok")
    }

    /**
     * Clear stale frame from cluster display after stop.
     * Uses profile 0 (refresh video) — lightweight, no full close/open cycle needed.
     * Measured on vehicle: profile 0 alone forces recomposite without closing projection.
     */
    private fun refreshCluster() {
        shell.execute("service call AutoContainer 2 i32 1000 i32 0 s16 \"\"")
    }

    private fun returnAllApps() {
        when (val current = state) {
            is SimpleCastState.CastingFull -> returnApp(current.targetPkg, current.appType)
            is SimpleCastState.CastingSplit -> {
                current.left?.let { returnApp(it.pkg, AppType.NORMAL) }
                current.right?.let { returnApp(it.pkg, AppType.NORMAL) }
            }
            else -> {}
        }
    }

    /**
     * Return ALL apps from display 1 to display 0.
     * Parses `am stack list`, finds all tasks on display 1 (except our own ClusterBlackActivity),
     * and moves them back to display 0 via `am stack move-task`.
     * Falls back to `am start --display 0` for exported activities if move-task fails.
     * Idempotent: safe to call even if display 1 is already empty.
     */
    private fun cleanDisplay1() {
        val result = shell.execute("am stack list")
        if (!result.success) return

        // Find a target stack on display 0 (any non-home standard stack)
        var targetStack: Int? = null
        var currentDisplayId = -1
        val tasksOnCluster = mutableListOf<Pair<Int, String>>() // taskId, component

        for (line in result.stdout.lines()) {
            val stackMatch = Regex("""Stack id=(\d+).*displayId=(\d+)""").find(line)
            if (stackMatch != null) {
                val stackId = stackMatch.groupValues[1].toIntOrNull() ?: continue
                val dId = stackMatch.groupValues[2].toIntOrNull() ?: continue
                currentDisplayId = dId
                // Find a usable stack on display 0 (not home stack 0)
                if (dId == 0 && stackId > 0 && targetStack == null) {
                    targetStack = stackId
                }
                continue
            }
            // Task line on cluster display
            if (currentDisplayId == displayId && line.contains("taskId=")) {
                val taskMatch = Regex("""taskId=(\d+):\s*([^\s]+)""").find(line) ?: continue
                val taskId = taskMatch.groupValues[1].toIntOrNull() ?: continue
                val component = taskMatch.groupValues[2]
                val pkg = component.substringBefore("/")
                // CLAUDE.md §4: NEVER move home/recents/pinned/system — causes surfaceflinger crash
                // Also skip CP — it has auto-relaunch behavior; moving it back makes it jump right back
                if (pkg == "com.byd.clusternav") continue
                if (pkg == "com.android.launcher3") continue
                if (pkg == "com.android.systemui") continue
                if (pkg.startsWith("com.android.")) continue // all system framework
                if (pkg == "com.byd.carplay.ui") continue // CP auto-relaunches to display 1
                tasksOnCluster.add(taskId to component)
            }
        }

        // Move each task back to display 0
        if (tasksOnCluster.isEmpty()) return

        // Ensure we have a target stack on display 0 — create one if needed
        if (targetStack == null) {
            shell.execute("am start --display 0 --windowingMode 1 -n 'com.android.settings/.Settings'")
            Thread.sleep(1000)
            // Re-parse to find the new stack
            val recheck = shell.execute("am stack list")
            if (recheck.success) {
                for (line in recheck.stdout.lines()) {
                    val m = Regex("""Stack id=(\d+).*displayId=0""").find(line)
                    if (m != null) {
                        val sid = m.groupValues[1].toIntOrNull() ?: continue
                        if (sid > 0) { targetStack = sid; break }
                    }
                }
            }
        }

        for ((taskId, component) in tasksOnCluster) {
            if (targetStack != null) {
                val moveResult = shell.execute("am stack move-task $taskId $targetStack true")
                if (moveResult.success) {
                    // Also launch on display 0 fullscreen to overwrite Android's "last display" memory.
                    // Without this, user tapping the app icon later reopens it on display 1.
                    val activity = component.takeIf { it.contains("/") }
                    if (activity != null) {
                        shell.execute("am start --display 0 --windowingMode 1 -n '$activity'")
                    }
                    Thread.sleep(300)
                    continue
                }
            }
            // Fallback: try am start (works for exported activities)
            val activity = component.takeIf { it.contains("/") }
            if (activity != null) {
                shell.execute("am start --display 0 --windowingMode 1 -n '$activity'")
            }
            Thread.sleep(300)
        }

        // Close stale projection
        shell.execute("service call AutoContainer 2 i32 1000 i32 18 s16 \"\"")
        Thread.sleep(300)
        shell.execute("service call AutoContainer 2 i32 1000 i32 0 s16 \"\"")
        Thread.sleep(500)
    }

    private fun closeProjectionSync() {
        returnAllApps()
        // Reset display to defaults before closing — undo all wm changes
        configurator.reset(displayId)
        setState(SimpleCastState.Closing)
        val ok = projection.close(displayId)
        if (ok) setState(SimpleCastState.Off) else setError("Close failed")
    }

    // ─── Resize active target ────────────────────────────────────────────────

    /**
     * Resize the currently casting app to the given bounds.
     * Only works in CastingFull state with a NORMAL app.
     * Thread-safe — queued on serial executor.
     */
    fun resizeActiveTarget(left: Int, top: Int, right: Int, bottom: Int) {
        executor.execute {
            val current = state
            if (current !is SimpleCastState.CastingFull) return@execute
            val taskId = findTaskIdForPkg(current.targetPkg) ?: return@execute
            shell.execute("am task resize $taskId $left $top $right $bottom")
            // Save bounds + current density per-app
            val existing = prefs.displayConfigFor(current.targetPkg) ?: DisplayConfig.NORMAL_DEFAULT
            prefs.saveDisplayConfig(current.targetPkg, existing.copy(
                bounds = CastBounds(left, top, right, bottom)
            ))
        }
    }

    private fun findTaskIdForPkg(pkg: String): String? {
        // Prefer the task on the cluster display to avoid resizing the wrong task on display 0.
        val clusterDid = prefs.lastDisplayId() ?: 1
        val result = shell.execute("am stack list")
        if (!result.success) return null
        var currentDisplayId = -1
        var fallback: String? = null
        for (line in result.stdout.lines()) {
            val sm = Regex("""Stack id=\d+.*displayId=(\d+)""").find(line)
            if (sm != null) { currentDisplayId = sm.groupValues[1].toIntOrNull() ?: -1; continue }
            val tm = Regex("""taskId=(\d+):[^\n]*$pkg""").find(line)
            if (tm != null) {
                val id = tm.groupValues[1]
                if (currentDisplayId == clusterDid) return id
                if (fallback == null) fallback = id
            }
        }
        return fallback
    }

    /** Check if a package has a visible task on the given display. */
    private fun isAppOnDisplay(pkg: String, targetDisplayId: Int): Boolean {
        val result = shell.execute("am stack list")
        if (!result.success) return false
        var currentDisplayId = -1
        for (line in result.stdout.lines()) {
            val sm = Regex("""Stack id=\d+.*displayId=(\d+)""").find(line)
            if (sm != null) { currentDisplayId = sm.groupValues[1].toIntOrNull() ?: -1; continue }
            if (currentDisplayId == targetDisplayId && line.contains(pkg) && line.contains("visible=true")) {
                return true
            }
        }
        return false
    }

    // ─── Density control ──────────────────────────────────────────────────────

    /**
     * Set or reset cluster display density.
     * @param dpi density value, or null to reset.
     */
    fun setDensity(dpi: Int?) {
        executor.execute {
            val current = state
            if (dpi != null && dpi in 80..640) {
                shell.execute("wm density $dpi -d $displayId")
            } else {
                shell.execute("wm density reset -d $displayId")
            }
            // Save density per-app
            if (current is SimpleCastState.CastingFull) {
                val existing = prefs.displayConfigFor(current.targetPkg) ?: DisplayConfig.NORMAL_DEFAULT
                prefs.saveDisplayConfig(current.targetPkg, existing.copy(
                    density = dpi?.toString() ?: "reset"
                ))
            }
        }
    }

    /**
     * Set density and save per specific package (for split mode DPI buttons).
     * Unlike [setDensity] which saves for current full-cast app, this targets a named package.
     */
    fun setDensityForPkg(dpi: Int?, pkg: String) {
        executor.execute {
            if (dpi != null && dpi in 80..640) {
                shell.execute("wm density $dpi -d $displayId")
            } else {
                shell.execute("wm density reset -d $displayId")
            }
            // Save per-app
            val existing = prefs.displayConfigFor(pkg) ?: DisplayConfig.NORMAL_DEFAULT
            prefs.saveDisplayConfig(pkg, existing.copy(density = dpi?.toString() ?: "reset"))
        }
    }

    /** Shutdown executor. Call on app destroy. */
    fun shutdown() {
        executor.shutdownNow()
    }

    // ─── Foreground detection (replaces V2 CastAmStackParser path) ────────────

    /**
     * Detect the foreground package on [targetDisplayId] (default: HOME display 0).
     *
     * Runs `am stack list` and parses visible tasks. Returns the single visible package
     * on the specified display, excluding packages in [excluded]. Returns null if ambiguous
     * (multiple distinct visible packages) or if shell fails.
     *
     * Must be called off main thread — performs shell I/O.
     */
    fun foregroundPackage(targetDisplayId: Int = 0, excluded: Set<String>): String? {
        val result = shell.execute("am stack list")
        if (!result.success || result.stdout.isBlank()) return null
        return parseForeground(result.stdout, targetDisplayId, excluded)
    }

    companion object {
        /**
         * Minimal `am stack list` parser for foreground detection.
         *
         * Actual format on BYD DiLink3 (Android 10):
         * ```
         * Stack id=10 bounds=[0,0][1920,1080] displayId=0 userId=0
         *   ...
         *   taskId=33: vn.vietmap.live/vn.vietmap.live.MainActivity bounds=[0,0][1920,1080] userId=0 visible=true topActivity=ComponentInfo{vn.vietmap.live/vn.vietmap.live.MainActivity}
         * ```
         *
         * Stack header has displayId. Task line has visible=true and pkg/activity.
         */
        internal fun parseForeground(amOutput: String, displayId: Int, excluded: Set<String>): String? {
            var currentDisplayId = -1
            for (line in amOutput.lines()) {
                // Stack header: "Stack id=N ... displayId=D ..."
                val stackMatch = Regex("""Stack id=\d+.*displayId=(\d+)""").find(line)
                if (stackMatch != null) {
                    currentDisplayId = stackMatch.groupValues[1].toIntOrNull() ?: -1
                    continue
                }
                // Task line: "taskId=N: pkg/activity ... visible=true ..."
                if (currentDisplayId == displayId && line.contains("visible=true")) {
                    // Extract package from "taskId=N: pkg/activity" format
                    val taskMatch = Regex("""taskId=\d+:\s*([^/\s]+)/""").find(line)
                    val pkg = taskMatch?.groupValues?.get(1) ?: continue
                    if (pkg in excluded) continue
                    return pkg
                }
            }
            return null
        }
    }
}

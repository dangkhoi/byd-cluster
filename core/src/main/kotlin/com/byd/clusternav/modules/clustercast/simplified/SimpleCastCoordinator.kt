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

    // ─── Projection lifecycle ─────────────────────────────────────────────────

    /** Opens projection. Called on app start (onCreate). */
    fun openProjection() {
        executor.execute {
            if (state != SimpleCastState.Off) return@execute
            setState(SimpleCastState.Opening)

            val ok = projection.open(displayId)
            setState(if (ok) SimpleCastState.Idle else SimpleCastState.Error("Projection open failed"))
        }
    }

    /** Closes projection. Called on app exit. */
    fun closeProjection() {
        executor.execute {
            // Return any active apps first
            returnAllApps()
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

        val config = configurator.resolveConfig(intent.pkg, intent.appType, prefs)
        if (!configurator.apply(displayId, config)) {
            setState(SimpleCastState.Error("Display config failed"))
            return
        }

        val ok = mover.castToCluster(
            pkg = intent.pkg,
            activity = null, // TODO: resolve from catalog at app layer
            displayId = displayId,
            appType = intent.appType,
        )
        if (ok) {
            setState(SimpleCastState.CastingFull(intent.pkg, intent.appType, config))
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
            setState(SimpleCastState.Error("Cast failed"))
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

        // R5: apply display config before moving the app
        if (!configurator.apply(displayId, config)) {
            setState(SimpleCastState.Error("Display config failed for slot"))
            return
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
        if (!ok) {
            setState(SimpleCastState.Error("Cast to slot failed"))
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
    }

    private fun handleStop(intent: SimpleCastIntent.Stop) {
        val current = state

        when {
            // Full mode: any stop = return everything
            current is SimpleCastState.CastingFull -> {
                setState(SimpleCastState.Stopping)
                returnApp(current.targetPkg, current.appType)
                refreshCluster() // clear stale frame from display 1
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

    private fun returnApp(pkg: String, appType: AppType) {
        mover.returnToMain(
            pkg = pkg,
            activity = null, // resolved at app layer
            appType = appType,
        )
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

    private fun closeProjectionSync() {
        returnAllApps()
        // Reset display to defaults before closing — undo all wm changes
        configurator.reset(displayId)
        setState(SimpleCastState.Closing)
        val ok = projection.close(displayId)
        setState(if (ok) SimpleCastState.Off else SimpleCastState.Error("Close failed"))
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
        val result = shell.execute("am stack list")
        if (!result.success) return null
        return Regex("taskId=(\\d+):[^\\n]*$pkg").find(result.stdout)?.groupValues?.get(1)
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
         * Looks for tasks with `visible=true` on the given display and returns the package.
         */
        internal fun parseForeground(amOutput: String, displayId: Int, excluded: Set<String>): String? {
            // Pattern: taskId=N ... displayId=D ... visible=true ... realActivity=pkg/activity
            val taskPattern = Regex("""taskId=\d+.*?(?=taskId=|\z)""", RegexOption.DOT_MATCHES_ALL)
            val displayIdPattern = Regex("""displayId=(\d+)""")
            val visiblePattern = Regex("""visible=(true|false)""")
            val realActivityPattern = Regex("""realActivity=([^/\s]+)/""")

            val candidates = mutableSetOf<String>()
            for (match in taskPattern.findAll(amOutput)) {
                val block = match.value
                val dId = displayIdPattern.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                if (dId != displayId) continue
                val visible = visiblePattern.find(block)?.groupValues?.get(1) == "true"
                if (!visible) continue
                val pkg = realActivityPattern.find(block)?.groupValues?.get(1) ?: continue
                if (pkg in excluded) continue
                candidates.add(pkg)
            }
            return candidates.singleOrNull()
        }
    }
}

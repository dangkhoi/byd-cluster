package com.byd.clusternav.modules.clustercast.simplified

import java.util.concurrent.atomic.AtomicReference

/**
 * Simplified Cluster Cast coordinator with safety (T4 remediation).
 * Single owner of projection/cast state. Mutations run on bounded executor.
 * UI observes [state] and emits [SimpleCastIntent].
 */
class SimpleCastCoordinator(
    private val projection: ProjectionManager,
    private val configurator: DisplayConfigurator,
    private val mover: AppMover,
    val prefs: SimpleCastPrefs,
    private val shell: SimpleCastShell,
    private val displayId: Int,
    private val castTimeoutMs: Long = 15_000L,
    private val stopTimeoutMs: Long = 5_000L,
) {
    private val executor = BoundedCastExecutor(
        castTimeoutMs = castTimeoutMs,
        stopTimeoutMs = stopTimeoutMs,
        onTimeout = { tag -> log("TIMEOUT: $tag") },
    )

    private val verifier = CastPostconditionVerifier(
        shell = shell,
        sleepMs = { Thread.sleep(it) },
        log = { msg -> log("verify: $msg") },
    )

    private fun log(msg: String) {
        println("[SimpleCast] $msg")
    }

    /** Owns freeform task resize + per-app profile persistence/restore (R4/R5/R6). */
    private val geometry = CastGeometryController(shell, prefs, displayId) { msg -> log(msg) }

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
        executor.submit("error-recovery") {
            Thread.sleep(3000)
            if (state is SimpleCastState.Error) {
                setState(if (projection.isOpen) SimpleCastState.Idle else SimpleCastState.Off)
            }
        }
    }

    // ─── Projection lifecycle ─────────────────────────────────────────────────
    /**
     * Opens projection (Activity onCreate; and on boot via FloatingBubbleService when autostart on).
     * R10: idempotent — opens only from Off/Error; opening/idle/casting is a no-op (both callers safe).
     */
    fun openProjection() {
        executor.submit("openProjection") {
            when (state) {
                is SimpleCastState.Off, is SimpleCastState.Error -> Unit // proceed
                else -> return@submit // already open/opening/casting/stopping/closing
            }
            setState(SimpleCastState.Opening)

            if (!prefs.dozeWhitelistApplied()) {
                shell.execute("cmd deviceidle whitelist +vn.vietmap.live")
                prefs.setDozeWhitelistApplied(true)
            }

            // Enable freeform boot flags so per-app bounds (resize + split) work after next power-cycle.
            // These settings are read ONLY at boot by ActivityTaskManagerService.retrieveSettings()
            // (no ContentObserver), so they take effect after a physical ignition off/on.
            // Idempotent — safe to run every open.
            geometry.ensureFreeformFlags()

            cleanDisplay1()
            configurator.apply(displayId, DisplayConfig.NORMAL_DEFAULT)

            projection.resetState(false)
            val ok = projection.open(displayId)
            if (!ok) { setError("Projection open failed"); return@submit }

            // Launch + resize black placeholder to keep projection alive
            shell.execute("am start --display $displayId --windowingMode 5" +
                " -n 'com.byd.clusternav/.modules.clustercast.ClusterBlackActivity'")
            Thread.sleep(1000)
            val stackResult = shell.execute("am stack list")
            if (stackResult.success) {
                val taskId = CastStackParser.findTaskId(stackResult.stdout, "com.byd.clusternav", displayId)
                    ?: CastStackParser.findTaskId(stackResult.stdout, "ClusterBlackActivity", displayId)
                if (taskId != null) shell.execute("am task resize $taskId 0 0 1920 720")
            }

            // Adopt external app already on display 1 (e.g. CP from previous session)
            val adoptResult = shell.execute("am stack list")
            var adopted = false
            if (adoptResult.success) {
                val tasks = CastStackParser.parseTasks(adoptResult.stdout)
                val ext = tasks.firstOrNull { t ->
                    t.displayId == displayId && t.visible &&
                        t.pkg != "com.byd.clusternav" && !t.pkg.startsWith("com.android.")
                }
                if (ext != null) {
                    val appType = AppMover.classifyApp(ext.pkg)
                    setState(SimpleCastState.CastingFull(ext.pkg, appType, DisplayConfig.forAppType(appType)))
                    adopted = true
                }
            }
            if (!adopted) setState(SimpleCastState.Idle)
        }
    }
    /** Closes projection. Called on app exit. */
    fun closeProjection() {
        executor.submit("closeProjection") {
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
    /** Dispatch a cast intent. Thread-safe — queued on serial executor.
     *  Stop is PRIORITY: cancels pending cast and executes immediately. */
    fun dispatch(intent: SimpleCastIntent) {
        when (intent) {
            is SimpleCastIntent.Stop -> executor.submitStop("stop") { handleIntent(intent) }
            is SimpleCastIntent.Close -> executor.submitStop("close") { handleIntent(intent) }
            else -> executor.submit("cast") { handleIntent(intent) }
        }
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
        // R4: Precondition validation (fail-fast before any shell command)
        val rejectReason = CastSlotValidator.validateCastFull(intent.pkg, intent.appType, projection.isOpen)
        if (rejectReason != null) {
            log("CastFull REJECTED: $rejectReason for ${intent.pkg}")
            setError("Cast rejected: $rejectReason")
            return
        }

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

        // R4: For protected apps, verify we will land in fullscreen stack (not freeform)
        if (intent.appType.isProtected && !geometry.verifyFullscreenStackAvailable()) {
            setError("Cast rejected: ${CastRejectReason.PROTECTED_FULLSCREEN_STACK_UNPROVEN}")
            return
        }

        // If currently casting something else full, stop it first
        if (afterWait is SimpleCastState.CastingFull) {
            returnApp(afterWait.targetPkg, afterWait.appType)
        }

        // If app is ALREADY on display 1, just adopt state — don't re-cast (prevents infinite loop)
        if (geometry.isAppOnDisplay(intent.pkg, displayId)) {
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
            activity = null,
            displayId = displayId,
            appType = intent.appType,
        )
        if (castTaskId != null) {
            // R3: Postcondition verification — use verifier instead of simple isAppOnDisplay
            val outcome = verifier.verifyCastFull(intent.pkg, displayId)
            when (outcome) {
                is CastMutationOutcome.Verified -> {
                    val savedTaskId = if (castTaskId > 0) castTaskId else outcome.taskId
                    setState(SimpleCastState.CastingFull(intent.pkg, intent.appType, config, savedTaskId))
                    // R6: Apply saved FULL-profile bounds + density ONLY after verified landing
                    if (intent.appType == AppType.NORMAL) {
                        geometry.applySavedProfile(intent.pkg, CastProfile.FULL)
                    }
                }
                else -> {
                    // Postcondition failed — do NOT commit state, do NOT persist prefs
                    log("postcondition FAIL: $outcome")
                    setError("Cast failed: app did not land on cluster")
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
        // R4: Precondition — rejects CP/AA and occupied slots
        val rejectReason = CastSlotValidator.validateCastSlot(
            pkg = intent.pkg,
            side = intent.side,
            currentState = state,
            projectionOpen = projection.isOpen,
        )
        if (rejectReason != null) {
            log("CastSlot REJECTED: $rejectReason for ${intent.pkg} side=${intent.side}")
            setError("Slot rejected: $rejectReason")
            return
        }

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
            activity = null,
            displayId = displayId,
            appType = AppType.NORMAL,
            slotSide = intent.side,
            leftPercent = leftPercent,
        )
        if (ok == null) {
            setError("Cast to slot failed")
            return
        }

        // R3: Postcondition verification for split
        val outcome = verifier.verifyCastSplit(intent.pkg, displayId, intent.side)
        when (outcome) {
            is CastMutationOutcome.Verified -> {
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
                // R6: Restore saved profile geometry (bounds + DPI) ONLY after verified landing.
                // If no profile is saved, the ratio-default bounds from AppMover.fitToCluster stand.
                geometry.applySavedProfile(intent.pkg, CastProfile.of(intent.side, leftPercent))
            }
            else -> {
                log("CastSlot postcondition FAIL: $outcome")
                setError("Slot cast failed: app did not land")
            }
        }
    }

    private fun handleStop(intent: SimpleCastIntent.Stop) {
        val current = state
        when {
            current is SimpleCastState.CastingFull -> {
                setState(SimpleCastState.Stopping)
                returnApp(current.targetPkg, current.appType, current.taskId)
                if (current.appType.isProtected) {
                    shell.execute("wm density reset -d $displayId")
                } else {
                    refreshCluster()
                }
                setState(SimpleCastState.Idle)
            }
            current is SimpleCastState.CastingSplit && intent.slot != null -> {
                val slotToStop = when (intent.slot) {
                    ClusterSlotSide.LEFT -> current.left
                    ClusterSlotSide.RIGHT -> current.right
                }
                if (slotToStop != null) {
                    returnApp(slotToStop.pkg, AppType.NORMAL)
                }
                // Determine remaining after stopping slot
                val remainingLeft = if (intent.slot == ClusterSlotSide.LEFT) null else current.left
                val remainingRight = if (intent.slot == ClusterSlotSide.RIGHT) null else current.right
                if (remainingLeft == null && remainingRight == null) {
                    setState(SimpleCastState.Idle)
                } else {
                    setState(SimpleCastState.CastingSplit(left = remainingLeft, right = remainingRight))
                }
            }
            current is SimpleCastState.CastingSplit && intent.slot == null -> {
                setState(SimpleCastState.Stopping)
                current.left?.let { returnApp(it.pkg, AppType.NORMAL) }
                current.right?.let { returnApp(it.pkg, AppType.NORMAL) }
                refreshCluster()
                setState(SimpleCastState.Idle)
            }
            else -> {}
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────
    private fun returnApp(pkg: String, appType: AppType, taskId: Int? = null) {
        if (pkg == "com.byd.clusternav") return
        log("returnApp: pkg=$pkg, appType=$appType, taskId=$taskId")
        mover.returnToMain(pkg = pkg, activity = null, appType = appType, taskId = taskId, clusterDisplayId = displayId)
    }

    /** Clear stale frame from cluster display after stop. */
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

    private fun cleanDisplay1() = CastDisplayCleaner.cleanDisplay(shell, displayId)

    private fun closeProjectionSync() {
        returnAllApps()
        // Reset display to defaults before closing — undo all wm changes
        configurator.reset(displayId)
        setState(SimpleCastState.Closing)
        val ok = projection.close(displayId)
        if (ok) setState(SimpleCastState.Off) else setError("Close failed")
    }

    // ─── Resize active target / slot ─────────────────────────────────────────

    /**
     * Resize the currently casting full app to the given bounds (R5/R6). NORMAL-only.
     * Persistence (FULL profile) happens ONLY on shell success — see [CastGeometryController].
     * Thread-safe — queued on serial executor.
     */
    fun resizeActiveTarget(left: Int, top: Int, right: Int, bottom: Int) = executor.submit("resize") {
        val current = state as? SimpleCastState.CastingFull ?: return@submit
        if (!current.appType.isResizable || right <= left || bottom <= top) {
            log("resizeActiveTarget: skip (state/bounds invalid) [$left,$top,$right,$bottom]")
            return@submit
        }
        geometry.resizeFull(current.targetPkg, left, top, right, bottom)
    }

    /**
     * Resize one split slot's app to the given bounds (R5/R6). Only valid in
     * [SimpleCastState.CastingSplit]; targets the app currently in [side]. Persists to the
     * matching profile ([CastProfile.of] on the current split ratio) ONLY on shell success.
     * Thread-safe — queued on serial executor.
     */
    fun resizeActiveSlot(side: ClusterSlotSide, left: Int, top: Int, right: Int, bottom: Int) =
        executor.submit("resize-slot") {
            val current = state as? SimpleCastState.CastingSplit ?: return@submit
            val pkg = (if (side == ClusterSlotSide.LEFT) current.left else current.right)?.pkg ?: return@submit
            if (right <= left || bottom <= top) {
                log("resizeActiveSlot: skip (bounds invalid) [$left,$top,$right,$bottom]")
                return@submit
            }
            geometry.resizeSlot(pkg, CastProfile.of(side, prefs.splitRatioLeftPercent()), left, top, right, bottom)
        }

    /** @see CastGeometryController.isFreeformAlive */
    fun isFreeformAlive(): Boolean = geometry.isFreeformAlive()

    // ─── Density control ──────────────────────────────────────────────────────
    /** @see CastDensityControl.set — persists ONLY on shell success (R6). */
    fun setDensity(dpi: Int?) = executor.submit("density") {
        CastDensityControl.set(shell, prefs, displayId, dpi, (state as? SimpleCastState.CastingFull)?.targetPkg)
    }

    /** @see CastDensityControl.setForPkg — persists ONLY on shell success (R6). */
    fun setDensityForPkg(dpi: Int?, pkg: String) = executor.submit("density-pkg") {
        CastDensityControl.setForPkg(shell, prefs, displayId, dpi, pkg)
    }
    /** Shutdown executor. Call on app destroy. */
    fun shutdown() {
        executor.shutdown()
    }

    // ─── Foreground detection (replaces V2 CastAmStackParser path) ────────────
    /**
     * Detect the foreground package on [targetDisplayId] (default: HOME display 0).
     *
     * Runs `am stack list` and parses visible tasks. Returns the single visible package
     * on the specified display, excluding packages in [excluded]. Returns null if ambiguous
     * (multiple distinct visible packages) or if shell fails.
     *
     * Before detection, dismisses any active PiP on the target display to prevent
     * Google Maps / YouTube PiP from being misidentified as the foreground app.
     *
     * Must be called off main thread — performs shell I/O.
     */
    fun foregroundPackage(targetDisplayId: Int = 0, excluded: Set<String>): String? {
        // Dismiss PiP first — prevents misidentification
        dismissPipOnDisplay(targetDisplayId)
        val result = shell.execute("am stack list")
        if (!result.success || result.stdout.isBlank()) return null
        return CastStackParser.foreground(result.stdout, targetDisplayId, excluded)
    }

    /**
     * Dismiss any active PiP (pinned stack) on [displayId] by sending KEYCODE_HOME
     * to the pinned task. On Android 10 BYD, `am stack resize-animated` or
     * removing the pinned stack forces PiP to close.
     *
     * Known PiP offenders: Google Maps (navigation overlay), YouTube (mini player).
     */
    fun dismissPipOnDisplay(displayId: Int) {
        val result = shell.execute("am stack list")
        if (!result.success) return
        var currentDisplayId = -1
        var currentStackId = -1
        var isPinned = false
        for (line in result.stdout.lines()) {
            val stackMatch = Regex("""Stack id=(\d+).*displayId=(\d+)""").find(line)
            if (stackMatch != null) {
                currentStackId = stackMatch.groupValues[1].toIntOrNull() ?: -1
                currentDisplayId = stackMatch.groupValues[2].toIntOrNull() ?: -1
                isPinned = false
                continue
            }
            if (line.contains("mWindowingMode=pinned")) isPinned = true
            if (isPinned && currentDisplayId == displayId && line.contains("visible=true")) {
                // Found a visible pinned task on this display — dismiss it
                shell.execute("am stack remove $currentStackId")
                return
            }
        }
    }

    /** Execute a shell command via the coordinator's transport. For PiP/appops management. */
    fun executeShell(command: String): ShellResult = shell.execute(command)
}

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
    private val prefs: SimpleCastPrefs,
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

            // CLAUDE.md §5: if previous session crashed with display dirty, reset now.
            configurator.resetIfDirtyOnBoot(displayId, prefs)

            val ok = projection.open(displayId)
            setState(if (ok) SimpleCastState.Idle else SimpleCastState.Error("Projection open failed"))
        }
    }

    /** Closes projection. Called on app exit. */
    fun closeProjection() {
        executor.execute {
            // Return any active apps first
            returnAllApps()
            // CLAUDE.md §5: reset display to defaults — clean shutdown clears dirty flag
            configurator.reset(displayId, prefs)
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
        // Only cast from IDLE or replace current full cast
        if (current != SimpleCastState.Idle && current !is SimpleCastState.CastingFull) {
            return // invalid transition — ignore
        }

        // If currently casting something else full, stop it first
        if (current is SimpleCastState.CastingFull) {
            returnApp(current.targetPkg, current.appType)
        }

        val config = configurator.resolveConfig(intent.pkg, intent.appType, prefs)
        if (!configurator.apply(displayId, config, prefs)) {
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
        } else {
            setState(SimpleCastState.Error("Cast failed"))
        }
    }

    private fun handleCastSlot(intent: SimpleCastIntent.CastSlot) {
        val current = state
        if (current != SimpleCastState.Idle && current !is SimpleCastState.CastingSplit) {
            return // can only split from idle or existing split
        }

        val config = configurator.resolveConfig(intent.pkg, AppType.NORMAL, prefs)
        val slot = SlotState(intent.pkg, config)

        // R5: apply display config before moving the app
        if (!configurator.apply(displayId, config, prefs)) {
            setState(SimpleCastState.Error("Display config failed for slot"))
            return
        }

        val ok = mover.castToCluster(
            pkg = intent.pkg,
            activity = null, // resolved at app layer
            displayId = displayId,
            appType = AppType.NORMAL,
        )
        if (!ok) {
            setState(SimpleCastState.Error("Cast to slot failed"))
            return
        }

        val newState = when {
            current is SimpleCastState.CastingSplit && intent.side == ClusterSlotSide.LEFT ->
                current.copy(left = slot)
            current is SimpleCastState.CastingSplit && intent.side == ClusterSlotSide.RIGHT ->
                current.copy(right = slot)
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
        // CLAUDE.md §5: reset display to defaults before closing — undo all wm changes
        configurator.reset(displayId, prefs)
        setState(SimpleCastState.Closing)
        val ok = projection.close(displayId)
        setState(if (ok) SimpleCastState.Off else SimpleCastState.Error("Close failed"))
    }

    /** Shutdown executor. Call on app destroy. */
    fun shutdown() {
        executor.shutdownNow()
    }
}

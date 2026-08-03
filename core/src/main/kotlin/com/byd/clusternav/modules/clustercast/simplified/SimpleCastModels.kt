package com.byd.clusternav.modules.clustercast.simplified

/**
 * Simplified Cluster Cast state model.
 *
 * Replaces the 11-state V2 machine with 4 stable states:
 * OFF → IDLE → CASTING_FULL / CASTING_SPLIT
 *
 * Field-proven 2026-08-02: all commands verified on vehicle.
 */

// ─── App classification ───────────────────────────────────────────────────────

enum class AppType {
    CARPLAY,
    ANDROID_AUTO,
    NORMAL;

    val isProtected: Boolean get() = this == CARPLAY || this == ANDROID_AUTO
    val isResizable: Boolean get() = this == NORMAL
}

// ─── Display configuration (measured on vehicle 2026-08-02) ───────────────────

data class DisplayConfig(
    val wmSize: String,
    val overscan: String,
    val density: String = "reset",
) {
    companion object {
        val CARPLAY = DisplayConfig(wmSize = "1422x800", overscan = "10,-120,10,50")
        val ANDROID_AUTO = DisplayConfig(wmSize = "1920x1080", overscan = "0,0,0,0")
        val NORMAL_DEFAULT = DisplayConfig(wmSize = "1920x800", overscan = "0,0,0,0")

        fun forAppType(type: AppType): DisplayConfig = when (type) {
            AppType.CARPLAY -> CARPLAY
            AppType.ANDROID_AUTO -> ANDROID_AUTO
            AppType.NORMAL -> NORMAL_DEFAULT
        }
    }
}

// ─── Slot state (for split mode) ─────────────────────────────────────────────

data class SlotState(
    val pkg: String,
    val displayConfig: DisplayConfig,
)

enum class ClusterSlotSide { LEFT, RIGHT }

// ─── Cast state (immutable, UI observes this) ─────────────────────────────────

sealed interface SimpleCastState {
    /** Projection closed. Cluster shows gauges. */
    object Off : SimpleCastState { override fun toString() = "Off" }

    /** Projection opening (transient, ~1-2s). */
    object Opening : SimpleCastState { override fun toString() = "Opening" }

    /** Projection open, no app on cluster. Ready to cast. */
    object Idle : SimpleCastState { override fun toString() = "Idle" }

    /** One app occupies the full cluster (CP/AA or single normal app). */
    data class CastingFull(
        val targetPkg: String,
        val appType: AppType,
        val displayConfig: DisplayConfig,
    ) : SimpleCastState

    /** Two normal apps split the cluster left/right. */
    data class CastingSplit(
        val left: SlotState?,
        val right: SlotState?,
    ) : SimpleCastState {
        init {
            require(left != null || right != null) { "At least one slot must be occupied" }
        }
    }

    /** App being returned to main display (transient). */
    object Stopping : SimpleCastState { override fun toString() = "Stopping" }

    /** Transient error, auto-clears. */
    data class Error(val message: String) : SimpleCastState

    /** Projection closing (transient). */
    object Closing : SimpleCastState { override fun toString() = "Closing" }
}

// ─── Cast intent (what the user wants to do) ──────────────────────────────────

sealed interface SimpleCastIntent {
    /** Cast the given app to full cluster. */
    data class CastFull(val pkg: String, val appType: AppType) : SimpleCastIntent

    /** Cast app to a specific slot (left or right). */
    data class CastSlot(val pkg: String, val side: ClusterSlotSide) : SimpleCastIntent

    /** Stop everything (full mode) or stop a specific slot (split mode). */
    data class Stop(val slot: ClusterSlotSide? = null) : SimpleCastIntent

    /** Close projection entirely. */
    object Close : SimpleCastIntent { override fun toString() = "Close" }
}

// ─── Shell interface (dependency inversion — core cannot import dadb) ─────────

interface SimpleCastShell {
    fun execute(command: String): ShellResult
}

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val success: Boolean get() = exitCode == 0
}

// ─── Prefs interface (persistence, implemented in app module) ─────────────────

interface SimpleCastPrefs {
    fun displayConfigFor(pkg: String): DisplayConfig?
    fun saveDisplayConfig(pkg: String, config: DisplayConfig)
    fun lastDisplayId(): Int?
    fun saveLastDisplayId(id: Int)

    // Autostart
    fun autoStartPackage(): String?
    fun setAutoStartPackage(pkg: String?)
    fun autoStartEnabled(): Boolean
    fun setAutoStartEnabled(enabled: Boolean)

    // Split ratio
    fun splitRatioLeftPercent(): Int
    fun setSplitRatioLeftPercent(pct: Int)
}

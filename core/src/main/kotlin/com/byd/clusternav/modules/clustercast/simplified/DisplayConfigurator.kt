package com.byd.clusternav.modules.clustercast.simplified

/**
 * Configures the cluster display's wm size and overscan per app-type.
 *
 * Tracks current applied config to avoid redundant shell commands.
 * Field-proven values measured on vehicle 2026-08-02.
 */
class DisplayConfigurator(
    private val shell: SimpleCastShell,
) {
    @Volatile
    var currentConfig: DisplayConfig? = null
        private set

    /**
     * Applies display config for the given app type.
     * Skips if the same config is already applied.
     * Marks prefs dirty BEFORE changing (CLAUDE.md §5).
     *
     * @return true on success, false on shell failure.
     */
    fun apply(displayId: Int, config: DisplayConfig, prefs: SimpleCastPrefs? = null): Boolean {
        if (config == currentConfig) return true

        // §5: mark dirty BEFORE mutating system state (survives crash/reboot)
        prefs?.setDisplayDirty(true)

        // Set wm size
        val sizeResult = shell.execute("wm size ${config.wmSize} -d $displayId")
        if (!sizeResult.success) return false

        // Set overscan
        val overscanResult = shell.execute("wm overscan ${config.overscan} -d $displayId")
        if (!overscanResult.success) return false

        // Set density (reset or specific value)
        if (config.density != "reset") {
            val densityResult = shell.execute("wm density ${config.density} -d $displayId")
            if (!densityResult.success) return false
        } else {
            val densityResult = shell.execute("wm density reset -d $displayId")
            if (!densityResult.success) return false
        }

        currentConfig = config
        return true
    }

    /**
     * Resets all display settings to default.
     * Clears dirty flag on success (CLAUDE.md §5).
     */
    fun reset(displayId: Int, prefs: SimpleCastPrefs? = null): Boolean {
        val r1 = shell.execute("wm size reset -d $displayId")
        val r2 = shell.execute("wm overscan reset -d $displayId")
        val r3 = shell.execute("wm density reset -d $displayId")
        currentConfig = null
        val ok = r1.success && r2.success && r3.success
        if (ok) prefs?.setDisplayDirty(false)
        return ok
    }

    /**
     * CLAUDE.md §5: reset display to defaults on boot/app-start if previous session crashed
     * while display config was applied (dirty flag set, never cleared).
     *
     * @return true if reset was needed and succeeded, false if not needed or failed.
     */
    fun resetIfDirtyOnBoot(displayId: Int, prefs: SimpleCastPrefs): Boolean {
        if (!prefs.isDisplayDirty()) return false
        return reset(displayId, prefs)
    }

    /**
     * Resolves the effective DisplayConfig for a package:
     * - CP/AA → fixed config from constants
     * - Normal → user-saved config or default
     */
    fun resolveConfig(pkg: String, appType: AppType, prefs: SimpleCastPrefs): DisplayConfig {
        return when (appType) {
            AppType.CARPLAY -> DisplayConfig.CARPLAY
            AppType.ANDROID_AUTO -> DisplayConfig.ANDROID_AUTO
            AppType.NORMAL -> prefs.displayConfigFor(pkg) ?: DisplayConfig.NORMAL_DEFAULT
        }
    }
}

package com.byd.clusternav.modules.clustercast.simplified

/**
 * Density control for the cluster display. Extracted from SimpleCastCoordinator
 * to keep coordinator under 500 LOC.
 *
 * R6: Persists density ONLY after successful shell application.
 * If `wm density` fails, prior saved value is preserved (no corruption).
 */
internal object CastDensityControl {

    /**
     * Set or reset cluster display density. Saves per-app ONLY if shell succeeds.
     * @param dpi density value, or null to reset.
     * @param activePkg the currently casting full-mode package (for per-app save), or null.
     */
    fun set(shell: SimpleCastShell, prefs: SimpleCastPrefs, displayId: Int, dpi: Int?, activePkg: String?) {
        val success = applyDensity(shell, displayId, dpi)
        if (success && activePkg != null) {
            saveForPkg(prefs, activePkg, dpi)
        }
    }

    /**
     * Set density and save per specific named package (split mode).
     * Persists ONLY on shell success.
     */
    fun setForPkg(shell: SimpleCastShell, prefs: SimpleCastPrefs, displayId: Int, dpi: Int?, pkg: String) {
        val success = applyDensity(shell, displayId, dpi)
        if (success) {
            saveForPkg(prefs, pkg, dpi)
        }
    }

    private fun applyDensity(shell: SimpleCastShell, displayId: Int, dpi: Int?): Boolean {
        val result = if (dpi != null && dpi in 80..640) {
            shell.execute("wm density $dpi -d $displayId")
        } else {
            shell.execute("wm density reset -d $displayId")
        }
        return result.success
    }

    private fun saveForPkg(prefs: SimpleCastPrefs, pkg: String, dpi: Int?) {
        val existing = prefs.displayConfigFor(pkg) ?: DisplayConfig.NORMAL_DEFAULT
        prefs.saveDisplayConfig(pkg, existing.copy(density = dpi?.toString() ?: "reset"))
    }
}

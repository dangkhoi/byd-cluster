package com.byd.clusternav.modules.clustercast.simplified

/**
 * Moves apps between display 0 (main) and display 1 (cluster).
 *
 * Strategy per app type:
 * - CP/AA: use `am stack move-task` (field-proven, no NPE)
 * - Normal: use `am start --display <id>` with freeform windowing mode
 *
 * Field-proven on BYD DiLink3 (Android 10), 2026-08-02.
 */
class AppMover(
    private val shell: SimpleCastShell,
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
) {
    /**
     * Moves an app to the cluster display using field-proven commands.
     *
     * Normal app recipe (from CastPlacementCommands, proven on vehicle):
     * 1. Resolve launcher component via `cmd package resolve-activity`
     * 2. `am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER
     *        --display <d> --windowingMode 5 --activity-clear-task -n '<component>'`
     * 3. `am task resize <taskId> <bounds>` to fit cluster viewport
     *
     * CP/AA recipe: `am stack move-task <taskId> <stackId> true`
     */
    fun castToCluster(
        pkg: String,
        activity: String?,
        displayId: Int,
        appType: AppType,
        taskId: Int? = null,
        stackId: Int? = null,
        slotSide: ClusterSlotSide? = null,
        leftPercent: Int = 50,
    ): Boolean {
        return when (appType) {
            AppType.CARPLAY, AppType.ANDROID_AUTO -> {
                // CP/AA uses move-task into an existing stack on the cluster display.
                // If no stack exists on display 1 yet, create one by launching a dummy freeform task.
                val clusterStackId = stackId ?: findOrCreateClusterStack(displayId)
                    ?: return false  // cannot create stack = cannot cast CP/AA
                val cpTaskId = taskId ?: findTaskId(pkg)
                    ?: return false  // CP/AA not running = cannot move
                val result = shell.execute("am stack move-task $cpTaskId $clusterStackId true")
                result.success
            }
            AppType.NORMAL -> {
                // Resolve launcher activity component (proven pattern from CastPlacementCommands)
                val component = activity ?: resolveLauncherComponent(pkg) ?: "$pkg/.MainActivity"
                // Fresh launch with freeform on cluster display
                val launchCmd = "am start -a android.intent.action.MAIN" +
                    " -c android.intent.category.LAUNCHER" +
                    " --display $displayId --windowingMode 5" +
                    " --activity-clear-task -n '$component'"
                val result = shell.execute(launchCmd)
                if (!result.success) return false
                // Fit to cluster viewport after landing (give 1s for task to land)
                sleepMs(1000)
                fitToCluster(pkg, displayId, slotSide, leftPercent)
                true
            }
        }
    }

    /**
     * Find an existing freeform stack on the cluster display.
     * If none exists, launch a lightweight activity to create one, then return its stackId.
     * The dummy activity gets displaced when CP/AA moves into the stack.
     */
    private fun findOrCreateClusterStack(displayId: Int): Int? {
        // First: check if a stack already exists on the display
        findStackOnDisplay(displayId)?.let { return it }
        // None exists — launch a freeform activity to create the stack structure.
        // Using settings as it's lightweight and exists on every BYD head unit.
        shell.execute(
            "am start --display $displayId --windowingMode 5 -n 'com.android.settings/.Settings'"
        )
        sleepMs(1500)
        // Stack now exists; CP/AA move-task will displace settings automatically.
        return findStackOnDisplay(displayId)
    }

    /** Find any freeform stack on the given display. */
    private fun findStackOnDisplay(displayId: Int): Int? {
        val result = shell.execute("am stack list")
        if (!result.success) return null
        // Parse: "Stack id=<N> ... displayId=<D> ..."
        val regex = Regex("Stack id=(\\d+)[^\\n]*displayId=$displayId")
        return regex.find(result.stdout)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Find the taskId for a running package. */
    private fun findTaskId(pkg: String): Int? {
        val result = shell.execute("am stack list")
        if (!result.success) return null
        val regex = Regex("taskId=(\\d+):[^\\n]*$pkg")
        return regex.find(result.stdout)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * After app lands on cluster, resize task to fill the cluster viewport.
     * Proven bounds: [0,90][1920,630] for default viewport on DiLink3 1920×720.
     * `am task resize` takes left, top, right, bottom — NOT width/height.
     *
     * If [slotSide] is specified, the bounds are calculated based on [leftPercent]:
     * - LEFT: [0, 90, 1920*leftPercent/100, 630]
     * - RIGHT: [1920*leftPercent/100, 90, 1920, 630]
     */
    private fun fitToCluster(pkg: String, displayId: Int, slotSide: ClusterSlotSide? = null, leftPercent: Int = 50) {
        // Find taskId on the cluster display
        val stackList = shell.execute("am stack list")
        if (!stackList.success) return
        val taskMatch = Regex("taskId=(\\d+):.*$pkg").find(stackList.stdout) ?: return
        val taskId = taskMatch.groupValues[1]
        // Calculate bounds based on slot side
        val left: Int
        val top = 90
        val right: Int
        val bottom = 630
        when (slotSide) {
            ClusterSlotSide.LEFT -> {
                left = 0
                right = 1920 * leftPercent / 100
            }
            ClusterSlotSide.RIGHT -> {
                left = 1920 * leftPercent / 100
                right = 1920
            }
            null -> {
                left = 0
                right = 1920
            }
        }
        shell.execute("am task resize $taskId $left $top $right $bottom")
    }

    /**
     * Resolve the launcher activity for a package.
     * Uses `cmd package resolve-activity` (same as CastPlacementCommands).
     */
    private fun resolveLauncherComponent(pkg: String): String? {
        val result = shell.execute(
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $pkg"
        )
        if (!result.success) return null
        // Output format: last line is "pkg/activity"
        return result.stdout.trim().lines().lastOrNull()
            ?.takeIf { it.contains("/") && it.contains(pkg) }
    }

    /**
     * Returns an app from the cluster back to display 0.
     *
     * @param pkg package name
     * @param activity fully qualified activity name, nullable for move-task
     * @param appType determines the return strategy
     * @param taskId required for CP/AA
     * @param mainStackId stack ID on display 0 for CP/AA
     * @return true on success
     */
    /**
     * Returns an app from the cluster back to display 0.
     *
     * Normal: `am start --display 0 --windowingMode 1 -n '<component>'` (fullscreen, proven)
     * CP/AA: `am stack move-task <taskId> <homeStackId> true`
     */
    fun returnToMain(
        pkg: String,
        activity: String?,
        appType: AppType,
        taskId: Int? = null,
        mainStackId: Int? = null,
    ): Boolean {
        return when (appType) {
            AppType.CARPLAY, AppType.ANDROID_AUTO -> {
                if (taskId == null || mainStackId == null) {
                    // Find task and home stack dynamically
                    val cpTaskId = findTaskId(pkg) ?: return false
                    val homeStack = findStackOnDisplay(0) ?: return false
                    val result = shell.execute("am stack move-task $cpTaskId $homeStack true")
                    result.success
                } else {
                    val result = shell.execute("am stack move-task $taskId $mainStackId true")
                    result.success
                }
            }
            AppType.NORMAL -> {
                // Return to fullscreen on display 0. windowingMode 1 only affects THIS app's task,
                // not the launcher. Without it, the app stays in freeform (tiny window on main screen).
                val component = activity ?: resolveLauncherComponent(pkg) ?: "$pkg/.MainActivity"
                val result = shell.execute("am start --display 0 --windowingMode 1 -n '$component'")
                result.success
            }
        }
    }

    companion object {
        /** Known CarPlay packages on BYD DiLink. */
        private val CARPLAY_PACKAGES = setOf(
            "com.byd.autolink.carplay",
            "com.byd.carlife.carplay",
        )

        /** Known Android Auto packages on BYD DiLink. */
        private val ANDROID_AUTO_PACKAGES = setOf(
            "com.byd.autolink.androidauto",
            "com.google.android.projection.gearhead",
        )

        /** Classifies a package into AppType. */
        fun classifyApp(pkg: String): AppType = when {
            CARPLAY_PACKAGES.contains(pkg) || pkg.contains("carplay", ignoreCase = true) ->
                AppType.CARPLAY
            ANDROID_AUTO_PACKAGES.contains(pkg) || pkg.contains("android.auto", ignoreCase = true)
                || pkg.contains("androidauto", ignoreCase = true) ->
                AppType.ANDROID_AUTO
            else -> AppType.NORMAL
        }
    }
}

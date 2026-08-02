package com.byd.clusternav.modules.clustercast.v2

/**
 * The exact field-proven placement ladder. Every expectation in the suite references this one list so
 * a silent reordering of the on-car recipe cannot pass unnoticed.
 *
 * Reordered after the 2026-07-26 on-car simulation on DiLink3: the cheap placement plus a conditional
 * fit was sufficient for a resizeable app (cold and warm) and for an unresizeable system app, with no
 * display-global mutation at all. The escalation rungs therefore come after the cheap path and each
 * no-ops once the target has landed, so an interrupted cast cannot leave global residue behind for a
 * placement that never needed it.
 */
internal object ExpectedLadder {
    /** Escalation only. Runs after the cheap path and no-ops when the target already landed. */
    private val escalate = listOf(
        CommandKind.SET_FORCE_RESIZABLE,
        CommandKind.DISABLE_TRANSITION_ANIMATION,
        CommandKind.BLOCK_PIP,
        CommandKind.MOVE_STACK_TO_CLUSTER,
        CommandKind.REASSERT_ON_CLUSTER,
        CommandKind.FIT_CLUSTER_COMPOSITE,
    )

    /** Normal app, session preserving: no force-stop anywhere in the default path. */
    val normal: List<CommandKind> = listOf(
        CommandKind.PRE_OPEN_ON_MAIN,
        CommandKind.PLACE_KEEP_SESSION,
        CommandKind.FIT_CLUSTER_COMPOSITE,
    ) + escalate

    /** Protected app (CarPlay/Android Auto/keep-session): resume only, never opened on display 0. */
    val protectedTarget: List<CommandKind> = listOf(
        CommandKind.RESUME_PROTECTED,
        CommandKind.FIT_CLUSTER_COMPOSITE,
        CommandKind.MOVE_TASK_TO_CLUSTER_STACK,
        CommandKind.SCALE_CLUSTER_DENSITY,
    ) + escalate

    /** Explicit destructive opt-in appends R3 after every gentler rung has been attempted. */
    val normalDestructive: List<CommandKind> = normal + listOf(
        CommandKind.FORCE_STOP_NORMAL,
        CommandKind.START_FRESH_NORMAL,
        CommandKind.FIT_CLUSTER_COMPOSITE,
    )

    /**
     * Stop restores exactly what the ladder changed, then closes the OEM projection.
     *
     * The last two rungs were added on 2026-07-26: without them the OEM keeps mirroring the final
     * cluster frame, so the cluster stayed frozen on a stale map even though the task was already
     * back on display 0. V1 always closed the projection with these opcodes.
     */
    val stopNormal: List<CommandKind> = listOf(
        CommandKind.RETURN_NORMAL_TO_MAIN,
        CommandKind.RESTORE_PIP,
        CommandKind.RESTORE_TRANSITION_ANIMATION,
        CommandKind.RESET_CLEAN_DISPLAY,
        CommandKind.SEAL_DL3_COMPENSATE_18,
        CommandKind.SEAL_DL3_COMPENSATE_0,
    )

    /** Stop for a protected target: return gently + move-task fallback + density reset + teardown. */
    val stopProtected: List<CommandKind> = listOf(
        CommandKind.RETURN_PROTECTED_GENTLY,
        CommandKind.RETURN_TASK_TO_MAIN,
        CommandKind.RESET_CLUSTER_DENSITY,
        CommandKind.RESTORE_PIP,
        CommandKind.RESTORE_TRANSITION_ANIMATION,
        CommandKind.RESET_CLEAN_DISPLAY,
        CommandKind.SEAL_DL3_COMPENSATE_18,
        CommandKind.SEAL_DL3_COMPENSATE_0,
    )
}

/** Exact Seal DL3 display topology observed on the vehicle: main, launcher-split and cluster VD. */
internal val SEAL_CLUSTER_DUMP = """
            Display 0:
              mDisplayId=0
              mBaseDisplayInfo=DisplayInfo{"Built-in Screen, displayId 0", app 1920 x 1080, density 240}
            Display 1:
              mDisplayId=1
              mBaseDisplayInfo=DisplayInfo{"launcher-split, displayId 1", app 1284 x 1080, density 240}
            Display 2:
              mDisplayId=2
              mBaseDisplayInfo=DisplayInfo{"fission_bg_xdjaVirtualSurface, displayId 2", app 1920 x 720, density 180}
        """.trimIndent()

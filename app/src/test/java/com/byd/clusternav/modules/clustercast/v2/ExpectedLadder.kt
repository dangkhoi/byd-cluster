package com.byd.clusternav.modules.clustercast.v2

/**
 * The exact field-proven placement ladder (0.72). Every expectation in the suite references this one
 * list so a silent reordering of the on-car recipe cannot pass unnoticed.
 */
internal object ExpectedLadder {
    private val prepare = listOf(
        CommandKind.SET_FORCE_RESIZABLE,
        CommandKind.DISABLE_TRANSITION_ANIMATION,
        CommandKind.BLOCK_PIP,
    )
    private val land = listOf(
        CommandKind.MOVE_STACK_TO_CLUSTER,
        CommandKind.REASSERT_ON_CLUSTER,
        CommandKind.FIT_CLUSTER_COMPOSITE,
    )

    /** Normal app, session preserving: no force-stop anywhere in the default path. */
    val normal: List<CommandKind> =
        prepare + CommandKind.PRE_OPEN_ON_MAIN + CommandKind.PLACE_KEEP_SESSION + land

    /** Protected app (CarPlay/Android Auto/keep-session): resume only, never opened on display 0. */
    val protectedTarget: List<CommandKind> = prepare + CommandKind.RESUME_PROTECTED + land

    /** Explicit destructive opt-in appends R3 after every gentler rung has been attempted. */
    val normalDestructive: List<CommandKind> = normal + listOf(
        CommandKind.FORCE_STOP_NORMAL,
        CommandKind.START_FRESH_NORMAL,
        CommandKind.FIT_CLUSTER_COMPOSITE,
    )

    /** Stop restores exactly what the ladder changed before clearing the display. */
    val stopNormal: List<CommandKind> = listOf(
        CommandKind.RETURN_NORMAL_TO_MAIN,
        CommandKind.RESTORE_PIP,
        CommandKind.RESTORE_TRANSITION_ANIMATION,
        CommandKind.RESET_CLEAN_DISPLAY,
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

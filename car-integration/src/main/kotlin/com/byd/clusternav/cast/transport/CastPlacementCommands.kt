package com.byd.clusternav.cast.transport

import com.byd.clusternav.modules.clustercast.v2.*

import dadb.Dadb

private const val NO_OP = "echo cast-v2-noop"
private const val FREEFORM_MODE = 5
private const val FULLSCREEN_MODE = 1
private const val HOME_DISPLAY_ID = 0
private const val LAUNCH_PREFIX =
    "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
private val PIP_MODES = setOf("allow", "ignore", "deny", "default", "foreground")

/** Left,top,right,bottom insets used only when freeform is not alive, matching the V1 default. */
private const val OVERSCAN_FALLBACK = "0,90,0,90"
private val PLACEMENT_PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
private val PLACEMENT_COMPONENT = Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")

/**
 * The single place that turns a typed [CommandKind] into the exact shell string dispatched on the
 * vehicle. Keeping the field-proven recipe here — freeform launch, reparent, reassert, fit — makes
 * the placement ladder auditable in one file instead of being spread through the runtime.
 */
object CastPlacementCommands {

    fun of(
        adb: Dadb,
        request: CastMutationRequest,
        cancelled: () -> Boolean,
        isDisplayClean: (Int, () -> Boolean) -> Boolean,
        measuredCluster: () -> NamedClusterDisplay? = { null },
    ): String? {
        if (cancelled()) return null
        fixedSealDl3BootstrapCommand(request.kind)?.let { return it }
        val pkg = request.targetPackage?.takeIf(PLACEMENT_PACKAGE::matches)
        if (request.kind in DISPLAY_FREE_KINDS) return displayFreeCommand(adb, request, pkg, cancelled)
        val display = discoverCastDisplay(adb, cancelled)
            ?: measuredCluster()?.id?.takeUnless { cancelled() }
            ?: return null
        return when (request.kind) {
            // R3 (destructive, opt-in): the task must be gone before --display is honoured at all.
            CommandKind.START_FRESH_NORMAL -> pkg?.let { resolveComponent(adb, it, cancelled) }?.let {
                "$LAUNCH_PREFIX --display $display --windowingMode $FREEFORM_MODE --activity-clear-task -n '$it'"
            }

            // R1: keep the running session. No clear-task, freeform mode so the cluster can composite.
            CommandKind.RESUME_PROTECTED, CommandKind.PLACE_KEEP_SESSION, CommandKind.REASSERT_ON_CLUSTER ->
                pkg?.let { resolveComponent(adb, it, cancelled) }?.let {
                    "$LAUNCH_PREFIX --display $display --windowingMode $FREEFORM_MODE -n '$it'"
                }

            // R2: the decisive rung. reparent bypasses ActivityStarter display gating entirely.
            CommandKind.MOVE_STACK_TO_CLUSTER -> when {
                pkg == null -> NO_OP
                landedStack(adb, pkg, display, cancelled) != null -> NO_OP
                else -> sourceStack(adb, pkg, display, cancelled)
                    ?.let { "am display move-stack $it $display" }
                    // Nothing to reparent is not a failed mutation; R1 or the next rung decides.
                    ?: NO_OP
            }

            // Composite fix: a task carried over from display 0 keeps the old config until resized.
            CommandKind.FIT_CLUSTER_COMPOSITE -> (pkg ?: return@of NO_OP).let { target ->
                // The app not being on the cluster, or an unreadable size, is not a failed mutation:
                // verification owns that verdict. Tearing down a landed cast here was the old bug.
                val landed = landedStack(adb, target, display, cancelled) ?: return@let NO_OP
                val size = clusterRealSize(adb, display, cancelled, measuredCluster) ?: return@let NO_OP
                val density = request.geometry?.densityDpi?.takeIf { it in 80..640 }
                // Khung đích: mặc định là TOÀN cụm, trừ khi kế hoạch yêu cầu một Ô (nửa cụm).
                //
                // `CastPlanner` đánh dấu yêu cầu chia ô bằng `profileId == "cluster-slot-request"` — cùng
                // quy ước sentinel với "cluster-density-request" đã có. Trước 2026-08-01 chỗ này BỎ QUA
                // `geometry.bounds` và luôn resize full màn, nên một lượt chiếu vào ô sẽ phát lệnh rồi
                // trượt xác minh (rect quan sát được ≠ rect đã yêu cầu) — tính năng chia đôi không bao giờ
                // chạy tới nơi. Đây là mắt xích cuối của chuỗi đó.
                //
                // Rect rác (âm, lộn ngược, to hơn cụm) mà bắn thẳng vào `am task resize` là đẩy cửa sổ ra
                // ngoài vùng nhìn thấy, không có đường quay lại ngoài cứu hộ ⇒ phải chặn. Nhưng phép chặn
                // này BẮT BUỘC phải tôn trọng đúng hai không gian toạ độ khác nhau — đây chính là chỗ bản
                // vá đầu tiên của tôi sai và test bắt được:
                //
                //   • `clusterRealSize` (từ `dumpsys display`) báo 1920×720.
                //   • Khung TASK thật trên cùng cụm đó lại là [0,90]–[1920,810] (đo trên xe, đêm 31/7).
                //
                // Cùng chiều cao 720 nhưng LỆCH XUỐNG 90 — đúng độ lệch của bug F3. Nên so `bottom` với
                // chiều cao display là sai: một ô hợp lệ (bottom = 810) bị coi là rác và lặng lẽ ngã về
                // full cụm, tức tính năng chia đôi lại không chạy, đúng kiểu hỏng-mà-không-ai-biết.
                // Chiều NGANG thì hai không gian trùng nhau (rect ô lấy left/right từ chính khung display),
                // nên chỉ chiều đó mới so tuyệt đối được; chiều DỌC chỉ so CHIỀU CAO, không so vị trí đáy.
                val slot = request.geometry
                    ?.takeIf { it.profileId == ClusterSplit.SLOT_PROFILE_ID }
                    ?.bounds
                    ?.takeIf { r ->
                        r.left >= 0 && r.top >= 0 && r.right > r.left && r.bottom > r.top &&
                            r.right <= size.first && (r.bottom - r.top) <= size.second
                    }
                val fit = slot?.let { "${it.left} ${it.top} ${it.right} ${it.bottom}" }
                    ?: ClusterViewport.DILINK3_DEFAULT.let { vp ->
                        "${vp.left} ${vp.top} ${vp.right} ${vp.bottom}"
                    }
                buildString {
                    if (density != null) append("wm density $density -d $display; ")
                    // V1's proven fallback: task resize needs freeform alive. When it is not, the
                    // artistic overscan frame keeps the cast usable instead of tearing down a cast
                    // that did land, and Stop resets overscan with the rest of the display state.
                    //
                    // Measured on DiLink3 2026-07-31 (CarPlay, PROJECTION_SINK style, docs/specs/
                    // cast-recovery-honesty-and-multi-occupant.html): a rejected `am task resize`
                    // writes a full Java stack trace to stderr even though the overall command still
                    // exits 0 once the fallback runs. `||` already decided this is success — that
                    // decision belongs here, at the one place this fallback is authored, not as a
                    // string the effect classifier has to recognize (a TaskRecord{...} dump changes
                    // every call, so no exact/prefix match survives it, and a loose match risks
                    // hiding a real resize failure elsewhere). Silence the rejected branch's own
                    // stderr; the fallback's stderr, if IT fails too, still surfaces unchanged.
                    // Đường lui `wm overscan` CHỈ đúng cho khung toàn cụm: nó là hiệu ứng cấp DISPLAY,
                    // không nhắm được vào một task. Với yêu cầu chia ô, ngã về nó vừa không đặt được ô,
                    // vừa bóp luôn khung của app đang nằm cạnh. Nên ô thì không có đường lui — và vì
                    // không còn `||` để "quyết định trước" rằng thất bại là lành, `2>/dev/null` cũng phải
                    // bỏ: lúc này stderr là tin thật cần nổi lên cho tầng phân loại hiệu ứng, không phải
                    // nhiễu của một nhánh đã được xử lý.
                    if (slot != null) {
                        append("am task resize ${landed.taskId} $fit")
                    } else {
                        append("(am task resize ${landed.taskId} $fit 2>/dev/null)")
                        append(" || wm overscan $OVERSCAN_FALLBACK -d $display")
                    }
                }
            }
            CommandKind.APPLY_TASK_GEOMETRY -> request.expectedTarget?.takeIf { it.displayId == display }?.let { target ->
                request.geometry?.let { CastGeometryCommandEncoder.taskBounds(target, request.expectedDisplayIdentity, it) }
            }
            CommandKind.APPLY_DISPLAY_GEOMETRY -> request.expectedTarget?.takeIf { it.displayId == display }?.let { target ->
                request.geometry?.let { CastGeometryCommandEncoder.displayDensity(target, request.expectedDisplayIdentity, it) }
            }

            // ── CarPlay/AA placement: move-task into cluster stack (2026-08-01, field-proven T1) ─────
            //
            // `am stack move-task <taskId> <stackId> true` di chuyển TASK vào stack đã nằm sẵn trên
            // display cụm. An toàn vì `TaskStack.positionChildAt` gán `task.mStack` TRƯỚC `addChild`,
            // nên không rơi vào cửa sổ null của `DisplayContent.moveStackToDisplay`.
            // Cần: (1) task tồn tại trên display khác, (2) stack đích tồn tại trên display cụm.
            CommandKind.MOVE_TASK_TO_CLUSTER_STACK -> {
                val pkg2 = pkg ?: return@of NO_OP
                // No-op when the target already landed on the cluster via the cheap path above.
                if (landedStack(adb, pkg2, display, cancelled) != null) return@of NO_OP
                val stacks = amStacks(adb, cancelled) ?: return@of NO_OP
                val task = stacks.tasks.firstOrNull { it.packageName == pkg2 && it.displayId != display }
                    ?: return@of NO_OP
                val clusterStack = stacks.stacks.firstOrNull { it.displayId == display }
                    ?: return@of NO_OP
                "am stack move-task ${task.taskId} ${clusterStack.stackId} true"
            }

            // Scale display density so an unresizeable app fits the cluster (field-proven T2c).
            CommandKind.SCALE_CLUSTER_DENSITY -> {
                val pkg2 = pkg ?: return@of NO_OP
                // No-op when the target already landed — density was already set or doesn't need changing.
                if (landedStack(adb, pkg2, display, cancelled) != null) return@of NO_OP
                val size = clusterRealSize(adb, display, cancelled, measuredCluster)
                    ?: return@of NO_OP
                val sourceHeight = 1080
                val clusterDensity = 320
                val scaledDpi = clusterDensity * size.second / sourceHeight
                "wm density $scaledDpi -d $display"
            }
            // ── Escalation rungs. Measured on DiLink3 2026-07-26: the cheap placement alone is
            // enough for a resizeable app cold and warm, and for an unresizeable system app, so
            // paying a display-global cost up front only left residue behind when a cast was
            // interrupted. Each rung is a no-op once the target has actually landed.
            //
            // The flag must be 1, not 0: 1 is what lets an activity that declares itself
            // unresizeable be placed on a differently sized display. Setting it to 0 is what made
            // com.byd.auto_photo unplaceable while reporting "does not stick".
            CommandKind.SET_FORCE_RESIZABLE -> when {
                pkg == null -> NO_OP
                landedStack(adb, pkg, display, cancelled) != null -> NO_OP
                else -> "settings put global force_resizable_activities 1"
            }

            CommandKind.DISABLE_TRANSITION_ANIMATION -> when {
                pkg == null -> NO_OP
                landedStack(adb, pkg, display, cancelled) != null -> NO_OP
                else -> "settings put global window_animation_scale 0; " +
                    "settings put global transition_animation_scale 0"
            }

            CommandKind.BLOCK_PIP -> when {
                pkg == null -> NO_OP
                landedStack(adb, pkg, display, cancelled) != null -> NO_OP
                else -> "appops set $pkg PICTURE_IN_PICTURE ignore"
            }

            CommandKind.RESET_CLEAN_DISPLAY ->
                // A foreign occupant must not be clobbered, but skipping is success, not a failure.
                if (isDisplayClean(display, cancelled)) {
                    request.geometry?.let {
                        CastGeometryCommandEncoder.restoreDisplay(display, request.expectedDisplayIdentity, it)
                    } ?: NO_OP
                } else NO_OP
            else -> null
        }
    }

    /**
     * Kinds that never reference the cluster display. Resolving the display first made Stop's own
     * restore steps, the gentle return to display 0 and the centre-screen pre-open fail whenever the
     * cluster had already disappeared — exactly the disconnected-sink case they exist for.
     */
    private val DISPLAY_FREE_KINDS = setOf(
        CommandKind.FORCE_STOP_NORMAL,
        CommandKind.DISCONNECTED_SINK_RECOVERY_ONCE,
        // SET_FORCE_RESIZABLE / DISABLE_TRANSITION_ANIMATION / BLOCK_PIP are deliberately NOT here:
        // they are escalation rungs that must be able to see whether the target already landed, so
        // they resolve the cluster display. Their RESTORE counterparts stay display-free because
        // Stop has to run them even when the cluster has disappeared.
        CommandKind.RESTORE_TRANSITION_ANIMATION,
        CommandKind.RESTORE_PIP,
        CommandKind.RETURN_NORMAL_TO_MAIN,
        CommandKind.RETURN_PROTECTED_GENTLY,
        CommandKind.PRE_OPEN_ON_MAIN,
        // CarPlay return path: move-task back to display 0 stack + density reset. Both must work
        // even when the cluster display has already disappeared (cable disconnected mid-cast).
        CommandKind.RETURN_TASK_TO_MAIN,
        CommandKind.RESET_CLUSTER_DENSITY,
    )

    private fun displayFreeCommand(
        adb: Dadb,
        request: CastMutationRequest,
        pkg: String?,
        cancelled: () -> Boolean,
    ): String? = when (request.kind) {
        // A task launched in freeform stays a floating window after it returns to the centre screen;
        // V1 proved `am start --windowingMode 1` is the only shell verb that restores fullscreen.
        // A protected sink is returned gently, without touching its windowing mode.
        //
        // No package at all is NOT a failed mutation — it is nothing to return, exactly like the
        // sibling kinds below (`FORCE_STOP_NORMAL`, `PRE_OPEN_ON_MAIN`, `RESTORE_PIP`) already read it.
        // This is the whole Stop ladder's first rung, and Stop is now reachable from a durable state
        // that never had an active target: `OBSERVATION_DIVERGED_STOP_AVAILABLE` offers it while
        // `stableSession.state == IDLE_VERIFIED` (docs/specs/cast-recovery-honesty-and-multi-occupant.html
        // §R2), so `CastFacade.continueStopAfterAcknowledgement` plans with `targetPackage = null`.
        // Failing closed here returned `null` → gateway `Rejected` → `CastExecutor.markRecovery`, i.e.
        // the offered escape wedged the journal in RECOVERING at its FIRST step and never dispatched
        // the rungs that need no package at all (restore animation/app-op, reset a clean display,
        // close the OEM projection so the driver's gauges come back). Locked by
        // `CastStopWithoutActiveTargetTest`. A package that IS known but whose launcher cannot be
        // resolved still fails closed, unchanged — that one really is an unresolved mutation.
        CommandKind.RETURN_NORMAL_TO_MAIN -> when (pkg) {
            null -> NO_OP
            else -> resolveComponent(adb, pkg, cancelled)
                ?.let { "am start --display 0 --windowingMode $FULLSCREEN_MODE -n '$it'" }
        }
        CommandKind.RETURN_PROTECTED_GENTLY -> when (pkg) {
            null -> NO_OP
            else -> resolveComponent(adb, pkg, cancelled)?.let { "am start --display 0 -n '$it'" }
        }

        // A never-started app has no task to relocate, so open it on the centre screen first.
        CommandKind.PRE_OPEN_ON_MAIN -> when {
            pkg == null -> NO_OP
            hasTask(adb, pkg, cancelled) -> NO_OP
            else -> resolveComponent(adb, pkg, cancelled)?.let { "$LAUNCH_PREFIX -n '$it'" } ?: NO_OP
        }

        CommandKind.FORCE_STOP_NORMAL, CommandKind.DISCONNECTED_SINK_RECOVERY_ONCE ->
            // Nothing to force-stop is not a failed mutation; the launch rung still fails closed.
            pkg?.let { "am force-stop $it" } ?: NO_OP

        CommandKind.RESTORE_TRANSITION_ANIMATION -> restoreAnimationCommand(request)

        CommandKind.RESTORE_PIP ->
            pkg?.let { "appops set $it PICTURE_IN_PICTURE ${restorePipMode(request)}" } ?: NO_OP

        // CarPlay/AA return: move task back to any stack on display 0.
        // Field-proven T3 (2026-08-01): `am stack move-task 15 12 true` returned CarPlay safely.
        CommandKind.RETURN_TASK_TO_MAIN -> {
            val target = request.expectedTarget
            val stacks = if (target != null) amStacks(adb, cancelled) else null
            val homeStack = stacks?.stacks?.firstOrNull { it.displayId == HOME_DISPLAY_ID }
            if (target == null) NO_OP
            else if (homeStack == null) null
            else "am stack move-task ${target.taskId} ${homeStack.stackId} true"
        }

        // Reset cluster density after CarPlay returns.
        CommandKind.RESET_CLUSTER_DENSITY -> {
            val display = request.expectedTarget?.displayId
                ?: discoverCastDisplay(adb, cancelled) ?: 1
            "wm density reset -d $display"
        }

        else -> null
    }

    /** Restores the animation scales captured in the operation baseline, never to an unsafe zero. */
    private fun restoreAnimationCommand(request: CastMutationRequest): String {
        fun safe(key: String): String =
            request.baseline?.animationPerKey?.get(key)?.toFloatOrNull()?.takeIf { it > 0f }?.toString() ?: "1.0"
        return "settings put global window_animation_scale ${safe("window_animation_scale")}; " +
            "settings put global transition_animation_scale ${safe("transition_animation_scale")}"
    }

    /** Restores the exact app-op mode observed before the cast rather than a guessed default. */
    private fun restorePipMode(request: CastMutationRequest): String =
        request.baseline?.pipMode?.takeIf { it in PIP_MODES } ?: "allow"

    private fun amStacks(adb: Dadb, cancelled: () -> Boolean): CastAmStackSnapshot? {
        if (cancelled()) return null
        val result = adb.shell("am stack list")
        if (result.exitCode != 0 || result.errorOutput.isNotBlank()) return null
        return (CastAmStackParser.parse(result.output) as? CastDumpParse.Known)?.value
    }

    private fun hasTask(adb: Dadb, pkg: String, cancelled: () -> Boolean): Boolean =
        amStacks(adb, cancelled)?.tasks?.any { it.packageName == pkg } == true

    private fun landedStack(
        adb: Dadb,
        pkg: String,
        display: Int,
        cancelled: () -> Boolean,
    ): CastAmTaskRecord? = amStacks(adb, cancelled)
        ?.tasks?.firstOrNull { it.packageName == pkg && it.displayId == display }

    /** The stack still holding the target somewhere other than the cluster, preferring display 0. */
    private fun sourceStack(adb: Dadb, pkg: String, display: Int, cancelled: () -> Boolean): Int? {
        val tasks = amStacks(adb, cancelled)?.tasks?.filter { it.packageName == pkg && it.displayId != display }
            ?: return null
        return (tasks.firstOrNull { it.displayId == 0 } ?: tasks.firstOrNull())?.stackId
    }

    private fun clusterRealSize(
        adb: Dadb,
        display: Int,
        cancelled: () -> Boolean,
        measuredCluster: () -> NamedClusterDisplay?,
    ): Pair<Int, Int>? {
        if (cancelled()) return null
        val result = adb.shell("dumpsys display")
        if (result.exitCode != 0 || result.errorOutput.isNotBlank()) return null
        val named = discoverClusterDisplay(result.output)?.takeIf { it.id == display }
            ?: measuredCluster()?.takeIf { it.id == display }
            ?: return null
        return named.appWidth to named.appHeight
    }

    private fun resolveComponent(adb: Dadb, pkg: String, cancelled: () -> Boolean): String? {
        if (cancelled()) return null
        val result = adb.shell(
            "cmd package resolve-activity --brief -a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER $pkg"
        )
        if (result.exitCode != 0 || result.errorOutput.isNotBlank()) return null
        return result.output.lineSequence().map(String::trim)
            .firstOrNull { it.startsWith("$pkg/") && PLACEMENT_COMPONENT.matches(it) }
    }

    private fun discoverCastDisplay(adb: Dadb, cancelled: () -> Boolean): Int? {
        if (cancelled()) return null
        val result = adb.shell("dumpsys display")
        if (result.exitCode != 0 || result.errorOutput.isNotBlank()) return null
        return discoverClusterDisplayId(result.output)
    }
}

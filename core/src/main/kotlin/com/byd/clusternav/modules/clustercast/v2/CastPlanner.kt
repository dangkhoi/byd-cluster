package com.byd.clusternav.modules.clustercast.v2

object CastPlanner {
    fun plan(intent: CastIntent, snapshot: PlannerSnapshot): PlanResult {
        val observed = when (val value = snapshot.observed) {
            is ObservationValue.Known -> value.value
            is ObservationValue.Unknown -> return PlanResult.Blocked("Observation unknown: ${value.reason}")
            is ObservationValue.Unsupported -> return PlanResult.Blocked("Observation unsupported: ${value.reason}")
        }
        if (observed.coarseState == ObservedCoarseState.UNKNOWN || observed.coarseState == ObservedCoarseState.SPLIT_BRAIN) {
            return PlanResult.Blocked("Observed state is not safe for planning: ${observed.coarseState}")
        }
        val stable = snapshot.stableSession
        if (intent.kind == CastIntentKind.CAST &&
            (stable?.engineVersion != EngineVersion.V2 || stable.state != StableState.IDLE_VERIFIED ||
                observed.coarseState != ObservedCoarseState.IDLE_CLEAN || observed.target != null ||
                observed.occupants.isNotEmpty() || observed.protectedResidue != null)
        ) return PlanResult.Blocked("CAST requires matching durable and observed V2 idle state")
        if (intent.kind == CastIntentKind.SWITCH &&
            (stable?.engineVersion != EngineVersion.V2 ||
                stable.state !in setOf(StableState.ACTIVE_VERIFIED, StableState.ACTIVE_DEGRADED) ||
                observed.coarseState !in setOf(ObservedCoarseState.ACTIVE_SINGLE, ObservedCoarseState.ACTIVE_MULTI) ||
                stable.activeTarget != observed.target)
        ) return PlanResult.Blocked("SWITCH requires matching durable and observed V2 active state")
        if (intent.kind != CastIntentKind.STOP && intent.kind != CastIntentKind.RECOVER) {
        if (observed.displayIdentity.isNullOrBlank()) {
            return PlanResult.Blocked("Expected display identity is unknown")
        }
            if (!snapshot.installed) return PlanResult.Blocked("Target is not installed")
            if (!snapshot.hasLauncher) return PlanResult.Blocked("Target has no launcher")
            if (snapshot.targetClass == TargetClass.UNKNOWN_PROTECTED) return PlanResult.Blocked("Target policy is unknown")
            if (intent.targetPackage.isNullOrBlank()) return PlanResult.Blocked("Target package is required")
        }
        if (observed.protectedResidue != null && intent.kind == CastIntentKind.SWITCH) {
            return PlanResult.Blocked("Existing protected residue must converge before another switch")
        }
        if (intent.kind == CastIntentKind.STOP && snapshot.stableSession?.baseline?.geometry == null) {
            return PlanResult.Blocked("Stop baseline geometry is unavailable")
        }
        if (intent.kind == CastIntentKind.RECOVER) {
            val proof = intent.recoveryProof
                ?: return PlanResult.Blocked("Disconnected-sink recovery proof is required")
            val observedOwner = observed.protectedResidue?.packageName ?: observed.target?.packageName
            if (!proof.consequenceConfirmed || !proof.phoneDisconnected) {
                return PlanResult.Blocked("Recovery consequence or phone disconnect is not proven")
            }
            if (!proof.projectionComponent ||
                proof.ownerPackage != intent.targetPackage || observedOwner != proof.ownerPackage
            ) return PlanResult.Blocked("Recovery owner identity is not proven")
            if (proof.first != proof.second || proof.second != observed || observed.displayIdentity.isNullOrBlank()) {
                return PlanResult.Blocked("Recovery requires two stable current WM and AM samples")
            }
        }

        val operation = when (intent.kind) {
            CastIntentKind.CAST -> CastOperation.CAST
            CastIntentKind.SWITCH -> CastOperation.SWITCH
            CastIntentKind.STOP -> CastOperation.STOP
            CastIntentKind.RECOVER -> CastOperation.RECOVER
            CastIntentKind.APPLY_GEOMETRY -> CastOperation.APPLY_GEOMETRY
        }
        val steps = when (intent.kind) {
            CastIntentKind.CAST, CastIntentKind.SWITCH -> buildList {
                val sameTarget = observed.target?.packageName == intent.targetPackage
                if (!sameTarget) addAll(placementSteps(snapshot.targetClass, intent.allowDestructive))
                val outgoing = observed.target?.packageName
                if (intent.kind == CastIntentKind.SWITCH && outgoing != null && outgoing != intent.targetPackage) {
                    add(PlannedStep(
                        "return-outgoing-gently",
                        CommandKind.RETURN_PROTECTED_GENTLY,
                        "incoming target observed before outgoing return",
                    ))
                }
            }
            CastIntentKind.STOP -> buildList {
                when (snapshot.targetClass) {
                    TargetClass.NORMAL -> add(
                        PlannedStep("return-normal", CommandKind.RETURN_NORMAL_TO_MAIN, "active normal target identity is fresh")
                    )
                    TargetClass.PROJECTION_SINK, TargetClass.KEEP_SESSION -> add(
                        PlannedStep("return-protected", CommandKind.RETURN_PROTECTED_GENTLY, "protected session must be preserved")
                    )
                    // Trước 2026-07-27 nhánh này KHÔNG trả task về, vì không biết target có phải phiên
                    // được bảo vệ nên sợ làm sai. Nhưng không làm gì lại tạo trạng thái tệ hơn: chiếu
                    // đóng nên cụm về đồng hồ, mà task vẫn nằm trên display 1 → app không hiện ở cả hai
                    // màn. Đo trực tiếp trên xe chiều 27/7: chủ xe nói "đồng hồ, và vietmap cũng ko ở màn
                    // chính luôn, đi đâu mất tiêu rồi".
                    //
                    // Trả về KIỂU NHẸ là hành động ít rủi ro nhất: `am start --display 0` chỉ đưa task
                    // sẵn có lên trước, không đổi windowing mode, không force-stop. Đã kiểm chính lệnh
                    // này trên xe ngay sau đó và app hiện lại ở màn giữa.
                    TargetClass.UNKNOWN_PROTECTED -> add(
                        PlannedStep(
                            "return-unknown-gently",
                            CommandKind.RETURN_PROTECTED_GENTLY,
                            "target policy unknown, so return gently rather than orphan the task",
                        )
                    )
                }
                add(PlannedStep("restore-pip", CommandKind.RESTORE_PIP, "baseline app-op mode is journaled"))
                add(PlannedStep(
                    "restore-transition",
                    CommandKind.RESTORE_TRANSITION_ANIMATION,
                    "baseline animation scales are journaled",
                ))
                add(PlannedStep(
                    "restore-clean-display",
                    CommandKind.RESET_CLEAN_DISPLAY,
                    "gateway independently proves the expected display has zero tasks before reset",
                ))
                // Measured on DiLink3 2026-07-26: returning the task to display 0 is not enough. The
                // OEM keeps mirroring the last cluster frame until it is told to close the
                // projection, so the driver's cluster stayed frozen on a stale map while the app was
                // already back on the centre screen. V1 always closed it (ClusterProfile.teardownSeq
                // = [18, 0]); V2 declared the same opcodes but only used them to compensate a failed
                // bootstrap. Issuing them by hand on the vehicle restored the native gauges with no
                // reboot and no force-stop, so Stop owns them now. Same order as V1: geometry reset
                // first, then close.
                add(PlannedStep(
                    "close-projection",
                    CommandKind.SEAL_DL3_COMPENSATE_18,
                    "cluster geometry already restored before the OEM projection is closed",
                ))
                add(PlannedStep(
                    "refresh-cluster-video",
                    CommandKind.SEAL_DL3_COMPENSATE_0,
                    "projection closed, so the OEM repaints its own cluster content",
                ))
            }
            CastIntentKind.RECOVER -> listOf(
                PlannedStep(
                    "recover-disconnected-sink-once",
                    CommandKind.DISCONNECTED_SINK_RECOVERY_ONCE,
                    "owner, disconnected phone and two stable observations are proven",
                )
            )
            CastIntentKind.APPLY_GEOMETRY -> {
                // Bind locally: these properties live in :core now, and Kotlin does not smart-cast a
                // property declared in another module. Same checks, same values.
                val requestedGeometry = intent.geometry
                val activeTarget = observed.target
                if (requestedGeometry == null || activeTarget == null || activeTarget.packageName != intent.targetPackage ||
                    intent.expectedTarget != activeTarget
                ) {
                    return PlanResult.Blocked("Geometry and exact active target are required")
                }
                buildList {
                    add(PlannedStep("geometry-target", CommandKind.APPLY_TASK_GEOMETRY, "fresh target identity on expected display"))
                    if (requestedGeometry.densityDpi != null && observed.protectedResidue == null &&
                        observed.coarseState == ObservedCoarseState.ACTIVE_SINGLE
                    ) add(PlannedStep("geometry-display", CommandKind.APPLY_DISPLAY_GEOMETRY, "single occupant and no protected residue"))
                }
            }
        }
        val actions = when (intent.kind) {
            CastIntentKind.STOP -> emptySet()
            else -> setOf(CastAction.STOP)
        }
        return PlanResult.Ready(
            CastPlan(
                operation, snapshot.plannerEpoch, steps, terminalFor(operation), actions,
                snapshot.targetClass, observed.displayIdentity,
                expectedTarget = observed.target,
                geometry = if (intent.kind == CastIntentKind.STOP) {
                    snapshot.stableSession?.baseline?.geometry
                } else intent.geometry,
            )
        )
    }

    /**
     * Field-proven placement ladder, reordered after the 2026-07-26 on-car simulation.
     *
     * The cheap path is tried FIRST and costs nothing global: a session-preserving launch on the
     * cluster display, then a fit only when the landed bounds differ from the measured cluster.
     * That alone was sufficient on DiLink3 for a resizeable app (cold and warm) and for an
     * unresizeable system app. Every rung after it is an escalation that no-ops once the target has
     * landed, so an interrupted cast can no longer leave display-global settings mutated for a
     * placement that never needed them.
     */
    private fun placementSteps(
        targetClass: TargetClass,
        allowDestructive: Boolean,
    ): List<PlannedStep> {
        if (targetClass == TargetClass.UNKNOWN_PROTECTED) return emptyList()
        val protected = targetClass != TargetClass.NORMAL
        return buildList {
            // ── Phase 0: no global mutation ──────────────────────────────────────────────
            if (!protected) {
                add(PlannedStep("pre-open-on-main", CommandKind.PRE_OPEN_ON_MAIN, "no-op when the target already has a task"))
            }
            add(
                PlannedStep(
                    if (protected) "resume-protected" else "place-keep-session",
                    if (protected) CommandKind.RESUME_PROTECTED else CommandKind.PLACE_KEEP_SESSION,
                    "session-preserving launch on the expected display",
                ),
            )
            add(PlannedStep("fit-cluster", CommandKind.FIT_CLUSTER_COMPOSITE, "measured cluster size for the landed task"))

            // ── Phase 1: escalation; each rung is a no-op once the target has landed ─────
            add(PlannedStep("allow-resizable", CommandKind.SET_FORCE_RESIZABLE, "only when the cheap placement did not land"))
            add(PlannedStep("quiet-transition", CommandKind.DISABLE_TRANSITION_ANIMATION, "baseline animation scales are journaled"))
            add(PlannedStep("block-pip", CommandKind.BLOCK_PIP, "prior app-op mode is journaled in the baseline"))
            add(PlannedStep("reparent-to-cluster", CommandKind.MOVE_STACK_TO_CLUSTER, "no-op when the task already landed on the cluster"))
            add(PlannedStep("reassert-composite", CommandKind.REASSERT_ON_CLUSTER, "task already resides on the expected display"))
            add(PlannedStep("fit-cluster-escalated", CommandKind.FIT_CLUSTER_COMPOSITE, "measured cluster size after escalation"))

            // ── Phase 2: destructive, explicit opt-in only ──────────────────────────────
            if (!protected && allowDestructive) {
                add(PlannedStep("prepare-normal", CommandKind.FORCE_STOP_NORMAL, "explicit destructive opt-in for a normal target"))
                add(PlannedStep("place-normal", CommandKind.START_FRESH_NORMAL, "expected display identity remains known"))
                add(PlannedStep("fit-cluster-fresh", CommandKind.FIT_CLUSTER_COMPOSITE, "measured cluster size after the fresh launch"))
            }
        }
    }

    private fun terminalFor(operation: CastOperation): String = when (operation) {
        CastOperation.STOP, CastOperation.RECOVER -> "IDLE_VERIFIED or durable recovery terminal"
        else -> "ACTIVE_VERIFIED or ACTIVE_DEGRADED"
    }
}

object CastCaseManifest {
    val cases: List<ManifestCase> = listOf(
        row(1, "First cast normal from gauges", "ACTIVE_VERIFIED : one normal target, exact geometry.", "IDLE_VERIFIED after restore; else RECOVERY_PENDING .", "VD-ready max 2 pre-mutation; max 1 full retry only after verified restore.", "CAR-HIST recipe; V2-OFF transcript/fault; V2-CAR cold-normal."),
        row(2, "First cast CarPlay", "ACTIVE_VERIFIED : resumed sink, phone session intact.", "IDLE_VERIFIED with session preserved, or RECOVERY_PENDING ; never teardown through visible sink.", "One bounded resume/reassert; 0 destructive retry.", "CAR-HIST context; SRC classification; V2-OFF; V2-CAR CP session continuity."),
        row(3, "First cast Android Auto", "ACTIVE_VERIFIED via resume-only allowlist.", "IDLE_VERIFIED /previous stable with “AA did not attach”; no move-stack exception.", "One resume/reassert; then 0 retry.", "CAR-HIST context; V2-OFF redirect; V2-CAR AA attach/session."),
        row(4, "Recast same normal", "ACTIVE_VERIFIED ; no force-stop/empty-VD interval.", "Previous ACTIVE_VERIFIED ; RECOVERY_PENDING only if reassert mutated then diverged.", "One reassert, no full-operation retry.", "SRC helper; V2-OFF same-target/F1; V2-CAR recast."),
        row(5, "Recast same protected target", "Preserve prior ACTIVE_VERIFIED or ACTIVE_DEGRADED ; session intact.", "Previous stable state; no destructive fallback.", "One reassert only.", "SRC helper; V2-OFF protected recast; V2-CAR CP/AA recast."),
        row(6, "Normal → normal", "ACTIVE_VERIFIED : B visible/exact; A fullscreen d0; one occupant.", "Previous A ACTIVE_VERIFIED , or RECOVERY_PENDING if compensation fails.", "One B reassert; after mutation max 1 retry only after verified compensation.", "CAR-HIST normal switch; V2-OFF sequence/TOCTOU; V2-CAR stress."),
        row(7, "Normal → CP", "ACTIVE_VERIFIED ; CP resumed, normal returned.", "Previous normal ACTIVE_VERIFIED ; CP never force-stopped.", "One CP resume/reassert; 0 destructive retry.", "SRC current intent only; V2-OFF; V2-CAR critical."),
        row(8, "Normal → AA", "ACTIVE_VERIFIED via resume; no hidden placement primitive.", "Previous normal ACTIVE_VERIFIED with actionable attach failure.", "One AA resume/reassert; then 0.", "V2-OFF redirect/fail-closed; V2-CAR critical."),
        row(9, "CP → normal", "ACTIVE_VERIFIED if CP returned; otherwise ACTIVE_DEGRADED with exactly one hidden CP residue and geometry flag.", "Previous CP stable state or RECOVERY_PENDING ; never second residue.", "One target reassert; one gentle CP return; 0 destructive sink retry.", "AOSP+SRC context; V2-OFF residue cap; V2-CAR critical."),
        row(10, "AA → normal", "ACTIVE_VERIFIED or one-residue ACTIVE_DEGRADED .", "Previous AA stable state or RECOVERY_PENDING .", "One target reassert + one gentle AA return; 0 destructive sink retry.", "AOSP+SRC context; V2-OFF; V2-CAR critical."),
        row(11, "CP → AA", "ACTIVE_VERIFIED or ACTIVE_DEGRADED with one hidden CP only.", "Previous CP state; abort before mutation if an existing residue would make two.", "One AA reassert + one gentle CP return; no session kill.", "V2-OFF residue/preflight; V2-CAR sink-pair critical."),
        row(12, "AA → CP", "ACTIVE_VERIFIED or ACTIVE_DEGRADED with one hidden AA only.", "Previous AA state; abort before a second residue.", "One CP reassert + one gentle AA return.", "V2-OFF residue/preflight; V2-CAR sink-pair critical."),
        row(13, "Target fails to land", "Safe-abort to exact previous stable state; no source mutation.", "RECOVERY_PENDING only if target-side baseline cannot be restored.", "One bounded reassert, then abort.", "SRC helper; V2-OFF orchestration/fault; V2-CAR sampled."),
        row(14, "Target lands then bounces", "Previous stable target retained after TOCTOU recheck.", "RECOVERY_PENDING if old was touched despite bounce.", "0 full retry until fresh observation proves old stable; then user retry.", "SRC test; V2-OFF cut-point fault; V2-CAR GL target."),
        row(15, "Old app resists return", "Normal old cleaned → ACTIVE_VERIFIED ; protected old retained once → ACTIVE_DEGRADED .", "RECOVERY_PENDING / MANUAL_REQUIRED for unknown visibility or residue overflow.", "Normal: one force-stop/relaunch; protected: one gentle attempt, 0 destructive.", "SRC+AOSP context; V2-OFF class matrix; V2-CAR resistance."),
        row(16, "Projection cable/session disconnects; typed eligibility is required before any destructive heal.", "IDLE_VERIFIED , or remaining normal target ACTIVE_VERIFIED , after an owner-only heal gated by DISCONNECTED_SINK_RECOVERY_ELIGIBLE.", "MANUAL_REQUIRED if eligibility is Unknown or the token persists; no context-based action lock.", "One explicit force-stop heal only after eligibility is re-observed and journaled for this recovery transaction; no repeat and no budget reset by epoch.", "AOSP inference; V2-OFF eligibility matrix independent of interaction context; V2-CAR critical disconnect."),
        row(17, "User presses Stop", "IDLE_VERIFIED : zero tokens, baseline geometry/PIP/anim/profile read back, gauges verified.", "RECOVERY_PENDING , escalating to MANUAL_REQUIRED ; never false idle.", "One journaled compensation attempt after ownership; watchdog finite budget, no duplicate Stop mutation.", "V2-OFF every cut point; V2-CAR Stop normal/CP/AA/residue."),
        row(18, "Stop while operation busy/hung", "IDLE_VERIFIED only after mutation ownership and effect are known.", "RECOVERY_PENDING(UNKNOWN_IN_FLIGHT_EFFECT) ; no concurrent mutator.", "Read-only reobserve until known; then one compensation. 0 blind mutation retry.", "V2-OFF blocked-I/O/close/epoch; V2-CAR transport interruption."),
        row(19, "App process dies/restarts during cast", "Recovered ACTIVE_VERIFIED / ACTIVE_DEGRADED or IDLE_VERIFIED from envelope.", "RECOVERY_PENDING / MANUAL_REQUIRED ; original mutation never blindly replayed.", "The originating transaction retains one durable compensation budget across restart, reboot fencing and epoch changes; restart never replenishes it. A distinct full-operation retry requires independently verified compensation plus a fresh plan/new epoch.", "V2-OFF process-kill every phase; V2-CAR selected phases."),
        row(20, "Head unit sleeps/wakes", "Prior stable state re-verified under new boot/wake observation.", "RECOVERY_PENDING ; no auto-cast while unresolved.", "Lifecycle-triggered reobserve; no mutation retry until journal + speed gate pass.", "V2-OFF bootId/epoch; V2-CAR sleep/wake."),
        row(21, "Orphan pre-exists before cast; owner-known/session-lost eligibility is mandatory.", "One owner-authorized heal yields IDLE_VERIFIED , then operation is re-planned from scratch.", "MANUAL_REQUIRED if ownership or eligibility fails; no cast or teardown.", "Exactly one heal after all predicates are journaled; 0 operation retry if still dirty.", "AOSP inference; V2-OFF ownership/eligibility guard; V2-CAR orphan experiment."),
        row(22, "Orphan created during cast; destructive heal requires owner/session eligibility but no interaction-context gate.", "Verified compensation returns previous stable or IDLE_VERIFIED .", "RECOVERY_PENDING / MANUAL_REQUIRED ; transaction retained when eligibility or verification fails.", "0 automatic operation retry until two-sample clean; one heal only after eligibility is proven and journaled.", "V2-OFF injected divergence and eligibility failures; V2-CAR only if safely reproducible."),
        row(23, "Resize/DPI/position while cast", "Same active state with target-bound geometry verified; degraded if global geometry forbidden.", "Previous active state/geometry after rollback; else RECOVERY_PENDING .", "Coalesce one latest request; one geometry compensation, no full cast retry.", "SRC guards; V2-OFF identity/residue tiers; V2-CAR geometry tiers."),
        row(24, "Resize when state target stale", "No mutation; observed target state preserved and UI refreshed; preference saved for later.", "Same stable state; UNKNOWN becomes read-only recovery, never display-0 mutation.", "0 immediate retry; user/app may retry after fresh active identity.", "SRC guard; V2-OFF stale-target boundary."),
        row(25, "PIP app before/after cast", "Cast terminal as planned and exact prior app-op restored/read back.", "RECOVERY_PENDING with PIP compensation pending; marker retained.", "One restore/read-back per recovery epoch; no new cast until resolved.", "SRC partial; V2-OFF app-op cut points; V2-CAR PIP smoke."),
        row(26, "Not installed / no launcher", "Previous stable state unchanged; validation outcome shown.", "Same stable state with install/choose action; no journal mutation ledger.", "0.", "SRC validation; V2-OFF contract."),
        row(27, "adb/dadb I/O failure", "Previous stable or IDLE_VERIFIED after compensation.", "RECOVERY_PENDING for unknown/post-mutation effect.", "Before mutation max 2 with exponential backoff+jitter; after mutation max 1 only after verified compensation; unknown effect 0.", "V2-OFF gateway timeout/close/fault; V2-CAR transport interrupt."),
        row(28, "VD missing/slow", "ACTIVE_VERIFIED after identity rediscovery, or safe IDLE_VERIFIED abort.", "RECOVERY_PENDING if activation mutated and restore fails.", "Max 2 readiness attempts pre-mutation; max 1 after verified restore.", "SRC poll context; V2-OFF; V2-CAR cold slow path."),
        row(29, "Unsupported vehicle/profile", "Previous stable unchanged; diagnostics/export action.", "Same stable state; no service call.", "0.", "SRC profile tests; V2-OFF model validation; V2-CAR each supported model before enable."),
        row(30, "Auto-cast at boot while moving", "Prior session resolved; moving/unknown speed leaves IDLE_VERIFIED or previous stable unchanged.", "RECOVERY_PENDING if prior journal unresolved; auto intent canceled.", "No immediate retry; next lifecycle trigger only after journal resolved and confirmed stopped.", "SRC partial; V2-OFF lifecycle/speed; V2-CAR stationary/moving gate."),
        row(31, "User selects another app in-flight", "Current operation reaches stable point, then one latest durable intent reaches its verified terminal.", "Previous stable/ RECOVERY_PENDING ; pending app intent dropped when Stop supersedes.", "Exactly one latest intent re-plan; no concurrent writes/no queue growth.", "V2-OFF ordering/process-death; V2-CAR rapid selection."),
        row(32, "Exactly-two-pipeline independence under the D8 fault model", "Adapter exception and owned-executor block/saturation leave the other pipeline uninterrupted. Component/host restart rehydrates each store independently without corruption.", "No cross-store corruption or cross-control mutation. Shared main-thread ANR/process/platform failure may interrupt both in a same-process build but must converge through independent rehydration; uninterrupted continuity requires approved process separation.", "Each pipeline retries/recovers only through its own policy; no cross-cancel, cross-reset, shared live gateway, retry budget or compensating action.", "V2-OFF static graph + full D8 fault matrix in both directions; V2-CAR owned-resource isolation and physical-reboot rehydration. Exact-two-runtime PASS also requires separately approved Dead Reckon retirement."),
    )

    private fun row(
        id: Int, name: String, success: String, failure: String, retry: String, evidence: String,
    ) = ManifestCase(id, name, success, failure, retry, evidence)
}


/** Pure Stage-3 transcript: symbolic command kinds only, never a live gateway call. */
data class CastDryRunStep(
    val id: String,
    val commandKind: CommandKind,
    val guard: String,
    val mutating: Boolean,
)

class CastDryRunTranscript(
    val operation: CastOperation,
    val epoch: Long,
    val expectedPostcondition: String,
    steps: List<CastDryRunStep>,
) {
    val steps: List<CastDryRunStep> = java.util.Collections.unmodifiableList(ArrayList(steps))
}

object CastDryPlanner {
    fun render(result: PlanResult): CastDryRunTranscript? = when (result) {
        is PlanResult.Blocked -> null
        is PlanResult.Ready -> result.plan.let { plan ->
            CastDryRunTranscript(
                plan.operation,
                plan.epoch,
                plan.expectedPostcondition,
                plan.steps.map { CastDryRunStep(it.id, it.commandKind, it.guard, it.commandKind.mutating) },
            )
        }
    }
}

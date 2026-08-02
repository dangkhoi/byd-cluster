package com.byd.clusternav.modules.clustercast.v2

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap


private val ANDROID_PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

/** Provenance stamp written into `stable=` while [CastCoordinator.clearCluster] is walking its ladder. */
private const val CLEAR_CLUSTER_BUILD = "runtime-clear-cluster"

/**
 * What [CastCoordinator.clearCluster] actually did, stated per rung rather than as one boolean.
 *
 * There is deliberately no "Succeeded" case carrying nothing else: the owner had to wipe app data five
 * times on the night of 2026-07-31 because every surface could only say "done" or "stuck", and neither
 * told him whether the physical gauges were back. [Ran] separates the three facts that matter and that
 * can genuinely differ — which occupants were handed a return command, whether the OEM projection was
 * told to close, and what a fresh observation says is still sitting on the cluster afterwards.
 */
sealed interface ClusterClearResult {
    /** Nothing was dispatched at all. The vehicle is exactly as it was. */
    data class Blocked(val reason: String) : ClusterClearResult

    data class Ran(
        val clusterDisplayId: Int,
        /** Packages whose return command the gateway reported an effect for, in dispatch order. */
        val returned: List<String>,
        /** Package → why its return rung did not report an effect. Never aborts the rest of the ladder. */
        val failures: Map<String, String>,
        /** True only when the close-projection opcodes themselves reported an effect. */
        val projectionClosed: Boolean,
        val projectionFailure: String?,
        /** Occupants a fresh observation still sees on the cluster; null when it could not be read. */
        val remainingOccupants: Set<String>?,
    ) : ClusterClearResult {
        /**
         * The only claim a surface may render as success: the projection was closed AND a fresh
         * observation proves nothing is left on the cluster. A return command that the gateway accepted
         * is NOT proof the app moved — that lesson is the whole point of the 2026-07-27 retraction
         * recorded in [CastCoordinator.reconcileAbandoned].
         */
        val cleared: Boolean get() = projectionClosed && remainingOccupants?.isEmpty() == true
    }
}

/** V2 owner facade. Constructing it has no mutation; runtime wiring remains separately controlled. */
class CastCoordinator(
    private val store: CastSessionStore,
    private val observation: ObservedStateReader,
    private val executor: CastExecutor,
    private val recovery: CastRecovery,
    private val now: () -> Instant = Instant::now,
    private val manualSleeper: CastSleeper = CastSleeper(Thread::sleep),
    // Đo trên DiLink3 2026-07-31 (docs/specs/cast-recovery-honesty-and-multi-occupant.html): dải lệnh
    // CAST bình thường có 9 bước shell (PRE_OPEN_ON_MAIN..FIT_CLUSTER_COMPOSITE escalated), mỗi bước một
    // round-trip adb/dadb riêng — 500ms không đủ để cả dải chạy xong VÀ app đích ổn định layout trước
    // khi lấy mẫu đầu tiên. Hậu quả đo được: `verify()` lấy mẫu lúc placement CHƯA XONG (thiếu geometry
    // hoặc coarseState còn transitional) → `completeVerificationLocked` coi là "verification diverged"
    // và ghim RECOVERING vĩnh viễn — dù vài giây sau (xác nhận bằng `am stack list`/`dumpsys display`
    // thật) app đã lên đúng cụm. Ngân sách thật của cả transaction là 15s (`CastExecutor.operationTimeoutMillis`),
    // 500ms gần như không dùng gì tới nó. Không đổi §2 "target sai thì fail nhanh" của
    // `CastNormalSliceTest` (target sai vẫn sai y hệt sau 3s), chỉ đổi thời gian tới mẫu đầu tiên.
    private val manualVerificationDelayMillis: Long = 3_000L,
) {
    private val verificationSamples = ConcurrentHashMap<java.util.UUID, ObservedState>()

    /**
     * Bố cục ô mà một lượt đặt đang phục vụ, khoá theo `operationId` — vòng đời y hệt [verificationSamples].
     *
     * VÌ SAO TRONG RAM, KHÔNG BỀN. `CastSessionStore` mã hoá/giải mã envelope ở MỌI lần ghi
     * (`CastEnvelopeCodec.encode/decode`), nên một trường mới trên `CastTransaction` mà codec chưa biết sẽ
     * BIẾN MẤT ngay lần `update{}` kế tiếp — im lặng, không lỗi, đúng loại hỏng tệ nhất. Codec nằm ngoài
     * phạm vi sửa của đợt này, nên nửa BỀN của yêu cầu chỉ đi bằng trường codec ĐÃ mã hoá sẵn:
     * `tx.requestedGeometry` (rect của ô + dấu [ClusterSplit.SLOT_PROFILE_ID]).
     *
     * HỎNG THÌ HỎNG VỀ PHÍA AN TOÀN. Tiến trình chết giữa lúc chờ xác minh ⇒ map này rỗng trong khi
     * `tx.requestedGeometry` vẫn nói "đây là một ô". [completeVerificationLocked] khi ấy trả `false` thẳng
     * (xem nhánh `layout == null` ở đó): KHÔNG bao giờ tự hạ xuống phép kiểm một-app rồi tuyên bố thành
     * công cho một bố cục mà nó không còn biết nội dung. Đường thoát vẫn là Dừng / Dọn cụm như mọi
     * transaction RECOVERING khác.
     */
    private val intendedLayouts = ConcurrentHashMap<java.util.UUID, IntendedClusterLayout>()

    fun initialize(bootId: String): CastSessionEnvelope = store.locked { initialize(bootId) }

    fun applyRollout(flags: CastRolloutFlags): CastSessionEnvelope = store.locked {
        update { CastRolloutRegistry.apply(it, flags) }
    }

    /** Persist Stop intent and bump the cancellation epoch before waiting for any mutation lease. */

    fun queueLatestTarget(packageName: String): Boolean {
        require(ANDROID_PACKAGE.matches(packageName)) { "Invalid Android package name" }
        return store.locked {
            val loaded = read() as? StoreRead.Loaded ?: return@locked false
            if (loaded.envelope.stopRequested) return@locked false
            val replaceable = loaded.envelope.transaction != null ||
                (loaded.envelope.pendingIntent != null && loaded.envelope.stableSession != null)
            if (!replaceable) return@locked false
            update { it.withPendingPackage(packageName) }
            true
        }
    }

    fun requestStop(): CastSessionEnvelope? {
        val accepted = store.locked {
            val loaded = read() as? StoreRead.Loaded ?: return@locked null
            if (loaded.envelope.stopRequested) return@locked loaded.envelope
            bumpEpoch { envelope -> envelope.copy(stopRequested = true, pendingIntent = null) }
        } ?: return null
        executor.fenceInFlight()
        return accepted
    }

    fun plan(intent: CastIntent, targetEvidence: TargetEvidence, installed: Boolean, hasLauncher: Boolean): PlanResult {
        val envelope = store.locked { (read() as? StoreRead.Loaded)?.envelope }
            ?: return PlanResult.Blocked("durable store unavailable")
        if (envelope.transaction != null) return PlanResult.Blocked("operation already active")
        if (envelope.stopRequested && intent.kind != CastIntentKind.STOP) {
            return PlanResult.Blocked("Stop requested; new mutation intents are fenced")
        }
        return CastPlanner.plan(
            intent,
            PlannerSnapshot(
                observed = observation.read(),
                stableSession = envelope.stableSession,
                targetClass = CastPolicy.classify(targetEvidence),
                installed = installed,
                hasLauncher = hasLauncher,
                plannerEpoch = envelope.durableEpoch,
            ),
        )
    }

    fun execute(result: PlanResult, targetPackage: String?): ExecutionResult = when (result) {
        is PlanResult.Blocked -> ExecutionResult.Blocked(result.reason)
        is PlanResult.Ready -> {
            val baseline = store.locked {
                val envelope = (read() as? StoreRead.Loaded)?.envelope
                val stable = envelope?.stableSession
                if (result.plan.operation == CastOperation.APPLY_GEOMETRY && stable != null) {
                    CastBaseline(
                        stable.baseline.occupants,
                        stable.acceptedGeometry ?: stable.baseline.geometry,
                        stable.baseline.animationPerKey,
                        stable.baseline.pipMode,
                        stable.baseline.profile,
                    )
                } else stable?.baseline ?: CastBaseline()
            }
            executor.execute(result.plan, baseline, targetPackage)
                .also { rememberIntendedLayout(it, result.plan.intendedLayout) }
        }
    }

    /**
     * Ghi nhớ bố cục ô của một lượt đặt VỪA phát lệnh xong, để [completeVerificationLocked] có cái mà đối
     * chiếu. Không làm gì khi kế hoạch không có ô (`null`) hoặc khi lệnh không được phát — nhờ vậy đường
     * chiếu toàn cụm không hề chạm tới map này, và map cũng không phình vì những kế hoạch bị chặn.
     */
    private fun rememberIntendedLayout(result: ExecutionResult, layout: IntendedClusterLayout?) {
        if (layout == null) return
        if (result is ExecutionResult.AwaitingVerification) intendedLayouts[result.operationId] = layout
    }

    fun observeAndComplete(operationId: java.util.UUID): Boolean = executor.withMutationLease {
        val first = observation.read() as? ObservationValue.Known
        if (first == null) {
            resetVerificationSampleLocked(operationId)
            return@withMutationLease false
        }
        if (completeVerificationLocked(operationId, first.value)) return@withMutationLease true
        val second = observation.read() as? ObservationValue.Known
        if (second == null) {
            resetVerificationSampleLocked(operationId)
            return@withMutationLease false
        }
        val completed = completeVerificationLocked(operationId, second.value)
        if (!completed) resetVerificationSampleLocked(operationId)
        completed
    }

    /**
     * Closes an operation that was issued and never resolved, when the vehicle itself proves there is
     * nothing left to undo.
     *
     * Measured on the vehicle 2026-07-27: a CAST transaction sat at phase=RECOVERING with
     * place-keep-session at effect=ISSUED, durableEpoch=3 against tx.epoch=2, and stopRequested set.
     * Every one of those three independently disqualifies compensation in CastRecovery.decide, and
     * completeVerification only accepts phase=VERIFYING, so no path existed to end the operation. The
     * screen stayed READ_ONLY for good: no cast, no stop, not even app selection. A durable journal
     * must always have a way back to a truthful terminal state.
     *
     * This is not an assumption that the operation failed. It requires a fresh Known observation
     * showing the cluster idle with no occupants and no target, and requires that no observed step
     * still owes a compensation. Under those two proofs the world is already at baseline, so the only
     * honest record is "operation ended, nothing left behind". The epoch is bumped so a late gateway
     * reply from the abandoned operation cannot be mistaken for a live one.
     */
    fun reconcileAbandoned(): Boolean = executor.tryMutationLease {
        val transaction = (store.locked { read() } as? StoreRead.Loaded)?.envelope?.transaction
            ?: return@tryMutationLease false
        if (transaction.phase != OperationPhase.RECOVERING) return@tryMutationLease false
        val id = transaction.operationId

        // RETRACTED 2026-07-27 by owner correction: an earlier version of this function adopted a
        // stuck transaction as a success when the observation showed ACTIVE_SINGLE with the expected
        // target on display-1. The owner confirmed the physical cluster never displayed that app at any
        // point, and the captured fixture agrees: the task was parented to the cluster's virtual
        // display while the driver saw the native gauges throughout. Window-manager placement is
        // therefore not evidence of a cast, and no success may be declared from it. See
        // docs/refactor-car-execution/spec.html Q1/Q7.
        //
        // What remains is the conservative half: an operation may be closed only when the vehicle shows
        // nothing left to undo. Note that this half rests on the same window-manager reading and is
        // itself under review as Q8.
        val second = (observation.read() as? ObservationValue.Known)?.value
            ?: return@tryMutationLease false
        if (second.coarseState != ObservedCoarseState.IDLE_CLEAN ||
            second.occupants.isNotEmpty() ||
            second.target != null ||
            second.protectedResidue != null
        ) {
            return@tryMutationLease false
        }
        if (transaction.ledger.any {
                it.effect == LedgerEffect.OBSERVED && it.compensation != null && !it.compensationObserved
            }
        ) {
            return@tryMutationLease false
        }
        forgetOperation(id)
        // Deliberately does NOT clear stopRequested. "IDLE_CLEAN" is CastAmStackParser proof that the
        // window-manager layer holds nothing on the cluster display — it is NOT proof the OEM
        // AutoContainer projection was ever told to close. CastPlanner.kt's own comment on the STOP
        // ladder (measured on the vehicle 2026-07-26) records that the OEM keeps mirroring the last
        // cluster frame until the close-projection opcodes run. Before this fix, a driver who tapped
        // Stop while an unrelated transaction was stuck in RECOVERING could have that tap silently
        // discarded the moment this reconciler ran: it cleared stopRequested as a side effect of
        // closing the abandoned transaction, with SEAL_DL3_COMPENSATE_18/0 never dispatched, so the
        // physical cluster could stay frozen on a stale frame while the app believed Stop was done.
        // Clearing the stuck transaction is still safe to do purely from observation, independent of
        // whether Stop was requested — bookkeeping cleanup and Stop fulfillment are different facts and
        // must not be conflated into one assignment. If stopRequested is true, it stays true: the next
        // Stop attempt now finds transaction == null and runs the real teardown, including the
        // close-projection opcodes, instead of finding nothing left to do.
        store.locked { bumpEpoch { envelope -> envelope.copy(transaction = null) } }
        CastOperationLog.record(
            "closed abandoned ${transaction.operation} ($id): cluster observed idle, no compensation owing",
        )
        true
    } ?: false

    /**
     * Clears a durable idle claim that this boot can never re-verify, so the next refresh is free to
     * bootstrap fresh instead of dead-ending forever.
     *
     * Measured on the vehicle 2026-07-29 after a real ignition power cycle: the cluster's virtual
     * display is WindowManager-level only and never survives a reboot, but `stableSession` (an
     * `IDLE_VERIFIED` claim recorded before the reboot) is deliberately preserved across a boot change
     * by `CastSessionStore`/`CastLifecycleMigration` so an unrelated transaction can still be diagnosed.
     * Nothing, however, ever re-verifies a *non-transaction* idle claim from the Activity's own refresh
     * path — only the boot-receiver/watchdog path does, via `CastLifecycleMigration.revalidateStable`.
     * The result was permanent `MANUAL_REQUIRED` with only read-only Diagnostics, on every single boot
     * after the first successful cast of the app's life, for every app, not just the one that was
     * active at reboot time.
     *
     * This is deliberately narrower than `revalidateStable`: it only ever fires when nothing was
     * active (`IDLE_VERIFIED`, so there is no target/session to protect or restore) and the cluster
     * display cannot be found AT ALL (`Unknown(MISSING_NAMED_CLUSTER_DISPLAY_REASON)`), never for a
     * `Known`-but-mismatched observation, which may mean something unexpected really is on the
     * display and must stay conservative. `ACTIVE_VERIFIED`/`ACTIVE_DEGRADED` after a vanished display
     * already has a correct, narrower mechanism (`CastAndroidLifecycle.vanished`/
     * `restoreVanishedCluster`) that this function must not duplicate or race with.
     *
     * The last gate is the strictest one: the claim is dropped ONLY when dropping it is by itself
     * enough to reach cold-pristine, asked of [CastRuntimeUi.isColdPristine] against the exact
     * envelope this write would produce. Added in review 2026-07-29 after two ways the unguarded
     * version made things worse rather than better. A durable Stop, a pending placement, an open
     * geometry draft or a pending UI rollback each independently keep `isColdPristine` false, so
     * clearing under any of them destroyed the only record of what the cluster used to be while
     * leaving the screen exactly as stuck as before — and with `stopRequested` set it produced a NEW
     * dead end: `CastRolloutRegistry.resolve` reports no action owner once nothing is recorded, so
     * `CastFacade.v2OwnsActions` turns false and the pending Stop could never be dispatched to clear
     * itself. Refusing here is not a regression for those states: they were already blocked, they
     * keep their evidence, and the next refresh retries this reconciler once the blocker is gone.
     */
    fun reconcileUnobservableIdleSession(): Boolean = executor.tryMutationLease {
        val envelope = (store.locked { read() } as? StoreRead.Loaded)?.envelope
            ?: return@tryMutationLease false
        if (envelope.transaction != null) return@tryMutationLease false
        val stable = envelope.stableSession ?: return@tryMutationLease false
        if (stable.state != StableState.IDLE_VERIFIED) return@tryMutationLease false
        val current = observation.read()
        if (current !is ObservationValue.Unknown || current.reason != MISSING_NAMED_CLUSTER_DISPLAY_REASON) {
            return@tryMutationLease false
        }
        // The projected envelope deliberately keeps the current epoch: isColdPristine stopped reading
        // durableEpoch on 2026-07-29, for exactly the reason this function exists. Should an epoch
        // condition ever be reintroduced there, this projection has to bump too or it will lie.
        if (!CastRuntimeUi.isColdPristine(StoreRead.Loaded(envelope.copy(stableSession = null)), current)) {
            return@tryMutationLease false
        }
        store.locked { bumpEpoch { it.copy(stableSession = null) } }
        CastOperationLog.record(
            "cleared unobservable idle stable session: cluster display not found, nothing was active, safe to re-bootstrap",
        )
        true
    } ?: false

    /**
     * Clears a stale RECOVERY_PENDING session when the cluster is observed empty (IDLE_CLEAN).
     *
     * Measured on DiLink3 2026-08-01: a cast that ended in RECOVERING (verification diverged) left
     * `stableSession.state = RECOVERY_PENDING` + `stopRequested = true` in the durable store. On
     * the next app launch (same boot OR different boot), this combination PERMANENTLY blocked every
     * intent: "Stop bị chặn: Observation unknown" — because stopRequested fences new intents, and
     * nothing in the existing reconcile set could clear it when the cluster was already empty.
     *
     * Logic: if stableSession is RECOVERY_PENDING, no transaction is active, and a fresh observation
     * proves the cluster is genuinely empty (IDLE_CLEAN, no occupants, no target, no residue), then
     * there is nothing left to recover. Clear the session AND stopRequested to let bootstrap run.
     *
     * This is the SAME observation proof that [reconcileAbandoned] uses (cluster idle + no occupants),
     * but this path handles the case where the transaction has already been closed and only the
     * stale stableSession remains.
     */
    fun reconcileStaleRecovery(): Boolean = executor.tryMutationLease {
        val envelope = (store.locked { read() } as? StoreRead.Loaded)?.envelope
            ?: return@tryMutationLease false
        if (envelope.transaction != null) return@tryMutationLease false
        val stable = envelope.stableSession ?: return@tryMutationLease false
        if (stable.state != StableState.RECOVERY_PENDING) return@tryMutationLease false
        val current = observation.read()
        if (current !is ObservationValue.Known) return@tryMutationLease false
        val state = current.value
        if (state.coarseState != ObservedCoarseState.IDLE_CLEAN ||
            state.occupants.isNotEmpty() || state.target != null || state.protectedResidue != null
        ) return@tryMutationLease false
        store.locked { bumpEpoch { it.copy(stableSession = null, stopRequested = false) } }
        CastOperationLog.record(
            "cleared stale RECOVERY_PENDING: cluster observed empty, nothing to recover, safe to re-bootstrap",
        )
        true
    } ?: false

    /**
     * Hands the physical cluster back to the driver: returns every app observed on the cluster display
     * to display 0, then tells the OEM to close its projection so the native gauges repaint.
     *
     * WHY THIS EXISTS AND WHY NOTHING ELSE COULD DO IT. On the night of 2026-07-31 the owner wiped app
     * data five times because no product path could recover a wedged cast. [recover] is not that path:
     * `CastExecutor.compensationFor` (CastExecutor.kt:393-399) maps only START_FRESH_NORMAL,
     * RESUME_PROTECTED and the two geometry kinds, while the ordinary placement ladder
     * (CastPlanner.kt:228-263 — PRE_OPEN_ON_MAIN, PLACE_KEEP_SESSION, MOVE_STACK_TO_CLUSTER,
     * REASSERT_ON_CLUSTER, FIT_CLUSTER_COMPOSITE, SET_FORCE_RESIZABLE, DISABLE_TRANSITION_ANIMATION,
     * BLOCK_PIP) declares ZERO compensable steps, so `CastRecovery.decide` answers
     * `Manual(COMPENSATION_EXHAUSTED)` for every stuck CAST. [reconcileAbandoned] is not that path
     * either: it refuses unless the cluster already reads IDLE_CLEAN with no occupants, and the whole
     * problem is that apps ARE stuck on it.
     *
     * THE OEM PROJECTION IS NOT WINDOWMANAGER — measured directly on DiLink3 on that same night.
     * Moving tasks off the cluster does NOT restore the gauges: the cluster kept showing a frozen stale
     * frame until `service call AutoContainer 2 i32 1000 i32 18` and then `... i32 0` were sent by hand
     * (CommandKind.SEAL_DL3_COMPENSATE_18 / _0, CastSealCommands.kt:19-20). Both halves are MEASURED,
     * not inferred. So this operation is only finished when those two opcodes have gone out; the
     * WindowManager half alone leaves a driver with no instruments. Same fact, same night, drove the
     * `CastColdBootstrap` change that always dispatches the activation ladder now — a virtual display
     * existing never meant the projection was routed to the physical cluster.
     *
     * BLAST RADIUS (CLAUDE.md §4 — all four answers, in order):
     *  1. WHICH DISPLAY: exactly the one named cluster display that `discoverClusterDisplay` finds in
     *     THIS dump, and only when its id is >= 1. No id, or an id below 1, refuses outright and
     *     dispatches nothing. Display 0 is never enumerated, never targeted, never reset.
     *  2. WHICH APP: every package the same dump shows holding a task on that display id, and nothing
     *     else — read from `am stack list` through [CastAmStackParser], never from a package list in
     *     code (CLAUDE.md §7). One rung per distinct package, in dump order.
     *  3. WHICH STACK TYPE: none is selected or reparented. `RETURN_NORMAL_TO_MAIN` is
     *     `am start --display 0 --windowingMode 1 -n <component>` (CastPlacementCommands.kt:173-177) —
     *     it asks the app's own task to come forward on the centre screen. Nothing is force-stopped
     *     (no FORCE_STOP_NORMAL, no --activity-clear-task), nothing is moved with `am display
     *     move-stack`, and no home/recents/pinned stack is touched. The launcher-wedge of 2026-07-21
     *     is out of reach by construction.
     *
     *     One deliberate coarseness: every occupant gets the NORMAL return, including a projection sink,
     *     where the ordinary Stop ladder would pick `RETURN_PROTECTED_GENTLY` to avoid touching the
     *     sink's windowing mode (CastPlanner.kt:80-82). Telling them apart needs target classification,
     *     which lives above `:core` and is not available here — and the gentle verb would leave an
     *     ordinary app floating in freeform on the centre screen, the V1 lesson `--windowingMode 1`
     *     exists for (CastPlacementCommands.kt:157-159). Worst case for a sink is that its component
     *     does not resolve, the rung is recorded as that package's failure, and the ladder still reaches
     *     the close-projection rung — gauges are the safety outcome, a stuck sink is not.
     *  4. HOW TO UNDO: every rung is individually undone by casting again — the app the driver wants is
     *     re-placed by the ordinary ladder, and the projection is re-opened by the bootstrap opcodes
     *     that now always run. This operation writes nothing display-global of its own, so it leaves no
     *     residue of its own to restore. The one destructive act is durable: the stable session is
     *     dropped (see below), and the baseline it carried is written to [CastOperationLog] first so it
     *     is still readable on the vehicle after the fact.
     *
     *     KNOWN GAP, stated rather than implied (CLAUDE.md §2/§5, review 2026-08-01 Pass 1). This
     *     operation does NOT hand back residue left by the WEDGED cast it is rescuing. The one that
     *     matters is the PICTURE_IN_PICTURE app-op: `BLOCK_PIP` writes `ignore` when the cheap placement
     *     did not land (`CastPlacementCommands.kt:115-119` — it no-ops once the target has landed, so
     *     this is exactly the wedge case), and the only rung that ever writes it back is Stop's
     *     `restore-pip` (CastPlanner.kt:100). A driver who rescues instead of stopping keeps that block,
     *     and dropping the stable session below also drops the baseline that recorded the mode. Measured
     *     consequence, on this vehicle, this exact residue: vn.vietmap.live's own speed bubble stayed
     *     silently dead until `appops` was set back by hand (see [CastPipBaseline]). Closing it properly
     *     needs a per-occupant restore rung with evidence for WHICH occupant we actually blocked — spec
     *     task B4/T9, deliberately deferred, not an oversight. Until then the honest reading of a
     *     successful clear is "the gauges are back", not "the vehicle is as it was".
     *
     * WHAT THE DURABLE STATE BECOMES, AND WHY. `stableSession = null`, `transaction = null`,
     * `pendingIntent = null`, `adjustmentDraft = null`. That exact set is `CastColdBootstrapPreflight`'s
     * pristine list (CastColdBootstrap.kt:91-94), and reaching it is the POINT, not a side effect:
     *  - Leaving an `IDLE_VERIFIED` session behind would be the worst of the options. The next cast must
     *    re-run the AutoContainer ladder, because this operation just closed the projection; a session
     *    that still claimed the cluster was ready would send an app to draw into a buffer nobody is
     *    showing, which is the exact bug that cost the whole night. `CastManualIntentRunner.run` reaches
     *    the ladder for a sealless claim too (`isUnprovenClusterClaim`), so this is belt AND braces —
     *    but the belt is here, where the projection is actually closed.
     *  - Leaving any OTHER state behind (RECOVERY_PENDING, MANUAL_REQUIRED) is a different dead end:
     *    `CastPlanner` refuses CAST unless the durable state is `IDLE_VERIFIED` (CastPlanner.kt:14-18),
     *    and nothing would ever promote it there again.
     *  - `pendingIntent` must go with it, or `resumePendingIntent` finds a queued placement it can never
     *    run ("Pending target has no verified stable session", CastManualIntent.kt:201-203) while that
     *    same field blocks bootstrap forever. A queued placement whose session no longer exists is not
     *    a fact worth keeping.
     *  - `adjustmentDraft` names a task on the display we just emptied. It blocks pristine, its target
     *    is gone, and its contents are a local size preference, never a vehicle mutation — so it is
     *    dropped, with its rect written to the operation log so the driver can retype it.
     * Everything not in that list is left alone on purpose: `pendingUiRollback`, `effectiveUiVersion`,
     * the automation config and its request/outcome rows are rollout and automation bookkeeping this
     * operation neither caused nor understands.
     *
     * `stopRequested` IS cleared here — and that is the deliberate OPPOSITE of what [reconcileAbandoned]
     * does, reached by the same reasoning rather than a different one. That reconciler keeps a pending
     * Stop precisely because it proves nothing about the OEM projection and never dispatches
     * SEAL_DL3_COMPENSATE_18/0, so a driver's Stop must survive to run the real teardown later. This
     * operation dispatches those two opcodes itself; when they report an effect, the Stop the driver
     * asked for has been carried out, and holding the flag would fence every future intent
     * (`plan`, `runManualIntent`, preflight) with no way left to clear it, since `CastPlanner` refuses a
     * STOP with no journaled baseline geometry (CastPlanner.kt:37-39). When the close does NOT report an
     * effect the flag is left exactly as it was: the Stop is still owed, and this operation is itself
     * re-runnable, which is the escape.
     *
     * SHAPE OF THE LADDER. One transaction per rung, each closed as soon as its command's effect is
     * known, rather than one transaction for the whole thing. Two mechanical facts force this and both
     * are worth knowing before anyone tries to "simplify" it:
     *  - `CastExecutor.executeLocked` resolves the package for RETURN_NORMAL_TO_MAIN from
     *    `transaction.sourcePkg` (CastExecutor.kt:205-208), and `sourcePkg` is fixed at creation from
     *    `stableSession.activeTarget` (CastExecutor.kt:167). One transaction can therefore return exactly
     *    one package. That is also why each return rung first writes a `RECOVERY_PENDING` clearing claim
     *    naming that occupant: it is not decoration, it is the only channel that carries the package name
     *    into the dispatch — and it is honest, because the occupant really is on the cluster and this
     *    really is a recovery. Its task id is the real one from the dump, never invented.
     *  - `completeVerificationLocked`'s STOP terminal demands full baseline convergence (geometry,
     *    animations, app-op, profile — CastCoordinator.kt:373-377), which cannot be true until the last
     *    rung has run. Per-rung VERIFYING would therefore mark every rung "diverged" and re-wedge the
     *    journal this operation exists to unwedge. The single verification that matters is taken once at
     *    the end, from a fresh observation, and reported honestly in [ClusterClearResult.Ran.cleared].
     *
     * A rung that fails does NOT abort the ladder. If returning one app fails, the remaining apps are
     * still returned and — above all — the projection is still closed, because gauges are the safety
     * outcome and a stuck third-party app is not.
     *
     * Safe to call with no transaction at all, and with no occupants at all: a cluster frozen on a stale
     * frame with nothing on the display is exactly the state the close opcodes exist for. This is I/O
     * over adb and it sleeps before its final reading, so it must never be called on the main thread.
     */
    fun clearCluster(facts: CastVehicleFacts): ClusterClearResult = executor.tryMutationLease<ClusterClearResult> {
        val raw = when (val inspected = observation.inspectRaw()) {
            is ObservationValue.Known -> inspected.value
            is ObservationValue.Unknown, is ObservationValue.Unsupported -> {
                // 2026-08-01: observation transport thường fail trên xe (ADB key issues, timeout).
                // Fallback: đọc ĐÚNG MỘT lệnh `am stack list` qua gateway thay vì 6 lệnh observation.
                // Display cụm trên DiLink3 LUÔN là display 1 (đã đo nhiều phiên). Nếu cả fallback cũng
                // fail → Blocked, nhưng ít nhất đã thử đường nhẹ nhất.
                val amResult = observation.readSingleCommand(CommandKind.AM_STACK_LIST)
                if (amResult is ObservationValue.Known) {
                    RawObservation(
                        amStacks = amResult.value,
                        wmDisplays = "",
                        displays = "",
                        profile = "0",
                        animations = "1\n1\n1",
                        appOps = "No app ops",
                    )
                } else {
                    return@tryMutationLease ClusterClearResult.Blocked(
                        "cluster observation unavailable and am-stack-list fallback also failed",
                    )
                }
            }
        }
        // When observation failed and we used the am-stack-list fallback, discoverClusterDisplayId
        // won't work (displays is empty). Use hardcoded display-1 for DiLink3.
        val clusterDisplayId = discoverClusterDisplayId(raw.displays)
            ?: if (raw.displays.isBlank()) 1 // DiLink3 fallback: cluster is always display 1
            else return@tryMutationLease ClusterClearResult.Blocked(MISSING_NAMED_CLUSTER_DISPLAY_REASON)
        if (clusterDisplayId < 1) {
            return@tryMutationLease ClusterClearResult.Blocked(
                "refusing to clear display $clusterDisplayId: only a cluster display with id >= 1 may be cleared",
            )
        }
        val stacks = when (val parsed = CastAmStackParser.parse(raw.amStacks)) {
            is CastDumpParse.Known -> parsed.value
            is CastDumpParse.Malformed ->
                return@tryMutationLease ClusterClearResult.Blocked("am stack topology is not parseable: ${parsed.reason}")
        }
        // Answer 2: occupants come from this dump, filtered to this display id. One rung per distinct
        // package (the shell verb is per-package); the first task record of that package supplies a REAL
        // task id for the clearing claim instead of an invented one.
        val occupants = stacks.tasks.filter { it.displayId == clusterDisplayId }.distinctBy { it.packageName }
        val displayIdentity = "display-$clusterDisplayId"
        val baseline = carriedBaseline()
        CastOperationLog.record(
            "clear-cluster on $displayIdentity: ${occupants.size} occupant(s) " +
                occupants.joinToString(", ") { "${it.packageName}#${it.taskId}" },
        )
        closeWedgedJournal()

        val returned = ArrayList<String>(occupants.size)
        val failures = LinkedHashMap<String, String>()
        occupants.forEach { task ->
            val failure = dispatchClearRung(
                steps = listOf(
                    PlannedStep(
                        "clear-return-${task.packageName}",
                        CommandKind.RETURN_NORMAL_TO_MAIN,
                        "occupant observed on $displayIdentity in the same dump this ladder was built from",
                    ),
                ),
                displayIdentity = displayIdentity,
                baseline = baseline,
                targetPackage = task.packageName,
                occupant = CastTarget(task.packageName, task.taskId, clusterDisplayId),
                postcondition = "${task.packageName} is back on display 0",
            )
            if (failure == null) returned += task.packageName else failures[task.packageName] = failure
        }

        // Vehicle differences live in the catalog, never in this file (CLAUDE.md §7). A generation with
        // no field-verified close sequence says so with its own reason and receives no opcode at all.
        val profile = CastVehicleProfileCatalog.resolve(facts)
        val closeKinds = profile.compensationKinds
        val projectionFailure = if (!profile.dispatchImplemented || closeKinds.isEmpty()) {
            profile.blockedReason ?: "this vehicle profile declares no close-projection sequence"
        } else {
            // Both opcodes in ONE ledger so their order can never drift: 18 (close) then 0 (repaint),
            // the order measured on the vehicle and the same order the STOP ladder already dispatches
            // them in (CastPlanner.kt:119-128). Back-to-back with no settle, exactly like that ladder.
            dispatchClearRung(
                steps = closeKinds.mapIndexed { index, kind ->
                    PlannedStep(
                        "clear-close-projection-$index",
                        kind,
                        "cluster occupants have already been handed their return command",
                    )
                },
                displayIdentity = displayIdentity,
                baseline = baseline,
                targetPackage = null,
                occupant = null,
                postcondition = "the OEM projection is closed and the native gauges repaint",
            )
        }

        commitCleared(projectionClosed = projectionFailure == null, baseline = baseline)
        // The vehicle needs time to repaint before its answer means anything; the same budget the manual
        // intent path uses to decide when an observation is worth believing (tests set it to zero).
        settle(manualVerificationDelayMillis)
        val remaining = (observation.read() as? ObservationValue.Known)?.value?.occupants
        ClusterClearResult.Ran(
            clusterDisplayId, returned, failures,
            projectionClosed = projectionFailure == null,
            projectionFailure = projectionFailure,
            remainingOccupants = remaining,
        ).also {
            CastOperationLog.record(
                "clear-cluster finished: returned=${it.returned} failures=${it.failures} " +
                    "projectionClosed=${it.projectionClosed} remaining=${it.remainingOccupants} cleared=${it.cleared}",
            )
        }
    } ?: ClusterClearResult.Blocked("a mutation is already in flight; the cluster cannot be cleared underneath it")

    /** The most recent journaled baseline, so a rung that ever needs one is never handed an empty guess. */
    private fun carriedBaseline(): CastBaseline = store.locked {
        val envelope = (read() as? StoreRead.Loaded)?.envelope ?: return@locked CastBaseline()
        envelope.stableSession?.baseline ?: envelope.transaction?.baseline ?: CastBaseline()
    }

    /**
     * Ends the wedged journal before the ladder starts, because `CastExecutor.executeLocked` refuses to
     * create a transaction while one exists (CastExecutor.kt:148). Unlike [reconcileAbandoned] this asks
     * the vehicle for no permission: the driver asked for the cluster back, and the rungs that follow are
     * what makes the closure true. The epoch is bumped so a late gateway reply from the abandoned
     * operation can never be mistaken for one of ours.
     */
    private fun closeWedgedJournal() {
        val closed = store.locked {
            val envelope = (read() as? StoreRead.Loaded)?.envelope ?: return@locked null
            val transaction = envelope.transaction ?: return@locked null
            bumpEpoch { it.copy(transaction = null) }
            transaction
        } ?: return
        forgetOperation(closed.operationId)
        CastOperationLog.record(
            "clear-cluster closed the wedged ${closed.operation} (${closed.operationId}) at " +
                "phase=${closed.phase}, lastFailure=${closed.lastFailure}",
        )
    }

    /**
     * One rung: write the claim that carries the package into dispatch, run it, close its own journal.
     *
     * Returns null when the gateway reported an effect for every step, otherwise the reason. It never
     * throws on a refusal — the caller must be free to keep going and reach the close-projection rung.
     */
    private fun dispatchClearRung(
        steps: List<PlannedStep>,
        displayIdentity: String,
        baseline: CastBaseline,
        targetPackage: String?,
        occupant: CastTarget?,
        postcondition: String,
    ): String? {
        val epoch = store.locked {
            if (read() !is StoreRead.Loaded) return@locked null
            update { envelope ->
                envelope.copy(
                    transaction = null,
                    stableSession = occupant?.let { clearingClaim(displayIdentity, baseline, it) },
                )
            }.durableEpoch
        } ?: return "durable store unavailable"
        val plan = CastPlan(
            CastOperation.STOP, epoch, steps, postcondition, emptySet(),
            expectedDisplayIdentity = displayIdentity,
        )
        // STOP is the only operation `ordinaryStableStateAllows` accepts from any durable state
        // (CastExecutor.kt:413) and the only one a pending Stop does not fence (CastExecutor.kt:150) —
        // which is exactly right, since every rung here IS teardown.
        val failure = when (val result = executor.execute(plan, baseline, targetPackage)) {
            is ExecutionResult.AwaitingVerification -> null
            is ExecutionResult.RecoveryRequired -> result.reason
            is ExecutionResult.Blocked -> result.reason
        }
        store.locked {
            val transaction = (read() as? StoreRead.Loaded)?.envelope?.transaction ?: return@locked
            forgetOperation(transaction.operationId)
            bumpEpoch { it.copy(transaction = null) }
            Unit
        }
        return failure
    }

    /**
     * The transient durable claim a return rung needs, and nothing more.
     *
     * RECOVERY_PENDING naming a real occupant is the truthful reading of the world at that instant, and
     * it is also the safest thing to die holding: should the process be killed mid-ladder, the state
     * left behind is the one whose only honest exit is running [clearCluster] again. [CLEAR_CLUSTER_BUILD]
     * makes that visible in `session.env` on the vehicle without a debugger (CLAUDE.md §15).
     *
     * `RECOVERY_PENDING` is also what keeps this transient claim out of
     * `CastManualIntentRunner.retireUnprovenClusterClaim`: that gate carries no seal either
     * (`profileExport = null`), but it only ever retires an `IDLE_VERIFIED` claim with no occupant on
     * record, precisely so a claim that still names an app on the cluster can never be thrown away
     * (CLAUDE.md §5 — the occupant record is the only thing that knows what has to come back).
     */
    private fun clearingClaim(
        displayIdentity: String,
        baseline: CastBaseline,
        occupant: CastTarget,
    ): StableCastSession = StableCastSession(
        StableState.RECOVERY_PENDING, EngineVersion.V2, CLEAR_CLUSTER_BUILD, null,
        displayIdentity, baseline, occupant, null, null, now().toEpochMilli(),
    )

    /** The terminal write. See [clearCluster]'s KDoc for why each field becomes what it becomes. */
    private fun commitCleared(projectionClosed: Boolean, baseline: CastBaseline) {
        store.locked {
            val envelope = (read() as? StoreRead.Loaded)?.envelope ?: return@locked
            // Say out loud what is being dropped: after this write these values exist nowhere else, and
            // the operation log is readable on the vehicle when the app can no longer show them. Two
            // records, not one, because CastOperationLog truncates a single entry at 400 characters and
            // the baseline is the half a human would have to retype by hand.
            CastOperationLog.record(
                "clear-cluster dropping baseline: anim=${baseline.animationPerKey} " +
                    "pip=${baseline.pipMode} geometry=${baseline.geometry}",
            )
            CastOperationLog.record(
                "clear-cluster dropping pendingIntent=${envelope.pendingPackage} " +
                    "adjustmentDraft=${envelope.adjustmentDraft?.localDraft}; " +
                    "stopRequested ${envelope.stopRequested} → ${if (projectionClosed) false else envelope.stopRequested}",
            )
            bumpEpoch { current ->
                current.copy(
                    transaction = null,
                    stableSession = null,
                    pendingIntent = null,
                    adjustmentDraft = null,
                    stopRequested = if (projectionClosed) false else current.stopRequested,
                )
            }
            Unit
        }
    }

    /** A settle that can fail loudly but never derails the ladder — nothing is mutated by waiting. */
    private fun settle(millis: Long) {
        if (millis <= 0) return
        try {
            manualSleeper.sleep(millis)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            CastOperationLog.record("clear-cluster settle interrupted; the final reading may be early")
        } catch (failure: RuntimeException) {
            CastOperationLog.record("clear-cluster settle failed: ${failure.message.orEmpty()}")
        }
    }

    fun observe(): ObservationValue<ObservedState> = observation.read()

    fun observe(deadlineAtEpochMillis: Long): ObservationValue<ObservedState> =
        observation.read(deadlineAtEpochMillis)

    /** Raw dumpsys/am text this boundary already fetches for every observation, before parsing. */
    fun inspectRaw(deadlineAtEpochMillis: Long = Long.MAX_VALUE): ObservationValue<RawObservation> =
        observation.inspectRaw(deadlineAtEpochMillis)

    fun bootstrap(
        facts: CastVehicleFacts,
        style: ClusterStyle = ClusterStyle.CURVED,
    ): ColdBootstrapResult = executor.bootstrap(
        facts,
        { deadline -> observation.inspectRaw(deadline) },
        { deadline -> observation.read(deadline) },
        style,
    )

    fun runManualIntent(
        packageName: String,
        facts: CastVehicleFacts,
        targets: CastManualTargetReader,
        origin: CastIntentOrigin = CastIntentOrigin.USER,
        automationRequestId: java.util.UUID? = null,
        allowDestructive: Boolean = false,
        preferredDensityDpi: Int? = null,
        clusterStyle: ClusterStyle = ClusterStyle.CURVED,
        /**
         * Bố cục ô cho lượt đặt này, hoặc `null` = chiếu toàn cụm y như hôm nay (mặc định, và là đường
         * mà mọi caller hiện có đang đi — không caller nào phải sửa một chữ).
         */
        slotLayout: ClusterSlotLayout? = null,
    ): CastManualIntentResult {
        require((origin == CastIntentOrigin.BOOT_AUTO) == (automationRequestId != null)) {
            "automation request id exists exactly for BOOT_AUTO origin"
        }
        if (origin == CastIntentOrigin.BOOT_AUTO) {
            val envelope = store.locked { (read() as? StoreRead.Loaded)?.envelope }
                ?: return CastManualIntentResult.Blocked("durable store unavailable")
            if (envelope.stopRequested) {
                return CastManualIntentResult.Blocked("Stop requested; automation is fenced")
            }
            val request = envelope.bootAutomationRequest
            if (request == null || request.requestId != automationRequestId ||
                request.state != AutomationRequestState.CLAIMED
            ) {
                return CastManualIntentResult.Blocked("automation request is not claimed")
            }
            if (request.targetPackage != packageName) {
                return CastManualIntentResult.Blocked("automation target no longer matches the claim")
            }
            val pending = envelope.pendingIntent
            if (pending != null && !pending.matches(automationRequestId)) {
                return CastManualIntentResult.Blocked("pending placement belongs to another origin")
            }
            // Review 2026-07-30 (vòng 3, docs/specs/cast-simplified-active-app-toggle.html). Boot
            // automation never takes the cluster away from a placement that is ALREADY on it.
            //
            // Every other precedence rule in this block compares automation against *durable
            // configuration* identity, and `CastAutomationPolicy.claimAllowed` does the same
            // (revision/consent/default equality). None of that moves when the driver casts something
            // by hand, so nothing here refused a claim whose cluster was already occupied — and
            // `CastManualIntentRunner.executeOrdinary` reads `stable.activeTarget != null` as
            // `CastIntentKind.SWITCH`, i.e. it would replace what the driver just chose with the
            // configured default. That is reachable inside ONE boot: `CastAutomationService`
            // .deferForPriorJournal re-arms itself REVALIDATION_DELAY_MS (45 s) later when a prior
            // journal was still open at first evaluation, and 45 s is more than enough for a bubble
            // tap while driving.
            //
            // R6 already states the rule for the interactive auto-start ("không cướp phiên", enforced
            // by `autoStartTarget`); this is the same rule for R7. This read is a cheap early exit —
            // the binding one runs inside the mutation lease, via the `automation` flag below, because
            // everything read here can still move before the runner acquires that lease.
            if (envelope.stableSession?.activeTarget != null) {
                return CastManualIntentResult.Blocked(LIVE_SESSION_REFUSAL)
            }
        }
        return manualIntentRunner().run(
            packageName, facts, targets, allowDestructive, preferredDensityDpi, clusterStyle,
            automation = origin == CastIntentOrigin.BOOT_AUTO,
            slotLayout = slotLayout,
        )
    }

    fun resumePendingIntent(targets: CastManualTargetReader): CastManualIntentResult? =
        manualIntentRunner().resumePending(targets)

    private fun manualIntentRunner() = CastManualIntentRunner(
        store,
        observation,
        executor,
        ::completeVerification,
        ::resetVerificationSample,
        ::queueLatestTarget,
        manualSleeper,
        manualVerificationDelayMillis,
        { now().toEpochMilli() },
        ::rememberIntendedLayout,
    )

    fun recover(): ExecutionResult = recovery.compensate()

    fun project(input: CastProjectionInput): CastUiStateV2 = CastUiStateProjector.project(input)

    fun completeVerification(operationId: java.util.UUID, observed: ObservedState): Boolean =
        executor.withMutationLease { completeVerificationLocked(operationId, observed) }

    private fun resetVerificationSample(operationId: java.util.UUID) =
        executor.withMutationLease { resetVerificationSampleLocked(operationId) }

    /**
     * Bỏ mẫu quan sát đã ghi, KHÔNG bỏ bố cục ô.
     *
     * Cố ý khác [forgetOperation]: đường này còn được gọi cho những hỏng hóc TẠM (quan sát trả `Unknown`,
     * lần ngủ chờ bị ngắt — CastManualIntent.kt), lúc đó transaction vẫn đang VERIFYING và một lần thử lại
     * hoàn toàn có thể thành công. Xoá bố cục ở đây là tự tay biến một trục trặc tạm thành "không còn biết
     * đã hỏi gì" ⇒ lần thử lại chắc chắn trả `false`. Bố cục chỉ được bỏ khi transaction THẬT SỰ kết thúc.
     */
    private fun resetVerificationSampleLocked(operationId: java.util.UUID) {
        verificationSamples.remove(operationId)
    }

    /** Transaction đã kết thúc (xong, hoặc chuyển sang RECOVERING): bỏ cả mẫu quan sát lẫn bố cục ô. */
    private fun forgetOperation(operationId: java.util.UUID) {
        verificationSamples.remove(operationId)
        intendedLayouts.remove(operationId)
    }

    /**
     * Quan sát có ĐÚNG bằng bố cục đã hỏi hay không. Chỉ trả `true` khi mọi vế dưới đây cùng đúng.
     *
     * Mỗi vế khoá một cách hỏng cụ thể, không vế nào là trang trí:
     *
     *  1. **Đúng tập app, không thừa một ai.** So bằng `==` trên tập hợp, không phải `containsAll`: một
     *     app THỨ BA đậu trên cụm nghĩa là hoặc nó đang bị che (đo thật: rect chồng nhau thì che hẳn),
     *     hoặc nó đang chiếm chỗ của một ô. Cả hai đều KHÔNG phải thứ người lái yêu cầu ⇒ không xác nhận.
     *  2. **Đúng dạng thô.** Một app thì phải là ACTIVE_SINGLE, hai app thì phải là ACTIVE_MULTI. Vế này
     *     dôi ra so với (1) khi `ObservedState` được dựng bằng tay, và nó rẻ; giữ lại để không bao giờ có
     *     một quan sát tự mâu thuẫn lọt qua.
     *  3. **Đúng số khung, và đúng từng DẢI NGANG.** `taskBounds` chỉ chứa task ĐANG ở cụm và CÓ đọc được
     *     `bounds=` (KDoc `ObservedState.taskBounds`). Đòi bằng cả SỐ LƯỢNG lẫn TẬP HỢP nên bắt được cả
     *     ca "một gói mở hai task trên cụm" lẫn ca "một dòng task không in bounds" — cả hai đều là chưa
     *     biết chắc, và chưa biết chắc thì không xác nhận (CLAUDE.md §2).
     *  4. **Trục DỌC: các ô phải NHẤT QUÁN với nhau, không phải bằng một giá trị dự đoán.** Xem khối ★ bên
     *     dưới — đây là vế đổi nghĩa nhiều nhất trong cả hàm.
     *  5. **Khung ĐÃ HỎI phải đúng là ô của app vừa đặt.** Nối lại hai đầu: rect đi ra xe
     *     (`tx.requestedGeometry`, trường bền) và ô trong bố cục (RAM) phải là một. Lệch nhau nghĩa là
     *     bố cục trong tay không thuộc về transaction này.
     *  6. **Nếu quan sát CÓ chốt được một target thì dải của chính task đó phải đúng ô của nó.** Đây là
     *     phép kiểm theo GÓI duy nhất khả thi hôm nay và nó chỉ làm chặt thêm, không nới ra.
     *
     * ★ VÌ SAO SO DẢI NGANG CHỨ KHÔNG SO CẢ RECT (2026-08-01). Bản trước so `taskBounds.values.toSet() ==
     * layout.rects`, tức khẳng định cả trục dọc. Khẳng định ấy chỉ đúng được khi rect yêu cầu vốn được cắt
     * ra từ một dải VẼ đo được — và điều đó lại đòi cụm phải sẵn có một app, tức đúng cái vòng khoá làm
     * cho lượt đặt app ĐẦU TIÊN vào một nửa không bao giờ chạy nổi (xem [ClusterSplit.slotBand]).
     *
     * Trục dọc không phải thứ app này chọn: hệ thống phát cho, và nó chịu ảnh hưởng của overscan cấp
     * display — một giá trị KHÔNG ổn định giữa các lần dump (`overscan (0,90,0,90)` đo 31/7 so với
     * `overscan (536,224,88,336)` trong docs/diagnostics/carlog-2026-07-21/05-display.txt:121). So một
     * đại lượng như thế với một con số ta tự sinh ra là tự chuốc "diverged" mỗi lần nó đổi.
     *
     * Thay vào đó, hai vế dưới đây khẳng định đúng những gì app này thật sự kiểm soát và đúng những gì
     * quyết định kết cục sản phẩm:
     *  - **Ngang, tuyệt đối.** ĐÃ ĐO là ánh xạ đồng nhất (0 / 960 / 1920 gõ vào, đọc lại đúng từng số —
     *    xem [ClusterSpan]). Đây cũng là vế giữ nguyên độ chặt ở ca nguy hiểm nhất: một app TOÀN CỤM nằm
     *    cạnh một app nửa cụm có dải `[0..1920)`, không khớp ô `[0..960)` ⇒ VẪN TRƯỢT, đúng như đo thật
     *    (nó che hẳn app kia). Khoá bởi `a fullscreen app beside a half-width one never verifies`.
     *  - **Dọc, chỉ nhất quán.** Mọi ô của bố cục này đều cao hết cụm, nên điều duy nhất còn ý nghĩa là
     *    chúng nhận CÙNG một dải dọc — hai app cùng cao thì không thể chồng nhau khi dải ngang đã rời
     *    nhau, dù hệ thống chọn dải dọc nào. Dải suy biến (cao ≤ 0) vẫn bị từ chối.
     *
     * GIỚI HẠN CÒN LẠI, nói thẳng (CLAUDE.md §2): phép kiểm này KHÔNG chứng minh được hệ thống đã cho một
     * chiều cao HỢP LÝ — nếu nó phát cho cả hai app một dải dọc cao 1px thì hai vế trên vẫn đúng. Không
     * đóng lỗ đó bằng một ngưỡng tự nghĩ ra, vì ngưỡng ấy sẽ lại là một con số chưa đo (đúng thứ vừa gỡ
     * bỏ). Chỗ chốt là phép đo trên xe: `docs/diagnostics/oncar-checklist-2026-08-01.md §9`.
     *
     * ★ GIỚI HẠN ĐÃ BIẾT, nói thẳng (CLAUDE.md §2). Khi quan sát KHÔNG chốt được target (hai app cùng
     * `visible=true` — chính là ca đo thật), phép kiểm này chứng minh được "đúng hai app này, và đúng hai
     * khung này", nhưng KHÔNG chứng minh được "app A đúng là app đang ở ô TRÁI". Lý do rất cơ học:
     * `ObservedState` không mang bản đồ `taskId → gói` (`occupants` là tập tên gói, `taskBounds` khoá theo
     * `taskId`), mà `DumpObservedStateParser`/`CastAmStackParser` nằm ngoài phạm vi sửa của đợt này. Hậu
     * quả xấu nhất nếu hai app đổi chỗ cho nhau: cụm hiện đủ hai app, đúng kích thước, sai bên. Đó là lỗi
     * NHÌN THẤY ĐƯỢC và tự sửa bằng một lượt xếp lại — không phải lỗi im lặng, không phải lỗi mất cụm.
     * Đường đóng lại đúng cách là cho `CastAmTaskRecord` mang luôn `bounds` rồi đưa `taskId → gói` vào
     * `ObservedState` (đúng món nợ có chủ ý đã ghi trong KDoc `CastAmTaskBoundsParser`).
     */
    private fun layoutMatchesObservation(
        layout: IntendedClusterLayout,
        observed: ObservedState,
        tx: CastTransaction,
    ): Boolean {
        if (observed.occupants != layout.packages) return false
        val expectedCoarse = if (layout.packages.size == 1) {
            ObservedCoarseState.ACTIVE_SINGLE
        } else ObservedCoarseState.ACTIVE_MULTI
        if (observed.coarseState != expectedCoarse) return false
        val bounds = observed.taskBounds.values.toList()
        if (bounds.size != layout.occupantRects.size) return false
        // Ngang: đúng từng dải, không thừa không thiếu. Một khung suy biến (rộng ≤ 0) không có dải nào ⇒
        // `horizontalSpan` trả null ⇒ thoát ngay, không xác nhận một thứ đọc không ra.
        val observedSpans = LinkedHashSet<ClusterSpan>(bounds.size)
        bounds.forEach { observedSpans += (it.horizontalSpan ?: return false) }
        if (observedSpans.size != bounds.size || observedSpans != layout.spans) return false
        // Dọc: chỉ đòi các ô nhất quán với nhau, và dải ấy không suy biến.
        val top = bounds.first().top
        val bottom = bounds.first().bottom
        if (bottom <= top) return false
        if (bounds.any { it.top != top || it.bottom != bottom }) return false
        val placed = tx.targetPkg ?: return false
        if (tx.requestedGeometry?.bounds?.horizontalSpan != layout.spanOf(placed)) return false
        val resolvedTarget = observed.target ?: return true
        return resolvedTarget.packageName in layout.packages &&
            observed.targetTaskBounds?.horizontalSpan == layout.spanOf(resolvedTarget.packageName)
    }

    private fun completeVerificationLocked(operationId: java.util.UUID, observed: ObservedState): Boolean = store.locked {
        val loaded = read() as? StoreRead.Loaded ?: return@locked false
        val tx = loaded.envelope.transaction ?: return@locked false
        if (tx.operationId != operationId || tx.phase != OperationPhase.VERIFYING) return@locked false
        if (loaded.envelope.durableEpoch != tx.epoch ||
            (loaded.envelope.stopRequested && tx.operation !in setOf(CastOperation.STOP, CastOperation.RECOVER))
        ) {
            forgetOperation(operationId)
            update { envelope -> envelope.copy(transaction = tx.copyForRecovery("verification fenced by Stop or epoch change")) }
            return@locked false
        }
        if (now().toEpochMilli() >= tx.deadlineAtEpochMillis) {
            forgetOperation(operationId)
            update { envelope -> envelope.copy(transaction = tx.copyForRecovery("verification deadline exceeded")) }
            return@locked false
        }
        val targetMatches = tx.targetPkg == null || observed.target?.packageName == tx.targetPkg
        val displayMatches = tx.expectedDisplayIdentity != "unresolved" &&
            observed.displayIdentity == tx.expectedDisplayIdentity
        val geometryKnown = observed.geometry != null
        val terminal = when (tx.operation) {
            CastOperation.BOOTSTRAP -> CastColdBootstrapVerification.accepts(observed)
            CastOperation.STOP, CastOperation.RECOVER ->
                observed.coarseState == ObservedCoarseState.IDLE_CLEAN && displayMatches &&
                    observed.geometry == tx.baseline.geometry &&
                    observed.animationPerKey == tx.baseline.animationPerKey &&
                    observed.pipMode == tx.baseline.pipMode && observed.profile == tx.baseline.profile
            CastOperation.APPLY_GEOMETRY ->
                targetMatches && displayMatches && geometryKnown && tx.requestedGeometry != null &&
                    observed.geometry == tx.requestedGeometry &&
                    observed.coarseState == ObservedCoarseState.ACTIVE_SINGLE
            // ── CAST / SWITCH ───────────────────────────────────────────────────────────────────────
            //
            // Hai nhánh, và cửa rẽ là một dữ kiện BỀN chứ không phải một cờ trong RAM: `requestedGeometry`
            // của chính transaction này có mang dấu [ClusterSplit.SLOT_PROFILE_ID] hay không. Lượt chiếu
            // toàn cụm (mọi lượt đang chạy ngoài hiện trường) không bao giờ mang dấu đó, nên nó rơi vào
            // nhánh dưới — nguyên văn từng ký tự như trước bản vá.
            else -> if (tx.requestedGeometry?.profileId != ClusterSplit.SLOT_PROFILE_ID) {
                // Đường cũ, không đổi một chữ: một app trên cụm, hoặc nhiều app nhưng phần dôi ra là một
                // phiên được bảo vệ (CarPlay/AA còn sót lại) — ca ACTIVE_MULTI "ngoài ý muốn".
                targetMatches && displayMatches && geometryKnown &&
                    (observed.coarseState == ObservedCoarseState.ACTIVE_SINGLE ||
                        (observed.coarseState == ObservedCoarseState.ACTIVE_MULTI && observed.protectedResidue != null))
            } else {
                // ACTIVE_MULTI CÓ CHỦ Ý. `layout == null` nghĩa là đã hỏi một bố cục nhưng không còn biết
                // bố cục ấy là gì (tiến trình chết giữa chừng — xem KDoc [intendedLayouts]): không đoán,
                // không hạ chuẩn xuống phép kiểm một-app, trả thẳng `false`.
                //
                // `targetMatches` ở đây cố ý KHÔNG được dùng: đo thật cho thấy hai app cùng `visible=true`
                // thì `DumpObservedStateParser` trả `target = null` một cách trung thực (CastDeviceParsers.kt,
                // khoá bởi CastMultiOccupantObservationTest) — đòi `observed.target?.packageName ==
                // tx.targetPkg` là đòi đúng thứ mà chính quan sát nói rằng nó không biết. Thay vào đó
                // [layoutMatchesObservation] kiểm một mệnh đề CHẶT HƠN: đúng tập app đó, đúng từng khung,
                // không thừa một ai.
                val layout = intendedLayouts[operationId]
                layout != null && displayMatches && geometryKnown &&
                    layoutMatchesObservation(layout, observed, tx)
            }
        }
        if (!terminal) {
            forgetOperation(operationId)
            update { envelope -> envelope.copy(transaction = tx.copyForRecovery("verification diverged")) }
            return@locked false
        }
        val previous = verificationSamples.put(operationId, observed)
        if (previous != observed) return@locked false
        forgetOperation(operationId)
        val stableState = if (observed.protectedResidue == null) StableState.ACTIVE_VERIFIED else StableState.ACTIVE_DEGRADED
        val stable = when (tx.operation) {
            CastOperation.BOOTSTRAP -> StableCastSession(
                StableState.IDLE_VERIFIED, EngineVersion.V2, "runtime-bootstrap", "seal-dl3-cold-bootstrap-v1",
                checkNotNull(observed.displayIdentity),
                CastBaseline(emptySet(), observed.geometry, observed.animationPerKey, observed.pipMode, observed.profile),
                null, null, observed.geometry, now().toEpochMilli(),
            )
            CastOperation.STOP, CastOperation.RECOVER -> StableCastSession(
                StableState.IDLE_VERIFIED, EngineVersion.V2, "runtime", null,
                tx.expectedDisplayIdentity, tx.baseline, null, null, observed.geometry, now().toEpochMilli(),
            )
            CastOperation.APPLY_GEOMETRY -> {
                val prior = loaded.envelope.stableSession ?: return@locked false
                StableCastSession(
                    stableState, prior.engineVersion, prior.createdByBuild, prior.profileExport,
                    tx.expectedDisplayIdentity, prior.baseline, observed.target, observed.protectedResidue,
                    prior.acceptedGeometry, now().toEpochMilli(),
                )
            }
            else -> StableCastSession(
                stableState, EngineVersion.V2, "runtime", null,
                tx.expectedDisplayIdentity, tx.baseline, observed.target, observed.protectedResidue,
                observed.geometry, now().toEpochMilli(),
            )
        }
        update { envelope ->
            envelope.copy(
                stableSession = stable,
                transaction = null,
                stopRequested = if (tx.operation in setOf(CastOperation.STOP, CastOperation.RECOVER)) false else envelope.stopRequested,
            )
        }
        true
    }

    private fun CastTransaction.copyForRecovery(reason: String) = CastTransaction(
        operationId, epoch, operation, OperationPhase.RECOVERING, sourcePkg, targetPkg, targetClass,
        expectedDisplayIdentity, baseline, ledger, retries, deadlineAtEpochMillis, reason,
        expectedPostcondition, compensationUsed, requestedGeometry,
    )

    companion object {
        /** Cùng một câu cho cả cửa sớm ở đây lẫn cửa ràng buộc trong lease — không viết hai bản chữ. */
        internal const val LIVE_SESSION_REFUSAL =
            "cluster already holds an active target; automation never supersedes a live session"
    }
}

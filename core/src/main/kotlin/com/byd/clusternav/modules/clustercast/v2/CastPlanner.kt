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
        if (intent.kind == CastIntentKind.STOP && snapshot.stableSession?.baseline?.geometry == null &&
            snapshot.stableSession?.state != StableState.RECOVERY_PENDING
        ) {
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

        // ── Ô cụm (chia đôi) ────────────────────────────────────────────────────────────────────────
        // Toàn bộ khối này KHÔNG chạy khi `intent.slotLayout == null`, tức mọi lượt chiếu đang có ngoài
        // hiện trường đi qua đây mà không chạm một dòng nào — điều kiện bắt buộc của đợt thay đổi này.
        //
        // Đặt Ở ĐÂY, sau mọi cổng cũ và trước khi dựng thang, vì hai lẽ: (1) một yêu cầu chia ô phải bị
        // các cổng cũ (trạng thái bền, display, installed/launcher, policy) từ chối y hệt một lượt chiếu
        // thường — không được có đường tắt nào chỉ vì có ô; (2) rect của ô phải tính từ CHÍNH `observed`
        // mà kế hoạch đang dựng trên đó, không phải từ một lần đọc khác.
        val slotLayout = intent.slotLayout
        var intendedLayout: IntendedClusterLayout? = null
        var slotGeometry: AcceptedGeometry? = null
        if (slotLayout != null) {
            if (intent.kind != CastIntentKind.CAST && intent.kind != CastIntentKind.SWITCH) {
                return PlanResult.Blocked("A cluster slot only applies to a placement, not to ${intent.kind}")
            }
            val targetPackage = intent.targetPackage
                ?: return PlanResult.Blocked("Target package is required")
            if (targetPackage !in slotLayout.packages) {
                return PlanResult.Blocked("The placed app must own one of the requested slots")
            }
            // Dải để cắt ô — xem KDoc `ClusterSplit.slotBand` để biết hai nguồn của nó và vì sao thứ tự
            // ấy không được đảo. Cụm ĐANG CÓ TASK thì dải phải ĐO ĐƯỢC từ khung task thật (con số 90 của
            // bug F3 chỉ đọc được ở đó); cụm đã CHỨNG MINH là rỗng thì dùng khung display — chính không
            // gian toạ độ mà `FIT_CLUSTER_COMPOSITE` vẫn gửi đi ở đường toàn cụm đã field-proven.
            val band = ClusterSplit.slotBand(observed)
                ?: return PlanResult.Blocked(
                    "Cluster slot band is unreadable: the cluster is neither proven empty nor laid out in " +
                        "measurable slots",
                )
            val resolved = slotLayout.resolve(band)
                ?: return PlanResult.Blocked("Cluster $band is too small to split into the requested slots")

            // Lượt này đặt ĐÚNG MỘT app: `CastMutationRequest` mang đúng một `targetPackage`
            // (CastExecutor.kt:205-208), nên không có bước nào trong thang đây dựng ra có thể resize app
            // KIA. Vậy nửa còn lại phải ĐÃ nằm đúng ô của nó rồi — kiểm bằng quan sát, không bằng niềm
            // tin. Không đạt thì từ chối SỚM, thay vì phát lệnh rồi để phép xác minh nghiêm ngặt kết luận
            // "diverged" và ghim transaction vào RECOVERING (CLAUDE.md §5: không tạo ngõ cụt).
            //
            // Hệ quả sản phẩm, nói thẳng: bố cục hai app dựng bằng HAI lượt — đặt app đầu vào ô của nó
            // trước, rồi mới đưa app thứ hai vào ô còn lại. Mỗi lượt tự xác minh được, và không lượt nào
            // phải chạm vào app của lượt kia.
            //
            // So theo DẢI NGANG, không theo cả rect (sửa 2026-08-01 cùng lượt mở khoá app đầu tiên). Lý do
            // là hệ quả trực tiếp của [ClusterSplit.slotBand]: khi app kia được đặt lúc cụm còn rỗng, rect
            // của nó mang trục dọc mà HỆ THỐNG phát cho, không phải trục dọc app này xin. Đòi bằng cả rect
            // ở đây là đòi đúng thứ chưa đo được, và cái giá rất cụ thể: app đầu nằm ngay ngắn ở nửa trái
            // mà lượt đưa app thứ hai vào nửa phải bị từ chối vĩnh viễn — người lái không có đường nào đi
            // tiếp. Dải ngang thì ĐÃ ĐO là đồng nhất (xem [ClusterSpan]), nên nó vừa so được, vừa đúng là
            // điều duy nhất cần đúng để hai app không che nhau.
            resolved.occupantSpans.forEach { (packageName, span) ->
                if (packageName == targetPackage) return@forEach
                if (packageName !in observed.occupants) {
                    return PlanResult.Blocked("$packageName must already be on the cluster to keep its slot")
                }
                if (observed.taskBounds.values.none { it.horizontalSpan == span }) {
                    return PlanResult.Blocked("$packageName does not hold slot $span yet; place it into its slot first")
                }
            }
            // Một app lạ trên cụm KHÔNG được lặng lẽ nằm dưới một bố cục không có tên nó: hoặc nó bị che
            // (đã đo: rect chồng nhau thì che hẳn), hoặc nó chiếm chỗ của một ô. Cả hai đều là "cụm không
            // như người lái yêu cầu" ⇒ từ chối, để đường Dừng/Dọn cụm xử lý.
            val strangers = observed.occupants - resolved.packages
            if (strangers.isNotEmpty()) {
                return PlanResult.Blocked("Unexpected app(s) $strangers on the cluster; clear it before splitting")
            }
            intendedLayout = resolved
            // Rect của ô đi ra xe bằng CHÍNH trường mà thang fit đã đọc hôm nay (`request.geometry`), với
            // `profileId` là dấu "đây là một Ô" — xem `ClusterSplit.SLOT_PROFILE_ID` để biết vì sao không
            // thêm trường mới. Mật độ vẫn giữ nguyên đường cũ, không bị nuốt mất.
            slotGeometry = AcceptedGeometry(
                checkNotNull(resolved.rectOf(targetPackage)),
                intent.geometry?.densityDpi,
                ClusterSplit.SLOT_PROFILE_ID,
            )
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
                // ── Z-ORDER, và vì sao ca "app đã ở trên cụm" chỉ được RESIZE ──────────────────────
                //
                // ĐÃ ĐO (DiLink3, 31/07): thứ tự vẽ đi theo lần `am start` CUỐI CÙNG, KHÔNG theo lần
                // resize cuối. Hai hệ quả, và cả hai đều được mã hoá tường minh ở đây thay vì phó mặc:
                //
                //  1. App vừa được người lái chọn phải là app `am start` SAU CÙNG. Đúng như thang cũ vẫn
                //     làm: chỉ app đích mới có rung `am start` (PLACE_KEEP_SESSION/REASSERT_ON_CLUSTER),
                //     app kia không có rung nào. Nhờ vậy, nếu bước fit có hỏng thì thứ nằm TRÊN là app
                //     người lái vừa yêu cầu — hỏng kiểu nhìn thấy được, không phải hỏng kiểu app biến mất
                //     dưới một app khác mà không ai biết.
                //  2. Xếp một app ĐANG chiếu vào ô của nó thì tuyệt đối KHÔNG được `am start` lại: làm thế
                //     là kéo nó lên trên cùng, và trong khoảnh khắc trước khi rect kịp đổi (nó vẫn đang
                //     toàn cụm) nó che hẳn app kia — đúng ca "rect chồng nhau thì không ghép" đã đo. Đường
                //     đúng là resize thuần, và resize ĐÃ ĐO là không đổi thứ tự vẽ.
                //
                // Hôm nay ca `sameTarget` lập ra thang RỖNG (không rung nào), nên thêm ĐÚNG MỘT rung fit ở
                // đây là cộng thêm, không đảo thứ tự gì của đường đã kiểm thực địa (CLAUDE.md §6). Nó chỉ
                // xuất hiện khi có yêu cầu ô — không có ô thì `sameTarget` vẫn lập thang rỗng y như trước.
                if (sameTarget && slotLayout != null) {
                    add(PlannedStep(
                        "fit-cluster-slot",
                        CommandKind.FIT_CLUSTER_COMPOSITE,
                        "target already on the cluster: resize into its slot without changing z-order",
                    ))
                }
                val outgoing = observed.target?.packageName
                // App đang chiếu chỉ là "app đi ra" khi bố cục mới KHÔNG có tên nó. Trong một lượt xếp ô,
                // nó thường lại chính là app giữ nửa kia — mà `RETURN_PROTECTED_GENTLY` là
                // `am start --display 0` (CastPlacementCommands.kt), tức ĐUỔI nó khỏi cụm. Chạy rung đó ở
                // đây là tự tay phá đúng bố cục vừa yêu cầu: người lái xin hai app, nhận về một.
                //
                // Điều kiện chỉ nới ra khi có ô VÀ app ấy có tên trong bố cục; không có ô thì biểu thức
                // này đúng từng ký tự như trước bản vá.
                val outgoingKeepsASlot =
                    outgoing != null && slotLayout != null && outgoing in slotLayout.packages
                if (intent.kind == CastIntentKind.SWITCH && outgoing != null &&
                    outgoing != intent.targetPackage && !outgoingKeepsASlot
                ) {
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
                    TargetClass.PROJECTION_SINK, TargetClass.KEEP_SESSION -> {
                        add(PlannedStep("return-protected", CommandKind.RETURN_PROTECTED_GENTLY, "protected session must be preserved"))
                        add(PlannedStep("return-task-fallback", CommandKind.RETURN_TASK_TO_MAIN,
                            "no-op when gentle return worked; fallback for not-exported"))
                        add(PlannedStep("reset-cluster-density", CommandKind.RESET_CLUSTER_DENSITY,
                            "restore display density after unresizeable app leaves"))
                    }
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
            CastIntentKind.RECOVER -> buildList {
                add(PlannedStep(
                    "recover-disconnected-sink-once",
                    CommandKind.DISCONNECTED_SINK_RECOVERY_ONCE,
                    "owner, disconnected phone and two stable observations are proven",
                ))
                // Đo trên xe 2026-07-31: sau vài transaction cast bị kẹt với vn.vietmap.live, lệnh
                // `appops get vn.vietmap.live PICTURE_IN_PICTURE` vẫn trả `deny`. Tính năng bong bóng
                // tốc độ của CHÍNH VietMap vì thế chết lặng — không báo lỗi, không dấu vết trên UI —
                // chủ xe chỉ nhận ra vì bong bóng không bao giờ hiện lại. Đặt tay về `allow` là hết.
                //
                // Đường trả app-op duy nhất tới hôm nay là bước `restore-pip` của nhánh STOP ở trên.
                // Mà STOP lại bị từ chối thẳng khi chưa journal được baseline geometry (dòng 37-39
                // file này), nên nếu tiến trình chết giữa một lần cast đã leo tới rung BLOCK_PIP thì
                // KHÔNG còn đường nào gỡ block nữa — đúng nghĩa ngõ cụt mà CLAUDE.md §5 cấm: thứ gì
                // đổi ra ngoài hệ thống phải có đường trả lại chạy được cả khi tiến trình đã chết.
                //
                // RECOVER là đường phục hồi duy nhất KHÔNG phụ thuộc baseline, nên bước trả app-op
                // được gắn vào đây, dưới hai ràng buộc tự đặt:
                //
                //   1. Không đảo thứ tự thang đã kiểm thực địa. Rung quyết định (force-stop đúng
                //      owner) vẫn là bước ĐẦU TIÊN, giữ nguyên guard cũ; bước mới xuống CUỐI
                //      (CLAUDE.md §6 — đường mới luôn xuống cuối). Nhờ vậy nếu `appops set` có hỏng
                //      thì hành động chữa cháy thật sự đã phát đi và đã journal OBSERVED trước đó.
                //   2. Không ghi app-op của một app mà mình không chứng minh được là đã bị chặn.
                //      Chỉ thêm bước khi có bằng chứng, không thêm mù:
                //        · quan sát hiện tại cho thấy chính owner đang ở mode chặn (`ignore` đúng là
                //          thứ BLOCK_PIP ghi ra, `deny` là thứ đo được trên xe), hoặc
                //        · baseline đã journal sẵn một mode KHÔNG chặn — lúc đó RESTORE_PIP trả về
                //          đúng giá trị đã ghi chứ không đoán.
                //      Không có bằng chứng nào thì thang RECOVER giữ nguyên đúng một bước như cũ.
                //
                // `observed.pipMode` chỉ được đọc cho TARGET đang quan sát (`val pipMode =
                // target?.packageName?.let` trong DumpObservedStateParser, CastDeviceParsers.kt:185).
                // Khi owner là residue chứ không phải target thì trường đó là app-op của app KHÁC, dùng
                // làm bằng chứng cho owner là sai — đó là lý do có `ownerIsObservedTarget`.
                val ownerIsObservedTarget = observed.target?.packageName == intent.targetPackage
                val ownerBlockedNow = ownerIsObservedTarget && CastPipBaseline.isBlocked(observed.pipMode)
                val journaledMode = snapshot.stableSession?.baseline?.pipMode
                // Chốt chặn cuối: RESTORE_PIP ghi ra ĐÚNG mode trong baseline, chỉ rơi về "allow" khi
                // baseline trống (CastPlacementCommands.kt:210-212). Vậy nếu envelope cũ — ghi từ trước
                // bản vá CastPipBaseline — đang giữ một mode chặn thì bước "phục hồi" này sẽ ghi lại
                // chính cái chặn đó. Trường hợp đó thà không phát lệnh còn hơn tự tay chặn lần nữa;
                // đường sửa đúng nằm ở tầng transport (`restorePipMode` cũng phải từ chối mode chặn),
                // ngoài phạm vi thay đổi này nên ghi lại đây thay vì đoán.
                val restoreWouldRewriteTheBlock = CastPipBaseline.isBlocked(journaledMode)
                if ((ownerBlockedNow || journaledMode != null) && !restoreWouldRewriteTheBlock) {
                    add(PlannedStep(
                        "restore-pip",
                        CommandKind.RESTORE_PIP,
                        "owner app-op is observed blocked or a non-blocked baseline mode is journaled",
                    ))
                }
            }
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
                } else slotGeometry ?: intent.geometry,
                intendedLayout = intendedLayout,
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

            // ── Phase 0b: move-task escalation for not-exported targets (CarPlay/AA) ─────────────────
            // These are no-ops when the target already landed via RESUME_PROTECTED above. They only
            // fire as fallback when `am start` throws SecurityException (not exported from uid 1000).
            // Field-proven 2026-08-01 T1: `am stack move-task` bypasses ActivityStarter entirely.
            if (protected) {
                add(PlannedStep(
                    "move-task-to-cluster",
                    CommandKind.MOVE_TASK_TO_CLUSTER_STACK,
                    "no-op when target already on cluster; fallback for not-exported activities",
                ))
                add(PlannedStep(
                    "scale-cluster-density",
                    CommandKind.SCALE_CLUSTER_DENSITY,
                    "no-op when target already on cluster; scales unresizeable app to fit",
                ))
            }

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

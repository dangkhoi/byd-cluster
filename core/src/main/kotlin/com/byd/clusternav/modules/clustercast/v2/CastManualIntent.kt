package com.byd.clusternav.modules.clustercast.v2

import java.util.UUID

private val MANUAL_ANDROID_PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

data class CastManualTargetSnapshot(
    val evidence: TargetEvidence,
    val installed: Boolean,
    val hasLauncher: Boolean,
)

fun interface CastManualTargetReader {
    fun read(packageName: String): CastManualTargetSnapshot
}

sealed interface CastManualIntentResult {
    data class Succeeded(val stableSession: StableCastSession) : CastManualIntentResult
    data class Queued(val packageName: String) : CastManualIntentResult
    data class VerificationPending(val operationId: UUID, val reason: String) : CastManualIntentResult
    data class RecoveryRequired(val operationId: UUID, val reason: String) : CastManualIntentResult
    data class Blocked(val reason: String) : CastManualIntentResult
}

sealed interface CastManualTargetEligibility {
    data class Ready(val targetClass: TargetClass) : CastManualTargetEligibility
    data class Blocked(val reason: String) : CastManualTargetEligibility
}

fun CastManualTargetSnapshot.eligibilityFor(
    current: CastSessionEnvelope,
): CastManualTargetEligibility {
    if (!installed) return CastManualTargetEligibility.Blocked("Target is not installed")
    if (!hasLauncher) return CastManualTargetEligibility.Blocked("Target has no launcher")
    val targetClass = CastPolicy.classify(evidence)
    if (targetClass == TargetClass.UNKNOWN_PROTECTED) {
        return CastManualTargetEligibility.Blocked("Target policy is unknown")
    }
    val rollout = CastRolloutRegistry.resolve(current, CastRolloutRegistry.vehicleTestCandidate)
    if (rollout.effectiveUiVersion != EngineVersion.V2 || targetClass !in rollout.enabledSlices) {
        return CastManualTargetEligibility.Blocked("V2 slice is disabled for $targetClass")
    }
    return CastManualTargetEligibility.Ready(targetClass)
}

/** High-level composition only: no shell/opcode knowledge and no mutation owner beyond CastExecutor. */
internal class CastManualIntentRunner(
    private val store: CastSessionStore,
    private val observation: ObservedStateReader,
    private val executor: CastExecutor,
    private val completeVerification: (UUID, ObservedState) -> Boolean,
    private val resetVerificationSample: (UUID) -> Unit,
    private val queueLatestTarget: (String) -> Boolean,
    private val sleeper: CastSleeper,
    private val verificationDelayMillis: Long,
) {
    init { require(verificationDelayMillis >= 0) }

    /**
     * [automation] = lượt chạy này là tự-chiếu-lúc-khởi-động (BOOT_AUTO), không phải người bấm.
     *
     * Hai luật chỉ áp cho tự động hoá, và cả hai PHẢI nằm ở đây chứ không chỉ ở tầng gọi (review vòng 3,
     * 2026-07-30, docs/specs/cast-simplified-active-app-toggle.html):
     *
     * 1. **Không cướp phiên đang sống.** Cửa sớm ở `CastCoordinator.runManualIntent` đọc envelope TRƯỚC
     *    khi vào lease, nên mọi thứ nó thấy còn kịp đổi: một cú chạm nút nổi bắt đầu chiếu ngay sau đó
     *    sẽ giữ lease vài giây, và khi tự động hoá giành được lease thì `activeTarget` đã có — lúc ấy
     *    `executeOrdinary` lập kế hoạch `SWITCH` và đổi cụm khỏi thứ người lái vừa chọn. Cửa ràng buộc
     *    phải đọc CÙNG một `current` mà kế hoạch được dựng từ đó, tức bên trong lease. Đây đúng là điều
     *    CLAUDE.md §5 đòi: "Guard cứng đặt ở tầng thi hành".
     * 2. **Không xếp hàng.** Tự động hoá có ngân sách MỘT claim cho mỗi lần khởi động; xếp hàng là khái
     *    niệm của người dùng. `queueLatestTarget` ghi `pendingIntent` gốc **USER** (mặc định của
     *    `CastModels.withPendingPackage`) — nên một lượt tự động hoá bị xếp hàng sẽ để lại một hàng đợi
     *    gốc USER bền mà `CastAutomationSettings.terminalize` KHÔNG dọn (nó chỉ dọn hàng đợi khớp
     *    `requestId`), và `initializeForBoot` giữ nó qua mọi lần khởi động ⇒ tự động hoá tự tay tắt
     *    chính mình cho mọi lần nổ máy sau. Từ chối thẳng, kết cục hữu hạn, không để lại dấu vết bền.
     */
    fun run(
        packageName: String,
        facts: CastVehicleFacts,
        targets: CastManualTargetReader,
        allowDestructive: Boolean = false,
        preferredDensityDpi: Int? = null,
        clusterStyle: ClusterStyle = ClusterStyle.CURVED,
        automation: Boolean = false,
    ): CastManualIntentResult {
        if (!MANUAL_ANDROID_PACKAGE.matches(packageName)) {
            return CastManualIntentResult.Blocked("Target package name is invalid")
        }
        val before = envelope() ?: return CastManualIntentResult.Blocked("Durable store unavailable")
        if (before.transaction != null) {
            if (automation) return CastManualIntentResult.Blocked(AUTOMATION_NEVER_QUEUES)
            val target = checkTarget(packageName, before, targets)
            if (target is CastManualTargetEligibility.Blocked) return CastManualIntentResult.Blocked(target.reason)
            return if (queueLatestTarget(packageName)) CastManualIntentResult.Queued(packageName)
            else CastManualIntentResult.Blocked("Target could not be queued in the current state")
        }
        return executor.withMutationLease {
            val current = envelope() ?: return@withMutationLease blockedStore()
            if (current.stopRequested) {
                return@withMutationLease CastManualIntentResult.Blocked("Stop requested; manual intent is fenced")
            }
            if (current.transaction != null) {
                if (automation) return@withMutationLease CastManualIntentResult.Blocked(AUTOMATION_NEVER_QUEUES)
                return@withMutationLease if (queueLatestTarget(packageName)) {
                    CastManualIntentResult.Queued(packageName)
                } else CastManualIntentResult.Blocked("Operation already active")
            }
            val target = checkTarget(packageName, current, targets)
            if (target is CastManualTargetEligibility.Blocked) return@withMutationLease CastManualIntentResult.Blocked(target.reason)
            if (current.stableSession == null) {
                when (val bootstrap = executor.bootstrapForManualIntent(
                    facts,
                    packageName,
                    { deadline -> observation.inspectRaw(deadline) },
                    { deadline -> observation.read(deadline) },
                    clusterStyle,
                )) {
                    is ColdBootstrapResult.Blocked -> CastManualIntentResult.Blocked(bootstrap.reason)
                    is ColdBootstrapResult.RecoveryRequired -> CastManualIntentResult.RecoveryRequired(
                        bootstrap.operationId,
                        bootstrap.reason,
                    )
                    is ColdBootstrapResult.Succeeded -> continuePending(targets, automation)
                }
            } else {
                executeOrdinary(
                    packageName,
                    (target as CastManualTargetEligibility.Ready).targetClass,
                    consumePending = false,
                    allowDestructive = allowDestructive,
                    preferredDensityDpi = preferredDensityDpi,
                    automation = automation,
                )
            }
        }
    }

    fun resumePending(targets: CastManualTargetReader): CastManualIntentResult? = executor.withMutationLease {
        val current = envelope() ?: return@withMutationLease blockedStore()
        if (current.pendingIntent == null) return@withMutationLease null
        if (current.stopRequested) return@withMutationLease CastManualIntentResult.Blocked("Stop requested; pending target is fenced")
        if (current.transaction != null) return@withMutationLease CastManualIntentResult.Blocked("Operation already active")
        if (current.stableSession == null) {
            return@withMutationLease CastManualIntentResult.Blocked("Pending target has no verified stable session")
        }
        continuePending(targets, automation = false)
    }

    private fun continuePending(
        targets: CastManualTargetReader,
        automation: Boolean,
    ): CastManualIntentResult {
        repeat(MAX_PENDING_REPLANS) {
            val current = envelope() ?: return blockedStore()
            if (current.stopRequested) return CastManualIntentResult.Blocked("Stop requested; pending target is fenced")
            if (current.transaction != null) return CastManualIntentResult.Blocked("Operation already active")
            if (current.stableSession == null) {
                return CastManualIntentResult.Blocked("Pending target has no verified stable session")
            }
            val packageName = current.pendingPackage ?: return CastManualIntentResult.Blocked("Pending target disappeared")
            when (val target = checkTarget(packageName, current, targets)) {
                is CastManualTargetEligibility.Blocked -> {
                    if (discardPending(packageName)) {
                        return CastManualIntentResult.Blocked("Pending target is no longer eligible: ${target.reason}")
                    }
                }
                is CastManualTargetEligibility.Ready -> {
                    val result = executeOrdinary(
                        packageName, target.targetClass, consumePending = true, automation = automation,
                    )
                    if (!pendingChangedAfterBlocked(packageName, result)) return result
                }
            }
        }
        return CastManualIntentResult.Blocked("Pending target changed repeatedly; latest selection remains durable")
    }

    private fun executeOrdinary(
        packageName: String,
        targetClass: TargetClass,
        consumePending: Boolean,
        allowDestructive: Boolean = false,
        preferredDensityDpi: Int? = null,
        automation: Boolean = false,
    ): CastManualIntentResult {
        val current = envelope() ?: return blockedStore()
        val stable = current.stableSession ?: return CastManualIntentResult.Blocked("Verified stable session is required")
        if (current.stopRequested || current.transaction != null) {
            return CastManualIntentResult.Blocked("Operation is fenced or already active")
        }
        // Cửa ràng buộc của luật 1 (xem KDoc của [run]): đọc trên CHÍNH `current` mà kế hoạch dưới đây
        // dựng từ đó, và đang giữ mutation lease — nên không còn khe nào để một cú chạm chen vào giữa
        // "thấy cụm rảnh" và "phát lệnh SWITCH".
        if (automation && stable.activeTarget != null) {
            return CastManualIntentResult.Blocked(CastCoordinator.LIVE_SESSION_REFUSAL)
        }
        val kind = if (stable.activeTarget == null) CastIntentKind.CAST else CastIntentKind.SWITCH
        CastOperationLog.record(
            "$kind $packageName · class=$targetClass · destructive=$allowDestructive · epoch=${current.durableEpoch}",
        )
        val plan = CastPlanner.plan(
            CastIntent(
                kind,
                packageName,
                geometry = preferredDensityDpi?.let {
                    AcceptedGeometry(CastRect(0, 0, 0, 0), it, "cluster-density-request")
                },
                allowDestructive = allowDestructive,
            ),
            PlannerSnapshot(
                observed = observation.read(),
                stableSession = stable,
                targetClass = targetClass,
                installed = true,
                hasLauncher = true,
                plannerEpoch = current.durableEpoch,
            ),
        )
        val ready = plan as? PlanResult.Ready
            ?: return CastManualIntentResult.Blocked((plan as PlanResult.Blocked).reason)
        val execution = if (consumePending) {
            executor.executePendingTarget(ready.plan, stable.baseline, packageName)
        } else executor.execute(ready.plan, stable.baseline, packageName)
        return when (execution) {
            is ExecutionResult.Blocked -> CastManualIntentResult.Blocked(execution.reason)
            is ExecutionResult.RecoveryRequired -> CastManualIntentResult.RecoveryRequired(
                execution.operationId,
                execution.reason,
            )
            is ExecutionResult.AwaitingVerification -> verify(execution.operationId)
        }
    }

    private fun verify(operationId: UUID): CastManualIntentResult {
        if (verificationDelayMillis > 0) {
            try {
                sleeper.sleep(verificationDelayMillis)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                resetVerificationSample(operationId)
                return CastManualIntentResult.VerificationPending(operationId, "Verification wait was interrupted")
            } catch (failure: RuntimeException) {
                resetVerificationSample(operationId)
                return CastManualIntentResult.VerificationPending(
                    operationId,
                    "Verification wait failed: ${failure.message.orEmpty()}",
                )
            }
        }
        repeat(2) { sampleIndex ->
            when (val sample = observation.read()) {
                is ObservationValue.Known -> if (completeVerification(operationId, sample.value)) {
                    val stable = envelope()?.stableSession
                    if (stable != null && stable.state in ACTIVE_STATES) {
                        return CastManualIntentResult.Succeeded(stable)
                    }
                    resetVerificationSample(operationId)
                    return CastManualIntentResult.VerificationPending(operationId, "Active stable state was not committed")
                }
                is ObservationValue.Unknown -> {
                    resetVerificationSample(operationId)
                    return verificationPending(operationId, sample.reason)
                }
                is ObservationValue.Unsupported -> {
                    resetVerificationSample(operationId)
                    return verificationPending(operationId, sample.reason)
                }
            }
            val transaction = envelope()?.transaction
            if (transaction?.phase == OperationPhase.RECOVERING) {
                resetVerificationSample(operationId)
                return CastManualIntentResult.RecoveryRequired(
                    operationId,
                    transaction.lastFailure ?: "Verification diverged at sample ${sampleIndex + 1}",
                )
            }
        }
        resetVerificationSample(operationId)
        return verificationPending(operationId, "Two equal active observations did not converge")
    }

    private fun verificationPending(operationId: UUID, reason: String) =
        CastManualIntentResult.VerificationPending(operationId, reason)

    private fun checkTarget(
        packageName: String,
        current: CastSessionEnvelope,
        targets: CastManualTargetReader,
    ): CastManualTargetEligibility {
        val snapshot = try {
            targets.read(packageName)
        } catch (failure: Exception) {
            return CastManualTargetEligibility.Blocked("Target validation failed: ${failure.message ?: failure.javaClass.simpleName}")
        }
        return snapshot.eligibilityFor(current)
    }

    private fun discardPending(packageName: String): Boolean = store.locked {
        val current = (read() as? StoreRead.Loaded)?.envelope ?: return@locked false
        if (current.pendingPackage != packageName || current.transaction != null || current.stopRequested) {
            return@locked false
        }
        update { it.copy(pendingIntent = null) }
        true
    }

    private fun pendingChangedAfterBlocked(
        packageName: String,
        result: CastManualIntentResult,
    ): Boolean {
        if (result !is CastManualIntentResult.Blocked) return false
        val current = envelope() ?: return false
        return current.transaction == null && !current.stopRequested &&
            current.pendingPackage != null && current.pendingPackage != packageName
    }

    private fun envelope(): CastSessionEnvelope? =
        store.locked { (read() as? StoreRead.Loaded)?.envelope }

    private fun blockedStore() = CastManualIntentResult.Blocked("Durable store unavailable")

    companion object {
        private const val MAX_PENDING_REPLANS = 4
        private val ACTIVE_STATES = setOf(StableState.ACTIVE_VERIFIED, StableState.ACTIVE_DEGRADED)
        internal const val AUTOMATION_NEVER_QUEUES =
            "Operation already active; boot automation never queues a durable placement"
    }
}

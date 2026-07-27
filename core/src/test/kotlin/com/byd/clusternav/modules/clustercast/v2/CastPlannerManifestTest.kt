package com.byd.clusternav.modules.clustercast.v2

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastPlannerManifestTest {
    @Test
    fun `canonical manifest has exactly cases 1 through 32 with complete evidence fields`() {
        val cases = CastCaseManifest.cases
        assertEquals(32, cases.size)
        assertEquals((1..32).toList(), cases.map { it.id })
        assertEquals(32, cases.map { it.name }.toSet().size)
        cases.forEach {
            assertTrue(it.name.isNotBlank())
            assertTrue(it.successTerminal.isNotBlank())
            assertTrue(it.failureTerminal.isNotBlank())
            assertTrue(it.retryPolicy.isNotBlank())
            assertTrue(it.evidenceGate.isNotBlank())
        }
        val normalized = cases.joinToString("\u001e") { row ->
            listOf(
                row.id.toString(), row.name, row.successTerminal, row.failureTerminal,
                row.retryPolicy, row.evidenceGate,
            ).joinToString("\u001f")
        }
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals("7010da5287f5da4ac6ea4370419236989842921c904f19914ac8ca9efd77696f", fingerprint)
    }

    @Test
    fun `command kinds form a closed disjoint allowlist and V2 exposes no move stack`() {
        assertTrue(CommandAllowlist.readOnly.intersect(CommandAllowlist.plannedMutation).isEmpty())
        assertEquals(CommandKind.entries.toSet(), CommandAllowlist.readOnly + CommandAllowlist.plannedMutation)
        // 0.72: reparent is the decisive rung, so exactly one move-stack kind exists and it only
        // ever targets the cluster display. Moving a stack back to display 0 remains forbidden.
        assertEquals(
            listOf(CommandKind.MOVE_STACK_TO_CLUSTER),
            CommandKind.entries.filter { it.name.contains("MOVE_STACK") },
        )
        (CommandAllowlist.readOnly - setOf(CommandKind.PACKAGE_RESOLVE, CommandKind.PHONE_SESSION_STATE)).forEach {
            assertTrue(ShellCommandEncoder.encode(it).isNotBlank())
        }
        assertThrows(IllegalStateException::class.java) { ShellCommandEncoder.encode(CommandKind.PACKAGE_RESOLVE) }
        assertThrows(IllegalStateException::class.java) { ShellCommandEncoder.encode(CommandKind.PHONE_SESSION_STATE) }
        assertThrows(IllegalArgumentException::class.java) {
            ReadOnlyShellRequest.observation(CommandKind.PACKAGE_RESOLVE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReadOnlyShellRequest.observation(CommandKind.PHONE_SESSION_STATE)
        }
        val resolution = ReadOnlyShellRequest.packageResolution("com.example.maps")
        assertEquals(CommandKind.PACKAGE_RESOLVE, resolution.kind)
        assertTrue(resolution.command.endsWith("com.example.maps"))
        assertEquals("am get-current-user", ShellCommandEncoder.encode(CommandKind.PROFILE_STATE))
        assertThrows(IllegalArgumentException::class.java) {
            ShellCommandEncoder.encodePackageResolution("com.example.maps;id")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReadOnlyShellRequest.packageResolution("com.example.maps;id")
        }
        val phone = ReadOnlyShellRequest.phoneSession("com.example.maps")
        assertEquals(CommandKind.PHONE_SESSION_STATE, phone.kind)
        assertTrue(phone.command.endsWith("com.example.maps"))
        listOf("com.example.maps;id", "../com.example.maps", "com example.maps", "-n").forEach {
            assertThrows(IllegalArgumentException::class.java) { ReadOnlyShellRequest.phoneSession(it) }
        }
        CommandAllowlist.plannedMutation.forEach {
            assertThrows(IllegalStateException::class.java) { ShellCommandEncoder.encode(it) }
            assertThrows(IllegalArgumentException::class.java) { ReadOnlyShellRequest.observation(it) }
        }
    }

    @Test
    fun `planner is deterministic and emits typed plan only`() {
        val snapshot = safeSnapshot(TargetClass.NORMAL)
        val first = CastPlanner.plan(CastIntent(CastIntentKind.CAST, "app.maps", "app.maps/.Main"), snapshot)
        val second = CastPlanner.plan(CastIntent(CastIntentKind.CAST, "app.maps", "app.maps/.Main"), snapshot)
        assertEquals(first, second)
        val plan = (first as PlanResult.Ready).plan
        assertEquals(ExpectedLadder.normal, plan.steps.map { it.commandKind })
        assertEquals(7, plan.epoch)
    }

    @Test
    fun `protected target uses resume only and unknown truth blocks before any plan`() {
        val protected = CastPlanner.plan(
            CastIntent(CastIntentKind.CAST, "app.carplay", "app.carplay/.Video"),
            safeSnapshot(TargetClass.PROJECTION_SINK),
        ) as PlanResult.Ready
        assertEquals(ExpectedLadder.protectedTarget, protected.plan.steps.map { it.commandKind })

        val unknown = safeSnapshot(TargetClass.NORMAL).copy(observed = ObservationValue.Unknown("AM timeout"))
        assertTrue(CastPlanner.plan(CastIntent(CastIntentKind.CAST, "app.maps"), unknown) is PlanResult.Blocked)
        val unclassified = safeSnapshot(TargetClass.UNKNOWN_PROTECTED)
        assertTrue(CastPlanner.plan(CastIntent(CastIntentKind.CAST, "app.unknown"), unclassified) is PlanResult.Blocked)
    }

    @Test
    fun `existing residue blocks second-residue switch before placement`() {
        val observed = knownState().copy(
            protectedResidue = ProtectedResidue("app.carplay", 9, ResidueVisibility.HIDDEN)
        )
        val snapshot = safeSnapshot(TargetClass.PROJECTION_SINK).copy(observed = ObservationValue.Known(observed))
        val result = CastPlanner.plan(CastIntent(CastIntentKind.SWITCH, "app.aa"), snapshot)
        assertTrue(result is PlanResult.Blocked)
    }

    @Test
    fun `policy fails closed when projection evidence is missing`() {
        assertEquals(TargetClass.UNKNOWN_PROTECTED, CastPolicy.classify(TargetEvidence(null, null, false)))
        assertEquals(TargetClass.PROJECTION_SINK, CastPolicy.classify(TargetEvidence(true, true, false)))
        assertEquals(TargetClass.KEEP_SESSION, CastPolicy.classify(TargetEvidence(false, null, true)))
        assertEquals(TargetClass.NORMAL, CastPolicy.classify(TargetEvidence(false, null, false)))
        assertFalse(CastPolicy.mayForceStop(TargetClass.PROJECTION_SINK))
    }

    @Test
    fun `Stop khong bao gio de app thanh mo coi, ke ca khi chua ro chinh sach target`() {
        // Đo trực tiếp trên xe 2026-07-27: gửi 18,0 mà không trả task về display 0 thì cụm về đồng hồ
        // NHƯNG app cũng không hiện ở màn giữa — chủ xe nói "đồng hồ, và vietmap cũng ko ở màn chính
        // luôn, đi đâu mất tiêu rồi". Nhánh UNKNOWN_PROTECTED trước đây không có bước trả về nào, nên
        // Stop tự tạo ra đúng trạng thái đó. Bài kiểm quét MỌI lớp target để không lớp nào lọt.
        val target = CastTarget("com.example.maps", 7, 2)
        val state = ObservedState(
            ObservedCoarseState.ACTIVE_SINGLE, "display-2", target,
            setOf(target.packageName), null,
            AcceptedGeometry(CastRect(0, 0, 1920, 720), 160, "android-user-0"),
        )
        val stable = StableCastSession(
            StableState.ACTIVE_VERIFIED, EngineVersion.V2, "test", null, "display-2",
            CastBaseline(geometry = state.geometry), target, null, state.geometry, 1,
        )
        val returns = setOf(CommandKind.RETURN_NORMAL_TO_MAIN, CommandKind.RETURN_PROTECTED_GENTLY)
        TargetClass.entries.forEach { targetClass ->
            val result = CastPlanner.plan(
                CastIntent(CastIntentKind.STOP, target.packageName),
                PlannerSnapshot(ObservationValue.Known(state), stable, targetClass, true, true, 4),
            )
            val ready = result as? PlanResult.Ready ?: return@forEach
            val kinds = ready.plan.steps.map { it.commandKind }
            val closes = CommandKind.SEAL_DL3_COMPENSATE_18 in kinds
            if (!closes) return@forEach
            assertTrue(
                kinds.any { it in returns },
                "$targetClass: Stop đóng chiếu mà không trả task về màn giữa → app mồ côi",
            )
            assertTrue(
                kinds.indexOfFirst { it in returns } < kinds.indexOf(CommandKind.SEAL_DL3_COMPENSATE_18),
                "$targetClass: phải trả task về TRƯỚC khi đóng chiếu",
            )
        }
    }

    @Test
    fun `Stop plans target return followed by independently guarded display reset`() {
        val target = CastTarget("com.example.maps", 7, 2)
        val state = ObservedState(
            ObservedCoarseState.ACTIVE_SINGLE, "display-2", target,
            setOf(target.packageName), null,
            AcceptedGeometry(CastRect(0, 0, 1920, 720), 160, "android-user-0"),
        )
        val stable = StableCastSession(
            StableState.ACTIVE_VERIFIED, EngineVersion.V2, "test", null, "display-2",
            CastBaseline(geometry = state.geometry), target, null, state.geometry, 1,
        )
        val ready = CastPlanner.plan(
            CastIntent(CastIntentKind.STOP, target.packageName),
            PlannerSnapshot(ObservationValue.Known(state), stable, TargetClass.NORMAL, true, true, 4),
        ) as PlanResult.Ready
        assertEquals(state.geometry, ready.plan.geometry)
        assertEquals(
            ExpectedLadder.stopNormal,
            ready.plan.steps.map { it.commandKind },
        )
    }

    @Test
    fun `disconnected sink recovery requires explicit two sample consequence proof`() {
        val state = ObservedState(
            ObservedCoarseState.ACTIVE_SINGLE, "display-x",
            CastTarget("com.example.carplay", 7, 2), setOf("com.example.carplay"), null, null,
        )
        val snapshot = PlannerSnapshot(
            ObservationValue.Known(state), null, TargetClass.PROJECTION_SINK,
            installed = true, hasLauncher = true, plannerEpoch = 7,
        )
        assertTrue(CastPlanner.plan(CastIntent(CastIntentKind.RECOVER, "com.example.carplay"), snapshot) is PlanResult.Blocked)
        val incomplete = DisconnectedSinkRecoveryProof(
            "com.example.carplay", state, state, phoneDisconnected = false,
            projectionComponent = true, consequenceConfirmed = true,
        )
        assertTrue(CastPlanner.plan(
            CastIntent(CastIntentKind.RECOVER, "com.example.carplay", recoveryProof = incomplete), snapshot,
        ) is PlanResult.Blocked)
        val proof = incomplete.copy(phoneDisconnected = true)
        val ready = CastPlanner.plan(
            CastIntent(CastIntentKind.RECOVER, "com.example.carplay", recoveryProof = proof), snapshot,
        ) as PlanResult.Ready
        assertEquals(listOf(CommandKind.DISCONNECTED_SINK_RECOVERY_ONCE), ready.plan.steps.map { it.commandKind })
    }

    @Test
    fun `observation reader issues only closed read-only command kinds and propagates unknown`() {
        val seen = mutableListOf<CommandKind>()
        val gateway = object : ShellGateway {
            override fun execute(request: ReadOnlyShellRequest): ShellResult {
                seen += request.kind
                return ShellResult.Success("known-${request.kind}", "", 1)
            }
            override fun close() = Unit
        }
        val reader = ObservedStateReader(gateway) { raw ->
            assertTrue(raw.amStacks.startsWith("known-"))
            ObservationValue.Known(knownState())
        }
        assertTrue(reader.read() is ObservationValue.Known)
        assertEquals(
            listOf(
                CommandKind.AM_STACK_LIST, CommandKind.WM_DISPLAYS, CommandKind.DISPLAY_STATE,
                CommandKind.PROFILE_STATE, CommandKind.ANIMATION_STATE, CommandKind.APP_OPS_STATE,
            ),
            seen,
        )
        assertTrue(seen.all { it in CommandAllowlist.readOnly })

        val empty = object : ShellGateway {
            override fun execute(request: ReadOnlyShellRequest) = ShellResult.Success("", "", 1)
            override fun close() = Unit
        }
        assertTrue(ObservedStateReader(empty) { ObservationValue.Known(knownState()) }.read() is ObservationValue.Unknown)
    }

    @Test
    fun `rollout is persisted session sticky independent from nav flag and applies rollback only at safe boundary`() {
        assertEquals(CastRolloutFlags(), CastRolloutRegistry.defaults)
        val active = StableCastSession(
            StableState.ACTIVE_VERIFIED, EngineVersion.V2, "build", null, "display",
            CastBaseline(), CastTarget("app", 1, 1), null, null, 1,
        )
        val pristine = CastSessionEnvelope(
            durableEpoch = 0, bootId = "boot", stopRequested = false, pendingIntent = null,
            effectiveUiVersion = EngineVersion.LEGACY, pendingUiRollback = false,
            stableSession = null, transaction = null,
        )
        val candidate = CastRolloutRegistry.resolve(pristine, CastRolloutRegistry.vehicleTestCandidate)
        assertEquals(EngineVersion.V2, candidate.effectiveUiVersion)
        assertEquals(null, candidate.actionOwner)
        assertEquals(
            setOf(TargetClass.NORMAL, TargetClass.KEEP_SESSION, TargetClass.PROJECTION_SINK),
            candidate.enabledSlices,
        )
        val activeEnvelope = CastSessionEnvelope(
            durableEpoch = 1, bootId = "boot", stopRequested = false, pendingIntent = null,
            effectiveUiVersion = EngineVersion.V2, pendingUiRollback = false,
            stableSession = active, transaction = null,
        )
        val castOnly = CastRolloutRegistry.resolve(
            activeEnvelope,
            CastRolloutFlags(castUiV2 = false, engineNormalV2 = true),
        )
        val navChanged = CastRolloutRegistry.resolve(
            activeEnvelope,
            CastRolloutFlags(navUiV2 = true, castUiV2 = false, engineNormalV2 = true),
        )
        assertEquals(EngineVersion.V2, castOnly.actionOwner)
        assertEquals(EngineVersion.V2, castOnly.effectiveUiVersion)
        assertTrue(castOnly.pendingUiRollback)
        assertEquals(castOnly.actionOwner, navChanged.actionOwner)
        assertEquals(castOnly.effectiveUiVersion, navChanged.effectiveUiVersion)
        assertEquals(castOnly.pendingUiRollback, navChanged.pendingUiRollback)
        assertEquals(castOnly.enabledSlices, navChanged.enabledSlices)
        val persistedDecision = CastRolloutRegistry.apply(
            activeEnvelope, CastRolloutFlags(castUiV2 = false, engineNormalV2 = true),
        )
        val roundTrip = CastEnvelopeCodec.decode(CastEnvelopeCodec.encode(persistedDecision)) as StoreRead.Loaded
        assertTrue(roundTrip.envelope.pendingUiRollback)
        assertEquals(EngineVersion.V2, roundTrip.envelope.effectiveUiVersion)
        assertThrows(UnsupportedOperationException::class.java) {
            (castOnly.enabledSlices as MutableSet).clear()
        }

        val safeEnvelope = activeEnvelope.copy(
            stableSession = active.copy(state = StableState.IDLE_VERIFIED, activeTarget = null),
            pendingUiRollback = true,
        )
        val safe = CastRolloutRegistry.resolve(safeEnvelope, CastRolloutFlags(castUiV2 = false))
        assertEquals(null, safe.actionOwner)
        assertEquals(EngineVersion.LEGACY, safe.effectiveUiVersion)
        assertFalse(safe.pendingUiRollback)
        val appliedSafe = CastRolloutRegistry.apply(safeEnvelope, CastRolloutFlags(castUiV2 = false))
        assertFalse(appliedSafe.pendingUiRollback)
        assertEquals(EngineVersion.LEGACY, appliedSafe.effectiveUiVersion)
    }

    private fun safeSnapshot(targetClass: TargetClass) = PlannerSnapshot(
        ObservationValue.Known(knownState()),
        StableCastSession(
            StableState.IDLE_VERIFIED, EngineVersion.V2, "test", null, "display-x",
            CastBaseline(), null, null, null, 1,
        ),
        targetClass, installed = true, hasLauncher = true, plannerEpoch = 7,
    )

    private fun knownState() = ObservedState(
        ObservedCoarseState.IDLE_CLEAN, "display-x", null, emptySet(), null, null,
    )
}

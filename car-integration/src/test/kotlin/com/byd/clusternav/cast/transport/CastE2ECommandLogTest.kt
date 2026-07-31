package com.byd.clusternav.cast.transport

import com.byd.clusternav.modules.clustercast.v2.CastIntent
import com.byd.clusternav.modules.clustercast.v2.CastIntentKind
import com.byd.clusternav.modules.clustercast.v2.CastManualIntentResult
import com.byd.clusternav.modules.clustercast.v2.CastManualTargetReader
import com.byd.clusternav.modules.clustercast.v2.CastManualTargetSnapshot
import com.byd.clusternav.modules.clustercast.v2.CommandKind
import com.byd.clusternav.modules.clustercast.v2.ExecutionResult
import com.byd.clusternav.modules.clustercast.v2.PlanResult
import com.byd.clusternav.modules.clustercast.v2.ProtectedResidue
import com.byd.clusternav.modules.clustercast.v2.ResidueVisibility
import com.byd.clusternav.modules.clustercast.v2.SealDl3BootstrapProfile
import com.byd.clusternav.modules.clustercast.v2.StableState
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The four foundation scenarios for the off-device E2E command-log harness ([CastE2ECommandHarness.kt]).
 *
 * Every scenario drives [com.byd.clusternav.modules.clustercast.v2.CastCoordinator] the same way
 * [com.byd.clusternav.modules.clustercast.CastFacade] does (see the harness file's KDoc for the exact
 * call-boundary justification), captures the real [CastPlacementCommands]-encoded shell string for every
 * dispatched [CommandKind], and diffs the resulting text log byte-for-byte against a checked-in golden
 * fixture under `docs/refactor-car-execution/fixtures/e2e-command-logs/` — the same convention
 * `RealFixtureParsingTest` already uses for dumpsys/am-stack-list fixtures.
 *
 * This is the harness FOUNDATION the Coverage phase extends for every other action (per-app DPI/style,
 * geometry adjust, retry, recovery, …): call [castE2EFixture], script observed states with
 * [CastE2EFixture.scriptStates], drive a coordinator call, then assert [CastE2EFixture.commandLog]
 * against a new golden file in the same directory.
 */
class CastE2ECommandLogTest {

    @Test
    fun `cold bootstrap cast normal target`() {
        val target = "com.example.maps"
        val world = CastVehicleWorld()
        val fixture = castE2EFixture(
            world = world,
            onDispatched = { request, _ -> if (request.kind == CommandKind.PLACE_KEEP_SESSION) world.land(target, 2) },
        )
        fixture.scriptStates(e2eIdle(), e2eIdle(), e2eIdle(), e2eActive(target), e2eActive(target))

        val result = fixture.coordinator.runManualIntent(
            target,
            SealDl3BootstrapProfile.exactFacts,
            CastManualTargetReader { CastManualTargetSnapshot(e2eNormalTargetEvidence(), installed = true, hasLauncher = true) },
        )

        assertTrue(result is CastManualIntentResult.Succeeded, "expected Succeeded, got $result")
        // `Succeeded` alone is not proof of ACTIVE_VERIFIED: CastManualIntentRunner.verify() (see
        // CastManualIntent.kt:220-221) returns Succeeded for EITHER ACTIVE_VERIFIED or ACTIVE_DEGRADED
        // (ACTIVE_DEGRADED is what a projection-protected residue on the observed sample would produce —
        // see CastCoordinator.kt:287). This scenario's scripted terminal sample (`e2eActive(target)`) has
        // no `protectedResidue`, so it must land on ACTIVE_VERIFIED specifically; assert that fact
        // directly instead of only the broader sealed-type check.
        val stableSession = (result as CastManualIntentResult.Succeeded).stableSession
        assertEquals(StableState.ACTIVE_VERIFIED, stableSession.state, "a normal, unprotected target must verify, not degrade")
        assertEquals(target, stableSession.activeTarget?.packageName)
        assertEquals(
            golden("cold-bootstrap-cast-normal.txt"), fixture.commandLog(),
            "cold-bootstrap normal command log diverged from the golden fixture",
        )
    }

    @Test
    fun `cold bootstrap cast protected target — CarPlay-shaped evidence`() {
        val target = "com.example.carplay"
        val world = CastVehicleWorld()
        val fixture = castE2EFixture(
            world = world,
            onDispatched = { request, _ -> if (request.kind == CommandKind.RESUME_PROTECTED) world.land(target, 2) },
        )
        fixture.scriptStates(e2eIdle(), e2eIdle(), e2eIdle(), e2eActive(target), e2eActive(target))

        val result = fixture.coordinator.runManualIntent(
            target,
            SealDl3BootstrapProfile.exactFacts,
            CastManualTargetReader { CastManualTargetSnapshot(e2eProtectedTargetEvidence(), installed = true, hasLauncher = true) },
        )

        assertTrue(result is CastManualIntentResult.Succeeded, "expected Succeeded, got $result")
        assertTrue(
            fixture.commands.none { it.kind == CommandKind.FORCE_STOP_NORMAL },
            "a protected/projection-sink target must never be force-stopped",
        )
        assertEquals(
            golden("cold-bootstrap-cast-protected.txt"), fixture.commandLog(),
            "cold-bootstrap protected-target command log diverged from the golden fixture",
        )
    }

    @Test
    fun `warm switch between two targets`() {
        val old = "com.example.old"
        val target = "com.example.new"
        val world = CastVehicleWorld()
        val fixture = castE2EFixture(
            stable = e2eActiveSession(old),
            epoch = 4L,
            world = world,
            onDispatched = { request, _ -> if (request.kind == CommandKind.PLACE_KEEP_SESSION) world.land(target, 2) },
        )
        fixture.scriptStates(e2eActive(old), e2eActive(target), e2eActive(target))

        val result = fixture.coordinator.runManualIntent(
            target,
            SealDl3BootstrapProfile.exactFacts,
            CastManualTargetReader { CastManualTargetSnapshot(e2eNormalTargetEvidence(), installed = true, hasLauncher = true) },
        )

        assertTrue(result is CastManualIntentResult.Succeeded, "expected Succeeded, got $result")
        assertTrue(
            fixture.commands.none { it.kind in SealDl3BootstrapProfile.forwardKinds },
            "a warm switch must never replay cold bootstrap",
        )
        assertEquals(
            golden("warm-switch.txt"), fixture.commandLog(),
            "warm-switch command log diverged from the golden fixture",
        )
    }

    @Test
    fun `Stop from an active session`() {
        // FIXED 2026-07-28: CastExecutor.executeLocked's anti-bootstrap-replay guard used to match on
        // BOOTSTRAP_COMMANDS = forwardKinds + compensationKinds (CastExecutor.kt). Every ordinary Stop
        // plan (CastPlanner.kt's CastIntentKind.STOP branch) unconditionally ends with
        // SEAL_DL3_COMPENSATE_18/_0 to close the OEM projection — exactly compensationKinds — so the
        // guard blocked every Stop, on any vehicle, in any state, before a single command dispatched.
        // Fix: BOOTSTRAP_COMMANDS now covers only the forward ACTIVATION opcodes
        // (forwardKinds + styleKinds), which a Stop plan never carries; the guard still correctly
        // blocks any plan smuggling a real activation opcode outside cold bootstrap (see
        // CastExecutorBootstrapGuardTest in :core). This test now asserts the FIXED dispatch ladder.
        val target = "com.example.maps"
        val world = CastVehicleWorld()
        val fixture = castE2EFixture(
            stable = e2eActiveSession(target),
            epoch = 4L,
            world = world,
            isDisplayClean = { true },
        )
        fixture.scriptStates(e2eActive(target))

        val accepted = fixture.coordinator.requestStop()
        assertTrue(accepted != null && accepted.stopRequested, "Stop must be journaled before any mutation is planned")

        val plan = fixture.coordinator.plan(
            CastIntent(CastIntentKind.STOP, target),
            e2eNormalTargetEvidence(),
            installed = true,
            hasLauncher = true,
        )
        assertTrue(plan is PlanResult.Ready, "expected a Ready Stop plan, got $plan")

        val execution = fixture.coordinator.execute(plan, target)
        assertTrue(execution is ExecutionResult.AwaitingVerification, "Stop must actually dispatch, got $execution")
        assertTrue(
            fixture.commands.any { it.kind == CommandKind.SEAL_DL3_COMPENSATE_18 } &&
                fixture.commands.any { it.kind == CommandKind.SEAL_DL3_COMPENSATE_0 },
            "Stop must close the OEM projection (opcodes 18,0) so the cluster returns to its native gauges",
        )
        assertTrue(
            fixture.commands.none { it.kind in SealDl3BootstrapProfile.forwardKinds },
            "Stop must never replay a cold-bootstrap activation opcode",
        )
        assertTrue(
            fixture.commands.none { it.kind == CommandKind.FORCE_STOP_NORMAL },
            "returning a normal target uses RETURN_NORMAL_TO_MAIN, never a force-stop",
        )

        assertEquals(
            golden("stop-from-active.txt"), fixture.commandLog(),
            "Stop-from-active command log diverged from the golden fixture",
        )
    }

    @Test
    fun `Stop from ACTIVE_DEGRADED with a protected residue`() {
        // FUNCTION UNDER TEST: stop-from-degraded. ACTIVE_DEGRADED is the durable state left behind when
        // a protected app (CarPlay/AA/keep-session) resisted an earlier return and stayed parked on the
        // cluster display as a `protectedResidue` alongside the current active target
        // (CastCoordinator.kt:287). Same call boundary as "Stop from an active session" above, only the
        // durable stableSession and the scripted observed sample now carry that residue.
        //
        // FIXED 2026-07-28: same root cause and same fix as "Stop from an active session" above
        // (CastExecutor's BOOTSTRAP_COMMANDS guard no longer matches the teardown opcodes every Stop
        // plan carries). This test now asserts the fixed dispatch ladder, and the "never force-stops or
        // names the protected residue" claim non-vacuously, against real dispatched commands.
        val target = "com.example.maps"
        val residue = ProtectedResidue("com.example.carplay", 9, ResidueVisibility.HIDDEN)
        val world = CastVehicleWorld()
        val fixture = castE2EFixture(
            stable = e2eActiveSession(target).copy(state = StableState.ACTIVE_DEGRADED, protectedResidue = residue),
            epoch = 4L,
            world = world,
            isDisplayClean = { true },
        )
        fixture.scriptStates(e2eActive(target, protectedResidue = residue))

        val accepted = fixture.coordinator.requestStop()
        assertTrue(accepted != null && accepted.stopRequested, "Stop must be journaled before any mutation is planned")

        val plan = fixture.coordinator.plan(
            CastIntent(CastIntentKind.STOP, target),
            e2eNormalTargetEvidence(),
            installed = true,
            hasLauncher = true,
        )
        assertTrue(plan is PlanResult.Ready, "expected a Ready Stop plan even from ACTIVE_DEGRADED, got $plan")

        val execution = fixture.coordinator.execute(plan, target)
        assertTrue(execution is ExecutionResult.AwaitingVerification, "Stop must actually dispatch, got $execution")
        assertTrue(
            fixture.commands.isNotEmpty(),
            "this claim must be non-vacuous: some command must actually dispatch",
        )
        assertTrue(
            fixture.commands.none { it.kind == CommandKind.FORCE_STOP_NORMAL },
            "no dispatched command may ever force-stop anything during Stop",
        )
        assertTrue(
            fixture.commands.none { it.shell?.contains(residue.packageName) == true },
            "no dispatched command may ever name the protected residue's package",
        )

        assertEquals(
            golden("stop-from-degraded.txt"), fixture.commandLog(),
            "Stop-from-degraded command log diverged from the golden fixture",
        )
    }

    /**
     * DPI-adjustment coverage (cast-time path): `CastAppCatalog.clusterDensityDpi` is read by
     * `ClusterCastActivity`/`FloatingBubbleService` and passed as `runManualIntent`'s `preferredDensityDpi`,
     * which becomes `CastMutationRequest.geometry.densityDpi` for EVERY dispatched step of the plan
     * (`CastPlanner.plan`'s trailing `geometry = ... else intent.geometry`) — including
     * `FIT_CLUSTER_COMPOSITE`, whose `CastPlacementCommands.kt` branch is the one place that folds it into
     * a "wm density" shell prefix. This proves the exact string a chosen, in-range DPI produces, using the
     * REAL production encoder (not a hand-written string) — the same call boundary
     * `ClusterCastActivity.executeCast()` and `FloatingBubbleService.dispatchTarget()` both go through.
     */
    @Test
    fun `preferred density dpi reaches an exact wm density prefix inside FIT_CLUSTER_COMPOSITE`() {
        val target = "com.example.maps"
        val world = CastVehicleWorld()
        world.land(target, 2) // already landed on the cluster display before this cast begins
        val fixture = castE2EFixture(stable = e2eIdleSession(), epoch = 4L, world = world)
        fixture.scriptStates(e2eIdle(), e2eActive(target), e2eActive(target))

        val result = fixture.coordinator.runManualIntent(
            target,
            SealDl3BootstrapProfile.exactFacts,
            CastManualTargetReader { CastManualTargetSnapshot(e2eNormalTargetEvidence(), installed = true, hasLauncher = true) },
            preferredDensityDpi = 220,
        )

        assertTrue(result is CastManualIntentResult.Succeeded, "expected Succeeded, got $result")
        val fitCalls = fixture.commands.filter { it.kind == CommandKind.FIT_CLUSTER_COMPOSITE }
        assertTrue(fitCalls.isNotEmpty(), "expected at least one FIT_CLUSTER_COMPOSITE dispatch")
        fitCalls.forEach {
            assertEquals(
                "wm density 220 -d 2; (am task resize 42 0 0 1920 720 2>/dev/null) || wm overscan 0,90,0,90 -d 2",
                it.shell,
                "FIT_CLUSTER_COMPOSITE must fold the chosen preferredDensityDpi into an exact wm density prefix",
            )
        }
    }

    /**
     * `CastPlacementCommands.kt`'s FIT_CLUSTER_COMPOSITE branch guards the density it folds in with
     * `takeIf { it in 80..640 }` (the same range `CastAppCatalog.setClusterDensityDpi` enforces at store
     * time — see `CastAppCatalogDensityDpiTest` in `:app`). This is defense-in-depth for the case that
     * upstream gate is ever bypassed: an out-of-range value must never appear as a "wm density" fragment
     * in the dispatched shell string — proven here with the real encoder, not a hand-written string.
     *
     * NOTE what this does NOT claim: unlike `CastGeometry.validate` (the gate for the OTHER density input,
     * `CastRowActions.applyGeometry`'s per-app-size DPI — see `CastGeometryTest`'s "density outside 72 to
     * 640" case, which rejects the WHOLE apply before any command is even planned), this shell-encoding
     * guard does not reject the operation: it silently drops just the density fragment and still dispatches
     * the resize. The DPI never reaches a shell string either way, but by a different mechanism.
     */
    @Test
    fun `out-of-range preferred density never appears in the dispatched shell string`() {
        val target = "com.example.maps"
        val world = CastVehicleWorld()
        world.land(target, 2)
        val fixture = castE2EFixture(stable = e2eIdleSession(), epoch = 4L, world = world)
        fixture.scriptStates(e2eIdle(), e2eActive(target), e2eActive(target))

        val result = fixture.coordinator.runManualIntent(
            target,
            SealDl3BootstrapProfile.exactFacts,
            CastManualTargetReader { CastManualTargetSnapshot(e2eNormalTargetEvidence(), installed = true, hasLauncher = true) },
            preferredDensityDpi = 700, // outside CastPlacementCommands' 80..640 shell-encoding guard
        )

        assertTrue(result is CastManualIntentResult.Succeeded, "expected Succeeded, got $result")
        val fitCalls = fixture.commands.filter { it.kind == CommandKind.FIT_CLUSTER_COMPOSITE }
        assertTrue(fitCalls.isNotEmpty(), "expected at least one FIT_CLUSTER_COMPOSITE dispatch")
        fitCalls.forEach {
            assertEquals(
                "(am task resize 42 0 0 1920 720 2>/dev/null) || wm overscan 0,90,0,90 -d 2",
                it.shell,
                "an out-of-range density must never be dispatched as a \"wm density\" fragment",
            )
            assertFalse(it.shell?.contains("wm density") == true, "shell string must not contain wm density at all")
        }
    }

    private fun golden(name: String): String {
        val roots = listOf(
            "docs/refactor-car-execution/fixtures/e2e-command-logs",
            "../docs/refactor-car-execution/fixtures/e2e-command-logs",
        ).map(Paths::get)
        val root = roots.firstOrNull(Files::exists)
            ?: error("could not find the e2e-command-logs fixture directory; tried $roots")
        return root.resolve(name).toFile().readText().trimEnd('\n')
    }
}

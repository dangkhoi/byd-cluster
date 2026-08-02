package com.byd.clusternav.cast.transport

import com.byd.clusternav.modules.clustercast.v2.CastIntent
import com.byd.clusternav.modules.clustercast.v2.CastIntentKind
import com.byd.clusternav.modules.clustercast.v2.CastManualIntentResult
import com.byd.clusternav.modules.clustercast.v2.CastManualTargetReader
import com.byd.clusternav.modules.clustercast.v2.CastManualTargetSnapshot
import com.byd.clusternav.modules.clustercast.v2.CommandKind
import com.byd.clusternav.modules.clustercast.v2.ExecutionResult
import com.byd.clusternav.modules.clustercast.v2.OperationPhase
import com.byd.clusternav.modules.clustercast.v2.PlanResult
import com.byd.clusternav.modules.clustercast.v2.SealDl3BootstrapProfile
import com.byd.clusternav.modules.clustercast.v2.StableState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Khoá lỗi hiện trường DiLink3 2026-07-30 (`docs/diagnostics/cast-stop-recovering-stuck-2026-07-30.md`)
 * ở ĐÚNG tầng nó gây hại: không phải "hàm phân loại trả về gì" (việc đó
 * `CastMutationEffectClassificationTest` trong `:core` đã khoá), mà là "giao dịch có đóng được không".
 *
 * Thực tế trên xe: cast VietMap lên cụm THÀNH CÔNG (mắt thấy trên cụm), nhưng vì app đã có task sẵn nên
 * `am start` ghi ra stderr đúng một dòng cảnh báo lành ("task hiện tại đã được mang lên trước"). Bộ phân
 * loại cũ coi MỌI stderr không rỗng là thất bại → `CastExecutor.markRecovery` → `phase=RECOVERING` ngay
 * giữa thang, dù không có bước nào hỏng. Từ đó transaction không bao giờ đóng, và mỗi cú bấm Dừng chỉ hiện
 * lại "Đang xử lý…".
 *
 * Ba test dưới đây đi qua đúng đường thật: [CastPlacementCommands] mã hoá lệnh, [FakeDadb] trả stderr
 * nguyên văn như xe, `classifyMutationShellResult` phân loại, `CastExecutor` ghi ledger. Test 1 và 2 khoá
 * hai luồng người dùng thấy (Chiếu, Dừng); test 3 khoá việc danh sách trắng KHÔNG được nới rộng thành
 * "hễ có dòng cảnh báo đó thì bỏ qua cả stderr".
 */
class CastBenignLaunchStderrTest {
    /** Nguyên văn, gõ lại (không import hằng của production) để một lần sửa hằng đó là thấy test đỏ. */
    private val brought = "Warning: Activity not started, its current task has been brought to the front"

    @Test
    fun `cast completes when am start only brings the existing task to the front`() {
        val target = "com.example.maps"
        val world = CastVehicleWorld(launchStderr = "$brought\n")
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

        assertTrue(result is CastManualIntentResult.Succeeded, "cảnh báo lành không được làm hỏng cast, got $result")
        assertEquals(
            StableState.ACTIVE_VERIFIED,
            (result as CastManualIntentResult.Succeeded).stableSession.state,
            "đường verify vẫn phải là nơi ra phán quyết, không phải stderr của am start",
        )
        assertNotEquals(
            OperationPhase.RECOVERING,
            fixture.envelope().transaction?.phase,
            "không được còn giao dịch nào treo ở RECOVERING sau một cast đã thành công",
        )
    }

    @Test
    fun `Stop dispatches through when returning the task only brings it to the front`() {
        // `am start --display 0 --windowingMode 1` (bước return-normal của Stop, CastPlanner.kt) gặp ĐÚNG
        // kịch bản này thường xuyên hơn cả đường cast: task đang nằm trên cụm, nên framework mang nó lên
        // trước chứ không khởi tạo mới. Trước bản vá, Stop chết ngay bước ĐẦU TIÊN của nó.
        val target = "com.example.maps"
        val world = CastVehicleWorld(launchStderr = "$brought\n")
        val fixture = castE2EFixture(
            stable = e2eActiveSession(target),
            epoch = 4L,
            world = world,
            isDisplayClean = { true },
        )
        fixture.scriptStates(e2eActive(target))

        val accepted = fixture.coordinator.requestStop()
        assertTrue(accepted != null && accepted.stopRequested, "Stop phải được ghi bền trước khi phát lệnh")

        val plan = fixture.coordinator.plan(
            CastIntent(CastIntentKind.STOP, target),
            e2eNormalTargetEvidence(),
            installed = true,
            hasLauncher = true,
        )
        assertTrue(plan is PlanResult.Ready, "expected a Ready Stop plan, got $plan")

        val execution = fixture.coordinator.execute(plan, target)
        assertTrue(
            execution is ExecutionResult.AwaitingVerification,
            "Stop phải chạy hết thang rồi sang verify, không rơi vào recovery, got $execution",
        )
        assertTrue(
            fixture.commands.any { it.kind == CommandKind.RETURN_NORMAL_TO_MAIN } &&
                fixture.commands.any { it.kind == CommandKind.SEAL_DL3_COMPENSATE_0 },
            "phải phát cả bước trả task về màn giữa VÀ bước đóng chiếu của OEM",
        )
        assertNotEquals(
            OperationPhase.RECOVERING,
            fixture.envelope().transaction?.phase,
            "một cảnh báo lành không được đẩy Stop sang RECOVERING",
        )
    }

    @Test
    fun `a real error riding along with the benign warning still forces recovery`() {
        // Đây là lý do danh sách trắng so khớp CHÍNH XÁC cả dòng chứ không `contains`: dòng lành đi kèm
        // một lỗi thật vẫn phải là lỗi thật.
        val target = "com.example.maps"
        val world = CastVehicleWorld(
            launchStderr = "$brought\njava.lang.SecurityException: Permission Denial: starting Intent",
        )
        val fixture = castE2EFixture(
            stable = e2eActiveSession(target),
            epoch = 4L,
            world = world,
            isDisplayClean = { true },
        )
        fixture.scriptStates(e2eActive(target))

        assertTrue(fixture.coordinator.requestStop()?.stopRequested == true)
        val plan = fixture.coordinator.plan(
            CastIntent(CastIntentKind.STOP, target),
            e2eNormalTargetEvidence(),
            installed = true,
            hasLauncher = true,
        )
        assertTrue(plan is PlanResult.Ready, "expected a Ready Stop plan, got $plan")

        val execution = fixture.coordinator.execute(plan, target)
        assertTrue(
            execution is ExecutionResult.RecoveryRequired,
            "stderr có lỗi thật phải vẫn dừng thang và đòi recovery, got $execution",
        )
        assertEquals(
            OperationPhase.RECOVERING,
            fixture.envelope().transaction?.phase,
            "giao dịch phải được ghi RECOVERING khi hiệu ứng thật sự không rõ",
        )
    }
}

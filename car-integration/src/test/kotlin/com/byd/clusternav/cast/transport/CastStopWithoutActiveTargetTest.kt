package com.byd.clusternav.cast.transport

import com.byd.clusternav.modules.clustercast.v2.CastIntent
import com.byd.clusternav.modules.clustercast.v2.CastIntentKind
import com.byd.clusternav.modules.clustercast.v2.CommandKind
import com.byd.clusternav.modules.clustercast.v2.ExecutionResult
import com.byd.clusternav.modules.clustercast.v2.ObservedCoarseState
import com.byd.clusternav.modules.clustercast.v2.PlanResult
import com.byd.clusternav.modules.clustercast.v2.TargetEvidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Khoá ĐƯỜNG THOÁT của trạng thái "bền nói rảnh, cụm thật lại có app lạ"
 * (docs/specs/cast-recovery-honesty-and-multi-occupant.html §R2).
 *
 * R2 mở ra một ca CHƯA TỪNG có trước đó: `OBSERVATION_DIVERGED_STOP_AVAILABLE` xuất Stop khi
 * `stableSession.state == IDLE_VERIFIED` — mà một phiên IDLE_VERIFIED KHÔNG có `activeTarget`, nên
 * `CastFacade.continueStopAfterAcknowledgement` gọi `planStop(null, null)`. Trước bản vá đi kèm review
 * này, bước ĐẦU TIÊN của thang Stop (`RETURN_NORMAL_TO_MAIN`) dựng lệnh từ chính `targetPackage` đó:
 * không có gói ⇒ `CastPlacementCommands.of` trả `null` ⇒ gateway trả `Rejected` ⇒ `CastExecutor` ghi
 * `markRecovery` ⇒ transaction kẹt RECOVERING với `stopRequested=true`. `reconcileAbandoned` đòi cụm
 * IDLE_CLEAN mới dọn được, mà cụm thì đang có app lạ — đúng lớp ngõ cụt
 * `docs/diagnostics/cast-recovery-deadend-2026-07-28.md`, lần này do CHÍNH hành động mà UI mời bấm.
 *
 * CLAUDE.md §3: "không bao giờ gate một đường phục hồi bằng dữ liệu mà chỉ chính đường đó mới làm mới
 * được" — Stop phải chạy được kể cả khi không biết gói nào, vì phần còn lại của thang (trả animation,
 * trả app-op, reset display sạch, đóng projection OEM) đều KHÔNG cần tên gói.
 */
class CastStopWithoutActiveTargetTest {

    /** Cụm (display 2) đang bị hai app lạ chiếm — ClusterNav không hề biết app nào là chủ. */
    private fun foreignOccupiedCluster() = CastVehicleWorld(
        amStackList = "Stack id=0 displayId=0\n" +
            "  taskId=1: com.example.launcher/.Main\n" +
            "Stack id=17 displayId=2\n" +
            "  taskId=15: app.revanced.android.apps.maps/com.google.android.maps.MapsActivity\n" +
            "Stack id=18 displayId=2\n" +
            "  taskId=14: vn.vietmap.live/vn.vietmap.live.MainActivity",
    )

    @Test
    fun `Stop from a diverged idle claim with no known target dispatches the teardown instead of wedging`() {
        val fixture = castE2EFixture(
            stable = e2eIdleSession(),
            epoch = 4L,
            world = foreignOccupiedCluster(),
            isDisplayClean = { false },
        )
        // Đúng thứ parser trả về sau bản vá R2: ACTIVE_MULTI, target=null (không ai là chủ rõ ràng).
        fixture.scriptStates(
            e2eIdle().copy(
                coarseState = ObservedCoarseState.ACTIVE_MULTI,
                target = null,
                occupants = setOf("app.revanced.android.apps.maps", "vn.vietmap.live"),
            ),
        )

        val accepted = fixture.coordinator.requestStop()
        assertTrue(accepted != null && accepted.stopRequested, "Stop phải được ghi bền trước khi phát lệnh")
        assertTrue(accepted!!.transaction == null, "không có transaction nào đang chạy nên Stop được đi tiếp")

        // Đúng đối số mà CastFacade.continueStopAfterAcknowledgement dựng khi activeTarget == null.
        val plan = fixture.coordinator.plan(
            CastIntent(CastIntentKind.STOP, null),
            TargetEvidence(false, null, false),
            installed = true,
            hasLauncher = true,
        )
        assertTrue(plan is PlanResult.Ready, "Stop phải lên được kế hoạch dù không biết gói nào, got $plan")

        val execution = fixture.coordinator.execute(plan as PlanResult.Ready, null)
        assertTrue(
            execution is ExecutionResult.AwaitingVerification,
            "Stop không được tự đẩy mình vào RECOVERING chỉ vì không có gói để trả về màn chính: " +
                "got $execution, log=\n${fixture.commandLog()}",
        )
        assertEquals(
            listOf(
                CommandKind.RETURN_NORMAL_TO_MAIN,
                CommandKind.RESTORE_PIP,
                CommandKind.RESTORE_TRANSITION_ANIMATION,
                CommandKind.RESET_CLEAN_DISPLAY,
                CommandKind.SEAL_DL3_COMPENSATE_18,
                CommandKind.SEAL_DL3_COMPENSATE_0,
            ),
            fixture.commands.map { it.kind },
            "cả thang Stop phải chạy tới nơi, đặc biệt là hai bước đóng projection OEM — đó mới là thứ " +
                "trả đồng hồ lại cho tài xế: log=\n${fixture.commandLog()}",
        )
        assertTrue(
            fixture.commands.none { it.shell == null },
            "không bước nào được refuse: log=\n${fixture.commandLog()}",
        )
        // Không có gói ⇒ không phát `am start` nào cả. Bước đó là no-op, KHÔNG phải một lệnh đoán mò
        // vào app nào đó (CLAUDE.md §4: mọi lệnh phải nhắm đúng app, không quét mù).
        assertTrue(
            fixture.commands.none { it.shell?.startsWith("am start") == true },
            "không biết gói thì không được đoán một `am start`: log=\n${fixture.commandLog()}",
        )
        // Cụm đang có app lạ ⇒ RESET_CLEAN_DISPLAY phải tự bỏ qua, không được đụng vào chúng.
        assertTrue(
            fixture.commands.none { it.shell?.startsWith("wm ") == true },
            "cụm không sạch thì không được reset hình học của nó: log=\n${fixture.commandLog()}",
        )
    }
}

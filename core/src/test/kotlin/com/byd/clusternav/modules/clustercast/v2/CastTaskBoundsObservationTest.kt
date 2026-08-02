package com.byd.clusternav.modules.clustercast.v2

import com.byd.clusternav.modules.clustercast.StackParse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Khoá lỗi F3 (docs/specs/cast-one-mode-and-three-zone-bubble.html §F3, task T6/B3): quan sát trước đây
 * CHỈ mang khung của **display**, trong khi `CastTransaction.requestedGeometry` là rect của **task**
 * (`am task resize <taskId> l t r b`, CastGeometry.kt:45). `CastCoordinator.completeVerificationLocked`
 * (CastCoordinator.kt:379-381) so bằng hai đại lượng khác loại đó ⇒ chỉ đúng khi rect yêu cầu tình cờ
 * bằng nguyên khung display ⇒ MỌI lần resize thật đều "verification diverged" → RECOVERING. Đó là lý do
 * tính năng chỉnh kích thước chưa từng chạy nổi một lần nào trên xe.
 *
 * Test này khoá phần T6 làm: khung THẬT của task phải QUAN SÁT ĐƯỢC (`ObservedState.taskBounds` /
 * `targetTaskBounds`), và phải KHÁC khung display khi thực tế chúng khác nhau. Phép so ở CastCoordinator
 * cố ý CHƯA đổi trong đợt này (T7 mới tiêu thụ) — nên ở đây không assert gì về verification.
 *
 * Dữ liệu: dòng task nguyên văn kiểu dump DiLink3 tối 2026-07-31 (cùng nguồn với
 * CastMultiOccupantObservationTest), cụm `fission_bg_xdjaVirtualSurface` 1920×720 nhưng vùng vẽ thật đo
 * được trên xe là `[0,90]–[1920,810]` — chính con số làm hai đại lượng lệch nhau.
 */
class CastTaskBoundsObservationTest {

    /** Một app trên cụm, khung task LỆCH khung display — đúng hình dạng ca resize thật. */
    private val amStacksSingleResized = """
        Stack id=17 bounds=[0,90][1920,810] displayId=2 userId=0
         mBounds=Rect(0, 90 - 1920, 810)
          taskId=12: app.revanced.android.apps.maps/com.google.android.maps.MapsActivity bounds=[0,90][1920,810] userId=0 visible=true topActivity=ComponentInfo{app.revanced.android.apps.maps/com.google.android.maps.MapsActivity}
    """.trimIndent()

    /**
     * Hai app chia đôi cụm — đúng ảnh chụp trên xe 31/07 (spec §"Hai app cùng hiện được"):
     * GMaps `[0,90][960,810]` + VietMap `[960,90][1920,810]`.
     */
    private val amStacksSplitFifty = """
        Stack id=17 bounds=[0,90][960,810] displayId=2 userId=0
          taskId=15: app.revanced.android.apps.maps/com.google.android.maps.MapsActivity bounds=[0,90][960,810] userId=0 visible=true topActivity=ComponentInfo{app.revanced.android.apps.maps/com.google.android.maps.MapsActivity}

        Stack id=18 bounds=[960,90][1920,810] displayId=2 userId=0
          taskId=14: vn.vietmap.live/vn.vietmap.live.MainActivity bounds=[960,90][1920,810] userId=0 visible=true topActivity=ComponentInfo{vn.vietmap.live/vn.vietmap.live.MainActivity}
    """.trimIndent()

    private val mapsAppOps = "Package app.revanced.android.apps.maps:\n  PICTURE_IN_PICTURE: allow"

    /**
     * `wmDisplays` cố ý báo khung display 1920×720 (đúng như dump thật của VD cụm) để phép so
     * "task rect ≠ display bounds" ở dưới là phép so trên DỮ LIỆU THẬT, không phải trên số bịa.
     */
    private fun observe(amStacks: String, appOps: String = mapsAppOps): ObservedState {
        val result = DumpObservedStateParser.parse(
            RawObservation(
                amStacks = amStacks,
                wmDisplays = "mBounds=Rect(0, 0 - 1920, 720)",
                displays = SEAL_CLUSTER_DUMP,
                profile = "0",
                animations = "1.0\n1.0\n1.0",
                appOps = appOps,
            ),
        )
        assertTrue(result is ObservationValue.Known, "expected Known, got $result")
        return (result as ObservationValue.Known).value
    }

    @Test
    fun `task line bounds land in the observation exactly as captured`() {
        val state = observe(amStacksSingleResized)
        assertEquals(mapOf(12 to CastRect(0, 90, 1920, 810)), state.taskBounds)
        assertEquals(CastRect(0, 90, 1920, 810), state.targetTaskBounds)
        assertEquals(12, state.target?.taskId)
    }

    @Test
    fun `the landed task rect is NOT the display bounds — this is the whole of bug F3`() {
        // Nếu assert này còn xanh mà `taskBounds` rỗng thì bản vá vô nghĩa: phép xác minh vẫn chỉ có
        // trong tay khung display. Hai vế phải KHÁC nhau, và cả hai phải cùng đọc được.
        val state = observe(amStacksSingleResized)
        val displayBounds = state.geometry?.bounds
        assertEquals(CastRect(0, 0, 1920, 720), displayBounds, "display bounds must still be reported as before")
        assertNotEquals(
            displayBounds,
            state.targetTaskBounds,
            "task rect and display bounds differ on the real vehicle; comparing them is exactly why every resize verified as diverged",
        )
    }

    @Test
    fun `both halves of a 50-50 split are observable, keyed by task id`() {
        // Ca R6/T8: hai app cùng visible=true ⇒ không có target duy nhất (đã khoá ở
        // CastMultiOccupantObservationTest), nhưng khung của CẢ HAI vẫn phải quan sát được — nếu không,
        // "so rect theo từng task" của T8 không có dữ liệu để chạy.
        val state = observe(amStacksSplitFifty, appOps = "")
        assertEquals(ObservedCoarseState.ACTIVE_MULTI, state.coarseState)
        assertNull(state.target, "two visible winners means no single target, unchanged by this task")
        assertNull(state.targetTaskBounds, "no target ⇒ no target rect; must not fall back to some other task")
        assertEquals(
            mapOf(15 to CastRect(0, 90, 960, 810), 14 to CastRect(960, 90, 1920, 810)),
            state.taskBounds,
        )
    }

    @Test
    fun `a task line with no bounds token is simply absent, never a fabricated rect`() {
        // Dump thật có dòng task hợp lệ KHÔNG in `bounds=`. Trạng thái đúng là "chưa biết" (CLAUDE.md §2),
        // không phải mượn tạm khung display — mượn tạm chính là cách bug F3 sinh ra lần đầu.
        val noBounds = amStacksSingleResized.replace(" bounds=[0,90][1920,810] userId=0", " userId=0")
        val state = observe(noBounds)
        assertEquals(12, state.target?.taskId, "the task itself is still parsed as before")
        assertTrue(state.taskBounds.isEmpty(), "no bounds token ⇒ no entry, got ${state.taskBounds}")
        assertNull(state.targetTaskBounds)
    }

    @Test
    fun `tasks parked on other displays never leak into the cluster observation`() {
        // CLAUDE.md §4: phạm vi tường minh. Khung task chỉ được lấy trên ĐÚNG display cụm; một task đang
        // đậu ở màn giữa (display 0) không được lẫn vào dữ liệu mà T7 sẽ đem đi xác minh.
        val withMainScreenTask = amStacksSingleResized + "\n\n" + """
            Stack id=1 bounds=[0,0][1920,1080] displayId=0 userId=0
              taskId=3: com.byd.dudu.launcher/com.byd.dudu.launcher.HomeActivity bounds=[0,0][1920,1080] userId=0 visible=true topActivity=ComponentInfo{com.byd.dudu.launcher/com.byd.dudu.launcher.HomeActivity}
        """.trimIndent()
        val state = observe(withMainScreenTask)
        assertEquals(mapOf(12 to CastRect(0, 90, 1920, 810)), state.taskBounds)
    }

    @Test
    fun `the v2 task-bounds reader agrees number-for-number with StackParse on the same dump`() {
        // Bài học "hai parser lệch nhau": khung được đọc ở hai nơi trong repo này (StackParse cho engine
        // V1/chẩn đoán, quan sát V2 cho xác minh). Chúng phải ra CÙNG bốn con số trên CÙNG một dump thật,
        // nếu không thì đến lúc hiện trường báo lỗi sẽ không ai biết tin bên nào.
        val v2 = observe(amStacksSingleResized).taskBounds.getValue(12)
        val v1 = StackParse.parse(amStacksSingleResized).single { it.taskId == 12 }.bounds
        assertEquals(listOf(v2.left, v2.top, v2.right, v2.bottom), v1?.toList())
    }
}

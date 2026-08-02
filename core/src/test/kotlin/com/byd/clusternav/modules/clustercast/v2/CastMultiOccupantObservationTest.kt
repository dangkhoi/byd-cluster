package com.byd.clusternav.modules.clustercast.v2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Khoá lỗi đo trên DiLink3 2026-07-31 (docs/specs/cast-recovery-honesty-and-multi-occupant.html §R2):
 * `am stack list` thật cho thấy CẢ HAI app.revanced.android.apps.maps VÀ vn.vietmap.live cùng
 * `visible=true` trên display cụm — vi phạm giả định "đúng một app visible=true mỗi display" mà
 * `DumpObservedStateParser` trước đây coi là bất biến. Dump gốc (rút gọn còn 2 dòng task, giữ nguyên chữ):
 *
 *   taskId=15: app.revanced.android.apps.maps/com.google.android.maps.MapsActivity ... visible=true
 *   taskId=14: vn.vietmap.live/vn.vietmap.live.MainActivity ... visible=true
 *
 * Trước bản vá, hàm trả `Unknown("multi-occupant target visibility unavailable")` — VỨT BỎ luôn sự thật
 * "đây là ACTIVE_MULTI" mà chính hàm này vừa tính đúng một dòng trước đó. Test khoá: sự thật đó phải
 * được GIỮ LẠI dưới dạng `Known`, không phải biến mất thành lỗi.
 */
class CastMultiOccupantObservationTest {
    private val amStacksTwoVisible = """
        Stack id=17 bounds=[0,0][1920,720] displayId=2 userId=0
         mBounds=Rect(0, 0 - 1920, 720)
          taskId=15: app.revanced.android.apps.maps/com.google.android.maps.MapsActivity bounds=[0,0][1920,720] userId=0 visible=true topActivity=ComponentInfo{app.revanced.android.apps.maps/com.google.android.maps.MapsActivity}

        Stack id=18 bounds=[0,0][1920,720] displayId=2 userId=0
          taskId=14: vn.vietmap.live/vn.vietmap.live.MainActivity bounds=[0,0][1920,720] userId=0 visible=true topActivity=ComponentInfo{vn.vietmap.live/vn.vietmap.live.MainActivity}
    """.trimIndent()

    private fun observe(amStacks: String, appOps: String = "") = DumpObservedStateParser.parse(
        RawObservation(
            amStacks = amStacks,
            wmDisplays = "mBounds=Rect(0, 0 - 1920, 720)",
            displays = SEAL_CLUSTER_DUMP,
            profile = "0",
            animations = "1.0\n1.0\n1.0",
            appOps = appOps,
        ),
    )

    @Test
    fun `two apps both visible=true on the cluster is Known ACTIVE_MULTI with no resolvable target, not Unknown`() {
        val result = observe(amStacksTwoVisible)
        assertTrue(result is ObservationValue.Known, "expected Known (the real fact, not a parse failure), got $result")
        val state = (result as ObservationValue.Known).value
        assertEquals(ObservedCoarseState.ACTIVE_MULTI, state.coarseState)
        assertNull(state.target, "no single visible=true winner exists, so target must honestly be null")
        assertEquals(setOf("app.revanced.android.apps.maps", "vn.vietmap.live"), state.occupants)
    }

    @Test
    fun `zero apps visible=true on a multi-task display is ALSO Known ACTIVE_MULTI with no target`() {
        // Biến thể còn lại của cùng mập mờ: 2 task, KHÔNG cái nào visible=true (vd cả hai đang chuyển
        // tiếp). Trước bản vá đây cũng rơi vào Unknown; giờ phải cùng một sự thật như trên.
        val bothHidden = amStacksTwoVisible.replace("visible=true", "visible=false")
        val result = observe(bothHidden)
        assertTrue(result is ObservationValue.Known, "expected Known, got $result")
        val state = (result as ObservationValue.Known).value
        assertEquals(ObservedCoarseState.ACTIVE_MULTI, state.coarseState)
        assertNull(state.target)
    }

    @Test
    fun `exactly one visible=true winner among several occupants still resolves a target as before`() {
        // Không được để bản vá multi-occupant phá ca BÌNH THƯỜNG: một winner rõ ràng vẫn phải ra target.
        val oneWinner = amStacksTwoVisible.replaceFirst("visible=true", "visible=false")
        val result = observe(oneWinner, appOps = "Package vn.vietmap.live:\n  PICTURE_IN_PICTURE: allow")
        assertTrue(result is ObservationValue.Known, "expected Known, got $result")
        val state = (result as ObservationValue.Known).value
        assertEquals(ObservedCoarseState.ACTIVE_MULTI, state.coarseState)
        assertEquals("vn.vietmap.live", state.target?.packageName)
    }
}

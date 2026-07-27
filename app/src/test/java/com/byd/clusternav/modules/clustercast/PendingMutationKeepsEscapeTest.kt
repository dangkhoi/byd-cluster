package com.byd.clusternav.modules.clustercast

import com.byd.clusternav.modules.clustercast.v2.CastAction
import com.byd.clusternav.modules.clustercast.v2.CastRenderAction
import com.byd.clusternav.modules.clustercast.v2.CastRenderModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Trong lúc chờ một phép ghi hội tụ, màn hình vẫn phải còn đường thoát và đường tìm hiểu.
 *
 * Bản trước của `activityActions` so sánh BẰNG với hai tập phép cứng rồi trả về đúng một phép STOP,
 * nghĩa là suốt thời gian chờ người dùng mất cả Chẩn đoán. Nó cũng vỡ im lặng khi tập phép đổi: thêm
 * một phép mới là điều kiện so-bằng lặng lẽ thành false. Tìm ra ngày 2026-07-27 khi chạy E2E emulator.
 */
class PendingMutationKeepsEscapeTest {

    private fun model(vararg allowed: CastAction) = CastRenderModel(
        title = "t",
        status = "s",
        operationAcknowledged = false,
        stopAcknowledgementTimedOut = false,
        durableStatusPriority = false,
        actions = CastAction.entries.map { CastRenderAction(it, it in allowed, null) },
    )

    @Test
    fun `dang cho van giu Stop va Chan doan`() {
        val actions = model(CastAction.OPEN_DIAGNOSTICS, CastAction.SELECT_TARGET_APP, CastAction.CAST)
            .activityActions(CastUiMutationSnapshot(1L, pending = true))
        assertTrue(CastAction.STOP in actions, "mất đường thoát")
        assertTrue(CastAction.OPEN_DIAGNOSTICS in actions, "mất Chẩn đoán trong lúc chờ")
        assertTrue(CastAction.SELECT_TARGET_APP in actions, "mất quyền chọn app trong lúc chờ")
    }

    @Test
    fun `dang cho thi chan moi phep phat lenh moi`() {
        val actions = model(CastAction.CAST, CastAction.SWITCH, CastAction.ADJUST, CastAction.RETRY_CONNECT)
            .activityActions(CastUiMutationSnapshot(1L, pending = true))
        setOf(CastAction.CAST, CastAction.SWITCH, CastAction.ADJUST, CastAction.RETRY_CONNECT).forEach {
            assertTrue(it !in actions, "$it không được phát khi đang chờ")
        }
    }

    @Test
    fun `khong cho thi tra nguyen tap phep`() {
        val allowed = setOf(CastAction.CAST, CastAction.OPEN_DIAGNOSTICS)
        assertEquals(
            allowed,
            model(*allowed.toTypedArray()).activityActions(CastUiMutationSnapshot(1L, pending = false)),
        )
    }

    @Test
    fun `khong bao gio tra ve tap rong`() {
        // Quét mọi tập con cỡ ≤2 của các phép, ở cả hai trạng thái chờ: không tổ hợp nào để lại màn
        // hình trắng tay. Đây là mệnh đề mà bản so-bằng-tập-cứng không thể bảo đảm.
        var checked = 0
        CastAction.entries.forEach { a ->
            CastAction.entries.forEach { b ->
                listOf(false, true).forEach { pending ->
                    checked++
                    val actions = model(a, b).activityActions(CastUiMutationSnapshot(1L, pending))
                    assertTrue(actions.isNotEmpty(), "tập rỗng với a=$a b=$b pending=$pending")
                }
            }
        }
        assertTrue(checked >= CastAction.entries.size * CastAction.entries.size * 2)
    }
}

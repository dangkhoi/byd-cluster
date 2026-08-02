package com.byd.clusternav.modules.clustercast

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Khoá lại bẫy round-1 nghi ngờ ở `CastFacade.applyGeometry`: `CastAdjustmentWorkspace.beginApply`
 * KHÔNG luôn trả `AdjustmentResult.Rejected` khi từ chối — khi `CastGeometry.validate` từ chối
 * (GEOMETRY_LIMITED/PROTECTED_SESSION) hoặc khi identity đổi giữa chừng (FROZEN), nó trả về
 * `AdjustmentResult.Ready` bọc một draft CHƯA chuyển sang `APPLYING`, kèm `validationError`. Xem
 * `CastAdjustmentWorkspace.kt` (`beginApply`, `frozen()`).
 *
 * `CastAdjustmentWorkspaceTest.beginApply rejects invalid geometry and protected residue through
 * Ready, not Rejected` (module :core) khoá đúng hành vi đó ở tầng workspace. Test này khoá tầng gọi:
 * `CastFacade.applyGeometry` không được coi "không phải Rejected" là "an toàn để plan/execute" — nó
 * phải đọc `applyState` thật của draft trước khi phát bất kỳ lệnh mutating nào (APPLY_TASK_GEOMETRY).
 *
 * Không dựng được `CastFacade` thật trong test off-device (constructor phụ thuộc `android.content.Context`
 * qua `CastAppCatalog`, dự án không có Robolectric/mockk) nên test neo vào nguồn, cùng khuôn với
 * `CastFacadeBoundaryTest`. Nếu ai sửa lại `applyGeometry` về mẫu ép kiểu ngây thơ
 * `(beginApply(...) as? AdjustmentResult.Rejected)?.let { return ... }` mà không kiểm `applyState`,
 * test này fail ngay.
 */
class CastFacadeApplyGeometryGuardTest {

    private val source: String by lazy {
        listOf(
            "app/src/main/java/com/byd/clusternav/modules/clustercast/CastFacade.kt",
            "src/main/java/com/byd/clusternav/modules/clustercast/CastFacade.kt",
        ).map(Paths::get).first(Files::exists).toFile().readText()
    }

    private fun applyGeometryBody(): String {
        val start = source.indexOf("fun applyGeometry(")
        assertTrue(start >= 0, "CastFacade.applyGeometry không còn tồn tại")
        val end = source.indexOf("\n    fun ", start + 1)
        assertTrue(end > start, "Không tách được thân applyGeometry để kiểm tra")
        return source.substring(start, end)
    }

    @Test
    fun `applyGeometry gates on the draft's real applyState, not just the Rejected type`() {
        val body = applyGeometryBody()

        val beginApplyCall = body.indexOf("beginApply(observed)")
        assertTrue(beginApplyCall >= 0, "applyGeometry phải gọi runtime.adjustment.beginApply(observed)")

        val applyingGuard = Regex("""applyState\s*!=\s*AdjustmentApplyState\.APPLYING""")
        val guardMatch = applyingGuard.find(body)
        assertTrue(
            guardMatch != null,
            "applyGeometry phải kiểm draft.applyState != AdjustmentApplyState.APPLYING sau beginApply — " +
                "chỉ ép kiểu as? AdjustmentResult.Rejected bỏ sót nhánh Ready-bọc-từ-chối (GEOMETRY_LIMITED/" +
                "PROTECTED_SESSION/FROZEN) và để lọt một lệnh mutating xuống execute().",
        )
        assertTrue(
            guardMatch!!.range.first > beginApplyCall,
            "Guard applyState phải nằm SAU lời gọi beginApply, không phải trước",
        )

        val executeCall = body.indexOf("execute(plan, pkg)")
        assertTrue(executeCall > 0, "applyGeometry phải phát qua execute(plan, pkg)")
        assertTrue(
            guardMatch.range.first < executeCall,
            "Guard applyState phải chặn TRƯỚC khi plan được thi hành (execute(plan, pkg)), " +
                "không phải sau — nếu không, mutating command đã kịp phát trước khi nhánh từ chối có tác dụng",
        )
    }
}

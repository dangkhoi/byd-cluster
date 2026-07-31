package com.byd.clusternav.modules.clustercast.v2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Khoá lỗi đo trên DiLink3 2026-07-30 (docs/diagnostics/cast-stop-recovering-stuck-2026-07-30.md): cast
 * VietMap lên cụm thành công thật (thấy trên cụm), nhưng `am start` ghi ra stderr đúng một dòng cảnh báo
 * lành ("task đã có sẵn, mang lên trước") mà `classifyMutationShellResult` cũ coi là UnknownEffect —
 * đẩy transaction đã thành công sang RECOVERING, rồi kẹt vĩnh viễn vì epoch bền trôi qua trước khi
 * recovery kịp chạy. Nút nổi từ đó chỉ hiện "Đang xử lý" mãi mỗi lần bấm Dừng.
 */
class CastMutationEffectClassificationTest {
    private val brought = "Warning: Activity not started, its current task has been brought to the front"
    private val deliveredToTop = "Warning: Activity not started, intent has been delivered to currently running top-most instance."

    @Test
    fun `exact benign task-brought-to-front warning is Observed, not UnknownEffect`() {
        val result = classifyMutationShellResult(0, "", "$brought\n")
        assertTrue(result is MutationResult.Observed, "expected Observed, got $result")
    }

    @Test
    fun `benign warning without trailing newline is still Observed`() {
        val result = classifyMutationShellResult(0, "some stdout", brought)
        assertEquals(MutationResult.Observed("some stdout | $brought"), result)
    }

    /**
     * Không được coi là lỗi, nhưng cũng KHÔNG được nuốt mất: chuỗi này là dấu duy nhất phân biệt "task cũ
     * được mang lên trước" với "khởi tạo mới", và chính nó là đầu mối tìm ra bug 2026-07-30. Evidence chỉ
     * đi vào CastOperationLog (CastExecutor.resultLabel), nên khoá nó ở đây là khoá đúng đường chẩn đoán.
     */
    @Test
    fun `benign warning stays visible in the Observed evidence for the operation log`() {
        val result = classifyMutationShellResult(0, "Starting: Intent { cmp=vn.vietmap.live/.MainActivity }", "$brought\n")
        assertEquals(
            MutationResult.Observed("Starting: Intent { cmp=vn.vietmap.live/.MainActivity } | $brought"),
            result,
        )
        assertEquals(
            MutationResult.Observed(brought),
            classifyMutationShellResult(0, "", "$brought\n"),
            "stdout rỗng thì evidence không được có dấu phân cách lửng",
        )
    }

    @Test
    fun `blank stderr evidence is untouched, exactly as before the whitelist`() {
        assertEquals(
            MutationResult.Observed("Starting: Intent { cmp=a/.B }\n"),
            classifyMutationShellResult(0, "Starting: Intent { cmp=a/.B }\n", ""),
        )
    }

    @Test
    fun `blank stderr remains Observed as before`() {
        assertEquals(MutationResult.Observed("ok"), classifyMutationShellResult(0, "ok", ""))
    }

    /**
     * Chuẩn hoá bằng `trim()` chứ không so bằng thô, và điều đó phải được khoá lại: dump thật trên DiLink3
     * trả về đúng dòng này kèm `\n` cuối, nhưng khoảng trắng đầu/cuối không phải hằng số của môi trường —
     * có bản AOSP in kèm `\n` ngay trước "Warning", và nếu transport rơi về shell v1 (có pty) thì xuống
     * dòng thành `\r\n`. Cả hai biến thể vẫn là ĐÚNG một dòng cảnh báo lành, không phải lỗi mới.
     */
    @Test
    fun `surrounding whitespace variants of the same single line are still Observed`() {
        listOf("\n$brought\n", "$brought\r\n", "\r\n$brought\r\n", "  $brought  ").forEach { stderr ->
            assertTrue(
                classifyMutationShellResult(0, "", stderr) is MutationResult.Observed,
                "expected Observed for stderr=${stderr.replace("\n", "\\n").replace("\r", "\\r")}",
            )
        }
    }

    @Test
    fun `nonzero exit code stays UnknownEffect even with the benign warning text`() {
        val result = classifyMutationShellResult(1, "", brought)
        assertTrue(result is MutationResult.UnknownEffect, "expected UnknownEffect, got $result")
    }

    @Test
    fun `benign warning mixed with other stderr content is NOT whitelisted`() {
        val result = classifyMutationShellResult(0, "", "$brought\nException: something else went wrong")
        assertTrue(result is MutationResult.UnknownEffect, "expected UnknownEffect, got $result")
    }

    /**
     * Khoá lỗi đo trên DiLink3 2026-07-31: cast Google Maps xong, `am stack list` xác nhận task ĐÃ
     * visible=true trên display-1 (cụm) đúng lúc REASSERT_ON_CLUSTER gửi lại intent — nhưng vì task đã
     * sẵn ở đó, `am start` in đúng dòng này ra stderr thay vì "brought to the front", và trước khi có
     * whitelist thứ hai này, transaction kẹt RECOVERING y hệt bug 2026-07-30, dù cast đã thành công thật.
     */
    @Test
    fun `delivered-to-top-most-instance warning is Observed, not UnknownEffect`() {
        val result = classifyMutationShellResult(0, "", "$deliveredToTop\n")
        assertTrue(result is MutationResult.Observed, "expected Observed, got $result")
    }

    @Test
    fun `both known benign warnings are independently recognized, not just the first one added`() {
        assertTrue(classifyMutationShellResult(0, "", brought) is MutationResult.Observed)
        assertTrue(classifyMutationShellResult(0, "", deliveredToTop) is MutationResult.Observed)
    }

    /**
     * Khoá QUYẾT ĐỊNH THIẾT KẾ của docs/specs/cast-recovery-honesty-and-multi-occupant.html §R1: đo trên
     * DiLink3 2026-07-31, cast CarPlay (`PROJECTION_SINK`) — `am task resize` bị từ chối, in nguyên văn
     * dòng dưới ra stderr. KHÔNG được thêm chuỗi này vào whitelist ở đây — nó chứa `TaskRecord{c924b0c
     * #24 ...}` đổi mỗi lần gọi nên không match chính xác được, và match lỏng (contains/prefix) có thể
     * che một lỗi resize thật khác. Sửa đúng phải ở `CastPlacementCommands.kt` (bọc nhánh resize bằng
     * `2>/dev/null`), KHÔNG phải ở đây — nên test này cố tình khoá stderr này VẪN LÀ UnknownEffect.
     */
    @Test
    fun `carplay resize-rejected exception is NOT whitelisted here — the fix belongs in the command, not the classifier`() {
        val resizeRejected = "\nException occurred while executing:\n" +
            "java.lang.IllegalArgumentException: resizeTask not allowed on task=TaskRecord{c924b0c " +
            "#24 A=com.byd.carplay.ui U=0 StackId=24 sz=3}\n" +
            "\tat com.android.server.wm.Activ"
        val result = classifyMutationShellResult(0, "", resizeRejected)
        assertTrue(
            result is MutationResult.UnknownEffect,
            "expected UnknownEffect (this stderr must never be whitelisted here), got $result",
        )
    }

    @Test
    fun `unrelated real stderr remains UnknownEffect`() {
        val result = classifyMutationShellResult(0, "", "java.lang.SecurityException: Permission Denial")
        assertTrue(result is MutationResult.UnknownEffect, "expected UnknownEffect, got $result")
        assertEquals(
            "java.lang.SecurityException: Permission Denial",
            (result as MutationResult.UnknownEffect).reason,
        )
    }
}

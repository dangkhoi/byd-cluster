package com.byd.clusternav.modules.clustercast

import com.byd.clusternav.modules.clustercast.v2.ProjectionSessionEvidenceParser
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Khoá fix `phone-disconnect-evidence-carplay` 2026-07-28 tại `CastActivityRefreshReader.read()`.
 *
 * Trước fix: `dumpsys activity services <hostPackage>` không bao giờ khớp field phone-session cho
 * CHÍNH package host CarPlay/Android Auto (xác nhận bằng dump thật 2026-07-23 dưới đây), nên
 * `phoneConnected` luôn `null` cho hai target chủ lực này — và `null` bị `CastRuntimeUi.render`/
 * `CastUiStateProjector.project` coi như "chưa biết gì", rơi vào `failClosed` (MANUAL_REQUIRED) vĩnh
 * viễn khi `stableSession.state == RECOVERY_PENDING`. Xem doc comment
 * `resolveProjectionPhoneConnected` (CastActivityRefresh.kt) để biết fix chính xác.
 */
class CastActivityRefreshPhoneConnectedTest {

    private fun realDump(name: String): String {
        val roots = listOf(
            "docs/diagnostics/carlog-2026-07-23-trace/live",
            "../docs/diagnostics/carlog-2026-07-23-trace/live",
        ).map(Paths::get)
        val root = roots.firstOrNull(Files::exists) ?: error("không tìm thấy thư mục fixture; đã thử $roots")
        return root.resolve(name).toFile().readText()
    }

    @Test
    fun `real CarPlay and Android Auto host dumps still parse to an unmeasurable null`() {
        // Chính bản thân parser không đổi -- vẫn không bao giờ đo được cho package host. Đây là điều
        // KHÔNG được vá (fix nằm ở cách dùng kết quả null, không phải ở parser).
        assertNull(ProjectionSessionEvidenceParser.parse(realDump("08-svc-carplay.txt")))
        assertNull(ProjectionSessionEvidenceParser.parse(realDump("09-svc-androidauto.txt")))
    }

    @Test
    fun `unmeasurable raw reading resolves to still-connected instead of the old dead end`() {
        assertTrue(resolveProjectionPhoneConnected(null))
    }

    @Test
    fun `a confirmed-true raw reading still resolves to connected`() {
        assertTrue(resolveProjectionPhoneConnected(true))
    }

    @Test
    fun `only a positively confirmed false raw reading resolves to disconnected`() {
        assertFalse(resolveProjectionPhoneConnected(false))
    }

    @Test
    fun `real host dumps feed through the reader exactly like the confirmed dead-end case`() {
        // End-to-end trên dữ liệu thật: parse(dump) == null -> resolve == true (còn nối), không phải
        // null (ngõ cụt). Đây đúng là chuỗi mà CastActivityRefreshReader.read() chạy trên xe thật.
        val carplayRaw = ProjectionSessionEvidenceParser.parse(realDump("08-svc-carplay.txt"))
        val androidAutoRaw = ProjectionSessionEvidenceParser.parse(realDump("09-svc-androidauto.txt"))
        assertTrue(resolveProjectionPhoneConnected(carplayRaw))
        assertTrue(resolveProjectionPhoneConnected(androidAutoRaw))
    }
}

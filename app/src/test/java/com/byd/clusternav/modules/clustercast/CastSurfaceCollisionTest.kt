package com.byd.clusternav.modules.clustercast

import com.byd.clusternav.testsupport.SourceRoots
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Ba bề mặt cùng có thể phát một cú chiếu từ v0.72: nút nổi (người chạm), panel Cast của Home
 * (`onCreate`/`onResume` tự chiếu) và tự-chiếu-lúc-khởi-động (`CastAutomationService`). Chúng dùng CHUNG
 * một control plane (`CastAndroidRuntime.create` là singleton của tiến trình) nên `CastExecutor` tuần tự
 * hoá được mọi lượt ghi — nhưng tuần tự hoá KHÔNG trả lời câu "ai được phép giành cụm của ai".
 *
 * Test này khoá đúng ba câu trả lời tìm được ở review vòng 3 (2026-07-30,
 * docs/specs/cast-simplified-active-app-toggle.html). Đây là test quét-source (module này không chạy
 * Robolectric) — phần hành vi thật của cửa BOOT_AUTO nằm ở
 * `:core` `CastBootAutomationLiveSessionTest`.
 */
class CastSurfaceCollisionTest {

    private fun source(relative: String): String {
        val direct = Paths.get("app/src/$relative")
        val nested = SourceRoots.path("src/$relative")
        return (if (Files.exists(direct)) direct else nested).toFile().readText()
    }

    private val bubble = source("main/java/com/byd/clusternav/modules/clustercast/FloatingBubbleService.kt")
    private val service = source("main/java/com/byd/clusternav/modules/clustercast/CastAutomationService.kt")
    private val autoStart = source("main/java/com/byd/clusternav/modules/clustercast/CastActivityAutoStart.kt")

    /**
     * Nút nổi không được TẠO ra một `pendingIntent` gốc USER bền.
     *
     * `CastManualIntentRunner.run` không từ chối khi có transaction — nó xếp hàng, và
     * `CastSessionStore.initializeForBoot` cố ý giữ pendingIntent gốc USER qua mọi lần khởi động, còn
     * `CastAutomationService.evaluate` terminalize yêu cầu tự-chiếu thành USER_SUPERSEDED khi thấy nó.
     * Chỗ rút hàng đợi duy nhất là `MainActivityCastController.drainPendingTarget()` lúc MỞ Home; nút nổi
     * là service sống mãi, không có khoảnh khắc đó. Nên một cú chạm rơi trúng lúc bận sẽ tắt R7 cho mọi
     * lần khởi động sau, cho tới khi ai đó mở lại Home.
     */
    @Test
    fun `the bubble refuses instead of queueing a durable placement it can never drain`() {
        assertTrue(bubble.contains("private fun foreignMutationInFlight()"), "the durable busy read must exist")
        assertTrue(bubble.contains("facade.envelope()?.transaction != null"), "busy means an open journal, not a RAM flag")
        val tap = bubble.substringAfter("private fun onPrimaryTap()").substringBefore("private fun foreignMutationInFlight")
        val guard = tap.indexOf("foreignMutationInFlight()")
        val dispatch = tap.indexOf("dispatchTarget(foreground)")
        assertTrue(guard in 1 until dispatch, "the refusal must come before any dispatch, got $guard/$dispatch")
        assertTrue(tap.contains("Đang có thao tác khác chạy"), "a refused tap must say so, never fail silently")
    }

    /**
     * Tự-chiếu-lúc-khởi-động không được cướp cụm khỏi thứ người lái vừa đặt lên đó (cùng luật mà R6 đã
     * ghi cho tự-chiếu-khi-mở-app). Kết cục phải được GHI là SUPERSEDED/USER_SUPERSEDED — "người dùng
     * tới trước", không phải một thất bại — và phải nằm TRƯỚC `settings.claim(...)` để không tiêu mất
     * ngân sách claim duy nhất của lần khởi động đó.
     */
    @Test
    fun `boot automation stands down when the driver already placed something on the cluster`() {
        assertTrue(service.contains("envelope.stableSession?.activeTarget != null"))
        val gate = service.indexOf("envelope.stableSession?.activeTarget != null")
        val claim = service.indexOf("settings.claim(requestId)")
        assertTrue(gate in 1 until claim, "the stand-down must precede the claim, got $gate/$claim")
        val branch = service.substringAfter("envelope.stableSession?.activeTarget != null").substringBefore("if (abandoned)")
        assertTrue(branch.contains("AutomationRequestState.SUPERSEDED"))
        assertTrue(branch.contains("AutomationReason.USER_SUPERSEDED"))
    }

    /**
     * Ô tick tự-chiếu chạy trên `CastActivityWork.misc`, tức pool HAI luồng — không serialize. Đọc-sửa-ghi
     * cấu hình phải nằm dưới một khoá, nếu không tick rồi bỏ tick nhanh tay có thể để lại cấu hình BẬT
     * trong khi thao tác cuối của người dùng là TẮT.
     */
    @Test
    fun `automation config writes are serialized against the two-thread misc lane`() {
        assertTrue(autoStart.contains("synchronized(configWriteLock)"), "config writes must hold one lock")
        val commit = autoStart.substringAfter("private fun commitConfig")
        listOf("automation.setDefault(pkg)", "automation.accept()", "automation.revoke()").forEach {
            assertTrue(commit.contains(it), "$it must live inside the serialized commit")
        }
        val apply = autoStart.substringAfter("private fun apply(").substringBefore("private fun commitConfig")
        listOf("automation.setDefault", "automation.accept()", "automation.revoke()").forEach {
            assertTrue(!apply.contains(it), "$it must not be written outside the lock")
        }
    }
}

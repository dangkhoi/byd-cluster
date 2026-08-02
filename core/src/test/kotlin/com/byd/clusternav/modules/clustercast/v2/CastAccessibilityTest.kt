package com.byd.clusternav.modules.clustercast.v2

import com.byd.clusternav.testsupport.SourceRoots
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 2026-07-29: hai test dưới đây trước đó trỏ vào `activity_cluster_cast.xml` và `ClusterCastActivity.kt`
 * — cả hai đã bị xoá khi Cluster Cast gộp vào panel bên phải Home
 * (docs/specs/cast-simplified-active-app-toggle.html). Chúng KHÔNG chỉ được đổi tên: điều đáng khoá vẫn
 * y nguyên (mọi control chạm được phải đủ lớn, phải có nhãn cho TalkBack/núm xoay, và tuyệt đối không có
 * cử chỉ ẩn), chỉ là bề mặt mang nó đã đổi.
 */
class CastAccessibilityTest {
    @Test
    fun `Home cast panel and Bubble expose labeled, large-enough controls`() {
        val layout = source("main/res/layout/activity_main.xml")
        val bubble = source("main/java/com/byd/clusternav/modules/clustercast/FloatingBubbleService.kt")
        val rows = source("main/java/com/byd/clusternav/modules/clustercast/CastAppRows.kt")
        // Nhóm nút khắc phục sự cố của Home: 56dp + contentDescription cho từng nút.
        assertTrue(layout.contains("android:minHeight=\"56dp\""))
        assertTrue(layout.contains("android:contentDescription"))
        // Mọi nút Cast trong layout phải có nhãn đọc được, không nút nào chỉ có ký hiệu.
        Regex("<Button[^>]*android:id=\"@\\+id/(cast_[a-z_]+)\"[^>]*>", RegexOption.DOT_MATCHES_ALL)
            .findAll(layout)
            .forEach { match ->
                assertTrue(
                    match.value.contains("android:contentDescription"),
                    "${match.groupValues[1]} thiếu contentDescription",
                )
            }
        // Panel kích thước dựng bằng code: nút cao tối thiểu 52dp và contentDescription ghép từ
        // "<nhóm> <nhãn>", vì nhãn hiển thị là ký hiệu (◀ ▲ ▼ ▶ ↺) — TalkBack không đọc được ký hiệu.
        assertTrue(rows.contains("minimumHeight = dp(context, 52)"))
        assertTrue(rows.contains("contentDescription = \"\$title \$caption\""))
        // Nút nổi: mỗi ô 28dp compact, có nhãn, không cử chỉ ẩn.
        assertTrue(bubble.contains("BUBBLE_SIZE_DP = 30"))
        assertTrue(bubble.contains("minimumHeight = dp(minDp)"))
        assertTrue(bubble.contains("contentDescription"))
        assertFalse(bubble.contains("setOnLongClickListener"))
    }

    @Test
    fun `Home has a base and a wide adaptive layout, both carrying the whole cast panel`() {
        val variants = listOf(
            "main/res/layout/activity_main.xml",
            "main/res/layout-w960dp/activity_main.xml",
        )
        variants.forEach { relative -> assertTrue(Files.exists(path(relative)), relative) }
        // Màn Cast riêng đã xoá — không được còn biến thể nào của nó sót lại làm tài nguyên mồ côi.
        listOf(
            "main/res/layout/activity_cluster_cast.xml",
            "main/res/layout-w960dp/activity_cluster_cast.xml",
            "main/res/layout-w1280dp/activity_cluster_cast.xml",
        ).forEach { relative -> assertFalse(SourceRoots.exists("src/$relative"), relative) }
        // Cả hai biến thể phải mang ĐỦ panel Cast, không phải chỉ bản dọc — đúng lớp lỗi 2026-07-29
        // (một biến thể thiếu id mà Activity tra cứu vô điều kiện thì chỉ crash trên đúng đời màn đó).
        val required = listOf(
            "cast_geometry_container", "cb_autostart", "spinner_autostart_app",
            "cast_recovery_toggle", "cast_recovery_actions", "txt_cast_status",
            "cast_stop", "cast_diagnostics", "cast_retry", "cast_phone_disconnect",
            "cast_recover_once", "cast_physical_instruction", "cast_profile_setup",
        )
        variants.forEach { relative ->
            val markup = source(relative)
            required.forEach { id -> assertTrue(markup.contains("@+id/$id\""), "$relative thiếu @+id/$id") }
        }
    }

    private fun source(relative: String) = path(relative).toFile().readText()
    private fun path(relative: String): Path = SourceRoots.path("src/$relative")
}

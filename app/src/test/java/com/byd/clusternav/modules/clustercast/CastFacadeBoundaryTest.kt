package com.byd.clusternav.modules.clustercast

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Bánh răng một chiều cho ranh giới UI ↔ máy móc Cast.
 *
 * Test này ghim đúng tập file còn chạm trực tiếp vào `runtime.coordinator` / `runtime.store` /
 * `runtime.gateway`. Thêm một file mới vào tập đó là fail ngay. Dời một file ra khỏi tập thì phải sửa
 * danh sách xuống — nên tiến độ nhìn thấy được, và ranh giới không thể rò lại một cách im lặng.
 *
 * Khởi điểm 2026-07-27 là 5 file; khởi điểm 5 file, kết thúc B5 còn 0. Từ giờ bất kỳ file nào chạm lại đều fail.
 */
class CastFacadeBoundaryTest {

    // 2026-07-27: đạt 0. Giữ tập rỗng để mọi lần chạm lại vào máy móc đều fail ngay.
    private val stillReachingIn = emptySet<String>()

    @Test
    fun `only the recorded files still reach into cast machinery`() {
        val root = listOf("app/src/main/java", "src/main/java", "../app/src/main/java")
            .map(Paths::get).first(Files::exists)
        val offenders = Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .filter { !NON_UI.any { part -> it.toString().contains(part) } }
                .filter { it.fileName.toString() != "CastFacade.kt" }
                .filter {
                    val text = it.toFile().readText()
                    text.contains("runtime.coordinator") ||
                        text.contains("runtime.store") ||
                        text.contains("runtime.gateway")
                }
                .toList()
        }.map { it.fileName.toString() }.toSet()
        assertEquals(
            stillReachingIn,
            offenders,
            "ranh giới đã đổi: cập nhật danh sách xuống nếu đã dời bớt, hoặc đưa file mới qua CastFacade",
        )
    }

    @Test
    fun `facade exposes reads and completion without leaking the runtime`() {
        val source = listOf(
            "app/src/main/java/com/byd/clusternav/modules/clustercast/CastFacade.kt",
            "src/main/java/com/byd/clusternav/modules/clustercast/CastFacade.kt",
        ).map(Paths::get).first(Files::exists).toFile().readText()
        listOf("fun observe(", "fun storeRead(", "fun phoneSession(", "fun observeAndComplete(").forEach {
            assertTrue(source.contains(it), "façade thiếu $it")
        }
        // Runtime phải là private: nếu expose ra thì façade chỉ là cái cửa sổ, không phải ranh giới.
        assertTrue(source.contains("private val runtime"))
        assertTrue(!source.contains("val runtime: CastAndroidRuntime.Runtime\n"))
    }

    private val NON_UI = listOf("/modules/clustercast/v2/", "/cast/platform/", "/cast/transport/")
}

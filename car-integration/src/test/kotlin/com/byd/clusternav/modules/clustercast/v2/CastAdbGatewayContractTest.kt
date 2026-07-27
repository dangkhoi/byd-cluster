package com.byd.clusternav.modules.clustercast.v2

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Hợp đồng của tầng transport sau khi tách khỏi Android ngày 2026-07-27.
 *
 * Trước đó lớp này nhận thẳng một `Context` và tự gọi `AdbKeys.ensure(context)`; vì đúng hai chỗ đó mà
 * toàn bộ transport bị khoá vào Android và runner không thể chạy mà không dựng APK.
 */
class CastAdbGatewayContractTest {

    private val source: String by lazy {
        listOf(
            "car-integration/src/main/kotlin/com/byd/clusternav/modules/clustercast/v2/CastAdbGateway.kt",
            "src/main/kotlin/com/byd/clusternav/modules/clustercast/v2/CastAdbGateway.kt",
            "../car-integration/src/main/kotlin/com/byd/clusternav/modules/clustercast/v2/CastAdbGateway.kt",
        ).map(Paths::get).first(Files::exists).toFile().readText()
    }

    /**
     * Chỉ soi phần code. Comment ở đầu file có nhắc tên thiết kế cũ (Context, AdbKeys) một cách cố ý —
     * đó là ghi chép vì sao ranh giới này tồn tại, không phải vi phạm.
     */
    private val code: String by lazy {
        source.lines().filterNot {
            val t = it.trim()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }.joinToString("\n")
    }

    @Test
    fun `transport khong duoc biet Android`() {
        assertFalse(code.contains("import android."))
        assertFalse(code.contains("Context"))
        assertFalse(code.contains("AdbKeys"))
    }

    @Test
    fun `khoa va phep do display duoc truyen vao chu khong tu lay`() {
        assertTrue(source.contains("private val keys: () -> AdbKeyPair"))
        assertTrue(source.contains("private val measureCluster: () -> NamedClusterDisplay?"))
        assertTrue(source.contains("keys()"))
    }

    @Test
    fun `diem den mac dinh la loopback de app khong doi hanh vi`() {
        assertTrue(source.contains("""private val host: String = "localhost""""))
        assertTrue(source.contains("private val port: Int = 5555"))
    }
}

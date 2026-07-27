package com.byd.clusternav.carexec

import com.byd.clusternav.modules.clustercast.v2.CastAmStackParser
import com.byd.clusternav.modules.clustercast.v2.CastDumpParse
import com.byd.clusternav.modules.clustercast.v2.discoverClusterDisplay
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Kiểm bộ parse trên **output thật của head unit**, không phải trên chuỗi tự bịa.
 *
 * Lý do tồn tại rất cụ thể: 2026-07-27 09:33 tôi chạy `am stack list` rồi grep bằng tay, pattern không
 * khớp nên không có output, và tôi kết luận "cụm sạch" — trong khi VietMap đang nằm trên cụm. Fixture
 * `am-stack-list-occupied.txt` chính là output của lần đó. Nếu parser có test trên file này thì loại sai
 * ấy không lặp lại được.
 */
class RealFixtureParsingTest {

    private fun fixture(name: String): String {
        val roots = listOf(
            "docs/refactor-car-execution/fixtures",
            "../docs/refactor-car-execution/fixtures",
        ).map(Paths::get)
        val root: Path = roots.firstOrNull(Files::exists)
            ?: error("không tìm thấy thư mục fixture; đã thử $roots")
        return root.resolve(name).toFile().readText()
    }

    @Test
    fun `stack list luc cum bi chiem doc ra dung occupant`() {
        val raw = fixture("am-stack-list-occupied.txt")
        val parsed = CastAmStackParser.parse(raw)
        assertTrue(parsed is CastDumpParse.Known, "parse thất bại trên output thật: $parsed")
        val snapshot = (parsed as CastDumpParse.Known).value
        val onCluster = snapshot.tasks.filter { it.displayId == 1 }
        assertTrue(onCluster.isNotEmpty(), "output thật CÓ task trên display 1 mà parser không thấy")
        assertTrue(
            onCluster.any { it.packageName == "vn.vietmap.live" },
            "phải nhận ra vn.vietmap.live trên cụm; thấy: ${onCluster.map { it.packageName }}",
        )
        assertTrue(onCluster.any { it.taskId == 26 }, "taskId 26 nằm trong output thật")
    }

    @Test
    fun `stack list luc cum rong khong bao co occupant`() {
        val parsed = CastAmStackParser.parse(fixture("am-stack-list-clean.txt"))
        assertTrue(parsed is CastDumpParse.Known)
        val onCluster = (parsed as CastDumpParse.Known).value.tasks.filter { it.displayId == 1 }
        assertEquals(emptyList<Any>(), onCluster, "output thật lúc rỗng mà parser lại thấy occupant")
    }

    @Test
    fun `nhan dien display cum tu dumpsys that`() {
        val display = discoverClusterDisplay(fixture("dumpsys-display-occupied.txt"))
        assertNotNull(display, "không nhận ra display cụm trong dumpsys thật")
        assertEquals(1, display!!.id)
        assertEquals(1920, display.appWidth)
        assertEquals(720, display.appHeight)
        assertEquals(320, display.densityDpi)
        assertTrue(display.name.contains("fission", ignoreCase = true), display.name)
    }

    @Test
    fun `dumpsys luc chieu dong van thay display cum`() {
        // Đây là bằng chứng cho một kết luận đã ghi: display cụm LUÔN tồn tại, nên sự tồn tại của nó
        // không phải tín hiệu "đang chiếu".
        val display = discoverClusterDisplay(fixture("dumpsys-display-projection-CLOSED.txt"))
        assertNotNull(display, "display cụm phải vẫn tồn tại khi chiếu đã đóng")
        assertEquals(1, display!!.id)
    }

    @Test
    fun `layer bydAdd ton tai ca khi chieu dang dong`() {
        // Cũng là bằng chứng cho một giả định đã bị loại: bydAdd-<pkg> KHÔNG phải tín hiệu chiếu.
        val closed = fixture("sf-layers-projection-CLOSED.txt")
        assertTrue(closed.contains("bydAdd-com.byd.clusternav"), "fixture phải chứa layer bydAdd lúc đóng")
    }

    @Test
    fun `numLayers cua display cum bang 0 khi chieu dang dong`() {
        val full = fixture("sf-FULL-projection-CLOSED.txt")
        val block = full.lines().dropWhile { !it.contains("fission_bg_xdjaVirtualSurface\"") || !it.contains("DisplayDevice") }
        assertTrue(block.isNotEmpty(), "không thấy khối DisplayDevice của cụm trong dump thật")
        val numLayers = block.take(4).firstNotNullOfOrNull {
            Regex("""numLayers=(\d+)""").find(it)?.groupValues?.get(1)
        }
        assertEquals("0", numLayers, "lúc chiếu đóng, cụm không composite layer nào")
    }
}

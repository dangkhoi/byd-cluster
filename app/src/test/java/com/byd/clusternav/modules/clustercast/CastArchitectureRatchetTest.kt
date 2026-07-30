package com.byd.clusternav.modules.clustercast

import com.byd.clusternav.cast.platform.CastAndroidLifecycle
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Bánh răng một chiều cho hai con số thật của việc phân tầng.
 *
 * Vì sao test này tồn tại: bánh răng trước đó chỉ đếm các lời gọi `runtime.coordinator/store/gateway`,
 * nên khi nó về 0 tôi đã báo cáo là "ranh giới đã đạt". Review đối kháng 2026-07-27 cho thấy điều đó
 * sai ở hai mặt cùng lúc — UI vẫn phụ thuộc 42 kiểu của tầng dưới, và vẫn còn những đường adb đi tắt
 * trong app mà bánh răng cũ không nhìn thấy. Bài học: đo đúng thứ mình tuyên bố, không đo thứ dễ đo.
 *
 * Hai con số dưới đây chỉ được phép **giảm**. Tăng là fail. Giảm thì phải sửa số xuống, nên tiến độ
 * không thể tự bốc hơi và cũng không thể nói quá.
 */
class CastArchitectureRatchetTest {

    /**
     * Số kiểu của tầng dưới mà UI còn import trực tiếp. Façade chỉ chặn được lời gọi; chừng nào con số
     * này còn lớn thì UI vẫn buộc chặt vào hình dạng bên trong.
     *
     * 28 → 14 ngày 2026-07-29: xoá màn Cấu hình riêng (`ClusterCastActivity`), `CastAppManagerDialog`,
     * `CastAppManagerBinding`, `CastAppListView` và `CastAdjustmentDialog` (mồ côi, không còn ai gọi
     * sau khi màn Cấu hình bị xoá) khi gộp Cluster Cast vào panel Home — xem
     * docs/specs/cast-simplified-active-app-toggle.html.
     */
    private val uiTypeCoupling = 14

    /**
     * Số kiểu **của tầng dưới** (`:core` / `:car-integration`) mà UI còn import trực tiếp.
     *
     * Phép đo đầu tiên của tôi đếm theo package nên gộp cả những lớp vẫn sống trong `:app`
     * (`CastAndroidRuntime`, `CastAppCatalog`, `CastAndroidLifecycle`) — UI dùng chúng là app→app, không
     * phải vượt tầng. Con số này mới là khoảng cách thật tới lời tuyên bố "UI chỉ thấy façade".
     *
     * 28 → 14 ngày 2026-07-29: cùng lý do với uiTypeCoupling ở trên — xoá màn Cấu hình riêng và các
     * dialog/list-view mồ côi khi gộp Cluster Cast vào panel Home.
     */
    private val crossLayerCoupling = 14

    /**
     * Số chỗ mở kết nối adb nằm ngoài :car-integration. Kiến trúc nói mọi transport thuộc một chỗ; đây
     * là khoảng cách còn lại tới lời tuyên bố đó.
     *
     * 13 → 9 ngày 27/7: bốn chỗ lẻ (`NavConnect` tự chữa listener, `ClusterDiag`, `MockLoc` tự cấp quyền — `MockLoc` đã bị xoá hẳn cuối ngày,
     * `UpdateChecker` cài bản mới) đã đi qua `LocalDeviceShell` trong `:car-integration`. Trình tự được giữ
     * nguyên bằng API `session` vì `NavConnect` ngủ 1.5 giây giữa hai lệnh trên cùng phiên — đổi thành hai
     * phiên rời là đổi hành vi của một đường tự-chữa vốn mong manh.
     *
     * 9 → 8 cùng ngày: `DadbBridge` giữ phiên lâu dài nên được đưa sang `:car-integration` thành
     * `PersistentDeviceShell`. Rồi cuối ngày cả chuỗi đó bị XOÁ: người dùng duy nhất của nó là module
     * `vd_map`, và `vd_map` bị bỏ khi Cluster Cast đã chiếu được. Giữ lại một transport không ai gọi chỉ
     * là nợ chờ mục.
     *
     * 8 còn lại đều trong `ClusterCast.kt` — engine V1 cũ, chỉ xoá được sau khi V2 chạy được trên xe.
     */
    private val adbEntryPointsOutsideTransport = 8

    @Test
    fun `ui type coupling to the lower layer only shrinks`() {
        // Chỉ quét phía UI. Bản đầu quét cả sourceRoots() nên khi thêm file mới trong :core thì con số
        // "UI coupling" tăng lên — đo sai đối tượng. Đây là lần thứ tư trong ngày phép đo của tôi đếm thứ
        // khác với thứ nó tuyên bố; giữ lại ghi chú này để lần sau kiểm tên test khớp phạm vi quét.
        val types = mutableSetOf<String>()
        uiRoots().forEach { root ->
            Files.walk(root).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .filter { !NON_UI.any { part -> it.toString().contains(part) } }
                    .filter { it.fileName.toString() != "CastFacade.kt" }
                    .forEach { file ->
                        IMPORT.findAll(file.toFile().readText()).forEach { types += it.groupValues[1] }
                    }
            }
        }
        assertEquals(
            uiTypeCoupling,
            types.size,
            "coupling kiểu đã đổi. Nếu giảm: hạ uiTypeCoupling. Nếu tăng: đưa kiểu mới qua CastFacade. Hiện: ${types.sorted()}",
        )
    }

    @Test
    fun `cross layer coupling only shrinks`() {
        val owner = declarationOwners()
        val lower = mutableSetOf<String>()
        uiRoots().forEach { root ->
            Files.walk(root).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .filter { !NON_UI.any { part -> it.toString().contains(part) } }
                    .filter { it.fileName.toString() != "CastFacade.kt" }
                    .forEach { file ->
                        IMPORT.findAll(file.toFile().readText()).forEach { match ->
                            val name = match.groupValues[1]
                            if (owner[name] != "app") lower += name
                        }
                    }
            }
        }
        assertEquals(
            crossLayerCoupling,
            lower.size,
            "coupling vượt tầng đã đổi. Hiện: ${lower.sorted()}",
        )
    }

    /** Kiểu nào được khai báo ở module nào. Dùng để phân biệt vượt tầng với dùng nội bộ app. */
    private fun declarationOwners(): Map<String, String> {
        val owners = mutableMapOf<String, String>()
        listOf("core/src/main/kotlin" to "core", "../core/src/main/kotlin" to "core",
            "car-integration/src/main/kotlin" to "car", "../car-integration/src/main/kotlin" to "car",
            "app/src/main/java" to "app", "../app/src/main/java" to "app").forEach { (path, module) ->
            val root = Paths.get(path)
            if (!Files.exists(root)) return@forEach
            Files.walk(root).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.forEach { file ->
                    DECLARATION.findAll(file.toFile().readText()).forEach {
                        owners.putIfAbsent(it.groupValues[1], module)
                    }
                }
            }
        }
        return owners
    }

    @Test
    fun `adb entry points outside the transport module only shrink`() {
        val sites = mutableListOf<String>()
        // Ngoài :car-integration nghĩa là KHÔNG tính module transport — nó được phép mở adb, đó là việc
        // của nó. Đếm cả nó thì con số mất nghĩa.
        outsideTransportRoots().forEach { root ->
            Files.walk(root).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .forEach { file ->
                        file.toFile().readLines().forEachIndexed { index, line ->
                            val code = line.trim()
                            if (!code.startsWith("//") && !code.startsWith("*") && DADB.containsMatchIn(line)) {
                                sites += "${file.fileName}:${index + 1}"
                            }
                        }
                    }
            }
        }
        assertEquals(
            adbEntryPointsOutsideTransport,
            sites.size,
            "số điểm mở adb ngoài :car-integration đã đổi. Hiện: $sites",
        )
    }

    private fun sourceRoots(): List<Path> = listOf(
        "app/src/main/java", "../app/src/main/java",
        "core/src/main/kotlin", "../core/src/main/kotlin",
        "car-integration/src/main/kotlin", "../car-integration/src/main/kotlin",
    ).map(Paths::get).filter(Files::exists)

    /** Cây source của mọi module TRỪ :car-integration. */
    private fun outsideTransportRoots(): List<Path> = listOf(
        "app/src/main/java", "../app/src/main/java",
        "core/src/main/kotlin", "../core/src/main/kotlin",
    ).map(Paths::get).filter(Files::exists)

    /** Chỉ cây source của :app — nơi UI sống. */
    private fun uiRoots(): List<Path> =
        listOf("app/src/main/java", "../app/src/main/java").map(Paths::get).filter(Files::exists)

    private companion object {
        /** Không phải UI: package của tầng dưới và của lớp nền tảng vừa tách theo module. */
        val NON_UI = listOf("/modules/clustercast/v2/", "/cast/platform/", "/cast/transport/")

        /**
         * Đếm cả tên đầy đủ viết thẳng trong dòng code, không chỉ dòng `import`.
         *
         * 2026-07-27: đây là lỗi đo thứ năm của bộ ratchet này. `ClusterCastActivity` từng viết
         * `com.byd.clusternav.modules.clustercast.v2.AdjustmentResult` ngay trong biểu thức; phép đo cũ
         * chỉ nhìn dòng import nên coupling đó vô hình. Khi dọn sang import thì con số "tăng", trong khi
         * thực tế nó vốn đã ở đó — đúng kiểu phép đo tự tạo ảo giác tiến bộ.
         */
        val IMPORT = Regex("""(?:import |[^A-Za-z0-9_.])com\.byd\.clusternav\.modules\.clustercast\.v2\.([A-Za-z0-9_]+)""")
        val DADB = Regex("""\bDadb\.create\(""")
        val DECLARATION = Regex(
            """(?m)^(?:internal )?(?:data )?(?:sealed )?(?:class|object|interface|enum class|fun) ([A-Za-z0-9_]+)""",
        )
    }
}

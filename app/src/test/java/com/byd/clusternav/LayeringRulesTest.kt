package com.byd.clusternav

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Cưỡng chế các quy tắc xếp chỗ trong docs/refactor-car-execution/layering-rules.md.
 *
 * Có test này vì trong một ngày tôi xếp sai chuồng bốn lần và mỗi lần chỉ phát hiện khi tình cờ đo lại.
 * Quy tắc viết trong tài liệu mà không ai cưỡng chế thì chỉ là ý định.
 */
class LayeringRulesTest {

    private fun root(vararg candidates: String): Path? =
        candidates.map(Paths::get).firstOrNull(Files::exists)

    private fun kotlinFiles(root: Path): List<Path> =
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList()
        }

    private val androidOrDadb = Regex("""(?m)^import (android|androidx|dadb)\.""")
    private val androidOnly = Regex("""(?m)^import android""")

    @Test
    fun `core khong duoc biet Android hay dadb`() {
        val root = root("core/src/main/kotlin", "../core/src/main/kotlin") ?: return
        val offenders = kotlinFiles(root).filter { androidOrDadb.containsMatchIn(it.toFile().readText()) }
        assertEquals(emptyList<Path>(), offenders, "quy tắc Q1: :core là JVM thuần")
    }

    @Test
    fun `car-integration khong duoc biet Android`() {
        // Nó nói với head unit qua adb; API Android cục bộ là việc của :app. Nếu Android lọt vào đây thì
        // CLI runner không chạy được nữa — tức mất đúng lý do module này tồn tại.
        val root = root("car-integration/src/main/kotlin", "../car-integration/src/main/kotlin") ?: return
        val offenders = kotlinFiles(root).filter { androidOnly.containsMatchIn(it.toFile().readText()) }
        assertEquals(emptyList<Path>(), offenders, "quy tắc Q1: transport phải chạy trên JVM thuần")
    }

    /**
     * Số file trong `:app` không dùng Android/dadb gì cả — tức đang nằm sai chuồng theo Q1.
     *
     * Chỉ được phép **giảm**. Khởi điểm 27/7: 7 file / 995 LOC. Sau khi review B1–B5 theo checklist, bốn
     * file đã về đúng chuồng: `WmParse`, `StackParse`, `DisplayParse` (comment của chính chúng ghi "PURE
     * (không đụng Android)" — parser thuộc `:core` theo bảng xếp chỗ) và `AppScale` (dữ liệu cấu hình).
     *
     * Ba file còn lại ở `:app` là **có chủ ý**, không phải nợ:
     * - `CastActivityRefresh` — điều phối làm mới của Activity, nó là UI dù không import Android.
     * - `CastOperationStatus` — theo dõi trạng thái thao tác cho màn hình, thuộc vòng đời UI.
     * - `ModuleRegistry` — hạ tầng đăng ký module của chính app.
     */
    private val pureFilesStillInApp = 3

    @Test
    fun `so file thuan con nam trong app chi duoc giam`() {
        val root = root("app/src/main/java", "../app/src/main/java") ?: return
        val pure = kotlinFiles(root).filter { file ->
            val text = file.toFile().readText()
            !androidOrDadb.containsMatchIn(text) &&
                !Regex("""\b(Context|View|Activity|Service|Bitmap|Canvas)\b""").containsMatchIn(text)
        }
        assertEquals(
            pureFilesStillInApp,
            pure.size,
            "đổi rồi: nếu giảm thì hạ con số; nếu tăng thì file thuần mới đang bị đặt vào :app. " +
                "Hiện: ${pure.map { it.fileName.toString() }.sorted()}",
        )
    }

    /**
     * Q1 áp cho `:car-integration`: mọi file ở đây phải thật sự nói với thiết bị.
     *
     * Sinh ra từ một lỗi thật: `CarExecCommands` 276 dòng nằm trong module transport với **0** lần dùng
     * dadb, bị giữ lại chỉ vì phụ thuộc một kiểu kết quả. Logic thuần nằm nhờ trong module thiết bị thì
     * không ai test nó off-car được — đúng cái giá đã phải trả với `CastAndroidRuntime`.
     */
    @Test
    fun `moi file trong car-integration phai that su dung dadb`() {
        val root = root("car-integration/src/main/kotlin", "../car-integration/src/main/kotlin") ?: return
        val pure = kotlinFiles(root).filter { !it.toFile().readText().contains("dadb") }
        assertEquals(
            emptyList<String>(),
            pure.map { it.fileName.toString() },
            "file không dùng dadb thì thuộc :core, không phải :car-integration",
        )
    }

    /**
     * Q3: mọi file phải thuộc một cột feature. File nằm ở gốc package là file không có chủ.
     *
     * Sinh ra từ lỗi thật: sau khi dời, 5 file navigation nằm ở gốc `com.byd.clusternav` trong `:core`,
     * không thuộc cột nào — nên không ai biết ai sở hữu chúng khi cần đổi.
     */
    @Test
    fun `khong file nao nam o goc package cua core`() {
        val root = root("core/src/main/kotlin/com/byd/clusternav", "../core/src/main/kotlin/com/byd/clusternav")
            ?: return
        val orphans = Files.list(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList()
        }
        assertEquals(
            emptyList<String>(),
            orphans.map { it.fileName.toString() },
            "mỗi file phải nằm trong một cột feature (navigation/, modules/, carexec/, core/)",
        )
    }

    /**
     * P4: tên không được nói sai về chuồng.
     *
     * `CastAndroidGateway` sau khi sang module không có Android thì cái tên thành lời nói dối, và
     * `AndroidObservedStateParser` trong `:core` cũng vậy. Người đọc sau sẽ xếp chỗ sai theo cái tên.
     */
    @Test
    fun `khong ten nao chua Android trong module khong co Android`() {
        val roots = listOfNotNull(
            root("core/src/main/kotlin", "../core/src/main/kotlin"),
            root("car-integration/src/main/kotlin", "../car-integration/src/main/kotlin"),
        )
        val liars = roots.flatMap(::kotlinFiles).filter { file ->
            Regex("""(?m)^(?:internal )?(?:object|class|interface) [A-Za-z]*Android""")
                .containsMatchIn(file.toFile().readText())
        }
        assertEquals(emptyList<String>(), liars.map { it.fileName.toString() }, "tên nói sai về chuồng")
    }

    /** Q3: feature không gọi ngang feature, kiểm trong `:core` nơi cả hai cùng sống. */
    @Test
    fun `navigation va cast khong goi ngang nhau trong core`() {
        val root = root("core/src/main/kotlin/com/byd/clusternav", "../core/src/main/kotlin/com/byd/clusternav")
            ?: return
        val nav = root.resolve("navigation")
        val cast = root.resolve("modules")
        if (Files.exists(nav)) {
            kotlinFiles(nav).forEach { file ->
                assertTrue(
                    !file.toFile().readText().contains("modules.clustercast"),
                    "${file.fileName}: navigation không được import Cast",
                )
            }
        }
        if (Files.exists(cast)) {
            kotlinFiles(cast).forEach { file ->
                assertTrue(
                    !file.toFile().readText().contains("clusternav.navigation"),
                    "${file.fileName}: Cast không được import navigation",
                )
            }
        }
    }

    @Test
    fun `tai lieu quy tac ton tai va liet ke du cot cuong che`() {
        val doc = root(
            "docs/refactor-car-execution/layering-rules.md",
            "../docs/refactor-car-execution/layering-rules.md",
        )
        assertTrue(doc != null, "thiếu tài liệu quy tắc")
        val text = doc!!.toFile().readText()
        listOf(
            "Q1", "Q2", "Q3", "P1", "P2", "P3", "P4",
            "chưa cưỡng chế", "LayeringRulesTest", "attestation",
        ).forEach {
            assertTrue(text.contains(it), "tài liệu thiếu phần '$it'")
        }
    }
}

package com.byd.clusternav.modules.clustercast.v2

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Static wiring contract for the 0.71 application surfaces. It proves the user-facing controls exist
 * and that presentation never imports a mutation path of its own.
 */
class CastAppManagerWiringTest {

    private fun source(relative: String): String {
        val direct: Path = Paths.get("app/src/$relative")
        val nested: Path = Paths.get("src/$relative")
        val path = if (Files.exists(direct)) direct else nested
        return path.toFile().readText()
    }

    private val activity = source("main/java/com/byd/clusternav/modules/clustercast/ClusterCastActivity.kt")
    private val dialog = source("main/java/com/byd/clusternav/modules/clustercast/CastAppManagerDialog.kt")
    private val binding = source("main/java/com/byd/clusternav/modules/clustercast/CastAppManagerBinding.kt")
    private val catalog = source("main/java/com/byd/clusternav/modules/clustercast/v2/CastAppCatalog.kt")

    @Test
    fun `activity binds shared icon tiles and local default preselection without dispatch`() {
        assertTrue(activity.contains("CastAppTiles.bind"))
        assertTrue(activity.contains("appBinding.preselect"))
        assertTrue(activity.contains("catalog.installed(runtime.automation.config().defaultPackage)"))
        assertTrue(activity.contains("appBinding.model(castActionExported)"))
        val preselect = activity.substringAfter("if (selectedPackage == null && preselected != null)")
            .substringBefore("refresh()")
        assertFalse(preselect.contains("executeCast"))
        assertFalse(preselect.contains("queueLatestTarget"))
        assertFalse(preselect.contains("runManualIntent"))
    }

    @Test
    fun `app manager exposes search details and distinct labeled actions`() {
        listOf(
            "Tìm theo tên hoặc package", "Không có ứng dụng khớp tìm kiếm", "showDetails",
            "Chiếu lên cụm", "Thêm vào yêu thích", "Bỏ khỏi yêu thích",
            "Đặt làm mặc định", "Thay ứng dụng mặc định", "Bỏ ứng dụng mặc định",
            "Nâng cao · giữ phiên", "Nâng cao · bỏ giữ phiên",
        ).forEach { assertTrue(dialog.contains(it), "missing App Manager control: $it") }
        assertTrue(dialog.contains("setOnClickListener { onOpen() }"))
    }

    @Test
    fun `auto cast enable requires the versioned disclosure and cancel writes nothing`() {
        assertTrue(dialog.contains("confirmConsent"))
        assertTrue(dialog.contains("setNegativeButton(\"Huỷ\")"))
        assertTrue(dialog.contains("setOnCancelListener { onCancel() }"))
        assertTrue(dialog.contains("Đồng ý bật"))
        assertTrue(dialog.contains("Bật tự động chiếu"))
        assertTrue(dialog.contains("Tắt tự động chiếu"))
        assertTrue(dialog.contains("REVIEW_REQUIRED"))
    }

    @Test
    fun `app manager surfaces all four protection classes and stale default guidance`() {
        AppProtectionSource.values().forEach { assertTrue(dialog.contains(it.name), "missing class ${it.name}") }
        AppUnavailableReason.values().forEach { assertTrue(dialog.contains(it.name), "missing reason ${it.name}") }
        assertTrue(dialog.contains("bị khoá theo bằng chứng hệ thống"))
    }

    @Test
    fun `presentation surfaces never import a second mutation path`() {
        listOf(dialog, binding).forEach { file ->
            val imports = file.lineSequence().filter { it.trimStart().startsWith("import ") }.toList()
            assertFalse(imports.any { it.contains("CastCoordinator") }, "unexpected coordinator import")
            assertFalse(imports.any { it.contains("CastExecutor") || it.contains("ShellGateway") })
            assertFalse(imports.any { it.contains("clustercast.ClusterCast") })
        }
        assertFalse(dialog.contains("runManualIntent"))
        assertFalse(binding.contains("runManualIntent"))
        assertTrue(binding.contains("automation.setDefault"))
        assertTrue(binding.contains("automation.accept()"))
        assertTrue(binding.contains("automation.revoke()"))
    }

    @Test
    fun `catalog keeps locked classes out of user protection and migrates legacy fields once`() {
        assertTrue(catalog.contains("locked by planner evidence"))
        assertTrue(catalog.contains("current != TargetClass.NORMAL && current != TargetClass.KEEP_SESSION"))
        assertTrue(catalog.contains("MIGRATION_VERSION = 2"))
        assertTrue(catalog.contains("bubble_auto"))
        assertTrue(catalog.contains("legacyDefaultCandidate"))
        assertFalse(catalog.contains("autoCastEnabled = true"))
        assertFalse(catalog.contains("legacy.edit()"))
    }

    @Test
    fun `legacy default migration reaches the envelope without granting consent`() {
        assertTrue(binding.contains("migrateLegacyDefault"))
        assertTrue(binding.contains("clearLegacyDefaultCandidate"))
        val migration = binding.substringAfter("private fun migrateLegacyDefaultOnce()").substringBefore("override fun")
        assertFalse(migration.contains("accept("))
    }
}

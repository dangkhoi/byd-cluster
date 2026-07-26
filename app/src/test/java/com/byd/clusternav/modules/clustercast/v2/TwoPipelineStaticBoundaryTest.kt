package com.byd.clusternav.modules.clustercast.v2

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TwoPipelineStaticBoundaryTest {
    @Test
    fun `Cast V2 has no Navigation or legacy runtime import`() {
        kotlinFiles("src/main/java/com/byd/clusternav/modules/clustercast/v2").forEach { file ->
            val imports = Files.readAllLines(file).filter { it.trimStart().startsWith("import ") }
            assertFalse(imports.any { it.contains("com.byd.clusternav.navigation") }, file.toString())
            assertFalse(imports.any { it.contains("ClusterBroadcaster") || it.contains("NavRepository") || it.contains("NavState") }, file.toString())
            assertFalse(imports.any { it.contains("modules.clustercast.") && !it.contains("modules.clustercast.v2") }, file.toString())
        }
    }

    @Test
    fun `Navigation contracts have no Cast control import`() {
        kotlinFiles("src/main/java/com/byd/clusternav/navigation").forEach { file ->
            val imports = Files.readAllLines(file).filter { it.trimStart().startsWith("import ") }
            assertFalse(imports.any { it.contains("clustercast") || it.contains("CastCoordinator") }, file.toString())
        }
    }

    @Test
    fun `Cast activity owns V2 mutation while Bubble and diagnostics are read only projections`() {
        val root = projectPath("src/main/java")
        val consumers = Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .filter { !it.toString().contains("/modules/clustercast/v2/") }
                .filter { it.toFile().readText().contains("com.byd.clusternav.modules.clustercast.v2") }
                .toList()
        }
        val expected = setOf(
            "ClusterCastActivity.kt", "CastOperationStatus.kt", "CastActivityRefresh.kt",
            "CastActivityStatusTimers.kt", "CastAdjustmentDialog.kt", "CastAppManagerDialog.kt",
            "CastAppManagerBinding.kt", "CastAutomationService.kt", "CastRetryPrompt.kt",
            "CastLifecycleReceiver.kt", "FloatingBubbleService.kt", "DiagActivity.kt", "RebindReceiver.kt",
        )
        assertTrue(consumers.map { it.fileName.toString() }.toSet() == expected, "unexpected V2 consumers: $consumers")
        val activity = consumers.single { it.fileName.toString() == "ClusterCastActivity.kt" }.toFile().readText()
        assertTrue(activity.contains("runtime.coordinator.execute"))
        consumers.filterNot { it.fileName.toString() == "ClusterCastActivity.kt" }.forEach {
            assertFalse(it.toFile().readText().contains("runtime.coordinator.execute"), it.toString())
        }
        assertFalse(activity.contains("ClusterCast.cast("))
        assertFalse(activity.contains("ClusterCast.stop("))
    }

    @Test
    fun `ShellGateway surface accepts read-only request type only`() {
        val source = projectPath("src/main/java/com/byd/clusternav/modules/clustercast/v2/ShellGateway.kt").toFile().readText()
        assertTrue(source.contains("fun execute(request: ReadOnlyShellRequest)"))
        assertFalse(source.contains("fun execute(command: String)"))
        assertFalse(source.contains("am display move-stack"))
    }

    private fun kotlinFiles(relative: String): List<Path> {
        val root = projectPath(relative)
        if (!Files.exists(root)) return emptyList()
        return Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList() }
    }

    private fun projectPath(relative: String): Path {
        val current = Path.of(System.getProperty("user.dir"))
        return if (Files.exists(current.resolve("src"))) current.resolve(relative) else current.resolve("app").resolve(relative)
    }
}

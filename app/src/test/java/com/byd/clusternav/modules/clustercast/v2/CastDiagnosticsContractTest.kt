package com.byd.clusternav.modules.clustercast.v2

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastDiagnosticsContractTest {
    @Test
    fun `diagnostics reads V2 observation and journal without repair or reset actions`() {
        val diagnostics = source("main/java/com/byd/clusternav/modules/clustercast/DiagActivity.kt")
        assertTrue(diagnostics.contains("runtime.coordinator.observe()"))
        assertTrue(diagnostics.contains("runtime.store.locked"))
        assertTrue(diagnostics.contains("mode=READ_ONLY"))
        assertFalse(diagnostics.contains("ClusterCast."))
        assertFalse(diagnostics.contains("gateway.execute(CastMutation"))
        listOf("unseedFreeform(", "reconcileOnStart(", "repair(", "resetCleanDisplay(")
            .forEach { assertFalse(diagnostics.contains(it), it) }
    }

    @Test
    fun `boot update and Bubble contain no legacy Cast mutation or reconcile`() {
        val receiver = source("main/java/com/byd/clusternav/RebindReceiver.kt")
        val bubble = source("main/java/com/byd/clusternav/modules/clustercast/FloatingBubbleService.kt")
        listOf("watchdogTick", "autoCastOnBoot", "reconcileOnStart", "ClusterCast.cast(", "ClusterCast.stop(")
            .forEach { forbidden ->
                assertFalse(receiver.contains(forbidden), "receiver: $forbidden")
                assertFalse(bubble.contains(forbidden), "bubble: $forbidden")
            }
        assertTrue(receiver.contains("CastAndroidLifecycle.rehydrate"))
        assertTrue(bubble.contains("CastRuntimeUi.render"))
        assertFalse(bubble.contains("ACTION_STOP"), "0.71 Bubble must not send a duplicate Activity Stop")
        assertTrue(bubble.contains("STOP_MIN_DP = 64"))
        assertTrue(bubble.contains("runtime.coordinator.requestStop()"))
        assertTrue(bubble.contains("stopInFlight.compareAndSet(false, true)"))
        assertTrue(bubble.indexOf("stopInFlight.compareAndSet(false, true)") < bubble.indexOf("runtime.coordinator.requestStop()"))
        assertTrue(bubble.contains("STOP_ACK_DEADLINE_MS = 500L"))
        assertTrue(bubble.contains("contentDescription"))
        assertFalse(bubble.contains("setOnLongClickListener"))
        assertFalse(bubble.contains("runtime.coordinator.execute"))
    }

    @Test
    fun `Android Cast surfaces share one process owner and never close it independently`() {
        val runtime = source("main/java/com/byd/clusternav/modules/clustercast/v2/CastAndroidRuntime.kt")
        val activity = source("main/java/com/byd/clusternav/modules/clustercast/ClusterCastActivity.kt")
        val bubble = source("main/java/com/byd/clusternav/modules/clustercast/FloatingBubbleService.kt")
        val manifest = source("main/AndroidManifest.xml")
        assertTrue(runtime.contains("processRuntime"))
        assertTrue(manifest.contains("android:launchMode=\"singleTask\""))
        assertFalse(activity.contains("runtime.gateway.close()"))
        assertFalse(bubble.contains("runtime.gateway.close()"))
    }

    private fun source(relative: String): String {
        val current = Path.of(System.getProperty("user.dir"))
        val app = if (Files.exists(current.resolve("src"))) current else current.resolve("app")
        return app.resolve("src").resolve(relative).toFile().readText()
    }
}

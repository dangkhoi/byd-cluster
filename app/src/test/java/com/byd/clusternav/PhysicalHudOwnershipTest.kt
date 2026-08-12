package com.byd.clusternav

import com.byd.clusternav.testsupport.SourceRoots
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhysicalHudOwnershipTest {
    @Test
    fun `stop arrival output off never undo durable physical HUD ON`() {
        val controller = HudMirrorController { 123L }
        controller.recordPhysicalHudConfirmedOn()
        controller.requestEnabled(true)
        controller.onStop()
        controller.onArrival()
        controller.onOutputDisabled()

        val snapshot = controller.snapshot()
        assertEquals(PhysicalHudOwnership.DURABLE_USER_ON, snapshot.physicalOwnership)
        assertEquals(0, snapshot.physicalWriteCount)
        assertFalse(snapshot.requested)
    }

    @Test
    fun `active runtime has no direct HUD writer and no speed candidate write path`() {
        val controller = SourceRoots.text("src/main/java/com/byd/clusternav/HudMirrorController.kt")
        val broadcaster = SourceRoots.text("src/main/java/com/byd/clusternav/ClusterBroadcaster.kt")
        val speedOwner = SourceRoots.text("src/main/java/com/byd/clusternav/NavigationSpeedSignOwner.kt")
        val hal = SourceRoots.text("src/main/java/com/byd/clusternav/modules/hal/BydHal.kt")

        listOf(controller, broadcaster, speedOwner).forEach { text ->
            assertFalse(text.contains("BydHal"))
            assertFalse(text.contains("writeNavFrame"))
            assertFalse(text.contains("writeSpeedLimit"))
            assertFalse(text.contains("clearSpeedLimit"))
        }
        assertFalse(broadcaster.contains("NavigationHudOwner"))
        assertFalse(hal.contains("writeSpeedLimit"))
        assertFalse(hal.contains("clearSpeedLimit"))
        assertFalse(hal.contains("ADAS_TRAFFIC_LIMIT_SPEED_STATUS_PROMPT"))

        val callers = Files.walk(SourceRoots.path("src/main/java/com/byd/clusternav")).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .filter { it.fileName.toString() != "NavigationHudOwner.kt" }
                .filter { it.fileName.toString() != "BydHal.kt" }
                .filter { it.toFile().readText().contains("BydHal.writeNavFrame") }
                .toList()
        }
        assertTrue(callers.isEmpty(), "direct HUD write must be unreachable: $callers")
    }
}

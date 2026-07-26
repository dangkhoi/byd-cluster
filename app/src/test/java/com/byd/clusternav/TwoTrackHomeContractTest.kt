package com.byd.clusternav

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TwoTrackHomeContractTest {
    @Test
    fun `Home exposes exactly Navigation and Cast cards without DR or direct Cast mutation`() {
        val activity = source("main/java/com/byd/clusternav/MainActivity.kt")
        val layout = source("main/res/layout/activity_main.xml")
        assertFalse(activity.contains("modules.deadreckon"))
        assertFalse(activity.contains("ClusterCast.cast("))
        assertFalse(activity.contains("ClusterCast.stop("))
        assertFalse(layout.contains("GPS trong hầm"))
        assertFalse(layout.contains("btn_gps"))
        assertTrue(layout.contains("btn_nav_details"))
        assertTrue(layout.contains("btn_cast_details"))
        assertTrue(activity.contains("NavRepository.stop"))
    }

    @Test
    fun `notification source submits once through authoritative coordinator facade`() {
        val listener = source("main/java/com/byd/clusternav/NavNotificationListener.kt")
        assertTrue(listener.contains("NavRepository.ingest"))
        assertTrue(listener.contains("NavRepository.stop"))
        assertFalse(listener.contains("ClusterBroadcaster.emit"))
        assertFalse(listener.contains("ClusterBroadcaster.stop"))
        val repository = source("main/java/com/byd/clusternav/NavRepository.kt")
        assertTrue(repository.contains("NavigationSessionCoordinator"))
        assertTrue(repository.contains("PersistentNavigationFrameStore"))
        assertTrue(repository.contains("runtime.acceptFrame"))
    }

    @Test
    fun `lane and HUD physical output entry points are separate`() {
        val broadcaster = source("main/java/com/byd/clusternav/ClusterBroadcaster.kt")
        val repository = source("main/java/com/byd/clusternav/NavRepository.kt")
        assertTrue(broadcaster.contains("fun emitLane"))
        assertTrue(broadcaster.contains("fun emitHud"))
        assertTrue(broadcaster.contains("fun stopLane"))
        assertTrue(broadcaster.contains("fun stopHud"))
        assertTrue(repository.contains("ClusterLaneAdapter"))
        assertTrue(repository.contains("HudAdapter"))
        assertTrue(repository.contains("ClusterBroadcaster.emitLane"))
        assertTrue(repository.contains("ClusterBroadcaster.emitHud"))
    }

    @Test
    fun `HUD delivery touches only HUD owned cache`() {
        val broadcaster = source("main/java/com/byd/clusternav/ClusterBroadcaster.kt")
        val hudSection = broadcaster.substringAfter("fun emitHud").substringBefore("/** Ghi 1 frame nav") +
            broadcaster.substringAfter("private fun pushHud").substringBefore("/** Tắt HUD")
        listOf("lastCleanRoad", "scrollTick", "lastState", "lastFreshAt", "heartbeat", "sessionReset")
            .forEach { laneSymbol -> assertFalse(hudSection.contains(laneSymbol), laneSymbol) }
        listOf("lastHudIcon", "lastHudSeg", "lastHudRoad", "hudActive", "hudExec")
            .forEach { hudSymbol -> assertTrue(hudSection.contains(hudSymbol), hudSymbol) }
    }

    @Test
    fun `lane toggle is local and lane self heal cannot clear HUD`() {
        val activity = source("main/java/com/byd/clusternav/MainActivity.kt")
        val layout = source("main/res/layout/activity_main.xml")
        val broadcaster = source("main/java/com/byd/clusternav/ClusterBroadcaster.kt")
        assertTrue(layout.contains("cb_lane"))
        assertTrue(activity.contains("NavigationOutputTarget.CLUSTER_LANE, enabled"))
        val laneHeal = broadcaster.substringAfter("private fun idleSelfHeal").substringBefore("private fun cancelHeartbeat")
        assertFalse(laneHeal.contains("clearHud"))
        val inactiveLane = broadcaster.substringAfter("fun emitLane").substringBefore("val clean")
        assertFalse(inactiveLane.contains("stop(ctx)"))
    }

    private fun source(relative: String): String {
        val current = Path.of(System.getProperty("user.dir"))
        val app = if (Files.exists(current.resolve("src"))) current else current.resolve("app")
        return app.resolve("src").resolve(relative).toFile().readText()
    }
}

package com.byd.clusternav.modules.clustercast.v2

import com.byd.clusternav.testsupport.SourceRoots
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastAccessibilityTest {
    @Test
    fun `Activity and Bubble expose labeled 56dp and 64dp controls`() {
        val layout = source("main/res/layout/activity_cluster_cast.xml")
        val bubble = source("main/java/com/byd/clusternav/modules/clustercast/FloatingBubbleService.kt")
        assertTrue(layout.contains("android:minHeight=\"56dp\""))
        assertTrue(layout.contains("android:minHeight=\"64dp\""))
        assertTrue(layout.contains("android:contentDescription"))
        assertTrue(bubble.contains("STOP_MIN_DP = 64"))
        assertTrue(bubble.contains("MENU_MIN_DP = 56"))
        assertTrue(bubble.contains("minimumHeight = dp(minDp)"))
        assertTrue(bubble.contains("contentDescription"))
        assertFalse(bubble.contains("setOnLongClickListener"))
    }

    @Test
    fun `app manager has base w960 and w1280 adaptive resources`() {
        listOf(
            "main/res/values/integers.xml",
            "main/res/values-w960dp/integers.xml",
            "main/res/values-w1280dp/integers.xml",
        ).forEach { relative ->
            val resource = path(relative)
            assertTrue(Files.exists(resource), relative)
            assertTrue(resource.toFile().readText().contains("cast_app_columns"), relative)
        }
        listOf(
            "main/res/layout/activity_cluster_cast.xml",
            "main/res/layout-w960dp/activity_cluster_cast.xml",
            "main/res/layout-w1280dp/activity_cluster_cast.xml",
        ).forEach { relative ->
            assertTrue(Files.exists(path(relative)), relative)
        }
        val activity = source("main/java/com/byd/clusternav/modules/clustercast/ClusterCastActivity.kt")
        assertTrue(activity.contains("R.integer.cast_app_columns"))
        assertTrue(activity.contains("GridLayout"))
    }

    private fun source(relative: String) = path(relative).toFile().readText()
    private fun path(relative: String): Path = SourceRoots.path("src/$relative")
}

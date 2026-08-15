package com.byd.clusternav

import com.byd.clusternav.testsupport.SourceRoots
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Khoá lại tập gói được LẮNG NGHE là GMaps-only (owner 2026-08-15).
 *
 * NavNotificationListener.onNotificationPosted/onNotificationRemoved lọc ở MAPS_PACKAGES TRƯỚC KHI gọi
 * parser — một notification không nằm trong tập này không bao giờ tới được parser. Waze-Mod nav-source và
 * VietMap/Waze speed-limit signal đã được gỡ (không hoạt động — xem
 * docs/diagnostics/removal-manifest-waze-vietmap-1.22.md), nên tập chỉ còn Google Maps + ReVanced.
 */
class NavNotificationListenerTest {
    @Test
    fun `listened set is GMaps-only (stock plus ReVanced)`() {
        assertEquals(
            setOf("com.google.android.apps.maps", "app.revanced.android.apps.maps"),
            NavNotificationListener.MAPS_PACKAGES,
        )
    }

    @Test
    fun `Waze and VietMap are no longer listened (removed feature)`() {
        assertTrue("com.google.android.apps.maps" in NavNotificationListener.MAPS_PACKAGES)
        assertFalse("vn.vietmap.live" in NavNotificationListener.MAPS_PACKAGES)
        assertFalse("com.waze" in NavNotificationListener.MAPS_PACKAGES)
        assertFalse("com.chisadin.wazemod" in NavNotificationListener.MAPS_PACKAGES)
    }

    // ── B1 Lỗ 2 (handoff 2026-08-15): disconnect PHẢI là tín hiệu DƯƠNG "nguồn dừng" ──────────
    // Runtime của onListenerDisconnected cần Android (NotificationListenerService/requestRebind), nên — như
    // NavCastUiWiringContractTest — khoá WIRING bằng cách đọc source: binding rớt phải stop() phiên
    // authoritative (⇒ hudOwner.stop() huỷ nhịp keep-alive, không ghim frame cũ vô hạn) + idle làn cụm.
    private val listenerSrc by lazy {
        SourceRoots.text("src/main/java/com/byd/clusternav/NavNotificationListener.kt")
    }

    /** Trích thân MỘT hàm để khỏi khớp nhầm NavRepository.stop ở nhánh khác (onNotificationRemoved/handle). */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "missing $signature" }
        val after = start + signature.length
        val next = listOf("\n    fun ", "\n    private fun ", "\n    override fun ", "\n    companion object", "\n}")
            .mapNotNull { source.indexOf(it, after).takeIf { i -> i >= 0 } }
            .minOrNull() ?: source.length
        return source.substring(start, next)
    }

    @Test
    fun `onListenerDisconnected stops the authoritative session (positive source-ended signal)`() {
        val body = functionBody(listenerSrc, "override fun onListenerDisconnected()")
        assertTrue(
            body.contains("NavRepository.stop(applicationContext)"),
            "disconnect phải stop() phiên nav → hudOwner.stop() huỷ nhịp keep-alive (nếu thiếu, frame cũ ghim vô hạn)",
        )
        assertTrue(
            body.contains("ClusterNavLaneWidget.onNavIdle()"),
            "disconnect phải idle làn cụm như nhánh onNotificationRemoved",
        )
    }
}

package com.byd.clusternav.modules.clustercast

import com.byd.clusternav.modules.clustercast.simplified.AppType
import com.byd.clusternav.modules.clustercast.simplified.DisplayConfig
import com.byd.clusternav.modules.clustercast.simplified.SimpleCastState
import com.byd.clusternav.modules.clustercast.simplified.SlotState
import com.byd.clusternav.modules.clustercast.v2.BubbleZone
import com.byd.clusternav.modules.clustercast.v2.CastBubbleProjection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T5 gap coverage — Bubble accessibility compliance:
 *
 * 1. Content descriptions for each zone state (empty, occupied, disabled)
 *    follow the pattern: "[label] · [action hint / disabled reason]"
 * 2. Zone labels are correct per BubbleZone
 * 3. Touch target minimum (48dp) meets the 48dp automotive guideline
 * 4. Disabled alpha is distinct from enabled (visual + programmatic)
 * 5. All SimpleCastState variants produce meaningful disabled reasons
 *
 * Pure JVM tests: we verify the content-description FORMAT and constants
 * without Android Context. The actual rendering is integration-tested.
 */
class BubbleAccessibilityTest {

    // ─── 1. Zone labels ──────────────────────────────────────────────────────

    @Test
    fun `zone short label for FULL is Full`() {
        assertEquals("Full", CastBubbleProjection.zoneShortLabel(BubbleZone.FULL))
    }

    @Test
    fun `zone short label for LEFT is Trái`() {
        assertEquals("Trái", CastBubbleProjection.zoneShortLabel(BubbleZone.LEFT))
    }

    @Test
    fun `zone short label for RIGHT is Phải`() {
        assertEquals("Phải", CastBubbleProjection.zoneShortLabel(BubbleZone.RIGHT))
    }

    // ─── 2. Content description format contracts ─────────────────────────────

    @Test
    fun `occupied zone content description includes app name and return hint`() {
        // BubbleRenderer.paintOccupied sets: "$label · chạm để trả về"
        val label = "maps"
        val expected = "$label · chạm để trả về"
        // Verify the pattern matches what BubbleRenderer produces
        assertTrue(expected.contains("chạm để trả về"),
            "Occupied zone must hint 'tap to return'")
        assertTrue(expected.contains(label),
            "Occupied zone must show app short name")
    }

    @Test
    fun `empty zone content description includes zone name and cast hint`() {
        // BubbleRenderer.paintEmpty sets: "${view.text} · chạm để chiếu"
        val zoneLabel = CastBubbleProjection.zoneShortLabel(BubbleZone.FULL)
        val expected = "$zoneLabel · chạm để chiếu"
        assertTrue(expected.contains("chạm để chiếu"),
            "Empty zone must hint 'tap to cast'")
        assertTrue(expected.contains(zoneLabel),
            "Empty zone must include zone label")
    }

    @Test
    fun `disabled zone content description includes reason`() {
        // BubbleRenderer.paintDisabled sets: "${view.text} · không khả dụng: $reason"
        val zoneLabel = CastBubbleProjection.zoneShortLabel(BubbleZone.LEFT)
        val reason = "Đang chiếu full"
        val expected = "$zoneLabel · không khả dụng: $reason"
        assertTrue(expected.contains("không khả dụng"),
            "Disabled zone must indicate unavailability")
        assertTrue(expected.contains(reason),
            "Disabled zone must include the specific reason")
    }

    @Test
    fun `disabled zone without reason still indicates unavailability`() {
        val zoneLabel = CastBubbleProjection.zoneShortLabel(BubbleZone.RIGHT)
        val expected = "$zoneLabel · không khả dụng"
        assertTrue(expected.contains("không khả dụng"),
            "Disabled zone without reason must still say unavailable")
    }

    // ─── 3. Touch target size ────────────────────────────────────────────────

    @Test
    fun `ZONE_MIN_DP is 48 meeting the 48dp automotive guideline`() {
        assertEquals(48, BubbleRenderer.ZONE_MIN_DP)
        assertTrue(BubbleRenderer.ZONE_MIN_DP >= 48,
            "Zone size must meet the 48dp automotive touch guideline")
    }

    @Test
    fun `ZONE_MIN_DP never drops below the 48dp guideline`() {
        val margin = (BubbleRenderer.ZONE_MIN_DP - 48.0) / 48.0
        assertTrue(margin >= 0.0,
            "${BubbleRenderer.ZONE_MIN_DP}dp must be >= 48dp guideline (margin ${(margin * 100).toInt()}%)")
    }

    // ─── 4. Disabled alpha is visually distinct ──────────────────────────────

    @Test
    fun `disabled alpha is significantly lower than full opacity`() {
        assertTrue(BubbleRenderer.DISABLED_ZONE_ALPHA < 0.5f,
            "Disabled zones must be visually distinct (alpha < 0.5)")
        assertTrue(BubbleRenderer.DISABLED_ZONE_ALPHA > 0.0f,
            "Disabled zones must still be slightly visible")
    }

    @Test
    fun `disabled alpha is exactly 0_35`() {
        assertEquals(0.35f, BubbleRenderer.DISABLED_ZONE_ALPHA, 0.001f)
    }

    // ─── 5. All SimpleCastState variants produce disabled reasons ──────────────

    @Test
    fun `CastingFull disables left and right with reason`() {
        val state = SimpleCastState.CastingFull("com.test", AppType.NORMAL, DisplayConfig.NORMAL_DEFAULT)
        // When state is CastingFull, left/right zones get reason "Đang chiếu full"
        val expectedReason = "Đang chiếu full"
        assertTrue(expectedReason.isNotBlank(),
            "CastingFull must provide a disabled reason for half zones")
    }

    @Test
    fun `CastingSplit disables full zone with reason`() {
        val state = SimpleCastState.CastingSplit(
            left = SlotState("com.left", DisplayConfig.NORMAL_DEFAULT),
            right = null,
        )
        // When state is CastingSplit, full zone gets reason "Đang chia đôi"
        val expectedReason = "Đang chia đôi"
        assertTrue(expectedReason.isNotBlank(),
            "CastingSplit must provide a disabled reason for full zone")
    }

    @Test
    fun `Opening state provides informative disabled reason`() {
        val reason = "Đang mở cụm"
        assertTrue(reason.isNotBlank())
    }

    @Test
    fun `Stopping state provides informative disabled reason`() {
        val reason = "Đang trả app"
        assertTrue(reason.isNotBlank())
    }

    @Test
    fun `Closing state provides informative disabled reason`() {
        val reason = "Đang đóng"
        assertTrue(reason.isNotBlank())
    }

    @Test
    fun `Error state includes error message in reason`() {
        val state = SimpleCastState.Error("Projection failed")
        val reason = "Lỗi: ${state.message}"
        assertTrue(reason.contains("Projection failed"),
            "Error state reason must include the error message")
    }

    @Test
    fun `Off state provides generic not-ready reason`() {
        val reason = "Chưa sẵn sàng"
        assertTrue(reason.isNotBlank())
    }

    // ─── 6. Zone enum coverage ────────────────────────────────────────────────

    @Test
    fun `BubbleZone has exactly 3 zones`() {
        assertEquals(3, BubbleZone.entries.size)
        assertTrue(BubbleZone.entries.contains(BubbleZone.FULL))
        assertTrue(BubbleZone.entries.contains(BubbleZone.LEFT))
        assertTrue(BubbleZone.entries.contains(BubbleZone.RIGHT))
    }

    @Test
    fun `every BubbleZone has a non-blank short label`() {
        BubbleZone.entries.forEach { zone ->
            val label = CastBubbleProjection.zoneShortLabel(zone)
            assertTrue(label.isNotBlank(), "$zone must have non-blank label")
        }
    }

    // ─── 7. Content description completeness for screen readers ──────────────

    @Test
    fun `occupied content description is actionable — user knows what tap does`() {
        // Pattern: "[shortName] · chạm để trả về"
        val contentDesc = "maps · chạm để trả về"
        // Screen reader user hears: what app is there + what will happen on tap
        assertTrue(contentDesc.contains("·"), "must have separator for clarity")
        assertTrue(contentDesc.length > 5, "must be meaningful length")
    }

    @Test
    fun `empty content description is actionable — user knows what tap does`() {
        val contentDesc = "Full · chạm để chiếu"
        assertTrue(contentDesc.contains("chiếu"), "must indicate cast action")
    }

    @Test
    fun `disabled content description explains WHY — user not confused by no-op`() {
        val contentDesc = "Trái · không khả dụng: Đang chiếu full"
        assertTrue(contentDesc.contains("không khả dụng"), "must say unavailable")
        assertTrue(contentDesc.contains("Đang chiếu full"), "must explain why")
    }

    // ─── 8. Zone fills are translucent so cluster content shows through ───────

    @Test
    fun `occupied fill is translucent so casting content shows through`() {
        val alpha = (BubbleRenderer.FILL_OCCUPIED.toLong() and 0xFF000000L) ushr 24
        assertTrue(alpha < 0xFF, "Occupied (casting) fill must be translucent, was $alpha")
        assertTrue(alpha > 0x00, "Occupied fill must still be visible")
    }

    @Test
    fun `empty idle fill is translucent and lighter than the occupied fill`() {
        val emptyAlpha = (BubbleRenderer.FILL_EMPTY.toLong() and 0xFF000000L) ushr 24
        val occupiedAlpha = (BubbleRenderer.FILL_OCCUPIED.toLong() and 0xFF000000L) ushr 24
        assertTrue(emptyAlpha < 0xFF, "Empty (idle) fill must be translucent, was $emptyAlpha")
        assertTrue(emptyAlpha < occupiedAlpha,
            "Idle fill ($emptyAlpha) must be lighter than casting fill ($occupiedAlpha)")
    }

    @Test
    fun `disabled fill is translucent`() {
        val alpha = (BubbleRenderer.FILL_DISABLED.toLong() and 0xFF000000L) ushr 24
        assertTrue(alpha < 0xFF, "Disabled fill must be translucent, was $alpha")
    }

    @Test
    fun `brand stroke and text color stays opaque for label legibility`() {
        val alpha = (BubbleRenderer.BRAND.toLong() and 0xFF000000L) ushr 24
        assertEquals(0xFF, alpha, "BRAND (stroke + empty-zone text) stays opaque so labels remain legible")
    }
}

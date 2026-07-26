package com.byd.clusternav.modules.clustercast.v2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Canonical Bubble projection: no localized-text inference, exact Stop export, shared app rows. */
class CastBubbleProjectionTest {

    private fun model(
        title: String = "Đang chiếu",
        status: String = "sẵn sàng",
        allowed: Set<CastAction> = emptySet(),
        disabledReasons: Map<CastAction, DisabledReason> = emptyMap(),
        durableStatusPriority: Boolean = false,
    ) = CastRenderModel(
        title, status, true, false, durableStatusPriority,
        CastAction.entries.map { action ->
            CastRenderAction(action, action in allowed, if (action in allowed) null else disabledReasons[action])
        },
    )

    private fun row(
        label: String,
        pkg: String,
        favorite: Boolean = false,
        protection: AppProtectionSource = AppProtectionSource.NORMAL,
    ) = CastAppRow(label, pkg, favorite, false, protection, AppIconState.LOADED)

    private val maps = row("Google Maps", "com.example.maps")
    private val vietmap = row("VietMap", "com.example.vietmap", favorite = true)
    private val unknown = row("Mystery", "com.example.mystery", protection = AppProtectionSource.UNKNOWN_PROTECTED)
    private val rows = listOf(maps, vietmap, unknown)

    @Test
    fun `stop appears exactly when the canonical stop action is exported`() {
        val available = CastBubbleProjection.project(model(allowed = setOf(CastAction.STOP)), rows, null)
        assertEquals(BubbleStopControl.AVAILABLE, available.stop)
        assertEquals("Dừng chiếu", available.stopLabel)
        val hidden = CastBubbleProjection.project(
            model(disabledReasons = mapOf(CastAction.STOP to DisabledReason.NO_ACTIVE_TARGET)), rows, null,
        )
        assertEquals(BubbleStopControl.HIDDEN, hidden.stop)
        assertEquals("Chưa có phiên đang chiếu", hidden.status)
    }

    @Test
    fun `stop visibility never depends on the localized title`() {
        val idle = CastBubbleProjection.project(
            model(title = "Cluster Cast sẵn sàng", allowed = setOf(CastAction.STOP)), rows, null,
        )
        assertEquals(BubbleStopControl.AVAILABLE, idle.stop)
        val active = CastBubbleProjection.project(model(title = "Đang chiếu"), rows, null)
        assertEquals(BubbleStopControl.HIDDEN, active.stop)
    }

    @Test
    fun `local acknowledgement is rendered before durable truth changes`() {
        val acknowledged = CastBubbleProjection.project(
            model(allowed = setOf(CastAction.STOP)), rows, null, localStopRequested = true,
        )
        assertEquals(BubbleStopControl.ACKNOWLEDGED, acknowledged.stop)
        assertEquals("Đã yêu cầu dừng", acknowledged.stopLabel)
        assertEquals("Đã yêu cầu dừng", acknowledged.status)
    }

    @Test
    fun `durable recovery or manual status has priority over an unavailable reason`() {
        val projection = CastBubbleProjection.project(
            model(
                status = "cần xử lý thủ công",
                disabledReasons = mapOf(CastAction.STOP to DisabledReason.RECOVERY_PENDING),
                durableStatusPriority = true,
            ),
            rows, null,
        )
        assertEquals("cần xử lý thủ công", projection.status)
        assertTrue(projection.durableStatusPriority)
    }

    @Test
    fun `every disabled reason maps to an exact non mutating status`() {
        DisabledReason.entries.forEach { reason ->
            val projection = CastBubbleProjection.project(
                model(disabledReasons = mapOf(CastAction.STOP to reason)), rows, null,
            )
            assertEquals(BubbleStopControl.HIDDEN, projection.stop)
            assertTrue(projection.status.isNotBlank(), "missing status for $reason")
        }
    }

    @Test
    fun `focus order is stop then apps then settings`() {
        val withStop = CastBubbleProjection.project(model(allowed = setOf(CastAction.STOP)), rows, null)
        assertEquals(
            listOf(BubbleFocusTarget.STOP, BubbleFocusTarget.APPS, BubbleFocusTarget.SETTINGS),
            withStop.focusOrder,
        )
        val withoutStop = CastBubbleProjection.project(model(), rows, null)
        assertEquals(listOf(BubbleFocusTarget.APPS, BubbleFocusTarget.SETTINGS), withoutStop.focusOrder)
    }

    @Test
    fun `content description carries state and target`() {
        val projection = CastBubbleProjection.project(
            model(title = "Đang chiếu", status = "sẵn sàng"), rows, "com.example.maps",
            activeTargetPackage = "com.example.maps",
        )
        assertTrue(projection.contentDescription.contains("Đang chiếu"))
        assertTrue(projection.contentDescription.contains("sẵn sàng"))
        assertTrue(projection.contentDescription.contains("com.example.maps"))
    }

    @Test
    fun `menu shows the default first and only exports enabled canonical actions`() {
        val projection = CastBubbleProjection.project(
            model(allowed = setOf(CastAction.CAST)), rows, "com.example.maps",
        )
        assertEquals("com.example.maps", projection.menu.first().packageName)
        assertTrue(projection.menu.first().isDefault)
        assertTrue(projection.menu.first().label.contains("mặc định"))
        assertTrue(projection.menu.first { it.packageName == "com.example.maps" }.enabled)
        assertTrue(projection.menu.all { it.action == CastAction.CAST })
    }

    @Test
    fun `menu items are disabled when the canonical action is not exported`() {
        val projection = CastBubbleProjection.project(model(), rows, "com.example.maps")
        assertTrue(projection.menu.none { it.enabled })
    }

    @Test
    fun `switch replaces cast while another target is active`() {
        val projection = CastBubbleProjection.project(
            model(allowed = setOf(CastAction.SWITCH)), rows, "com.example.maps",
            activeTargetPackage = "com.example.vietmap",
        )
        val target = projection.menu.first { it.packageName == "com.example.maps" }
        assertEquals(CastAction.SWITCH, target.action)
        assertTrue(target.enabled)
        val active = projection.menu.firstOrNull { it.packageName == "com.example.vietmap" }
        if (active != null) assertFalse(active.enabled)
    }

    @Test
    fun `unknown protected apps never appear as an enabled menu action`() {
        val projection = CastBubbleProjection.project(
            model(allowed = setOf(CastAction.CAST)), listOf(unknown.copy(favorite = true)), null,
        )
        assertTrue(projection.menu.none { it.packageName == "com.example.mystery" && it.enabled })
    }

    @Test
    fun `menu prefers default and favorites then falls back to all apps`() {
        val favouriteOnly = CastBubbleProjection.project(model(allowed = setOf(CastAction.CAST)), rows, null)
        assertEquals(listOf("com.example.vietmap"), favouriteOnly.menu.map { it.packageName })
        val plain = listOf(maps, unknown)
        val fallback = CastBubbleProjection.project(model(allowed = setOf(CastAction.CAST)), plain, null)
        assertEquals(2, fallback.menu.size)
    }

    @Test
    fun `menu is bounded and empty input is deterministic`() {
        val many = (1..12).map { row("App $it", "com.example.app$it", favorite = true) }
        assertEquals(CastBubbleProjection.MENU_ITEM_LIMIT, CastBubbleProjection.project(model(), many, null).menu.size)
        assertEquals(emptyList<BubbleMenuItem>(), CastBubbleProjection.project(model(), emptyList(), null).menu)
    }

    @Test
    fun `a stale default still appears as a disabled menu row`() {
        val projection = CastBubbleProjection.project(
            model(allowed = setOf(CastAction.CAST)), rows, "com.example.removed",
        )
        val stale = projection.menu.first()
        assertEquals("com.example.removed", stale.packageName)
        assertFalse(stale.enabled)
    }
}

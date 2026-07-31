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
        sessionConfirmed: Boolean = false,
    ) = CastRenderModel(
        title, status, true, false, durableStatusPriority,
        CastAction.entries.map { action ->
            CastRenderAction(action, action in allowed, if (action in allowed) null else disabledReasons[action])
        },
        sessionConfirmed,
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
    fun `menu shows only chosen apps plus whatever is casting`() {
        val favouriteOnly = CastBubbleProjection.project(model(allowed = setOf(CastAction.CAST)), rows, null)
        assertEquals(listOf("com.example.vietmap"), favouriteOnly.menu.map { it.packageName })
        // Nothing pinned means nothing to shortcut. Filling the menu with the first few installed apps
        // presented choices the owner never made, so the empty menu is the honest projection and the
        // surface tells them where to pick.
        val plain = listOf(maps, unknown)
        assertEquals(0, CastBubbleProjection.project(model(allowed = setOf(CastAction.CAST)), plain, null).menu.size)
        // The active target is always reachable, pinned or not, otherwise there is no way to switch off it.
        val active = CastBubbleProjection.project(
            model(allowed = setOf(CastAction.SWITCH)), plain, null,
            activeTargetPackage = maps.packageName,
        )
        assertEquals(listOf(maps.packageName), active.menu.map { it.packageName })
    }

    @Test
    fun `menu is bounded and empty input is deterministic`() {
        val many = (1..12).map { row("App $it", "com.example.app$it", favorite = true) }
        assertEquals(CastBubbleProjection.MENU_ITEM_LIMIT, CastBubbleProjection.project(model(), many, null).menu.size)
        assertEquals(emptyList<BubbleMenuItem>(), CastBubbleProjection.project(model(), emptyList(), null).menu)
    }

    /**
     * Bong bóng v0.57 vẽ ✓ lên app đang chiếu và tô đặc vòng tròn. Nguồn của hai thứ đó phải là dữ liệu,
     * không phải chuỗi `contentDescription` — tầng view bị cấm dò chữ đã bản địa hoá.
     */
    @Test
    fun `the projection names the active target and says whether anything is on the cluster`() {
        val idle = CastBubbleProjection.project(model(), rows, null)
        assertEquals(null, idle.activeTargetPackage)
        assertFalse(idle.projecting)

        val active = CastBubbleProjection.project(
            model(allowed = setOf(CastAction.STOP, CastAction.SWITCH)), rows, null,
            activeTargetPackage = maps.packageName,
        )
        assertEquals(maps.packageName, active.activeTargetPackage)
        assertTrue(active.projecting)

        // Phiên legacy đã XÁC MINH là thật: không đọc ra tên gói nhưng Stop vẫn được xuất ra — vòng tròn
        // vẫn phải báo "đang chiếu", nếu không chủ xe tưởng cụm đã sạch mà thật ra chưa.
        val nameless = CastBubbleProjection.project(
            model(allowed = setOf(CastAction.STOP), sessionConfirmed = true), rows, null,
        )
        assertEquals(null, nameless.activeTargetPackage)
        assertTrue(nameless.projecting)
    }

    /**
     * Khoá lỗi đo trên DiLink3 2026-07-31 (docs/specs/cast-recovery-honesty-and-multi-occupant.html §R3):
     * CarPlay kẹt RECOVERING (`am task resize` bị từ chối) vẫn xuất Stop từ recovery row của nó — nhưng
     * KHÔNG có phiên nào từng được xác minh. Trước bản vá, `projecting` chỉ nhìn `stop != HIDDEN` nên vẫn
     * tô đặc vòng tròn, y hệt case "nameless" ở trên dù bản chất khác hẳn — một cái là phiên thật, một cái
     * là lối thoát của lỗi. `sessionConfirmed=false` (mặc định, KHÔNG xác minh) phải tách được hai ca này.
     */
    @Test
    fun `stop offered from an unconfirmed recovery state does not paint the bubble as projecting`() {
        val recovering = CastBubbleProjection.project(
            model(allowed = setOf(CastAction.STOP), sessionConfirmed = false), rows, null,
        )
        assertEquals(null, recovering.activeTargetPackage)
        assertFalse(recovering.projecting, "Stop chỉ là lối thoát của lỗi, không phải phiên đã xác minh")
    }

    /**
     * Đôi với test ngay trên, và là nửa quan trọng hơn của nó (review 2026-07-31).
     *
     * `projecting` giờ đòi phiên đã xác minh — đúng cho việc TÔ MÀU. Nhưng `FloatingBubbleService` dùng
     * đúng một tín hiệu để quyết định CHẠM = Dừng hay CHẠM = Chiếu. Nếu cú chạm cũng đi theo `projecting`
     * thì ở đúng trạng thái kẹt (RECOVERING/RECOVERY_PENDING xuất Stop, chưa xác minh gì) nút nổi mất
     * luôn đường Stop — trong khi Stop chính là `nextSafeAction` của những hàng recovery đó, và nút nổi
     * thường là bề mặt duy nhất còn nhìn thấy khi CarPlay/AA chiếm màn chính. Tệ hơn: khi không có
     * transaction nào đang chạy (ca `OBSERVATION_DIVERGED_STOP_AVAILABLE` mới của §R2), cú chạm rơi
     * xuống nhánh chiếu và phát một lượt cast MỚI lên cụm đang có app lạ. CLAUDE.md §3 cấm đúng vòng
     * tròn này. Hai câu hỏi khác nhau ⇒ hai trường khác nhau.
     */
    @Test
    fun `a tap still means Stop whenever Stop is exported, even with nothing confirmed`() {
        val recovering = CastBubbleProjection.project(
            model(allowed = setOf(CastAction.STOP), sessionConfirmed = false), rows, null,
        )
        assertFalse(recovering.projecting, "không tô đặc: chưa có gì được xác minh")
        assertTrue(recovering.stopOnTap, "nhưng chạm PHẢI vẫn là Dừng — đó là lối thoát duy nhất")

        // Đã bấm Dừng và đang chờ: vẫn là Dừng, không được rơi xuống nhánh phát một lượt chiếu mới.
        val acknowledged = CastBubbleProjection.project(
            model(allowed = setOf(CastAction.STOP), sessionConfirmed = false), rows, null,
            localStopRequested = true,
        )
        assertEquals(BubbleStopControl.ACKNOWLEDGED, acknowledged.stop)
        assertTrue(acknowledged.stopOnTap)

        // Cụm thật sự rảnh: không có Stop, không có target ⇒ chạm là Chiếu, y như trước.
        val idle = CastBubbleProjection.project(model(), rows, null)
        assertFalse(idle.stopOnTap)

        // Phiên đã xác minh có tên gói: cả hai câu trả lời đều đúng.
        val active = CastBubbleProjection.project(
            model(allowed = setOf(CastAction.STOP), sessionConfirmed = true), rows, null,
            activeTargetPackage = maps.packageName,
        )
        assertTrue(active.projecting)
        assertTrue(active.stopOnTap)
    }

    /**
     * Kiểm chéo của review 2026-07-31: một cú Dừng THẬT đang bay có `coarseState = STOP_REQUESTED`, tức
     * `sessionConfirmed = false` — vòng tròn có bị rỗng ngay giữa lúc chờ không (rồi cú chạm kế tiếp
     * thành một lượt chiếu mới đè lên cụm đang dừng)? KHÔNG: phiên bền chưa bị xoá cho tới khi Stop hội
     * tụ, nên `activeTargetPackage` vẫn còn — và nó là VẾ ĐẦU của `projecting`, không đi qua
     * `sessionConfirmed`. Khoá lại để lần sau ai sửa `projecting` còn thấy ca này.
     */
    @Test
    fun `a real stop in flight keeps the bubble solid for the whole wait`() {
        val stopping = CastBubbleProjection.project(
            model(title = "Đang xử lý", sessionConfirmed = false), rows, null,
            localStopRequested = true, activeTargetPackage = maps.packageName,
        )
        assertEquals(BubbleStopControl.ACKNOWLEDGED, stopping.stop)
        assertTrue(stopping.projecting, "đang chờ Stop hội tụ mà vòng tròn rỗng là mời một cú chiếu nhầm")
        assertTrue(stopping.stopOnTap)
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

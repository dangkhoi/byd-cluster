package com.byd.clusternav.modules.clustercast.v2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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

    /**
     * Nút nổi v0.73 là BẢN ĐỒ của cụm (docs/specs/cast-one-mode-and-three-zone-bubble.html §R7): ô rộng
     * phía trên = cả cụm, hai ô dưới = nửa trái và nửa phải.
     *
     * Khoá hình dạng và THỨ TỰ: tầng view dựng layout theo đúng thứ tự này, và nó cũng là thứ tự đọc của
     * TalkBack. Cụm rảnh thì ô CẢ CỤM mời chiếu — đường một-app đã field-proven, không đổi một li.
     *
     * Cả BA ô đều mời chiếu khi cụm rảnh (2026-08-01). Bản trước cho hai nửa CÂM với lý do
     * [BubbleZoneDisabledReason.SPLIT_NEEDS_THE_OTHER_HALF], và lúc đó lời từ chối ấy đúng — tầng dưới
     * chưa có đường đặt app ĐẦU TIÊN vào một nửa. Nay có ([ClusterSplit.slotBand]), nên câm tiếp là câm
     * đúng thao tác chính mà chủ dự án vừa chốt.
     */
    @Test
    fun `the projection always exports three zones, full first then the two halves`() {
        val idle = CastBubbleProjection.project(model(allowed = setOf(CastAction.CAST)), rows, null)
        assertEquals(
            listOf(BubbleZone.FULL, BubbleZone.LEFT, BubbleZone.RIGHT),
            idle.zones.map { it.zone },
        )
        idle.zones.forEach { cell ->
            assertEquals(BubbleZoneAction.CAST, cell.action, "${cell.zone} phải mời chiếu khi cụm rảnh")
            assertFalse(cell.occupied)
            assertNull(cell.occupantPackage)
            assertTrue(cell.contentDescription.isNotBlank())
            assertTrue(cell.enabled, "${cell.zone}: cụm rảnh thì cả ba ô đều chiếu được")
            assertNull(cell.disabledReason, "${cell.zone}")
        }
        assertEquals(idle.zone(BubbleZone.LEFT), idle.zones[1])
        assertNull(CastBubbleProjection.project(model(), rows, null).zone(BubbleZone.FULL)?.occupantPackage)
    }

    /**
     * Luật chủ dự án chốt 2026-08-01: **chạm nửa TRÁI ⇒ app đang mở vào nửa trái NGAY, nửa phải để trống
     * chờ app sau.** Rồi mở app khác, chạm nửa PHẢI, nó lấp nốt. Đối xứng nếu bắt đầu từ bên phải.
     *
     * Đây là test khoá đúng vế BỀ MẶT của việc mở khoá đó. Bản trước câm hai nửa trên cụm rỗng, với lý do
     * đúng của lúc đó: `ClusterSplit.contentRect` không đọc nổi dải vẽ khi cụm không có task nào, nên
     * `CastPlanner` từ chối 100%. Cái đã đổi là ở tầng dưới, không phải ở đây: chia cụm là quyết định
     * NGANG, và trục ngang thì ĐÃ ĐO là đồng nhất trong không gian display, nên [ClusterSplit.slotBand]
     * cắt được ô từ khung display khi cụm đã chứng minh là rỗng (xem KDoc của nó).
     *
     * Khoá cả ba trạng thái occupancy, vì cả ba đều là câu trả lời KHÁC NHAU cho "cụm đang có gì":
     * đã-đo-và-rỗng, chưa-đo-gì-cả, và một-nửa-đã-có-app.
     */
    @Test
    fun `an empty cluster invites a first app into either half`() {
        val idle = CastBubbleProjection.project(
            model(allowed = setOf(CastAction.CAST)), rows, null,
            occupancy = ClusterOccupancy.Measured(emptyList()),
            castSource = BubbleCastSource.PRESENT,
        )
        listOf(BubbleZone.LEFT, BubbleZone.RIGHT).forEach { zone ->
            val cell = idle.zone(zone)!!
            assertTrue(cell.enabled, "$zone: cụm rỗng là ĐÚNG ca đặt app đầu tiên vào một nửa")
            assertEquals(BubbleZoneAction.CAST, cell.action)
            assertNull(cell.disabledReason, "$zone")
            assertFalse(cell.occupied, "$zone: chưa có app thì để viền, không tô đặc")
        }
        // Chưa đo được gì cả thì ô vẫn mời — cùng lệ với `castSource` (CLAUDE.md §2: "chưa biết" không
        // được đọc thành "không được"), và lượt phát lệnh còn tự đọc lại một quan sát TƯƠI rồi mới quyết.
        val unmeasured = CastBubbleProjection.project(
            model(allowed = setOf(CastAction.CAST)), rows, null, castSource = BubbleCastSource.PRESENT,
        )
        listOf(BubbleZone.LEFT, BubbleZone.RIGHT).forEach {
            assertTrue(unmeasured.zone(it)!!.enabled, "$it")
        }

        // …và ca đặt app THỨ HAI (đo thật 31/7) vẫn y như trước: nửa kia có app, nửa này mời chiếu.
        val halfTaken = CastBubbleProjection.project(
            model(allowed = setOf(CastAction.CAST)), rows, null,
            occupancy = ClusterOccupancy.Measured(
                listOf(ClusterOccupant(BubbleZone.LEFT, maps.packageName)),
            ),
            castSource = BubbleCastSource.PRESENT,
        )
        val free = halfTaken.zone(BubbleZone.RIGHT)!!
        assertTrue(free.enabled, "nửa kia đã có app ⇒ đây đúng là lượt đặt app THỨ HAI đã đo được")
        assertEquals(BubbleZoneAction.CAST, free.action)
        assertNull(free.disabledReason)

        // Hằng số cũ còn đó làm dấu vết lịch sử, nhưng KHÔNG được sinh ra ở bất kỳ ô nào nữa.
        listOf(idle, unmeasured, halfTaken).forEach { projection ->
            assertTrue(
                projection.zones.none {
                    it.disabledReason == BubbleZoneDisabledReason.SPLIT_NEEDS_THE_OTHER_HALF
                },
                "SPLIT_NEEDS_THE_OTHER_HALF đã hết đường sinh ra: ${projection.zones}",
            )
        }
    }

    /**
     * Luật §R7, vế thứ nhất: **ô đang có app thì chạm là TRẢ VỀ, không bao giờ là chiếu.**
     *
     * Đây là nửa quan trọng nhất của thiết kế: người lái không phải nhớ trạng thái, không phải mở menu —
     * nhìn ô đặc là biết chạm vào sẽ trả app đó về. Nếu một ô đã có app mà vẫn mời chiếu thì cú chạm sẽ
     * đè app mới lên app đang hiển thị, và (đo trên xe 31/7) app bị đè BIẾN MẤT hoàn toàn.
     */
    @Test
    fun `an occupied zone offers return and never cast`() {
        val projection = CastBubbleProjection.project(
            model(allowed = setOf(CastAction.CAST)), rows, null,
            occupancy = ClusterOccupancy.Measured(
                listOf(ClusterOccupant(BubbleZone.LEFT, vietmap.packageName)),
            ),
        )
        val left = projection.zone(BubbleZone.LEFT)!!
        assertEquals(BubbleZoneAction.RETURN, left.action)
        assertTrue(left.enabled, "trả app về là đường thoát — không bao giờ được khoá")
        assertTrue(left.occupied)
        assertEquals(vietmap.packageName, left.occupantPackage)
        // Nói bằng TÊN APP, không phải tên gói: người lái không đọc com.example.vietmap khi đang lái.
        assertTrue(left.message.contains("VietMap"), left.message)

        // Nửa còn lại vẫn trống ⇒ vẫn mời chiếu bình thường (§R7 hình 4).
        val right = projection.zone(BubbleZone.RIGHT)!!
        assertEquals(BubbleZoneAction.CAST, right.action)
        assertTrue(right.enabled)

        // Và ô cả cụm KHÔNG được mời chiếu full: chiếu full sẽ nuốt mất app đang ở nửa trái.
        val full = projection.zone(BubbleZone.FULL)!!
        assertFalse(full.enabled)
        assertEquals(BubbleZoneDisabledReason.HALVES_IN_USE, full.disabledReason)
        assertTrue(full.message.isNotBlank())
    }

    /**
     * Occupant ĐO ĐƯỢC là bằng chứng mạnh hơn mọi kết luận của máy trạng thái.
     *
     * Ca thật đứng sau: phiên bền chỉ ghi được MỘT `activeTarget`, nên khi cụm đang chia đôi thì
     * `activeTargetPackage` trỏ vào một trong hai app — đọc nó thành "cụm đang bị chiếm trọn" là bản đồ
     * nói dối, và ô cả cụm sẽ tự tô đặc trong khi thật ra cụm đang có hai app cạnh nhau.
     */
    @Test
    fun `measured halves win over the single-session target when both disagree`() {
        val projection = CastBubbleProjection.project(
            model(allowed = setOf(CastAction.STOP, CastAction.CAST), sessionConfirmed = true), rows, null,
            activeTargetPackage = maps.packageName,
            occupancy = ClusterOccupancy.Measured(
                listOf(
                    ClusterOccupant(BubbleZone.LEFT, maps.packageName),
                    ClusterOccupant(BubbleZone.RIGHT, vietmap.packageName),
                ),
            ),
        )
        assertTrue(projection.projecting, "hai app đo được trên cụm thì rõ ràng là đang chiếu")
        val full = projection.zone(BubbleZone.FULL)!!
        assertFalse(full.occupied, "cụm đang chia đôi, ô cả cụm không được tô đặc")
        assertNull(full.occupantPackage)
        assertEquals(BubbleZoneDisabledReason.HALVES_IN_USE, full.disabledReason)
        assertEquals(maps.packageName, projection.zone(BubbleZone.LEFT)?.occupantPackage)
        assertEquals(vietmap.packageName, projection.zone(BubbleZone.RIGHT)?.occupantPackage)
        assertTrue(projection.zone(BubbleZone.RIGHT)!!.action == BubbleZoneAction.RETURN)
    }

    /**
     * LUẬT DUY NHẤT do chủ dự án chốt 2026-08-01: **đang có app trên cụm thì chạm ô NÀO cũng là TRẢ VỀ
     * màn chính** — "không làm gì cả, nếu đang có app đang chiếu, nhấn bubble phải trả về màn chính, sau
     * đó mới start lại việc chiếu sang".
     *
     * Thay cho hành vi trước đó (hai nửa CÂM với `CLUSTER_TAKEN_FULLSCREEN`). Bản cũ bắt người lái nhìn
     * một ô khoá trong khi cụm rõ ràng đang có thứ để trả về — hai câu trả lời khác nhau cho cùng một câu
     * hỏi "cụm đang có gì?", tuỳ chạm trúng ô nào. Cả ba ô nay nói cùng một sự thật, và luật gọn tới mức
     * không cần học: có app ⇒ trả về; trống ⇒ chiếu.
     *
     * Ba ô đều tô ĐẶC ở đây vì bản đồ phải nói thật là cụm đang bị chiếm — không được vẽ hai nửa như chỗ
     * trống mời chiếu trong khi chiếu vào đó sẽ nuốt mất thứ người lái đang nhìn.
     */
    @Test
    fun `a fullscreen session claims the whole map and every zone returns it`() {
        val projection = CastBubbleProjection.project(
            model(allowed = setOf(CastAction.STOP, CastAction.CAST), sessionConfirmed = true), rows, null,
            activeTargetPackage = maps.packageName,
        )
        val full = projection.zone(BubbleZone.FULL)!!
        assertTrue(full.occupied)
        assertEquals(BubbleZoneAction.RETURN, full.action)
        assertEquals(maps.packageName, full.occupantPackage)
        assertTrue(full.message.contains("Google Maps"), full.message)
        listOf(BubbleZone.LEFT, BubbleZone.RIGHT).forEach { zone ->
            val cell = projection.zone(zone)!!
            assertTrue(cell.enabled, "$zone phải bấm được để TRẢ VỀ khi cụm đang bị chiếm trọn")
            assertEquals(BubbleZoneAction.RETURN, cell.action, "$zone phải là trả về, không phải chiếu")
            assertEquals(maps.packageName, cell.occupantPackage, "$zone phải nêu đúng app đang chiếm cụm")
            assertTrue(cell.occupied, "$zone phải tô đặc: cụm đang có app, không phải chỗ trống")
        }
    }

    /**
     * Ô trống chỉ mời chiếu khi CÓ GÌ ĐÓ để chiếu.
     *
     * Ba trạng thái, ba nghĩa khác nhau — và "chưa đo" KHÔNG được đọc thành "không có gì": nút nổi cố ý
     * chỉ đọc app đang mở đúng lúc chạm (tươi hơn, rẻ hơn nhịp 15 giây), nên ở [BubbleCastSource.UNMEASURED]
     * ô vẫn mời chiếu và sự thật được nói ra lúc chạm. Đó đúng là hành vi đã chạy ngoài hiện trường.
     *
     * Luật này được đọc trên MỌI ô còn trống — cả ô cả cụm lẫn hai nửa, kể cả khi nửa kia cũng trống
     * (2026-08-01, xem `an empty cluster invites a first app into either half`). Cửa bố cục duy nhất còn
     * đứng TRƯỚC và độc lập với `castSource` là [BubbleZoneDisabledReason.HALVES_IN_USE]: nó trả lời "cụm
     * có chỗ cho lượt này không", còn `castSource` trả lời "có gì để chiếu không".
     */
    @Test
    fun `an empty zone offers cast only when there is something to cast`() {
        val nothing = CastBubbleProjection.project(
            model(allowed = setOf(CastAction.CAST)), rows, null, castSource = BubbleCastSource.ABSENT,
        )
        val nothingFull = nothing.zone(BubbleZone.FULL)!!
        assertFalse(nothingFull.enabled, "không có gì để chiếu mà vẫn mời là mời hụt")
        assertEquals(BubbleZoneDisabledReason.NOTHING_TO_CAST, nothingFull.disabledReason)

        val present = CastBubbleProjection.project(
            model(allowed = setOf(CastAction.CAST)), rows, null, castSource = BubbleCastSource.PRESENT,
        )
        assertTrue(present.zone(BubbleZone.FULL)!!.enabled)
        val unmeasured = CastBubbleProjection.project(model(allowed = setOf(CastAction.CAST)), rows, null)
        assertTrue(unmeasured.zone(BubbleZone.FULL)!!.enabled, "chưa đo ≠ không có gì (CLAUDE.md §2)")

        // Cùng ba nghĩa ấy trên một nửa cụm THẬT SỰ chiếu được (nửa kia đã có app).
        fun halfWithSource(source: BubbleCastSource) = CastBubbleProjection.project(
            model(allowed = setOf(CastAction.CAST)), rows, null,
            occupancy = ClusterOccupancy.Measured(
                listOf(ClusterOccupant(BubbleZone.LEFT, vietmap.packageName)),
            ),
            castSource = source,
        ).zone(BubbleZone.RIGHT)!!
        assertEquals(BubbleZoneDisabledReason.NOTHING_TO_CAST, halfWithSource(BubbleCastSource.ABSENT).disabledReason)
        assertTrue(halfWithSource(BubbleCastSource.PRESENT).enabled)
        assertTrue(halfWithSource(BubbleCastSource.UNMEASURED).enabled, "chưa đo ≠ không có gì (CLAUDE.md §2)")

        // Ô đã có app thì "không có gì đang mở" không liên quan: trả về vẫn phải chạm được.
        val occupied = CastBubbleProjection.project(
            model(), rows, null, castSource = BubbleCastSource.ABSENT,
            occupancy = ClusterOccupancy.Measured(listOf(ClusterOccupant(BubbleZone.RIGHT, maps.packageName))),
        )
        val right = occupied.zone(BubbleZone.RIGHT)!!
        assertTrue(right.enabled)
        assertEquals(BubbleZoneAction.RETURN, right.action)
    }

    /**
     * Bản đồ 3 ô GIỮ NGUYÊN sự phân đôi tô-≠-chạm của §R3 (2026-07-31), không gộp lại.
     *
     * Ca đo được: CarPlay kẹt RECOVERING vẫn xuất Stop nhưng chưa xác minh phiên nào. Ô cả cụm vì thế
     * phải để VIỀN (chưa có gì được chứng minh là đang trên cụm) mà chạm vào vẫn phải là DỪNG — Stop là
     * `nextSafeAction` duy nhất của trạng thái đó, và nút nổi thường là bề mặt duy nhất còn nhìn thấy
     * khi CarPlay/AA chiếm màn chính. Nếu ô này quay ra mời CHIẾU, cú chạm sẽ phát một lượt cast mới lên
     * một cụm đang kẹt — đúng vòng tròn CLAUDE.md §3 cấm.
     */
    @Test
    fun `a stuck recovery paints the full zone empty but a tap on it still returns`() {
        val recovering = CastBubbleProjection.project(
            model(allowed = setOf(CastAction.STOP), sessionConfirmed = false), rows, null,
        )
        assertFalse(recovering.projecting)
        assertTrue(recovering.stopOnTap)
        val full = recovering.zone(BubbleZone.FULL)!!
        assertFalse(full.occupied, "chưa xác minh gì thì không được tô đặc")
        assertEquals(BubbleZoneAction.RETURN, full.action, "nhưng chạm PHẢI vẫn là Dừng")
        assertTrue(full.enabled)
        // Và vì cụm đang bị coi là "có chủ", hai nửa TUYỆT ĐỐI không được mời chiếu chồng lên trạng thái
        // kẹt đó — theo luật 2026-08-01 chúng cũng là đường TRẢ VỀ, giống hệt ô cả cụm. Điều bất biến ở
        // đây không phải "câm" mà là "không bao giờ là CAST": một cú chạm phát cast mới lên cụm đang kẹt
        // chính là vòng tròn CLAUDE.md §3 cấm.
        listOf(BubbleZone.LEFT, BubbleZone.RIGHT).forEach {
            val cell = recovering.zone(it)!!
            assertEquals(BubbleZoneAction.RETURN, cell.action, "$it không được mời chiếu khi cụm đang kẹt")
            assertTrue(cell.enabled, "$it vẫn phải bấm được — Stop là lối thoát duy nhất của trạng thái này")
        }
    }

    /**
     * Phiên legacy đã xác minh: Stop được xuất ra, không đọc được tên gói.
     *
     * Ô cả cụm vẫn phải TÔ ĐẶC — nếu không, chủ xe tưởng cụm đã sạch mà thật ra chưa — và vẫn phải trả
     * về được dù không gọi được tên app. Đây là lý do `occupied` là một trường riêng chứ không suy ra từ
     * `occupantPackage != null`.
     */
    @Test
    fun `a confirmed nameless session still fills the full zone`() {
        val nameless = CastBubbleProjection.project(
            model(allowed = setOf(CastAction.STOP), sessionConfirmed = true), rows, null,
        )
        val full = nameless.zone(BubbleZone.FULL)!!
        assertTrue(full.occupied)
        assertNull(full.occupantPackage)
        assertEquals(BubbleZoneAction.RETURN, full.action)
        assertTrue(full.message.isNotBlank())
    }

    /**
     * Đôi với `a real stop in flight keeps the bubble solid for the whole wait`, nhưng theo ô.
     *
     * Đang chờ Stop hội tụ mà ô cả cụm rỗng ra là mời đúng một cú chạm sai: người lái tưởng cụm đã sạch
     * và chạm thêm một lượt chiếu mới lên cụm đang dừng dở.
     */
    @Test
    fun `a stop in flight keeps the full zone solid and still offers return`() {
        val stopping = CastBubbleProjection.project(
            model(title = "Đang xử lý", sessionConfirmed = false), rows, null,
            localStopRequested = true, activeTargetPackage = maps.packageName,
        )
        val full = stopping.zone(BubbleZone.FULL)!!
        assertTrue(full.occupied)
        assertEquals(BubbleZoneAction.RETURN, full.action)
        assertEquals(maps.packageName, full.occupantPackage)
    }

    /**
     * `Measured(emptyList())` = ĐÃ ĐO và cụm trống — khác hẳn `Unmeasured` (chưa ai đo).
     *
     * Với cụm đã đo là trống, một `activeTargetPackage` còn sót vẫn thắng (phiên bền là nguồn của
     * `stopOnTap`), nên ô cả cụm vẫn trả về được: không có ca nào mà app còn trên cụm mà không còn
     * đường gỡ xuống.
     */
    @Test
    fun `a measured empty cluster is not the same thing as an unmeasured one`() {
        val measuredEmpty = CastBubbleProjection.project(
            model(allowed = setOf(CastAction.CAST)), rows, null,
            occupancy = ClusterOccupancy.Measured(emptyList()),
        )
        assertEquals(0, measuredEmpty.measuredOccupants)
        assertFalse(measuredEmpty.projecting)
        assertTrue(measuredEmpty.zones.all { it.action == BubbleZoneAction.CAST })
        // Ô cả cụm — đường một-app đã field-proven — vẫn mời chiếu. Hai nửa thì không: xem
        // `a half only offers a cast when the other half already holds an app` (bản vá [P0] Pass 2).
        assertTrue(measuredEmpty.zone(BubbleZone.FULL)!!.enabled)

        val staleTarget = CastBubbleProjection.project(
            model(allowed = setOf(CastAction.STOP)), rows, null,
            activeTargetPackage = maps.packageName,
            occupancy = ClusterOccupancy.Measured(emptyList()),
        )
        assertEquals(BubbleZoneAction.RETURN, staleTarget.zone(BubbleZone.FULL)?.action)
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

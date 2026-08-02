package com.byd.clusternav.modules.clustercast.v2

import java.io.IOException
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Khoá lại sự cố app-op PICTURE_IN_PICTURE đo được trên xe 2026-07-31.
 *
 * Sau vài transaction cast bị kẹt với vn.vietmap.live, `appops get vn.vietmap.live
 * PICTURE_IN_PICTURE` vẫn trả `deny`: bong bóng tốc độ của CHÍNH VietMap chết lặng, không báo lỗi,
 * chủ xe chỉ nhận ra vì nó không bao giờ hiện lại. Đặt tay về `allow` là app chạy lại bình thường —
 * tức app-op đúng là toàn bộ nguyên nhân. Không có gì trong app trả nó về.
 *
 * Hai lỗ hổng đã lần ra bằng đọc source, và bài kiểm này khoá cả hai:
 *
 *  1. Cold bootstrap nhận NGUYÊN mode đang quan sát làm baseline (CastColdBootstrap.succeed). Nếu
 *     tiến trình chết trong lúc PIP đang bị chặn thì `deny`/`ignore` trở thành "thế giới trước khi
 *     ClusterNav động vào", và mọi lần RESTORE_PIP sau đó sẽ trung thành ghi lại chính cái chặn đó.
 *  2. RESTORE_PIP chỉ được lên kế hoạch trong nhánh STOP, mà STOP lại bị từ chối thẳng khi chưa
 *     journal được baseline geometry (CastPlanner.kt:37-39) — nên có trường hợp không còn đường nào
 *     gỡ block. CLAUDE.md §5: thứ gì đổi ra ngoài hệ thống phải có đường trả lại chạy được cả khi
 *     tiến trình đã chết.
 */
class CastPipResidueTest {

    // ─────────────────────────────────────────────────────────────────────────────
    // (a) baseline không bao giờ nuốt chính bã của mình
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `mode PIP dang bi chan khong bao gio duoc ghi vao baseline cold bootstrap`() {
        // "ignore" đúng là thứ rung BLOCK_PIP ghi ra (CastPlacementCommands.kt:115-119); "deny" là
        // thứ đo được trên xe 2026-07-31. Cả hai đều là bã của ClusterNav chứ không phải thế giới
        // trước đó, nên không được phép trở thành baseline.
        CastPipBaseline.BLOCKED_MODES.forEach { blocked ->
            assertNull(
                bootstrapBaseline(observedPipMode = blocked),
                "baseline nuot mode chan '$blocked' → moi lan RESTORE_PIP sau do se ghi lai cai chan",
            )
        }
    }

    @Test
    fun `mode PIP khong bi chan van duoc giu nguyen trong baseline`() {
        // Bản vá chỉ được bỏ đúng giá trị chặn, không được xoá trắng thông tin thật: một mode lành
        // vẫn phải journal nguyên văn để RESTORE_PIP trả về đúng cái đã có, không phải đoán.
        assertEquals("allow", bootstrapBaseline(observedPipMode = "allow"))
        assertEquals("default", bootstrapBaseline(observedPipMode = "default"))
        assertNull(bootstrapBaseline(observedPipMode = null))
    }

    @Test
    fun `tu vung mode chan chi gom dung nhung gi dat ten duoc`() {
        // CLAUDE.md §2/§3: chỉ khẳng định thứ đã chứng minh. "ignore" đọc từ chính lệnh BLOCK_PIP,
        // "deny" đo trên xe. Mặc định nền tảng của app-op này CHƯA verify trong source AOSP nên
        // "default" không được coi là chặn — không đoán, và cũng không tự ghi đè.
        assertEquals(setOf("ignore", "deny"), CastPipBaseline.BLOCKED_MODES)
        assertTrue(CastPipBaseline.isBlocked("ignore"))
        assertTrue(CastPipBaseline.isBlocked("deny"))
        assertTrue(CastPipBaseline.isBlocked(" DENY "), "dump co the tra ve hoa/thua khoang trang")
        assertFalse(CastPipBaseline.isBlocked("allow"))
        assertFalse(CastPipBaseline.isBlocked("default"))
        assertFalse(CastPipBaseline.isBlocked("foreground"))
        assertFalse(CastPipBaseline.isBlocked(null))
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // (b) có đường trả app-op về mà không phụ thuộc STOP
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `RECOVER tra app-op ve khi quan sat thay owner dang bi chan`() {
        CastPipBaseline.BLOCKED_MODES.forEach { blocked ->
            val steps = recoverSteps(observedPipMode = blocked)
            assertEquals(
                listOf(CommandKind.DISCONNECTED_SINK_RECOVERY_ONCE, CommandKind.RESTORE_PIP),
                steps,
                "mode '$blocked' dang chan ma RECOVER khong co duong tra app-op ve",
            )
            // Thang đã kiểm thực địa phải giữ nguyên vị trí: rung force-stop vẫn là bước ĐẦU TIÊN,
            // bước mới xuống CUỐI (CLAUDE.md §6). Nếu `appops set` hỏng thì hành động chữa cháy thật
            // sự đã phát đi và đã journal xong từ trước.
            assertEquals(CommandKind.DISCONNECTED_SINK_RECOVERY_ONCE, steps.first())
            assertEquals(CommandKind.RESTORE_PIP, steps.last())
        }
    }

    @Test
    fun `RECOVER giu nguyen dung mot buoc khi khong co bang chung nao`() {
        // Không có bằng chứng nào thì không được đụng vào app-op của app khác: thang RECOVER giữ
        // nguyên đúng hình dạng đã kiểm thực địa.
        listOf(null, "allow", "default", "foreground").forEach { mode ->
            assertEquals(
                listOf(CommandKind.DISCONNECTED_SINK_RECOVERY_ONCE),
                recoverSteps(observedPipMode = mode),
                "mode '$mode' khong phai bang chung bi chan ma van phat lenh appops",
            )
        }
    }

    @Test
    fun `RECOVER khong dung app-op cua app KHAC lam bang chung cho owner`() {
        // `observed.pipMode` chỉ được đọc cho TARGET đang quan sát (DumpObservedStateParser, dòng
        // `val pipMode = target?.packageName?.let`, CastDeviceParsers.kt:185). Khi
        // owner là residue chứ không phải target thì giá trị đó thuộc về app khác — dùng nó để quyết
        // định là quy kết sai (CLAUDE.md §2), dù kết quả có "trông hợp lý".
        val owner = "com.example.carplay"
        val other = CastTarget("com.example.maps", 7, 2)
        val observed = ObservedState(
            ObservedCoarseState.ACTIVE_MULTI, "display-2", other,
            setOf(other.packageName, owner),
            ProtectedResidue(owner, 9, ResidueVisibility.HIDDEN),
            null, emptyMap(), "ignore", "android-user-10",
        )
        assertEquals(
            listOf(CommandKind.DISCONNECTED_SINK_RECOVERY_ONCE),
            plannedRecoverSteps(observed, owner, stableSession = null),
        )
    }

    @Test
    fun `RECOVER van tra ve khi baseline da journal mot mode lanh`() {
        // Có baseline lành thì RESTORE_PIP trả về ĐÚNG giá trị đã ghi, không đoán — hợp lệ kể cả khi
        // quan sát hiện tại không đọc được app-op nào.
        assertEquals(
            listOf(CommandKind.DISCONNECTED_SINK_RECOVERY_ONCE, CommandKind.RESTORE_PIP),
            recoverSteps(observedPipMode = null, baselinePipMode = "allow"),
        )
    }

    @Test
    fun `RECOVER khong ghi lai chinh cai chan con sot trong envelope cu`() {
        // Envelope ghi TRƯỚC bản vá CastPipBaseline có thể đang giữ baseline `ignore`/`deny`.
        // RESTORE_PIP ghi ra đúng mode trong baseline (CastPlacementCommands.kt:210-212), nên phát
        // lệnh lúc đó là tự tay chặn lần nữa. Thà không phát còn hơn — đường sửa đúng nằm ở tầng
        // transport và nằm ngoài phạm vi thay đổi này.
        CastPipBaseline.BLOCKED_MODES.forEach { blocked ->
            val steps = recoverSteps(observedPipMode = blocked, baselinePipMode = blocked)
            assertEquals(listOf(CommandKind.DISCONNECTED_SINK_RECOVERY_ONCE), steps)
            assertFalse(CommandKind.RESTORE_PIP in steps)
        }
    }

    @Test
    fun `thang STOP khong bi dao thu tu boi ban va nay`() {
        // Thứ tự STOP là thứ đã chạy tốt ngoài hiện trường (ExpectedLadder.stopNormal, đo 2026-07-26
        // trên DiLink3). CLAUDE.md §6 cấm đảo nó để chữa một lỗi khác, nên khoá lại ngay tại đây.
        val target = CastTarget("com.example.maps", 7, 2)
        val geometry = AcceptedGeometry(CastRect(0, 0, 1920, 720), 180, "android-user-10")
        val observed = ObservedState(
            ObservedCoarseState.ACTIVE_SINGLE, "display-2", target, setOf(target.packageName), null,
            geometry, emptyMap(), "ignore", "android-user-10",
        )
        val stable = StableCastSession(
            StableState.ACTIVE_VERIFIED, EngineVersion.V2, "test", null, "display-2",
            CastBaseline(geometry = geometry), target, null, geometry, 1,
        )
        val ready = CastPlanner.plan(
            CastIntent(CastIntentKind.STOP, target.packageName),
            PlannerSnapshot(ObservationValue.Known(observed), stable, TargetClass.NORMAL, true, true, 4),
        ) as PlanResult.Ready
        assertEquals(ExpectedLadder.stopNormal, ready.plan.steps.map { it.commandKind })
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Chạy nguyên đường cold bootstrap và trả về pipMode đã journal.
     *
     * Từ 2026-08-01 thang opcode LUÔN được phát, kể cả khi display cụm đã tồn tại sẵn — cổng `adopt` cũ
     * là giả định sai đã ngốn trọn đêm 31/7 (xem `CastColdBootstrap.run` và
     * `CastColdBootstrapTest.an existing clean cluster display still dispatches the full seal ladder`).
     * Khẳng định về danh sách lệnh ở đây chỉ là phụ — trọng tâm test này là baseline pipMode.
     */
    private fun bootstrapBaseline(observedPipMode: String?): String? {
        val store = CastSessionStore(MemoryAtomicBytes())
        store.locked {
            initialize("boot")
            update { it.copy(effectiveUiVersion = EngineVersion.V2) }
        }
        val issued = mutableListOf<CommandKind>()
        val executor = CastExecutor(
            store,
            CastMutationGateway { request -> issued += request.kind; MutationResult.Observed("unexpected") },
            nowEpochMillis = { 1_000L },
            operationId = { UUID.fromString("00000000-0000-0000-0000-0000000000b4") },
            bootstrapVerificationAttempts = 3,
            bootstrapVerificationPollMillis = 25L,
            sleeper = CastSleeper { _ -> },
        )
        val idle = ObservationValue.Known(idleState(observedPipMode))
        val result = executor.bootstrap(
            SealDl3BootstrapProfile.exactFacts,
            { _ -> ObservationValue.Known(rawAdoptable()) },
            { _ -> idle },
        )
        assertTrue(result is ColdBootstrapResult.Succeeded, result.toString())
        assertEquals(
            SealDl3BootstrapProfile.forwardKinds, issued,
            "display cum co san van phai gui du thang opcode — xem KDoc cua helper nay",
        )
        val stable = ((store.locked { read() } as StoreRead.Loaded).envelope).stableSession!!
        return stable.baseline.pipMode
    }

    /** Quan sát bootstrap hợp lệ: cụm rỗng, tên display thật lấy từ dump xe. */
    private fun idleState(pipMode: String?) = ObservedState(
        ObservedCoarseState.IDLE_CLEAN, "display-2", null, emptySet(), null,
        AcceptedGeometry(CastRect(0, 0, 1920, 720), 180, "android-user-10"),
        mapOf(
            "window_animation_scale" to "1.0",
            "transition_animation_scale" to "0.5",
            "animator_duration_scale" to "1.0",
        ),
        pipMode, "android-user-10", "fission_bg_xdjaVirtualSurface",
    )

    private fun rawAdoptable() = RawObservation(
        amStacks = "Stack id=0 bounds=[0,0][1920,1080] displayId=0 userId=0\n" +
            "  taskId=1: com.example.launcher/.Main bounds=[0,0][1920,1080] userId=0",
        wmDisplays = "Display: mDisplayId=0",
        displays = SEAL_CLUSTER_DUMP,
        profile = "10", animations = "1.0\n0.5\n1.0", appOps = "No app ops",
    )

    /** RECOVER với owner CHÍNH LÀ target đang quan sát — hình dạng thường gặp khi sink rớt phiên. */
    private fun recoverSteps(observedPipMode: String?, baselinePipMode: String? = null): List<CommandKind> {
        val owner = "com.example.carplay"
        val observed = ObservedState(
            ObservedCoarseState.ACTIVE_SINGLE, "display-2", CastTarget(owner, 7, 2), setOf(owner), null,
            null, emptyMap(), observedPipMode, "android-user-10",
        )
        val stable = baselinePipMode?.let {
            StableCastSession(
                StableState.RECOVERY_PENDING, EngineVersion.V2, "test", null, "display-2",
                CastBaseline(pipMode = it), null, null, null, 1,
            )
        }
        return plannedRecoverSteps(observed, owner, stable)
    }

    private fun plannedRecoverSteps(
        observed: ObservedState,
        owner: String,
        stableSession: StableCastSession?,
    ): List<CommandKind> {
        val proof = DisconnectedSinkRecoveryProof(
            owner, observed, observed, phoneDisconnected = true,
            projectionComponent = true, consequenceConfirmed = true,
        )
        val result = CastPlanner.plan(
            CastIntent(CastIntentKind.RECOVER, owner, recoveryProof = proof),
            PlannerSnapshot(
                ObservationValue.Known(observed), stableSession, TargetClass.PROJECTION_SINK,
                installed = true, hasLauncher = true, plannerEpoch = 7,
            ),
        )
        val ready = result as? PlanResult.Ready
            ?: throw AssertionError("RECOVER phai lap ke hoach duoc, khong duoc chan: $result")
        return ready.plan.steps.map { it.commandKind }
    }

    private class MemoryAtomicBytes : AtomicBytes {
        private var bytes: ByteArray? = null
        override fun exists() = bytes != null
        override fun read(): ByteArray = bytes?.copyOf() ?: throw IOException("missing")
        override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
    }
}

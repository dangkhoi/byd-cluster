package com.byd.clusternav.modules.clustercast.v2

import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Khoá đúng đêm 31/07–01/08 trên DiLink3: một transaction CAST kẹt ở `RECOVERING` trong khi HAI app lạ
 * cùng đậu trên display cụm, và trong sản phẩm KHÔNG có đường nào gỡ ra — chủ xe phải xoá dữ liệu app
 * năm lần. `recover()` bất lực vì thang đặt app không khai báo bước bù hoàn nào
 * (`CastExecutor.compensationFor`), còn `reconcileAbandoned()` từ chối thẳng vì nó đòi cụm đã IDLE_CLEAN —
 * mà "cụm còn app" mới chính là vấn đề.
 *
 * Dump `am stack list` dưới đây là dump THẬT của đêm đó (giữ nguyên chữ, dùng chung fixture với
 * `CastMultiOccupantObservationTest`), chỉ thêm một stack display 0 cho giống máy thật:
 *
 *   taskId=15: app.revanced.android.apps.maps/... visible=true
 *   taskId=14: vn.vietmap.live/...               visible=true
 *
 * Bốn điều bộ test này khoá, mỗi điều là một cách hỏng đã có thật:
 *  1. MỌI app trên cụm đều được trả về display 0 — danh sách lấy từ dump, không phải danh sách gói cứng.
 *  2. Hai opcode đóng đường chiếu (18 rồi 0) là hai lệnh CUỐI CÙNG, đúng thứ tự. ĐO THẬT: dời task ra
 *     khỏi cụm KHÔNG trả lại đồng hồ — cụm đứng hình ở khung cũ cho tới khi 18 rồi 0 được gửi.
 *  3. Không force-stop bất cứ thứ gì (CLAUDE.md §4: trả app về là nhẹ, giết app thì không).
 *  4. Nhật ký kết thúc TRUNG THỰC: envelope về đúng danh sách pristine của `CastColdBootstrapPreflight`,
 *     nên lượt chiếu kế tiếp chạy lại thang AutoContainer thay vì đặt app lên một display ảo mà đường
 *     chiếu vừa bị đóng.
 */
class CastClearClusterTest {

    private val mapsPackage = "app.revanced.android.apps.maps"
    private val vietmapPackage = "vn.vietmap.live"

    /** Dump thật đêm 31/07 (2 app cùng visible=true trên display cụm) + một stack display 0. */
    private val amStacksTwoOccupants = """
        Stack id=0 bounds=[0,0][1920,1080] displayId=0 userId=0
          taskId=1: com.byd.launcher/.Main bounds=[0,0][1920,1080] userId=0 visible=true

        Stack id=17 bounds=[0,0][1920,720] displayId=2 userId=0
         mBounds=Rect(0, 0 - 1920, 720)
          taskId=15: app.revanced.android.apps.maps/com.google.android.maps.MapsActivity bounds=[0,0][1920,720] userId=0 visible=true topActivity=ComponentInfo{app.revanced.android.apps.maps/com.google.android.maps.MapsActivity}

        Stack id=18 bounds=[0,0][1920,720] displayId=2 userId=0
          taskId=14: vn.vietmap.live/vn.vietmap.live.MainActivity bounds=[0,0][1920,720] userId=0 visible=true topActivity=ComponentInfo{vn.vietmap.live/vn.vietmap.live.MainActivity}
    """.trimIndent()

    @Test
    fun `a wedged transaction with two foreign occupants returns both, then closes the projection last`() {
        val fixture = fixture(wedged = true, stopRequested = false)
        fixture.observations += clusterIsEmpty()

        val result = fixture.coordinator.clearCluster(SealDl3BootstrapProfile.exactFacts)

        assertTrue(result is ClusterClearResult.Ran, "expected the ladder to run, got $result")
        val ran = result as ClusterClearResult.Ran
        assertEquals(2, ran.clusterDisplayId, "only the named cluster display may ever be cleared")
        assertEquals(listOf(mapsPackage, vietmapPackage), ran.returned)
        assertEquals(emptyMap<String, String>(), ran.failures)
        assertTrue(ran.cleared, "a fresh observation showed the cluster empty and the projection closed")

        // ── The exact dispatched sequence. Both occupants first, then 18 then 0, and nothing else. ──
        assertEquals(
            listOf(
                CommandKind.RETURN_NORMAL_TO_MAIN,
                CommandKind.RETURN_NORMAL_TO_MAIN,
                CommandKind.SEAL_DL3_COMPENSATE_18,
                CommandKind.SEAL_DL3_COMPENSATE_0,
            ),
            fixture.gateway.kinds(),
        )
        assertEquals(
            listOf(mapsPackage, vietmapPackage, null, null),
            fixture.gateway.requests.map { it.targetPackage },
            "each return must name its own occupant; the close opcodes name nobody",
        )
        // Nói riêng thành một khẳng định: hai opcode đóng chiếu phải nằm CUỐI. Nếu ai đó chèn thêm rung
        // mới sau chúng, người lái sẽ mất đồng hồ trong khoảng thời gian đó.
        assertEquals(
            listOf(CommandKind.SEAL_DL3_COMPENSATE_18, CommandKind.SEAL_DL3_COMPENSATE_0),
            fixture.gateway.kinds().takeLast(2),
        )
        assertTrue(
            fixture.gateway.kinds().none {
                it == CommandKind.FORCE_STOP_NORMAL || it == CommandKind.START_FRESH_NORMAL
            },
            "returning an app is gentle; killing it is not (CLAUDE.md §4). Dispatched: ${fixture.gateway.kinds()}",
        )
        // CLAUDE.md §4 câu 2: chỉ app trên ĐÚNG display cụm. Cùng dump đó có `com.byd.launcher` đang giữ
        // taskId=1 trên display 0 — chạm vào nó chính là cách làm đơ launcher ngày 2026-07-21.
        assertTrue(
            fixture.gateway.requests.none { it.targetPackage == "com.byd.launcher" },
            "nothing on display 0 may ever be enumerated or targeted",
        )

        // ── The journal ends in an honest terminal state. ──
        val envelope = fixture.envelope()
        assertNull(envelope.transaction, "the wedged journal must be closed, not left for the next boot")
        assertNull(envelope.stableSession, "no claim survives: the projection this session described is closed")
        assertNull(envelope.pendingIntent)
        assertNull(envelope.adjustmentDraft)
        assertFalse(envelope.stopRequested)
    }

    @Test
    fun `the same clear runs with no transaction at all, because a dirty cluster is reason enough`() {
        // Một cụm bẩn KHÔNG cần phải có transaction nào đang kẹt: chủ xe có thể chỉ muốn dọn. Trước đây
        // đây là ca không ai phục vụ — `reconcileAbandoned` đòi có transaction, `recover` đòi có sổ.
        val fixture = fixture(wedged = false, stopRequested = false)
        fixture.observations += clusterIsEmpty()

        val result = fixture.coordinator.clearCluster(SealDl3BootstrapProfile.exactFacts)

        assertTrue((result as ClusterClearResult.Ran).cleared)
        assertEquals(listOf(mapsPackage, vietmapPackage), result.returned)
        assertEquals(
            listOf(
                CommandKind.RETURN_NORMAL_TO_MAIN,
                CommandKind.RETURN_NORMAL_TO_MAIN,
                CommandKind.SEAL_DL3_COMPENSATE_18,
                CommandKind.SEAL_DL3_COMPENSATE_0,
            ),
            fixture.gateway.kinds(),
        )
    }

    @Test
    fun `a pending Stop is cleared only because the close-projection opcodes really ran`() {
        // Đối chiếu có chủ ý với `CastReconcileAbandonedTest`: ở ĐÓ `stopRequested` phải SỐNG SÓT, vì
        // reconciler kia không phát 18/0 bao giờ nên yêu cầu Dừng của tài xế vẫn còn nợ. Ở ĐÂY chính hai
        // opcode ấy vừa được phát, tức việc tài xế yêu cầu đã làm xong — và giữ cờ lại sẽ khoá mọi ý định
        // sau này mà không còn đường gỡ (Stop bị từ chối khi không có baseline geometry).
        val fixture = fixture(wedged = true, stopRequested = true)
        fixture.observations += clusterIsEmpty()

        val result = fixture.coordinator.clearCluster(SealDl3BootstrapProfile.exactFacts)

        assertTrue((result as ClusterClearResult.Ran).projectionClosed)
        assertFalse(fixture.envelope().stopRequested, "the Stop the driver asked for was actually carried out")
    }

    @Test
    fun `a pending Stop survives when the close-projection opcodes did not report an effect`() {
        val fixture = fixture(wedged = true, stopRequested = true)
        fixture.gateway.refuse = { request ->
            "no such service".takeIf { request.kind == CommandKind.SEAL_DL3_COMPENSATE_18 }
        }
        fixture.observations += clusterIsEmpty()

        val result = fixture.coordinator.clearCluster(SealDl3BootstrapProfile.exactFacts) as ClusterClearResult.Ran

        assertFalse(result.projectionClosed)
        assertFalse(result.cleared, "no gauges means no success, whatever WindowManager says")
        assertTrue(
            fixture.envelope().stopRequested,
            "the projection was never closed, so the Stop is still owed and must not be silently dropped",
        )
    }

    @Test
    fun `a refused return still hands the driver the gauges back`() {
        // Ưu tiên: đồng hồ của người đang lái > một app bướng bỉnh. Một rung hỏng KHÔNG được chặn thang.
        val fixture = fixture(wedged = true, stopRequested = false)
        fixture.gateway.refuse = { request ->
            "component not resolved".takeIf {
                request.kind == CommandKind.RETURN_NORMAL_TO_MAIN && request.targetPackage == mapsPackage
            }
        }
        fixture.observations += ObservedState(
            ObservedCoarseState.ACTIVE_SINGLE, "display-2", CastTarget(mapsPackage, 15, 2),
            setOf(mapsPackage), null, null, emptyMap(), null, "android-user-10", "fission_bg_xdjaVirtualSurface",
        )

        val result = fixture.coordinator.clearCluster(SealDl3BootstrapProfile.exactFacts) as ClusterClearResult.Ran

        assertEquals(listOf(vietmapPackage), result.returned)
        assertEquals(setOf(mapsPackage), result.failures.keys)
        assertTrue(result.projectionClosed, "the close opcodes must run even after a rung failed")
        assertFalse(result.cleared, "an occupant is still observed on the cluster, so this is not success")
        assertEquals(setOf(mapsPackage), result.remainingOccupants)
        assertEquals(
            listOf(CommandKind.SEAL_DL3_COMPENSATE_18, CommandKind.SEAL_DL3_COMPENSATE_0),
            fixture.gateway.kinds().takeLast(2),
        )
    }

    @Test
    fun `after a successful clear the durable envelope can cold-bootstrap again`() {
        // Đây là lý do `stableSession` bị đặt về null chứ không phải IDLE_VERIFIED: chỉ khi envelope
        // pristine thì `CastManualIntentRunner.run` mới chạy lại thang AutoContainer. Giữ một phiên
        // IDLE_VERIFIED sau khi đã đóng đường chiếu chính là con bug đã ngốn cả đêm — app lên display ảo,
        // cụm vật lý vẫn đồng hồ.
        val fixture = fixture(wedged = true, stopRequested = true)
        fixture.observations += clusterIsEmpty()

        fixture.coordinator.clearCluster(SealDl3BootstrapProfile.exactFacts)

        val preflight = CastColdBootstrapPreflight.inspect(
            fixture.envelope(), SealDl3BootstrapProfile.exactFacts, clearedRaw(),
        )
        assertTrue(preflight is ColdBootstrapPreflight.Ready, "expected Ready, got $preflight")
    }

    @Test
    fun `an unidentifiable cluster display dispatches nothing and leaves the journal untouched`() {
        // CLAUDE.md §4 câu 1: không quét mù, không đoán display. Không nhận ra cụm thì không phát gì —
        // và tuyệt đối không rơi về display 0, nơi tài xế đang nhìn.
        val fixture = fixture(wedged = true, stopRequested = false, displays = MAIN_ONLY_DUMP)

        val result = fixture.coordinator.clearCluster(SealDl3BootstrapProfile.exactFacts)

        assertTrue(result is ClusterClearResult.Blocked, "expected Blocked, got $result")
        assertEquals(MISSING_NAMED_CLUSTER_DISPLAY_REASON, (result as ClusterClearResult.Blocked).reason)
        assertTrue(fixture.gateway.requests.isEmpty(), "nothing may be dispatched blind: ${fixture.gateway.kinds()}")
        assertTrue(fixture.envelope().transaction != null, "a refusal must not silently close the journal")
    }

    @Test
    fun `a vehicle generation with no field-verified close sequence returns the apps but says the gauges are not back`() {
        // CLAUDE.md §7: khác biệt giữa các đời xe nằm ở catalog, không rải rác trong code. DL5 chưa có
        // chuỗi opcode đã kiểm thực địa, nên KHÔNG được gửi mù — nhưng nửa WindowManager thì generic và
        // vẫn chạy được, nên vẫn trả app về display 0.
        val fixture = fixture(wedged = true, stopRequested = false)
        fixture.observations += clusterIsEmpty()

        val result = fixture.coordinator.clearCluster(
            SealDl3BootstrapProfile.exactFacts.copy(product = "DiLink5", device = "DiLink5"),
        ) as ClusterClearResult.Ran

        assertEquals(listOf(mapsPackage, vietmapPackage), result.returned)
        assertFalse(result.projectionClosed)
        assertFalse(result.cleared)
        assertEquals(
            listOf(CommandKind.RETURN_NORMAL_TO_MAIN, CommandKind.RETURN_NORMAL_TO_MAIN),
            fixture.gateway.kinds(),
            "no opcode may be sent sight-unseen to hardware that was never field-verified",
        )
    }

    // ── fixture ────────────────────────────────────────────────────────────────────────────────────

    private fun clusterIsEmpty() = ObservedState(
        ObservedCoarseState.IDLE_CLEAN, "display-2", null, emptySet(), null,
        AcceptedGeometry(CastRect(0, 0, 1920, 720), 180, "android-user-10"),
        emptyMap(), null, "android-user-10", "fission_bg_xdjaVirtualSurface",
    )

    private fun clearedRaw() = RawObservation(
        amStacks = "Stack id=0 bounds=[0,0][1920,1080] displayId=0 userId=0\n" +
            "  taskId=1: com.byd.launcher/.Main bounds=[0,0][1920,1080] userId=0",
        wmDisplays = "Display: mDisplayId=0",
        displays = SEAL_CLUSTER_DUMP,
        profile = "10",
        animations = "1.0\n0.5\n1.0",
        appOps = "No app ops",
    )

    private fun fixture(
        wedged: Boolean,
        stopRequested: Boolean,
        displays: String = SEAL_CLUSTER_DUMP,
    ): Fixture {
        val store = CastSessionStore(MemoryAtomicBytes())
        val baseline = CastBaseline(
            occupants = emptySet(),
            geometry = AcceptedGeometry(CastRect(0, 0, 1920, 720), 180, "android-user-10"),
            animationPerKey = mapOf(
                "window_animation_scale" to "1.0",
                "transition_animation_scale" to "0.5",
                "animator_duration_scale" to "1.0",
            ),
            pipMode = "allow",
            profile = "android-user-10",
        )
        // Đúng hình dạng transaction kẹt đã đọc được trên xe: RECOVERING, một bước còn ISSUED (nên
        // `CastRecovery.decide` trả Manual), và không bước nào có compensation để mà hoàn tác.
        val stuck = CastTransaction(
            UUID(0L, 931L), 0L, CastOperation.CAST, OperationPhase.RECOVERING,
            null, mapsPackage, TargetClass.NORMAL.name, "display-2", baseline,
            listOf(
                LedgerStep("place-keep-session", "known", CommandKind.PLACE_KEEP_SESSION, 0L, 1L, LedgerEffect.OBSERVED, null, false),
                LedgerStep("fit-cluster", "known", CommandKind.FIT_CLUSTER_COMPOSITE, 0L, 1L, LedgerEffect.ISSUED, null, false),
            ),
            0, 900_000L, "unknown effect: gateway threw", "ACTIVE_VERIFIED", false,
        )
        store.locked {
            initialize("boot")
            update {
                it.copy(
                    effectiveUiVersion = EngineVersion.V2,
                    stableSession = StableCastSession(
                        StableState.ACTIVE_VERIFIED, EngineVersion.V2, "runtime", null, "display-2",
                        baseline, CastTarget(mapsPackage, 15, 2), null, baseline.geometry, 1L,
                    ),
                    transaction = if (wedged) stuck else null,
                    stopRequested = stopRequested,
                )
            }
        }
        val gateway = RecordingGateway()
        val executor = CastExecutor(
            store, gateway,
            nowEpochMillis = { 1_000L },
            operationId = { UUID.randomUUID() },
            sleeper = CastSleeper { },
        )
        val observations = ArrayDeque<ObservedState>()
        val reader = ObservedStateReader(
            RawShell(amStacksTwoOccupants, displays),
            ObservedStateParser {
                if (observations.isEmpty()) ObservationValue.Unknown("no scripted state")
                else ObservationValue.Known(observations.removeFirst())
            },
            nowEpochMillis = { 1_000L },
        )
        val coordinator = CastCoordinator(
            store, reader, executor, CastRecovery(store, executor),
            manualSleeper = CastSleeper { }, manualVerificationDelayMillis = 0L,
        )
        return Fixture(store, coordinator, gateway, observations)
    }

    private data class Fixture(
        val store: CastSessionStore,
        val coordinator: CastCoordinator,
        val gateway: RecordingGateway,
        val observations: ArrayDeque<ObservedState>,
    ) {
        fun envelope() = (store.locked { read() } as StoreRead.Loaded).envelope
    }

    /** Ghi lại NGUYÊN VĂN mọi request đi ra cổng — thứ tự chính là thứ được khoá. */
    private class RecordingGateway : CastMutationGateway {
        val requests = mutableListOf<CastMutationRequest>()
        var refuse: (CastMutationRequest) -> String? = { null }
        private val fence = AtomicLong(0)

        override fun execute(request: CastMutationRequest): MutationResult {
            requests += request
            return refuse(request)?.let { MutationResult.Rejected(it) } ?: MutationResult.Observed("ok")
        }

        override fun fenceInFlight() { fence.incrementAndGet() }
        override fun currentFenceToken(): Long = fence.get()

        fun kinds(): List<CommandKind> = requests.map { it.kind }
    }

    private class RawShell(private val amStacks: String, private val displays: String) : ShellGateway {
        override fun execute(request: ReadOnlyShellRequest): ShellResult {
            val output = when (request.kind) {
                CommandKind.AM_STACK_LIST -> amStacks
                CommandKind.WM_DISPLAYS -> "Display: mDisplayId=0"
                CommandKind.DISPLAY_STATE -> displays
                CommandKind.PROFILE_STATE -> "10"
                CommandKind.ANIMATION_STATE -> "1.0\n0.5\n1.0"
                CommandKind.APP_OPS_STATE -> "No app ops"
                else -> error("Unexpected read ${request.kind}")
            }
            return ShellResult.Success(output, "", 1L)
        }

        override fun close() {}
    }

    private class MemoryAtomicBytes : AtomicBytes {
        private var bytes: ByteArray? = null
        override fun exists() = bytes != null
        override fun read(): ByteArray = bytes?.copyOf() ?: throw IOException("missing")
        override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
    }

    private companion object {
        /** Dumpsys thật của một xe chỉ còn màn giữa: không có display cụm nào để nhận ra. */
        val MAIN_ONLY_DUMP = """
            Display 0:
              mDisplayId=0
              mBaseDisplayInfo=DisplayInfo{"Built-in Screen, displayId 0", app 1920 x 1080, density 240}
        """.trimIndent()
    }
}

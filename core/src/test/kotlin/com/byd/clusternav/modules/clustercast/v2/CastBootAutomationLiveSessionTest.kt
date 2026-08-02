package com.byd.clusternav.modules.clustercast.v2

import java.io.IOException
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Khoá lỗi tìm được ở review vòng 3 (2026-07-30, docs/specs/cast-simplified-active-app-toggle.html):
 * **tự-chiếu-lúc-khởi-động có thể cướp cụm khỏi app mà người lái vừa tự đặt lên đó.**
 *
 * Trước bản vá, mọi cửa của đường BOOT_AUTO đều so với *cấu hình bền* (`claimAllowed` so
 * revision/consent/defaultPackage) — mà một cú chiếu tay KHÔNG làm đổi thứ nào trong ba thứ đó. Nên khi
 * `CastAutomationService.deferForPriorJournal` hẹn lại 45 giây (`REVALIDATION_DELAY_MS`) vì lúc đánh giá
 * đầu tiên còn một transaction dở, đủ thời gian để người lái chạm nút nổi chiếu app A; 45 giây sau yêu
 * cầu tự-chiếu tỉnh dậy, claim được, và `CastManualIntentRunner.executeOrdinary` thấy
 * `stable.activeTarget != null` nên lập kế hoạch `SWITCH` — cụm đồng hồ đổi từ A sang app mặc định B
 * trong lúc xe đang chạy, không ai bấm gì.
 *
 * R6 đã ghi đúng luật này cho tự-chiếu-khi-mở-app ("không cướp phiên", `autoStartTarget` từ chối khi
 * `activeTarget != null`). Test này khoá luật đó cho R7, tại tầng THI HÀNH (CLAUDE.md §5), không phải
 * chỉ ở host Android gọi nó.
 */
class CastBootAutomationLiveSessionTest {

    private val target = "com.example.maps"
    private val driverChose = "com.example.other"

    @Test
    fun `boot automation is refused while a session the driver placed is live on the cluster`() {
        val fixture = fixture(activeTarget = CastTarget(driverChose, 7, 2))

        val result = fixture.coordinator.runManualIntent(
            target, FACTS, fixture.targets,
            origin = CastIntentOrigin.BOOT_AUTO,
            automationRequestId = fixture.requestId,
        )

        assertTrue(result is CastManualIntentResult.Blocked, "expected a refusal, got $result")
        assertEquals(
            "cluster already holds an active target; automation never supersedes a live session",
            (result as CastManualIntentResult.Blocked).reason,
        )
        // Cửa phải chặn TRƯỚC khi có bất kỳ lệnh nào ra xe, và không được đụng vào phiên đang sống.
        assertTrue(fixture.mutations.isEmpty(), "no command may be dispatched: ${fixture.mutations}")
        val envelope = fixture.envelope()
        assertEquals(driverChose, envelope.stableSession?.activeTarget?.packageName)
        assertEquals(null, envelope.transaction, "a refused claim must not open a journal")
    }

    /**
     * Cửa phải HẸP: nó chỉ được từ chối vì "cụm đang có app", không được biến thành một cách khác để
     * chặn đường tự-chiếu bình thường. Cụm rảnh ⇒ yêu cầu đi tiếp qua cửa này (kết cục sau đó do
     * planner/executor quyết, không phải việc của test này).
     */
    @Test
    fun `an idle cluster still passes the guard`() {
        val fixture = fixture(activeTarget = null)

        val result = fixture.coordinator.runManualIntent(
            target, FACTS, fixture.targets,
            origin = CastIntentOrigin.BOOT_AUTO,
            automationRequestId = fixture.requestId,
        )

        val refusedByGuard = result is CastManualIntentResult.Blocked &&
            result.reason.contains("already holds an active target")
        assertFalse(refusedByGuard, "an idle cluster must never be refused by the live-session guard")
    }

    /**
     * Luật thứ hai của cùng bản vá: tự-chiếu-lúc-khởi-động KHÔNG được xếp hàng.
     *
     * `queueLatestTarget` ghi `pendingIntent` gốc **USER** (mặc định của `CastModels.withPendingPackage`),
     * mà `CastAutomationSettings.terminalize` chỉ dọn hàng đợi khớp `requestId` của chính nó và
     * `CastSessionStore.initializeForBoot` giữ hàng đợi gốc USER qua mọi lần khởi động. Nên một lượt tự
     * động hoá bị xếp hàng sẽ HẠ CẤP chính chỗ đặt của nó thành USER và tự tay tắt tự-chiếu cho mọi lần
     * nổ máy sau — `CastAutomationService.evaluate` thấy pendingIntent gốc USER là terminalize
     * `USER_SUPERSEDED` ngay.
     */
    @Test
    fun `boot automation refuses to queue instead of downgrading its own placement to USER origin`() {
        val fixture = fixture(activeTarget = null, withOpenJournal = true)

        val result = fixture.coordinator.runManualIntent(
            target, FACTS, fixture.targets,
            origin = CastIntentOrigin.BOOT_AUTO,
            automationRequestId = fixture.requestId,
        )

        assertTrue(result is CastManualIntentResult.Blocked, "expected a refusal, got $result")
        assertEquals(
            "Operation already active; boot automation never queues a durable placement",
            (result as CastManualIntentResult.Blocked).reason,
        )
        val pending = fixture.envelope().pendingIntent
        assertEquals(
            CastIntentOrigin.BOOT_AUTO, pending?.origin,
            "automation must never leave a USER-origin placement behind: it fences every later boot",
        )
        assertEquals(fixture.requestId, pending?.automationRequestId)
    }

    private fun fixture(activeTarget: CastTarget?, withOpenJournal: Boolean = false): Fixture {
        val store = CastSessionStore(MemoryAtomicBytes())
        store.locked { initialize("boot") }
        val config = AutomationConfig().withDefault(target).accepting()
        val request = BootAutomationRequest.pending(UUID(0L, 42L), "boot", config, 1_000L).claimed(1_000L)
        val stable = StableCastSession(
            if (activeTarget == null) StableState.IDLE_VERIFIED else StableState.ACTIVE_VERIFIED,
            EngineVersion.V2, "test", null, "display-2",
            CastBaseline(geometry = AcceptedGeometry(CastRect(0, 0, 1920, 720), 180, "android-user-10")),
            activeTarget, null, null, 1L,
        )
        // Một transaction của bề mặt KHÁC (cú chạm nút nổi) đang chạy, và chỗ đặt của tự động hoá đã được
        // `bindAutoPending` ghi đúng gốc BOOT_AUTO trước đó.
        val foreignJournal = CastTransaction(
            UUID(0L, 77L), 0L, CastOperation.CAST, OperationPhase.VERIFYING,
            null, driverChose, TargetClass.NORMAL.name, "display-2", CastBaseline(),
            emptyList(), 0, 60_000L, null, "expected", false,
        )
        store.locked {
            update {
                it.copy(
                    effectiveUiVersion = EngineVersion.V2,
                    automationConfig = config,
                    bootAutomationRequest = request,
                    stableSession = stable,
                    transaction = if (withOpenJournal) foreignJournal else null,
                    pendingIntent = if (withOpenJournal) {
                        PendingCastIntent(target, CastIntentOrigin.BOOT_AUTO, request.requestId)
                    } else {
                        null
                    },
                )
            }
        }
        val mutations = mutableListOf<CommandKind>()
        val executor = CastExecutor(
            store,
            CastMutationGateway { mutations += it.kind; MutationResult.Observed("known") },
            nowEpochMillis = { 1_000L },
            operationId = { UUID(0L, 43L) },
            sleeper = CastSleeper { },
        )
        val reader = ObservedStateReader(
            RawShell(),
            ObservedStateParser { ObservationValue.Unknown("not scripted") },
            nowEpochMillis = { 1_000L },
        )
        val coordinator = CastCoordinator(
            store, reader, executor, CastRecovery(store, executor),
            manualSleeper = CastSleeper { }, manualVerificationDelayMillis = 0L,
        )
        return Fixture(store, coordinator, request.requestId, mutations)
    }

    private class Fixture(
        val store: CastSessionStore,
        val coordinator: CastCoordinator,
        val requestId: UUID,
        val mutations: List<CommandKind>,
    ) {
        val targets = CastManualTargetReader {
            CastManualTargetSnapshot(
                TargetEvidence(projectionComponent = false, connectedPhoneSession = false, userProtected = false),
                installed = true,
                hasLauncher = true,
            )
        }

        fun envelope() = (store.locked { read() } as StoreRead.Loaded).envelope
    }

    private class RawShell : ShellGateway {
        override fun execute(request: ReadOnlyShellRequest): ShellResult =
            ShellResult.Failure(1, "not scripted", 1L)
        override fun close() {}
    }

    private class MemoryAtomicBytes : AtomicBytes {
        private var bytes: ByteArray? = null
        override fun exists() = bytes != null
        override fun read(): ByteArray = bytes?.copyOf() ?: throw IOException("missing")
        override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
    }

    private companion object {
        val FACTS = CastVehicleFacts(29, "BYD AUTO", "BYD-AUTO", "DiLink3.0", "DiLink3.0", "DiLink3.0")
    }
}

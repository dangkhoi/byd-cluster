package com.byd.clusternav.modules.clustercast.v2

import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastManualIntentTest {
    @Test
    fun `one cold normal intent journals target then reaches active verified`() {
        val fixture = fixture()
        val target = "com.example.maps"
        fixture.states += listOf(idle(), idle(), idle(), active(target), active(target))
        fixture.onMutation = { request ->
            val envelope = fixture.envelope()
            when (request.kind) {
                CommandKind.SEAL_DL3_BOOTSTRAP_30 -> {
                    assertEquals(target, envelope.pendingPackage)
                    assertEquals(CastOperation.BOOTSTRAP, envelope.transaction!!.operation)
                    assertEquals(target, envelope.transaction!!.targetPkg)
                    assertEquals(5, envelope.transaction!!.ledger.size)
                }
                CommandKind.SET_FORCE_RESIZABLE -> {
                    assertEquals(StableState.IDLE_VERIFIED, envelope.stableSession!!.state)
                    assertNull(envelope.pendingPackage)
                    assertEquals(CastOperation.CAST, envelope.transaction!!.operation)
                }
                else -> Unit
            }
            MutationResult.Observed("known")
        }
        var targetReads = 0
        val result = fixture.coordinator.runManualIntent(target, facts(), CastManualTargetReader {
            targetReads++
            normalTarget()
        })

        assertTrue(result is CastManualIntentResult.Succeeded)
        assertEquals(
            SealDl3BootstrapProfile.forwardKinds + ExpectedLadder.normal,
            fixture.commands,
        )
        assertEquals(2, targetReads)
        assertEquals(2L, fixture.envelope().durableEpoch)
        assertEquals(StableState.ACTIVE_VERIFIED, fixture.envelope().stableSession!!.state)
        assertEquals(target, fixture.envelope().stableSession!!.activeTarget!!.packageName)
    }

    @Test
    fun `one cold protected intent resumes without force stop and reaches active verified`() {
        val fixture = fixture()
        val target = "com.example.carplay"
        fixture.states += listOf(idle(), idle(), idle(), active(target), active(target))
        fixture.onMutation = { MutationResult.Observed("known") }

        val result = fixture.coordinator.runManualIntent(target, facts(), CastManualTargetReader {
            protectedTarget()
        })

        assertTrue(result is CastManualIntentResult.Succeeded)
        assertEquals(
            SealDl3BootstrapProfile.forwardKinds + ExpectedLadder.protectedTarget,
            fixture.commands,
        )
        assertFalse(CommandKind.FORCE_STOP_NORMAL in fixture.commands)
    }

    /**
     * The real on-vehicle CarPlay/Android Auto reading (2026-07-28 CastPolicy fix): `dumpsys` can
     * never report `connectedPhoneSession` for the host surface's own dump, so it is always `null`,
     * never `true`. Before the fix this classified `UNKNOWN_PROTECTED` and `eligibilityFor` blocked it
     * before `executeCast()` ever ran -- tapping "CHIẾU Android Auto LÊN CỤM" only showed a toast. This
     * drives the exact same harness as the `connectedPhoneSession = true` cold-protected test above, to
     * prove the full path -- not just `CastPolicy.classify` -- now reaches `ACTIVE_VERIFIED`.
     */
    @Test
    fun `cold Android Auto intent with unmeasurable phone session reaches active verified without force stop`() {
        val fixture = fixture()
        val target = "com.example.androidauto"
        fixture.states += listOf(idle(), idle(), idle(), active(target), active(target))
        fixture.onMutation = { MutationResult.Observed("known") }

        val result = fixture.coordinator.runManualIntent(target, facts(), CastManualTargetReader {
            unmeasurableProjectionTarget()
        })

        assertTrue(result is CastManualIntentResult.Succeeded)
        assertEquals(
            SealDl3BootstrapProfile.forwardKinds + ExpectedLadder.protectedTarget,
            fixture.commands,
        )
        assertFalse(CommandKind.FORCE_STOP_NORMAL in fixture.commands)
        assertEquals(StableState.ACTIVE_VERIFIED, fixture.envelope().stableSession!!.state)
        assertEquals(target, fixture.envelope().stableSession!!.activeTarget!!.packageName)
    }

    /**
     * "For any prior occupant" -- `CastPlanner.plan`'s outgoing-return step is unconditional on a
     * SWITCH (`outgoing != null && outgoing != intent.targetPackage`), regardless of the outgoing
     * target's own class. Proves the prior normal occupant is returned via `RETURN_PROTECTED_GENTLY`,
     * never `FORCE_STOP_NORMAL`, when the *incoming* target is Android Auto classified from the
     * unmeasurable (`null`) session reading -- the exact shape that was fully blocked before the fix.
     */
    @Test
    fun `switch to Android Auto with unmeasurable phone session resumes it and returns the prior occupant gently`() {
        val old = "com.example.old"
        val target = "com.example.androidauto"
        val fixture = fixture(stable = activeSession(old), epoch = 4L)
        fixture.states += listOf(active(old), active(target), active(target))
        fixture.onMutation = { MutationResult.Observed("known") }

        val result = fixture.coordinator.runManualIntent(target, facts(), CastManualTargetReader {
            unmeasurableProjectionTarget()
        })

        assertTrue(result is CastManualIntentResult.Succeeded)
        assertEquals(
            ExpectedLadder.protectedTarget + CommandKind.RETURN_PROTECTED_GENTLY,
            fixture.commands,
        )
        assertFalse(CommandKind.FORCE_STOP_NORMAL in fixture.commands)
        assertEquals(target, fixture.envelope().stableSession!!.activeTarget!!.packageName)
    }

    /**
     * The other direction, confirmed through the runner rather than `CastPolicy.classify` alone: a
     * *positively confirmed* absent session (`connectedPhoneSession = false`) is a stronger, different
     * signal than "unmeasurable" and must keep failing closed as `UNKNOWN_PROTECTED` -- see
     * `CastAndroidAutoSliceTest.projection evidence without connected truth fails closed` and
     * `CastPolicyTest`. Here the same evidence is driven through `runManualIntent` end to end: zero
     * gateway mutation, zero durable-state change, from a cold (pristine) store.
     */
    @Test
    fun `manual intent for Android Auto with a confirmed absent session still fails closed at the runner`() {
        val fixture = fixture()
        val target = "com.example.androidauto"
        fixture.onMutation = { MutationResult.Observed("unexpected") }

        val result = fixture.coordinator.runManualIntent(target, facts(), CastManualTargetReader {
            confirmedAbsentProjectionTarget()
        })

        assertTrue(result is CastManualIntentResult.Blocked)
        assertEquals("Target policy is unknown", (result as CastManualIntentResult.Blocked).reason)
        assertTrue(fixture.commands.isEmpty())
        assertEquals(0L, fixture.envelope().durableEpoch)
        assertNull(fixture.envelope().pendingPackage)
        assertNull(fixture.envelope().transaction)
        assertNull(fixture.envelope().stableSession)
    }

    @Test
    fun `existing executor lease spans bootstrap stable boundary and fresh target read`() {
        val fixture = fixture()
        val target = "com.example.maps"
        fixture.states += listOf(idle(), idle(), idle(), active(target), active(target))
        fixture.onMutation = { MutationResult.Observed("known") }
        val atStableBoundary = CountDownLatch(1)
        val releaseBoundary = CountDownLatch(1)
        val firstResult = AtomicReference<CastManualIntentResult>()
        var reads = 0
        val first = thread(name = "manual-first") {
            firstResult.set(fixture.coordinator.runManualIntent(target, facts(), CastManualTargetReader {
                reads++
                if (reads == 2) {
                    atStableBoundary.countDown()
                    assertTrue(releaseBoundary.await(2, TimeUnit.SECONDS))
                }
                normalTarget()
            }))
        }
        assertTrue(atStableBoundary.await(2, TimeUnit.SECONDS))

        val secondDone = CountDownLatch(1)
        val secondResult = AtomicReference<CastManualIntentResult>()
        val second = thread(name = "manual-second") {
            secondResult.set(fixture.coordinator.runManualIntent(
                "com.example.other",
                facts(),
                CastManualTargetReader { normalTarget().copy(installed = false) },
            ))
            secondDone.countDown()
        }
        assertFalse(secondDone.await(50, TimeUnit.MILLISECONDS))
        releaseBoundary.countDown()
        first.join(2_000)
        second.join(2_000)

        assertTrue(firstResult.get() is CastManualIntentResult.Succeeded)
        assertTrue(secondResult.get() is CastManualIntentResult.Blocked)
        assertEquals(
            SealDl3BootstrapProfile.forwardKinds + ExpectedLadder.normal,
            fixture.commands,
        )
    }

    @Test
    fun `target invalid before bootstrap produces zero durable or gateway mutation`() {
        val fixture = fixture()
        fixture.onMutation = { MutationResult.Observed("unexpected") }

        val result = fixture.coordinator.runManualIntent(
            "com.example.missing",
            facts(),
            CastManualTargetReader { normalTarget().copy(installed = false) },
        )

        assertTrue(result is CastManualIntentResult.Blocked)
        assertTrue(fixture.commands.isEmpty())
        assertEquals(0L, fixture.envelope().durableEpoch)
        assertNull(fixture.envelope().pendingPackage)
        assertNull(fixture.envelope().transaction)
        assertNull(fixture.envelope().stableSession)
    }

    @Test
    fun `target removed after bootstrap remains verified idle and is consumed without placement`() {
        val fixture = fixture()
        fixture.states += listOf(idle(), idle())
        fixture.onMutation = { MutationResult.Observed("known") }
        var reads = 0

        val result = fixture.coordinator.runManualIntent(
            "com.example.maps",
            facts(),
            CastManualTargetReader {
                reads++
                normalTarget().copy(installed = reads == 1)
            },
        )

        assertTrue(result is CastManualIntentResult.Blocked)
        assertEquals(SealDl3BootstrapProfile.forwardKinds, fixture.commands)
        assertEquals(StableState.IDLE_VERIFIED, fixture.envelope().stableSession!!.state)
        assertNull(fixture.envelope().pendingPackage)
        assertNull(fixture.envelope().transaction)
    }

    @Test
    fun `selection during bootstrap replaces one durable slot and only latest target is placed`() {
        val fixture = fixture()
        val first = "com.example.first"
        val latest = "com.example.latest"
        fixture.states += listOf(idle(), idle(), idle(), active(latest), active(latest))
        fixture.onMutation = { request ->
            if (request.kind == CommandKind.SEAL_DL3_BOOTSTRAP_30) {
                assertEquals(first, fixture.envelope().pendingPackage)
                assertTrue(fixture.coordinator.queueLatestTarget(latest))
                assertEquals(latest, fixture.envelope().pendingPackage)
            }
            MutationResult.Observed("known")
        }
        val requestedPackages = mutableListOf<String>()

        val result = fixture.coordinator.runManualIntent(first, facts(), CastManualTargetReader { packageName ->
            requestedPackages += packageName
            normalTarget()
        })

        assertTrue(result is CastManualIntentResult.Succeeded)
        assertEquals(listOf(first, latest), requestedPackages)
        assertEquals(latest, fixture.envelope().stableSession!!.activeTarget!!.packageName)
        assertNull(fixture.envelope().pendingPackage)
    }

    @Test
    fun `Stop at bootstrap stable boundary prevents CAST transaction creation`() {
        val fixture = fixture()
        fixture.states += listOf(idle(), idle())
        fixture.onMutation = { MutationResult.Observed("known") }
        var reads = 0

        val result = fixture.coordinator.runManualIntent(
            "com.example.maps",
            facts(),
            CastManualTargetReader {
                reads++
                if (reads == 2) fixture.coordinator.requestStop()
                normalTarget()
            },
        )

        assertTrue(result is CastManualIntentResult.Blocked)
        assertEquals(SealDl3BootstrapProfile.forwardKinds, fixture.commands)
        assertEquals(StableState.IDLE_VERIFIED, fixture.envelope().stableSession!!.state)
        assertTrue(fixture.envelope().stopRequested)
        assertNull(fixture.envelope().pendingPackage)
        assertNull(fixture.envelope().transaction)
    }

    @Test
    fun `Stop after first bootstrap opcode clears target and suppresses every later opcode`() {
        val fixture = fixture()
        fixture.onMutation = { request ->
            if (request.kind == CommandKind.SEAL_DL3_BOOTSTRAP_30) {
                assertTrue(fixture.coordinator.requestStop()!!.stopRequested)
            }
            MutationResult.Observed("known")
        }

        val result = fixture.coordinator.runManualIntent(
            "com.example.maps",
            facts(),
            CastManualTargetReader { normalTarget() },
        )

        assertTrue(result is CastManualIntentResult.RecoveryRequired)
        assertEquals(listOf(CommandKind.SEAL_DL3_BOOTSTRAP_30), fixture.commands)
        assertTrue(fixture.envelope().stopRequested)
        assertNull(fixture.envelope().pendingPackage)
        assertEquals(OperationPhase.RECOVERING, fixture.envelope().transaction!!.phase)
    }

    @Test
    fun `Stop during ordinary cast suppresses every later placement opcode`() {
        val fixture = fixture(stable = idleSession(), epoch = 4L)
        fixture.states += idle()
        fixture.onMutation = { request ->
            if (request.kind == CommandKind.SET_FORCE_RESIZABLE) fixture.coordinator.requestStop()
            MutationResult.Observed("known")
        }

        val result = fixture.coordinator.runManualIntent(
            "com.example.maps",
            facts(),
            CastManualTargetReader { normalTarget() },
        )

        assertTrue(result is CastManualIntentResult.RecoveryRequired)
        assertEquals(listOf(
                // Stop lands after the cheap path has run; what it must suppress is every
                // escalation rung that follows. Order per ExpectedLadder (2026-07-26 on-car).
                CommandKind.PRE_OPEN_ON_MAIN,
                CommandKind.PLACE_KEEP_SESSION,
                CommandKind.FIT_CLUSTER_COMPOSITE,
                CommandKind.SET_FORCE_RESIZABLE,
            ), fixture.commands)
        assertTrue(fixture.envelope().stopRequested)
        assertEquals(OperationPhase.RECOVERING, fixture.envelope().transaction!!.phase)
    }

    @Test
    fun `warm verified cast bypasses all bootstrap commands`() {
        val fixture = fixture(stable = idleSession(), epoch = 4L)
        val target = "com.example.maps"
        fixture.states += listOf(idle(), active(target), active(target))
        fixture.onMutation = { MutationResult.Observed("known") }

        val result = fixture.coordinator.runManualIntent(target, facts(), CastManualTargetReader { normalTarget() })

        assertTrue(result is CastManualIntentResult.Succeeded)
        assertEquals(ExpectedLadder.normal, fixture.commands)
        assertTrue(fixture.commands.none { it in SealDl3BootstrapProfile.forwardKinds })
    }

    /**
     * KHOÁ đúng lỗi đo được trên DiLink3 đêm 31/7–1/8 (docs/specs/cast-one-mode-and-three-zone-bubble.html
     * §Context, dựng từ session.env THẬT đọc bằng `run-as … cat`):
     *
     *   stable=IDLE_VERIFIED|V2|runtime-migration|~|display-1|…
     *
     * `CastLifecycleMigration.migratePristine` (app/…/CastAndroidLifecycle.kt:55-74) thấy cụm rảnh là ghi
     * luôn tuyên bố IDLE_VERIFIED mà không phát một opcode AutoContainer nào; từ đó mọi lượt cast đi
     * `executeOrdinary` (chỉ vì `stableSession != null`) nên thang bootstrap thật không bao giờ chạy. Trên
     * xe: `am stack list` xác nhận app nằm đúng display cụm, `screencap -d 1` chụp được app đang vẽ, mà
     * cụm vật lý vẫn là đồng hồ gốc — phần cứng chưa hề được bảo chuyển sang Android.
     *
     * Test khoá hai điều: opcode 30/16/35 PHẢI được phát, và phải phát TRƯỚC mọi bước đặt app.
     *
     * Giới hạn trung thực của test này (CLAUDE.md §2 — không được nâng "nhiều khả năng" lên "đã chứng
     * minh"): fixture `RawShell` trả `dumpsys display` KHÔNG có display cụm mang tên fission/xdja, nên
     * `CastColdBootstrap.run` đi nhánh `adopt = false` (`val adopt = discoverClusterDisplayId(raw.displays)
     * != null` rồi `if (!adopt) resolvedProfile.forwardKindsFor(style)…` — trích theo mã, không theo số
     * dòng, vì file đó đang được sửa song song) và thực sự phát opcode. Trên xe thật, một phiên di trú chỉ
     * tồn tại được khi display cụm ĐÃ có (parser chỉ trả `Known` khi tìm thấy display đó — nhánh
     * `?: return ObservationValue.Unknown(MISSING_NAMED_CLUSTER_DISPLAY_REASON)` trong
     * `CastDeviceParsers.parse`), và dump thật
     * docs/diagnostics/carlog-2026-07-21/05-display.txt:120 cho thấy nó nằm ngay trong `dumpsys display`
     * ⇒ nhiều khả năng `adopt = true` và nhánh đó bỏ qua TOÀN BỘ opcode. Cổng ở đây là điều kiện CẦN, chưa
     * đủ; phần `adopt` nằm ở CastColdBootstrap và phải được sửa riêng.
     */
    @Test
    fun `migration claim without a real bootstrap runs the bootstrap ladder before any placement command`() {
        val fixture = fixture(stable = migrationClaim(), epoch = 3L)
        val target = "com.example.maps"
        fixture.states += listOf(idle(), idle(), idle(), active(target), active(target))
        fixture.onMutation = { MutationResult.Observed("known") }

        val result = fixture.coordinator.runManualIntent(target, facts(), CastManualTargetReader { normalTarget() })

        assertTrue(result is CastManualIntentResult.Succeeded, "expected Succeeded, got $result")
        assertEquals(SealDl3BootstrapProfile.forwardKinds + ExpectedLadder.normal, fixture.commands)
        // Nói tường minh cái ràng buộc quan trọng nhất, không để nó chỉ ngầm định trong thứ tự danh sách
        // ở trên: mở cụm xong rồi mới được đặt app.
        assertTrue(
            fixture.commands.indexOfLast { it in SealDl3BootstrapProfile.forwardKinds } <
                fixture.commands.indexOfFirst { it in ExpectedLadder.normal },
            "bootstrap opcodes must all precede the placement ladder, got ${fixture.commands}",
        )
        assertEquals(StableState.ACTIVE_VERIFIED, fixture.envelope().stableSession!!.state)
        assertEquals(target, fixture.envelope().stableSession!!.activeTarget!!.packageName)
    }

    /**
     * KHOÁ lỗi mà review 2026-08-01 (Pass 1) tìm ra và sửa: cổng "một chế độ duy nhất" phải bắt luôn phiên
     * do một lần DỪNG ghi ra, không chỉ phiên di trú.
     *
     * Thang STOP kết thúc bằng `SEAL_DL3_COMPENSATE_18` rồi `_0` (CastPlanner.kt:119-128) — hai opcode
     * ĐÓNG đường chiếu OEM; đo thật trên xe đêm 31/7: gửi 18 rồi 0 là cụm vật lý trở lại đồng hồ native.
     * Kết cục STOP ghi `IDLE_VERIFIED | runtime | profileExport=null | activeTarget=null`
     * (CastCoordinator.kt:774-777). Bản đầu của cổng còn đòi thêm `createdByBuild.startsWith
     * ("runtime-migration")` nên chuỗi `"runtime"` KHÔNG khớp ⇒ lượt chiếu ngay sau một lần Dừng bỏ qua
     * thang mở cụm ⇒ app lên đúng display ảo, cụm vật lý ở nguyên đồng hồ. Tức đúng con bug của cả đêm
     * 31/7, chỉ dời từ lượt chiếu thứ NHẤT sang lượt thứ HAI — và chính là tiêu chí nghiệm thu T5
     * ("10 lượt chiếu/trả liên tiếp, 0 lần can thiệp").
     *
     * Không có test nào cũ bắt được: mọi fixture idle đều mang sẵn chuỗi seal (`idleSession()`), tức mô tả
     * một hình dạng mà runtime chỉ tạo ra ĐÚNG MỘT LẦN, ngay sau bootstrap.
     */
    @Test
    fun `a session written by a Stop must reopen the projection before the next placement`() {
        val fixture = fixture(stable = stoppedSession(), epoch = 9L)
        val target = "com.example.maps"
        fixture.states += listOf(idle(), idle(), idle(), active(target), active(target))
        fixture.onMutation = { MutationResult.Observed("known") }

        val result = fixture.coordinator.runManualIntent(target, facts(), CastManualTargetReader { normalTarget() })

        assertTrue(result is CastManualIntentResult.Succeeded, "expected Succeeded, got $result")
        assertEquals(SealDl3BootstrapProfile.forwardKinds + ExpectedLadder.normal, fixture.commands)
        assertTrue(
            fixture.commands.indexOfLast { it in SealDl3BootstrapProfile.forwardKinds } <
                fixture.commands.indexOfFirst { it in ExpectedLadder.normal },
            "the projection must be reopened before any placement command, got ${fixture.commands}",
        )
    }

    /**
     * ★ Cổng "yêu cầu ô" thu hẹp lại đúng ca cơ học của nó (2026-08-01).
     *
     * Bản trước từ chối MỌI yêu cầu ô khi `stableSession?.activeTarget == null`, với hai lý lẽ. Lý lẽ thứ
     * hai ("ô cắt từ khung task thật nên cụm phải có sẵn app") chết cùng ngày mà `ClusterSplit.slotBand`
     * mở khoá lượt đặt app ĐẦU TIÊN — nhưng điều kiện cũ thì loại thẳng chính ca đó: cụm rỗng + phiên
     * IDLE_VERIFIED = không có `activeTarget`. Tức nó đã thành cửa duy nhất còn chặn tính năng, mà chặn vì
     * một lý do không còn đúng.
     *
     * Ở đây phiên bền là `idleSession()` — IDLE_VERIFIED, CÓ dấu seal, không `activeTarget`: đúng hình
     * dạng ngay sau một lần bootstrap thật, và đúng chỗ người lái đứng khi chạm nửa trái lần đầu.
     */
    @Test
    fun `a slot placement runs from an empty cluster once the projection is already open`() {
        val fixture = fixture(stable = idleSession(), epoch = 4L)
        val target = "com.example.maps"
        // Ô TRÁI cắt từ khung display (cụm rỗng ⇒ không có dải vẽ nào đo được): 50% của 1920 là 960.
        val landed = active(target).copy(taskBounds = mapOf(7 to CastRect(0, 0, 960, 720)))
        fixture.states += listOf(idle(), landed, landed)
        fixture.onMutation = { MutationResult.Observed("known") }

        val result = fixture.coordinator.runManualIntent(
            target, facts(), CastManualTargetReader { normalTarget() },
            slotLayout = ClusterSlotLayout(
                mapOf(target to ClusterSlot(ClusterSlotSide.LEFT, ClusterSplitRatio.EVEN)),
            ),
        )

        assertTrue(result is CastManualIntentResult.Succeeded, "expected Succeeded, got $result")
        // Thang đã kiểm thực địa, không thêm rung nào, không opcode bootstrap nào (cụm đã mở sẵn).
        assertEquals(ExpectedLadder.normal, fixture.commands)
        assertEquals(StableState.ACTIVE_VERIFIED, fixture.envelope().stableSession!!.state)
    }

    /**
     * Mặt còn lại của cùng cổng, và là lý do DUY NHẤT nó còn tồn tại: nhánh cold-bootstrap tái lập kế
     * hoạch từ `pendingIntent`, thứ chỉ mang được TÊN GÓI qua tầng bền. Một yêu cầu ô đi qua đó sẽ lặng lẽ
     * thành lượt chiếu TOÀN CỤM — tức nuốt app đang ở nửa kia, đúng ca đã đo là app bị đè biến mất hẳn.
     *
     * Khoá thêm một điều mà bản vá này dễ làm hỏng nhất: cửa phải từ chối bằng một vị từ THUẦN. Gọi
     * `retireUnprovenClusterClaim()` để "thử xem có bootstrap không" sẽ XOÁ `stableSession` như tác dụng
     * phụ — tức phá bản ghi bền của một cú chạm rồi mới từ chối cú chạm ấy. Vế cuối của test đo đúng điều
     * đó: sau lời từ chối, hồ sơ bền còn nguyên vẹn.
     */
    @Test
    fun `a slot placement is refused when this tap would run the cold bootstrap, and changes nothing`() {
        val target = "com.example.maps"
        val layout = ClusterSlotLayout(
            mapOf(target to ClusterSlot(ClusterSlotSide.LEFT, ClusterSplitRatio.EVEN)),
        )
        // Phiên do một lần DỪNG ghi ra: 18/0 vừa ĐÓNG đường chiếu, nên lượt kế tiếp phải chạy lại thang mở
        // cụm — và thang đó không mang nổi một yêu cầu ô.
        listOf(stoppedSession(), null).forEach { stable ->
            val fixture = fixture(stable = stable, epoch = 9L)
            fixture.onMutation = { MutationResult.Observed("unexpected") }

            val result = fixture.coordinator.runManualIntent(
                target, facts(), CastManualTargetReader { normalTarget() }, slotLayout = layout,
            )

            assertTrue(result is CastManualIntentResult.Blocked, "expected Blocked, got $result")
            assertEquals(
                CastManualIntentRunner.SLOT_NEVER_SURVIVES_BOOTSTRAP,
                (result as CastManualIntentResult.Blocked).reason,
            )
            assertTrue(fixture.commands.isEmpty(), "no opcode may be dispatched, got ${fixture.commands}")
            assertEquals(stable, fixture.envelope().stableSession, "lời từ chối không được đụng bản ghi bền")
            assertEquals(9L, fixture.envelope().durableEpoch, "cũng không được bump epoch")
        }
    }

    /**
     * Mặt trái của cùng một cổng: một phiên ẤM (vừa cast xong, đang có app trên cụm) KHÔNG được rơi vào
     * bootstrap, dù nó cũng không mang dấu seal.
     *
     * `CastCoordinator.completeVerificationLocked` ghi `profileExport = null` cho MỌI kết cục CAST/SWITCH
     * (CastCoordinator.kt:786-790) và STOP/RECOVER (:774-777) — chỉ BOOTSTRAP (:768-773) mới ghi chuỗi
     * seal. Nên ngay sau lượt cast ĐẦU TIÊN của một đời máy đã bootstrap thật, phiên bền có đúng hình dạng
     * dựng ở đây: `runtime` + `profileExport = null`. Thứ giữ nó ngoài bootstrap KHÔNG phải là chuỗi
     * `createdByBuild` mà là ba guard trạng thái của `retireUnprovenClusterClaim` (ACTIVE_VERIFIED, còn
     * `activeTarget`) — đúng chỗ mà `CastColdBootstrapPreflight` cũng sẽ từ chối vì envelope không pristine
     * (CastColdBootstrap.kt:91-94), nhưng ta chặn TRƯỚC khi vứt mất bản ghi bền.
     *
     * Không fixture nào cũ bắt được điều này: `idleSession()`/`activeSession()` đều mang sẵn chuỗi seal,
     * tức mô tả một hình dạng mà runtime KHÔNG bao giờ tạo ra sau một lượt cast.
     */
    @Test
    fun `warm session written by a real cast still switches without re-running bootstrap`() {
        val old = "com.example.old"
        val target = "com.example.new"
        val fixture = fixture(stable = runtimeCastSession(old), epoch = 6L)
        fixture.states += listOf(active(old), active(target), active(target))
        fixture.onMutation = { MutationResult.Observed("known") }

        val result = fixture.coordinator.runManualIntent(target, facts(), CastManualTargetReader { normalTarget() })

        assertTrue(result is CastManualIntentResult.Succeeded, "expected Succeeded, got $result")
        assertEquals(ExpectedLadder.normal + CommandKind.RETURN_PROTECTED_GENTLY, fixture.commands)
        assertTrue(fixture.commands.none { it in SealDl3BootstrapProfile.forwardKinds })
        assertEquals(target, fixture.envelope().stableSession!!.activeTarget!!.packageName)
    }

    /**
     * Biến thể "unowned" của cùng đường di trú (`runtime-migration-unowned`, RECOVERY_PENDING —
     * CastAndroidLifecycle.kt:36-53) cũng có `profileExport = null`, nhưng nó GHI NHỚ app đang chiếm cụm:
     * đó là hồ sơ duy nhất để Stop biết phải trả cái gì về đâu (CLAUDE.md §5 — mọi thứ đổi ra ngoài phải có
     * đường trả lại). Cổng mới tuyệt đối không được xoá hồ sơ đó để lấy chỗ bootstrap: ca này đi tiếp
     * `executeOrdinary` và bị planner chặn tường minh, KHÔNG phát opcode nào.
     */
    @Test
    fun `unowned migration claim keeps its occupant record and never enters bootstrap`() {
        val occupant = "com.example.old"
        val fixture = fixture(stable = unownedMigrationClaim(occupant), epoch = 3L)
        fixture.states += active(occupant)
        fixture.onMutation = { MutationResult.Observed("unexpected") }

        val result = fixture.coordinator.runManualIntent(
            "com.example.maps", facts(), CastManualTargetReader { normalTarget() },
        )

        assertTrue(result is CastManualIntentResult.Blocked, "expected Blocked, got $result")
        assertTrue(fixture.commands.isEmpty(), "no opcode may be dispatched, got ${fixture.commands}")
        val stable = fixture.envelope().stableSession
        assertTrue(
            stable != null && stable.activeTarget?.packageName == occupant &&
                stable.createdByBuild == "runtime-migration-unowned",
            "the occupant record must survive untouched, got $stable",
        )
    }

    /**
     * Điều kiện thứ hai của cổng: chỉ gỡ tuyên bố di trú khi việc gỡ TỰ NÓ đủ đưa envelope về pristine —
     * cùng kỷ luật đã rút ra ở CastCoordinator.kt:209-219. Ở đây `pendingIntent` còn sót lại (một mục tiêu
     * bền từ đời máy trước) nên preflight vẫn sẽ chặn (CastColdBootstrap.kt:91-94); nếu cổng cứ xoá thì kết
     * cục là mất hồ sơ mà vẫn Blocked — tệ hơn cả trước khi vá.
     *
     * Test này KHÔNG khẳng định "cast không bootstrap ở ca này là đúng đắn": đó là khe hở còn lại, đã báo
     * cáo kèm bản vá. Nó chỉ khoá đúng một lời hứa của cổng: không bao giờ vứt bản ghi bền đi để đổi lấy
     * một bootstrap chắc chắn bị từ chối.
     */
    @Test
    fun `migration claim that cannot reach pristine keeps its record instead of being thrown away`() {
        val target = "com.example.maps"
        val fixture = fixture(stable = migrationClaim(), pending = target, epoch = 3L)
        fixture.states += listOf(idle(), active(target), active(target))
        fixture.onMutation = { MutationResult.Observed("known") }

        fixture.coordinator.runManualIntent(target, facts(), CastManualTargetReader { normalTarget() })

        assertTrue(
            fixture.commands.none { it in SealDl3BootstrapProfile.forwardKinds },
            "a non-pristine envelope must never reach the bootstrap ladder, got ${fixture.commands}",
        )
        assertTrue(fixture.envelope().stableSession != null, "the durable claim must never be dropped here")
    }

    /**
     * Khoá lỗi đo trên DiLink3 2026-07-31: dải lệnh CAST bình thường có 9 bước shell, mỗi bước một
     * round-trip adb/dadb riêng — với 500ms cũ, mẫu xác minh ĐẦU TIÊN thường xuyên rơi vào lúc cụm CHƯA
     * kịp ổn định (thiếu geometry/coarseState còn transitional), nên `completeVerificationLocked` ghim
     * RECOVERING dù vài giây sau app đã lên đúng cụm thật (xác nhận bằng `am stack list` thật hai lần
     * độc lập, VietMap và GMaps). Ngân sách thật của cả transaction là 15s
     * (`CastExecutor.operationTimeoutMillis`); test này khoá đúng con số production KHÔNG override —
     * nếu ai lỡ hạ `manualVerificationDelayMillis` về gần 500ms nữa, test này báo ngay tại chỗ thay vì
     * để lộ lại trên xe thật.
     */
    @Test
    fun `production verification delay gives the mutation ladder real settle time, not the old 500ms`() {
        val store = CastSessionStore(MemoryAtomicBytes())
        store.locked {
            initialize("boot")
            update { it.copy(effectiveUiVersion = EngineVersion.V2, stableSession = idleSession(), durableEpoch = 4L) }
        }
        val target = "com.example.maps"
        val states = ArrayDeque(listOf(idle(), active(target), active(target)))
        val reader = ObservedStateReader(
            RawShell(),
            ObservedStateParser {
                if (states.isEmpty()) ObservationValue.Unknown("no scripted state") else ObservationValue.Known(states.removeFirst())
            },
            nowEpochMillis = { 1_000L },
        )
        val executor = CastExecutor(
            store, CastMutationGateway { MutationResult.Observed("known") },
            nowEpochMillis = { 1_000L }, operationId = idSequence(), bootstrapVerificationPollMillis = 0L,
            sleeper = CastSleeper { },
        )
        val requestedSleeps = mutableListOf<Long>()
        // Không dùng CastCoordinator's manualVerificationDelayMillis mặc định của TEST fixture() ở trên
        // (cố tình đặt 0L để test khác chạy nhanh) — cố tình để mặc định PRODUCTION ở đây.
        val coordinator = CastCoordinator(
            store, reader, executor, CastRecovery(store, executor),
            now = { Instant.ofEpochMilli(1_000L) },
            manualSleeper = CastSleeper { millis -> requestedSleeps += millis },
        )

        val result = coordinator.runManualIntent(target, facts(), CastManualTargetReader { normalTarget() })

        assertTrue(result is CastManualIntentResult.Succeeded, "expected Succeeded, got $result")
        assertTrue(
            requestedSleeps.all { it == 3_000L } && requestedSleeps.isNotEmpty(),
            "expected only the new 3000ms settle waits, got $requestedSleeps",
        )
        assertTrue(
            requestedSleeps.sum() < 15_000L,
            "settle waits must stay well inside the 15s operation deadline, got total ${requestedSleeps.sum()}ms",
        )
    }

    @Test
    fun `warm verified switch bypasses bootstrap and preserves display identity`() {
        val old = "com.example.old"
        val target = "com.example.new"
        val fixture = fixture(stable = activeSession(old), epoch = 4L)
        fixture.states += listOf(active(old), active(target), active(target))
        fixture.onMutation = { MutationResult.Observed("known") }

        val result = fixture.coordinator.runManualIntent(target, facts(), CastManualTargetReader { normalTarget() })

        assertTrue(result is CastManualIntentResult.Succeeded)
        assertEquals(
            ExpectedLadder.normal + CommandKind.RETURN_PROTECTED_GENTLY,
            fixture.commands,
        )
        assertTrue(fixture.commands.none { it in SealDl3BootstrapProfile.forwardKinds })
        assertEquals("display-2", fixture.envelope().stableSession!!.expectedDisplayIdentity)
        assertEquals(target, fixture.envelope().stableSession!!.activeTarget!!.packageName)
    }

    @Test
    fun `read only rehydration continues one pending placement without bootstrap replay`() {
        val target = "com.example.maps"
        val fixture = fixture(stable = idleSession(), pending = target, epoch = 1L)
        fixture.states += listOf(idle(), active(target), active(target))
        fixture.onMutation = { request ->
            if (request.kind == CommandKind.SET_FORCE_RESIZABLE) {
                assertNull(fixture.envelope().pendingPackage)
                assertEquals(CastOperation.CAST, fixture.envelope().transaction!!.operation)
            }
            MutationResult.Observed("known")
        }

        val result = fixture.coordinator.resumePendingIntent(CastManualTargetReader { normalTarget() })

        assertTrue(result is CastManualIntentResult.Succeeded)
        assertEquals(ExpectedLadder.normal, fixture.commands)
        assertNull(fixture.coordinator.resumePendingIntent(CastManualTargetReader { normalTarget() }))
    }

    private fun fixture(
        stable: StableCastSession? = null,
        pending: String? = null,
        epoch: Long = 0L,
    ): Fixture {
        val store = CastSessionStore(MemoryAtomicBytes())
        store.locked {
            initialize("boot")
            update {
                it.copy(
                    durableEpoch = epoch,
                    effectiveUiVersion = EngineVersion.V2,
                    stableSession = stable,
                    pendingIntent = pending?.let(::PendingCastIntent),
                )
            }
        }
        val states = ArrayDeque<ObservedState>()
        val commands = mutableListOf<CommandKind>()
        lateinit var fixture: Fixture
        val gateway = CastMutationGateway { request ->
            commands += request.kind
            fixture.onMutation(request)
        }
        val executor = CastExecutor(
            store,
            gateway,
            nowEpochMillis = { 1_000L },
            operationId = idSequence(),
            bootstrapVerificationPollMillis = 0L,
            sleeper = CastSleeper { },
        )
        val reader = ObservedStateReader(
            RawShell(),
            ObservedStateParser {
                if (states.isEmpty()) ObservationValue.Unknown("no scripted state")
                else ObservationValue.Known(states.removeFirst())
            },
            nowEpochMillis = { 1_000L },
        )
        val coordinator = CastCoordinator(
            store,
            reader,
            executor,
            CastRecovery(store, executor),
            now = { Instant.ofEpochMilli(1_000L) },
            manualSleeper = CastSleeper { },
            manualVerificationDelayMillis = 0L,
        )
        fixture = Fixture(store, coordinator, states, commands)
        return fixture
    }

    private fun normalTarget() = CastManualTargetSnapshot(
        TargetEvidence(projectionComponent = false, connectedPhoneSession = false, userProtected = false),
        installed = true,
        hasLauncher = true,
    )

    private fun protectedTarget() = CastManualTargetSnapshot(
        TargetEvidence(projectionComponent = true, connectedPhoneSession = true, userProtected = false),
        installed = true,
        hasLauncher = true,
    )

    /** The real on-vehicle CarPlay/Android Auto reading: dumpsys never reports the host's own session. */
    private fun unmeasurableProjectionTarget() = CastManualTargetSnapshot(
        TargetEvidence(projectionComponent = true, connectedPhoneSession = null, userProtected = false),
        installed = true,
        hasLauncher = true,
    )

    /** A positively confirmed absent session -- a different, stronger signal that keeps failing closed. */
    private fun confirmedAbsentProjectionTarget() = CastManualTargetSnapshot(
        TargetEvidence(projectionComponent = true, connectedPhoneSession = false, userProtected = false),
        installed = true,
        hasLauncher = true,
    )

    private fun idle() = ObservedState(
        ObservedCoarseState.IDLE_CLEAN,
        "display-2",
        null,
        emptySet(),
        null,
        geometry(),
        animations(),
        null,
        "android-user-10",
        "fission_bg_xdjaVirtualSurface",
    )

    private fun active(packageName: String) = idle().copy(
        coarseState = ObservedCoarseState.ACTIVE_SINGLE,
        target = CastTarget(packageName, 7, 2),
        occupants = setOf(packageName),
    )

    private fun idleSession() = StableCastSession(
        StableState.IDLE_VERIFIED,
        EngineVersion.V2,
        "test",
        "seal-dl3-cold-bootstrap-v1",
        "display-2",
        CastBaseline(geometry = geometry(), animationPerKey = animations(), profile = "android-user-10"),
        null,
        null,
        geometry(),
        1L,
    )

    private fun activeSession(packageName: String) = idleSession().copy(
        state = StableState.ACTIVE_VERIFIED,
        activeTarget = CastTarget(packageName, 7, 2),
    )

    /**
     * Hình dạng THẬT của đường tắt, chép từ session.env đọc trên xe: reason `runtime-migration`, không có
     * chuỗi seal (`CastAndroidLifecycle.kt:55-74` ghi đúng cặp này). Khác `idleSession()` đúng hai trường —
     * và đúng hai trường đó là toàn bộ khác biệt giữa "cụm đã được mở thật" và "chỉ đoán là đã mở".
     */
    private fun migrationClaim() = idleSession().copy(
        createdByBuild = "runtime-migration",
        profileExport = null,
    )

    /** Biến thể di trú khi cụm KHÔNG rảnh (CastAndroidLifecycle.kt:36-53): giữ hồ sơ app đang chiếm cụm. */
    private fun unownedMigrationClaim(packageName: String) = idleSession().copy(
        state = StableState.RECOVERY_PENDING,
        createdByBuild = "runtime-migration-unowned",
        profileExport = null,
        activeTarget = CastTarget(packageName, 7, 2),
    )

    /**
     * Hình dạng runtime ghi ra sau một lượt DỪNG thật (CastCoordinator.kt:774-777): idle, không seal,
     * không occupant — và đường chiếu OEM đã bị 18/0 đóng lại ngay trước đó.
     */
    private fun stoppedSession() = idleSession().copy(
        createdByBuild = "runtime",
        profileExport = null,
    )

    /** Hình dạng runtime ghi ra sau MỘT lượt cast thật (CastCoordinator.kt:786-790): seal đã biến mất. */
    private fun runtimeCastSession(packageName: String) = idleSession().copy(
        state = StableState.ACTIVE_VERIFIED,
        createdByBuild = "runtime",
        profileExport = null,
        activeTarget = CastTarget(packageName, 7, 2),
    )

    private fun geometry() = AcceptedGeometry(CastRect(0, 0, 1920, 720), 180, "android-user-10")
    private fun animations() = mapOf(
        "window_animation_scale" to "1.0",
        "transition_animation_scale" to "0.5",
        "animator_duration_scale" to "1.0",
    )
    private fun facts() = SealDl3BootstrapProfile.exactFacts

    private fun idSequence(): () -> UUID {
        var next = 1L
        return { UUID(0L, next++) }
    }

    private data class Fixture(
        val store: CastSessionStore,
        val coordinator: CastCoordinator,
        val states: ArrayDeque<ObservedState>,
        val commands: MutableList<CommandKind>,
        var onMutation: (CastMutationRequest) -> MutationResult = { MutationResult.Observed("known") },
    ) {
        fun envelope() = (store.locked { read() } as StoreRead.Loaded).envelope
    }

    private class RawShell : ShellGateway {
        override fun execute(request: ReadOnlyShellRequest): ShellResult {
            val output = when (request.kind) {
                CommandKind.AM_STACK_LIST -> "Stack id=0 displayId=0 userId=0\n  taskId=1: com.example.launcher/.Main"
                CommandKind.WM_DISPLAYS -> "Display: mDisplayId=0"
                CommandKind.DISPLAY_STATE -> "Display 0:\n  mDisplayId=0"
                CommandKind.PROFILE_STATE -> "10"
                CommandKind.ANIMATION_STATE -> "1.0\n0.5\n1.0"
                CommandKind.APP_OPS_STATE -> "No app ops"
                else -> error("Unexpected read ${request.kind}")
            }
            return ShellResult.Success(output, "", 1L)
        }
        override fun close() = Unit
    }

    private class MemoryAtomicBytes : AtomicBytes {
        private var bytes: ByteArray? = null
        override fun exists() = bytes != null
        override fun read(): ByteArray = bytes?.copyOf() ?: throw IOException("missing")
        override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
    }
}

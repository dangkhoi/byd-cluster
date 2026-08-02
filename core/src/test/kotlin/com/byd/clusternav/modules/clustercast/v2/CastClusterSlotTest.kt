package com.byd.clusternav.modules.clustercast.v2

import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Khoá phép ĐO THẬT ngày 2026-07-31 trên DiLink3 (adb thô, mức bằng chứng "đã chứng minh" — CLAUDE.md §2),
 * và khoá luôn ranh giới với đường chiếu MỘT app đang chạy ngoài hiện trường.
 *
 * Nguyên văn những gì đã đo, vì mọi con số dưới đây đều bắt nguồn từ đó:
 *  - freeform SỐNG trên display cụm: `am start --display 1 --windowingMode 1 -n <component>` ra cửa sổ
 *    không-toàn-màn, `am task resize <taskId> L T R B` đổi khung thành công;
 *  - HAI app cùng vẽ khi hai rect KHÔNG chồng nhau:
 *        am task resize <mapsTask>    0 90  960 810
 *        am task resize <vietmapTask> 960 90 1920 810
 *    `screencap -d 1` và cụm vật lý đều hiện GMaps trái / VietMap phải;
 *  - rect CHỒNG nhau thì KHÔNG ghép: app toàn màn che hẳn app kia (task kia vẫn còn trong `am stack list`
 *    với visible=true — chỉ bị vẽ đè);
 *  - Z-ORDER theo lần `am start` CUỐI, không theo lần resize cuối;
 *  - hình học cụm: display 1920×720, vùng vẽ thật `[0,90]–[1920,810]`.
 *
 * Dòng dump trong file này giữ NGUYÊN VĂN chữ của dump thật (cùng nguồn với
 * `CastMultiOccupantObservationTest` / `CastTaskBoundsObservationTest`), chỉ thay bốn con số của `bounds=`
 * theo đúng bố cục đang được kiểm.
 */
class CastClusterSlotTest {

    private val maps = "app.revanced.android.apps.maps"
    private val vietmap = "vn.vietmap.live"

    /** Rect ĐO ĐƯỢC: đúng hai dòng `am task resize` đã gõ trên xe. */
    private val leftHalf = CastRect(0, 90, 960, 810)
    private val rightHalf = CastRect(960, 90, 1920, 810)
    private val wholeCluster = CastRect(0, 90, 1920, 810)

    /**
     * Khung DISPLAY của cụm (`app 1920 x 720`) — dải mà ô được cắt ra khi cụm đang RỖNG, vì lúc ấy không
     * có khung task nào để đo dải vẽ. Trục dọc ở đây KHÔNG phải một lời khẳng định: xem
     * `ClusterSplit.slotBand`, và xem hai thế giới `…DisplaySpace` / `…Offset` bên dưới.
     */
    private val displayFrame = CastRect(0, 0, 1920, 720)
    private val leftHalfDisplaySpace = CastRect(0, 0, 960, 720)
    private val rightHalfDisplaySpace = CastRect(960, 0, 1920, 720)

    private val fiftyFifty = ClusterSlotLayout(
        mapOf(
            maps to ClusterSlot(ClusterSlotSide.LEFT, ClusterSplitRatio.EVEN),
            vietmap to ClusterSlot(ClusterSlotSide.RIGHT, ClusterSplitRatio.EVEN),
        ),
    )
    private val mapsLeftOnly = ClusterSlotLayout(
        mapOf(maps to ClusterSlot(ClusterSlotSide.LEFT, ClusterSplitRatio.EVEN)),
    )
    private val vietmapRightOnly = ClusterSlotLayout(
        mapOf(vietmap to ClusterSlot(ClusterSlotSide.RIGHT, ClusterSplitRatio.EVEN)),
    )

    // ── Dump thật ────────────────────────────────────────────────────────────────────────────────────

    /** Một app chiếm cả cụm — hình dạng ngay trước khi người lái xin chia đôi. */
    private val mapsFullscreen = """
        Stack id=17 bounds=[0,90][1920,810] displayId=2 userId=0
         mBounds=Rect(0, 90 - 1920, 810)
          taskId=15: app.revanced.android.apps.maps/com.google.android.maps.MapsActivity bounds=[0,90][1920,810] userId=0 visible=true topActivity=ComponentInfo{app.revanced.android.apps.maps/com.google.android.maps.MapsActivity}
    """.trimIndent()

    /** Cùng app đó sau khi đã được xếp vào ô TRÁI, cụm còn trống nửa phải. */
    private val mapsInLeftSlot = """
        Stack id=17 bounds=[0,90][960,810] displayId=2 userId=0
          taskId=15: app.revanced.android.apps.maps/com.google.android.maps.MapsActivity bounds=[0,90][960,810] userId=0 visible=true topActivity=ComponentInfo{app.revanced.android.apps.maps/com.google.android.maps.MapsActivity}
    """.trimIndent()

    // ── HAI THẾ GIỚI CỦA TRỤC DỌC, và vì sao cả hai đều phải chạy ────────────────────────────────────
    //
    // Lượt đặt app ĐẦU TIÊN vào một nửa gửi đi rect trong không gian DISPLAY (`0 0 960 720`), vì cụm rỗng
    // thì không có dải vẽ nào đo được. **Chưa đo** (docs/diagnostics/oncar-checklist-2026-08-01.md §9) hệ
    // thống đặt task ở dải dọc nào khi nhận rect ấy: có thể đúng `[0,0][960,720]`, có thể cộng offset
    // thành `[0,90][960,810]` như mọi khung task chụp được đêm 31/7. Đó chính là lý do phép xác minh chỉ
    // khẳng định trục NGANG. Hai fixture dưới đây là hai câu trả lời khả dĩ, và cả hai đều PHẢI xác minh
    // xong — nếu chỉ một cái chạy thì thiết kế này chưa làm đúng điều nó tự nhận.

    /** Thế giới A: hệ thống nhận nguyên rect trong không gian display. */
    private val mapsInLeftSlotDisplaySpace = """
        Stack id=17 bounds=[0,0][960,720] displayId=2 userId=0
          taskId=15: app.revanced.android.apps.maps/com.google.android.maps.MapsActivity bounds=[0,0][960,720] userId=0 visible=true topActivity=ComponentInfo{app.revanced.android.apps.maps/com.google.android.maps.MapsActivity}
    """.trimIndent()

    /** Thế giới A, bước hai: app thứ hai lấp nốt nửa phải trong cùng dải dọc hệ thống đã chọn. */
    private val fiftyFiftySplitDisplaySpace = """
        Stack id=17 bounds=[0,0][960,720] displayId=2 userId=0
          taskId=15: app.revanced.android.apps.maps/com.google.android.maps.MapsActivity bounds=[0,0][960,720] userId=0 visible=true topActivity=ComponentInfo{app.revanced.android.apps.maps/com.google.android.maps.MapsActivity}

        Stack id=18 bounds=[960,0][1920,720] displayId=2 userId=0
          taskId=14: vn.vietmap.live/vn.vietmap.live.MainActivity bounds=[960,0][1920,720] userId=0 visible=true topActivity=ComponentInfo{vn.vietmap.live/vn.vietmap.live.MainActivity}
    """.trimIndent()

    /** VietMap một mình ở nửa PHẢI — hình dạng sau khi người lái bắt đầu từ bên phải thay vì bên trái. */
    private val vietmapInRightSlot = """
        Stack id=18 bounds=[960,90][1920,810] displayId=2 userId=0
          taskId=14: vn.vietmap.live/vn.vietmap.live.MainActivity bounds=[960,90][1920,810] userId=0 visible=true topActivity=ComponentInfo{vn.vietmap.live/vn.vietmap.live.MainActivity}
    """.trimIndent()

    /**
     * Cụm CÓ task nhưng cao khác dải display (720) — bố cục không phải thứ app này biết đo.
     * Dùng để khoá điều ngược lại của `slotBand`: ca này KHÔNG được lặng lẽ mượn khung display.
     */
    private val mapsAtAnUnexpectedHeight = """
        Stack id=17 bounds=[0,90][1920,700] displayId=2 userId=0
          taskId=15: app.revanced.android.apps.maps/com.google.android.maps.MapsActivity bounds=[0,90][1920,700] userId=0 visible=true topActivity=ComponentInfo{app.revanced.android.apps.maps/com.google.android.maps.MapsActivity}
    """.trimIndent()

    /** Bố cục 50-50 hoàn chỉnh — đúng ảnh chụp trên xe. */
    private val fiftyFiftySplit = """
        Stack id=17 bounds=[0,90][960,810] displayId=2 userId=0
          taskId=15: app.revanced.android.apps.maps/com.google.android.maps.MapsActivity bounds=[0,90][960,810] userId=0 visible=true topActivity=ComponentInfo{app.revanced.android.apps.maps/com.google.android.maps.MapsActivity}

        Stack id=18 bounds=[960,90][1920,810] displayId=2 userId=0
          taskId=14: vn.vietmap.live/vn.vietmap.live.MainActivity bounds=[960,90][1920,810] userId=0 visible=true topActivity=ComponentInfo{vn.vietmap.live/vn.vietmap.live.MainActivity}
    """.trimIndent()

    /** Ca ĐÃ ĐO là hỏng: rect chồng nhau ⇒ app toàn màn che hẳn app kia. */
    private val overlappingFullscreenAndHalf = """
        Stack id=17 bounds=[0,90][1920,810] displayId=2 userId=0
          taskId=15: app.revanced.android.apps.maps/com.google.android.maps.MapsActivity bounds=[0,90][1920,810] userId=0 visible=true topActivity=ComponentInfo{app.revanced.android.apps.maps/com.google.android.maps.MapsActivity}

        Stack id=18 bounds=[960,90][1920,810] displayId=2 userId=0
          taskId=14: vn.vietmap.live/vn.vietmap.live.MainActivity bounds=[960,90][1920,810] userId=0 visible=true topActivity=ComponentInfo{vn.vietmap.live/vn.vietmap.live.MainActivity}
    """.trimIndent()

    /** Bố cục đúng, nhưng có thêm một app THỨ BA lọt lên cụm. */
    private val fiftyFiftyPlusStranger = fiftyFiftySplit + "\n\n" + """
        Stack id=19 bounds=[0,90][1920,810] displayId=2 userId=0
          taskId=21: com.byd.auto_photo/.PhotoActivity bounds=[0,90][1920,810] userId=0 visible=true topActivity=ComponentInfo{com.byd.auto_photo/.PhotoActivity}
    """.trimIndent()

    /**
     * Nửa trái đã xếp xong, nhưng có một app lạ ĐANG NẰM DƯỚI (visible=false) trên cùng display cụm —
     * hình dạng để phần lập kế hoạch còn chốt được đúng một target (`maps`) mà vẫn thấy kẻ lạ.
     */
    private val mapsInLeftSlotPlusStranger = mapsInLeftSlot + "\n\n" + """
        Stack id=19 bounds=[0,90][1920,810] displayId=2 userId=0
          taskId=21: com.byd.auto_photo/.PhotoActivity bounds=[0,90][1920,810] userId=0 visible=false topActivity=ComponentInfo{com.byd.auto_photo/.PhotoActivity}
    """.trimIndent()

    // ── Hình học thuần ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `fifty-fifty slots reproduce the exact rects typed on the vehicle`() {
        val content = ClusterSplit.contentRect(observe(mapsFullscreen))
        assertEquals(wholeCluster, content, "vùng vẽ phải là dải ĐO ĐƯỢC [0,90]-[1920,810]")
        val rects = fiftyFifty.resolve(content!!)!!
        assertEquals(leftHalf, rects.rectOf(maps))
        assertEquals(rightHalf, rects.rectOf(vietmap))
    }

    @Test
    fun `the content band comes from the measured task rects, never from the display frame`() {
        // Chính là bug F3: khung display là [0,0][1920,720], khung task thật là [0,90][1920,810]. Cắt ô từ
        // khung display là lệch 90px ở MỌI ô, và phép xác minh nghiêm ngặt sẽ "diverged" mọi lần.
        val state = observe(mapsFullscreen)
        assertEquals(CastRect(0, 0, 1920, 720), state.geometry?.bounds)
        assertEquals(wholeCluster, ClusterSplit.contentRect(state))
    }

    @Test
    fun `all three allowed ratios tile the cluster with no gap and no overlap`() {
        // Chồng nhau = một app che hẳn app kia (ĐÃ ĐO). Hở = khung đen giữa cụm. Ranh giới phải là MỘT.
        ClusterSplitRatio.values().forEach { ratio ->
            val left = ClusterSplit.slotRect(wholeCluster, ClusterSlot(ClusterSlotSide.LEFT, ratio))!!
            val right = ClusterSplit.slotRect(wholeCluster, ClusterSlot(ClusterSlotSide.RIGHT, ratio))!!
            assertEquals(wholeCluster.left, left.left, "$ratio")
            assertEquals(left.right, right.left, "$ratio: hai ô phải khít nhau tại đúng một ranh giới")
            assertEquals(wholeCluster.right, right.right, "$ratio")
            listOf(left, right).forEach {
                assertEquals(wholeCluster.top, it.top, "$ratio: ô nào cũng cao hết cụm")
                assertEquals(wholeCluster.bottom, it.bottom, "$ratio")
            }
        }
        assertEquals(
            CastRect(0, 90, 576, 810),
            ClusterSplit.slotRect(wholeCluster, ClusterSlot(ClusterSlotSide.LEFT, ClusterSplitRatio.LEFT_NARROW)),
            "30% của 1920 là 576",
        )
        assertEquals(
            CastRect(1344, 90, 1920, 810),
            ClusterSplit.slotRect(wholeCluster, ClusterSlot(ClusterSlotSide.RIGHT, ClusterSplitRatio.LEFT_WIDE)),
            "70% của 1920 là 1344",
        )
    }

    @Test
    fun `an empty cluster cuts its slots from the display frame, and only when proven empty`() {
        // Cụm rỗng: con số 90 KHÔNG đọc được ở đâu cả, và đó vẫn đúng — `contentRect` vẫn trả null.
        // Cái đổi là câu hỏi được hỏi: chia cụm là quyết định NGANG, mà trục ngang thì ĐÃ ĐO là đồng nhất
        // trong không gian display (0/960/1920 gõ vào, đọc lại đúng từng số — xem KDoc ClusterSpan).
        // Nên `slotBand` cắt ô từ khung display, đúng không gian toạ độ mà đường toàn cụm field-proven
        // vẫn gửi đi (`am task resize <t> 0 0 W H`).
        val empty = observe(EMPTY_CLUSTER, appOps = "")
        assertEquals(ObservedCoarseState.IDLE_CLEAN, empty.coarseState)
        assertNull(ClusterSplit.contentRect(empty), "cụm rỗng thì không có khung task nào để đo dải vẽ")
        assertEquals(displayFrame, ClusterSplit.slotBand(empty))
        assertEquals(
            leftHalfDisplaySpace,
            ClusterSplit.slotRect(displayFrame, ClusterSlot(ClusterSlotSide.LEFT, ClusterSplitRatio.EVEN)),
        )

        // …nhưng CHỈ khi cụm đã CHỨNG MINH là rỗng. Cụm CÓ task mà đo không nổi dải vẽ thì tuyệt đối
        // không được mượn khung display: ở trạng thái ấy `geometry` là kết quả một regex chạy trên
        // `wmDisplays + displays` và chưa đo được rằng dòng khớp đầu tiên thuộc display CỤM — chính điểm
        // yếu mà phép kiểm chéo chiều cao của `contentRect` sinh ra để bắt.
        val unmeasurable = observe(mapsAtAnUnexpectedHeight)
        assertNull(ClusterSplit.contentRect(unmeasurable))
        assertNull(
            ClusterSplit.slotBand(unmeasurable),
            "cụm có task mà bố cục lạ ⇒ chưa biết; mượn khung display ở đây là vô hiệu hoá phép kiểm chéo",
        )
    }

    @Test
    fun `a layout is refused when it cannot tile the cluster`() {
        assertThrows(IllegalArgumentException::class.java) {
            ClusterSlotLayout(
                mapOf(
                    maps to ClusterSlot(ClusterSlotSide.LEFT, ClusterSplitRatio.EVEN),
                    vietmap to ClusterSlot(ClusterSlotSide.LEFT, ClusterSplitRatio.EVEN),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClusterSlotLayout(
                mapOf(
                    maps to ClusterSlot(ClusterSlotSide.LEFT, ClusterSplitRatio.LEFT_NARROW),
                    vietmap to ClusterSlot(ClusterSlotSide.RIGHT, ClusterSplitRatio.LEFT_WIDE),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClusterSlotLayout(
                mapOf(
                    maps to ClusterSlot(ClusterSlotSide.LEFT, ClusterSplitRatio.EVEN),
                    vietmap to ClusterSlot(ClusterSlotSide.RIGHT, ClusterSplitRatio.EVEN),
                    "com.byd.auto_photo" to ClusterSlot(ClusterSlotSide.LEFT, ClusterSplitRatio.EVEN),
                ),
            )
        }
    }

    // ── Kế hoạch ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `placing the second app runs the field-proven ladder with the slot rect, and never returns the first`() {
        val plan = planSlot(observe(mapsInLeftSlot), target = vietmap, layout = fiftyFifty)

        // §6: thang đã kiểm thực địa KHÔNG được đảo. Chia ô dùng đúng thang đó, chỉ đổi rect của bước fit.
        assertEquals(ExpectedLadder.normal, plan.steps.map { it.commandKind })
        assertEquals(rightHalf, plan.geometry?.bounds)
        assertEquals(ClusterSplit.SLOT_PROFILE_ID, plan.geometry?.profileId)
        assertEquals(
            mapOf(maps to leftHalf, vietmap to rightHalf),
            plan.intendedLayout?.occupantRects,
        )
        // App đang giữ nửa kia KHÔNG phải "app đi ra". `RETURN_PROTECTED_GENTLY` là `am start --display 0`,
        // tức đuổi nó khỏi cụm — chạy rung đó là người lái xin hai app mà nhận về một.
        assertFalse(
            plan.steps.any { it.commandKind == CommandKind.RETURN_PROTECTED_GENTLY },
            "app giữ ô còn lại không được trả về màn giữa: ${plan.steps.map { it.id }}",
        )
    }

    @Test
    fun `re-slotting the app already on the cluster is a resize only, never a relaunch`() {
        // Z-ORDER đi theo lần `am start` CUỐI (ĐÃ ĐO). `am start` lại app đang chiếu là kéo nó lên trên
        // trong lúc rect của nó vẫn còn toàn cụm ⇒ che hẳn app kia. Resize thì không đổi thứ tự vẽ.
        val plan = planSlot(observe(mapsFullscreen), target = maps, layout = mapsLeftOnly)

        assertEquals(listOf(CommandKind.FIT_CLUSTER_COMPOSITE), plan.steps.map { it.commandKind })
        assertEquals(leftHalf, plan.geometry?.bounds)
        assertEquals(mapOf(maps to leftHalf), plan.intendedLayout?.occupantRects)
    }

    @Test
    fun `a split is refused while the other half is still fullscreen`() {
        // Một lượt đặt chỉ chạm được MỘT app (`CastMutationRequest` mang đúng một targetPackage), nên nửa
        // kia phải đã nằm đúng ô của nó. Nếu không: từ chối SỚM, thay vì phát lệnh rồi kẹt ở RECOVERING.
        val blocked = planResult(observe(mapsFullscreen), target = vietmap, layout = fiftyFifty)
        assertTrue(blocked is PlanResult.Blocked, "expected Blocked, got $blocked")
        assertTrue(
            (blocked as PlanResult.Blocked).reason.contains(maps) &&
                blocked.reason.contains("slot"),
            blocked.reason,
        )
    }

    @Test
    fun `a stranger already on the cluster refuses the split instead of burying it`() {
        // Một app không có tên trong bố cục hoặc sẽ bị che (rect chồng nhau — ĐÃ ĐO), hoặc đang chiếm chỗ
        // của một ô. Cả hai đều không phải thứ người lái yêu cầu ⇒ từ chối, để Dừng/Dọn cụm xử lý.
        val observed = observe(mapsInLeftSlotPlusStranger)
        assertEquals(setOf(maps, "com.byd.auto_photo"), observed.occupants)
        val blocked = planResult(observed, target = vietmap, layout = fiftyFifty)
        assertTrue(blocked is PlanResult.Blocked, "expected Blocked, got $blocked")
        assertTrue((blocked as PlanResult.Blocked).reason.contains("com.byd.auto_photo"), blocked.reason)
    }

    /**
     * LUẬT SẢN PHẨM chủ dự án chốt 2026-08-01: chạm nửa TRÁI ⇒ app đang mở vào nửa trái NGAY, nửa phải để
     * TRỐNG chờ app sau. Trước đó ca này bị từ chối thẳng ("Chưa chia đôi được · nửa kia chưa có app").
     *
     * Khoá ba điều, và cả ba đều là điều kiện để lượt đặt ấy đúng chứ không chỉ chạy được:
     *  1. thang lệnh vẫn là thang đã kiểm thực địa, không thêm không bớt không đảo (CLAUDE.md §6);
     *  2. rect đi ra xe là ô TRÁI cắt từ khung DISPLAY — cụm rỗng không có dải vẽ nào để đo;
     *  3. bố cục chỉ hứa MỘT app. Nửa phải không có tên ai, nên không có gì để so, và cũng không có rung
     *     nào chạm tới nó.
     */
    @Test
    fun `the first app goes into an empty left half, cut from the display frame`() {
        val plan = planFirstSlot(maps, mapsLeftOnly)

        assertEquals(ExpectedLadder.normal, plan.steps.map { it.commandKind })
        assertEquals(leftHalfDisplaySpace, plan.geometry?.bounds)
        assertEquals(ClusterSplit.SLOT_PROFILE_ID, plan.geometry?.profileId)
        assertEquals(mapOf(maps to leftHalfDisplaySpace), plan.intendedLayout?.occupantRects)
        assertEquals(setOf(ClusterSpan(0, 960)), plan.intendedLayout?.spans)
    }

    @Test
    fun `starting from the right half is the exact mirror, with no extra rung`() {
        val plan = planFirstSlot(vietmap, vietmapRightOnly)

        assertEquals(ExpectedLadder.normal, plan.steps.map { it.commandKind })
        assertEquals(rightHalfDisplaySpace, plan.geometry?.bounds)
        assertEquals(mapOf(vietmap to rightHalfDisplaySpace), plan.intendedLayout?.occupantRects)
    }

    @Test
    fun `all three ratios cut the first slot at the boundary measured on the vehicle`() {
        // 1920 × {30,50,70}% ⇒ ranh giới {576, 960, 1344}. Trục NGANG là thứ ĐÃ ĐO (0/960/1920 gõ vào,
        // đọc lại đúng từng số), nên đây là phép kiểm số học có bằng chứng đứng sau — không phải số đẹp.
        mapOf(
            ClusterSplitRatio.LEFT_NARROW to 576,
            ClusterSplitRatio.EVEN to 960,
            ClusterSplitRatio.LEFT_WIDE to 1344,
        ).forEach { (ratio, boundary) ->
            val left = planFirstSlot(
                maps,
                ClusterSlotLayout(mapOf(maps to ClusterSlot(ClusterSlotSide.LEFT, ratio))),
            )
            assertEquals(CastRect(0, 0, boundary, 720), left.geometry?.bounds, "$ratio trái")
            val right = planFirstSlot(
                vietmap,
                ClusterSlotLayout(mapOf(vietmap to ClusterSlot(ClusterSlotSide.RIGHT, ratio))),
            )
            assertEquals(CastRect(boundary, 0, 1920, 720), right.geometry?.bounds, "$ratio phải")
            // Ranh giới là DUY NHẤT cho cả hai bên ⇒ không chồng một pixel (chồng thì che hẳn — ĐÃ ĐO),
            // không hở một pixel (hở thì lộ khung đen giữa cụm).
            assertEquals(left.geometry?.bounds?.right, right.geometry?.bounds?.left, "$ratio")
        }
    }

    // ── Xác minh ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * ★ TEST QUAN TRỌNG NHẤT CỦA CẢ ĐỢT: lượt đặt app ĐẦU TIÊN vào một nửa cụm rỗng phải xác minh xong
     * **dù hệ thống chọn dải dọc nào**.
     *
     * Rect gửi đi là `0 0 960 720` (không gian display). **Chưa đo** hệ thống đặt task ở đâu khi nhận rect
     * ấy — xem docs/diagnostics/oncar-checklist-2026-08-01.md §9. Hai câu trả lời khả dĩ đều được chạy ở
     * đây; nếu chỉ một cái xác minh nổi thì phép so vẫn đang lén khẳng định trục dọc, và tính năng sẽ chết
     * đúng vào lần đầu chạm trên xe thật.
     *
     * Trục NGANG thì vẫn so tuyệt đối trong cả hai thế giới: `[0..960)`, đúng ba con số ĐÃ ĐO gõ tay
     * 31/7 và đọc lại nguyên vẹn.
     */
    @Test
    fun `the first app into an empty half verifies whichever vertical band the system gives it`() {
        listOf(mapsInLeftSlotDisplaySpace to "display space", mapsInLeftSlot to "offset by 90").forEach {
            (dump, world) ->
            val harness = harness(activeTarget = null, state = StableState.IDLE_VERIFIED)
            val operationId = harness.placeFirstApp()
            val landed = observe(dump)

            assertEquals(ObservedCoarseState.ACTIVE_SINGLE, landed.coarseState, world)
            assertEquals(setOf(maps), landed.occupants, world)
            // Đúng luật cũ: một mẫu chưa đủ, phải hai mẫu bằng nhau liên tiếp.
            assertFalse(harness.coordinator.completeVerification(operationId, landed), world)
            assertTrue(harness.coordinator.completeVerification(operationId, landed), world)

            val envelope = harness.envelope()
            assertNull(envelope.transaction, "$world: lượt đặt đúng ô thì transaction phải đóng")
            assertEquals(StableState.ACTIVE_VERIFIED, envelope.stableSession?.state, world)
            // Ô TRÁI đi ra tới tận cổng, ở cả ba rung fit của thang.
            harness.gateway.requests
                .filter { it.kind == CommandKind.FIT_CLUSTER_COMPOSITE }
                .forEach { assertEquals(leftHalfDisplaySpace, it.geometry?.bounds, world) }
        }
    }

    /**
     * Vế thứ hai của luật: mở app khác, chạm nửa còn lại, nó lấp nốt — và lấp được từ CẢ HAI thế giới
     * dọc mà lượt đầu có thể đã rơi vào.
     *
     * Đây là chỗ phép so theo DẢI NGANG ở planner trả công: nửa kia đang giữ `[0,0][960,720]` (thế giới A)
     * hay `[0,90][960,810]` (thế giới B) đều là "đã ở đúng ô của nó", vì ô của nó là một DẢI, không phải
     * một rect. Bản trước so cả rect nên thế giới A sẽ bị từ chối vĩnh viễn: app đầu nằm ngay ngắn ở nửa
     * trái mà app thứ hai không bao giờ vào được.
     */
    @Test
    fun `the second app fills the remaining half from either vertical the system chose`() {
        listOf(
            Triple(mapsInLeftSlotDisplaySpace, fiftyFiftySplitDisplaySpace, rightHalfDisplaySpace),
            Triple(mapsInLeftSlot, fiftyFiftySplit, rightHalf),
        ).forEach { (firstDump, bothDump, expectedRect) ->
            val onCluster = observe(firstDump)
            val plan = planSlot(onCluster, target = vietmap, layout = fiftyFifty)
            assertEquals(expectedRect, plan.geometry?.bounds, "ô phải cắt từ dải dọc mà cụm ĐANG dùng")
            assertFalse(
                plan.steps.any { it.commandKind == CommandKind.RETURN_PROTECTED_GENTLY },
                "app giữ nửa kia không được trả về màn giữa: ${plan.steps.map { it.id }}",
            )

            val harness = harness()
            val issued = harness.coordinator.execute(PlanResult.Ready(plan), vietmap)
                as ExecutionResult.AwaitingVerification
            val both = observe(bothDump, appOps = "")

            assertEquals(ObservedCoarseState.ACTIVE_MULTI, both.coarseState)
            assertFalse(harness.coordinator.completeVerification(issued.operationId, both))
            assertTrue(harness.coordinator.completeVerification(issued.operationId, both))
            assertNull(harness.envelope().transaction)
            assertEquals(StableState.ACTIVE_VERIFIED, harness.envelope().stableSession?.state)
        }
    }

    /**
     * Thứ tự ngược lại phải cho ĐÚNG một bố cục: bắt đầu từ nửa PHẢI rồi lấp nửa TRÁI.
     *
     * Có test riêng vì hai chiều đi qua hai nhánh khác nhau của [ClusterSplit.slotRect] (biên trái là
     * `content.left`, biên phải là `content.right`), và vì `CastPlanner` chỉ kiểm "app kia đã ở đúng ô
     * chưa" cho app KHÔNG phải target — tức mỗi chiều kiểm một app khác nhau.
     */
    @Test
    fun `both orders reach the same 50-50 layout`() {
        val rightFirst = planFirstSlot(vietmap, vietmapRightOnly)
        assertEquals(rightHalfDisplaySpace, rightFirst.geometry?.bounds)

        val thenLeft = planSlot(observe(vietmapInRightSlot, appOps = VIETMAP_APP_OPS), target = maps, layout = fiftyFifty)
        assertEquals(leftHalf, thenLeft.geometry?.bounds)
        assertEquals(
            mapOf(maps to leftHalf, vietmap to rightHalf),
            thenLeft.intendedLayout?.occupantRects,
            "cùng một bố cục dù người lái bắt đầu từ bên nào",
        )

        val harness = harness(activeTarget = vietmap)
        val issued = harness.coordinator.execute(PlanResult.Ready(thenLeft), maps)
            as ExecutionResult.AwaitingVerification
        val both = observe(fiftyFiftySplit, appOps = "")
        assertFalse(harness.coordinator.completeVerification(issued.operationId, both))
        assertTrue(harness.coordinator.completeVerification(issued.operationId, both))
        assertEquals(StableState.ACTIVE_VERIFIED, harness.envelope().stableSession?.state)
    }

    @Test
    fun `a 50-50 layout verifies when both task rects match what was asked for`() {
        val harness = harness()
        val operationId = harness.placeSecondApp()
        val split = observe(fiftyFiftySplit, appOps = "")

        // Đúng luật cũ: một mẫu chưa đủ, phải hai mẫu bằng nhau liên tiếp.
        assertFalse(harness.coordinator.completeVerification(operationId, split))
        assertTrue(harness.coordinator.completeVerification(operationId, split))

        val envelope = harness.envelope()
        assertNull(envelope.transaction, "một bố cục hai app đã đúng thì transaction phải đóng")
        assertEquals(StableState.ACTIVE_VERIFIED, envelope.stableSession?.state)
        assertEquals(ObservedCoarseState.ACTIVE_MULTI, split.coarseState)
        assertNull(split.target, "hai app cùng visible=true thì quan sát trung thực là không có target")
        // Rect của ô phải đi RA TỚI CỔNG, không chỉ nằm trong kế hoạch: đây là thứ tầng transport đọc để
        // gõ `am task resize`. Cả hai rung fit đều mang đúng rect ấy.
        assertEquals(
            listOf(rightHalf, rightHalf),
            harness.gateway.requests
                .filter { it.kind == CommandKind.FIT_CLUSTER_COMPOSITE }
                .map { it.geometry?.bounds },
        )
    }

    @Test
    fun `a fullscreen app beside a half-width one never verifies — measured, it hides the other`() {
        val harness = harness()
        val operationId = harness.placeSecondApp()
        val overlapping = observe(overlappingFullscreenAndHalf, appOps = "")

        // Đúng tập app, nhưng khung của GMaps vẫn là toàn cụm ⇒ hai rect chồng nhau ⇒ ĐÃ ĐO là một app
        // che hẳn app kia. Không được nhận là thành công chỉ vì "cả hai vẫn còn trong am stack list".
        assertEquals(setOf(maps, vietmap), overlapping.occupants, "cả hai vẫn có mặt — đó chính là cái bẫy")
        assertFalse(harness.coordinator.completeVerification(operationId, overlapping))
        assertFalse(harness.coordinator.completeVerification(operationId, overlapping))
        assertEquals(OperationPhase.RECOVERING, harness.envelope().transaction?.phase)
    }

    @Test
    fun `an unexpected third occupant fails verification even with both intended rects present`() {
        val harness = harness()
        val operationId = harness.placeSecondApp()
        val crowded = observe(fiftyFiftyPlusStranger, appOps = "")

        assertTrue(crowded.taskBounds.values.containsAll(listOf(leftHalf, rightHalf)))
        assertFalse(harness.coordinator.completeVerification(operationId, crowded))
        assertEquals(OperationPhase.RECOVERING, harness.envelope().transaction?.phase)
    }

    @Test
    fun `a slot transaction whose layout is no longer known never verifies`() {
        // Tiến trình chết giữa lúc chờ xác minh: `tx.requestedGeometry` (bền) vẫn nói "đây là một ô", còn
        // bố cục trong RAM thì mất. Ở đây dựng đúng hình dạng đó bằng cách phát lệnh THẲNG qua executor,
        // không qua coordinator — nên coordinator chưa bao giờ biết bố cục.
        val harness = harness()
        val plan = planSlot(observe(mapsInLeftSlot), target = vietmap, layout = fiftyFifty)
        val issued = harness.executor.execute(plan, CastBaseline(), vietmap)
            as ExecutionResult.AwaitingVerification
        val split = observe(fiftyFiftySplit, appOps = "")

        assertFalse(
            harness.coordinator.completeVerification(issued.operationId, split),
            "không biết đã hỏi bố cục gì thì không được tự hạ xuống phép kiểm một-app rồi báo thành công",
        )
        assertEquals(OperationPhase.RECOVERING, harness.envelope().transaction?.phase)
    }

    // ── Ranh giới với đường MỘT app (thứ chủ xe test sáng mai) ────────────────────────────────────────

    @Test
    fun `no slot requested means the plan is byte-for-byte what it was before`() {
        val idle = observe(EMPTY_CLUSTER, appOps = "")
        val result = CastPlanner.plan(
            CastIntent(CastIntentKind.CAST, maps),
            PlannerSnapshot(
                ObservationValue.Known(idle), idleSession(), TargetClass.NORMAL,
                installed = true, hasLauncher = true, plannerEpoch = 0L,
            ),
        )
        val plan = (result as PlanResult.Ready).plan

        assertEquals(ExpectedLadder.normal, plan.steps.map { it.commandKind })
        assertNull(plan.geometry, "không xin ô, không xin mật độ ⇒ geometry vẫn là null y như trước")
        assertNull(plan.intendedLayout, "không xin ô ⇒ không hứa bố cục nào")
    }

    @Test
    fun `the single-app path still verifies with no task rect observable at all`() {
        // Chốt chặn quan trọng nhất của cả đợt thay đổi: phép so rect nghiêm ngặt CHỈ áp cho lượt có ô.
        // Một lượt chiếu bình thường vẫn xác minh xong dù `taskBounds` rỗng hoàn toàn — đúng như hôm nay,
        // và đúng như mọi bản dump mà đường một-app đang gặp ngoài hiện trường.
        val harness = harness(activeTarget = null, state = StableState.IDLE_VERIFIED)
        val plan = CastPlan(
            CastOperation.CAST, harness.envelope().durableEpoch,
            listOf(PlannedStep("start", CommandKind.PLACE_KEEP_SESSION, "known")),
            "visible", setOf(CastAction.STOP), expectedDisplayIdentity = "display-2",
        )
        val issued = harness.coordinator.execute(PlanResult.Ready(plan), maps)
            as ExecutionResult.AwaitingVerification
        val singleApp = ObservedState(
            ObservedCoarseState.ACTIVE_SINGLE, "display-2", CastTarget(maps, 15, 2), setOf(maps), null,
            AcceptedGeometry(CastRect(0, 0, 1920, 720), 180, "android-user-0"),
        )
        assertTrue(singleApp.taskBounds.isEmpty(), "đúng hình dạng quan sát mà đường một-app vẫn gặp")

        assertFalse(harness.coordinator.completeVerification(issued.operationId, singleApp))
        assertTrue(harness.coordinator.completeVerification(issued.operationId, singleApp))
        assertEquals(StableState.ACTIVE_VERIFIED, harness.envelope().stableSession?.state)
    }

    // ── fixture ──────────────────────────────────────────────────────────────────────────────────────

    private fun observe(amStacks: String, appOps: String = MAPS_APP_OPS): ObservedState {
        val result = DumpObservedStateParser.parse(
            RawObservation(
                amStacks = amStacks,
                wmDisplays = "mBounds=Rect(0, 0 - 1920, 720)",
                displays = SEAL_CLUSTER_DUMP,
                profile = "0",
                animations = "1.0\n1.0\n1.0",
                appOps = appOps,
            ),
        )
        assertTrue(result is ObservationValue.Known, "expected Known, got $result")
        return (result as ObservationValue.Known).value
    }

    private fun planResult(
        observed: ObservedState,
        target: String,
        layout: ClusterSlotLayout,
    ): PlanResult = CastPlanner.plan(
        CastIntent(CastIntentKind.SWITCH, target, slotLayout = layout),
        PlannerSnapshot(
            ObservationValue.Known(observed),
            // SWITCH đòi `stable.activeTarget == observed.target` (CastPlanner.kt) — so bằng CẢ taskId,
            // nên phiên bền phải mang đúng target mà quan sát chốt được, không phải một taskId dựng sẵn.
            activeSession(observed.target ?: CastTarget(maps, 15, 2)),
            TargetClass.NORMAL,
            installed = true,
            hasLauncher = true,
            plannerEpoch = 0L,
        ),
    )

    private fun planSlot(observed: ObservedState, target: String, layout: ClusterSlotLayout): CastPlan {
        val result = planResult(observed, target, layout)
        assertTrue(result is PlanResult.Ready, "expected Ready, got $result")
        return (result as PlanResult.Ready).plan
    }

    /**
     * Lượt ĐẦU TIÊN: cụm rỗng, phiên IDLE_VERIFIED, nên đây là `CAST` chứ không phải `SWITCH` —
     * đúng nhánh mà `CastManualIntentRunner.executeOrdinary` chọn khi `stable.activeTarget == null`.
     */
    private fun planFirstSlot(target: String, layout: ClusterSlotLayout): CastPlan {
        val result = CastPlanner.plan(
            CastIntent(CastIntentKind.CAST, target, slotLayout = layout),
            PlannerSnapshot(
                ObservationValue.Known(observe(EMPTY_CLUSTER, appOps = "")), idleSession(), TargetClass.NORMAL,
                installed = true, hasLauncher = true, plannerEpoch = 0L,
            ),
        )
        assertTrue(result is PlanResult.Ready, "expected Ready, got $result")
        return (result as PlanResult.Ready).plan
    }

    private fun baseline() = CastBaseline(
        geometry = AcceptedGeometry(CastRect(0, 0, 1920, 720), 180, "android-user-0"),
        animationPerKey = mapOf(
            "window_animation_scale" to "1.0",
            "transition_animation_scale" to "1.0",
            "animator_duration_scale" to "1.0",
        ),
        pipMode = "allow",
        profile = "android-user-0",
    )

    private fun idleSession() = StableCastSession(
        StableState.IDLE_VERIFIED, EngineVersion.V2, "test", "seal-dl3-cold-bootstrap-v1", "display-2",
        baseline(), null, null, baseline().geometry, 1L,
    )

    private fun activeSession(target: CastTarget) = idleSession().copy(
        state = StableState.ACTIVE_VERIFIED,
        activeTarget = target,
    )

    private fun harness(
        activeTarget: String? = maps,
        state: StableState = StableState.ACTIVE_VERIFIED,
    ): Harness {
        val store = CastSessionStore(MemoryAtomicBytes())
        store.locked {
            initialize("boot")
            update {
                it.copy(
                    effectiveUiVersion = EngineVersion.V2,
                    stableSession = idleSession().copy(
                        state = state,
                        activeTarget = activeTarget?.let { pkg -> CastTarget(pkg, 15, 2) },
                    ),
                )
            }
        }
        val gateway = RecordingGateway()
        val executor = CastExecutor(
            store, gateway,
            nowEpochMillis = { 1_000L },
            operationId = idSequence(),
            sleeper = CastSleeper { },
        )
        val reader = ObservedStateReader(
            object : ShellGateway {
                override fun execute(request: ReadOnlyShellRequest) = ShellResult.Success("known", "", 1L)
                override fun close() = Unit
            },
        ) { ObservationValue.Unknown("this suite verifies with explicit observations") }
        val coordinator = CastCoordinator(
            store, reader, executor, CastRecovery(store, executor),
            now = { Instant.ofEpochMilli(1_000L) },
            manualSleeper = CastSleeper { },
            manualVerificationDelayMillis = 0L,
        )
        return Harness(store, executor, coordinator, gateway)
    }

    private inner class Harness(
        val store: CastSessionStore,
        val executor: CastExecutor,
        val coordinator: CastCoordinator,
        val gateway: RecordingGateway,
    ) {
        fun envelope() = (store.locked { read() } as StoreRead.Loaded).envelope

        /** Lượt thứ hai của bố cục: GMaps đã ở ô trái, đưa VietMap vào ô phải. */
        fun placeSecondApp(): UUID {
            val plan = planSlot(observe(mapsInLeftSlot), target = vietmap, layout = fiftyFifty)
            val issued = coordinator.execute(PlanResult.Ready(plan), vietmap)
            assertTrue(issued is ExecutionResult.AwaitingVerification, "expected dispatch, got $issued")
            return (issued as ExecutionResult.AwaitingVerification).operationId
        }

        /** Lượt ĐẦU TIÊN: cụm rỗng, GMaps vào ô trái cắt từ khung display. */
        fun placeFirstApp(): UUID {
            val issued = coordinator.execute(PlanResult.Ready(planFirstSlot(maps, mapsLeftOnly)), maps)
            assertTrue(issued is ExecutionResult.AwaitingVerification, "expected dispatch, got $issued")
            return (issued as ExecutionResult.AwaitingVerification).operationId
        }
    }

    private fun idSequence(): () -> UUID {
        var next = 1L
        return { UUID(0L, next++) }
    }

    private class RecordingGateway : CastMutationGateway {
        val requests = mutableListOf<CastMutationRequest>()
        private val fence = AtomicLong(0)
        override fun execute(request: CastMutationRequest): MutationResult {
            requests += request
            return MutationResult.Observed("ok")
        }
        override fun fenceInFlight() { fence.incrementAndGet() }
        override fun currentFenceToken(): Long = fence.get()
    }

    private class MemoryAtomicBytes : AtomicBytes {
        private var bytes: ByteArray? = null
        override fun exists() = bytes != null
        override fun read(): ByteArray = bytes?.copyOf() ?: throw IOException("missing")
        override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
    }

    private companion object {
        val MAPS_APP_OPS = "Package app.revanced.android.apps.maps:\n  PICTURE_IN_PICTURE: allow"
        val VIETMAP_APP_OPS = "Package vn.vietmap.live:\n  PICTURE_IN_PICTURE: allow"

        /** Cụm rỗng: chỉ còn một task ở màn giữa, đúng như dump thật sau một lượt Dừng. */
        val EMPTY_CLUSTER = """
            Stack id=0 bounds=[0,0][1920,1080] displayId=0 userId=0
              taskId=1: com.byd.launcher/.Main bounds=[0,0][1920,1080] userId=0 visible=true
        """.trimIndent()
    }
}

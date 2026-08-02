package com.byd.clusternav.modules.clustercast

import com.byd.clusternav.modules.clustercast.v2.AcceptedGeometry
import com.byd.clusternav.modules.clustercast.v2.BubbleZone
import com.byd.clusternav.modules.clustercast.v2.CastRect
import com.byd.clusternav.modules.clustercast.v2.CastTarget
import com.byd.clusternav.modules.clustercast.v2.ClusterOccupancy
import com.byd.clusternav.modules.clustercast.v2.ClusterOccupant
import com.byd.clusternav.modules.clustercast.v2.ClusterSlot
import com.byd.clusternav.modules.clustercast.v2.ClusterSlotSide
import com.byd.clusternav.modules.clustercast.v2.ClusterSplit
import com.byd.clusternav.modules.clustercast.v2.ClusterSplitRatio
import com.byd.clusternav.modules.clustercast.v2.ObservedCoarseState
import com.byd.clusternav.modules.clustercast.v2.ObservedState
import com.byd.clusternav.testsupport.SourceRoots
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T8 — hai ô NỬA CỤM của nút nổi được nối dây thật (docs/specs/cast-one-mode-and-three-zone-bubble.html
 * §R6/§R7). Test này khoá bốn thứ, mỗi thứ là một cách hỏng đã trả giá ở nơi khác trong dự án này:
 *
 *  1. **Chạm ô đang có app là TRẢ VỀ, tuyệt đối không chiếu lại.** Chủ dự án chốt "chỉ có cast ↔ trả,
 *     không có switch app". Một cú chiếu đè lên nửa đang có app không phải phiền toái nhỏ: ĐO THẬT trên
 *     DiLink3 (31/07) cho thấy rect chồng nhau thì app này che HẲN app kia và không có cơ chế tự thu
 *     nhỏ nào — người lái mất thứ đang nhìn giữa lúc đang lái.
 *  2. **Ô mà ngón tay vừa chạm phải theo được tới tận lệnh phát ra.** Bố cục ô đi kèm lượt đặt, và rect
 *     của ô phải khớp từng pixel với hai dòng `am task resize` đã gõ thật trên xe.
 *  3. **Tỉ lệ mặc định là 50-50** — tỉ lệ DUY NHẤT đã đo — và nó được ghi bền.
 *  4. **Kéo nút nổi vẫn chạy.** Bản nối dây này thêm một đường chạm mới; `BubbleDragGestureTest` khoá
 *     hình dạng của lời giải kéo-vs-chạm, còn ở đây khoá điều mà chính thay đổi này có thể phá: không
 *     có đường bắn chạm thứ hai nào mọc lên bên cạnh nó.
 *
 * Module `:app` không chạy Robolectric (xem `CastAppCatalogDensityDpiTest`), nên phần dính Android được
 * kiểm bằng quét-source theo đúng lệ của repo, còn phần THUẦN ([ClusterZoneReading]) được chạy thật với
 * dump số đo lấy trên xe.
 */
class CastHalfZoneWiringTest {

    private fun source(relative: String): String {
        val direct = Paths.get("app/src/$relative")
        val nested = SourceRoots.path("src/$relative")
        return (if (Files.exists(direct)) direct else nested).toFile().readText()
    }

    /** Chỉ phần MÃ: một chú thích nhắc lại tên hàm cũ không được tính là lời gọi. */
    private fun code(text: String): String = text.lineSequence()
        .filterNot { it.trimStart().let { line -> line.startsWith("//") || line.startsWith("*") || line.startsWith("/*") } }
        .joinToString("\n")

    private val bubble = code(source("main/java/com/byd/clusternav/modules/clustercast/FloatingBubbleService.kt"))
    private val facade = code(source("main/java/com/byd/clusternav/modules/clustercast/CastFacade.kt"))
    private val ratioSetting = code(source("main/java/com/byd/clusternav/modules/clustercast/CastSplitRatioSetting.kt"))
    private val controller = code(source("main/java/com/byd/clusternav/modules/clustercast/MainActivityCastController.kt"))

    private val halfTap = bubble.substringAfter("private fun dispatchHalfZone(zone: BubbleZone) {")
        .substringBefore("private fun returnZoneOccupant(")

    // ── Số đo THẬT, DiLink3, 2026-07-31 (adb thô) ──────────────────────────────────────────────────
    // Display cụm 1920×720; vùng vẽ đọc từ khung task là [0,90]–[1920,810] (số 90 có mặt ở MỌI dòng
    // task chụp được). Hai app cùng hiện khi và chỉ khi hai rect không chồng nhau, bằng đúng hai lệnh:
    //   am task resize <maps>    0 90  960 810
    //   am task resize <vietmap> 960 90 1920 810
    private val displayFrame = CastRect(0, 0, 1920, 720)
    private val fullBand = CastRect(0, 90, 1920, 810)
    private val leftHalf = CastRect(0, 90, 960, 810)
    private val rightHalf = CastRect(960, 90, 1920, 810)
    private val maps = "app.revanced.android.apps.maps"
    private val vietmap = "vn.vietmap.live"

    private fun observed(
        occupants: Set<String>,
        taskBounds: Map<Int, CastRect>,
        target: CastTarget? = null,
        coarseState: ObservedCoarseState = ObservedCoarseState.ACTIVE_SINGLE,
    ) = ObservedState(
        coarseState = coarseState,
        displayIdentity = "display-1",
        target = target,
        occupants = occupants,
        protectedResidue = null,
        geometry = AcceptedGeometry(displayFrame, null, "android-user-0"),
        taskBounds = taskBounds,
    )

    // ── 1. Ô có app ⇒ TRẢ VỀ ───────────────────────────────────────────────────────────────────────

    /**
     * Nhánh "ô đang có app" phải kết thúc ở đường TRẢ VỀ và không được chạm tới bất kỳ đường chiếu nào.
     *
     * Khoá bằng thứ tự trong chính thân hàm: cửa trả-về đứng TRƯỚC mọi thứ liên quan tới chiếu (dò app
     * đang mở, phát lệnh), và nhánh đó `return` ngay. Nếu một bản sau chèn một lượt chiếu vào giữa —
     * kiểu "chiếu lại cho chắc" — thì thứ tự này gãy.
     */
    @Test
    fun `an occupied half returns the app and never re-casts it`() {
        val returnGate = halfTap.indexOf("facade.zoneReturnsOnTap(decided, zone)")
        val foregroundRead = halfTap.indexOf("facade.foregroundPackage(")
        val dispatch = halfTap.indexOf("dispatchTarget(foreground, zone)")
        assertTrue(returnGate > 0, "thiếu cửa trả-về trong dispatchHalfZone")
        assertTrue(returnGate < foregroundRead, "phải quyết định trả-về TRƯỚC khi đi dò app để chiếu")
        assertTrue(foregroundRead < dispatch)

        // Nhánh trả-về không được chứa một mảnh nào của đường chiếu.
        val returning = halfTap.substringAfter("facade.zoneReturnsOnTap(decided, zone)")
            .substringBefore("if (foreignMutationInFlight())")
        assertTrue(returning.contains("returnZoneOccupant(decided, zone)"))
        assertFalse(returning.contains("dispatchTarget("), "trả về mà lại chiếu là đúng cái bị cấm")
        assertFalse(returning.contains("runHalfIntent"))
        assertFalse(returning.contains("runManualIntent"))

        // Và "trả về" nghĩa là đúng đường Dừng đã field-proven, không phải một plan/execute viết riêng.
        val returnBody = bubble.substringAfter("private fun returnZoneOccupant(")
            .substringBefore("private fun onPrimaryTap()")
        assertTrue(returnBody.contains("requestStopOnce()"))
        assertFalse(returnBody.contains("facade.planStop("), "nút nổi không được là chủ sở hữu mutation thứ hai")
        assertFalse(returnBody.contains("facade.executeAndSettle("))
        // Khi ô thuộc về một app KHÁC app đang giữ phiên thì Dừng sẽ trả NHẦM app — phải từ chối, và
        // phải chỉ ra đường thoát thật (nút cứu hộ ở Home), không im lặng.
        assertTrue(returnBody.contains("occupant != null && active != null && occupant != active"))
        assertTrue(returnBody.contains("Trả cụm về đồng hồ"))
        // [P1] review Pass 2: một bố cục HAI app xác minh xong ghi `activeTarget = null` (quan sát trung
        // thực của hai app cùng visible=true), nên vế `active != null` ở trên KHÔNG bắt được nó — và
        // đường Dừng khi ấy vẫn phát 18/0 đóng đường chiếu trong khi cả hai app còn nằm trên cụm: đồng hồ
        // về, hai app biến mất, transaction rơi vào RECOVERING. Đếm occupant ĐO ĐƯỢC là cửa duy nhất bắt
        // được ca đó, và nó phải nằm trong CÙNG một biểu thức từ chối.
        assertTrue(
            returnBody.contains("projection.measuredOccupants > 1"),
            "cụm nhiều occupant phải đi Dọn cụm, không đi Dừng",
        )

        // Câu hỏi là HÀNH ĐỘNG của ô, không phải màu tô của nó (xem KDoc BubbleProjection.stopOnTap).
        assertTrue(facade.contains("projection.zone(zone)?.action == BubbleZoneAction.RETURN"))
        assertFalse(halfTap.contains("cell?.occupied"), "occupied là câu hỏi VẼ, không phải câu hỏi HÀNH ĐỘNG")
    }

    /**
     * Cùng kỷ luật với ô cả cụm: quyết định chỉ được đưa ra SAU một lần đọc lại sự thật.
     *
     * Đây là bài học đã trả giá thật (CLAUDE.md §5, và `MainActivityCastControllerWiringTest` khoá đúng
     * điều này cho [FloatingBubbleService.onPrimaryTap]): ảnh chụp `lastProjection` có thể cũ 15 giây,
     * mà cụm còn bị đổi từ panel Home và từ tự-chiếu-lúc-khởi-động.
     */
    @Test
    fun `a half tap decides from a fresh read, not from the 15s-old snapshot`() {
        val fresh = halfTap.indexOf("project(token)")
        assertTrue(fresh > 0, "cú chạm nửa cụm phải tự đọc lại sự thật")
        assertTrue(fresh < halfTap.indexOf("facade.zoneReturnsOnTap(decided, zone)"))
        assertTrue(halfTap.contains("val decided = fresh ?: lastProjection"))
        // Không tạo hàng đợi bền: một yêu cầu-theo-ô bị xếp hàng sẽ sống lại thành lượt chiếu TOÀN CỤM.
        assertTrue(halfTap.contains("foreignMutationInFlight()"))
        assertTrue(halfTap.indexOf("foreignMutationInFlight()") < halfTap.indexOf("dispatchTarget(foreground, zone)"))
        // Cú chạm rơi vào lúc bận phải NÓI ra, không bị nuốt.
        assertTrue(halfTap.contains("Đang có thao tác khác chạy"))
        assertTrue(
            halfTap.trimStart().startsWith("if (dispatchInFlight.get() || stopAckPending()) { toast(\"Đang xử lý…\"); return }"),
            "cua dang-ban phai la dong dau tien, truoc ca luot doc lai",
        )
    }

    // ── 2. Ô đi theo tới tận lệnh phát ra ──────────────────────────────────────────────────────────

    /** Nút nổi phải chuyển ĐÚNG ô vừa chạm và tỉ lệ đang chọn xuống façade, không hạ cấp thành toàn cụm. */
    @Test
    fun `a half cast carries the tapped zone and the chosen ratio down to the facade`() {
        assertTrue(halfTap.contains("dispatchTarget(foreground, zone)"))
        val dispatch = bubble.substringAfter("private fun dispatchTarget(packageName: String, zone: BubbleZone = BubbleZone.FULL)")
            .substringBefore("private fun requestStopOnce()")
        assertTrue(dispatch.contains("if (zone == BubbleZone.FULL) {"))
        assertTrue(dispatch.contains("facade.runHalfIntent("))
        assertTrue(dispatch.contains("splitRatio.leftPercent()"), "tỉ lệ phải là tỉ lệ người lái đã chọn")
        // Mật độ/kiểu cụm theo app vẫn đi cùng, y như đường toàn cụm (CastFieldParityTest khoá phần này).
        assertEquals(2, Regex("catalog\\.clusterDensityDpi\\(packageName\\)").findAll(dispatch).count())
        assertEquals(2, Regex("catalog\\.clusterStyle\\(packageName\\)").findAll(dispatch).count())
        // Façade dịch ô → cạnh, và từ chối thẳng khi ai đó đưa nhầm ô cả cụm vào đường nửa cụm.
        assertTrue(facade.contains("BubbleZone.LEFT -> ClusterSlotSide.LEFT"))
        assertTrue(facade.contains("BubbleZone.RIGHT -> ClusterSlotSide.RIGHT"))
        assertTrue(facade.contains("BubbleZone.FULL -> return CastManualIntentResult.Blocked("))
        // Bố cục dựng từ quan sát tươi của chính lượt phát, không nhận từ bề mặt.
        val half = facade.substringAfter("fun runHalfIntent(").substringBefore("fun initialize(bootId: String)")
        assertTrue(half.contains("val observed = observedState()"))
        assertTrue(half.contains("ClusterZoneReading.layout(observed, packageName, ClusterSlot(side, splitRatio(leftPercent)))"))
        assertTrue(half.contains("slotLayout = layout"))
    }

    /**
     * Rect của ô phải bằng ĐÚNG hai dòng `am task resize` đã gõ trên xe — đây là phép kiểm số học duy
     * nhất trong file này chạy thật, và nó là thứ chứng minh "ô" không phải một khái niệm trang trí.
     */
    @Test
    fun `the slot layout resolves to the exact rects measured on the vehicle`() {
        val onCluster = observed(
            occupants = setOf(vietmap),
            taskBounds = mapOf(11 to leftHalf),
            target = CastTarget(vietmap, 11, 1),
        )
        val band = ClusterSplit.contentRect(onCluster)
        assertEquals(fullBand, band, "dải vẽ phải cắt từ khung TASK thật, không phải khung display")
        val content = requireNotNull(band)

        val layout = requireNotNull(
            ClusterZoneReading.layout(onCluster, maps, ClusterSlot(ClusterSlotSide.RIGHT, ClusterSplitRatio.EVEN)),
        )
        // App kia được gán nửa còn lại — bắt buộc, vì một bố cục chỉ chứa một app mỗi bên.
        assertEquals(
            mapOf(maps to rightHalf, vietmap to leftHalf),
            requireNotNull(layout.resolve(content)).occupantRects,
        )

        // Xếp lại chính app đang chiếu vào ô của nó: không có app kia, bố cục chỉ có một tên.
        val reslot = requireNotNull(
            ClusterZoneReading.layout(onCluster, vietmap, ClusterSlot(ClusterSlotSide.LEFT, ClusterSplitRatio.EVEN)),
        )
        assertEquals(mapOf(vietmap to leftHalf), requireNotNull(reslot.resolve(content)).occupantRects)

        // Ba app trên cụm thì không có ô nào để mà xếp — từ chối, không đoán.
        val crowded = observed(
            occupants = setOf(vietmap, maps, "com.byd.airjoy"),
            taskBounds = mapOf(11 to leftHalf, 12 to rightHalf),
        )
        assertNull(
            ClusterZoneReading.layout(crowded, "com.example.third", ClusterSlot(ClusterSlotSide.LEFT, ClusterSplitRatio.EVEN)),
        )
    }

    // ── 3. Occupancy THẬT theo ô, và "chưa biết" không bao giờ bị đọc thành "trống" ────────────────

    @Test
    fun `occupancy comes from real task rects and stays Unmeasured when it cannot be truthful`() {
        // Một app chiếm trọn cụm ⇒ ô cả cụm có app; hai nửa không dùng được (luật bố cục ở projection).
        assertEquals(
            ClusterOccupancy.Measured(listOf(ClusterOccupant(BubbleZone.FULL, vietmap))),
            ClusterZoneReading.occupancy(
                observed(setOf(vietmap), mapOf(11 to fullBand), CastTarget(vietmap, 11, 1)),
            ),
        )

        // Chia đôi 50-50: tên gói của task không-phải-target suy ra được vì nó là khả năng DUY NHẤT.
        val split = ClusterZoneReading.occupancy(
            observed(
                occupants = setOf(vietmap, maps),
                taskBounds = mapOf(11 to leftHalf, 12 to rightHalf),
                target = CastTarget(maps, 12, 1),
                coarseState = ObservedCoarseState.ACTIVE_MULTI,
            ),
        )
        assertEquals(
            setOf(ClusterOccupant(BubbleZone.LEFT, vietmap), ClusterOccupant(BubbleZone.RIGHT, maps)),
            (split as ClusterOccupancy.Measured).occupants.toSet(),
        )

        // Cụm ĐÃ CHỨNG MINH là rỗng mới được nói là rỗng…
        assertEquals(
            ClusterOccupancy.Measured(emptyList()),
            ClusterZoneReading.occupancy(observed(emptySet(), emptyMap(), coarseState = ObservedCoarseState.IDLE_CLEAN)),
        )
        // …còn "có app trên cụm nhưng dump không in bounds" là CHƯA BIẾT, không phải trống. Đây đúng là
        // ca sinh ra `ClusterOccupancy.Unmeasured`: đọc nhầm nó thành trống là mời một cú chạm chiếu đè.
        assertEquals(
            ClusterOccupancy.Unmeasured,
            ClusterZoneReading.occupancy(observed(setOf(vietmap), emptyMap())),
        )
        // Task nằm ở một khung không phải ô nào (người dùng/ROM tự resize) ⇒ chưa biết, không bịa.
        assertEquals(
            ClusterOccupancy.Unmeasured,
            ClusterZoneReading.occupancy(observed(setOf(vietmap), mapOf(11 to CastRect(0, 90, 1000, 810)))),
        )
        // Hai app mà chỉ đọc được một khung ⇒ bản đồ sẽ thiếu đúng một app ⇒ chưa biết.
        assertEquals(
            ClusterOccupancy.Unmeasured,
            ClusterZoneReading.occupancy(observed(setOf(vietmap, maps), mapOf(11 to leftHalf))),
        )
        // Không quan sát được gì cả ⇒ chưa biết (không phải trống).
        assertEquals(ClusterOccupancy.Unmeasured, ClusterZoneReading.occupancy(null))

        // Và nó thật sự tới được nút nổi: façade đưa occupancy đo được vào chính bản chiếu đang vẽ.
        assertTrue(facade.contains("occupancy = ClusterZoneReading.occupancy(observed)"))
        assertTrue(bubble.contains("facade.bubbleProjection { durableStop -> localAckActive(durableStop) }"))
    }

    // ── 4. Tỉ lệ: mặc định 50-50, lưu bền, sống ở panel Home ───────────────────────────────────────

    @Test
    fun `the split ratio defaults to 50-50, is persisted, and lives in the Home cast panel`() {
        // 50-50 là tỉ lệ ĐÃ ĐO trên xe; hai tỉ lệ còn lại mới chỉ suy ra từ cùng phép chia.
        assertEquals(50, ClusterSplitRatio.EVEN.leftPercent)
        assertEquals(listOf(50, 30, 70), ClusterSplitRatio.entries.map { it.leftPercent })
        assertTrue(facade.contains("val DEFAULT_SPLIT_LEFT_PERCENT: Int = ClusterSplitRatio.EVEN.leftPercent"))
        // Nhãn được DỰNG từ leftPercent, không chép tay — thêm/bớt tỉ lệ ở :core là dropdown tự đúng.
        assertTrue(facade.contains("fun splitRatioChoices(): List<SplitRatioChoice> = ClusterSplitRatio.entries.map {"))

        // Lưu bền: ghi bằng commit (cùng lệ CastAppCatalog), lọc lại cả lúc đọc lẫn lúc ghi.
        assertTrue(ratioSetting.contains("check(prefs.edit().putInt(KEY, value).commit())"))
        assertTrue(ratioSetting.contains("prefs.getInt(KEY, CastFacade.DEFAULT_SPLIT_LEFT_PERCENT)"))
        assertTrue(ratioSetting.contains("CastFacade.splitRatioChoices().any { it.leftPercent == stored }"))
        assertTrue(ratioSetting.contains("require(CastFacade.splitRatioChoices().any { it.leftPercent == value })"))
        // Không ghi lại đúng giá trị đang có: Spinner bắn onItemSelected một lần cho lựa chọn đặt bằng
        // code — chính lớp lỗi đã giết yêu cầu tự-chiếu ở CastAutoStartBinding.
        assertTrue(ratioSetting.contains("if (chosen == applied) return"))
        // Đọc/ghi prefs không nằm trên luồng vẽ.
        assertTrue(ratioSetting.contains("background {"))

        // Sống trong panel Cast của Home, cạnh ô tick tự-chiếu, và có mặt ở MỌI biến thể layout
        // (`MainActivityCastControllerWiringTest` khoá phần "mọi biến thể").
        assertTrue(controller.contains("CastSplitRatioBinding("))
        assertTrue(controller.contains("splitRatio.bind(activity.findViewById(R.id.spinner_split_ratio))"))
        listOf("main/res/layout/activity_main.xml", "main/res/layout-w960dp/activity_main.xml").forEach {
            val markup = source(it)
            assertTrue(markup.contains("@+id/spinner_split_ratio"), "$it thiếu ô chọn tỉ lệ")
            assertTrue(
                markup.indexOf("@+id/spinner_autostart_app") < markup.indexOf("@+id/spinner_split_ratio"),
                "$it: tỉ lệ phải nằm trong nhóm cài đặt Cast, ngay dưới nhóm tự-chiếu",
            )
        }
    }

    // ── 5. Đường kéo không được sứt mẻ vì đường chạm mới ───────────────────────────────────────────

    /**
     * Bản nối dây này thêm một đường chạm; thứ nó có thể phá là hợp đồng kéo-vs-chạm mà
     * `BubbleDragGestureTest` khoá (một view, MỘT trọng tài). Khoá lại đúng phần đó ở đây để lỗi hiện ra
     * ngay trong bộ test của chính thay đổi này: vẫn đúng MỘT nơi nhận chạm, MỘT listener chạm, MỘT chỗ
     * phát cú chạm, và không có cử chỉ ẩn nào được thêm để "bù" cho ô nửa cụm.
     */
    @Test
    fun `wiring the halves adds no second tap path, so dragging still works`() {
        assertEquals(1, Regex("setOnClickListener").findAll(bubble).count())
        assertEquals(1, Regex("setOnTouchListener \\{").findAll(bubble).count())
        assertEquals(1, Regex("performClick\\(\\)").findAll(bubble).count())
        assertFalse(bubble.contains("setOnLongClickListener"))
        assertFalse(bubble.contains("GestureDetector"))
        // Cả ba ô vẫn đi qua đúng một cửa vào, và cửa đó vẫn là nơi luật §R7 được thi hành.
        assertTrue(bubble.contains("onZoneTap(zone)"))
        assertTrue(bubble.contains("if (zone == BubbleZone.FULL) { onPrimaryTap(); return }"))
        assertTrue(bubble.contains("dispatchHalfZone(zone)"))
        // Cử chỉ kéo vẫn được gắn lên MỌI view của cây, kể cả ba ô đang giữ listener chạm.
        assertTrue(bubble.contains("attachDragToEveryTouchSurface(root, root, layout, manager)"))
        assertTrue(bubble.contains("val slop = ViewConfiguration.get(this).scaledTouchSlop"))
    }
}

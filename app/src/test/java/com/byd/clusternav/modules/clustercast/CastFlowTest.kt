package com.byd.clusternav.modules.clustercast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * E2E FLOW tests cho các usecase chiếu-cụm — chạy logic quyết định thật của [ClusterCast] qua [FakeShell]
 * (không cần xe). Bọc từng usecase theo docs/review/track1-feature-usecase-test-plan.md.
 */
class CastFlowTest {

    private val silent: (String) -> Unit = {}
    private fun sink(id: Int, task: Int, disp: Int, amVisible: Boolean = true) =
        FakeStack(id, disp, task, "com.byd.carplay.ui/com.byd.carplay.ui.VideoActivity", amVisible = amVisible)
    private fun app(id: Int, task: Int, disp: Int, pkg: String = "vn.vietmap.live") =
        FakeStack(id, disp, task, "$pkg/.MainActivity")

    // ───────── UC3: TEARDOWN-GUARD (P0) ─────────
    @Test
    fun `UC3 guard be sink CP-AA khoi VD truoc khi teardown`() {
        val dev = FakeDevice(vd = 1)
            .add(app(0, 5, 0, "com.android.launcher3"))
            .add(sink(66, 71, 1))          // CarPlay đang bám VD cụm
        val ok = ClusterCast.guardSinksOffVd(fakeShell(dev), vd = 1, keepPkg = "", log = silent)
        assertTrue(ok, "guard phải thành công khi bê được sink")
        assertEquals(1, dev.moveCount, "bê đúng 1 lần (move-stack)")
        // sink giờ ở display 0, VD sạch → giữ phiên (không force-stop)
        assertTrue(ClusterCast.phoneProjectionSinksOn(StackParse.parse(dev.amStackList()), 1).isEmpty())
        assertEquals(0, dev.stacks.first { it.stackId == 66 }.displayId)
    }

    @Test
    fun `UC3 move-stack fail thi FAIL-SAFE (khong cho teardown)`() {
        val dev = FakeDevice(vd = 1, moveFailStacks = mutableSetOf(66))
            .add(app(0, 5, 0, "com.android.launcher3"))
            .add(sink(66, 71, 1))
        val ok = ClusterCast.guardSinksOffVd(fakeShell(dev), vd = 1, keepPkg = "", log = silent)
        assertFalse(ok, "move hụt → guard trả false → phía gọi KHÔNG teardown (chống mồ côi)")
        assertEquals(1, dev.stacks.first { it.stackId == 66 }.displayId, "sink vẫn ở VD (không bị bỏ nửa vời)")
    }

    @Test
    fun `UC3 khong co sink thi guard qua ngay`() {
        val dev = FakeDevice(vd = 1).add(app(44, 50, 1))   // chỉ Vietmap (app thường) trên VD
        assertTrue(ClusterCast.guardSinksOffVd(fakeShell(dev), 1, "", silent))
        assertEquals(0, dev.moveCount, "không sink → không bê gì")
    }

    @Test
    fun `UC3 keepPkg duoc loai (dang chieu chinh sink do)`() {
        val dev = FakeDevice(vd = 1).add(sink(66, 71, 1))
        // đang chiếu CHÍNH CarPlay lên cụm → guard với keepPkg=carplay KHÔNG được bê nó đi
        val ok = ClusterCast.guardSinksOffVd(fakeShell(dev), 1, keepPkg = "com.byd.carplay.ui", log = silent)
        assertTrue(ok)
        assertEquals(0, dev.moveCount)
        assertEquals(1, dev.stacks.first { it.stackId == 66 }.displayId, "sink đích giữ nguyên trên VD")
    }

    @Test
    fun `UC3 vd khong hop le thi guard no-op`() {
        val dev = FakeDevice(vd = 1).add(sink(66, 71, 1))
        assertTrue(ClusterCast.guardSinksOffVd(fakeShell(dev), vd = 0, keepPkg = "", log = silent))
        assertEquals(0, dev.moveCount)
    }

    // ───────── UC9: DIVERGENCE (WM↔AM lệch = orphan) (P0) ─────────
    @Test
    fun `UC9 orphan (WM co AM khong) thi CHAN thao tac cum`() {
        val dev = FakeDevice(vd = 1)
            .add(app(3, 5, 0, "com.android.launcher3"))     // AM có gì đó (không rỗng)
            .add(sink(66, 71, 1, amVisible = false))         // CarPlay mồ côi: WM thấy, AM không
        val verdict = ClusterCast.divergenceOn(fakeShell(dev), vd = 1)
        assertNotNull(verdict, "phải phát hiện mồ côi và chặn")
        assertTrue(verdict!!.contains("mồ côi") || verdict.contains("tắt máy"), "thông điệp cảnh báo đúng")
    }

    @Test
    fun `UC9 cum sach thi khong chan`() {
        val dev = FakeDevice(vd = 1)
            .add(app(3, 5, 0, "com.android.launcher3"))
            .add(app(44, 50, 1))    // Vietmap trên VD, AM+WM đều thấy → không lệch
        assertNull(ClusterCast.divergenceOn(fakeShell(dev), vd = 1))
    }

    @Test
    fun `UC9 am rong thi KHONG ket luan mo coi (chong false-positive shell hut)`() {
        val dev = FakeDevice(vd = 1).add(sink(66, 71, 1, amVisible = false))  // AM rỗng hoàn toàn
        assertNull(ClusterCast.divergenceOn(fakeShell(dev), vd = 1), "am rỗng = shell hụt, KHÔNG báo động")
    }

    // ───────── UC7/UC8: SCALE / RESIZE ─────────
    @Test
    fun `UC8 GUARD P0 - task KHONG o VD thi TU CHOI resize (khong dung man giua)`() {
        val dev = FakeDevice(vd = 1, freeformAlive = true)
        val onMain = StackEntry(stackId = 44, displayId = 0, mode = "fullscreen",
            activityType = "standard", taskId = 50, comp = "vn.vietmap.live/.MainActivity", visible = true)
        val r = ClusterCast.applyBounds(fakeShell(dev), vd = 1, e = onMain, scale = AppScale(dpi = 320), w = 1920, h = 720)
        assertTrue(r.contains("BỎ QUA"), "task ở display 0 ≠ VD 1 → phải từ chối")
        assertEquals(0, dev.resizeCount, "TUYỆT ĐỐI không bắn am task resize vào task màn giữa")
    }

    @Test
    fun `UC8 GUARD - vd khong hop le thi bo qua`() {
        val dev = FakeDevice(vd = 1, freeformAlive = true)
        val e = StackEntry(1, 1, "fullscreen", "standard", 71, "com.byd.carplay.ui/.VideoActivity", true)
        val r = ClusterCast.applyBounds(fakeShell(dev), vd = 0, e = e, scale = AppScale(dpi = 320), w = 1920, h = 720)
        assertTrue(r.contains("BỎ QUA"))
        assertEquals(0, dev.resizeCount)
    }

    @Test
    fun `UC7 freeform song - dung am task resize (tier 1)`() {
        val dev = FakeDevice(vd = 1, freeformAlive = true)
        val e = StackEntry(1, 1, "freeform", "standard", 71, "com.byd.carplay.ui/.VideoActivity", true)
        val r = ClusterCast.applyBounds(fakeShell(dev), vd = 1, e = e, scale = AppScale(dpi = 320), w = 1920, h = 720)
        assertTrue(r.contains("resize"), "freeform sống → tier resize: $r")
        assertTrue(dev.resizeCount >= 1)
    }

    // ───────── UC2/UC10: WARM vs COLD + floating cleanup (StackParse-driven decisions) ─────────
    @Test
    fun `UC2 isWarm dung khi VD dang co app thuong`() {
        val dev = FakeDevice(vd = 1).add(app(44, 50, 1))
        assertTrue(StackParse.isWarm(1, StackParse.parse(dev.amStackList())), "VD có app → warm switch")
    }

    @Test
    fun `UC1 cold khi VD trong`() {
        val dev = FakeDevice(vd = 1).add(app(3, 5, 0, "com.android.launcher3"))
        assertFalse(StackParse.isWarm(1, StackParse.parse(dev.amStackList())), "VD trống → cold cast")
    }

    // ───────── FIDELITY: khoá định dạng fake khớp regex parser thật ─────────
    // Vì sao cần: dumpsysDisplay() KHÔNG được exercise trực tiếp bởi các flow test ở trên (applyBounds nhận
    // w/h tường minh), nên nếu format fake trôi khỏi định dạng DiLink3 thì KHÔNG test nào bắt được → false
    // confidence. Test này là con mắt đó: nó đọc CHÍNH output fake qua DisplayParse (regex thật) và chốt số.
    @Test
    fun `FIDELITY dumpsysDisplay khop regex DisplayParse`() {
        val dev = FakeDevice(vd = 1, vdW = 1920, vdH = 720)
        val dump = dev.dumpsysDisplay()
        assertEquals(1920 to 720, DisplayParse.realSize(dump, 1), "realSize phải đọc đúng kích thước VD từ fake")
        assertEquals(1920 to 1080, DisplayParse.realSize(dump, 0), "realSize display 0 (màn giữa)")
        assertEquals(1, DisplayParse.clusterDisplayId(dump), "clusterDisplayId nhận ra VD theo tên fission/xdja")
    }

    // Fake phải THAM SỐ HOÁ đúng theo vd/vdW/vdH (đời xe khác kích cụm khác — vd Dudu chiếm id 1, cụm lùi về 2).
    @Test
    fun `FIDELITY dumpsysDisplay tham so hoa theo vd va kich thuoc`() {
        val dev = FakeDevice(vd = 2, vdW = 1284, vdH = 720)
        val dump = dev.dumpsysDisplay()
        assertEquals(1284 to 720, DisplayParse.realSize(dump, 2), "VD id/size phải theo tham số FakeDevice")
        assertEquals(2, DisplayParse.clusterDisplayId(dump), "clusterDisplayId bám tên cụm, không hardcode id 1")
    }
}

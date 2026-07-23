package com.byd.clusternav.modules.clustercast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * STRESS / LOAD — mô phỏng chiếu-đổi-app + chỉnh-kích-thước LIÊN TỤC (yêu cầu hiện trường). Chạy logic thật
 * qua [FakeShell] N vòng, khẳng định: KHÔNG tích luỹ stack mồ côi, guard idempotent, fail-safe khi move hụt,
 * resize không bao giờ đụng task ≠ VD. Bọc SL1–SL5 trong docs/review/track1-feature-usecase-test-plan.md.
 */
class CastStressTest {

    private val silent: (String) -> Unit = {}
    private val N = 200

    private fun sinkStack(id: Int, task: Int, disp: Int) =
        FakeStack(id, disp, task, "com.byd.carplay.ui/com.byd.carplay.ui.VideoActivity")

    // SL1: đổi app qua lại liên tục — mỗi lần bê sink CP/AA cũ khỏi VD (giữ phiên) TRƯỚC khi đặt app mới.
    @Test
    fun `SL1 doi app lien tuc khong sinh mo coi`() {
        val dev = FakeDevice(vd = 1).add(FakeStack(3, 0, 5, "com.android.launcher3/.Launcher"))
        var nextId = 100
        repeat(N) { i ->
            // giả lập: 1 sink CP/AA vừa bị đặt lên VD (rung R1/R2 của lượt trước)
            dev.add(sinkStack(nextId, nextId + 1000, 1)); nextId++
            // đổi sang app mới → guard bê sink khỏi VD trước cmd16/teardown
            val ok = ClusterCast.guardSinksOffVd(fakeShell(dev), vd = 1, keepPkg = "vn.vietmap.live", log = silent)
            assertTrue(ok, "vòng $i: guard phải bê được sink")
            // sau mỗi lượt: VD KHÔNG còn sink chiếu-điện-thoại
            assertTrue(ClusterCast.phoneProjectionSinksOn(StackParse.parse(dev.amStackList()), 1).isEmpty(),
                "vòng $i: VD phải sạch sink sau guard")
        }
        assertEquals(N, dev.moveCount, "mỗi vòng bê đúng 1 sink → tổng $N")
        // không stack mồ côi: mọi sink đã về display 0
        assertTrue(dev.stacks.filter { it.comp.contains("carplay") }.all { it.displayId == 0 })
    }

    // SL3: guard gọi lặp trên state đã sạch → idempotent, không lệnh thừa.
    @Test
    fun `SL3 guard idempotent - goi lai khong be thua`() {
        val dev = FakeDevice(vd = 1).add(sinkStack(66, 71, 1))
        assertTrue(ClusterCast.guardSinksOffVd(fakeShell(dev), 1, "", silent))
        assertEquals(1, dev.moveCount)
        // gọi lại nhiều lần: sink đã ở display 0 → không bê nữa
        repeat(20) { assertTrue(ClusterCast.guardSinksOffVd(fakeShell(dev), 1, "", silent)) }
        assertEquals(1, dev.moveCount, "không phát sinh move thừa")
    }

    // SL5: move-stack fail ngẫu nhiên giữa stress → LUÔN fail-safe (không teardown), sink không bị bỏ nửa vời.
    @Test
    fun `SL5 move fail giua stress luon fail-safe`() {
        repeat(50) { i ->
            val dev = FakeDevice(vd = 1, moveFailStacks = mutableSetOf(66)).add(sinkStack(66, 71, 1))
            val ok = ClusterCast.guardSinksOffVd(fakeShell(dev), 1, "", silent)
            assertFalse(ok, "vòng $i: move hụt → fail-safe false")
            assertEquals(1, dev.stacks.first().displayId, "vòng $i: sink giữ nguyên trên VD, không nửa vời")
        }
    }

    // SL2: chỉnh kích thước LIÊN TỤC (nhiều %) — luôn đúng tier, không bao giờ đụng task ≠ VD.
    @Test
    fun `SL2 resize lien tuc luon dung VD khong dung man giua`() {
        val dev = FakeDevice(vd = 1, freeformAlive = true)
        val onVd = StackEntry(1, 1, "freeform", "standard", 71, "com.byd.carplay.ui/.VideoActivity", true)
        val onMain = StackEntry(2, 0, "fullscreen", "standard", 99, "vn.vietmap.live/.MainActivity", true)
        repeat(N) { i ->
            val pct = 50 + (i % 51)   // 50%..100% đổi liên tục
            val rw = 1920 * pct / 100; val rh = 720 * pct / 100
            val scale = AppScale(dpi = 300 + (i % 40), rectL = 0, rectT = 0, rectR = rw, rectB = rh)
            // task đúng VD → resize chạy
            val r1 = ClusterCast.applyBounds(fakeShell(dev), 1, onVd, scale, 1920, 720)
            assertFalse(r1.contains("BỎ QUA"), "vòng $i: task ở VD phải resize được")
            // task màn giữa → LUÔN bị từ chối (P0 guard)
            val before = dev.resizeCount
            val r2 = ClusterCast.applyBounds(fakeShell(dev), 1, onMain, scale, 1920, 720)
            assertTrue(r2.contains("BỎ QUA"), "vòng $i: task màn giữa phải bị từ chối")
            assertEquals(before, dev.resizeCount, "vòng $i: KHÔNG resize task màn giữa")
        }
    }

    // SL4: divergence (WM↔AM lệch = mồ côi) XEN GIỮA chuỗi đổi app → CHẶN đúng lúc orphan, MỞ LẠI khi sạch.
    //   Khoá đúng yêu cầu plan §STRESS SL4 ("chặn đúng lúc orphan, mở lại khi sạch"): con mắt thứ hai
    //   (divergenceOn) phải hoạt động ĐAN XEN với luồng đổi app, không phải chỉ ở trạng thái tĩnh (UC9).
    @Test
    fun `SL4 divergence xen giua stress - chan luc orphan mo lai khi sach`() {
        val dev = FakeDevice(vd = 1).add(FakeStack(3, 0, 5, "com.android.launcher3/.Launcher"))
        var nextId = 300
        var blocked = 0
        repeat(10) { i ->
            if (i == 5) {
                // ★ GIỮA stress: 1 sink hoá MỒ CÔI (WM thấy, `am stack list` không) → mọi thao tác cụm phải DỪNG.
                dev.add(FakeStack(9000, 1, 9001, "com.byd.carplay.ui/com.byd.carplay.ui.VideoActivity", amVisible = false))
                assertNotNull(ClusterCast.divergenceOn(fakeShell(dev), 1), "vòng $i: orphan xen giữa → PHẢI chặn")
                blocked++
                // mô phỏng tắt-mở máy dọn sạch mồ côi (AM↔WM khớp lại) → mở lại
                dev.stacks.removeAll { it.stackId == 9000 }
                assertNull(ClusterCast.divergenceOn(fakeShell(dev), 1), "vòng $i: sau khi sạch → MỞ LẠI")
            } else {
                // đổi app bình thường: cụm không lệch → không chặn, guard bê sink cũ khỏi VD (giữ phiên)
                dev.add(sinkStack(nextId, nextId + 1000, 1)); nextId++
                assertNull(ClusterCast.divergenceOn(fakeShell(dev), 1), "vòng $i: cụm sạch → KHÔNG được chặn")
                assertTrue(ClusterCast.guardSinksOffVd(fakeShell(dev), 1, "vn.vietmap.live", silent),
                    "vòng $i: guard bê được sink")
                assertTrue(ClusterCast.phoneProjectionSinksOn(StackParse.parse(dev.amStackList()), 1).isEmpty(),
                    "vòng $i: VD sạch sink sau guard")
            }
        }
        assertEquals(1, blocked, "đúng 1 lần chặn giữa chuỗi (khi có orphan), còn lại đều thông")
    }
}

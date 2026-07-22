package com.byd.clusternav.modules.navprobe

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Khoá bài học: rút gọn file máy dò KHÔNG được cắt đuôi.
 * App dẫn đường bật sau (Waze) nằm cuối file; cắt đuôi là mất đúng thứ cần so sánh.
 */
class NavProbeDigestTest {

    private fun block(pkg: String, i: Int) =
        "=== 10:00:0$i · $pkg #$i [ongoing]\n  android.title = 500 m\n  android.text = Nguyen Hue\n\n"

    @Test
    fun `giu duoc moi app ke ca app o cuoi file`() {
        val head = "####### header #######\nquyen: OK\n\n"
        val text = head +
            (1..500).joinToString("") { block("com.vietmap.app", it % 10) } +
            (1..3).joinToString("") { block("com.waze", it) }
        val out = NavProbe.digest(text, maxChars = 4000)

        assertTrue(out.contains("com.waze"), "phai con waze")
        assertTrue(out.contains("com.vietmap.app"), "phai con vietmap")
        assertTrue(out.contains("header"), "phai giu header")
        assertTrue(out.length <= 6000, "phai nam trong han")
        assertTrue(out.contains("bỏ bớt"), "phai bao da bo bot")
    }

    @Test
    fun `giu duoc ban ghi man hinh lan noti`() {
        val head = "# header\n\n"
        val text = head +
            "=== 10:00:01 · vn.vietmap.live [MÀN HÌNH]\n  TextView #dist text=\"250 m\" @0,0-100,50\n\n" +
            (1..200).joinToString("") { block("com.waze", it % 7) }
        val out = NavProbe.digest(text, maxChars = 3000)
        assertTrue(out.contains("MÀN HÌNH"), "ban ghi man hinh phai con")
        assertTrue(out.contains("250 m"), "chu doc duoc phai con")
        assertTrue(out.contains("com.waze"), "waze phai con")
    }

    @Test
    fun `file chua co ban ghi nao thi tra ve nguyen van`() {
        assertTrue(NavProbe.digest("# header\nquyen: CHUA BAT\n", 4000).contains("CHUA BAT"))
    }
}

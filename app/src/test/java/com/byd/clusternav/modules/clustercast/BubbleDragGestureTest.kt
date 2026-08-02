package com.byd.clusternav.modules.clustercast

import com.byd.clusternav.testsupport.SourceRoots
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Khoá lại lỗi thật: **nút nổi không kéo được nữa** kể từ commit 9d70f62 (2026-07-30).
 *
 * Bản đó gắn cử chỉ kéo lên đúng MỘT chỗ — khung `LinearLayout` bọc ngoài, `attachDrag(root, root,
 * layout, manager)` — trong khi đứa con duy nhất của khung (`circle`) rộng đúng bằng khung
 * (`dp(BUBBLE_SIZE_DP)` × `dp(BUBBLE_SIZE_DP)`, `setPadding(0,0,0,0)`) và có `setOnClickListener` nên cờ
 * `CLICKABLE` bật. Chuỗi điều phối chạm của AOSP (`android-10.0.0_r47`, đã đọc source) khiến listener
 * của khung KHÔNG BAO GIỜ chạy: view clickable trả `true` cho ACTION_DOWN trong `onTouchEvent`
 * (`View.java:14779`, `:14958`) ⇒ `dispatchTouchEvent` của nó trả `true` (`View.java:13430`) ⇒ khung ghi
 * nó thành touch target (`ViewGroup.java:2714-2715`) ⇒ khung chỉ tự xử lý khi `mFirstTouchTarget == null`
 * (`ViewGroup.java:2739-2742`). Nhánh ACTION_MOVE của khung thành code chết, và vì đứa con phủ 100% khung
 * nên không còn một pixel nào của khung để nắm. Trước 9d70f62 cử chỉ kéo được gắn thẳng lên chính các nút
 * bấm, tức CÙNG một view giữ cả hai listener — và nó chạy tốt ngoài xe.
 *
 * Vì sao lỗi này ship im lặng: tới 2026-07-31 KHÔNG có một test nào trong repo chạm tới cử chỉ kéo (grep
 * `attachDrag`/`clampX`/`setOnTouchListener` trong `app/src/test` + `core/src/test` ra rỗng). Compile
 * xanh, 299 test app xanh, mà nút thì đứng yên trên xe — đúng bài học CLAUDE.md §8 ("compile xanh không
 * có nghĩa là code chạy") và §10 ("mỗi lỗi hiện trường đã root-cause → một test hồi quy").
 *
 * Module `:app` không chạy Robolectric nên đây là test quét-source, theo đúng lệ của
 * `CastSurfaceCollisionTest`/`CastFieldParityTest`: nó khoá HÌNH DẠNG của lời giải (ai giữ listener nào,
 * phân xử chạm-hay-kéo bằng cái gì), không khoá hành vi runtime.
 */
class BubbleDragGestureTest {

    private fun source(relative: String): String {
        val direct = Paths.get("app/src/$relative")
        val nested = SourceRoots.path("src/$relative")
        return (if (Files.exists(direct)) direct else nested).toFile().readText()
    }

    /**
     * Chỉ phần MÃ, bỏ mọi dòng chú thích.
     *
     * Bắt buộc ở đây, không phải cho gọn: chính KDoc của bản vá có trích lại các hằng số cũ (`dp(72)`,
     * `dp(84)`, `dp(28)`) để giải thích vì sao chúng sai. Quét trên văn bản thô thì phép kiểm "không còn
     * số chép tay" sẽ bắt nhầm lời giải thích và fail vĩnh viễn.
     */
    private fun code(text: String): String = text.lineSequence()
        .filterNot { it.trimStart().let { line -> line.startsWith("//") || line.startsWith("*") || line.startsWith("/*") } }
        .joinToString("\n")

    private val bubble = code(source("main/java/com/byd/clusternav/modules/clustercast/FloatingBubbleService.kt"))

    private val drag = bubble.substringAfter("private fun attachDrag(").substringBefore("private fun measureBubble(")

    /**
     * Cử chỉ kéo phải nằm trên CHÍNH view nhận cú chạm.
     *
     * Đây là mệnh đề bị 9d70f62 phá. Phép kiểm đi theo đúng đường mà sự kiện chạm đi: view được
     * `manager.addView` là gốc, phép duyệt gắn listener kéo lên chính gốc đó RỒI đệ quy xuống mọi con,
     * nên bất kỳ view nào giữ `setOnClickListener` cũng nằm trong tập được gắn. Không giả định nút nổi có
     * đúng một con (bản 3 vùng sắp tới có nhiều hơn), cũng không giả định con đó vuông 56dp.
     */
    @Test
    fun `drag is attached to the very view that receives the tap, not to the parent`() {
        // Đúng cái view được đưa cho WindowManager là gốc của phép duyệt.
        assertTrue(bubble.contains("attachDragToEveryTouchSurface(root, root, layout, manager)"))
        assertTrue(bubble.contains("manager.addView(root, layout)"))
        assertTrue(
            bubble.indexOf("attachDragToEveryTouchSurface(root, root, layout, manager)")
                < bubble.indexOf("manager.addView(root, layout)"),
            "listener phải có TRƯỚC khi cửa sổ hiện ra, không thì cú kéo đầu tiên rơi vào khoảng trống",
        )

        // Phép duyệt chạm chính view đang xét rồi mới xuống con — bỏ dòng đầu là quay lại đúng lỗi cũ.
        val walker = bubble.substringAfter("private fun attachDragToEveryTouchSurface(")
            .substringBefore("private fun attachDrag(")
        assertTrue(walker.contains("attachDrag(view, root, layout, manager)"), "gốc cũng phải được gắn")
        assertTrue(walker.contains("if (view is ViewGroup)"))
        assertTrue(walker.contains("attachDragToEveryTouchSurface(view.getChildAt(index), root, layout, manager)"))

        // Chính lời gọi đã gây lỗi: gắn kéo lên khung bọc ngoài và CHỈ lên đó.
        assertFalse(
            bubble.contains("attachDrag(root, root, layout, manager)"),
            "9d70f62 gắn kéo lên khung bọc ngoài; đứa con clickable nuốt ACTION_DOWN nên listener của " +
                "khung không bao giờ chạy (ViewGroup.java:2739-2742) — nút nổi đứng yên",
        )

        // Toàn bộ hành động chạm của nút nổi nằm trên ba ô do `zoneView` dựng, và cả ba đều nằm trong
        // cây `root` mà phép duyệt đi qua ⇒ view nhận chạm cũng là view nhận kéo.
        //
        // v0.73 (nút nổi 3 ô, docs/specs/cast-one-mode-and-three-zone-bubble.html §R7) là phép thử đầu
        // tiên của lời giải này: layout đổi hẳn từ một vòng tròn sang ba ô, mà phần kéo không phải sửa
        // một dòng nào — đúng điều KDoc của `attachDragToEveryTouchSurface` hứa. Vẫn giữ "chỉ MỘT nơi
        // nhận chạm": ba ô dùng chung một hàm dựng, nên không ô nào mọc thêm một đường bắn click riêng.
        assertEquals(1, Regex("setOnClickListener").findAll(bubble).count(), "chỉ một nơi nhận chạm")
        val builder = bubble.substringAfter("private fun zoneView(")
            .substringBefore("private fun attachDragToEveryTouchSurface(")
        assertTrue(builder.contains("setOnClickListener"))
        val rootBlock = bubble.substringAfter("val root = LinearLayout(this).apply {").substringBefore("val type =")
        assertTrue(rootBlock.contains("addView(zoneFull)"))
        assertTrue(rootBlock.contains("addView(zoneHalves)"))
        // Hai ô nửa cụm nằm trong hàng ngang, hàng đó nằm trong `root` ⇒ vẫn thuộc cây được duyệt.
        val halves = bubble.substringAfter("val zoneHalves = LinearLayout(this).apply {")
            .substringBefore("val root = LinearLayout(this).apply {")
        assertTrue(halves.contains("addView(zoneView(BubbleZone.LEFT"))
        assertTrue(halves.contains("addView(zoneView(BubbleZone.RIGHT"))

        // Và chỉ có MỘT listener chạm trong cả file, sống trong hàm mà phép duyệt gọi.
        assertEquals(1, Regex("setOnTouchListener \\{").findAll(bubble).count())
        assertTrue(drag.contains("handle.setOnTouchListener {"))
    }

    /**
     * Một view, MỘT trọng tài: chạm và kéo được phân xử bằng ngưỡng trượt của hệ thống.
     *
     * Hai kết cục phải loại trừ nhau tuyệt đối. Cú chạm mà lỡ tính thành kéo thì nút không phản hồi; cú
     * kéo mà lỡ bắn thêm click thì "kéo nút cho đỡ vướng" hoá ra dừng chiếu / phát một lượt chiếu mới —
     * trên xe đang lăn bánh đó là kết cục nguy hiểm hơn nhiều. Cửa duy nhất giữ hai đường đó rời nhau:
     * listener nuốt trọn cử chỉ từ ACTION_DOWN (nên `View.onTouchEvent` không chạy lần nào —
     * `View.java:13424-13432` — và framework không còn đường tự bắn click ở ACTION_UP), rồi tự phát lại
     * cú chạm bằng `performClick()` khi và chỉ khi chưa từng vượt ngưỡng.
     */
    @Test
    fun `tap and drag are told apart by the system touch slop, and never both fire`() {
        // Ngưỡng lấy từ hệ thống, không phải số dp chép tay (`View.java:5059` dùng đúng nguồn này).
        assertTrue(drag.contains("val slop = ViewConfiguration.get(this).scaledTouchSlop"))
        assertFalse(drag.contains("dp(8)"), "ngưỡng trượt không được chép tay theo dp")
        assertTrue(drag.contains("if (!dragging && dx * dx + dy * dy <= slop * slop) return@setOnTouchListener true"))
        // Chỉ vượt ngưỡng mới thành cú kéo — không có đường nào bật `dragging` sớm hơn.
        assertEquals(1, Regex("dragging = true").findAll(drag).count())
        assertTrue(drag.indexOf("slop * slop") < drag.indexOf("dragging = true"))

        // ACTION_DOWN phải NUỐT cử chỉ; trả `false` là để `onTouchEvent` vào cuộc và bắn click lần hai.
        val down = drag.substringAfter("MotionEvent.ACTION_DOWN ->").substringBefore("MotionEvent.ACTION_MOVE ->")
        assertEquals(
            "true",
            down.lines().map(String::trim).last { it.isNotEmpty() && it != "}" },
            "ACTION_DOWN phải trả true, nếu không framework tự bắn click lúc nhả tay sau một cú kéo",
        )

        // Nhả tay: kéo thì ghi vị trí, chạm thì phát click — hai nhánh loại trừ nhau.
        val up = drag.substringAfter("MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->")
            .substringBefore("else -> false")
        val dragged = up.substringAfter("if (dragging) {").substringBefore("} else if")
        assertTrue(dragged.contains("catalog.setBubblePosition(x, y)"), "cú kéo phải nhớ chỗ vừa đặt")
        assertFalse(dragged.contains("performClick"), "cú kéo TUYỆT ĐỐI không được bắn click")
        assertTrue(up.contains("} else if (event.actionMasked == MotionEvent.ACTION_UP && touched.isClickable) {"))
        assertTrue(up.indexOf("if (dragging)") < up.indexOf("touched.performClick()"))
        assertEquals(1, Regex("performClick\\(\\)").findAll(bubble).count(), "chỉ một chỗ phát cú chạm")

        // ACTION_CANCEL không được tính là chạm (ngón tay bị hệ thống cướp mất, không phải người nhả tay).
        assertTrue(up.contains("event.actionMasked == MotionEvent.ACTION_UP"))

        // Ngón thứ hai không được làm trượt hết mọi nhánh (`action` mang chỉ số ngón ở byte cao).
        assertTrue(drag.contains("when (event.actionMasked) {"))
        assertFalse(drag.contains("when (event.action) {"))

        // Và không có cử chỉ ẩn nào được thêm vào để "bù" — dự án cố ý không có giữ-lâu/chạm-đúp.
        assertFalse(bubble.contains("setOnLongClickListener"))
        assertFalse(bubble.contains("GestureDetector"))
    }

    /**
     * Vị trí mọc và biên kẹp phải suy ra từ kích thước THẬT của nút nổi, không chép tay theo cạnh 56dp.
     *
     * `clampX`/`clampY` từng chép `dp(72)` cho cả hai chiều, còn chỗ mọc mặc định chép `dp(84)`/`dp(28)` —
     * cả ba đều là số cộng sẵn cho một nút vuông 56dp và không tham chiếu `BUBBLE_SIZE_DP`. Với nút 56dp
     * chúng thừa 16dp (nút không bao giờ chạm được mép phải); với một nút to hơn — chính là bản 3 vùng
     * đang tới — chúng THIẾU, nút kéo lọt ra ngoài mép và phần lọt ra không bấm lại được. Cả hai kiểu sai
     * đều im lặng: không crash, không log, không test nào đỏ.
     */
    @Test
    fun `spawn point and clamping are derived from the measured bubble, not from 56dp constants`() {
        assertTrue(bubble.contains("private fun bubbleWidthPx(view: View? = bubble): Int"))
        assertTrue(bubble.contains("private fun bubbleHeightPx(view: View? = bubble): Int"))
        // Đo trước khi tính chỗ mọc, vì view chưa gắn vào cửa sổ thì `width` còn bằng 0.
        assertTrue(bubble.contains("measureBubble(root)"))
        assertTrue(bubble.indexOf("measureBubble(root)") < bubble.indexOf("val layout = WindowManager.LayoutParams("))
        assertTrue(bubble.contains("view.measure(unspecified, unspecified)"))

        // Kẹp biên = "giữ trọn nút trong màn hình", tính từ bề rộng/bề cao đo được.
        assertTrue(bubble.contains("(width - bubbleWidthPx(view)).coerceAtLeast(0)"))
        assertTrue(bubble.contains("(height - bubbleHeightPx(view)).coerceAtLeast(0)"))
        // Chỗ mọc mặc định: sát mép phải trừ một lề CÓ TÊN, và căn giữa theo chiều dọc.
        assertTrue(bubble.contains("resources.displayMetrics.widthPixels - bubbleWidthPx(root) - dp(EDGE_MARGIN_DP)"))
        assertTrue(bubble.contains("(resources.displayMetrics.heightPixels - bubbleHeightPx(root)) / 2"))

        // Bánh răng: trong toàn bộ vùng tính vị trí/kích thước không được còn MỘT số dp chép tay nào.
        // Đây mới là thứ bắt được lỗi lần sau, vì ba assertion ở trên chỉ khoá đúng ba con số đã biết.
        val positioning = bubble.substringAfter("val saved = catalog.bubblePosition()").substringBefore("params = layout") +
            bubble.substringAfter("private fun bubbleWidthPx(").substringBefore("private fun projectState()")
        val handwritten = Regex("""dp\((\d+)\)""").findAll(positioning).map { it.value }.toList()
        assertTrue(
            handwritten.isEmpty(),
            "vị trí/kích thước nút nổi còn số chép tay $handwritten — nút to lên là chúng sai lặng lẽ",
        )
        // Cạnh mặc định vẫn là hằng số có tên duy nhất, dùng làm lưới an toàn khi chưa đo được.
        assertTrue(positioning.contains("dp(BUBBLE_SIZE_DP)"))
    }
}

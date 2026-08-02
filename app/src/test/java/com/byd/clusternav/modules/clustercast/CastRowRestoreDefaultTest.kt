package com.byd.clusternav.modules.clustercast

import com.byd.clusternav.testsupport.SourceRoots
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Khoá hành vi của nút "Khôi phục" (↺) thấy trên xe, dưới panel chỉnh khung mỗi app trong `CastAppRows`.
 *
 * `CastRowActions`/`CastAppRows` không kiểm được bằng cách dựng thật (không Robolectric trong module này —
 * `CastAppCatalog` cần `Context` thật, `Handler(Looper.getMainLooper())` ném exception trên JVM trần). Theo
 * đúng quy ước đã có của `CastAppManagerWiringTest`, test này soi SOURCE THẬT để khoá ba khẳng định:
 *
 * 1. "↺" reset về đúng `AppScale()` — cấu hình mặc định (dpi=200, rect AUTO = full cụm), không phải một
 *    giá trị lai tạm nào khác.
 * 2. "↺" đi qua ĐÚNG MỘT chuỗi `setScale`→`applySoon`→`applyGeometry` mà mọi nút chỉnh khác (%, Kích thước,
 *    Vị trí, DPI) đều dùng — không có nhánh rẽ riêng cho restore nên không thể có "silent partial state".
 * 3. Phạm vi thật của nút: CHỈ dpi+rect (size/position). Cluster style (cong/thẳng) là một cờ bền RIÊNG
 *    trong `CastAppCatalog` (`rectStyle` CSV), không nằm trong `AppScale` và không bị nút này đụng tới.
 *    Ghi rõ ranh giới này để không ai vô tình tưởng "Khôi phục" cũng trả style về mặc định.
 */
class CastRowRestoreDefaultTest {

    private val appRows = SourceRoots.text("src/main/java/com/byd/clusternav/modules/clustercast/CastAppRows.kt")
    private val rowActions = SourceRoots.text("src/main/java/com/byd/clusternav/modules/clustercast/CastRowActions.kt")
    private val controller = SourceRoots.text("src/main/java/com/byd/clusternav/modules/clustercast/MainActivityCastController.kt")
    private val appScale = SourceRoots.text("src/main/kotlin/com/byd/clusternav/modules/clustercast/AppScale.kt")
    private val catalog = SourceRoots.text("src/main/java/com/byd/clusternav/cast/platform/CastAppCatalog.kt")

    @Test
    fun `restore button resets to the bare AppScale default, same statement shape as every other row action`() {
        // Nút restore: đúng "↺" gọi setScale với AppScale() KHÔNG THAM SỐ (= mặc định biên dịch), rồi after().
        assertTrue(
            appRows.contains(
                """actions.setScale(packageName, AppScale())""",
            ),
            "không thấy đúng câu lệnh restore mong đợi trong CastAppRows.kt",
        )
    }

    @Test
    fun `AppScale default is full-cluster auto rect at dpi 200 — the value restore writes back`() {
        // Khoá đúng giá trị "mặc định" mà nút ↺ tạo ra, đọc thẳng từ khai báo data class (core, thuần Kotlin,
        // đã có AppScaleTest.kt kiểm sâu hơn — ở đây chỉ khoá con số mà UI phụ thuộc không lệch khỏi test đó).
        assertTrue(appScale.contains("val dpi: Int = 200"))
        assertTrue(appScale.contains("val rectL: Int = -1"))
        assertTrue(appScale.contains("val rectT: Int = -1"))
        assertTrue(appScale.contains("val rectR: Int = -1"))
        assertTrue(appScale.contains("val rectB: Int = -1"))
        // rectL/T/R/B < 0 bất kỳ cạnh nào -> isAuto -> boundsOn trả full VD [0,0,w,h] (xem AppScaleTest.kt).
        assertTrue(appScale.contains("val isAuto: Boolean get() = rectL < 0 || rectT < 0 || rectR < 0 || rectB < 0"))
    }

    @Test
    fun `after() is the one shared commit path for every adjustment button including restore`() {
        // after() là điểm hội tụ DUY NHẤT: mọi hành động chỉnh khung (kể cả restore) đều gọi applySoon qua
        // đây, không có đường tắt song song nào khác gọi applyGeometry.
        val afterBody = appRows.substringAfter("fun after() {").substringBefore("fun resize")
        assertTrue(afterBody.contains("actions.applySoon(packageName)"))
        assertEquals(1, Regex("actions\\.applySoon\\(").findAll(appRows).count(),
            "applySoon phải chỉ được gọi từ đúng một chỗ (after()) — nếu số này đổi, restore có thể đã có đường riêng")
        // Không có nhánh nào so sánh giá trị scale để bỏ qua apply (vd if (scale == AppScale())) — nghĩa là
        // restore không bao giờ bị "áp thầm lặng nửa vời" khác với mọi nút kia.
        assertFalse(appRows.contains("== AppScale()"))
        assertFalse(rowActions.contains("== AppScale()"))
    }

    @Test
    fun `setScale and applySoon carry any AppScale value, restore included, through one unconditional write-then-apply`() {
        assertTrue(
            rowActions.contains(
                """override fun setScale(packageName: String, scale: AppScale) {
        background { catalog.setScale(packageName, scale) }
    }""",
            ),
            "setScale phải là một câu lệnh ghi duy nhất, không rẽ nhánh theo giá trị scale",
        )
        val applySoonBody = rowActions.substringAfter("override fun applySoon(packageName: String) {")
        assertTrue(applySoonBody.contains("val scale = catalog.scaleOf(packageName)"))
        assertTrue(applySoonBody.contains("applyGeometry("))
        assertTrue(applySoonBody.contains("AcceptedGeometry("))
        // Guard sống ở stillAlive()/activeTarget() — GIỐNG HỆT cho mọi giá trị scale, không có điều kiện
        // riêng cho "đang là mặc định". Đây là lý do restore trên app CHƯA chiếu chỉ lưu, không phát lệnh —
        // đúng hành vi chung của applySoon, không phải một case đặc biệt của restore.
        assertTrue(applySoonBody.contains("if (activeTarget() != packageName) return@postDelayed"))
    }

    @Test
    fun `row action's applyGeometry callback is the exact same verified-commit entry point Stop and Retry use`() {
        // MainActivityCastController nối applyGeometry của rowActions vào ĐÚNG hàm private applyGeometry()
        // — nghĩa là restore ↺ đi qua facade.applyGeometry(...) (verified-commit) y hệt bất kỳ điều chỉnh
        // nào khác, không có đường tắt bỏ qua xác minh. Màn hộp thoại điều chỉnh riêng (CastAdjustmentDialog)
        // đã không còn được gọi từ đâu cả trước khi màn Cluster Cast riêng bị xoá — không có gì để giữ lại.
        assertTrue(controller.contains("applyGeometry = { geometry -> applyGeometry(geometry) },"))
        val privateApplyGeometry = controller.substringAfter("private fun applyGeometry(geometry: AcceptedGeometry")
            .substringBefore("private fun retryConnect")
        assertTrue(privateApplyGeometry.contains("facade.applyGeometry("))
    }

    @Test
    fun `restore never touches cluster style — style is a separate persisted flag this control does not reach`() {
        // Ghi rõ ranh giới THẬT: "Khôi phục" trong CastAppRows chỉ đụng AppScale (dpi + rect). Style
        // (cong/thẳng) sống trong CastAppCatalog qua CSV "rectStyle", đổi bằng setClusterStyle() ở MỘT nơi
        // khác hẳn (CastAppManagerDialog) — nút ↺ không gọi, và AppScale không có field style nào để reset.
        assertFalse(appRows.contains("setClusterStyle"), "restore panel không được đụng cluster style")
        assertFalse(appRows.contains("ClusterStyle"))
        assertFalse(rowActions.contains("setClusterStyle"))
        assertFalse(rowActions.contains("ClusterStyle"))
        assertFalse(appScale.contains("Style"), "AppScale không có field style để '↺' có thể reset")
        assertTrue(
            catalog.contains(
                "fun clusterStyle(packageName: String): ClusterStyle =\n        if (packageName in immutableCsv(\"rectStyle\")) ClusterStyle.RECT else ClusterStyle.CURVED",
            ),
            "xác nhận style là cờ bền riêng, mặc định CURVED, tách khỏi AppScale",
        )
    }
}

package com.byd.clusternav.modules.clustercast

import com.byd.clusternav.testsupport.SourceRoots
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Static wiring contract for the single-screen redesign (docs/specs/cast-simplified-active-app-toggle.html).
 *
 * 2026-07-29 review found `ClusterCastActivity.kt` unconditionally looked up `R.id.cast_app_manager`
 * in `onCreate`, but the `layout-w960dp` variant of its screen never declared that id -- a crash on any
 * head unit landing in that width bucket, with zero compile errors. `activity_cluster_cast.xml` no
 * longer exists (that whole screen moved into Home's right panel), but the SAME class of regression is
 * possible for `activity_main.xml` -- this test generalizes the check to every id
 * `MainActivityCastController` looks up unconditionally, across every `layout*` variant of `activity_main.xml`.
 */
class MainActivityCastControllerWiringTest {

    private fun sourcePath(relative: String): Path {
        val direct: Path = Paths.get("app/src/$relative")
        return if (Files.exists(direct)) direct else SourceRoots.path("src/$relative")
    }

    private fun source(relative: String): String = sourcePath(relative).toFile().readText()

    /** Chỉ phần mã, bỏ dòng chú thích — để assertion "không được chứa X" không bắt nhầm lời giải thích. */
    private fun code(text: String): String = text.lineSequence()
        .filterNot { it.trimStart().let { line -> line.startsWith("//") || line.startsWith("*") || line.startsWith("/*") } }
        .joinToString("\n")

    private val controller = source("main/java/com/byd/clusternav/modules/clustercast/MainActivityCastController.kt")
    private val homeActivity = source("main/java/com/byd/clusternav/MainActivity.kt")
    private val autoStart = source("main/java/com/byd/clusternav/modules/clustercast/CastActivityAutoStart.kt")

    @Test
    fun `every layout variant of Home declares every id Home looks up unconditionally`() {
        val resDir = sourcePath("main/res/layout/activity_main.xml").parent.parent.toFile()
        val variants = resDir.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("layout") }
            .map { java.io.File(it, "activity_main.xml") }
            .filter { it.exists() }
        assertTrue(variants.size >= 2, "expected several layout variants, found $variants")
        // `[a-z_0-9]` chứ không phải `[a-z_]`: một id có chữ số (`btn_2`) sẽ KHÔNG khớp regex hẹp hơn,
        // nên nó lặng lẽ rơi khỏi tập kiểm tra — đúng kiểu lỗ hổng mà test này sinh ra để bịt.
        val pattern = Regex("""findViewById(?:<[A-Za-z]+>)?\(R\.id\.([a-z_0-9]+)\)""")
        // Cả `MainActivity` lẫn controller đều tra cứu vô điều kiện trên CÙNG layout đó, nên cả hai
        // phải nằm trong phép kiểm; bản đầu chỉ quét controller và bỏ sót nửa số id của chính Home.
        val required = (pattern.findAll(controller) + pattern.findAll(homeActivity))
            .map { it.groupValues[1] }.toSet()
        assertTrue(required.isNotEmpty(), "expected unconditional findViewById lookups in the controller")
        assertTrue("cast_geometry_container" in required)
        assertTrue("cb_autostart" in required)
        assertTrue("txt_app_title" in required, "MainActivity's own lookups must be covered too")
        variants.forEach { variant ->
            val markup = variant.readText()
            required.forEach { id ->
                assertTrue(
                    markup.contains("@+id/$id\""),
                    "${variant.toPath().parent.fileName}/${variant.name} is missing @+id/$id, " +
                        "which Home looks up unconditionally",
                )
            }
        }
    }

    /**
     * `CastFacade.observe()` tự ghi "Đây là I/O: đừng gọi trên main thread", và façade tồn tại chính là
     * để chặn "UI chạm vào transport ngay trong đường vẽ màn hình". Nhánh `postUi` của controller chạy
     * trên luồng vẽ; với nhịp tick 1s của Home thì mỗi lời gọi quan sát ở đó là một lần chờ dumpsys mỗi
     * giây. Khoá lại: mọi lời gọi `facade.observedState()`/`facade.observe()` phải nằm NGOÀI `postUi`.
     */
    @Test
    fun `the controller never observes the vehicle from the UI thread`() {
        // Bỏ chú thích trước khi quét: vừa để dấu ngoặc trong câu giải thích không phá phép đếm khối,
        // vừa để một chú thích nhắc tên hàm cũ không bị tính là lời gọi.
        val body = code(controller)
        val postUiBlocks = Regex("""postUi\s*\{""").findAll(body).map { match ->
            var depth = 0
            var index = match.range.last
            val start = index
            while (index < body.length) {
                when (body[index]) {
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) return@map body.substring(start, index + 1) }
                }
                index++
            }
            error("unbalanced braces after postUi at ${match.range.first}")
        }.toList()
        assertTrue(postUiBlocks.isNotEmpty(), "expected postUi blocks in the controller")
        postUiBlocks.forEach { block ->
            assertTrue(
                !block.contains("facade.observedState(") && !block.contains("facade.observe("),
                "postUi block observes the vehicle on the UI thread:\n$block",
            )
        }
        // Ảnh chụp đến từ chính lần đọc nền, không phải một vòng quan sát thứ hai.
        assertTrue(controller.contains("observedTarget = result.activeTarget"))
        assertTrue(controller.contains("refreshGeometryPanel(observedTarget)"))
        // …và lần đọc nền đó cũng chỉ quan sát MỘT lần cho một lần vẽ (bỏ dòng chú thích khi đếm — nội
        // dung chú thích ở đây có nhắc tên hàm cũ để giải thích vì sao nó biến mất).
        val reader = code(source("main/java/com/byd/clusternav/modules/clustercast/CastActivityRefresh.kt"))
        assertTrue(reader.contains("facade.observeBoth()"))
        assertTrue(!reader.contains("facade.observedState("), "reader must not take a second sample")
    }

    /**
     * Khoá lỗi thật 2026-07-29: panel bị `removeAllViews()` + dựng lại ở MỌI lần refresh, tức mỗi giây
     * một lần (Home tick 1s). Một cú chạm 80–150 ms rơi trúng lúc thay panel thì ACTION_DOWN và
     * ACTION_UP không còn cùng một View và click không bao giờ nổ — trên xe là "bấm chỉnh khung mà lúc
     * ăn lúc không".
     */
    @Test
    fun `the geometry panel is rebuilt only when the casting app changes`() {
        assertTrue(controller.contains("if (geometryPanelRendered && renderedGeometryTarget == activeTarget) return"))
    }

    /**
     * Khoá lỗi thật 2026-07-29: `Spinner` của Android bắn `onItemSelected` một lần cho lựa chọn đặt
     * bằng code, nên ghi cấu hình vô điều kiện làm CHỈ MỞ APP đã tăng `AutomationConfig.revision` hai
     * bậc. `CastAutomationPolicy.claimAllowed` đòi `config.revision == request.configRevision`, nên
     * đúng lần khởi động xe đó yêu cầu tự-chiếu bị `CONFIG_CHANGED` — R7 chết lặng lẽ, mỗi lần mở app.
     */
    @Test
    fun `the auto-start checkbox never rewrites an identical automation config`() {
        // 2026-07-30 (vòng 3): hai cửa idempotent này chuyển vào `commitConfig`, nơi phép đọc-sửa-ghi
        // được tuần tự hoá bằng một khoá (làn `misc` là pool 2 luồng). Nội dung cửa không đổi, chỉ nhãn
        // `return@background` thành `return` vì nó không còn nằm trong lambda nữa.
        assertTrue(
            autoStart.contains(
                "if (current.defaultPackage == pkg && current.autoCastEnabled && current.consentCurrent) return",
            ),
        )
        assertTrue(autoStart.contains("if (!current.autoCastEnabled && current.consentVersion == null) return"))
    }

    /**
     * `CastManualIntent.run()` xếp hàng target khi có transaction đang chạy và ghi `pendingIntent` gốc
     * USER vào store bền; `CastSessionStore.initializeForBoot` CỐ Ý giữ nó qua mọi lần khởi động lại;
     * `CastAutomationSettings` từ chối ghi yêu cầu tự-chiếu khi còn pendingIntent gốc USER. Không ai rút
     * hàng đợi thì một cú chạm trúng lúc bận tắt vĩnh viễn tự-chiếu-khi-khởi-động-xe (R7).
     */
    @Test
    fun `a queued pending target is always drained, so boot automation is never fenced forever`() {
        assertTrue(controller.contains("private fun drainPendingTarget()"))
        assertTrue(controller.contains("facade.resumePendingIntent()"))
        assertTrue(controller.contains("after = { drainPendingTarget() }"))
        // và cả lúc mở app, cho trường hợp hàng đợi còn sót từ phiên/khởi động trước.
        val onCreate = controller.substring(controller.indexOf("fun onCreate()"), controller.indexOf("fun onResume()"))
        assertTrue(onCreate.contains("drainPendingTarget()"))
    }

    /**
     * Khoá lỗi thật 2026-07-29 (review vòng 2): `MainActivity` không khai `launchMode` ⇒ `standard`, nên
     * mở lại ClusterNav từ launcher khi task đã tồn tại CHỈ gọi `onResume` (với `standard` thì
     * `onNewIntent` cũng không chạy). Bản trước treo R6 ở `onCreate` sau một cờ một-lần
     * (`autoStartAttempted`), nên sau lần mở đầu tiên của một tiến trình thì tự-chiếu-khi-mở-app chết
     * hẳn — đúng lúc nó là đường lui duy nhất khi nút nổi không dò ra app đang mở.
     */
    @Test
    fun `auto-start on open runs from both onCreate and onResume, with no one-shot flag`() {
        val manifest = source("main/AndroidManifest.xml")
        val mainActivityBlock = manifest.substring(
            manifest.indexOf("android:name=\".MainActivity\""),
            manifest.indexOf("</activity>", manifest.indexOf("android:name=\".MainActivity\"")),
        )
        assertTrue(
            !mainActivityBlock.contains("launchMode"),
            "MainActivity vẫn ở launchMode mặc định (standard); nếu đổi thì phải xem lại lập luận onResume ở đây",
        )
        val body = code(controller)
        val onCreate = body.substring(body.indexOf("fun onCreate()"), body.indexOf("fun onResume()"))
        val onResume = body.substring(body.indexOf("fun onResume()"), body.indexOf("fun tick()"))
        assertTrue(onCreate.contains("autoStartOnOpen()"), "onCreate vẫn phải thử tự-chiếu")
        assertTrue(onResume.contains("autoStartOnOpen()"), "onResume PHẢI thử lại, xem KDoc của onResume")
        assertTrue(!body.contains("autoStartAttempted"), "cờ một-lần chặn vĩnh viễn mọi lần mở sau")
        // Chống TRÙNG chứ không chặn vĩnh viễn: chốt in-flight + cửa cuối trên luồng vẽ.
        assertTrue(body.contains("autoStartInFlight.compareAndSet(false, true)"))
        assertTrue(body.contains("postUi { if (!work.mutationSnapshot().pending) executeCast(target) }"))
        // …và ô tick phải thật sự tắt được nó (`revoking()` giữ lại defaultPackage).
        assertTrue(body.contains("autoStartTarget(config.defaultPackage, config.armable, facade.envelope())"))
    }

    /**
     * Khoá lỗi thật 2026-07-29 (review vòng 2): nút nổi quyết định Dừng-hay-Chiếu bằng `lastProjection`
     * — ảnh chụp chỉ được làm mới mỗi 15 giây — trong khi từ v0.72 cụm còn được đổi từ panel Home và từ
     * tự-chiếu-lúc-khởi-động. CLAUDE.md §5 nói thẳng: "Cấm quyết định bằng cờ RAM. Kiểm bằng sự thật.
     * Cờ chỉ để hiển thị." Hậu quả không phải một cú chạm phí: đang chiếu mà chạm nhầm thành một lượt
     * cold-cast mới lên cụm đồng hồ lúc xe đang lăn bánh.
     */
    @Test
    fun `the bubble tap decides from a fresh read, not from the 15s-old snapshot`() {
        val bubble = code(source("main/java/com/byd/clusternav/modules/clustercast/FloatingBubbleService.kt"))
        val tap = bubble.substring(
            bubble.indexOf("private fun onPrimaryTap()"),
            bubble.indexOf("private fun homePackage()"),
        )
        assertTrue(tap.contains("project(token)"), "cú chạm phải tự đọc lại sự thật")
        assertTrue(
            tap.indexOf("project(token)") < tap.indexOf("requestStopOnce()"),
            "phải đọc TRƯỚC rồi mới rẽ nhánh Dừng",
        )
        assertTrue(tap.contains("fresh?.projecting ?: (lastProjection?.projecting == true)"))
        // Chốt cục bộ phải tự hết hạn, không được khoá đường phục hồi (CLAUDE.md §3).
        assertTrue(bubble.contains("private fun stopAckPending(): Boolean"))
        assertTrue(bubble.contains("if (dispatchInFlight.get() || stopAckPending()) { toast(\"Đang xử lý…\"); return }"))
        // Lượt vẽ lại bị trùng nhịp phải chạy bù, không bị nuốt.
        assertTrue(bubble.contains("projectionPending.set(true)"))
        assertTrue(bubble.contains("if (projectionPending.compareAndSet(true, false))"))
        // Kết cục của lệnh chiếu phải nói ra, không nuốt.
        assertTrue(bubble.contains(".statusMessage()"))
    }

    @Test
    fun `the App Manager screen and dialog are gone, superseded by the inline panel`() {
        listOf(
            "main/java/com/byd/clusternav/modules/clustercast/ClusterCastActivity.kt",
            "main/java/com/byd/clusternav/modules/clustercast/CastAppManagerDialog.kt",
            "main/java/com/byd/clusternav/modules/clustercast/CastAppManagerBinding.kt",
            "main/java/com/byd/clusternav/modules/clustercast/CastAppListView.kt",
            "main/java/com/byd/clusternav/modules/clustercast/CastAdjustmentDialog.kt",
            "main/res/layout/activity_cluster_cast.xml",
        ).forEach { relative ->
            // sourcePath()/SourceRoots.path() THROW when a file is missing (their whole purpose is
            // finding an existing file across module roots) -- exactly the case being asserted here, so
            // check plain existence directly instead of routing through the throwing helper.
            assertTrue(
                !Files.exists(Paths.get("app/src/$relative")) && !Files.exists(Paths.get("src/$relative")),
                "$relative should have been deleted, not left as dead code",
            )
        }
    }

    /**
     * Ô tick + dropdown sống trong `CastActivityAutoStart.kt` (tách ra 2026-07-29 để controller ở dưới
     * ngưỡng 500 LOC — cùng lý do `CastRowActions` từng tách khỏi màn Cast cũ), và nó nhận thẳng
     * `runtime.automation` từ controller, không qua hộp thoại xác nhận nào.
     */
    @Test
    fun `auto-start checkbox and dropdown reach AutomationConfig directly, no confirmation dialog`() {
        assertTrue(controller.contains("CastAutoStartBinding("))
        assertTrue(controller.contains("runtime.automation"))
        assertTrue(autoStart.contains("automation.setDefault(pkg)"))
        assertTrue(autoStart.contains("automation.accept()"))
        assertTrue(autoStart.contains("automation.revoke()"))
        assertTrue(!autoStart.contains("AlertDialog"), "tick vào ô LÀ sự đồng ý — không hộp thoại riêng")
    }

    @Test
    fun `geometry panel is disabled with no active target and enabled for the active one`() {
        val rows = source("main/java/com/byd/clusternav/modules/clustercast/CastAppRows.kt")
        assertTrue(rows.contains("val enabled = packageName != null"))
        assertTrue(rows.contains("isEnabled = enabled"))
    }
}

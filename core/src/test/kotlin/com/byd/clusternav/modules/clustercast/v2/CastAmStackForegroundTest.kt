package com.byd.clusternav.modules.clustercast.v2

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Locks `CastAmStackSnapshot.foregroundPackage()`, added 2026-07-29 for the "tap the bubble, cast
 * whatever app is currently open" redesign (docs/specs/cast-simplified-active-app-toggle.html).
 *
 * The first two fixtures are REAL `am stack list` dumps, not synthetic text — this vehicle puts one
 * top-level `Stack` per launched app on the same display (not one stack with many tasks), and exactly
 * the truly-foregrounded one carries `visible=true`.
 */
class CastAmStackForegroundTest {

    private fun realDump(name: String): String {
        val roots = listOf(
            "docs/diagnostics/carlog-2026-07-23-trace/live",
            "../docs/diagnostics/carlog-2026-07-23-trace/live",
        ).map(Paths::get)
        val root = roots.firstOrNull(Files::exists) ?: error("không tìm thấy thư mục fixture; đã thử $roots")
        return root.resolve(name).toFile().readText()
    }

    @Test
    fun `real dump with the driver sitting at the home screen yields no foreground app once the launcher is excluded`() {
        val snapshot = (CastAmStackParser.parse(realDump("02-am-stack-list.txt")) as CastDumpParse.Known).value
        // Only the launcher (Stack id=0, taskId=48) reads visible=true in this real capture; every
        // other backgrounded app (diLinkAccount, maps, vietmap, clusternav, youtube music) reads false.
        assertEquals("com.android.launcher3", snapshot.foregroundPackage(0, emptySet()))
        assertNull(snapshot.foregroundPackage(0, excluded = setOf("com.android.launcher3")))
    }

    @Test
    fun `real dump where ClusterNav itself is the visible app is excluded so its own screen is never cast`() {
        // Captured this session: ClusterCastActivity was open (Stack id=12, visible=true); the launcher
        // stack (id=0) read visible=false at that moment.
        val dump = """
            Stack id=12 bounds=[0,0][1920,1080] displayId=0 userId=0
             configuration={1.0 100000010byd_theme452mcc1mnc [vi_VN] ldltr sw720dp w1280dp h604dp 240dpi lrg long land night finger -keyb/v/h -nav/h winConfig={ mBounds=Rect(0, 0 - 1920, 1080) mAppBounds=Rect(0, 0 - 1920, 990) mWindowingMode=fullscreen mDisplayWindowingMode=fullscreen mActivityType=standard mAlwaysOnTop=undefined mRotation=ROTATION_0} s.41}
              taskId=16: com.byd.clusternav/com.byd.clusternav.MainActivity bounds=[0,0][1920,1080] userId=0 visible=true topActivity=ComponentInfo{com.byd.clusternav/com.byd.clusternav.modules.clustercast.ClusterCastActivity}

            Stack id=0 bounds=[0,0][1920,1080] displayId=0 userId=0
             configuration={1.0 100000010byd_theme452mcc1mnc [vi_VN] ldltr sw720dp w1280dp h604dp 240dpi lrg long land night finger -keyb/v/h -nav/h winConfig={ mBounds=Rect(0, 0 - 1920, 1080) mAppBounds=Rect(0, 0 - 1920, 990) mWindowingMode=fullscreen mDisplayWindowingMode=fullscreen mActivityType=home mAlwaysOnTop=undefined mRotation=ROTATION_0} s.41}
              taskId=11: com.android.launcher3/com.android.launcher3.home.MainActivity bounds=[0,0][1920,1080] userId=0 visible=false topActivity=ComponentInfo{com.android.launcher3/com.android.launcher3.home.MainActivity}
        """.trimIndent()
        val snapshot = (CastAmStackParser.parse(dump) as CastDumpParse.Known).value
        assertNull(
            snapshot.foregroundPackage(0, excluded = setOf("com.byd.clusternav", "com.android.launcher3")),
            "the bubble's own host app must never be cast just because its own screen happens to be open",
        )
    }

    @Test
    fun `a genuine third-party app in the foreground is detected and its label is exact`() {
        // Realistic (hand-built to the exact real format above), not a literal capture: no available
        // dump happens to show a non-launcher, non-ClusterNav app as the visible stack. Format matches
        // the two real dumps above exactly.
        val dump = """
            Stack id=0 bounds=[0,0][1920,1080] displayId=0 userId=0
             configuration={1.0 100000010byd_theme452mcc1mnc [vi_VN] ldltr sw720dp w1280dp h604dp 240dpi lrg long land night finger -keyb/v/h -nav/h winConfig={ mBounds=Rect(0, 0 - 1920, 1080) mAppBounds=Rect(0, 0 - 1920, 990) mWindowingMode=fullscreen mDisplayWindowingMode=fullscreen mActivityType=home mAlwaysOnTop=undefined mRotation=ROTATION_0} s.41}
              taskId=11: com.android.launcher3/com.android.launcher3.home.MainActivity bounds=[0,0][1920,1080] userId=0 visible=false topActivity=ComponentInfo{com.android.launcher3/com.android.launcher3.home.MainActivity}

            Stack id=20 bounds=[0,0][1920,1080] displayId=0 userId=0
             configuration={1.0 100000010byd_theme452mcc1mnc [vi_VN] ldltr sw720dp w1280dp h604dp 240dpi lrg long land night finger -keyb/v/h -nav/h winConfig={ mBounds=Rect(0, 0 - 1920, 1080) mAppBounds=Rect(0, 0 - 1920, 990) mWindowingMode=fullscreen mDisplayWindowingMode=fullscreen mActivityType=standard mAlwaysOnTop=undefined mRotation=ROTATION_0} s.41}
              taskId=30: vn.vietmap.live/vn.vietmap.live.MainActivity bounds=[0,0][1920,1080] userId=0 visible=true topActivity=ComponentInfo{vn.vietmap.live/vn.vietmap.live.MainActivity}
        """.trimIndent()
        val snapshot = (CastAmStackParser.parse(dump) as CastDumpParse.Known).value
        assertEquals(
            "vn.vietmap.live",
            snapshot.foregroundPackage(0, excluded = setOf("com.byd.clusternav", "com.android.launcher3")),
        )
    }

    @Test
    fun `a different display never leaks into the foreground read for display 0`() {
        val dump = """
            Stack id=1 bounds=[0,0][1920,720] displayId=1 userId=0
             configuration={1.0 s.41}
              taskId=40: vn.vietmap.live/vn.vietmap.live.MainActivity bounds=[0,0][1920,720] userId=0 visible=true

            Stack id=0 bounds=[0,0][1920,1080] displayId=0 userId=0
             configuration={1.0 s.41}
              taskId=11: com.android.launcher3/com.android.launcher3.home.MainActivity bounds=[0,0][1920,1080] userId=0 visible=false
        """.trimIndent()
        val snapshot = (CastAmStackParser.parse(dump) as CastDumpParse.Known).value
        assertNull(snapshot.foregroundPackage(0, excluded = setOf("com.android.launcher3")))
        assertEquals("vn.vietmap.live", snapshot.foregroundPackage(1, excluded = emptySet()))
    }

    /**
     * 2026-07-29 senior review: bản đầu lấy `tasks.firstOrNull { … }`, tức khi có nhiều stack cùng
     * `visible=true` thì kết quả phụ thuộc THỨ TỰ liệt kê của `am stack list` — đúng cái mà KDoc của
     * chính hàm này (và §Open Questions của spec) nói là KHÔNG được dựa vào. Hậu quả của đoán sai không
     * phải một cú chạm phí: nó là app sai hiện trên cụm đồng hồ trong lúc xe đang chạy. R3 nói thẳng
     * "không đoán mò, không chiếu nhầm", nên mập mờ phải trả `null` để bề mặt báo ngắn và không phát lệnh.
     */
    @Test
    fun `two different visible apps on the same display are ambiguous, so nothing is guessed`() {
        val dump = """
            Stack id=20 bounds=[0,0][960,1080] displayId=0 userId=0
             configuration={1.0 s.41}
              taskId=30: vn.vietmap.live/vn.vietmap.live.MainActivity bounds=[0,0][960,1080] userId=0 visible=true

            Stack id=21 bounds=[960,0][1920,1080] displayId=0 userId=0
             configuration={1.0 s.41}
              taskId=31: anddea.youtube.music/com.google.android.apps.youtube.music.activities.MusicActivity bounds=[960,0][1920,1080] userId=0 visible=true
        """.trimIndent()
        val snapshot = (CastAmStackParser.parse(dump) as CastDumpParse.Known).value
        assertNull(snapshot.foregroundPackage(0, excluded = setOf("com.byd.clusternav")))
        // Thứ tự đảo lại vẫn phải ra cùng kết luận — không có "cái đầu tiên thắng".
        val reversed = CastAmStackSnapshot(snapshot.stacks.reversed(), snapshot.tasks.reversed())
        assertNull(reversed.foregroundPackage(0, excluded = setOf("com.byd.clusternav")))
    }

    /** Nhiều task CÙNG MỘT gói thì không mập mờ về "chiếu app nào" — vẫn phải giải ra được. */
    @Test
    fun `two visible tasks of the same package still resolve to that package`() {
        val dump = """
            Stack id=20 bounds=[0,0][960,1080] displayId=0 userId=0
             configuration={1.0 s.41}
              taskId=30: vn.vietmap.live/vn.vietmap.live.MainActivity bounds=[0,0][960,1080] userId=0 visible=true

            Stack id=21 bounds=[960,0][1920,1080] displayId=0 userId=0
             configuration={1.0 s.41}
              taskId=31: vn.vietmap.live/vn.vietmap.live.SearchActivity bounds=[960,0][1920,1080] userId=0 visible=true
        """.trimIndent()
        val snapshot = (CastAmStackParser.parse(dump) as CastDumpParse.Known).value
        assertEquals("vn.vietmap.live", snapshot.foregroundPackage(0, excluded = setOf("com.byd.clusternav")))
    }
}

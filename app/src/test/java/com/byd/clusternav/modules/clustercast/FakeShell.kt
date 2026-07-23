package com.byd.clusternav.modules.clustercast

/**
 * ★ HARNESS test off-device — mô phỏng `sh: (String)->String` (dadb shell) bằng một [FakeDevice] state model.
 *
 * Vì sao: không có emulator BYD DiLink (cụm XDJA/AutoContainer/HAL proprietary). Nhưng mọi logic quyết định
 * của ClusterCast đi qua đúng một seam `sh(cmd)` → render output `am stack list` / `dumpsys window displays` /
 * `dumpsys display` KHỚP ĐỊNH DẠNG Android 10, và MUTATE state khi nhận `am display move-stack` → chạy được
 * E2E flow + stress off-xe. Fixture định dạng nguyên văn (khớp StackParse/WmParse/DisplayParse regex).
 */

/** 1 stack trên FakeDevice. [amVisible]=false → chỉ WM thấy (mồ côi: WM có, AM không). */
data class FakeStack(
    val stackId: Int,
    var displayId: Int,
    val taskId: Int,
    val comp: String,                 // "pkg/.Cls"
    val mode: String = "fullscreen",  // fullscreen · freeform · pinned
    val activityType: String = "standard",  // standard · home
    val amVisible: Boolean = true,    // false = orphan (WM thấy, `am stack list` không liệt kê)
)

/**
 * Mô hình đầu xe giả. [vd]=display cụm; [freeformAlive]=`am task resize` có được chấp nhận không;
 * [moveFailStacks]=stackId sẽ bị `am display move-stack` từ chối (mô phỏng lỗi hiện trường).
 */
class FakeDevice(
    val vd: Int = 1,
    var freeformAlive: Boolean = false,
    var vdW: Int = 1920,
    var vdH: Int = 720,
    val moveFailStacks: MutableSet<Int> = mutableSetOf(),
) {
    val stacks = mutableListOf<FakeStack>()
    var moveCount = 0
    var resizeCount = 0
    var overscanCount = 0

    fun add(s: FakeStack) = apply { stacks.add(s) }

    /** Render `am stack list` — CHỈ các stack amVisible (mồ côi bị ẩn, đúng như AM thật). */
    fun amStackList(): String = buildString {
        for (s in stacks.filter { it.amVisible }) {
            appendLine("Stack id=${s.stackId} bounds=[0,0][1920,1080] displayId=${s.displayId} userId=0")
            appendLine("  configuration={1.0 winConfig={ mWindowingMode=${s.mode} mActivityType=${s.activityType}} s.1}")
            appendLine("  taskId=${s.taskId}: ${s.comp} bounds=[0,0][1920,1080] userId=0 visible=true")
        }
    }

    /** Render `dumpsys window displays` — MỌI stack (kể cả mồ côi), nhóm theo display, đúng vùng token WmParse cần. */
    fun windowDisplays(): String = buildString {
        for (d in stacks.map { it.displayId }.distinct().sorted()) {
            appendLine("  Display: mDisplayId=$d")
            appendLine("    Application tokens in top down Z order:")
            for (s in stacks.filter { it.displayId == d }) {
                appendLine("      mStackId=${s.stackId}")
                appendLine("        taskId=${s.taskId}")
                appendLine("          appTokens=[AppWindowToken{a1 token=Token{a2 ActivityRecord{a3 u0 ${s.comp} t${s.taskId}}}}]")
            }
            appendLine("    DockedStackDividerController")   // RE_TOKENS_END → đóng vùng token
        }
    }

    /** Render `dumpsys display` tối thiểu cho DisplayParse.realSize(vd). */
    fun dumpsysDisplay(): String = buildString {
        appendLine("Display Devices: size=2")
        appendLine("  mDisplayId=0")
        appendLine("""    mBaseDisplayInfo=DisplayInfo{"built-in", displayId 0, real 1920 x 1080, largest app 1920 x 1080, smallest app 1080 x 1080, density 240}""")
        appendLine("  mDisplayId=$vd")
        appendLine("""    mBaseDisplayInfo=DisplayInfo{"fission_bg_xdjaVirtualSurface", displayId $vd, real $vdW x $vdH, largest app $vdW x $vdW, smallest app $vdH x $vdH, density 320}""")
    }
}

/** sh giả: dispatch lệnh → đọc/mutate [dev]. Trả output khớp định dạng thật. */
fun fakeShell(dev: FakeDevice): (String) -> String = fun(cmd: String): String {
    val c = cmd.trim()
    return when {
        c.startsWith("am stack list") -> dev.amStackList()
        c.contains("dumpsys window displays") || c.startsWith("dumpsys window") -> dev.windowDisplays()
        c.startsWith("dumpsys display") -> dev.dumpsysDisplay()
        c.startsWith("am display move-stack") -> {
            val toks = c.split(Regex("\\s+"))
            val sid = toks.getOrNull(3)?.toIntOrNull()
            val target = toks.getOrNull(4)?.toIntOrNull()
            if (sid == null || target == null) "Error: bad move-stack"
            else if (sid in dev.moveFailStacks) "java.lang.SecurityException: move rejected for stack $sid"
            else {
                dev.moveCount++
                dev.stacks.filter { it.stackId == sid }.forEach { it.displayId = target }
                ""
            }
        }
        c.startsWith("am task resize") -> {
            dev.resizeCount++
            if (dev.freeformAlive) "" else "java.lang.IllegalArgumentException: Task ... not allowed"
        }
        c.startsWith("wm overscan") -> { dev.overscanCount++; "" }
        else -> ""
    }
}

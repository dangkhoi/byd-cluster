package com.byd.clusternav.modules.clustercast

/**
 * ĐỘNG TÁC SHELL DÙNG CHUNG cho đường chiếu — tách khỏi [ClusterCast] để file điều phối không phình quá ngưỡng
 * guardrail của repo, và để từng động tác đọc/test được độc lập.
 *
 * Mọi hàm ở đây nhận `sh` (chạy 1 lệnh shell qua dadb, trả stdout) + `log` (đẩy ra panel log của màn Chiếu) —
 * KHÔNG giữ state của phiên chiếu (state nằm ở [ClusterCast]).
 */
internal object CastShell {
    /**
     * Poll tới khi [pkg] thực sự nằm trên [vd] (LOẠI stack pinned = PIP), tối đa [timeoutMs]. Thay `sleep(900)` mù cũ:
     * app nặng (projection/video) khởi động chậm hơn 900ms → verdict sai → rơi xuống rung phá hoại một cách oan uổng.
     */
    fun landedOn(sh: (String) -> String, pkg: String, vd: Int, timeoutMs: Long = 2500, stepMs: Long = 250): StackEntry? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val ents = StackParse.parse(sh("am stack list"))
            StackParse.of(ents, pkg).firstOrNull { it.displayId == vd && !it.isPinned }?.let { return it }
            if (System.currentTimeMillis() >= deadline) return null
            Thread.sleep(stepMs)
        }
    }

    /** `am display move-stack` bị từ chối? (display/stack không tồn tại, đã ở đó, thiếu quyền…) */
    fun moveRejected(o: String) =
        o.contains("Exception", true) || o.contains("Error", true) || o.contains("does not exist", true)

    /** In TỪNG DÒNG output shell (thay `take(60)` cũ — nó luôn cắt đúng chỗ "Warning: Activity not started…"). */
    fun logLines(out: String, log: (String) -> Unit) =
        out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(6).forEach { log("      $it") }

    /**
     * Ghi 3 setting freeform 1 LẦN mỗi phiên app (không phải mỗi lần chiếu). Chúng CHỈ được framework đọc lúc BOOT
     * (`ATMS.retrieveSettings`, không có ContentObserver) → ghi runtime chỉ có nghĩa cho lần khởi động SAU.
     * ★ v0.36 ĐỔI 0 → 1 (bản cũ ghi 0 MỖI lần chiếu). Cần =1 vì HAI lý do, đều verify trên source AOSP 10:
     *   (a) `ATMS.retrieveSettings` chỉ GÁN `mSupportsFreeformWindowManagement` BÊN TRONG nhánh
     *       `(supportsMultiWindow || forceResizable)`. OEM đặt `config_supportsMultiWindow=false` (hoặc
     *       `ro.config.low_ram=true`) là vế trái tắt → `enable_freeform_support` một mình bị VỨT ĐI.
     *   (b) `ActivityDisplay.validateWindowingMode` sẽ downgrade FREEFORM cho app khai
     *       `resizeableActivity="false"`, vì nó gate trên `TaskRecord.isResizeable()` =
     *       `mForceResizableActivities || isResizeableMode(...) || mSupportsPictureInPicture`.
     *   ⚠ KHÔNG hoàn tác được từ trong app (đảo về 0 sẽ phá freeform ở máy đã seed + power-cycle rồi).
     *     Muốn gỡ tay: `adb shell settings delete global force_resizable_activities` rồi tắt-mở máy xe.
     */
    /** Khoá prefs đánh dấu ĐÃ ghi cờ freeform ra Settings.Global — ghi TRƯỚC khi đổi, để lần chạy sau còn biết đường gỡ. */
    const val PREF_FREEFORM = "clusternav_state"
    /** 0 = chưa seed · 1 = đã seed · 2 = NGƯỜI DÙNG ĐÃ GỠ (không được tự bật lại). */
    const val K_FREEFORM_STATE = "freeform_state"
    const val FF_NONE = 0
    const val FF_SEEDED = 1
    const val FF_USER_REMOVED = 2

    private val FREEFORM_KEYS = listOf("enable_freeform_support", "force_resizable_activities")

    @Volatile private var freeformSeeded = false

    /**
     * Ghi hai cờ freeform vào `Settings.Global`.
     *
     * ⚠ ĐÂY LÀ STATE SỐNG NGOÀI TIẾN TRÌNH (§5): `Settings.Global` sống qua reboot, qua gỡ app, qua xoá data.
     * `ActivityTaskManagerService.retrieveSettings` đọc hai khoá này ĐÚNG MỘT LẦN lúc boot (không có
     * ContentObserver) ⇒ **lần tắt-mở máy sau đó không chữa bệnh, nó KÍCH HOẠT hiệu lực**: trước power-cycle mọi
     * yêu cầu freeform rơi xuống display 0 bị hạ cấp im lặng (vô hại); sau đó đúng lệnh ấy tạo cửa sổ nổi THẬT
     * trên màn hình giữa của tài xế. Đó chính là lỗi hiện trường 22/07 ("Vietmap bị scale ở màn chính, khởi động
     * lại vẫn bị").
     *
     * Vì thế: MARKER được ghi vào prefs (commit, đồng bộ) TRƯỚC khi chạm Settings.Global — để dù tiến trình
     * chết ngay sau đó, lần khởi động sau vẫn biết mình đã bật và còn đường [unseedFreeform] để gỡ.
     */
    fun ensureFreeformSeed(ctx: android.content.Context, sh: (String) -> String, log: (String) -> Unit) {
        if (freeformSeeded) return
        // ★ ĐỌC MARKER BỀN TRƯỚC, không phải cờ RAM. Bản v0.50 chỉ gate bằng cờ RAM nên nút "GỠ CHẾ ĐỘ CỬA SỔ
        //   NỔI" bị chính lần CHIẾU kế tiếp ghi lại — người dùng bấm gỡ, chiếu thêm một lần trước khi tắt máy
        //   (rất dễ, cùng một màn), thế là công cốc và họ kết luận "nút gỡ không ăn".
        if (freeformState(ctx) == FF_USER_REMOVED) {
            log("  ⚙ bỏ qua cờ freeform — người dùng đã chủ động gỡ. Chỉnh kích thước sẽ dùng wm size/overscan.")
            return
        }
        // ★ marker TRƯỚC khi đổi — §5. commit() chứ không apply(): apply() ghi nền, chết trước khi flush là mất marker.
        ctx.applicationContext.getSharedPreferences(PREF_FREEFORM, android.content.Context.MODE_PRIVATE)
            .edit().putInt(K_FREEFORM_STATE, FF_SEEDED).commit()
        freeformSeeded = true
        // ★ W2-8(a): BỎ `development_enable_freeform_windows_support` — nó KHÔNG PHẢI khoá của framework.
        //   AOSP 10 Settings.java ánh xạ hằng DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT về đúng chuỗi
        //   "enable_freeform_support"; ghi thêm tên kia chỉ làm bẩn bảng settings, không ai đọc.
        FREEFORM_KEYS.forEach { sh("settings put global $it 1") }
        log("  ⚙ đã ghi cờ freeform (có hiệu lực sau khi TẮT MÁY XE hẳn 1 lần rồi mở lại)")
    }

    /** Trạng thái cờ freeform: [FF_NONE] / [FF_SEEDED] / [FF_USER_REMOVED]. Marker BỀN, không phải cờ RAM. */
    fun freeformState(ctx: android.content.Context): Int =
        ctx.applicationContext.getSharedPreferences(PREF_FREEFORM, android.content.Context.MODE_PRIVATE)
            .getInt(K_FREEFORM_STATE, FF_NONE)

    fun freeformSeedMarked(ctx: android.content.Context): Boolean = freeformState(ctx) == FF_SEEDED

    /**
     * GỠ hai cờ freeform. Đường trả lại mà §5 bắt buộc phải có — và nó chạy được cả khi tiến trình lần trước đã chết,
     * vì marker nằm trong prefs chứ không trong RAM.
     *
     * Đánh đổi phải nói rõ với người dùng: gỡ xong thì tầng 1 (`am task resize`, chỉnh khung mượt trên cụm) hết
     * tác dụng, việc chỉnh kích thước tụt xuống `wm size`/`wm overscan` — hai đường vốn vẫn chạy tốt cho Vietmap
     * và CarPlay. Đổi lại: không còn cửa sổ nổi kẹt trên màn hình giữa của tài xế.
     * Chỉ có hiệu lực sau khi TẮT MÁY XE hẳn một lần.
     */
    fun unseedFreeform(ctx: android.content.Context, sh: (String) -> String, log: (String) -> Unit) {
        FREEFORM_KEYS.forEach { sh("settings delete global $it") }
        ctx.applicationContext.getSharedPreferences(PREF_FREEFORM, android.content.Context.MODE_PRIVATE)
            .edit().putInt(K_FREEFORM_STATE, FF_USER_REMOVED).commit()
        freeformSeeded = false
        log("  ⚙ đã GỠ cờ freeform — cần TẮT MÁY XE hẳn 1 lần rồi mở lại mới có hiệu lực")
    }

    /** freeform đã sống chưa? Probe rẻ + KHÔNG phá: resize task về ĐÚNG bounds hiện có → thành công = freeform sống. */
    fun freeformAlive(sh: (String) -> String, e: StackEntry, vd: Int): Boolean {
        if (e.displayId != vd) return false
        val (w, h) = DisplayParse.realSize(sh("dumpsys display"), vd)
        return !resizeRejected(sh("am task resize ${e.taskId} 0 0 $w $h 2>&1"))
    }

    /**
     * ★ SỬA MÀN GIỮA sau khi chiếu hụt — bê mọi stack của [pkg] còn trên VD về display 0 rồi ÉP FULLSCREEN.
     * Cần thiết vì: sau power-cycle (freeform sống), một stack đã bị set freeform mà quay về display 0 sẽ Ở LẠI
     * dạng CỬA SỔ NỔI trên màn hình giữa của tài xế — trước v0.36 không có đường nào đưa nó về bình thường.
     * `am start --windowingMode 1` (WINDOWING_MODE_FULLSCREEN) là verb shell duy nhất đổi được windowing-mode
     * của task đang chạy trên A10.
     */
    fun restoreFullscreenOnMain(adb: dadb.Dadb, sh: (String) -> String, pkg: String, vd: Int, log: (String) -> Unit) {
        runCatching {
            var ents = StackParse.parse(sh("am stack list"))
            // ★ v0.50 PHẠM VI TƯỜNG MINH (§4). Bản cũ lọc mù `displayId >= 1`. Trên xe có Dudu launcher,
            //   display 1 là `launcher-split` RIÊNG (FLAG_PRIVATE) của nó — quét mù sẽ giật app ra khỏi khung
            //   chia đôi của launcher, đúng mẫu lỗi từng làm đơ launcher. Nay chỉ đụng display MANG TÊN cụm,
            //   và lấy cả TẬP (opcode 16 tái tạo VD → id mới, id cũ có thể còn stack bám).
            val clusterIds = WmParse.clusterDisplayIds(sh("dumpsys display")).let { m ->
                if (vd >= 1) m + vd else m          // vd đang dùng luôn được tính, kể cả khi dump hụt
            }
            if (clusterIds.isEmpty()) log("  ⚠ không xác định được display của cụm — KHÔNG bê stack nào (thà không làm gì)")
            // ★ §4 câu 3 — LOẠI stack. v0.50 mới trả lời được câu 1 (đúng display) mà bỏ câu này: bê cả stack
            //   `pinned`/`home` là đúng lớp lệnh đã từng làm đơ Dudu launcher. Stack khác chỉ log, để VD tự trả
            //   nội dung khi teardown (removeMode 0).
            val skipped = StackParse.of(ents, pkg).filter { it.displayId in clusterIds && !(it.isStandard && !it.isPinned) }
            if (skipped.isNotEmpty()) log("  ⏭ giữ nguyên ${skipped.size} stack home/PIP trên cụm (đụng vào là hỏng launcher)")
            StackParse.of(ents, pkg).filter { it.displayId in clusterIds && it.isStandard && !it.isPinned }
                .map { it.stackId }.distinct().forEach {
                log("  ↩ bê stack $it của $pkg về màn giữa"); sh("am display move-stack $it 0 2>&1"); Thread.sleep(400)
            }
            ents = StackParse.parse(sh("am stack list"))
            // freeform HOẶC pinned đều là "cửa sổ nổi" trên màn giữa của tài xế → ép về fullscreen.
            // ★ v0.42: xử MỌI stack kẹt của app (bản cũ chỉ lấy firstOrNull) và KIỂM LẠI sau khi ép.
            val stuck = StackParse.of(ents, pkg).filter { it.displayId == 0 && (it.isFreeform || it.isPinned) }
            if (stuck.isNotEmpty()) {
                val comp = resolveComp(adb, pkg)
                log("  ↩ $pkg còn ${stuck.size} cửa sổ NỔI trên màn giữa → ép fullscreen")
                if (comp != null) {
                    // -f 0x20000000 = FLAG_ACTIVITY_SINGLE_TOP: lệnh dọn này chạy trên app ĐANG chạy, không cần
                    // instance mới. Thiếu cờ thì AOSP đi nhánh mAddingToTask=true → thêm một activity vào cùng task.
                    // KHÔNG thêm cờ này cho rung R3 (nó dùng --activity-clear-task và cố ý muốn khởi động lạnh).
                    sh("am start -f 0x20000000 --windowingMode 1 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $comp 2>&1")
                    Thread.sleep(400)
                    val left = StackParse.of(StackParse.parse(sh("am stack list")), pkg)
                        .count { it.displayId == 0 && (it.isFreeform || it.isPinned) }
                    if (left > 0) log("  ⚠ vẫn còn $left cửa sổ nổi — mở app từ launcher 1 lần là hết")
                }
            }
            if (vd >= 1) resetDisplayAll(sh, vd)
        }.onFailure { log("  ⚠ dọn màn giữa lỗi: ${it.message}") }
    }

    // ── CHẶN PIP (picture-in-picture) ──
    /**
     * appops PICTURE_IN_PICTURE — enforce trong system_server (`ActivityRecord.checkEnterPictureInPictureAppOpsState`)
     * nên bản YouTube mod cũng không lách được. Bị chặn thì `enterPictureInPictureMode()` trả **false**, KHÔNG ném,
     * KHÔNG crash — app đơn giản ở lại fullscreen. uid-2000 (shell) có MANAGE_APP_OPS_MODES nên set được.
     */
    fun pipCmd(pkg: String, mode: String) = "cmd appops set --user 0 $pkg PICTURE_IN_PICTURE $mode 2>&1"
    /** component launcher "pkg/cls" của [pkg] (dòng cuối có dấu '/'), null nếu app không có launcher activity. */
    fun resolveComp(adb: dadb.Dadb, pkg: String): String? =
        adb.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $pkg")
            .output.lines().map { it.trim() }.lastOrNull { it.contains("/") && !it.contains(" ") }

    /** `am task resize` bị framework từ chối? (task fullscreen trên A10 → IllegalArgumentException "not allowed"). */
    fun resizeRejected(o: String) =
        o.contains("Error", true) || o.contains("Exception", true) ||
        o.contains("not allowed", true) || o.contains("must be", true)

    /**
     * ★ TẦNG 2 — `wm size`: đổi THẬT logical size của VD. Đây là tầng DUY NHẤT đổi được kích thước cho app
     * chiếu điện thoại khi freeform chưa sống.
     * GỐC RỄ (verify AOSP 10): `wm overscan` KHÔNG đổi khung cửa sổ — `DisplayFrames.onBeginLayout` giữ
     * `mOverscan`/`mRestrictedOverscan` bằng NGUYÊN kích thước display, chỉ `mUnrestricted/mContent/mStable` bị co;
     * mà `DisplayInfo.appWidth/appHeight` (→ Configuration của app) tính từ `getNonDecorDisplayWidth/Height`, vốn
     * chỉ trừ thanh hệ thống chứ không trừ overscan. Nên overscan chỉ là *content inset*: app nào bỏ qua inset
     * (SurfaceView/video/immersive — đúng kiểu Android Auto) thì KHÔNG hề nhỏ đi.
     * `wm size` thì đổi `logicalWidth/Height` → `appWidth/appHeight` → Configuration mới → app BUỘC phải vẽ lại.
     * ⚠ Đánh đổi: LogicalDisplay letterbox CĂN GIỮA và giữ tỉ lệ → mất khả năng đặt khung lệch tâm.
     * ⚠ WM LƯU BỀN cái này vào /data/system/display_settings.xml theo uniqueId của VD → SỐNG QUA CẢ REBOOT.
     *   Mọi đường teardown/reconcile BẮT BUỘC gọi [resetDisplayAll].
     * @return mô tả nếu ăn thật (đã đọc lại `cur=WxH` để xác nhận), null nếu không → phía gọi rơi xuống overscan.
     */
    fun forceDisplaySize(sh: (String) -> String, vd: Int, wh: IntArray, dpi: Int, userDpi: Int, restoreOi: IntArray): String? {
        if (vd < 1 || wh[0] <= 0 || wh[1] <= 0) return null
        sh("wm overscan reset -d $vd")                      // overscan + wm size chồng nhau = co hai lần
        sh("wm size ${wh[0]}x${wh[1]} -d $vd")
        sh("wm density $dpi -d $vd")                        // dpi BÙ cho phần LogicalDisplay phóng, khác dpi user chọn
        val dump = sh("dumpsys window displays")
        val cur = DisplayParse.logicalSize(dump, vd)
        if (cur != null && cur.first == wh[0] && cur.second == wh[1]) {
            val eff = DisplayParse.density(dump, vd)?.first ?: dpi
            return "wm size ${wh[0]}x${wh[1]} · dpi thực $eff (bạn chọn $userDpi, đã bù cho phần phóng) — căn giữa"
        }
        // ★ không ăn → hoàn tác SẠCH: trả size, dpi user chọn, VÀ overscan đã gỡ ở đầu hàm.
        //   Thiếu bước trả overscan thì applyBounds sẽ báo "đã áp overscan […]" cho một VD trống trơn.
        sh("wm size reset -d $vd")
        sh("wm density $userDpi -d $vd")
        if (hasOverscan(sh)) sh("wm overscan ${restoreOi[0]},${restoreOi[1]},${restoreOi[2]},${restoreOi[3]} -d $vd")
        return null
    }

    /**
     * ★ TẦNG OVERSCAN CÓ KIỂM CHỨNG — đường ĐÃ CHẠY TỐT trên xe cho app thường (CarPlay, Vietmap, 21/07).
     * Ưu điểm so với [forceDisplaySize]: giữ được khung LỆCH TÂM, và không đụng vào logical size của VD.
     * Nhược điểm: app nào phớt lờ content inset (Android Auto) thì KHÔNG nhỏ đi — nên phải đọc lại khung
     * cửa sổ thật để biết có ăn không, rồi mới quyết định có leo lên `wm size` hay không.
     * @return mô tả nếu app CÓ co lại thật; null nếu app phớt lờ (phía gọi leo tầng).
     */
    /**
     * ★★ W2-4/C-4: `wm overscan` ĐÃ BỊ GỠ khỏi Android 11 trở lên. Trên DiLink5 (Android 12) gọi nó là lỗi cứng.
     * Chừng nào parser còn chết trên DL5 thì DL5 "hỏng an toàn" — cast tự huỷ trước khi đụng tới cụm. Sửa parser
     * mà KHÔNG có cổng này là biến nó thành "hỏng nguy hiểm": cast chạy tiếp, tầng overscan (tầng duy nhất ăn
     * ngoài hiện trường) hỏng, và cụm bị đổi mà không ai trả lại được.
     * Dò MỘT LẦN bằng chính shell, không đoán theo Build.VERSION của MÁY CHẠY APP (app và shell cùng máy, nhưng
     * để nhất quán với mọi thứ khác trong file này: đo, đừng đoán).
     */
    @Volatile private var overscanSupported: Boolean? = null
    fun hasOverscan(sh: (String) -> String): Boolean {
        overscanSupported?.let { return it }
        val out = sh("wm overscan 2>&1")
        val ok = !out.contains("Unknown command", true) && !out.contains("unknown option", true)
        overscanSupported = ok
        return ok
    }

    fun overscanVerified(sh: (String) -> String, vd: Int, pkg: String, oi: IntArray, w: Int, h: Int): String? {
        if (!hasOverscan(sh)) return null       // A11+ đã gỡ lệnh này → để tầng sau (wm size) lo
        sh("wm size reset -d $vd")                                   // gỡ wm size lần trước, tránh co hai lần
        sh("wm overscan ${oi[0]},${oi[1]},${oi[2]},${oi[3]} -d $vd")
        if (oi.all { it == 0 }) return "overscan [0,0,0,0] (full cụm)"
        val f = appWindowFrameOf(sh, pkg) ?: return "overscan [${oi.joinToString(",")}] (không đọc được khung app)"
        val ignored = f[0] <= 1 && f[1] <= 1 && f[2] >= w - 1 && f[3] >= h - 1
        return if (ignored) null                                     // khung vẫn full → app phớt lờ inset
        else "overscan [${oi.joinToString(",")}] (khung app ${f[2] - f[0]}×${f[3] - f[1]})"
    }

    private fun appWindowFrameOf(sh: (String) -> String, pkg: String): IntArray? =
        DisplayParse.appWindowFrame(sh("dumpsys window windows"), pkg)

    /**
     * Gỡ ÉP HÌNH HỌC trên VD (size + overscan) nhưng **GIỮ NGUYÊN density**.
     * ★ v0.38 SỬA LỖI TỰ GÂY: bản trước gộp cả `wm density reset` vào đây, mà applyBounds tầng 1 lại gọi hàm này
     *   NGAY SAU khi applyScaleLive vừa ghi DPI của user → lệnh sau xoá lệnh trước, nút DPI thành vô dụng.
     *   DPI là thứ NGƯỜI DÙNG chọn, chỉ được xoá khi teardown thật ([resetDisplayAll]).
     */
    fun resetDisplayGeometry(sh: (String) -> String, vd: Int) {
        if (vd < 1) return
        sh("wm size reset -d $vd")
        if (hasOverscan(sh)) sh("wm overscan reset -d $vd")     // A11+ đã gỡ lệnh này
    }

    /** Trả VD về gốc HOÀN TOÀN (kể cả density). Chỉ dùng ở teardown/reconcile — `wm size` sống qua reboot. */
    fun resetDisplayAll(sh: (String) -> String, vd: Int) {
        if (vd < 1) return
        sh("wm size reset -d $vd"); sh("wm density reset -d $vd")
        if (hasOverscan(sh)) sh("wm overscan reset -d $vd")     // A11+ đã gỡ lệnh này
    }

    /**
     * ★ CỤM CHỈ ĐƯỢC CÓ ĐÚNG 1 APP (sửa "chuyển app không clear"): bê MỌI stack KHÔNG phải [keepPkg] khỏi VD
     * về màn giữa rồi ép fullscreen. KHÔNG force-stop — có thể là phiên dẫn đường người dùng đang cần.
     */
    fun evictVd(adb: dadb.Dadb, sh: (String) -> String, vd: Int, keepPkg: String, log: (String) -> Unit) {
        if (vd < 1) return
        val victims = StackParse.evictableOnVd(StackParse.parse(sh("am stack list")), vd)
            .filter { it.pkg != keepPkg }
        if (victims.isEmpty()) return
        for (v in victims.distinctBy { it.stackId }) {
            log("  ⇤ dọn khỏi cụm: ${v.brief()}")
            sh("am display move-stack ${v.stackId} 0 2>&1"); Thread.sleep(300)
        }
        victims.map { it.pkg }.distinct().forEach { restoreFullscreenOnMain(adb, sh, it, -1, log) }
    }
}

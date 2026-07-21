package com.byd.clusternav.modules.clustercast

import android.content.Context
import android.content.Intent
import com.byd.clusternav.AdbKeys
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CHIẾU APP BẤT KỲ LÊN CLUSTER qua embedded-dadb (uid-2000 shell, localhost:5555).
 *
 * ★★★ TỔNG QUÁT HOÁ: cơ chế chiếu VỐN app-agnostic → BẤT KỲ app nào cũng bê lên cụm được. User curate danh sách
 * app ([castableApps]) trong Cài đặt chiếu; nút nổi long-press hiện menu, chọn 1 để chiếu; chạm = toggle.
 * ⚠ Cụm KHÔNG có cảm ứng (VD touch NONE) → chỉ hợp app ĐỂ NGÓ (nav/nhạc/dashboard/video). App cần set-up (Maps:
 * chọn tuyến) phải mở + set-up ở màn giữa TRƯỚC rồi mới chiếu (T1 GIỮ nguyên phiên dẫn).
 *
 * ĐÃ KIỂM CHỨNG TRÊN XE:
 *  1. VD cluster (`fission_bg_xdjaVirtualSurface`) tồn tại sẵn; đổi PROFILE (30/31) → TÁI TẠO VD id MỚI → phải DÒ động.
 *  2. RENDER (RE DashCast + kiểm chứng trên xe): T1 = MẶC ĐỊNH mọi app — `settings put global force_resizable_activities 0`
 *     → `am start --display <VD> --windowingMode 5` KHÔNG clear-task (resume task đang chạy → GIỮ dẫn) → `am task resize`
 *     (ép composite → hết trắng). App chưa chạy → freshLaunch. T3 (daemon app_process, [T3Daemon]) = dự phòng opt-in ([t3Apps]) khi T1 hụt.
 *  3. SCALE PER-APP (R6): dpi + bounds theo TỪNG app ([AppScale], [scaleOf]) thay global DPI+inset. Áp live [applyScaleLive].
 *  4. PROFILE ĐA-MODEL (R8): chuỗi chiếu/teardown + kích cụm nằm sau [ClusterProfile] (Seal DL3 = [30,16,35] / [18,0]).
 *  5. `wm density` = fix scale; `wm overscan` = khung mỹ thuật. WARM switch re-issue 16. TEARDOWN: bê app khỏi VD →
 *     reset density/overscan → teardownSeq (Seal 18→0). PROFILE 30=cong giữ km/h · 31=chữ nhật full (toggle [keepKmh]).
 */
object ClusterCast {
    private val busy = AtomicBoolean(false)
    // ★ 1 SERIAL EXECUTOR cho MỌI thao tác dadb sửa VD (cast/stop/applyScaleLive/applyGlobalAnim) → chạy TUẦN TỰ,
    //   KHÔNG BAO GIỜ 2 luồng cùng ghi wm density / am task resize / service call lên 1 VD (khử tận gốc race
    //   scale-vs-cast/stop: busy/scaleApplying giờ chỉ là cờ feedback + coalesce, không phải rào chống-đua DUY NHẤT).
    private val vdExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    // ★ SINGLE-FLIGHT áp-live (review#2): chỉ 1 luồng [applyScaleLive] chạy tại 1 thời điểm → nhấn mũi tên DỒN DẬP
    //   không mở nhiều kết nối dadb song song cùng `am task resize` 1 task. busy (cast/stop) là mutex RIÊNG.
    private val scaleApplying = AtomicBoolean(false)
    private val PKG_OK = Regex("^[a-zA-Z0-9._]+$")

    const val PROFILE_CURVED = 30
    const val PROFILE_RECT = 31
    private const val CMD_PROJECT = 16
    private const val CMD_DI40 = 35
    // Teardown codes (18=đóng chiếu, 0=refresh video) giờ nằm trong ClusterProfile.teardownSeq (Seal = [18,0]).

    // ★ TẮT animation hệ thống LÚC CHIẾU → transition move-stack giữa 2 màn mượt/tức thì (hết lag đổi app). Trả lại khi STOP.
    private val ANIM_KEYS = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
    @Volatile private var savedAnim = "1.0"   // giá trị gốc để khôi phục (đọc trước khi tắt)

    data class AppInfo(val label: String, val pkg: String, val icon: android.graphics.drawable.Drawable? = null)

    // ── state bền (prefs) ──
    @Volatile var keepKmh = true                 // true = cong SL6 giữ km/h | false = chữ nhật full
    // Profile trả cụm về KHI TEARDOWN. -1 = KHÔNG set profile sau RESTORE(0) — chỉ 18→0 như recipe DashCast.
    // ★ 2026-07-19: hardcode sc(31) cũ chốt cụm ở profile CHỮ NHẬT (mất km/h) → nghi phá luôn layout nav-lane
    //   (broadcast nav vẫn tới qua CAN nhưng cụm không có widget để vẽ) → nav chết tới mức phải reboot. -1 tránh điều đó.
    //   Nếu sau chiếu profile CONG (30) đồng hồ bị "dính cong" → set = 31 (hoặc mã profile-gốc đúng) qua setTeardownProfile.
    @Volatile var teardownProfile = -1
    @Volatile var insetH = 0                      // fallback overscan ngang (px). Ưu tiên AppScale rect; giữ làm khung mỹ thuật.
    @Volatile var insetV = 90                     // fallback overscan dọc (px) — mặc định chừa cho curve khỏi cắt.
    @Volatile var clusterDpi = 200                // DPI fallback — làm default cho AppScale khi app chưa cấu hình. tune 120–320.
    // ★ SCALE PER-APP (R6, T-B): map pkg→AppScale (dpi + rect L/T/R/B). Thay model global DPI+inset cố định.
    //   rect=-1 → auto = full VD (giữ hành vi cũ). Xem [AppScale], [scaleOf], [setScale], [applyScaleLive].
    @Volatile var appScales: Map<String, AppScale> = emptyMap(); private set
    // ★ T3 (RE DashCast) — app trong set này dùng DAEMON app_process uid-2000 + reflection (moveStackToDisplay +
    //   setTaskWindowingMode(5) + resizeTask FORCED). Đường chắc-ăn-nhất khi T1(shell) không set được freeform cho
    //   task đang chạy. NẶNG hơn T1 (spawn app_process mỗi lần). Chỉ bật khi T1 hụt. Xem [T3Daemon].
    @Volatile var t3Apps: Set<String> = emptySet(); private set
    @Volatile var castableApps: List<String> = emptyList(); private set   // app user tick cho menu nút nổi
    @Volatile var lastCastApp: String = ""; private set                   // app chiếu gần nhất (tap = chiếu lại)
    @Volatile var casting = false; private set    // đang chiếu? (bong bóng toggle + đổi icon theo cờ này)
    @Volatile var lastDisplayId = -1; private set
    // ★ Kích thước VD cụm THẬT — auto-detect từ dumpsys (vdRealSize) lần chiếu gần nhất. Dùng cho rect per-app UI
    //   (R8: KHÔNG hardcode 1920×720 — lấy kích cụm thật). 0 = chưa chiếu lần nào → UI dùng ClusterProfile fallback.
    @Volatile var lastClusterW = 0; private set
    @Volatile var lastClusterH = 0; private set
    private fun rememberClusterSize(w: Int, h: Int) { if (w > 0 && h > 0) { lastClusterW = w; lastClusterH = h } }

    /** observer cho bong bóng: đổi trạng thái chiếu → cập nhật icon/nền nút nổi. */
    @Volatile var onCastingChanged: (() -> Unit)? = null
    private fun setCasting(v: Boolean) { if (casting != v) { casting = v; runCatching { onCastingChanged?.invoke() } } }

    private const val PREF = "clustercast"
    fun setKeepKmh(ctx: Context, v: Boolean) { keepKmh = v; save(ctx) }
    /** Profile trả cụm về khi teardown. -1 = chỉ 18→0 (mặc định, an toàn cho nav-lane). ≥0 = set mã profile đó sau RESTORE. */
    fun setTeardownProfile(ctx: Context, p: Int) { teardownProfile = p; save(ctx) }
    /** true = app này dùng DAEMON app_process + reflection (T3). Chắc-ăn-nhất, nặng nhất. false = T1 mặc định (freeform+resize). */
    fun setT3(ctx: Context, pkg: String, on: Boolean) { t3Apps = if (on) t3Apps + pkg else t3Apps - pkg; save(ctx) }
    fun isT3(pkg: String) = pkg in t3Apps

    // ── SCALE PER-APP (R6, T-B) ──
    /** AppScale của [pkg]. Chưa cấu hình → default (dpi = [clusterDpi] fallback, rect auto = full VD). */
    fun scaleOf(pkg: String): AppScale = appScales[pkg] ?: AppScale(dpi = clusterDpi)
    /** Lưu AppScale cho [pkg] (cập nhật map + prefs). Áp lên cụm ngay gọi riêng [applyScaleLive]. */
    fun setScale(ctx: Context, pkg: String, scale: AppScale) {
        appScales = appScales.toMutableMap().apply { put(pkg, scale) }
        save(ctx)
    }
    fun setCastableApps(ctx: Context, apps: List<String>) { castableApps = apps.distinct(); save(ctx) }
    private fun setLastCastApp(ctx: Context, p: String) { lastCastApp = p; save(ctx) }
    private fun save(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("keepKmh", keepKmh).putInt("insetH", insetH).putInt("insetV", insetV)
            .putString("castable", castableApps.joinToString(",")).putString("lastApp", lastCastApp)
            .putInt("dpi", clusterDpi).putInt("teardownProfile", teardownProfile)
            .putString("t3", t3Apps.joinToString(","))
            .putString("appscales", AppScale.serializeMap(appScales)).apply()
    }
    fun loadPrefs(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        keepKmh = sp.getBoolean("keepKmh", true); insetH = sp.getInt("insetH", 0); insetV = sp.getInt("insetV", 90)
        castableApps = (sp.getString("castable", "") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        lastCastApp = sp.getString("lastApp", "") ?: ""
        clusterDpi = sp.getInt("dpi", 200).coerceIn(120, 320)
        teardownProfile = sp.getInt("teardownProfile", -1)
        t3Apps = (sp.getString("t3", "") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        appScales = AppScale.parseMap(sp.getString("appscales", "") ?: "")
    }

    // ── liệt kê app đã cài (PackageManager — chạy in-process, KHÔNG cần dadb) ──
    /** mọi app có launcher activity (để màn Cài đặt chiếu cho tick). Sắp theo tên. */
    fun listInstalledApps(ctx: Context): List<AppInfo> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            pm.queryIntentActivities(intent, 0)
                .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName, runCatching { it.loadIcon(pm) }.getOrNull()) }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
    }
    fun labelOf(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager; pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
    /** icon app (cho lưới chọn app + menu bong bóng). null nếu không có. */
    fun iconOf(ctx: Context, pkg: String): android.graphics.drawable.Drawable? =
        runCatching { ctx.packageManager.getApplicationIcon(pkg) }.getOrNull()

    private fun sc(n: Int) = "service call AutoContainer 2 i32 1000 i32 $n s16 \"\""
    private fun overscanArg() = "$insetH,$insetV,$insetH,$insetV"

    /**
     * Chiếu 1 app lên cụm. pkg rỗng → dùng [lastCastApp] → app đầu trong [castableApps]. Bê task đang chạy (giữ state);
     * chưa chạy thì mở ở màn giữa rồi bê. Không phải map thì mặc định vẫn theo [keepKmh] (user chỉnh cong/thẳng per ý).
     */
    fun cast(ctx: Context, pkg: String, log: (String) -> Unit) {
        if (!busy.compareAndSet(false, true)) { log("⏳ đang chạy 1 thao tác cụm — đợi xong"); return }
        val app = ctx.applicationContext
        vdExec.execute {
            try {
                runCatching {
                    val target = pkg.ifBlank { lastCastApp }.ifBlank { castableApps.firstOrNull() ?: "" }
                    if (target.isBlank() || !PKG_OK.matches(target)) { log("❌ chưa chọn app — vào Cài đặt chiếu tick app trước"); return@runCatching }
                    dadb.Dadb.create("localhost", 5555, AdbKeys.ensure(app)).use { adb ->
                        fun sh(c: String): String { val r = adb.shell(c); val e = r.errorOutput.trim(); if (e.isNotEmpty()) log("  ⚠ $e"); return r.output.trim() }

                        if (!isInstalled(adb, target)) { log("❌ app $target chưa cài trên xe"); return@use }
                        log("① app = ${labelOf(app, target)} ($target)")

                        var stk = appStack(sh("am stack list"), target)
                        if (stk < 0) {
                            val comp = resolveComp(adb, target) ?: run { log("❌ không mở được $target (không có launcher activity)"); return@use }
                            log("② app chưa chạy → mở ở màn giữa: $comp"); sh("am start -n $comp"); Thread.sleep(2500)
                            stk = appStack(sh("am stack list"), target)
                            if (stk < 0) { log("❌ mở app xong vẫn không thấy stack → HỦY"); return@use }
                        } else log("② app đang chạy (stack $stk) — BÊ NGUYÊN (giữ state)")

                        // ── ★ WARM PATH: ĐANG chiếu rồi + VD còn sống → CHỈ ĐỔI APP (move-stack), KHÔNG re-profile.
                        // Trước đây switch app chạy lại cả 30→16→35 → tái tạo VD + đổi mode cong liên tục → "nhảy loạn
                        // giữa các mode cong", ADAS hiện lại, app cũ mồ côi, stuck. Warm: bê app cũ ra, bê app mới vào VD.
                        val curVd = if (casting) parseClusterDisplayId(sh("dumpsys display | grep -iE 'Display [0-9]+:|fission|xdja'")) else -1
                        if (casting && curVd >= 1) {
                            log("③↔ WARM: đổi app trên VD $curVd (không re-profile)")
                            if (lastCastApp.isNotBlank() && lastCastApp != target) {
                                val list0 = sh("am stack list"); val oldStk = appStack(list0, lastCastApp)
                                if (oldStk >= 0 && appDisplay(list0, lastCastApp) == curVd) { sh("am display move-stack $oldStk 0"); Thread.sleep(400) }
                            }
                            // ADAS-fix: re-issue chiếu (16) để xoá mảng đen — warm switch cũ bỏ qua → ADAS hiện lại (RE DashCast).
                            // 16 có thể tái tạo VD → dò lại id trước khi đặt app.
                            sh(sc(CMD_PROJECT)); Thread.sleep(1000)
                            val useVd = parseClusterDisplayId(sh("dumpsys display | grep -iE 'Display [0-9]+:|fission|xdja'")).let { if (it >= 1) it else curVd }
                            val onVd = placeAppOnVd(app, adb, { c -> sh(c) }, target, useVd, log)
                            lastDisplayId = useVd; setLastCastApp(app, target); setCasting(true)
                            if (onVd == useVd) log("✅ Đổi sang ${labelOf(app, target)} (warm, display $useVd)")
                            else log("⚠ ${labelOf(app, target)} chưa bám VD (đang $onVd) — thử app khác")
                            return@use
                        }

                        val prof = ClusterProfile.resolve(app)
                        var clusterMutated = false
                        try {
                            // ★ TẮT animation hệ thống (lưu gốc để trả lại khi stop) → transition move-stack tức thì, hết lag đổi màn
                            savedAnim = sh("settings get global window_animation_scale").let { if (it.isBlank() || it == "null") "1.0" else it }
                            for (k in ANIM_KEYS) sh("settings put global $k 0")
                            clusterMutated = true   // ★ vũ trang rollback TRƯỚC khi đổi cụm: mọi lỗi từ đây (kể cả sc() ném) đều trả đồng hồ
                            // ③–⑤ chiếu THEO PROFILE (Seal DL3 = [30,16,35], timing 3s/3s/2s giữ nguyên). cmd cong (30) → 31 nếu user chọn "thẳng".
                            log("③–⑤ profile ${prof.id}: chiếu [${prof.castSeq.joinToString(",")}] ${if (keepKmh) "(cong — giữ km/h)" else "(thẳng — full)"}")
                            for (raw in prof.castSeq) {
                                val cmd = if (raw == PROFILE_CURVED && !keepKmh) PROFILE_RECT else raw
                                log("   cmd $cmd"); sh(sc(cmd)); Thread.sleep(castSleepMs(raw))
                            }

                            var vd = -1
                            for (i in 0 until 16) {
                                vd = parseClusterDisplayId(sh("dumpsys display | grep -iE 'Display [0-9]+:|fission|xdja'"))
                                if (vd >= 1) break
                                Thread.sleep(500)
                            }
                            if (vd < 1) { log("❌ không dò thấy VD cụm → rollback"); rollback(adb, prof.teardownSeq, log); return@use }
                            log("⑥ VD cụm = display $vd")

                            log("⑦ đặt app lên VD $vd (scale per-app; T1 freeform mặc định, T3 nếu bật)")
                            val onVd = placeAppOnVd(app, adb, { c -> sh(c) }, target, vd, log)
                            lastDisplayId = vd; setLastCastApp(app, target); setCasting(true)
                            if (onVd == vd) log("✅ Xong — ${labelOf(app, target)} trên cụm (display $vd). ${if (keepKmh) "km/h gốc còn." else "full (mất km/h)."}")
                            else log("⚠ app chưa bám VD (đang display=$onVd) — app có thể khoá xoay/singleInstance → thử app khác")
                        } catch (e: Throwable) { log("❌ lỗi giữa chừng: ${e.message}"); if (clusterMutated) rollback(adb, prof.teardownSeq, log) }
                    }
                }.onFailure { log("❌ LỖI kết nối: ${it.message}\n(popup 'Allow USB debugging' đã bấm chưa?)") }
            } finally { busy.set(false) }
        }
    }

    fun stop(ctx: Context, log: (String) -> Unit) {
        if (!busy.compareAndSet(false, true)) { log("⏳ đang chạy 1 thao tác — thử lại sau"); return }
        val app = ctx.applicationContext
        vdExec.execute {
            try {
                runCatching {
                    dadb.Dadb.create("localhost", 5555, AdbKeys.ensure(app)).use { adb ->
                        fun sh(c: String) = adb.shell(c).output.trim()
                        val vd = lastDisplayId
                        // ① BÊ MỌI task đang ở VD cụm (display>=1) về display 0 — tổng quát, không riêng map (khỏi kẹt app trên VD vô hình)
                        for (s in stacksOnVd(sh("am stack list"))) { log("① bê stack $s (trên VD) → display 0"); sh("am display move-stack $s 0"); Thread.sleep(500) }
                        // ★ reset density/overscan đã đặt lên VD (DashCast: rò state trên id cũ = 1 nguồn gây kẹt)
                        if (vd >= 1) { sh("wm density reset -d $vd"); sh("wm overscan reset -d $vd") }
                        // ② trả đồng hồ THEO PROFILE (Seal = [18,0], timing 800ms giữ nguyên). KHÔNG hardcode sc(31) —
                        //    nó chốt cụm ở layout mất km/h, nghi phá nav-lane (nav chết tới mức phải reboot).
                        //    Chỉ set profile cuối nếu teardownProfile>=0 (user chỉnh khi đồng hồ dính cong).
                        val teardown = ClusterProfile.resolve(app).teardownSeq
                        log("② trả đồng hồ [${teardown.joinToString(",")}]${if (teardownProfile >= 0) " + profile $teardownProfile" else " (an toàn nav-lane)"}")
                        teardown.forEachIndexed { i, cmd -> if (i > 0) Thread.sleep(800); sh(sc(cmd)) }
                        if (teardownProfile >= 0) { Thread.sleep(800); sh(sc(teardownProfile)) }
                        for (k in ANIM_KEYS) sh("settings put global $k $savedAnim")   // ★ trả lại animation gốc (đã tắt lúc chiếu)
                        log("✅ Đã trả đồng hồ gốc. App về màn giữa.")
                    }
                }.onFailure { log("❌ LỖI tắt: ${it.message} — vẫn reset cờ để thử lại được") }
            } finally { lastDisplayId = -1; setCasting(false); busy.set(false) }   // ★ LUÔN reset dù stop lỗi → không kẹt cờ 'đang chiếu'
        }
    }

    private fun rollback(adb: dadb.Dadb, teardownSeq: List<Int>, log: (String) -> Unit) {
        log("↩ rollback: trả đồng hồ [${teardownSeq.joinToString(",")}${if (teardownProfile >= 0) "→$teardownProfile" else ""}]…")
        val vd = lastDisplayId
        runCatching {
            if (vd >= 1) { adb.shell("wm density reset -d $vd"); adb.shell("wm overscan reset -d $vd") }
            teardownSeq.forEachIndexed { i, cmd -> if (i > 0) Thread.sleep(800); adb.shell(sc(cmd)) }
            if (teardownProfile >= 0) { Thread.sleep(800); adb.shell(sc(teardownProfile)) }
            for (k in ANIM_KEYS) adb.shell("settings put global $k $savedAnim") }   // trả lại animation gốc
        lastDisplayId = -1; setCasting(false)
    }

    /**
     * ĐẶT app lên VD cụm. DISPATCH (T-A): app trong [t3Apps] → **T3** (daemon app_process, chắc-ăn); CÒN LẠI → **T1**
     * ([placeFreeform] — mặc định: move+freeform+resize giữ dẫn, chưa chạy thì [freshLaunch]).
     *   ① `wm density <dpi> -d VD` → fix SCALE, dpi lấy từ [AppScale] per-app (scaleOf(target).dpi).
     *   ② T3 hoặc T1 (bounds cũng từ AppScale — rect=-1 → full VD).
     *   ③ `wm overscan` = khung mỹ thuật (fallback insetH/insetV).
     * Trả về displayId app THỰC bám. Guard: chỉ gọi khi vd>=1 (không bao giờ đụng display 0).
     */
    private fun placeAppOnVd(app: Context, adb: dadb.Dadb, sh: (String) -> String, target: String, vd: Int, log: (String) -> Unit): Int {
        // ★ FIX B v2 (2026-07-21, verify SOURCE AOSP 10 — xem [applyBounds]): 2 setting dưới CHỈ được framework đọc
        //   LÚC BOOT → set runtime KHÔNG ăn ngay (v0.33/0.34 tưởng ăn ngay → nút size chết có hệ thống). VẪN set để
        //   PERSIST: sau lần user TẮT MÁY XE HẲN rồi mở lại (adb reboot bị DiLink3 chặn), freeform sống thật →
        //   windowingMode 5 dính + `am task resize` ăn. Trước đó, size đi đường fallback overscan trong [applyBounds].
        sh("settings put global enable_freeform_support 1")
        sh("settings put global development_enable_freeform_windows_support 1")
        sh("wm density ${scaleOf(target).dpi} -d $vd"); Thread.sleep(150)             // ① scale per-app (dpi từ AppScale)
        // ② DISPATCH (T-A): app trong t3Apps → T3 (daemon app_process, chắc-ăn); CÒN LẠI → T1 placeFreeform (MẶC ĐỊNH — giữ dẫn).
        val landed = if (target in t3Apps) placeT3(app, adb, sh, target, vd, log)
                     else placeFreeform(adb, sh, target, vd, log)
        // ③ KHUNG per-app TẬP TRUNG 1 CHỖ: resize (freeform sống) → fallback overscan (không cần freeform).
        //   Thay `wm overscan overscanArg()` vô điều kiện cũ (nó đè khung khi fallback đã set overscan theo rect).
        if (landed == vd) {
            val tid = appTaskId(sh("am stack list"), target)
            val (w, h) = vdRealSize(sh("dumpsys display"), vd); rememberClusterSize(w, h)
            log("  ⑧ khung: ${applyBounds(sh, vd, tid, scaleOf(target), w, h)}")
        } else sh("wm overscan ${overscanArg()} -d $vd")                              // app không bám VD → chỉ khung mỹ thuật legacy
        return landed
    }

    /**
     * ★ ÁP KHUNG (bounds per-app) lên VD — 2 tầng, TỰ FALLBACK:
     *   ① `am task resize` — CHỈ ăn khi freeform SỐNG (đã boot với enable_freeform_support=1).
     *   ② bị từ chối → `wm overscan` theo [AppScale.overscanOn] — tầng display, KHÔNG cần freeform
     *      (đã chứng minh ăn trên xe 2026-07-20) → nút chỉnh size hoạt động NGAY, không chờ reboot.
     * GỐC RỄ (verify source AOSP 10 android-10.0.0_r47, 2026-07-21 — memory `byd-freeform-boot-gate`):
     *   • `enable_freeform_support` CHỈ đọc 1 lần lúc boot (`ATMS.retrieveSettings`, KHÔNG có ContentObserver)
     *     → set runtime vô hiệu tới khi power-cycle xe (adb reboot bị DiLink3 chặn → phải tắt máy tay).
     *   • `WindowConfiguration.canResizeTask()` == (mode==FREEFORM) → `am task resize` task fullscreen LUÔN ném
     *     IllegalArgumentException. `cmd activity task resize` là CÙNG handler → fallback cmd cũ vô dụng, ĐÃ BỎ.
     *   • `setTaskWindowingMode(5)` (cả shell lẫn T3 daemon) bị `validateWindowingMode` downgrade IM LẶNG khi
     *     freeform boot-flag tắt → lỗi KHÔNG lộ ở bước move, chỉ lộ ở resize — nên resize-output là probe tin cậy.
     * rect auto (user chưa cấu hình) → fallback dùng khung mỹ thuật legacy [overscanArg] (giữ hành vi cũ).
     * @return mô tả đường đã áp — để log TRUNG THỰC (trước đây log "đã áp scale" cả khi resize bị ném lỗi).
     */
    private fun applyBounds(sh: (String) -> String, vd: Int, tid: Int, scale: AppScale, w: Int, h: Int): String {
        var rejected = ""
        if (tid >= 0) {
            val b = scale.boundsOn(w, h)
            // 2>&1: lỗi resize in ra STDERR; sh() chỉ trả STDOUT → phải gộp mới detect được từ chối.
            val o = sh("am task resize $tid ${b[0]} ${b[1]} ${b[2]} ${b[3]} 2>&1")
            if (!resizeRejected(o)) {
                // freeform sống: bounds do TASK quản. rect tùy chỉnh → XÓA overscan (khỏi double-inset khung
                // fallback cũ / khung mỹ thuật); rect auto → giữ khung mỹ thuật legacy như trước (review P3).
                if (scale.isAuto) sh("wm overscan ${overscanArg()} -d $vd")
                else sh("wm overscan 0,0,0,0 -d $vd")
                return "resize freeform → [${b.joinToString(",")}]"
            }
            rejected = o
        }
        val oi = if (scale.isAuto) intArrayOf(insetH, insetV, insetH, insetV) else scale.overscanOn(w, h)
        sh("wm overscan ${oi[0]},${oi[1]},${oi[2]},${oi[3]} -d $vd")
        // Log ĐÚNG nguyên nhân (review P3): chỉ đổ cho "freeform chưa bật" khi đúng chữ ký từ chối của
        // canResizeTask ("not allowed"/Exception); lỗi khác (task chết, output lạ) → nói thẳng lỗi gì.
        return when {
            tid < 0 -> "overscan [${oi.joinToString(",")}] (không lấy được taskId)"
            rejected.contains("not allowed", true) || rejected.contains("Exception", true) ->
                "overscan [${oi.joinToString(",")}] (freeform chưa bật — TẮT MÁY XE 1 lần rồi mở lại để dùng resize thật)"
            else -> "overscan [${oi.joinToString(",")}] (resize lỗi: ${rejected.take(60)})"
        }
    }

    /** `am task resize` bị framework từ chối? (fullscreen task trên A10 → IllegalArgumentException "not allowed"). */
    private fun resizeRejected(o: String) =
        o.contains("Error", true) || o.contains("Exception", true) ||
        o.contains("not allowed", true) || o.contains("must be", true)

    /**
     * ★ T1 (RE DashCast 2026-07-19) — GIỮ STATE + RENDER ĐÚNG. Giải mâu thuẫn fresh(mất dẫn) vs move-stack(trắng).
     * Đường shell chạy từ uid-2000 (dadb), KHÔNG cần ký platform:
     *   ① `settings put global force_resizable_activities 0` — theo RE DashCast (ClusterService:149). ⚠ setting này
     *      cũng BOOT-ONLY (AOSP 10 retrieveSettings) → chỉ có nghĩa cho lần boot sau, runtime là no-op.
     *   ② `am start --display VD --windowingMode 5 -n comp` **KHÔNG --activity-clear-task**: resume task ĐANG CHẠY
     *      (giữ phiên dẫn) + move sang VD. FREEFORM chỉ dính khi freeform sống (boot-flag) — không thì im lặng
     *      giữ fullscreen, app VẪN bám VD (move được, render map OK; app GL/video có thể trắng).
     *   ③ bounds/ép-composite áp TẬP TRUNG ở [placeAppOnVd] bước ⑧ ([applyBounds]: resize → fallback overscan).
     * Trả về displayId app thực bám. Nếu chưa có task đang chạy → fallback freshLaunch (không có state để giữ).
     */
    private fun placeFreeform(adb: dadb.Dadb, sh: (String) -> String, target: String, vd: Int, log: (String) -> Unit): Int {
        val comp = resolveComp(adb, target) ?: run { log("  ❌ không resolve được component"); return -1 }
        if (appStack(sh("am stack list"), target) < 0) {
            log("  ↩ $target chưa chạy (không có state để giữ) → fresh-launch")
            return freshLaunch(adb, sh, target, vd, log)
        }
        sh("settings put global force_resizable_activities 0")                        // ① persist cho boot sau (runtime no-op)
        // ② move + freeform, GIỮ state (không clear-task → resume task đang dẫn)
        val out = sh("am start --display $vd --windowingMode 5 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $comp")
        Thread.sleep(900)
        val landed = appDisplay(sh("am stack list"), target)
        if (landed != vd) {
            // ★ FIX A: move-stack KHÔNG bám VD (app singleInstance/khoá xoay/từ chối move — hay gặp ở app video/mirror
            //   như CarPlay/Android Auto) → fallback FRESH-LAUNCH (mất state nhưng ÍT NHẤT lên cụm + composite ĐÚNG,
            //   hết đen/trắng). Generic (không hardcode pkg): áp cho mọi app move hụt. App có state (nav) thì move
            //   đã bám ở trên nên không rơi vào đây; app video (không state cần giữ) rơi vào đây → fresh là hợp lý.
            log("  ↩ move không bám VD (đang $landed) → fallback fresh-launch (composite đúng cho app video): ${out.take(60)}")
            return freshLaunch(adb, sh, target, vd, log)
        }
        log("  ✓ move-giữ-state → display $vd (khung áp ở bước ⑧)")
        return landed
    }

    /**
     * ★ T3 (RE DashCast) — GIỮ STATE + RENDER ĐÚNG qua DAEMON app_process uid-2000 + reflection IActivityTaskManager.
     * Dùng khi T1(shell) không set được freeform cho task đang chạy. Spawn [T3Daemon] one-shot qua dadb:
     *   `CLASSPATH=<apk ClusterNav> app_process64 /system/bin <T3Daemon> <pkg> <displayId> <l t r b>`
     * Daemon: findTaskId → setTaskWindowingMode(5) → moveStackToDisplay → resizeTask(FORCED). Task đang chạy được
     * relocate (không kill → giữ dẫn). Nếu app chưa chạy → fresh-launch (không có state để giữ).
     */
    private fun placeT3(app: Context, adb: dadb.Dadb, sh: (String) -> String, target: String, vd: Int, log: (String) -> Unit): Int {
        if (appStack(sh("am stack list"), target) < 0) {
            log("  ↩ $target chưa chạy (không có state) → fresh-launch"); return freshLaunch(adb, sh, target, vd, log)
        }
        sh("settings put global force_resizable_activities 0")
        // ★ pm path có thể trả NHIỀU dòng (split APK: base + config.arm64 + config.<lang>). CLASSPATH cho app_process
        //   PHẢI là APK chứa classes.dex = base.apk; config-split KHÔNG có dex → ClassNotFound. Ưu tiên base.apk,
        //   fallback dòng đầu (debug build thường single-APK nên chỉ 1 dòng = base).
        val apkPaths = sh("pm path ${app.packageName}").lineSequence()
            .filter { it.startsWith("package:") }.map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }.toList()
        val apk = apkPaths.firstOrNull { it.endsWith("base.apk") } ?: apkPaths.firstOrNull()
        if (apk.isNullOrBlank()) { log("  ❌ T3: không lấy được APK path ${app.packageName}"); return -1 }
        val (w, h) = vdRealSize(sh("dumpsys display"), vd); rememberClusterSize(w, h)
        val b = scaleOf(target).boundsOn(w, h)   // bounds per-app từ AppScale (rect=-1 → full VD [0,0,W,H])
        val cls = "com.byd.clusternav.modules.clustercast.T3Daemon"
        // -Xnoimage-dex2oat: tránh crash AOT lúc app_process khởi động trên SoC DiLink3 (RE ProxyClient DashCast).
        val cmd = "CLASSPATH=$apk app_process64 -Xnoimage-dex2oat /system/bin $cls $target $vd ${b[0]} ${b[1]} ${b[2]} ${b[3]}"
        log("  ⊞ T3 daemon (uid-2000 app_process) → move+freeform+resize task đang chạy")
        val out = sh(cmd)
        out.lineSequence().filter { it.startsWith("T3 ") }.forEach { log("    $it") }
        if (out.isBlank()) log("    (daemon không in gì — app_process64 chạy? thử log dadb)")
        Thread.sleep(600)
        val landed = appDisplay(sh("am stack list"), target)
        log(if (landed == vd) "  ✓ T3 → display $vd (giữ state)" else "  ⚠ T3 chưa bám VD (đang $landed)")
        return landed
    }

    /** Launch app MỚI vào VD ở FREEFORM (windowingMode 5). Activity mới → composite đúng (hết trắng); nav tự resume. */
    private fun freshLaunch(adb: dadb.Dadb, sh: (String) -> String, target: String, vd: Int, log: (String) -> Unit): Int {
        val comp = resolveComp(adb, target) ?: return -1
        val out = sh("am start --display $vd --windowingMode 5 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $comp --activity-clear-task")
        Thread.sleep(900); val d = appDisplay(sh("am stack list"), target)
        if (d == vd) log("  ✓ launch mới freeform → display $vd") else log("  ↩ freeform chưa bám (${out.take(60)})")
        return d
    }

    /** Sleep (ms) sau mỗi cmd chiếu — GIỮ NGUYÊN timing Seal: DI40(35)=2s, còn lại (profile/chiếu)=3s. */
    private fun castSleepMs(cmd: Int): Long = if (cmd == CMD_DI40) 2000L else 3000L

    /**
     * ★ ÁP SCALE PER-APP NGAY LÊN CỤM (T-C) — dpi ([wm density]) + bounds ([am task resize]) từ [AppScale] của [pkg].
     * Chỉ áp khi đang chiếu ĐÚNG app [pkg] (lastCastApp==pkg, vd>=1). Chưa chiếu → chỉ lưu (đã lưu ở setScale), lần sau tự áp.
     * Tái dùng helper [appTaskId]/[vdRealSize]. Guard vd>=1 (KHÔNG BAO GIỜ -d 0). Chạy nền (dadb block).
     */
    fun applyScaleLive(ctx: Context, pkg: String, log: (String) -> Unit) {
        val vd = lastDisplayId
        val scale = scaleOf(pkg)
        if (!casting || vd < 1 || lastCastApp != pkg) {
            log("đã lưu scale $pkg (dpi=${scale.dpi}${if (scale.isAuto) ", auto" else ", ${scale.rectR - scale.rectL}×${scale.rectB - scale.rectT}"}) — chưa chiếu app này → lần sau tự áp")
            return
        }
        // ★ KHÔNG đụng cụm khi cast()/stop() đang chạy (busy) → tránh 2 luồng dadb cùng ghi wm/am lên VD (interleave
        //   giữa chuỗi profile 30→16→35). Scale đã lưu ở setScale() trước khi gọi hàm này → lần chiếu/áp kế tiếp tự dùng.
        if (busy.get()) { log("đã lưu scale $pkg — đang chạy thao tác cụm, sẽ áp sau"); return }
        // ★ SINGLE-FLIGHT (review#2): nhấn mũi tên dồn dập → chỉ 1 luồng dadb áp-live; luồng đang bay tự đọc LẠI
        //   scaleOf(pkg) mới nhất lúc chạy nên gộp cả loạt nhấn thành 1 lần áp giá trị cuối (tránh N kết nối +
        //   N `am task resize` song song cho kết quả bất định). Nhả cờ trong finally → không kẹt kể cả khi lỗi.
        if (!scaleApplying.compareAndSet(false, true)) { log("đã lưu scale $pkg — đang áp bản trước, gộp vào lần áp đang chạy"); return }
        val app = ctx.applicationContext
        vdExec.execute {
            try {
                runCatching {
                    dadb.Dadb.create("localhost", 5555, AdbKeys.ensure(app)).use { adb ->
                        fun sh(c: String) = adb.shell(c).output.trim()
                        val cur = scaleOf(pkg)   // ★ đọc LẠI giá trị mới nhất (gộp loạt nhấn dồn dập)
                        sh("wm density ${cur.dpi} -d $vd")
                        val tid = appTaskId(sh("am stack list"), pkg)
                        val (w, h) = vdRealSize(sh("dumpsys display"), vd); rememberClusterSize(w, h)
                        // ★ v0.35: [applyBounds] tự fallback overscan khi freeform chưa sống (kể cả tid<0) →
                        //   nút chỉnh size LUÔN có tác dụng; log nói thật đường nào đã áp (hết "đã áp" ảo).
                        log("đã áp scale: dpi=${cur.dpi} qua ${applyBounds(::sh, vd, tid, cur, w, h)} (task $tid, VD ${w}x$h)")
                    }
                }.onFailure { log("❌ áp scale lỗi: ${it.message}") }
            } finally { scaleApplying.set(false) }
        }
    }

    /** "Mượt UI": set 3 animation scale GLOBAL qua dadb (uid shell). scale "0.5"=snappy · "1.0"=mặc định. Chạy nền, idempotent. */
    fun applyGlobalAnim(ctx: Context, scale: String, log: (String) -> Unit = {}) {
        val app = ctx.applicationContext
        vdExec.execute {
            runCatching {
                dadb.Dadb.create("localhost", 5555, AdbKeys.ensure(app)).use { adb ->
                    for (k in ANIM_KEYS) adb.shell("settings put global $k $scale")
                }
                log("đã set animation scale = $scale (mượt UI)")
            }.onFailure { log("❌ set animation lỗi: ${it.message} (popup Allow USB đã bấm?)") }
        }
    }

    // ── parse helpers ──
    // khớp TRỌN dòng "package:<pkg>" (không contains — tránh 'com.foo' dính 'com.foobar')
    private fun isInstalled(adb: dadb.Dadb, pkg: String) = adb.shell("pm list packages").output.lineSequence().any { it.trim() == "package:$pkg" }

    private fun resolveComp(adb: dadb.Dadb, pkg: String): String? =
        adb.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $pkg")
            .output.lines().map { it.trim() }.lastOrNull { it.contains("/") && !it.contains(" ") }

    /** stackId chứa task của pkg (dòng "Stack id=N" ở trên, có dòng configuration chen giữa → nhớ stack gần nhất). */
    private fun appStack(stackList: String, pkg: String): Int {
        var cur = -1
        for (line in stackList.lines()) {
            Regex("^Stack id=(\\d+)").find(line.trim())?.let { cur = it.groupValues[1].toInt() }
            if (line.contains("$pkg/")) return cur   // "$pkg/" = ranh giới component (tránh prefix trùng)
        }
        return -1
    }
    private fun appDisplay(stackList: String, pkg: String): Int {
        var cur = -1
        for (line in stackList.lines()) {
            Regex("^Stack id=\\d+.*displayId=(\\d+)").find(line.trim())?.let { cur = it.groupValues[1].toInt() }
            if (line.contains("$pkg/")) return cur
        }
        return -1
    }
    /** taskId của pkg — nhớ "Task id=N"/"taskId=N" gần nhất phía trên dòng có "$pkg/" (dùng cho `am task resize`). */
    private fun appTaskId(stackList: String, pkg: String): Int {
        var cur = -1
        for (line in stackList.lines()) {
            Regex("(?:Task id=|taskId=)(\\d+)").find(line.trim())?.let { cur = it.groupValues[1].toInt() }
            if (line.contains("$pkg/")) return cur
        }
        return -1
    }
    /** kích thước THẬT của VD (từ dumpsys display, "real W x H" trong block của display id). Fallback 1920x720 (fission cụm Seal). */
    private fun vdRealSize(dump: String, vd: Int): Pair<Int, Int> {
        var cur = -1
        for (line in dump.lines()) {
            Regex("(?:Display |mDisplayId=|Display Id=)(\\d+)").find(line)?.let { cur = it.groupValues[1].toIntOrNull() ?: cur }
            if (cur == vd) Regex("real (\\d+) x (\\d+)").find(line)?.let {
                return it.groupValues[1].toInt() to it.groupValues[2].toInt()
            }
        }
        return 1920 to 720
    }
    /** mọi stackId đang nằm trên VD cụm (displayId>=1) — để STOP bê hết về màn giữa. */
    private fun stacksOnVd(stackList: String): List<Int> {
        val out = mutableListOf<Int>()
        for (line in stackList.lines()) {
            Regex("^Stack id=(\\d+).*displayId=(\\d+)").find(line.trim())?.let {
                if (it.groupValues[2].toInt() >= 1) out.add(it.groupValues[1].toInt())
            }
        }
        return out
    }

    /** id logical display có tên fission/xdja (>=1, không bao giờ 0 = màn chính). */
    fun parseClusterDisplayId(grepOut: String): Int {
        var cur = -1
        for (line in grepOut.lines()) {
            Regex("Display (\\d+):").find(line)?.let { cur = it.groupValues[1].toIntOrNull() ?: cur }
            val l = line.lowercase()
            if (l.contains("fission") || l.contains("xdja")) {
                Regex("(?:displayId|Display) (\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { if (it >= 1) return it }
                if (cur >= 1) return cur
            }
        }
        return -1
    }
}

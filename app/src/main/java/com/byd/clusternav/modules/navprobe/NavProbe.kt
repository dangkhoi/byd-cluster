package com.byd.clusternav.modules.navprobe

import android.content.Context
import android.os.Bundle
import android.service.notification.StatusBarNotification

/**
 * ★ MÁY DÒ DẪN ĐƯỜNG — module KHẢO SÁT ĐỘC LẬP, dùng xong bỏ được. Mặc định TẮT.
 *
 * MỤC ĐÍCH: tìm xem có lấy được thông tin dẫn đường (cự ly tới rẽ · hướng rẽ · tên đường) từ app KHÁC
 * Google Maps không — Vietmap, Waze, CarPlay, Android Auto — để bắn lên cụm như đã làm được với GMaps.
 *
 * ĐÂY LÀ CODE THÍ NGHIỆM, CỐ Ý TÁCH RỜI:
 *   • KHÔNG một dòng nào của đường chạy thật gọi vào module này. Các component của nó (notification listener,
 *     accessibility service, activity) là component RIÊNG khai trong Manifest, bật/tắt độc lập.
 *   • Đo xong: được → viết bộ rút dữ liệu thật; không được → **xoá nguyên thư mục `modules/navprobe/`
 *     + 3 khối trong Manifest + `res/xml/navprobe_accessibility.xml`**, phần còn lại của app không hề hấn gì.
 *
 * BA KÊNH ĐO SONG SONG (chưa biết kênh nào sống thì đo cả ba; im lặng cũng là kết quả nên vẫn ghi vào file):
 *   1. NOTIFICATION — [recordNotification]. Chuyến 22/07: Vietmap 26 bản, Waze 4 bản, CarPlay 0 bản,
 *      tất cả đều là keepalive, KHÔNG có dữ liệu nav (cả ba lúc đó ĐANG dẫn đường thật — chủ xe xác nhận).
 *   2. HAL cụm — [NavProbeHal]. Chuyến 22/07: đăng ký 143 feature, 39 phút KHÔNG một sự kiện.
 *   3. MÀN HÌNH — [recordNodes]. Ngả duy nhất còn cửa: đọc thẳng chữ đang hiện, đúng cơ chế mà booster
 *      GMaps đã chạy được trên chính chiếc xe này.
 *
 * Ghi RA FILE (`files/navprobe/`) chứ không chỉ logcat: cắm CarPlay/AA là đầu xe tắt WiFi → adb không vào được.
 *
 * ⚠ RIÊNG TƯ: khi bật, file chứa NGUYÊN VĂN notification của mọi app (có thể gồm tin nhắn, tên người gọi) và
 * chữ đang hiện trên màn. Vì thế mặc định TẮT, tự tắt sau [AUTO_OFF_MS], và màn hình cảnh báo trước khi bật.
 */
object NavProbe {

    private const val PREF = "navprobe"
    private const val K_ON = "on"
    private const val K_UNTIL = "until"
    private const val K_AUTOARM = "autoarm"

    /** Tự tắt sau 2 giờ — đủ một chuyến đi, và không để quên bật cả tuần. */
    const val AUTO_OFF_MS = 2L * 60 * 60 * 1000

    /** Gói cần soi ở kênh MÀN HÌNH. Khai một chỗ để `navprobe_accessibility.xml` và UI cùng nhìn vào đây. */
    val TARGETS = listOf(
        "vn.vietmap.live",          // Vietmap Live
        "com.waze",                 // Waze
        "com.byd.carplay.ui",       // wrapper CarPlay của BYD
        "com.byd.androidauto",      // wrapper Android Auto của BYD
    )

    /** Bỏ rác hệ thống ở kênh notification để file còn đọc được; giữ mọi thứ CÓ THỂ là app dẫn đường. */
    private val SKIP_PREFIXES = listOf("android", "com.android.systemui", "com.byd.clusternav")

    // ───────────────────────────── bật / tắt ─────────────────────────────

    fun isOn(ctx: Context): Boolean {
        val sp = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (!sp.getBoolean(K_ON, false)) return false
        if (System.currentTimeMillis() > sp.getLong(K_UNTIL, 0L)) { setOn(ctx, false); return false }
        return true
    }

    fun setOn(ctx: Context, on: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(K_ON, on)
            .putLong(K_UNTIL, if (on) System.currentTimeMillis() + AUTO_OFF_MS else 0L)
            .apply()
        if (on) {
            seen.clear(); lastDump.clear(); dumpRepeat.clear(); lastWrite.clear(); cachedFile = null
            writeHeader(ctx)
            NavProbeHal.start(ctx.applicationContext)
            NavProbeBroadcast.start(ctx.applicationContext)
            NavProbeMedia.start(ctx.applicationContext)
        } else {
            NavProbeBroadcast.stop(ctx.applicationContext)
            NavProbeMedia.stop()
        }
    }

    private const val K_SUPPRESS_UNTIL = "suppress_until"
    /** Người dùng tắt tay thì im trong 12 giờ — đủ để họ lái xong chuyến mà không bị bật lại sau lưng. */
    private const val SUPPRESS_MS = 12L * 60 * 60 * 1000

    /**
     * TẮT do người dùng bấm. Khác [setOn] ở chỗ nó được GHI NHỚ: bản cũ `setOn(false)` không đụng gì tới
     * auto-arm, mà `onResume` lại gọi armProbe vô điều kiện → rời màn rồi quay lại là ghi tiếp, phá đúng
     * lời hứa "tắt tay bất cứ lúc nào".
     */
    fun stopByUser(ctx: Context) {
        setOn(ctx, false)
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putLong(K_SUPPRESS_UNTIL, System.currentTimeMillis() + SUPPRESS_MS).commit()
    }

    private fun suppressed(ctx: Context): Boolean =
        System.currentTimeMillis() <
            ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(K_SUPPRESS_UNTIL, 0L)

    /**
     * TỰ LÊN NÒNG mỗi lần app khởi động / xe nổ máy — để lên xe chỉ việc lái, không phải nhớ bấm gì.
     *
     * Bật mặc định (chủ dự án yêu cầu: "cài vào, đến nơi cắm máy là lấy được log luôn"). Vẫn giữ nguyên
     * hai rào chắn bảo vệ chính người dùng: (a) tự tắt sau [AUTO_OFF_MS] nên không ghi lén cả tuần —
     * mỗi lần nổ máy lại lên nòng 2 giờ, đủ một chuyến; (b) tắt tay bất cứ lúc nào và nhớ lựa chọn đó.
     *
     * @param sh shell dadb để tự cấp quyền; null thì chỉ bật, không cấp.
     */
    fun autoArm(ctx: Context, sh: ((String) -> String)? = null, log: (String) -> Unit = {}) {
        val app = ctx.applicationContext
        if (!autoArmEnabled(app) || suppressed(app)) return
        if (sh != null && !NavProbeArm.armed(app)) log("🔬 máy dò tự cấp quyền:\n" + NavProbeArm.selfGrant(app, sh))
        if (isOn(app)) { restartChannels(app); return }
        setOn(app, true)
        log("🔬 máy dò TỰ LÊN NÒNG (${AUTO_OFF_MS / 60000} phút) — bật/tắt trong màn Máy dò")
    }

    /** Tự lên nòng mỗi lần khởi động (mặc định BẬT). */
    fun autoArmEnabled(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(K_AUTOARM, true)

    fun setAutoArm(ctx: Context, on: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(K_AUTOARM, on).commit()
    }

    /** Dựng lại các kênh nền sau khi tiến trình bị kill mà cờ vẫn còn hạn (§8: đừng để cờ bật mà kênh chết). */
    fun restartChannels(ctx: Context) {
        val app = ctx.applicationContext
        NavProbeHal.start(app); NavProbeBroadcast.start(app); NavProbeMedia.start(app)
    }

    fun expiresInMin(ctx: Context): Long {
        val until = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(K_UNTIL, 0L)
        return ((until - System.currentTimeMillis()) / 60000).coerceAtLeast(0)
    }

    /**
     * Ghi đầu file NGAY khi bật, KHÔNG đợi bắt được gì.
     * Nếu file chỉ sinh ra lúc có bản ghi đầu tiên thì "không có file" mang hai nghĩa khác hẳn nhau:
     * "app không phát gì" và "chưa cấp quyền, cả chuyến đi công cốc". Có header thì mở màn Máy dò là biết ngay.
     */
    private fun writeHeader(ctx: Context) {
        val sb = StringBuilder()
        sb.appendLine("################ ClusterNav · MÁY DÒ DẪN ĐƯỜNG ################")
        sb.appendLine("bật lúc : " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
        sb.appendLine("xe      : ${android.os.Build.MODEL} / ${android.os.Build.BRAND} · Android ${android.os.Build.VERSION.RELEASE}")
        sb.appendLine("app     : v" + (runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"))
        sb.appendLine()
        sb.appendLine("quyền đọc notification : " + if (notifGranted(ctx)) "ĐÃ BẬT ✓" else "*** CHƯA BẬT — kênh 1 câm ***")
        // Đọc thẳng Settings.Secure, KHÔNG dùng cờ RAM `NavProbeAccessibility.connected`: header được ghi ngay
        // sau selfGrant trong cùng luồng, hệ thống chưa kịp bind service ⇒ gần như MỌI file sẽ mở đầu bằng
        // phán quyết "CHƯA BẬT" sai. Đây đúng lỗi "kết luận từ dữ liệu chưa có" mà ClusterDiag vừa phải sửa.
        sb.appendLine("quyền đọc màn hình     : " + when {
            NavProbeAccessibility.connected -> "ĐÃ BẬT ✓ (service đã bind)"
            NavProbeArm.armed(ctx) -> "đã cấp, chờ hệ thống bind"
            else -> "*** CHƯA CẤP — kênh 3 câm (kênh quan trọng nhất) ***"
        })
        sb.appendLine()
        sb.appendLine("--- 5 kênh đang đo ---")
        sb.appendLine("  1 notification · 2 HAL cụm · 3 màn hình · 4 broadcast CP/AA · 5 MediaSession")
        sb.appendLine()
        sb.appendLine("--- app dẫn đường đang cài trên xe ---")
        runCatching {
            val pm = ctx.packageManager
            val i = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(i, 0)
                .map { it.activityInfo.packageName }.distinct()
                .filter { p -> NAVISH.any { p.contains(it, true) } }
                .sorted().forEach { sb.appendLine("  $it") }
        }
        sb.appendLine()
        append(ctx, sb.toString())
    }

    /** Quyền đọc notification đã cấp cho listener CỦA MÁY DÒ chưa (không phải listener chính của app). */
    fun notifGranted(ctx: Context): Boolean = runCatching {
        (android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: "")
            .split(":").any { it.contains(NavProbeNotificationListener::class.java.name) }
    }.getOrDefault(false)

    /** Chỉ để liệt kê trong header cho dễ đối chiếu — KHÔNG dùng để rẽ nhánh logic. */
    private val NAVISH = listOf("map", "nav", "waze", "vietmap", "vmap", "carplay", "gearhead", "amap", "tmap")

    // ───────────────────────── kênh 1: notification ─────────────────────────

    /**
     * Ghi TOÀN BỘ một notification. Cố ý dump MỌI key trong extras, không đoán trước tên trường:
     * mỗi app đặt tên một kiểu, mà mục đích của máy dò chính là để BIẾT chúng đặt tên gì.
     */
    fun recordNotification(ctx: Context, sbn: StatusBarNotification) {
        if (!isOn(ctx)) return
        val pkg = sbn.packageName
        if (SKIP_PREFIXES.any { pkg == it || pkg.startsWith("$it.") }) return
        val nth = seen.merge(pkg, 1, Int::plus) ?: 1
        // Vietmap/Waze dùng notification "ongoing", cập nhật mỗi giây khi đang dẫn. Ghi hết thì một app đủ sức
        // lấp kín trần trong ít phút, và app bật SAU không có lấy một dòng — đúng thứ cần so sánh thì lại mất.
        if (nth > VERBATIM_PER_PKG) {
            val now = System.currentTimeMillis()
            if (now - (lastWrite[pkg] ?: 0L) < THROTTLE_MS) return
            lastWrite[pkg] = now
        }
        runCatching {
            val n = sbn.notification ?: return
            val sb = StringBuilder()
            sb.append("=== ").append(stamp()).append(" · ").append(pkg).append(" #").append(nth)
                .append(if (sbn.isOngoing) " [ongoing]" else "").append('\n')
            val ex: Bundle = n.extras ?: Bundle()
            for (k in ex.keySet().sorted()) {
                val v = runCatching { ex.get(k) }.getOrNull() ?: continue
                val s = when (v) {
                    is CharSequence -> v.toString()
                    is Array<*> -> v.joinToString(" | ")
                    is Bundle -> "Bundle(${v.keySet().joinToString(",")})"
                    else -> v.toString()
                }
                if (s.isBlank()) continue
                sb.append("  ").append(k).append(" = ").append(s.take(300).replace('\n', '↵')).append('\n')
            }
            // RemoteViews: nhiều app dẫn đường vẽ layout riêng, chữ nằm trong đó chứ không ở extras
            listOf("contentView" to n.contentView, "bigContentView" to n.bigContentView,
                   "headsUpContentView" to n.headsUpContentView).forEach { (name, rv) ->
                if (rv != null) sb.append("  [$name] layoutId=").append(rv.layoutId).append('\n')
            }
            n.actions?.forEach { a -> sb.append("  [action] ").append(a.title).append('\n') }
            sb.append('\n')
            append(ctx, sb.toString())
        }
    }

    // ───────────────────────── kênh 3: màn hình ─────────────────────────

    /**
     * Ghi cây node đọc từ màn hình. Khử trùng lặp: thẻ rẽ đứng yên hàng chục giây, dump y hệt nhau chỉ tổ đầy file.
     * Chỉ ghi khi cây ĐỔI, kèm số lần bản trước lặp lại — con số đó cho biết nhịp cập nhật của app.
     */
    fun recordNodes(ctx: Context, pkg: String, tree: String) {
        if (!isOn(ctx)) return
        if (lastDump[pkg] == tree) { dumpRepeat.merge(pkg, 1, Int::plus); return }
        val rep = dumpRepeat.remove(pkg) ?: 0
        lastDump[pkg] = tree
        seen.merge("$pkg (màn hình)", 1, Int::plus)
        val sb = StringBuilder()
        sb.append("=== ").append(stamp()).append(" · ").append(pkg).append(" [MÀN HÌNH]")
        if (rep > 0) sb.append(" (bản trước lặp ").append(rep).append(" lần)")
        sb.append('\n').append(tree).append('\n')
        append(ctx, sb.toString())
    }

    // ───────────────────────── kênh 4: broadcast ─────────────────────────

    /**
     * Ghi một broadcast bắt được. Dump MỌI extra, không lọc trước — mục đích là để BIẾT chúng chứa gì.
     * Không khử trùng lặp: broadcast thưa, và biết nó bắn bao nhiêu lần cũng là dữ liệu (vd cắm/rút CarPlay).
     */
    fun recordBroadcast(ctx: Context, i: android.content.Intent) {
        if (!isOn(ctx)) return
        runCatching {
            val sb = StringBuilder("=== ").append(stamp()).append(" · [BROADCAST] ")
                .append(i.action ?: "(không action)").append('\n')
            i.extras?.let { ex ->
                for (k in ex.keySet().sorted()) {
                    val v = runCatching { ex.get(k) }.getOrNull() ?: continue
                    sb.append("  ").append(k).append(" = ").append(v.toString().take(250)).append('\n')
                }
            } ?: sb.append("  (không extra)\n")
            append(ctx, sb.append('\n').toString())
            noteSeen("broadcast " + (i.action ?: "?"))
        }
    }

    // ───────────────────────── tóm tắt cho màn hình ─────────────────────────

    /** Cho các kênh khác ghi nhận "đã bắt được X" mà không phải chạm vào map nội bộ. */
    fun noteSeen(key: String) { seen.merge(key, 1, Int::plus) }

    private val seen = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val lastWrite = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val lastDump = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val dumpRepeat = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun seenSummary(): String =
        if (seen.isEmpty()) "(chưa bắt được gì)"
        else seen.entries.sortedByDescending { it.value }.joinToString("\n") { "  ${it.key} — ${it.value}" }

    // ───────────────────────── ghi file ─────────────────────────

    private fun stamp() =
        java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())

    @Volatile private var cachedFile: java.io.File? = null

    /**
     * Thư mục RIÊNG của app (`filesDir`), KHÔNG phải `getExternalFilesDir`.
     * App khai `requestLegacyExternalStorage=true` + minSdk 29 ⇒ rào `Android/data` chưa siết, app khác trên xe
     * nhiều khả năng đọc được. File này chứa nguyên văn thông báo của mọi app — không để ngoài đó.
     * Nút CHIA SẺ vẫn chạy bình thường vì nó gửi nội dung qua EXTRA_TEXT, không gửi đường dẫn.
     */
    private fun dir(ctx: Context) = java.io.File(ctx.applicationContext.filesDir, "navprobe").apply { mkdirs() }

    private const val K_FILE = "cur_file"

    /** Đường dẫn file hiện tại — nhớ trong prefs để tiến trình sống lại vẫn GHI TIẾP đúng file của chuyến này. */
    private fun newFilePath(ctx: Context): java.io.File {
        val d = dir(ctx)
        d.listFiles()?.sortedBy { it.lastModified() }?.dropLast(4)?.forEach { runCatching { it.delete() } }
        val ver = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "x"
        val f = java.io.File(d, "navprobe_v${ver}_${System.currentTimeMillis()}.txt")
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(K_FILE, f.absolutePath).commit()
        cachedFile = f
        return f
    }

    private fun file(ctx: Context): java.io.File? = cachedFile ?: runCatching {
        val saved = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(K_FILE, null)
        val f = saved?.let { java.io.File(it) }
        if (f != null && f.parentFile == dir(ctx) && f.exists()) f.also { cachedFile = it } else newFilePath(ctx)
    }.getOrNull()

    /**
     * Cổng `isOn` đặt ở ĐÂY chứ không ở từng hàm record: NavProbeMedia gọi thẳng append và đã lọt qua vì thiếu
     * cổng riêng. Một chỗ chặn cho mọi kênh, kể cả kênh viết sau này.
     */
    @Synchronized
    fun append(ctx: Context, s: String) {
        if (!isOn(ctx)) return
        var f = file(ctx) ?: return
        if (f.length() > MAX_BYTES) {
            // ★ ĐẦY thì XOAY FILE, không im lặng bỏ. Bản cũ `return` trần trụi: chuyến đo 2 giờ chỉ có ~15 phút
            //   đầu nằm trong file, mà bộ đếm "đã bắt được" vẫn leo → người đọc tưởng đã ghi đủ.
            runCatching { f.appendText("\n### FILE ĐẦY (${MAX_BYTES / 1024 / 1024}MB) lúc ${stamp()} — sang file mới ###\n") }
            cachedFile = null
            newFilePath(ctx)
            f = file(ctx) ?: return
            runCatching { f.appendText("### tiếp nối từ file trước lúc ${stamp()} ###\n\n") }
        }
        runCatching { f.appendText(s) }
    }

    fun latestPath(ctx: Context): String? = file(ctx)?.takeIf { it.exists() }?.absolutePath

    fun latestFile(ctx: Context): java.io.File? = file(ctx)?.takeIf { it.exists() && it.length() > 0 }

    /**
     * Rút gọn file để gửi đi mà KHÔNG mất app nào.
     * Cắt thẳng N ký tự đầu là cách sai: app bật sau nằm cuối file và bị cắt sạch — mà so sánh giữa các app
     * mới là mục đích. Nên chia hạn ngạch ĐỀU cho từng gói. Hàm thuần → test được off-device.
     */
    fun digest(text: String, maxChars: Int = 100_000): String {
        val head = text.substringBefore("=== ")
        val blocks = text.removePrefix(head).split("\n=== ").filter { it.isNotBlank() }
        if (blocks.isEmpty()) return text.take(maxChars)
        val byPkg = LinkedHashMap<String, MutableList<String>>()
        for (b in blocks) {
            val pkg = b.substringAfter("· ", "?").substringBefore(" ").substringBefore("\n")
            byPkg.getOrPut(pkg) { mutableListOf() }.add("=== " + b.removePrefix("=== ").trimEnd() + "\n")
        }
        val budget = ((maxChars - head.length).coerceAtLeast(1000)) / byPkg.size
        val sb = StringBuilder(head)
        for ((pkg, list) in byPkg) {
            sb.append("\n######## ").append(pkg).append(" — ").append(list.size).append(" bản ghi ########\n")
            var used = 0
            var kept = 0
            for (b in list) {
                if (used + b.length > budget) break
                sb.append(b); used += b.length; kept++
            }
            if (kept < list.size) sb.append("…(bỏ bớt ").append(list.size - kept).append(" bản ghi giống nhau)\n")
        }
        return sb.toString()
    }

    private const val VERBATIM_PER_PKG = 40
    private const val THROTTLE_MS = 30_000L
    private const val MAX_BYTES = 3L * 1024 * 1024
}

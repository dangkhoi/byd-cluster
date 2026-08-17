package com.byd.clusternav

import com.byd.clusternav.carexec.LocalDeviceShell
import android.content.Context
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * KIỂM TRA & TẢI BẢN CẬP NHẬT từ GitHub — không cần server riêng, không thư viện ngoài.
 *
 * Cách hoạt động: repo public để sẵn APK release trong thư mục `apk/`. Hỏi GitHub Contents API xem thư mục
 * đó có file `ClusterNav-<ver>-release.apk` nào mới hơn bản đang cài không, rồi tải từ `download_url` và cài.
 *
 * CÀI qua dadb loopback (`dadb.install(file, "-r")`): app chạy trên đầu xe nối `localhost:5555` = uid shell,
 * đủ quyền `pm install`. Không cần REQUEST_INSTALL_PACKAGES, không cần người dùng bấm qua trình cài đặt —
 * đúng tinh thần self-service của app (adb ngoài không vào được khi cắm CarPlay/AA, §11). Cùng chữ ký nên
 * `-r` (reinstall) chạy được.
 *
 * ⚠ APK release PHẢI nằm trên nhánh [BRANCH]. Hiện team đẩy APK vào `apk/` trên nhánh làm việc; muốn tính năng
 * này thấy được thì bản phát hành phải có trên nhánh này (mặc định nhánh mặc định của repo).
 */
object UpdateChecker {

    private const val REPO = "dangkhoi/byd-cluster"
    /** Nhánh chứa APK phát hành. Để trống = nhánh mặc định của repo (main). */
    private const val BRANCH = "main"
    private val RE_APK = Regex("""ClusterNav-([0-9]+(?:\.[0-9]+)*)-release\.apk""")

    data class Result(
        val current: String,
        val latest: String?,
        val downloadUrl: String?,
        val hasUpdate: Boolean,
        val error: String?,
    )

    /** Sentinel khi KHÔNG đọc được version đang cài — dùng để FAIL-CLOSED (không mời update khi "mù"). */
    const val UNKNOWN = "?"

    /** Phiên bản đang cài (đọc từ máy — nhất quán với phần còn lại của app). */
    fun currentVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: UNKNOWN

    /**
     * Hỏi GitHub xem có bản mới không. CHẠY TRÊN LUỒNG NỀN (có I/O mạng) — đừng gọi trên main thread.
     */
    fun check(ctx: Context): Result {
        val cur = currentVersion(ctx)
        val ref = if (BRANCH.isBlank()) "" else "?ref=$BRANCH"
        val api = "https://api.github.com/repos/$REPO/contents/apk$ref"
        return runCatching {
            val body = httpGet(api) ?: return Result(cur, null, null, false, Lang.t("không đọc được phản hồi", "empty response"))
            val arr = JSONArray(body)
            var bestVer: String? = null
            var bestUrl: String? = null
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name")
                val m = RE_APK.matchEntire(name) ?: continue
                val ver = m.groupValues[1]
                if (bestVer == null || cmp(ver, bestVer) > 0) {
                    bestVer = ver; bestUrl = o.optString("download_url").takeIf { it.isNotBlank() }
                }
            }
            if (bestVer == null) Result(cur, null, null, false, Lang.t("không thấy APK trên nhánh $BRANCH", "no APK found on branch $BRANCH"))
            else Result(cur, bestVer, bestUrl, shouldOffer(bestVer, cur), null)
        }.getOrElse { Result(cur, null, null, false, Lang.t("lỗi mạng: ${it.message}", "network error: ${it.message}")) }
    }

    /**
     * Tải APK về thư mục riêng của app. Trả file, hoặc null nếu lỗi.
     * @param onProgress phần trăm 0..100 (hoặc -1 khi không biết tổng cỡ)
     */
    fun download(ctx: Context, url: String, onProgress: (Int) -> Unit): File? = runCatching {
        val dir = File(ctx.applicationContext.filesDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { runCatching { it.delete() } }   // chỉ giữ 1 bản đang tải
        val out = File(dir, url.substringAfterLast('/').ifBlank { "update.apk" })
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 60000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ClusterNav-Updater")
        }
        conn.inputStream.use { input ->
            val total = conn.contentLength
            out.outputStream().use { output ->
                val buf = ByteArray(64 * 1024); var read = 0L; var n: Int
                while (input.read(buf).also { n = it } > 0) {
                    output.write(buf, 0, n); read += n
                    onProgress(if (total > 0) ((read * 100) / total).toInt() else -1)
                }
            }
        }
        conn.disconnect()
        out.takeIf { it.length() > 0 }
    }.getOrNull()

    /**
     * Cài APK qua dadb loopback. Trả chuỗi kết quả để hiển thị.
     * `-r` = reinstall giữ dữ liệu; cùng chữ ký nên không cần gỡ trước.
     *
     * Relaunch: một `-r` THÀNH CÔNG kill process này ngay → không code nào sau đó chạy. Nên ta HẸN GIỜ
     * mở lại Home TRƯỚC khi cài (lúc app còn foreground); nếu cài thất bại thì huỷ hẹn. Xem [UpdateRelaunch].
     *
     * Kết quả THẬT: [LocalDeviceShell.installApk] trả `false` khi dadb báo lỗi (vd khác chữ ký:
     * debug↔release, hoặc downgrade) — trước đây hàm này BỎ QUA giá trị đó và luôn báo "đã cài", nên
     * một lần cài fail vẫn hiện "sẽ tự khởi động lại" rồi đứng im. Giờ báo đúng thành/bại.
     */
    fun install(ctx: Context, apk: File): String {
        val app = ctx.applicationContext
        // LOOP-FIX (1.31, issue #A): quyết định cài theo versionCODE THẬT của APK vs bản đang cài — KHÔNG theo
        // tên file. check() dùng versionName (tên file) vốn có thể lệch (build lỗi / getPackageInfo="?"), và
        // `pm install -r` của EQUAL version VẪN thành công → thiếu gác này thì tên/versionName sai gây cài lại
        // vô hạn. getPackageArchiveInfo đọc vc file đã tải không cần cài; so với vc đang cài.
        val instVc = installedVersionCode(app)
        val apkVc = apkVersionCode(app, apk)
        if (!shouldInstall(apkVc, instVc)) {
            return Lang.t("đã là bản mới nhất (vc $instVc) — bỏ qua cài lại", "already newest (vc $instVc) — skip reinstall")
        }
        UpdateRelaunch.schedule(app) // arm BEFORE install: a successful -r kills us mid-call.
        val ok = LocalDeviceShell.installApk(AdbKeys.ensure(app), apk, "-r")
        return if (ok) {
            Lang.t("đã cài — đang mở lại…", "installed — reopening…")
        } else {
            UpdateRelaunch.cancel(app) // nothing was replaced → don't relaunch.
            Lang.t(
                "cài thất bại (khác chữ ký/phiên bản?). APK đã tải ở: ${apk.absolutePath}",
                "install failed (signature/version mismatch?). APK saved at: ${apk.absolutePath}",
            )
        }
    }

    // ── nội bộ ──

    private fun httpGet(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 20000
            setRequestProperty("User-Agent", "ClusterNav-Updater")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        return try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }

    /** So sánh hai chuỗi version dạng "0.56" / "1.2.3". >0 nếu a mới hơn b. */
    fun cmp(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = (pa.getOrElse(i) { 0 }) - (pb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    /** Quyết định CÓ MỜI update không (thuần, test được). FAIL-CLOSED: không đọc được version cài (UNKNOWN)
     *  → KHÔNG mời (tránh vòng lặp cài lại khi getPackageInfo trả rỗng). */
    fun shouldOffer(latest: String, current: String): Boolean =
        current != UNKNOWN && cmp(latest, current) > 0

    /** Quyết định CÓ CÀI file đã tải không (thuần, test được). Chặn khi APK KHÔNG mới hơn bản đang cài theo
     *  versionCODE (int authoritative) — đóng vòng lặp "cài lại cùng version". Đọc được cả hai và apkVc <= instVc
     *  → false. Không đọc được (-1) → fail-OPEN (check() đã gác trước, không chặn nhầm update thật). */
    fun shouldInstall(apkVersionCode: Long, installedVersionCode: Long): Boolean =
        !(apkVersionCode >= 0 && installedVersionCode >= 0 && apkVersionCode <= installedVersionCode)

    /** versionCode ĐANG CÀI (authoritative). -1 nếu không đọc được. minSdk 29 → longVersionCode luôn có. */
    fun installedVersionCode(ctx: Context): Long = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
    }.getOrDefault(-1L)

    /** versionCode của FILE apk (không cần cài) qua getPackageArchiveInfo. -1 nếu không đọc được. */
    fun apkVersionCode(ctx: Context, apk: File): Long = runCatching {
        ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.longVersionCode ?: -1L
    }.getOrDefault(-1L)
}

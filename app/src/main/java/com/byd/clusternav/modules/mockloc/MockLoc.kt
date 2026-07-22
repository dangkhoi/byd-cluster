package com.byd.clusternav.modules.mockloc

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock

/**
 * Helper bơm MOCK LOCATION no-root (cho DeadReckon dùng + MockLocModule test riêng). Dùng
 * LocationManager.addTestProvider (KHÔNG dùng FLP.setMockMode — nó tắt provider thật bên dưới).
 *
 * CHỈ mock GPS_PROVIDER (KHÔNG mock NETWORK) — mock ít provider nhất có thể.
 * ⚠ ĐÍNH CHÍNH (v0.36, verify source AOSP 10): chỉ cần cài test provider lên GPS_PROVIDER là GNSS THẬT ĐÃ TẮT —
 * `addTestProvider` gỡ provider thật khỏi mProviders → LMS đẩy ProviderRequest RỖNG xuống GnssLocationProvider →
 * `stopNavigating()` → `native_stop()`. Nên **cả `usedInFix` LẪN `satelliteCount` đều ĐÓNG BĂNG** suốt phiên mock,
 * KHÔNG được dùng làm tín hiệu sống để quyết định gì trong lúc đang mock. Cách DUY NHẤT biết GPS đã về là
 * `removeTestProvider` (peek) rồi chờ fix thật.
 *
 * Điều kiện no-root: chọn ClusterNav làm "mock location app" trong Developer Options (1 lần). Chưa chọn →
 * addTestProvider ném SecurityException (start() trả message). XOÁ: xoá modules/mockloc/ + dòng Registry
 * (DeadReckon phụ thuộc helper này — xoá MockLoc thì xoá luôn DeadReckon).
 */
object MockLoc {
    private val PROVS = listOf(LocationManager.GPS_PROVIDER)
    @Volatile var active = false; private set

    private fun lm(ctx: Context) = ctx.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    /** Đã được chọn làm "mock location app" chưa — CHỈ ĐỌC, không đụng gì (dùng cho selfTest). */
    fun isMockAppGranted(ctx: Context): Boolean = runCatching {
        val app = ctx.applicationContext
        val aom = app.getSystemService(android.app.AppOpsManager::class.java)
        val mode = aom.unsafeCheckOpNoThrow("android:mock_location",
            android.os.Process.myUid(), app.packageName)
        mode == android.app.AppOpsManager.MODE_ALLOWED
    }.getOrElse {
        // đời máy cũ: rơi về Settings.Secure (giá trị là tên gói được chọn)
        runCatching {
            android.provider.Settings.Secure.getString(ctx.contentResolver, "mock_location") == ctx.packageName
        }.getOrDefault(false)
    }

    /**
     * ★★ W1-2 — DẤU BỀN "ĐANG MOCK". Ghi bằng `commit()` (đồng bộ) TRƯỚC khi `addTestProvider`, xoá SAU khi
     * `removeTestProvider`. Nhờ nó, một tiến trình HOÀN TOÀN MỚI (sau force-stop / crash native, những trường hợp
     * mà `onDestroy` không bao giờ chạy) vẫn biết là có provider mồ côi cần dọn — xem [sweepIfOrphaned].
     */
    private fun marker(ctx: Context) = ctx.applicationContext.getSharedPreferences("mockloc", Context.MODE_PRIVATE)
    private fun setMarker(ctx: Context, on: Boolean) =
        runCatching { marker(ctx).edit().putBoolean(K_MOCK_ON, on).commit() }

    /**
     * Dọn test provider MỒ CÔI. Gọi từ watchdog alarm (receiver khai trong manifest → chạy được cả khi tiến
     * trình đã chết). CHỈ dọn khi: có dấu bền, mà DeadReckon KHÔNG đang chạy-và-mock — tức chắc chắn là rác.
     * ⚠ RỦI RO CAO nếu sai: gỡ nhầm mock đang phục vụ giữa hầm. Vì thế đòi [MISS_TO_SWEEP] lần liên tiếp
     * quan sát thấy mồ côi rồi mới ra tay.
     */
    @Volatile private var orphanMisses = 0
    fun sweepIfOrphaned(ctx: Context) {
        if (!runCatching { marker(ctx).getBoolean(K_MOCK_ON, false) }.getOrDefault(false)) { orphanMisses = 0; return }
        val live = com.byd.clusternav.modules.deadreckon.DeadReckonState.running &&
            com.byd.clusternav.modules.deadreckon.DeadReckonState.mocking
        if (live || active) { orphanMisses = 0; return }
        if (++orphanMisses < MISS_TO_SWEEP) return
        orphanMisses = 0
        android.util.Log.w("MockLoc", "phát hiện test provider mồ côi (dấu bền còn, DR không chạy) → dọn")
        stop(ctx)
    }

    /** Bật mock. Trả "" nếu OK, hoặc message lỗi (chưa chọn mock-app / thiếu quyền). */
    fun start(ctx: Context): String {
        setMarker(ctx, true)          // ★ ghi dấu TRƯỚC khi đổi state hệ thống (chết giữa chừng vẫn dọn được)
        val m = lm(ctx)
        for (p in PROVS) {
            try {
                runCatching { m.removeTestProvider(p) }
                m.addTestProvider(p, false, false, false, false, true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
                m.setTestProviderEnabled(p, true)
            } catch (e: SecurityException) {
                selfGrant(ctx)   // #1: chưa có quyền mock → kích self-grant qua dadb (retry lần tick sau sẽ ăn)
                return "chưa chọn ClusterNav làm 'mock location app' (Developer Options) / thiếu quyền: ${e.message}"
            } catch (_: Exception) { /* NETWORK đôi khi không add được — bỏ qua, GPS đủ */ }
        }
        active = true
        return ""
    }

    /**
     * Đẩy 1 fix. accuracy nhỏ để FLP tin; elapsedRealtimeNanos BẮT BUỘC trên API29 (thiếu là bị drop).
     * [moving] = false (COLD_SEED đứng yên) → KHÔNG set bearing/speed: consumer (Vietmap/GMaps) nội suy giữa 2 fix,
     * nếu ta báo "đang chạy 40km/h" mà toạ độ đứng im thì bản đồ trượt tới rồi GIẬT NGƯỢC về, 5 lần/giây.
     */
    fun push(
        ctx: Context, lat: Double, lon: Double, bearingDeg: Double, speedMps: Double,
        accuracy: Float = 6f, moving: Boolean = true,
    ) {
        if (!active) return
        val m = lm(ctx)
        for (p in PROVS) {
            val loc = Location(p).apply {
                latitude = lat; longitude = lon; altitude = 10.0; this.accuracy = accuracy
                time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                if (moving) {
                    bearing = ((bearingDeg % 360 + 360) % 360).toFloat()
                    speed = speedMps.coerceAtLeast(0.0).toFloat()
                    if (Build.VERSION.SDK_INT >= 26) { bearingAccuracyDegrees = 5f; speedAccuracyMetersPerSecond = 1f }
                } else {
                    speed = 0f   // vị trí đóng băng → tốc độ PHẢI là 0, không thì consumer ngoại suy rồi giật ngược
                    if (Build.VERSION.SDK_INT >= 26) speedAccuracyMetersPerSecond = 0.5f
                }
                if (Build.VERSION.SDK_INT >= 26) verticalAccuracyMeters = 5f
            }
            runCatching { m.setTestProviderLocation(p, loc) }
        }
    }

    /**
     * ★ ĐỪNG THÊM pause()/resume() BẰNG setTestProviderEnabled — ĐÃ THỬ VÀ SAI (v0.36, bắt được lúc review).
     *
     * `addTestProvider` GỠ provider GPS thật ra khỏi `mProviders` và cất vào `mRealProviders`
     * (LocationManagerService:3521); **CHỈ `removeTestProvider` mới lắp lại** (:3554-3579 "reinstate real
     * provider if available"). `setTestProviderEnabled(false)` chỉ lật cờ enabled của mock — provider thật
     * VẪN không có trong `mProviders`, mà `handleLocationChangedLocked` mở đầu bằng
     * `if (!mProviders.contains(provider)) return` → fix thật KHÔNG BAO GIỜ tới. Thêm nữa, lúc addTestProvider
     * đã đẩy ProviderRequest RỖNG xuống GNSS thật → engine tắt → GnssStatus im luôn.
     * ⇒ Peek bằng pause() là MÙ HOÀN TOÀN: `realBack` không bao giờ đúng, `usedInFix` đứng 0 → DR không có
     *   đường thoát; COLD_SEED lại được miễn failsafe → GPS_PROVIDER của CẢ MÁY bị ghim ở vị trí đóng băng
     *   suốt chuyến. Tệ hơn hẳn cái bug ban đầu.
     * ⇒ Peek BẮT BUỘC dùng [stop] (removeTestProvider). Giảm tần suất broadcast bằng cách peek THƯA hơn +
     *   bỏ qua peek khi còn chắc chắn trong hầm — xem PEEK_INTERVAL_MS ở DeadReckonService.
     */
    /** Gỡ HẲN test provider (kết thúc phiên DR). Chỉ dùng khi thực sự xong — xem [pause] cho chu kỳ peek. */
    fun stop(ctx: Context) {
        val m = lm(ctx)
        for (p in PROVS) runCatching { m.removeTestProvider(p) }
        active = false
        setMarker(ctx, false)         // ★ xoá dấu SAU khi đã gỡ thật
    }

    @Volatile private var granted = false        // đã cấp mock thành công phiên này → khỏi làm lại
    @Volatile private var lastGrantMs = 0L        // cooldown chống spam thread khi popup Allow chưa bấm
    // ★ v0.36: BACKOFF LUỸ THỪA. Trước đây cooldown cố định 8s → chưa cấp quyền mock (rất hay gặp sau cài đè) là
    //   máy mở 1 kết nối ADB + spawn shell MỖI 8 GIÂY, VĨNH VIỄN. Giờ 8s → 16s → … → trần 10 phút.
    @Volatile private var grantFails = 0
    @Volatile private var inFlight = false        // #11: đang có 1 thread grant chạy → chặn thread chồng (Dadb.create có thể treo lâu hơn cooldown)

    /**
     * TỰ CẤP quyền mock (`appops set ... mock_location allow`) qua embedded-dadb — vì `mock_location` RESET về
     * DENY sau MỖI lần cài đè app, mà app KHÔNG tự xin runtime được (nó là setting "Select mock location app"
     * trong Developer Options, không phải runtime perm). Chạy qua dadb localhost:5555 (uid shell) — CÙNG đường +
     * CÙNG key `adb.key` mà [com.byd.clusternav.NavConnect] đã dùng tự-heal nav listener (đã được user Allow 1 lần).
     * CHẠY NỀN (blocking I/O). Gọi lúc DR start (chủ động) + lúc mock.start lỗi (retry). Cooldown 8s + cờ [granted]
     * → không spam thread nếu popup chưa bấm; user vẫn bật tay được trong Developer Options.
     */
    fun selfGrant(ctx: Context) {
        if (granted) return
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            val wait = (GRANT_COOLDOWN_MS shl grantFails.coerceAtMost(7)).coerceAtMost(GRANT_MAX_BACKOFF_MS)
            if (granted || inFlight || now - lastGrantMs < wait) return
            inFlight = true; lastGrantMs = now
        }
        val app = ctx.applicationContext
        Thread {
            try {
                runCatching {
                    val kp = com.byd.clusternav.AdbKeys.ensure(app)   // key CHUNG, sinh nguyên tử + khóa chung (chống đua NavConnect)
                    dadb.Dadb.create("localhost", 5555, kp).use { adb ->
                        val r = adb.shell("appops set ${app.packageName} android:mock_location allow")
                        // CHỈ latch granted khi lệnh THẬT SỰ chạy OK (exitCode 0) — shell() KHÔNG throw khi appops fail;
                        // latch mù sẽ khoá vĩnh viễn retry nếu 1 lần fail thoáng qua.
                        if (r.exitCode == 0) {
                            granted = true; grantFails = 0
                            android.util.Log.i("MockLoc", "self-grant mock_location qua dadb OK")
                        } else {
                            grantFails++
                            android.util.Log.e("MockLoc", "self-grant appops rc=${r.exitCode} err=${r.errorOutput} → sẽ retry (lần $grantFails)")
                        }
                    }
                }.onFailure { grantFails++; android.util.Log.e("MockLoc", "self-grant mock lỗi (popup Allow USB chưa bấm?): ${it.message}") }
            } finally { inFlight = false }   // #11: luôn nhả (kể cả Dadb.create treo rồi bị hủy) để lần sau retry được
        }.start()
    }

    private const val K_MOCK_ON = "mock_on"
    /** Số lần watchdog liên tiếp phải thấy "mồ côi" trước khi dám gỡ — chống gỡ nhầm mock đang phục vụ. */
    private const val MISS_TO_SWEEP = 2
    private const val GRANT_COOLDOWN_MS = 8000L
    private const val GRANT_MAX_BACKOFF_MS = 600000L   // trần 10 phút
}

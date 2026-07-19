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
 * CHỈ mock GPS_PROVIDER (KHÔNG mock NETWORK): mock cả 2 sẽ làm Android đóng băng raw GnssStatus callback →
 * DeadReckon không phát hiện được GPS quay lại → kẹt mock vĩnh viễn. Mock mình GPS thì FLP vẫn surface mock cho
 * GMaps, mà raw GNSS (đếm vệ tinh) vẫn chạy để biết khi nào ra hầm. (Trong hầm NETWORK cũng mất nên không snap.)
 *
 * Điều kiện no-root: chọn ClusterNav làm "mock location app" trong Developer Options (1 lần). Chưa chọn →
 * addTestProvider ném SecurityException (start() trả message). XOÁ: xoá modules/mockloc/ + dòng Registry
 * (DeadReckon phụ thuộc helper này — xoá MockLoc thì xoá luôn DeadReckon).
 */
object MockLoc {
    private val PROVS = listOf(LocationManager.GPS_PROVIDER)
    @Volatile var active = false; private set

    private fun lm(ctx: Context) = ctx.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    /** Bật mock. Trả "" nếu OK, hoặc message lỗi (chưa chọn mock-app / thiếu quyền). */
    fun start(ctx: Context): String {
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

    /** Đẩy 1 fix. accuracy nhỏ để FLP tin; elapsedRealtimeNanos BẮT BUỘC trên API29 (thiếu là bị drop). */
    fun push(ctx: Context, lat: Double, lon: Double, bearingDeg: Double, speedMps: Double, accuracy: Float = 6f) {
        if (!active) return
        val m = lm(ctx)
        for (p in PROVS) {
            val loc = Location(p).apply {
                latitude = lat; longitude = lon; altitude = 10.0; this.accuracy = accuracy
                time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                bearing = ((bearingDeg % 360 + 360) % 360).toFloat(); speed = speedMps.coerceAtLeast(0.0).toFloat()
                if (Build.VERSION.SDK_INT >= 26) { bearingAccuracyDegrees = 5f; speedAccuracyMetersPerSecond = 1f; verticalAccuracyMeters = 5f }
            }
            runCatching { m.setTestProviderLocation(p, loc) }
        }
    }

    fun stop(ctx: Context) {
        val m = lm(ctx)
        for (p in PROVS) runCatching { m.removeTestProvider(p) }
        active = false
    }

    @Volatile private var granted = false        // đã cấp mock thành công phiên này → khỏi làm lại
    @Volatile private var lastGrantMs = 0L        // cooldown chống spam thread khi popup Allow chưa bấm
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
            if (granted || inFlight || now - lastGrantMs < GRANT_COOLDOWN_MS) return
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
                            granted = true
                            android.util.Log.i("MockLoc", "self-grant mock_location qua dadb OK")
                        } else {
                            android.util.Log.e("MockLoc", "self-grant appops rc=${r.exitCode} err=${r.errorOutput} → sẽ retry")
                        }
                    }
                }.onFailure { android.util.Log.e("MockLoc", "self-grant mock lỗi (popup Allow USB chưa bấm?): ${it.message}") }
            } finally { inFlight = false }   // #11: luôn nhả (kể cả Dadb.create treo rồi bị hủy) để lần sau retry được
        }.start()
    }

    private const val GRANT_COOLDOWN_MS = 8000L
}

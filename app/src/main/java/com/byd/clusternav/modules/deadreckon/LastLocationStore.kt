package com.byd.clusternav.modules.deadreckon

import android.content.Context
import kotlin.math.abs

/**
 * LƯU vị trí tốt nhất gần nhất (fix thật HOẶC vị trí dead-reckoning nội suy) vào SharedPreferences —
 * SỐNG QUA TẮT MÁY. Mục đích: mở máy trong hầm/bãi ngầm KHÔNG có GPS → không có vị trí hiện tại →
 * lấy vị trí LƯU lần trước làm seed (xe vẫn đỗ đúng chỗ đó) để GMaps có toạ độ + cho dead-reckoning
 * chỗ bắt đầu. Khi GPS thật quay lại thì DeadReckonService tự nhả mock, vị trí tự chỉnh.
 *
 * Dùng wall-clock (System.currentTimeMillis) làm mốc thời gian để so tuổi QUA REBOOT (elapsedRealtime
 * reset khi khởi động lại máy, không so được). Double lưu qua raw-bits (Long) để giữ đủ chính xác toạ độ.
 */
object LastLocationStore {
    private const val FILE = "clusternav_lastloc"
    private const val K_LAT = "lat"
    private const val K_LON = "lon"
    private const val K_BRG = "brg"          // bearing độ; -1 = chưa biết
    private const val K_AT = "at_wall"       // wall-clock ms lúc lưu
    private const val K_VALID = "valid"

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Vị trí lưu + tuổi (ms) tính từ [nowWallMs]. */
    data class Saved(val lat: Double, val lon: Double, val bearingDeg: Double, val ageMs: Long)

    /** Lưu vị trí. Bỏ qua toạ độ null-island (0,0) / NaN để tránh seed bậy. */
    fun save(ctx: Context, lat: Double, lon: Double, bearingDeg: Double, wallMs: Long) {
        if (lat.isNaN() || lon.isNaN()) return
        if (abs(lat) < 1e-4 && abs(lon) < 1e-4) return
        sp(ctx).edit()
            .putLong(K_LAT, lat.toRawBits())
            .putLong(K_LON, lon.toRawBits())
            .putFloat(K_BRG, bearingDeg.toFloat())
            .putLong(K_AT, wallMs)
            .putBoolean(K_VALID, true)
            .apply()
    }

    /** Đọc vị trí lưu (null nếu chưa từng lưu). [nowWallMs] để tính tuổi. */
    fun load(ctx: Context, nowWallMs: Long): Saved? {
        val p = sp(ctx)
        if (!p.getBoolean(K_VALID, false)) return null
        val lat = Double.fromBits(p.getLong(K_LAT, 0L))
        val lon = Double.fromBits(p.getLong(K_LON, 0L))
        if (lat.isNaN() || lon.isNaN()) return null
        if (abs(lat) < 1e-4 && abs(lon) < 1e-4) return null
        val brg = p.getFloat(K_BRG, -1f).toDouble()
        val at = p.getLong(K_AT, 0L)
        return Saved(lat, lon, brg, nowWallMs - at)
    }
}

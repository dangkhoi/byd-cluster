package com.byd.clusternav

import android.content.Context
import com.byd.clusternav.contracts.SpeedLimitSource

/** Lưu lựa chọn người dùng (bật/tắt đẩy cụm + chế độ chọn nguồn). Đọc trực tiếp trong listener. */
object Prefs {
    // Giá trị thật nằm ở :core (NavSourceMode) để bộ quyết định không phải phụ thuộc Android chỉ vì hai
    // con số. Giữ alias ở đây nên caller cũ không đổi và dữ liệu đã lưu vẫn đọc đúng.
    const val AUTO = com.byd.clusternav.navigation.NavSourceMode.AUTO
    const val PREFER_GMAPS = com.byd.clusternav.navigation.NavSourceMode.PREFER_GMAPS
    const val PREFER_WAZE = com.byd.clusternav.navigation.NavSourceMode.PREFER_WAZE
    const val PREFER_VIETMAP = com.byd.clusternav.navigation.NavSourceMode.PREFER_VIETMAP

    private const val FILE = "clusternav_prefs"
    private const val K_ENABLED = "enabled"
    private const val K_SOURCE = "source_mode"
    private const val K_SPEED_SOURCE = "speed_source"
    private const val K_MARQUEE = "marquee"

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = sp(ctx).getBoolean(K_ENABLED, true)
    fun setEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(K_ENABLED, v).apply()

    fun sourceMode(ctx: Context): Int = sp(ctx).getInt(K_SOURCE, AUTO)
    fun setSourceMode(ctx: Context, v: Int) = sp(ctx).edit().putInt(K_SOURCE, v).apply()

    fun speedSource(ctx: Context): Int = sp(ctx).getInt(K_SPEED_SOURCE, com.byd.clusternav.navigation.NavSourceMode.SPEED_VIETMAP)
    fun setSpeedSource(ctx: Context, v: Int) = sp(ctx).edit().putInt(K_SPEED_SOURCE, v).apply()
    fun speedLimitSource(ctx: Context): SpeedLimitSource = when (speedSource(ctx)) {
        com.byd.clusternav.navigation.NavSourceMode.SPEED_WAZE -> SpeedLimitSource.WAZE
        else -> SpeedLimitSource.VIETMAP
    }

    // Nav-on-cluster: op 39 "simple navigation" (Giữa + ETA) là chế độ DUY NHẤT (owner chốt 2026-08-12).
    // Bỏ hẳn biến thể "nhỏ/ở trên" (không dò được opcode trên xe) + nút chọn mode + nút test trên UI.

    // ★ 2026-08-12 (owner): MẶC ĐỊNH TẮT marquee — tên đường trên cụm/op39 hiển thị ĐỨNG IM (không chạy
    // phải→trái). Tên dài được VIẾT TẮT (fitRoadName: "Nguyễn Hữu Cảnh"→"NHC") cho vừa ô ~7 ký tự thay vì
    // cuộn cửa sổ theo scrollTick. Firmware cụm vốn hard-cut ~7 ký tự, không tự marquee → gửi tĩnh là đủ. Giữ toggle.
    fun marquee(ctx: Context): Boolean = sp(ctx).getBoolean(K_MARQUEE, false)   // false = tên đường TĨNH (rút gọn)
    fun setMarquee(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(K_MARQUEE, v).apply()

    // Nav-on-cluster DISPLAY MODE — ghi SET_NAVI_SCREEN_STATUS_SET (0x4C10E015 · BYDAutoSettingDevice), đúng
    // menu OEM "Đơn giản / Màn hình nhỏ / Toàn màn hình / OFF" (mở khoá 2026-08-13 qua BydHal). op39 ch1000 KHÔNG
    // đổi được cái này (no-op trên xe). NavigationHudOwner đọc pref này mỗi frame → selector áp dụng LIVE.
    // ⚠️ value↔menu CHƯA map chắc trên xe: navopen=3 (rc=0, ứng viên "Toàn màn hình"); "Đơn giản" đoán=1 — dò trên xe.
    // Default = FULL(3) = value đã-proven rc=0 (ít nhất hiện nav ở GIỮA thay vì dải nhỏ ở đỉnh).
    const val NAV_SCREEN_OFF = 0
    const val NAV_SCREEN_SIMPLE = 1       // "Đơn giản" (Giữa + ETA) — GUESS, verify on-car
    const val NAV_SCREEN_SMALL = 2        // "Màn hình nhỏ" (dải ở đỉnh) — GUESS
    const val NAV_SCREEN_FULL = 3         // "Toàn màn hình" — navopen/AmapService dùng 3 (rc=0)
    private const val K_NAV_SCREEN_MODE = "nav_cluster_screen_mode"
    fun navClusterScreenMode(ctx: Context): Int = sp(ctx).getInt(K_NAV_SCREEN_MODE, NAV_SCREEN_FULL)
    fun setNavClusterScreenMode(ctx: Context, v: Int) = sp(ctx).edit().putInt(K_NAV_SCREEN_MODE, v).apply()

    // Cluster-lane output is independently switchable while the shared Navigation session/HUD remain active.
    fun lane(ctx: Context): Boolean = sp(ctx).getBoolean("lane", true)
    fun setLane(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("lane", v).apply()

    // ★ 2026-08-12 (owner "1B"): MẶC ĐỊNH BẬT lại "tự bù theo tốc độ". Noti GMaps thưa → gửi RAW làm cự ly đứng im
    // rồi nhảy khi tới ngã rẽ/điểm đến ("trễ"). Bật nội suy: TurnDistanceInterpolator trừ dần cự ly theo TỐC ĐỘ XE
    // thật (SpeedProvider) mỗi nhịp tim 400ms; bộ đọc màn Maps (accBooster → refine()) kéo mốc về số thật. Interpolator
    // đã bảo thủ (FACTOR 0.95, slew-limit 2 chiều, dừng→giữ số, maneuver→snap) nên không tái diễn "số nhảy tán loạn"
    // của bản 2026-07-13. Giữ toggle để TẮT nếu overlay cụm tự animate rồi đánh nhau (cần verify trên xe).
    fun interpolate(ctx: Context): Boolean = sp(ctx).getBoolean("interpolate", true)
    fun setInterpolate(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("interpolate", v).apply()

    // ★ HUD kính lái: T7 chỉ feeds request/output lifecycle vào HudMirrorController UNKNOWN/no-op.
    // Mặc định TẮT; không có direct HAL content write hoặc physical-OFF ownership in production.
    fun hud(ctx: Context): Boolean = sp(ctx).getBoolean("hud", false)
    fun setHud(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("hud", v).apply()

    // Booster đọc UI GMaps trên màn (accessibility) -> tinh chỉnh cự ly tới rẽ chính xác hơn noti.
    // Chỉ chạy khi GMaps đang HIỆN trên màn; bị app khác (YouTube) che -> tự câm, nội suy gánh tiếp.
    fun accBooster(ctx: Context): Boolean = sp(ctx).getBoolean("acc_booster", true)
    fun setAccBooster(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("acc_booster", v).apply()

    // Tự hiện NÚT NỔI (bong bóng chiếu) khi mở app / khởi động máy. Mặc định BẬT (user: "luôn hiện bubble").
    // Cần quyền overlay 1 lần; chưa cấp thì service tự báo. User tắt → lưu false.
    fun bubbleAuto(ctx: Context): Boolean = sp(ctx).getBoolean("bubble_auto", true)
    fun setBubbleAuto(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("bubble_auto", v).apply()

    // "Mượt UI head-unit": set 3 animation scale = 0.5 GLOBAL qua dadb lúc mở app (tweak hội BYD hay xài). Mặc định BẬT.
    // KHÔNG phải tăng tốc CPU — chỉ rút ngắn animation cho snappy. Tắt → app set lại 1.0.
    fun animOpt(ctx: Context): Boolean = sp(ctx).getBoolean("anim_opt", true)
    fun setAnimOpt(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("anim_opt", v).apply()

    // Toggle theo module (key namespaced "mod_" — không thể đụng các key lõi ở trên). Mặc định TẮT
    // (experiment phải bật tay). Key mồ côi sau khi xoá module = dead data vô hại, không cần dọn.
    fun moduleEnabled(ctx: Context, title: String): Boolean =
        sp(ctx).getBoolean("mod_" + title.hashCode(), false)
    fun setModuleEnabled(ctx: Context, title: String, v: Boolean) =
        sp(ctx).edit().putBoolean("mod_" + title.hashCode(), v).apply()
}

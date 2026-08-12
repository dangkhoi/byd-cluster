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

    // Kiểu hiển thị dẫn đường trên CỤM (nav-only mode). Hai giá trị:
    //   CENTER_ETA ("center_eta", MẶC ĐỊNH) — overlay "simple navigation" ở giữa cụm kèm ETA sống, bật bằng op 39.
    //   SMALL_TOP  ("small_top")            — chỉ để broadcast mặc định vẽ (KHÔNG assert op 39). Xem
    //                                          ClusterNavLaneWidget: opcode cho biến thể "nhỏ/ở trên" chưa
    //                                          dò được trên xe nên tạm để broadcast tự lo (provisional).
    const val NAV_MODE_CENTER_ETA = "center_eta"
    const val NAV_MODE_SMALL_TOP = "small_top"
    private const val K_NAV_CLUSTER_MODE = "nav_cluster_mode"
    fun navClusterMode(ctx: Context): String =
        sp(ctx).getString(K_NAV_CLUSTER_MODE, NAV_MODE_CENTER_ETA) ?: NAV_MODE_CENTER_ETA
    fun setNavClusterMode(ctx: Context, mode: String) =
        sp(ctx).edit().putString(K_NAV_CLUSTER_MODE, mode).apply()

    fun marquee(ctx: Context): Boolean = sp(ctx).getBoolean(K_MARQUEE, true)   // cuộn tên đường dài
    fun setMarquee(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(K_MARQUEE, v).apply()

    // ★ 2026-07-13: MẶC ĐỊNH TẮT. RE DashCast/OpenBYD: HỌ KHÔNG nội suy — gửi cự ly RAW từ noti, để FIRMWARE cụm tự
    // animate đếm-lùi. Nội suy app-side (baseline−traveled theo tốc độ mỗi 400ms) ĐÁNH NHAU với firmware → "số nhảy

    // Cluster-lane output is independently switchable while the shared Navigation session/HUD remain active.
    fun lane(ctx: Context): Boolean = sp(ctx).getBoolean("lane", true)
    fun setLane(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("lane", v).apply()
    // tán loạn". Tắt = gửi raw → mượt như họ. Giữ toggle cho ai muốn thử lại.
    fun interpolate(ctx: Context): Boolean = sp(ctx).getBoolean("interpolate", false)
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

package com.byd.clusternav

import android.content.Context

/** Lưu lựa chọn người dùng (bật/tắt đẩy cụm + chế độ chọn nguồn). Đọc trực tiếp trong listener. */
object Prefs {
    const val AUTO = 0
    const val PREFER_GMAPS = 2

    private const val FILE = "clusternav_prefs"
    private const val K_ENABLED = "enabled"
    private const val K_SOURCE = "source_mode"
    private const val K_MARQUEE = "marquee"

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = sp(ctx).getBoolean(K_ENABLED, true)
    fun setEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(K_ENABLED, v).apply()

    fun sourceMode(ctx: Context): Int = sp(ctx).getInt(K_SOURCE, AUTO)
    fun setSourceMode(ctx: Context, v: Int) = sp(ctx).edit().putInt(K_SOURCE, v).apply()

    fun marquee(ctx: Context): Boolean = sp(ctx).getBoolean(K_MARQUEE, true)   // cuộn tên đường dài
    fun setMarquee(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(K_MARQUEE, v).apply()

    // ★ 2026-07-13: MẶC ĐỊNH TẮT. RE DashCast/OpenBYD: HỌ KHÔNG nội suy — gửi cự ly RAW từ noti, để FIRMWARE cụm tự
    // animate đếm-lùi. Nội suy app-side (baseline−traveled theo tốc độ mỗi 400ms) ĐÁNH NHAU với firmware → "số nhảy
    // tán loạn". Tắt = gửi raw → mượt như họ. Giữ toggle cho ai muốn thử lại.
    fun interpolate(ctx: Context): Boolean = sp(ctx).getBoolean("interpolate", false)
    fun setInterpolate(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("interpolate", v).apply()

    // ★ HUD kính lái: ghi thẳng CAN instrument register (BydHal.writeNavFrame) như DashCast/OpenBYD → firmware animate.
    // THỬ NGHIỆM (mặc định TẮT): cần xe có HUD + xác minh writeNavFrame render. Bật ở màn chính để test.
    fun hud(ctx: Context): Boolean = sp(ctx).getBoolean("hud", false)
    fun setHud(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("hud", v).apply()

    // Booster đọc UI GMaps trên màn (accessibility) -> tinh chỉnh cự ly tới rẽ chính xác hơn noti.
    // Chỉ chạy khi GMaps đang HIỆN trên màn; bị app khác (YouTube) che -> tự câm, nội suy gánh tiếp.
    fun accBooster(ctx: Context): Boolean = sp(ctx).getBoolean("acc_booster", true)
    fun setAccBooster(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("acc_booster", v).apply()

    // Tự bật GPS hiệu chỉnh (dead-reckoning hầm) khi mở app / khởi động máy. Mặc định BẬT. User tắt nút → lưu false
    // để phiên sau không tự bật lại.
    fun gpsAuto(ctx: Context): Boolean = sp(ctx).getBoolean("gps_auto", true)
    fun setGpsAuto(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("gps_auto", v).apply()

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

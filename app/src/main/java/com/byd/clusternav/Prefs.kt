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

    // ★ 1.13 (Option B, owner 2026-08-13): MẶC ĐỊNH TẮT. Mở app KHÔNG đụng adb/dadb lúc khởi động (tránh đua
    // nhiều client dadb + tránh popup "Allow USB debugging" khi user chưa cần nav). Chỉ khi user gạt công tắc
    // BẬT mới tự cấp quyền notification (NavConnect.selfGrant) + kết nối. Quyền đã cấp PERSIST qua reboot nên
    // các lần bật sau không phải chạy adb lại (listener tự bind; RebindReceiver lo phần khởi động lại).
    fun enabled(ctx: Context): Boolean = sp(ctx).getBoolean(K_ENABLED, false)
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

    // ★ 1.14 (owner on-car): MẶC ĐỊNH BẬT lại marquee — tên đường >~8 ký tự bị firmware cụm hard-cut, nên cho
    // chạy cuộn PHẢI→TRÁI. Bước cuộn nay TÍNH THEO THỜI GIAN (ClusterBroadcaster.MARQUEE_STEP_MS, reset mỗi
    // đường mới) → đều, chậm, MƯỢT (bản cũ tăng scrollTick không đều theo emission → dựt). Có toggle UI (cb_marquee).
    fun marquee(ctx: Context): Boolean = sp(ctx).getBoolean(K_MARQUEE, true)    // true = chạy marquee mượt
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

    // ─── T3 (1.13): Nút vật lý → Trợ lý giọng nói ───────────────────────────────────────────────
    // 1.19: KHÔNG thay chức năng gốc — onKeyEvent chỉ "nuốt" đúng keycode đã cấu hình, còn lại pass-through.
    // Bỏ cử chỉ (Nhấn/Nhấn-giữ) vì nút short/long ra keycode khác nhau. Đích lưu STRING (package/sentinel);
    // nút tự học lưu JSON. MẶC ĐỊNH TẮT. Xem VoiceKeyMatcher (:core).
    private const val K_VK_ENABLED = "voicekey_enabled"
    private const val K_VK_KEYCODE = "voicekey_keycode"
    private const val K_VK_TARGET = "voicekey_target"          // 1.19: STRING (package hoặc sentinel __ASSIST__/__RECOGNIZER__)
    private const val K_VK_LEARN = "voicekey_learn"
    private const val K_VK_CUSTOM = "voicekey_custom_buttons"  // 1.19: JSON [{"n":name,"k":keycode}] nút tự học
    const val VK_KEYCODE_DEFAULT = 328   // nút mic vô-lăng giữ trên xe này (đo on-car 2026-08-13). "Học phím mới" nếu xe khác.
    const val VK_TARGET_ASSIST = "__ASSIST__"
    const val VK_TARGET_RECOGNIZER = "__RECOGNIZER__"
    const val VK_TARGET_DEFAULT = "ai.zalo.kiki.car"           // mặc định Kiki (khớp default cũ 0=Kiki)

    fun voiceKeyEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(K_VK_ENABLED, false)
    fun setVoiceKeyEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(K_VK_ENABLED, v).apply()
    fun voiceKeyCode(ctx: Context): Int = sp(ctx).getInt(K_VK_KEYCODE, VK_KEYCODE_DEFAULT)
    fun setVoiceKeyCode(ctx: Context, v: Int) = sp(ctx).edit().putInt(K_VK_KEYCODE, v).apply()

    /** Đích mở khi bấm nút = package name hoặc sentinel. Migrate cấu hình cũ (int ordinal → string). */
    fun voiceKeyTargetSpec(ctx: Context): String {
        val p = sp(ctx)
        return when (val raw = p.all[K_VK_TARGET]) {
            is String -> raw
            is Int -> (when (raw) { 1 -> "com.byd.autovoice"; 2 -> VK_TARGET_RECOGNIZER; 3 -> VK_TARGET_ASSIST; else -> VK_TARGET_DEFAULT })
                .also { p.edit().putString(K_VK_TARGET, it).apply() }
            else -> VK_TARGET_DEFAULT
        }
    }
    fun setVoiceKeyTargetSpec(ctx: Context, spec: String) = sp(ctx).edit().putString(K_VK_TARGET, spec).apply()

    /** "Học phím mới": khi BẬT, onKeyEvent kế tiếp bắt keycode nút vừa bấm rồi tự tắt cờ + báo Activity đặt tên. */
    fun voiceKeyLearn(ctx: Context): Boolean = sp(ctx).getBoolean(K_VK_LEARN, false)
    fun setVoiceKeyLearn(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(K_VK_LEARN, v).apply()

    /** Nút tự học (tên, keycode) — lưu JSON để dropdown dựng lại + xoá được. */
    fun voiceKeyCustomButtons(ctx: Context): List<Pair<String, Int>> = runCatching {
        val arr = org.json.JSONArray(sp(ctx).getString(K_VK_CUSTOM, "[]"))
        (0 until arr.length()).map { val o = arr.getJSONObject(it); o.getString("n") to o.getInt("k") }
    }.getOrDefault(emptyList())
    fun addVoiceKeyCustomButton(ctx: Context, name: String, code: Int) =
        writeCustomButtons(ctx, voiceKeyCustomButtons(ctx).filterNot { it.second == code } + (name to code))
    fun removeVoiceKeyCustomButton(ctx: Context, code: Int) =
        writeCustomButtons(ctx, voiceKeyCustomButtons(ctx).filterNot { it.second == code })
    private fun writeCustomButtons(ctx: Context, items: List<Pair<String, Int>>) {
        val arr = org.json.JSONArray()
        items.forEach { arr.put(org.json.JSONObject().put("n", it.first).put("k", it.second)) }
        sp(ctx).edit().putString(K_VK_CUSTOM, arr.toString()).apply()
    }

    // Toggle theo module (key namespaced "mod_" — không thể đụng các key lõi ở trên). Mặc định TẮT
    // (experiment phải bật tay). Key mồ côi sau khi xoá module = dead data vô hại, không cần dọn.
    fun moduleEnabled(ctx: Context, title: String): Boolean =
        sp(ctx).getBoolean("mod_" + title.hashCode(), false)
    fun setModuleEnabled(ctx: Context, title: String, v: Boolean) =
        sp(ctx).edit().putBoolean("mod_" + title.hashCode(), v).apply()
}

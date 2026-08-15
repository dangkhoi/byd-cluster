package com.byd.clusternav.modules.hal

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import java.lang.reflect.Array as RArray

/**
 * Hạ tầng HAL DÙNG CHUNG cho các module chạm xe IN-PROCESS (không shell-out dadb).
 * Gồm: (1) BydPermissionBypassContext (port kim.apk) — bọc Context để check*Permission→0 cho quyền BYDAUTO,
 * cho HAL getInstance chạy từ app uid không cần OEM perm; (2) reflection getInstance/set/get/probe (SDK BYDAuto
 * KHÔNG có trên classpath → reflection thuần như NavOpen.java).
 *
 * Đây là INFRA (như ModuleHost), nhiều module HAL dùng chung. Xoá hết module HAL (inprochal/tpms/vehicle)
 * → có thể xoá luôn thư mục modules/hal/. Không module nào ngoài HAL import nó.
 */
object BydHal {
    const val EV = "android.hardware.bydauto.BYDAutoEventValue"
    const val IDS = "android.hardware.bydauto.BYDAutoFeatureIds"

    // FQN device (reflection) — đủ cho các module hiện có.
    const val INSTRUMENT = "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice"
    const val SETTING = "android.hardware.bydauto.setting.BYDAutoSettingDevice"
    const val TYRE = "android.hardware.bydauto.tyre.BYDAutoTyreDevice"
    const val SPEED = "android.hardware.bydauto.speed.BYDAutoSpeedDevice"
    const val GEARBOX = "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice"
    const val CHARGING = "android.hardware.bydauto.charging.BYDAutoChargingDevice"
    const val ENERGY = "android.hardware.bydauto.energy.BYDAutoEnergyDevice"
    const val STATISTIC = "android.hardware.bydauto.statistic.BYDAutoStatisticDevice"
    // Nguồn HƯỚNG cho dead-reckoning (recon: getInstance được không trên ROM này?): góc lái + tốc độ 4 bánh.
    const val BODYWORK = "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice"   // getSteeringWheelValue (±780°)
    const val SPECIAL = "android.hardware.bydauto.special.BYDAutoSpecialDevice"      // getWheelSpeed(area) 4 bánh → yaw
    // (ENGINE / EngineVoiceSimulator đã theo tiếng pô sang app com.byd.posound — bỏ khỏi ClusterNav)

    // Quyền KHÔNG có substring "byd" nhưng kim.apk vẫn allowlist (để bypass đầy đủ như reference).
    private val EXTRA_PERMS = setOf(
        "android.permission.WRITE_SECURE_SETTINGS", "android.permission.INJECT_EVENTS",
        "android.permission.MEDIA_CONTENT_CONTROL", "android.permission.START_ACTIVITIES_FROM_BACKGROUND")

    /**
     * Context bọc: check*Permission→0 + enforce*→no-op cho mọi quyền BYDAUTO/BYDACQUISITION/byd + EXTRA_PERMS
     * (port BydPermissionBypassContext kim.apk) VÀ getPackageName="com.byd.dashcast" + getApplicationContext=this
     * (khớp NavOpen.wrap đã CHỨNG MINH ghi được cụm — getInstance vài ROM cần spoof package này).
     */
    fun bypass(base: Context): Context = object : ContextWrapper(base) {
        private fun byd(p: String?): Boolean = !p.isNullOrBlank() &&
            (p.contains("BYDAUTO", true) || p.contains("BYDACQUISITION", true) || p.contains("byd", true) || p in EXTRA_PERMS)
        override fun checkPermission(p: String, pid: Int, uid: Int) = if (byd(p)) PackageManager.PERMISSION_GRANTED else super.checkPermission(p, pid, uid)
        override fun checkCallingPermission(p: String) = if (byd(p)) PackageManager.PERMISSION_GRANTED else super.checkCallingPermission(p)
        override fun checkCallingOrSelfPermission(p: String) = if (byd(p)) PackageManager.PERMISSION_GRANTED else super.checkCallingOrSelfPermission(p)
        override fun checkSelfPermission(p: String) = if (byd(p)) PackageManager.PERMISSION_GRANTED else super.checkSelfPermission(p)
        override fun enforcePermission(p: String, pid: Int, uid: Int, m: String?) { if (!byd(p)) super.enforcePermission(p, pid, uid, m) }
        override fun enforceCallingPermission(p: String, m: String?) { if (!byd(p)) super.enforceCallingPermission(p, m) }
        override fun enforceCallingOrSelfPermission(p: String, m: String?) { if (!byd(p)) super.enforceCallingOrSelfPermission(p, m) }
        override fun getPackageName() = "com.byd.dashcast"
        override fun getApplicationContext(): Context = this
    }

    /** System context (ActivityThread) đã bọc bypass — kiểu MapMode dùng. null nếu fail. */
    fun systemBypassContext(): Context? = runCatching {
        exemptHiddenApis()
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("currentActivityThread").invoke(null)
        (at.getMethod("getSystemContext").invoke(thread) as? Context)?.let { bypass(it) }
    }.getOrNull()

    /** getInstance(Context) của device qua reflection (thử nhiều ctx: system rồi app, đều bọc bypass). null nếu fail. */
    fun device(fqn: String, vararg ctxs: Context?): Any? {
        for (c in ctxs) {
            val d = runCatching { Class.forName(fqn).getMethod("getInstance", Context::class.java).invoke(null, c) }.getOrNull()
            if (d != null) return d
        }
        return null
    }

    fun featureId(name: String): Int? =
        runCatching { Class.forName(IDS).getField(name).getInt(null) }.getOrNull()

    /** Mọi field trong BYDAutoFeatureIds khớp 1 trong [subs] (substring, không phân biệt hoa thường) → (tên, id). */
    fun featureIdsMatching(vararg subs: String): List<Pair<String, Int>> = runCatching {
        Class.forName(IDS).fields
            .filter { f -> subs.any { f.name.contains(it, true) } }
            .mapNotNull { f -> runCatching { f.name to f.getInt(null) }.getOrNull() }
            .sortedBy { it.first }
    }.getOrElse { emptyList() }

    /** Ghi 1 feature int qua set(int[], EventValue). Trả rc; ném nếu không có method set. */
    fun setInt(dev: Any, id: Int, value: Int): Any? = setEv(dev, id) { ev ->
        ev.javaClass.getField("intValue").setInt(ev, value)
    }

    /** Ghi buffer (vd tên đường UTF-16LE) qua set(int[], EventValue).bufferDataValue. */
    fun setBytes(dev: Any, id: Int, bytes: ByteArray): Any? = setEv(dev, id) { ev ->
        ev.javaClass.getField("bufferDataValue").set(ev, bytes)
    }

    private inline fun setEv(dev: Any, id: Int, fill: (Any) -> Unit): Any? {
        val ev = Class.forName(EV).getDeclaredConstructor().newInstance()
        fill(ev)
        val set = dev.javaClass.methods.firstOrNull {
            it.name == "set" && it.parameterTypes.size == 2 && it.parameterTypes[0] == IntArray::class.java
        } ?: throw NoSuchMethodException("set(int[], EventValue)")
        return set.invoke(dev, intArrayOf(id), ev)
    }

    /** Gọi method [name] (0/1 arg int, có thể String) → (ok, "rc=..." / lỗi). Cho probe HAL set/get/hasFeature bất kỳ. */
    fun invokeM(dev: Any, name: String, arg: Any? = null): Pair<Boolean, String> {
        val m = dev.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.size == (if (arg == null) 0 else 1) &&
                (arg == null ||
                    (arg is Int && it.parameterTypes[0] == Int::class.javaPrimitiveType) ||
                    (arg is String && it.parameterTypes[0] == String::class.java))
        } ?: return false to "no method $name(${arg?.javaClass?.simpleName ?: ""})"
        return runCatching { true to "${if (arg == null) m.invoke(dev) else m.invoke(dev, arg)}" }.getOrElse { false to root(it) }
    }

    /** Gọi getter tên [name] (0 hoặc 1 tham số int) qua reflection → chuỗi giá trị. null nếu không có/ném.
     *  ĐÂY là cách đọc THẬT trên ROM này (getCurrentSpeed(), getTyrePressureValue(area)...) — KHÔNG cần listener.
     *  Method cache theo (class#name#arity) → hot-path (steering mỗi tick) khỏi scan getMethods() lại. */
    private val getterCache = java.util.concurrent.ConcurrentHashMap<String, java.lang.reflect.Method>()
    fun callGetter(dev: Any, name: String, arg: Int? = null): String? {
        val key = "${dev.javaClass.name}#$name#${if (arg == null) 0 else 1}"
        val m = getterCache[key] ?: dev.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.size == (if (arg == null) 0 else 1) &&
                (arg == null || it.parameterTypes[0] == Int::class.javaPrimitiveType)
        }?.also { getterCache[key] = it } ?: return null
        return runCatching { (if (arg == null) m.invoke(dev) else m.invoke(dev, arg))?.toString() ?: "null" }.getOrNull()
    }

    /** Đọc nhiều getter (tên, arg?) → list "name(arg)=value". Bỏ getter không có. */
    fun readGetters(dev: Any, specs: List<Pair<String, Int?>>): List<String> =
        specs.mapNotNull { (name, arg) -> callGetter(dev, name, arg)?.let { "$name${arg?.let { a -> "($a)" } ?: ""}=$it" } }

    /** Device có get(int[]) đồng bộ không (đa số BYDAuto device KHÔNG — đọc qua listener). */
    fun hasSyncGet(dev: Any): Boolean = dev.javaClass.methods.any {
        it.name == "get" && it.parameterTypes.size == 1 && it.parameterTypes[0] == IntArray::class.java
    }

    /** Thử ĐỌC đồng bộ: nếu device có get(int[]) → gọi, trả kết quả (EventValue[]/EventValue). null nếu không có method/ném. */
    fun tryGet(dev: Any, id: Int): Any? {
        if (!hasSyncGet(dev)) return null
        val get = dev.javaClass.methods.first { it.name == "get" && it.parameterTypes.size == 1 && it.parameterTypes[0] == IntArray::class.java }
        return runCatching { get.invoke(dev, intArrayOf(id)) }.getOrNull()
    }

    /** KIỂM CHỨNG GHI (cho self-test): set 1 feature int → (ok, chi tiết). ok=true nếu set() KHÔNG ném
     *  (bắt được SecurityException/HAL chặn). LƯU Ý: "không ném" mạnh hơn getInstance-non-null nhưng vẫn
     *  chưa chắc cụm render (set có thể trả rc lỗi / no-op âm thầm) → module vẫn bảo "nhìn cụm để chắc". */
    fun writeProbe(dev: Any, featureName: String, value: Int): Pair<Boolean, String> {
        val id = featureId(featureName) ?: return false to "không có feature-id $featureName"
        return runCatching { true to "rc=${setInt(dev, id, value)}" }.getOrElse { false to root(it) }
    }

    /** SET_NAVI_SCREEN_STATUS_SET (0x4C10E015 · BYDAutoSettingDevice) chọn CHẾ ĐỘ hiển thị nav trên cụm =
     *  đúng cái menu OEM "Đơn giản / Màn hình nhỏ / Toàn màn hình / OFF" (mở khoá 2026-08-13). Giá trị:
     *  ⚠️ CHƯA map chắc trên xe — navopen dùng 3 (rc=0, ứng viên "Toàn màn hình"), AmapService reset = 3.
     *  Cần sweep 0/1/2/3 on-car để chốt value = "Đơn giản" (Giữa+ETA). Default 3 = value đã-proven rc=0. */
    const val NAV_SCREEN_MODE_ON = 3

    /** RE 2026-08-15 (insight owner "mũi tên/cự ly/tên đường có tách domestic↔oversea"): CẢ HỌ guidance có bản
     *  OVERSEA (export) song song domestic — suffix id TRÙNG, chỉ khác prefix 0x1F7 (oversea) vs 0x43F (domestic).
     *  Xe export (Seal) đọc họ 0x1F7 → HUD KHÔNG lên gì khi app chỉ ghi 0x43F. Raw-id FALLBACK vì lớp reflect
     *  `BYDAutoFeatureIds` (bản tmap) thiếu các tên oversea. Nguồn: DiCarServer Instrument.java / InstrumentMapper. */
    const val EASY_NAVI_GUIDE_OVERSEA_ID = 0x1F701010   // mũi tên — INSTRUMENT_EASY_NAVI_GUIDE_INFOR_SET (~ GUIDE_INFO_SIMPLE)
    const val CROSSING_DIST_OVERSEA_ID = 0x1F701018     // cự ly  — INSTRUMENT_DISTANCE_TARGET_HEAD_SET (~ FRONT_CROSSING_DISTANCE)
    const val PATHNAME_OVERSEA_ID = 0x1F7A1008          // tên đường — INSTRUMENT_TARGET_NEXT_PATHNAME_INFO_OVERASEA_SET (~ NEXT_PATHNAME)

    /** Ghi 1 frame nav IN-PROCESS lên cụm (status=2 + chọn mode nav-screen + icon/khoảng-cách/tên-đường) qua
     *  bypass-context. Đây là CƠ CHẾ THẬT tạo "Giữa + ETA" (khớp navopen), KHÁC ch1000 op39 (no-op trên xe này).
     *  [screenMode] = giá trị SET_NAVI_SCREEN_STATUS_SET (xem [NAV_SCREEN_MODE_ON]). Trả tóm tắt rc.
     *  Owner hợp lệ DUY NHẤT: [com.byd.clusternav.NavigationHudOwner] (giữ ownership boundary — xem PhysicalHudOwnershipTest). */
    fun writeNavFrame(
        ctx: Context, icon: Int, segMeters: Int, road: String, screenMode: Int = NAV_SCREEN_MODE_ON,
        routeSeconds: Int = -1, routeMeters: Int = -1, arrivalClock: String? = null,
    ): String {
        val sys = systemBypassContext()
        val instr = device(INSTRUMENT, sys, bypass(ctx)) ?: return "InstrumentDevice null (không ghi được)"
        val setting = device(SETTING, sys, bypass(ctx))
        val rc = StringBuilder()
        fun w(name: String, v: Int) { featureId(name)?.let { id -> rc.append(" $name=").append(runCatching { setInt(instr, id, v) }.getOrElse { root(it) }) } }
        // Ghi 1 feature INT theo TÊN (reflect BYDAutoFeatureIds), FALLBACK raw-id cho các tên OVERSEA vắng trong lớp reflect.
        fun wi(name: String, rawId: Int, v: Int, tag: String) {
            (featureId(name) ?: rawId).let { id -> rc.append(" $tag=").append(runCatching { setInt(instr, id, v) }.getOrElse { root(it) }) }
        }
        w("INSTRUMENT_SEND_NAVI_STATUS_SET", 2)
        featureId("SET_NAVI_SCREEN_STATUS_SET")?.let { id -> setting?.let { s -> rc.append(" NAVI_SCREEN=").append(runCatching { setInt(s, id, screenMode) }.getOrElse { e -> root(e) }) } }
        w("INSTRUMENT_GUIDE_INFO_SIMPLE_SET", icon)
        w("INSTRUMENT_FRONT_CROSSING_DISTANCE_SET", segMeters)
        featureId("INSTRUMENT_TARGET_NEXT_PATHNAME_INFO_SET")?.let { id -> rc.append(" PATHNAME=").append(runCatching { setBytes(instr, id, road.toByteArray(Charsets.UTF_16LE)) }.getOrElse { e -> root(e) }) }
        // THỬ NGHIỆM (2026-08-15, insight owner): guidance có bản OVERSEA (export) song song domestic (suffix id trùng,
        // prefix 0x1F7 vs 0x43F). Xe export (Seal) đọc họ 0x1F7 → HUD không lên GÌ khi app chỉ ghi 0x43F. Ghi THÊM
        // bản oversea cho mũi tên + cự ly + tên đường (KHÔNG bỏ domestic). Feature hiển thị → ghi lành tính; null-safe;
        // fallback raw-id (BYDAutoFeatureIds thiếu tên oversea). Xác nhận trên xe: HUD export lên mũi tên/cự ly/tên chưa.
        (featureId("INSTRUMENT_EASY_NAVI_GUIDE_INFOR_SET") ?: EASY_NAVI_GUIDE_OVERSEA_ID).let { id ->
            rc.append(" GUIDE_OVERSEA=").append(runCatching { setInt(instr, id, icon) }.getOrElse { e -> root(e) })
        }
        (featureId("INSTRUMENT_DISTANCE_TARGET_HEAD_SET") ?: CROSSING_DIST_OVERSEA_ID).let { id ->
            rc.append(" DIST_OVERSEA=").append(runCatching { setInt(instr, id, segMeters) }.getOrElse { e -> root(e) })
        }
        (featureId("INSTRUMENT_TARGET_NEXT_PATHNAME_INFO_OVERASEA_SET") ?: PATHNAME_OVERSEA_ID).let { id ->
            rc.append(" PATHNAME_OVERSEA=").append(runCatching { setBytes(instr, id, road.toByteArray(Charsets.UTF_16LE)) }.getOrElse { e -> root(e) })
        }
        // FULL DATA HUD (2026-08-15, owner "ghi hết data lên HUD, domestic + oversea"): thời-gian-còn-lại (giờ/phút/
        // giây/ngày), quãng-đường-còn-lại, giờ-tới (ETA) — CHƯA từng ghi lên HUD (trước chỉ vào CỤM qua broadcast).
        // Ghi vào CẢ 2 họ; CHỈ khi có giá trị hợp lệ (bỏ qua lúc keep-alive/absent để không xoá trắng số đang hiện).
        // Toàn feature HIỂN THỊ (không phải switch) → lành tính. Id: DiCarServer Instrument.java (0x43F dom / 0x1F7 oversea).
        if (routeSeconds >= 0) {
            val d = routeSeconds / 86400; val h = (routeSeconds % 86400) / 3600
            val m = (routeSeconds % 3600) / 60; val s = routeSeconds % 60
            wi("INSTRUMENT_NAVI_TRIP_INFO_HOUR_SET", 0x43F02010, h, "RT_H");   wi("INSTRUMENT_REMAIN_DRIVE_TIME_HOUR_SET", 0x1F702010, h, "RT_HO")
            wi("INSTRUMENT_NAVI_TRIP_INFO_MINUTE_SET", 0x43F02018, m, "RT_M"); wi("INSTRUMENT_REMAIN_DRIVE_TIME_MINUTE_SET", 0x1F702018, m, "RT_MO")
            wi("INSTRUMENT_NAVI_TRIP_REMAINING_SECOND_SET", 0x43F0201E, s, "RT_S"); wi("INSTRUMENT_REMAIN_DRIVE_TIME_SECOND_SET", 0x1F70201E, s, "RT_SO")
            wi("INSTRUMENT_REMAIN_DRIVING_TIME_DAY_SET", 0x43F02024, d, "RT_D")   // day: domestic only (oversea không có ô ngày riêng)
        }
        if (routeMeters >= 0) {
            wi("INSTRUMENT_NAVI_TRIP_INFO_MILEAGE_SET", 0x43F02028, routeMeters, "MILE"); wi("INSTRUMENT_REMAIN_MILEAGE_SET", 0x1F702028, routeMeters, "MILE_O")
        }
        arrivalClock?.split(":")?.let { p ->
            p.getOrNull(0)?.trim()?.toIntOrNull()?.let { h ->
                wi("INSTRUMENT_EXPECTED_ARRIVE_HOUR_SET", 0x43F09018, h, "ETA_H"); wi("INSTRUMENT_EXPECT_ARRIVAL_TIME_HOUR_SET", 0x1F705018, h, "ETA_HO")
            }
            p.getOrNull(1)?.trim()?.toIntOrNull()?.let { m ->
                wi("INSTRUMENT_EXPECTED_ARRIVE_MINUTE_SET", 0x43F09020, m, "ETA_M"); wi("INSTRUMENT_EXPECT_ARRIVAL_TIME_MINUTE_SET", 0x1F705020, m, "ETA_MO")
            }
        }
        return rc.toString().trim()
    }

    /** TẮT nav HUD (status=4 + clear guide/dist) khi hết dẫn đường — như DashCast setNaviActive(false). */
    fun clearNavFrame(ctx: Context): String {
        val instr = device(INSTRUMENT, systemBypassContext(), bypass(ctx)) ?: return "InstrumentDevice null"
        val rc = StringBuilder()
        fun w(name: String, v: Int) { featureId(name)?.let { id -> rc.append(" $name=").append(runCatching { setInt(instr, id, v) }.getOrElse { root(it) }) } }
        w("INSTRUMENT_SEND_NAVI_STATUS_SET", 4)
        w("INSTRUMENT_GUIDE_INFO_SIMPLE_SET", 0)
        w("INSTRUMENT_FRONT_CROSSING_DISTANCE_SET", -1)
        return rc.toString().trim()
    }

    /** Đọc đồng bộ feature đầu tiên ra giá trị (cho self-test read). null nếu không đọc được cái nào. */
    fun firstReadable(dev: Any, ids: List<Pair<String, Int>>): Pair<String, String>? {
        for ((n, id) in ids) {
            val r = tryGet(dev, id) ?: continue
            return n to readValue(r)
        }
        return null
    }

    /** Rút giá trị đọc được từ kết quả get() (EventValue hoặc mảng) → chuỗi int/float/buffer. */
    fun readValue(result: Any?): String {
        if (result == null) return "null"
        val item = if (result.javaClass.isArray && RArray.getLength(result) > 0) RArray.get(result, 0) else result
        if (item == null) return "null(empty)"
        val i = runCatching { item.javaClass.getField("intValue").getInt(item) }.getOrNull()
        val f = runCatching { item.javaClass.getField("floatValue").getFloat(item) }.getOrNull()
        val buf = runCatching { (item.javaClass.getField("bufferDataValue").get(item) as? ByteArray)?.size }.getOrNull()
        return "int=$i float=$f buf=${buf ?: "-"}"
    }

    /** Liệt kê method (lọc theo tiền tố) để PROBE API thật trên ROM (vd "get","set","register","on"). */
    fun methods(dev: Any, vararg prefixes: String): List<String> =
        dev.javaClass.methods
            .filter { m -> prefixes.isEmpty() || prefixes.any { m.name.startsWith(it) } }
            .map { "${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }})" }
            .distinct().sorted()

    fun exemptHiddenApis() {
        runCatching {
            val vm = Class.forName("dalvik.system.VMRuntime")
            val rt = vm.getMethod("getRuntime").invoke(null)
            vm.getMethod("setHiddenApiExemptions", Array<String>::class.java).invoke(rt, arrayOf("L") as Any)
        }
    }

    fun root(t: Throwable): String {
        var c: Throwable = t
        while (c.cause != null && c.cause !== c) c = c.cause!!
        return "${c.javaClass.simpleName}: ${c.message}"
    }

}

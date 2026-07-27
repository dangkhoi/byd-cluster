package com.byd.clusternav

import com.byd.clusternav.navigation.TurnDistanceInterpolator
import android.content.Context
import android.content.ContextWrapper

/**
 * Đọc TỐC ĐỘ XE real-time từ BYD HAL (no-root, in-process) cho dead-reckoning. TỰ CHỨA reflection — KHÔNG
 * import modules/hal (giữ luật "lõi không phụ thuộc modules"). Dựng device 1 LẦN rồi cache (nhanh, không lag).
 * getCurrentSpeed() trả km/h → đổi m/s. Null/sentinel → trả last-good (degrade an toàn, không bao giờ tệ hơn).
 *
 * LƯU Ý over-read: getCurrentSpeed() = tốc-độ-đồng-hồ (speedometer), theo thiết kế đọc CAO hơn thực ~5-8%.
 * KHÔNG bù ở đây (giữ provider "thô, trung thực"); việc bù do TurnDistanceInterpolator lo: ưu tiên
 * closingRate tự-suy (tự khử over-read), và chỉ khi chưa có closingRate mới nhân SPEEDO_CORRECTION=0.93.
 */
object SpeedProvider {
    @Volatile private var dev: Any? = null
    @Volatile private var speedMethod: java.lang.reflect.Method? = null   // cache 1 lần (device ổn định) — khỏi scan getMethods() mỗi tick
    @Volatile private var lastGoodMps = 0.0

    /**
     * ★★ W1-3 (senior review 2026-07-21) — "KHÔNG ĐỌC ĐƯỢC" phải là một GIÁ TRỊ RIÊNG, không được giả dạng 0.
     *
     * Bản cũ chỉ có [mps], và cả BỐN nhánh hỏng đều trả `lastGoodMps` — khởi tạo 0.0. Trên đời xe mà HAL tốc độ
     * không đọc được, hàm này trả **0.0 vĩnh viễn**. Người tiêu thụ từng làm lộ ra lỗi này là `DeadReckonService`: nó
     * dùng `speed < 2.0` làm cổng an toàn với ý nghĩa "xe đang ĐỖ", nên trên những xe đó cổng LUÔN mở, kể
     * cả lúc đang chạy, và vị trí GPS bị ghim vào một toạ độ cũ.
     *
     * Dead Reckon đã bị bỏ hẳn ngày 2026-07-27, nhưng bài học thì giữ nguyên và không phụ thuộc nó: một giá
     * trị "không đọc được" mà giả dạng 0 sẽ làm mọi phép so sánh ngưỡng ở tầng trên kết luận sai. Vì thế
     * [mps] và trạng thái đọc-được vẫn là hai thứ tách rời.
     */
    fun mpsOrNull(): Double? {
        val d = device() ?: return null
        val m = speedMethod ?: runCatching {
            d.javaClass.methods.firstOrNull { it.name == "getCurrentSpeed" && it.parameterTypes.isEmpty() }
        }.getOrNull()?.also { speedMethod = it } ?: return null
        val kmh = runCatching { (m.invoke(d) as? Number)?.toDouble() }.getOrNull() ?: return null
        if (kmh < 0 || kmh > 400) return null             // sentinel/không hợp lệ → KHÔNG BIẾT, không phải 0
        lastGoodMps = kmh / 3.6
        return lastGoodMps
    }

    /** Tốc độ hiện tại (m/s), suy biến về giá trị đọc được gần nhất. CHỈ dùng cho hiển thị/nội suy —
     *  cổng an toàn phải dùng [mpsOrNull]. */
    fun mps(): Double = mpsOrNull() ?: lastGoodMps

    private fun device(): Any? {
        dev?.let { return it }
        return runCatching {
            exemptHiddenApis()
            val ctx = systemBypassContext() ?: return null
            Class.forName("android.hardware.bydauto.speed.BYDAutoSpeedDevice")
                .getMethod("getInstance", Context::class.java).invoke(null, ctx).also { dev = it }
        }.getOrNull()
    }

    private fun systemBypassContext(): Context? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("currentActivityThread").invoke(null)
        (at.getMethod("getSystemContext").invoke(thread) as? Context)?.let { bypass(it) }
    }.getOrNull()

    private fun bypass(base: Context): Context = object : ContextWrapper(base) {
        private fun byd(p: String?) = !p.isNullOrBlank() && (p.contains("byd", true) || p.contains("BYDAUTO", true))
        override fun checkSelfPermission(p: String) = if (byd(p)) 0 else super.checkSelfPermission(p)
        override fun checkCallingOrSelfPermission(p: String) = if (byd(p)) 0 else super.checkCallingOrSelfPermission(p)
        override fun enforceCallingOrSelfPermission(p: String, m: String?) { if (!byd(p)) super.enforceCallingOrSelfPermission(p, m) }
        override fun getPackageName() = "com.byd.dashcast"
        override fun getApplicationContext(): Context = this
    }

    private fun exemptHiddenApis() {
        runCatching {
            val vm = Class.forName("dalvik.system.VMRuntime")
            val rt = vm.getMethod("getRuntime").invoke(null)
            vm.getMethod("setHiddenApiExemptions", Array<String>::class.java).invoke(rt, arrayOf("L") as Any)
        }
    }
}

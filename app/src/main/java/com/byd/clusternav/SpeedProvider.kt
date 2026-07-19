package com.byd.clusternav

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
    @Volatile private var lastGoodMps = 0.0

    /** Tốc độ hiện tại (m/s). 0 nếu chưa đọc được. */
    fun mps(): Double {
        val d = device() ?: return lastGoodMps
        val kmh = runCatching {
            val m = d.javaClass.methods.firstOrNull { it.name == "getCurrentSpeed" && it.parameterTypes.isEmpty() }
            (m?.invoke(d) as? Number)?.toDouble()
        }.getOrNull() ?: return lastGoodMps
        if (kmh < 0 || kmh > 400) return lastGoodMps      // sentinel/không hợp lệ → giữ giá trị cũ
        lastGoodMps = kmh / 3.6
        return lastGoodMps
    }

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

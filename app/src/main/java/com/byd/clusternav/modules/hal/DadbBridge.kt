package com.byd.clusternav.modules.hal

import android.content.Context
import android.util.Log
import com.byd.clusternav.AdbKeys
import dadb.Dadb

/**
 * Cầu nối ADB nội bộ (thư viện dadb) — INFRA dùng chung (cạnh BydHal). App tự nối adb-tcp của CHÍNH cái xe
 * (localhost:5555) → shell uid 2000 → chạy lệnh privileged. Lần đầu nối: xe hiện popup "Allow" (RSA) → bấm
 * (key persist filesDir → 1 lần). MỌI lời gọi PHẢI chạy NỀN (blocking I/O).
 *
 * Hiện dùng cho: vd_map shell-fallback `am start --display <vdId>` (chiếu map vào VirtualDisplay — cách kim.apk,
 * vì app thường không pin được activity lên display ảo bằng setLaunchDisplayId).
 */
object DadbBridge {
    private const val TAG = "DadbBridge"
    @Volatile private var dadb: Dadb? = null

    /** Nối adb-tcp. Trả true nếu sẵn sàng. CHẠY NỀN. */
    @Synchronized
    fun ensure(ctx: Context): Boolean {
        if (dadb != null) return true
        return runCatching {
            // #6: dùng KEY CHUNG [AdbKeys] (cùng key NavConnect/MockLoc/ClusterCast) → 1 lần Allow USB cho tất cả,
            // không popup thứ 2 cho vd_map.
            dadb = Dadb.create("localhost", 5555, AdbKeys.ensure(ctx))
            Log.i(TAG, "connected localhost:5555")
            true
        }.getOrElse { Log.e(TAG, "ensure fail: ${it.message}", it); dadb = null; false }
    }

    /** Chạy 1 lệnh shell, trả output. (Phải ensure() trước.) */
    fun shell(cmd: String): String {
        val d = dadb ?: return "ERR: chưa kết nối (gọi ensure)"
        return runCatching { d.shell(cmd).output }.getOrElse {
            close()   // #7: socket có thể đã chết → tear down để lần sau ensure() nối LẠI (đừng coi socket chết là sống mãi)
            "ERR shell: ${it.message}"
        }
    }

    @Synchronized
    fun close() { runCatching { dadb?.close() }; dadb = null }
}

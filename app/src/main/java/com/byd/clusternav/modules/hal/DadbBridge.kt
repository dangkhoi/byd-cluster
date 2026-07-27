package com.byd.clusternav.modules.hal

import android.content.Context
import android.util.Log
import com.byd.clusternav.AdbKeys
import com.byd.clusternav.carexec.PersistentDeviceShell

/**
 * Bọc phía Android cho [PersistentDeviceShell]: giải khoá adb từ `Context` và nối `android.util.Log`
 * vào khe ghi chú. Toàn bộ phần nói chuyện với thiết bị nằm ở `:car-integration`.
 *
 * App tự nối adb-tcp của CHÍNH cái xe (localhost:5555) → shell uid 2000 → chạy được lệnh privileged.
 * Lần đầu nối, xe hiện popup "Allow" (RSA); khoá lưu ở filesDir nên chỉ một lần. MỌI lời gọi phải chạy
 * ở luồng nền vì là I/O nghẽn.
 */
object DadbBridge {
    private const val TAG = "DadbBridge"
    @Volatile private var shell: PersistentDeviceShell? = null

    /** Nối adb-tcp. Trả true nếu sẵn sàng. CHẠY NỀN. */
    @Synchronized
    fun ensure(ctx: Context): Boolean {
        val existing = shell
        if (existing != null) return existing.ensure()
        // Dùng KEY CHUNG [AdbKeys] (cùng khoá với NavConnect/MockLoc/ClusterCast) → chỉ một lần Allow
        // cho tất cả, không popup thứ hai.
        val created = PersistentDeviceShell(
            keys = { AdbKeys.ensure(ctx) },
            note = { message -> Log.i(TAG, message) },
        )
        shell = created
        return created.ensure()
    }

    /** Chạy 1 lệnh shell, trả output. (Phải ensure() trước.) */
    fun shell(cmd: String): String = shell?.shell(cmd) ?: "ERR: chưa kết nối (gọi ensure)"

    @Synchronized
    fun close() {
        shell?.close()
        shell = null
    }
}

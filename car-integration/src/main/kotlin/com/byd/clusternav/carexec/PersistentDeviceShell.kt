package com.byd.clusternav.carexec

import dadb.AdbKeyPair
import dadb.Dadb

/**
 * Phiên adb GIỮ LÂU tới chính head unit (`localhost:5555`).
 *
 * Khác [LocalDeviceShell] ở chỗ đó: helper kia mở-chạy-đóng cho từng việc lẻ, còn lớp này giữ handle
 * để chạy nhiều lệnh rời rạc theo nhịp người dùng bấm, không phải theo một thao tác liền mạch.
 *
 * Trước 2026-07-27 lớp này nằm ở `:app` dưới tên `DadbBridge` và nhận `Context` chỉ để lấy khoá, cùng
 * `android.util.Log` để ghi chú — hai phụ thuộc Android mà không cần HÀNH VI nào của Android (đúng lớp
 * lỗi Q1b trong layering-rules). Giờ nó nhận khoá đã giải, và ghi chú qua một khe [note] để phía Android
 * tự cắm `Log` vào.
 *
 * Mọi lời gọi là I/O nghẽn — bắt buộc chạy ở luồng nền.
 */
class PersistentDeviceShell(
    private val keys: () -> AdbKeyPair,
    private val note: (String) -> Unit = {},
    private val host: String = "localhost",
    private val port: Int = 5555,
) {
    @Volatile private var handle: Dadb? = null

    /** Nối nếu chưa có. Trả true khi sẵn sàng. */
    @Synchronized
    fun ensure(): Boolean {
        if (handle != null) return true
        return runCatching {
            handle = Dadb.create(host, port, keys())
            note("đã nối $host:$port")
            true
        }.getOrElse {
            note("nối thất bại: ${it.message}")
            handle = null
            false
        }
    }

    /**
     * Chạy một lệnh, trả output. Nếu socket đã chết thì đóng luôn handle để lần sau [ensure] nối LẠI —
     * đừng coi một socket chết là sống mãi.
     */
    fun shell(command: String): String {
        val open = handle ?: return "ERR: chưa kết nối (gọi ensure)"
        return runCatching { open.shell(command).output }.getOrElse {
            close()
            "ERR shell: ${it.message}"
        }
    }

    @Synchronized
    fun close() {
        runCatching { handle?.close() }
        handle = null
    }
}

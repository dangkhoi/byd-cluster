package com.byd.clusternav.carexec

import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/**
 * Một phiên adb ngắn tới chính head unit đang chạy app, cho các việc lẻ: tự cấp quyền, tự chữa listener,
 * cài bản cập nhật, đọc chẩn đoán.
 *
 * Vì sao tập trung: trước 2026-07-27 có **13 chỗ** trong app tự gọi `Dadb.create("localhost", 5555, ...)`.
 * Mỗi chỗ là một đường ra thiết bị mà kiến trúc không thấy, nên không ai kiểm được ai đang nói gì với máy.
 * Kiến trúc nói mọi transport thuộc `:car-integration`; đây là chỗ đó.
 *
 * KHÔNG dùng cho Cluster Cast. Cast đi qua `CastAdbGateway` vì mọi lệnh của nó phải truy được về một giao
 * dịch có fence và deadline; helper này thì không có gì bảo vệ, đúng cho việc lẻ và sai cho việc có journal.
 */
/** Output một lệnh, giữ nguyên stdout và stderr riêng để caller tự định dạng như trước. */
data class LocalShellText(val output: String, val errorOutput: String, val exitCode: Int) {
    val ok: Boolean get() = exitCode == 0
}

object LocalDeviceShell {

    private const val HOST = "localhost"
    private const val PORT = 5555

    /** Chạy một lệnh, trả về stdout+stderr đã trim, hoặc null nếu không nối được. */
    fun run(keys: AdbKeyPair, command: String): String? = runCatching {
        Dadb.create(HOST, PORT, keys).use { adb -> (adb.shell(command).allOutput ?: "").trim() }
    }.getOrNull()

    /** Chạy nhiều lệnh trên cùng một phiên; trả về danh sách output theo thứ tự, hoặc null nếu phiên lỗi. */
    fun runAll(keys: AdbKeyPair, commands: List<String>): List<String>? = runCatching {
        Dadb.create(HOST, PORT, keys).use { adb ->
            commands.map { (adb.shell(it).allOutput ?: "").trim() }
        }
    }.getOrNull()

    /**
     * Mở một phiên và trao vào một hàm chạy lệnh.
     *
     * Có mặt vì hai call site cần **trình tự trên cùng một phiên**: `NavConnect` ngủ 1.5 giây giữa
     * disallow và allow listener, `ClusterDiag` chạy hàng chục lệnh và tự ghép stdout với stderr. Tách
     * thành nhiều phiên rời sẽ đổi hành vi của một đường tự-chữa vốn đã mong manh — mà giai đoạn này
     * không được đổi hành vi.
     */
    fun <T> session(keys: AdbKeyPair, block: (shell: (String) -> LocalShellText) -> T): T? = runCatching {
        Dadb.create(HOST, PORT, keys).use { adb ->
            block { command ->
                val response = adb.shell(command)
                LocalShellText(response.output ?: "", response.errorOutput ?: "", response.exitCode)
            }
        }
    }.getOrNull()

    /** Cài một APK. Trả về true nếu dadb không ném. */
    fun installApk(keys: AdbKeyPair, apk: File, vararg options: String): Boolean = runCatching {
        Dadb.create(HOST, PORT, keys).use { adb -> adb.install(apk, *options) }
        true
    }.getOrDefault(false)
}

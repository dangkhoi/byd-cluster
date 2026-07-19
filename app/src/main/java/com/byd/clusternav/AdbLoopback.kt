package com.byd.clusternav

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Mini ADB client — gọi shell commands trên chính head-unit qua loopback (127.0.0.1:5555).
 * Head-unit BYD luôn bật adb TCP nên app tự connect được.
 * Protocol: ADB transport → shell command (simplified: CNXN → OPEN shell:cmd → read WRTE → CLSE).
 *
 * Chỉ dùng cho rebind listener (disallow/allow) vì firmware chặn mọi cách khác.
 */
object AdbLoopback {
    private const val TAG = "AdbLoopback"
    private const val PORT = 5555
    private const val TIMEOUT_MS = 8000

    /**
     * Chạy 1 shell command trên head-unit qua adb loopback. Trả stdout hoặc null nếu lỗi.
     * Blocking — gọi từ background thread.
     */
    fun shell(cmd: String): String? {
        // Simplified: dùng Runtime.exec gọi chính `adb` binary nếu có trên head-unit (nhiều BYD unit có sẵn).
        // Nếu không có binary → fallback socket ADB protocol.
        return tryRuntimeAdb(cmd) ?: trySocketAdb(cmd)
    }

    /** Cách 1: gọi `/system/bin/adb` hoặc `adb` nếu có trên PATH. */
    private fun tryRuntimeAdb(cmd: String): String? = runCatching {
        // Trên nhiều head-unit BYD, /system/bin/cmd hoặc /system/bin/sh -c "cmd ..." work trực tiếp
        // với elevated permission vì app chạy trên user system-like.
        val proc = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", cmd))
        val out = proc.inputStream.bufferedReader().readText()
        val err = proc.errorStream.bufferedReader().readText()
        val exitCode = proc.waitFor()
        Log.i(TAG, "sh -c '$cmd' → exit=$exitCode out='$out' err='$err'")
        if (exitCode == 0) out else null
    }.getOrNull()

    /** Cách 2: connect 127.0.0.1:5555 ADB protocol, open shell. (backup) */
    private fun trySocketAdb(cmd: String): String? = runCatching {
        val sock = Socket("127.0.0.1", PORT)
        sock.soTimeout = TIMEOUT_MS
        val ins = sock.getInputStream()
        val ous = sock.getOutputStream()

        // ADB handshake: read CNXN from device, send CNXN back
        val devCnxn = readPacket(ins) ?: throw Exception("no CNXN from device")
        if (String(devCnxn.sliceArray(0..3)) != "CNXN") throw Exception("expected CNXN, got ${String(devCnxn.sliceArray(0..3))}")

        // Send our CNXN
        val banner = "host::\u0000".toByteArray()
        sendPacket(ous, "CNXN", 0x01000001, 256 * 1024, banner)

        // OPEN shell:cmd
        val shellCmd = "shell:$cmd\u0000".toByteArray()
        sendPacket(ous, "OPEN", 1, 0, shellCmd)

        // Read response (OKAY then WRTE with output)
        val sb = StringBuilder()
        var done = false
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!done && System.currentTimeMillis() < deadline) {
            val pkt = readPacket(ins) ?: break
            val pktCmd = String(pkt.sliceArray(0..3))
            when (pktCmd) {
                "OKAY" -> { /* ack, continue */ }
                "WRTE" -> {
                    val dataLen = intAt(pkt, 12)
                    if (pkt.size > 24) sb.append(String(pkt, 24, (pkt.size - 24).coerceAtMost(dataLen)))
                    // send OKAY back
                    sendPacket(ous, "OKAY", intAt(pkt, 8), intAt(pkt, 4), ByteArray(0))
                }
                "CLSE" -> done = true
                else -> done = true
            }
        }
        sock.close()
        val result = sb.toString()
        Log.i(TAG, "socket adb '$cmd' → '$result'")
        result
    }.onFailure { Log.e(TAG, "socket adb failed", it) }.getOrNull()

    private fun sendPacket(ous: OutputStream, cmd: String, arg0: Int, arg1: Int, data: ByteArray) {
        val hdr = ByteArray(24)
        cmd.toByteArray().copyInto(hdr, 0, 0, 4)
        putInt(hdr, 4, arg0)
        putInt(hdr, 8, arg1)
        putInt(hdr, 12, data.size)
        putInt(hdr, 16, crc(data))
        putInt(hdr, 20, cmd.toByteArray().let { b -> (b[0].toInt() xor 0xFF) or ((b[1].toInt() xor 0xFF) shl 8) or ((b[2].toInt() xor 0xFF) shl 16) or ((b[3].toInt() xor 0xFF) shl 24) })
        ous.write(hdr)
        if (data.isNotEmpty()) ous.write(data)
        ous.flush()
    }

    private fun readPacket(ins: InputStream): ByteArray? {
        val hdr = ByteArray(24)
        var read = 0
        while (read < 24) { val n = ins.read(hdr, read, 24 - read); if (n <= 0) return null; read += n }
        val dataLen = intAt(hdr, 12)
        if (dataLen <= 0) return hdr
        val full = ByteArray(24 + dataLen)
        hdr.copyInto(full)
        read = 0
        while (read < dataLen) { val n = ins.read(full, 24 + read, dataLen - read); if (n <= 0) break; read += n }
        return full
    }

    private fun intAt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off+1].toInt() and 0xFF) shl 8) or ((b[off+2].toInt() and 0xFF) shl 16) or ((b[off+3].toInt() and 0xFF) shl 24)

    private fun putInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte(); b[off+1] = ((v shr 8) and 0xFF).toByte(); b[off+2] = ((v shr 16) and 0xFF).toByte(); b[off+3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun crc(data: ByteArray): Int { var c = 0; for (b in data) c += (b.toInt() and 0xFF); return c }
}

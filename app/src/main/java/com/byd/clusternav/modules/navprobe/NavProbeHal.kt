package com.byd.clusternav.modules.navprobe

import android.content.Context
import com.byd.clusternav.modules.hal.BydHal

/**
 * KÊNH 2 của máy dò — nghe/dò HAL cụm xem có bắt được thứ app khác (CarPlay/AA) ghi lên không.
 *
 * VÌ SAO NGHI CÓ CỬA: chủ xe quan sát thấy cắm CarPlay thì TÊN BÀI HÁT hiện trên cụm ⇒ giữa hai bên có một
 * đường dữ liệu thật đang chạy. Firmware xác nhận cơ chế: `jadx-amap/.../AmapService.java` nhận broadcast
 * AUTONAVI rồi `BYDAutoInstrumentDevice.set(int[]{INSTRUMENT_*}, EventValue)` → cụm tự vẽ bằng widget gốc.
 *
 * KẾT QUẢ ĐO 22/07 (DiLink3): đăng ký nghe 143 feature nav/media thành công, dò 780 vòng ≈ 39 phút,
 * KHÔNG một sự kiện nào; `get()` đồng bộ cũng không tồn tại trên ROM này. ⇒ HAL KHÔNG vọng lại thứ app khác ghi.
 * Giữ code để đo trên đời xe khác (DL5 có thêm `INSTRUMENT_CP_MAP_NAVIGATION_TIPS`), nhưng đừng kỳ vọng.
 *
 * CHỈ DÙNG BydHal ở chế độ ĐỌC (getInstance / featureIds / tryGet) — không sửa một dòng nào của hạ tầng đó.
 */
object NavProbeHal {

    @Volatile private var thread: Thread? = null

    @Synchronized
    fun start(ctx: Context) {
        if (!NavProbe.isOn(ctx)) return
        if (thread?.isAlive == true) return
        val app = ctx.applicationContext
        thread = Thread({ loop(app) }, "navprobe-hal").apply { isDaemon = true; start() }
    }

    private fun loop(ctx: Context) {
        BydHal.exemptHiddenApis()
        val dev = BydHal.device(BydHal.INSTRUMENT, BydHal.systemBypassContext(), BydHal.bypass(ctx))
        if (dev == null) {
            NavProbe.append(ctx, "[HAL] KHONG lay duoc InstrumentDevice - kenh 2 khong dung duoc\n\n")
            return
        }

        // Không hardcode danh sách: hỏi thẳng BYDAutoFeatureIds trên ROM này xem thực tế có feature nào.
        val feats = BydHal.featureIdsMatching(
            "NAVI", "MAP", "GUIDE", "MUSIC", "ROAD", "CROSSING", "PATHNAME",
            "TURN", "TARGET", "ARRIVE", "TRIP", "MEDIA", "SONG",
        ).distinctBy { it.second }.take(MAX_FEATS)
        val names = feats.associate { (n, id) -> id to n }

        val head = StringBuilder()
        head.appendLine("[HAL] InstrumentDevice OK - ${feats.size} feature lien quan tren ROM nay")
        head.appendLine("[HAL] get() dong bo: " + if (BydHal.hasSyncGet(dev)) "CO" else "KHONG (chi con duong nghe)")
        feats.forEach { (n, id) -> head.appendLine("   $n = $id") }
        NavProbe.append(ctx, head.toString() + "\n")

        val proxy = listen(dev, feats.map { it.second }.toIntArray()) { kind, id, v ->
            NavProbe.append(ctx, "[HAL nghe] ${names[id] ?: id} ($kind) -> ${describe(v)}\n")
        }
        NavProbe.append(
            ctx,
            if (proxy != null) "[HAL] da dang ky nghe ${feats.size} feature\n\n"
            else "[HAL] ROM KHONG cho dang ky nghe - chi con duong do doc\n\n"
        )

        // Dò đọc: chỉ ghi khi ĐỔI, không thì file đầy rác trong vài phút.
        val last = HashMap<Int, String>()
        var ticks = 0
        try {
            while (NavProbe.isOn(ctx)) {
                for ((n, id) in feats) {
                    val cur = BydHal.tryGet(dev, id)?.let { BydHal.readValue(it) } ?: continue
                    if (last.put(id, cur) != cur) NavProbe.append(ctx, "[HAL doc] $n -> $cur\n")
                }
                if (++ticks % 60 == 0) NavProbe.append(ctx, "[HAL] con song, da do $ticks vong\n")
                Thread.sleep(POLL_MS)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            unlisten(dev, proxy)
            NavProbe.append(ctx, "[HAL] dung nghe\n\n")
        }
    }

    /**
     * Đăng ký listener nghe feature do BẤT KỲ app nào ghi.
     *
     * BẰNG CHỨNG cho phép dùng dynamic proxy (đã đọc source, không đoán):
     * `jadx-openbyd/sources/android/hardware/IBYDAutoListener.java` khai đúng 3 method
     * (onDataChanged / onDataEventChanged / onError) và KHÔNG extends IInterface ⇒ interface Java thuần,
     * KHÔNG phải AIDL Stub ⇒ `java.lang.reflect.Proxy` implement được hợp lệ.
     * `AbsBYDAutoDevice.registerListener(IBYDAutoListener, int[])` — bản 2 tham số nhận danh sách feature-id.
     */
    private fun listen(dev: Any, ids: IntArray, onEvent: (String, Int, Any?) -> Unit): Any? = runCatching {
        val iface = Class.forName("android.hardware.IBYDAutoListener")
        val h = java.lang.reflect.InvocationHandler { proxy, m, args ->
            when (m.name) {
                "onDataEventChanged" -> { onEvent("event", (args?.getOrNull(0) as? Int) ?: -1, args?.getOrNull(1)); null }
                "onDataChanged" -> { onEvent("data", -1, args?.getOrNull(0)); null }
                "onError" -> { onEvent("error", (args?.getOrNull(0) as? Int) ?: -1, args?.getOrNull(1)); null }
                "toString" -> "NavProbeHalListener"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> args?.getOrNull(0) === proxy
                else -> null
            }
        }
        val proxy = java.lang.reflect.Proxy.newProxyInstance(iface.classLoader, arrayOf(iface), h)
        val two = dev.javaClass.methods.firstOrNull {
            it.name == "registerListener" && it.parameterTypes.size == 2 && it.parameterTypes[1] == IntArray::class.java
        }
        if (two != null) two.invoke(dev, proxy, ids)
        else dev.javaClass.methods.first { it.name == "registerListener" && it.parameterTypes.size == 1 }.invoke(dev, proxy)
        proxy
    }.getOrNull()

    private fun unlisten(dev: Any, proxy: Any?) {
        proxy ?: return
        runCatching {
            dev.javaClass.methods.firstOrNull { it.name == "unregisterListener" && it.parameterTypes.size == 1 }
                ?.invoke(dev, proxy)
        }
    }

    /**
     * Diễn giải BYDAutoEventValue / IBYDAutoEvent ra chuỗi đọc được.
     * Giải buffer theo CẢ UTF-16LE lẫn UTF-8: `BydHal.writeNavFrame` ghi tên đường bằng UTF-16LE, nhưng app
     * khác (wrapper CarPlay) có thể dùng mã khác — đang đi tìm nên không được đoán trước một kiểu.
     */
    private fun describe(v: Any?): String {
        v ?: return "null"
        val parts = mutableListOf<String>()
        fun fld(n: String): Any? = runCatching { v.javaClass.getField(n).get(v) }.getOrNull()
        fun mth(n: String): Any? = runCatching { v.javaClass.getMethod(n).invoke(v) }.getOrNull()
        (fld("intValue") ?: mth("getValue"))?.let { parts += "int=$it" }
        (fld("floatValue"))?.let { if (it != 0.0f) parts += "float=$it" }
        (fld("doubleValue") ?: mth("getDoubleValue"))?.let { if (it != 0.0) parts += "double=$it" }
        val buf = (fld("bufferDataValue") ?: mth("getBufferData")) as? ByteArray
        if (buf != null && buf.isNotEmpty()) {
            parts += "buf[${buf.size}]"
            runCatching { String(buf, Charsets.UTF_16LE).trim() }.getOrNull()
                ?.takeIf { t -> t.any { it.isLetterOrDigit() } }?.let { parts += "utf16=\"$it\"" }
            runCatching { String(buf, Charsets.UTF_8).trim() }.getOrNull()
                ?.takeIf { t -> t.any { it.isLetterOrDigit() } }?.let { parts += "utf8=\"$it\"" }
            parts += "hex=" + buf.take(24).joinToString("") { b -> "%02x".format(b) }
        }
        return if (parts.isEmpty()) v.toString().take(120) else parts.joinToString(" ")
    }

    private const val MAX_FEATS = 220
    private const val POLL_MS = 3000L
}

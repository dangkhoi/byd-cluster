package com.byd.clusternav

import android.graphics.Bitmap
import android.util.Log

/**
 * Suy HƯỚNG RẼ GMaps từ large-icon bằng "chữ ký tri giác" — PORT NGUYÊN VĂN cách Open BYD 2.3 / DashCast
 * (defpackage/wm0.java b()/c()/d() + bảng w40.e). ĐÂY là cách reference app cụm thật dùng, mạnh hơn
 * heuristic trọng-tâm cũ ([ArrowClassifier]) và KHÔNG cần lái thu thập bitmap (registry dựng sẵn 38 mũi tên).
 *
 * Quy trình:
 *   1) Hạ mẫu large-icon -> lưới 15×15 = 225 bit (1 = pixel mực mũi tên) qua composite alpha + ngưỡng cực đại.
 *   2) Khớp với [ManeuverRegistry] bằng khoảng cách Hamming ≤ 18 bit (gần nhất thắng).
 *   3) Tên maneuver -> mã AMAP NEW_ICON (enum của ta, để gửi broadcast — KHÁC mã HAL của app gốc).
 *
 * ⚠ Chữ ký PHẢI tính y hệt app gốc (cùng composite/ngưỡng) nếu không sẽ lệch >18 bit và trượt registry.
 * Mã giữ đúng từng bước của wm0.b với tham số c() dùng (f=1.0, z=false): ngưỡng = max -> đếm pixel sáng nhất.
 */
object ManeuverSignature {
    private const val TAG = "ManeuverSig"
    private const val GRID = 15                 // 15×15 = 225 ô
    private const val BITS = GRID * GRID
    private const val WORDS = (BITS + 63) / 64  // = 4 long
    private const val MAX_HAMMING = 18          // ngưỡng khớp của app gốc (wm0.d)

    // DEBUG (hiện trên MainActivity): tên maneuver khớp gần nhất + mã suy ra.
    @Volatile var lastName: String = "-"; private set
    @Volatile var lastAmap: Int = -1; private set

    /** Registry dựng sẵn: chuỗi 225-bit -> LongArray(4) (đóng gói MSB-first y như ki0.a). */
    private val registry: List<Pair<LongArray, String>> by lazy {
        ManeuverRegistry.RAW.map { (bits, name) ->
            val s = LongArray(WORDS)
            for (i in bits.indices) if (bits[i] == '1') s[i ushr 6] = s[i ushr 6] or (1L shl (63 - (i and 63)))
            s to name
        }
    }

    /** -> mã AMAP NEW_ICON từ ảnh mũi tên, hoặc null nếu mờ/không khớp. */
    fun classify(bmp: Bitmap?): Int? {
        if (bmp == null || bmp.width < 8 || bmp.height < 8) return null
        val s = signature(bmp) ?: run { set("(mờ)", -1); return null }
        val name = match(s.bits) ?: matchNCC(s.fill)   // #3: Hamming trượt → NCC fallback (suy giảm dần)
            ?: run { set("(không khớp)", -1); Log.i(TAG, "no match (Hamming>$MAX_HAMMING, NCC<$NCC_MIN)"); return null }
        val amap = nameToAmap(name)
        set(name, amap)
        Log.i(TAG, "sig '$name' -> amap=$amap")
        return amap
    }

    private fun set(n: String, a: Int) { lastName = n; lastAmap = a }

    /** -> mã icon HAL GỐC (1..49, enum HudController) từ ảnh, hoặc null. Cho đường ghi-thẳng-HAL (dadb/NavOpen). */
    fun classifyHal(bmp: Bitmap?): Int? {
        if (bmp == null || bmp.width < 8 || bmp.height < 8) return null
        val s = signature(bmp) ?: return null
        val name = match(s.bits) ?: matchNCC(s.fill) ?: return null   // #3: NCC fallback
        return nameToHal(name)
    }

    // ── chữ ký 225-bit (port wm0.c -> wm0.b với f=1.0, z=false) ──
    private fun signature(bmp: Bitmap): Sig? {
        val w = bmp.width; val h = bmp.height
        val n = w * h
        if (n <= 0) return null
        val px = IntArray(n)
        runCatching { bmp.getPixels(px, 0, w, 0, 0, w, h) }.getOrElse { return null }

        // z2: ảnh có alpha? — app gốc CHỈ xét pixel[0] (giữ y nguyên dù lạ; large-icon góc trên trong suốt).
        val hasAlpha = ((px[0] ushr 24) and 0xFF) < 255

        val gray = IntArray(n)
        val alpha = if (hasAlpha) IntArray(n) else IntArray(0)
        var opaque = 0; var graySum = 0L
        for (i in 0 until n) {
            val c = px[i]
            val g = (((c ushr 16) and 0xFF) + ((c ushr 8) and 0xFF) + (c and 0xFF)) / 3
            gray[i] = g
            if (hasAlpha) {
                val a = (c ushr 24) and 0xFF
                alpha[i] = a
                if (a > 0) { opaque++; graySum += g }
            }
        }

        // z3: cực tính (mũi tên sáng-trên-tối hay tối-trên-sáng) -> chọn nền composite.
        val z3 = if (!hasAlpha || opaque >= n) median(gray, n) >= 128
                 else !(opaque > 0 && graySum.toFloat() / opaque >= 128f)

        // composite + min/max độ sáng.
        val comp: IntArray
        var mn = 255; var mx = 0
        if (hasAlpha) {
            comp = IntArray(n)
            val bg = if (z3) 255 else 47
            for (i in 0 until n) {
                val a = alpha[i]
                val v = ((255 - a) * bg + gray[i] * a) / 255
                comp[i] = v
                if (v > mx) mx = v; if (v < mn) mn = v
            }
        } else {
            comp = gray
            for (i in 0 until n) { val v = gray[i]; if (v > mx) mx = v; if (v < mn) mn = v }
        }
        val contrast = mx - mn
        if (contrast < 30) return null                 // quá mờ -> bỏ (wm0.b trả null)

        val thr = mn + contrast                        // z4=false, f=1.0 -> ngưỡng = max; đếm pixel >= max

        val sig = LongArray(WORDS)
        val fill = FloatArray(BITS)                    // #3: tỉ lệ lấp mỗi ô (0..1) → dùng cho NCC fallback
        var cell = 0
        for (gy in 0 until GRID) {
            val y0 = gy * h / GRID
            var y1 = (gy + 1) * h / GRID; if (y1 > h) y1 = h
            for (gx in 0 until GRID) {
                val x0 = gx * w / GRID
                var x1 = (gx + 1) * w / GRID; if (x1 > w) x1 = w
                val area = (y1 - y0) * (x1 - x0)
                var cnt = 0
                var yy = y0
                while (yy < y1) {
                    val base = yy * w
                    var xx = x0
                    while (xx < x1) { if (comp[base + xx] >= thr) cnt++; xx++ }
                    yy++
                }
                if (area > 0) fill[cell] = cnt.toFloat() / area
                if (cnt > (area * 0.5f).toInt()) sig[cell ushr 6] = sig[cell ushr 6] or (1L shl (63 - (cell and 63)))
                cell++
            }
        }
        return Sig(sig, fill)
    }

    // #3: NCC FALLBACK — khi Hamming trượt (GMaps đổi style icon → chữ ký lệch >18 bit = "vực im lặng"), khớp mềm
    // bằng normalized cross-correlation giữa tỉ-lệ-lấp-ô (grayscale) và 38 template (bit 0/1) → suy giảm dần thay vì null.
    private class Sig(val bits: LongArray, val fill: FloatArray)   // gói bits+fill, truyền tường minh (bỏ field ngầm → thread-safe)
    private val grayRegistry: List<Pair<FloatArray, String>> by lazy {
        ManeuverRegistry.RAW.map { (bits, name) -> FloatArray(bits.length) { if (bits[it] == '1') 1f else 0f } to name }
    }
    private const val NCC_MIN = 0.45f              // ngưỡng khớp mềm (thực nghiệm; dưới = coi như không ra)

    private fun matchNCC(q: FloatArray): String? {
        var mq = 0f; for (v in q) mq += v; mq /= q.size
        var vq = 0f; for (v in q) { val dq = v - mq; vq += dq * dq }
        if (vq < 1e-6f) return null
        var best: String? = null; var bestNcc = NCC_MIN
        for ((t, name) in grayRegistry) {
            if (t.size != q.size) continue
            var mt = 0f; for (v in t) mt += v; mt /= t.size
            var cov = 0f; var vt = 0f
            for (i in q.indices) { val dq = q[i] - mq; val dt = t[i] - mt; cov += dq * dt; vt += dt * dt }
            if (vt < 1e-6f) continue
            val ncc = cov / kotlin.math.sqrt(vq * vt)
            if (ncc > bestNcc) { bestNcc = ncc; best = name }
        }
        return best
    }

    /** Trung vị histogram (port wm0.a). */
    private fun median(arr: IntArray, n: Int): Int {
        val hist = IntArray(256)
        for (i in 0 until n) { var v = arr[i]; if (v < 0) v = 0 else if (v > 255) v = 255; hist[v]++ }
        val half = n / 2 + 1
        var cum = 0
        for (v in 0..255) { cum += hist[v]; if (cum >= half) return v }
        return 0
    }

    /** Khớp gần nhất theo Hamming ≤18 (port wm0.d). null nếu không có. */
    private fun match(sig: LongArray): String? {
        var best: String? = null; var bestD = Int.MAX_VALUE
        for ((reg, name) in registry) {
            var d = 0
            for (k in 0 until WORDS) d += java.lang.Long.bitCount(sig[k] xor reg[k])
            if (d == 0) return name
            if (d <= MAX_HAMMING && d < bestD) { bestD = d; best = name }
        }
        return best
    }

    /** Tên maneuver (app gốc) -> mã AMAP NEW_ICON của ta (đặc thù trước, generic sau). */
    private fun nameToAmap(name: String): Int = when {
        name.contains("roundabout") -> 11    // Q1: xét TRƯỚC u_turn — tên "roundabout..._u_turn" phải ra vòng-xuyến, không phải quay-đầu
        name.contains("u_turn") -> 8
        name.contains("destination") -> 15
        // "depart"/"start" = bước đầu GMaps ("Head/Đi về hướng...") = ĐI THẲNG ra đường, KHÔNG phải điểm-mốc.
        // Trước map -> 1 (glyph "hình ghim + xe" start-point) khiến lúc bắt đầu đi cụm hiện ghim thay vì mũi tên thẳng.
        name.contains("depart") || name.contains("start") -> 9
        name.contains("sharp_left") -> 6
        name.contains("sharp_right") -> 7
        name.contains("slight_left") || name.contains("fork_left") -> 4
        name.contains("slight_right") || name.contains("fork_right") -> 5
        name.contains("normal_left") || name.contains("turn_left") -> 2
        name.contains("normal_right") || name.contains("turn_right") -> 3
        name.contains("merge") -> 5
        name.contains("straight") -> 9
        name.endsWith("_left") -> 2
        name.endsWith("_right") -> 3
        else -> 9
    }

    /** Tên maneuver -> mã icon HAL gốc (enum HudController TURN_ICON_*, port w40.a). Vòng xuyến gộp ~đúng. */
    private fun nameToHal(name: String): Int = when {
        name.contains("roundabout") -> 20          // Q1: xét TRƯỚC u_turn (tên roundabout..._u_turn = vòng-xuyến); chi tiết 15-22 để sau
        name.contains("u_turn_left") -> 9
        name.contains("u_turn_right") -> 10
        name.contains("u_turn") -> 9
        name.contains("destination") -> 48
        name.contains("depart") -> 12
        name.contains("sharp_left") -> 7
        name.contains("sharp_right") -> 8
        name.contains("slight_left") || name.contains("fork_left") -> 3
        name.contains("slight_right") || name.contains("fork_right") -> 5
        name.contains("normal_left") || name.contains("turn_left") -> 1
        name.contains("normal_right") || name.contains("turn_right") -> 2
        name.contains("merge") -> 11
        name.contains("straight") -> 11
        name.endsWith("_left") -> 1
        name.endsWith("_right") -> 2
        else -> 11
    }
}

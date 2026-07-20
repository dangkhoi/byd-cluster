package com.byd.clusternav.modules.clustercast

/**
 * CẤU HÌNH SCALE PER-APP khi chiếu lên cụm (R6). Thay model global DPI+inset cố định.
 *
 *  • [dpi]   = `wm density -d <VD>` — độ lớn nội dung (120..320).
 *  • rect L/T/R/B = bounds `am task resize` theo px cụm THẬT (vd Seal 1920×720).
 *    rect = -1 (bất kỳ cạnh) → AUTO = full VD [0,0,W,H] (GIỮ hành vi cũ cho app chưa cấu hình).
 *
 * Serialize 1 app: "dpi,l,t,r,b". Map nhiều app: "pkg=dpi,l,t,r,b|pkg2=...". Round-trip qua [parse]/[serialize].
 * Data class thuần (không phụ thuộc Android) → unit-test off-device được.
 */
data class AppScale(
    val dpi: Int = 200,
    val rectL: Int = -1,
    val rectT: Int = -1,
    val rectR: Int = -1,
    val rectB: Int = -1,
) {
    /** true = chưa cấu hình rect (bất kỳ cạnh nào < 0) → dùng full VD. */
    val isAuto: Boolean get() = rectL < 0 || rectT < 0 || rectR < 0 || rectB < 0

    /** Bounds THỰC trên VD kích thước [w]×[h]. Auto → full VD [0,0,w,h]. Trả [l,t,r,b]. */
    fun boundsOn(w: Int, h: Int): IntArray =
        if (isAuto) intArrayOf(0, 0, w, h) else intArrayOf(rectL, rectT, rectR, rectB)

    /** "dpi,l,t,r,b" — dùng trong map serialize. */
    fun serialize(): String = "$dpi,$rectL,$rectT,$rectR,$rectB"

    /**
     * Nới/thu khung QUANH TÂM. [dW]/[dH] = tổng thay đổi ngang/dọc (px; + = to ra, − = nhỏ lại).
     * Đang AUTO → materialize từ full VD [0,0,w,h] trước rồi mới nudge. Clamp trong [0,w]/[0,h] + [MIN_PX].
     */
    fun nudgeRect(w: Int, h: Int, dW: Int, dH: Int): AppScale {
        val b = boundsOn(w, h)
        var l = b[0] - dW / 2
        var r = b[2] + dW / 2
        var t = b[1] - dH / 2
        var bot = b[3] + dH / 2
        val maxL = (w - MIN_PX).coerceAtLeast(0)
        val maxT = (h - MIN_PX).coerceAtLeast(0)
        l = l.coerceIn(0, maxL)
        r = r.coerceIn((l + MIN_PX).coerceAtMost(w), w)
        t = t.coerceIn(0, maxT)
        bot = bot.coerceIn((t + MIN_PX).coerceAtMost(h), h)
        return copy(rectL = l, rectT = t, rectR = r, rectB = bot)
    }

    /** Đổi DPI ± [d] nấc, GIỮ nguyên rect. Clamp [DPI_MIN,DPI_MAX]. */
    fun nudgeDpi(d: Int): AppScale = copy(dpi = (dpi + d).coerceIn(DPI_MIN, DPI_MAX))

    /**
     * Chỉnh 1 CẠNH độc lập: dời cạnh [edge] đi [delta] px, TỰ TÍNH LẠI kích thước (không căn giữa).
     * delta<0 = cạnh dịch sang TRÁI/LÊN · delta>0 = sang PHẢI/XUỐNG. AUTO/full → materialize [0,0,w,h] trước.
     * Clamp trong [0,w]/[0,h], không để 2 cạnh đối vượt nhau (giữ tối thiểu [MIN_PX]).
     */
    fun nudgeEdge(w: Int, h: Int, edge: Edge, delta: Int): AppScale {
        val b = boundsOn(w, h)
        var l = b[0]; var t = b[1]; var r = b[2]; var bot = b[3]
        when (edge) {
            Edge.LEFT   -> l = (l + delta).coerceIn(0, r - MIN_PX)
            Edge.RIGHT  -> r = (r + delta).coerceIn(l + MIN_PX, w)
            Edge.TOP    -> t = (t + delta).coerceIn(0, bot - MIN_PX)
            Edge.BOTTOM -> bot = (bot + delta).coerceIn(t + MIN_PX, h)
        }
        return copy(rectL = l, rectT = t, rectR = r, rectB = bot)
    }

    /**
     * DỜI cả khung đi ([dx],[dy]) px, GIỮ NGUYÊN kích thước (dùng cho D-pad "Vị trí" ◀▲▼▶).
     * dx<0 = sang TRÁI · dx>0 = PHẢI · dy<0 = LÊN · dy>0 = XUỐNG. AUTO/full → materialize [0,0,w,h] trước.
     * Clamp để khung không tràn ra ngoài VD (dán mép khi chạm biên). Khung đã full VD → không còn chỗ dời (giữ nguyên).
     */
    fun nudgeMove(w: Int, h: Int, dx: Int, dy: Int): AppScale {
        val b = boundsOn(w, h)
        val bw = b[2] - b[0]
        val bh = b[3] - b[1]
        val l = (b[0] + dx).coerceIn(0, (w - bw).coerceAtLeast(0))
        val t = (b[1] + dy).coerceIn(0, (h - bh).coerceAtLeast(0))
        return copy(rectL = l, rectT = t, rectR = l + bw, rectB = t + bh)
    }

    /** 4 cạnh khung để chỉnh độc lập. */
    enum class Edge { LEFT, TOP, RIGHT, BOTTOM }

    companion object {
        const val DPI_MIN = 120
        const val DPI_MAX = 320
        const val MIN_PX = 160   // khung tối thiểu (không co về 0)
        const val STEP_WH = 16   // 1 nấc mũi tên chỉnh cạnh Trái/Phải/Trên/Dưới (px)
        const val STEP_DPI = 10  // 1 nấc mũi tên DPI

        /** parse "dpi,l,t,r,b". null nếu sai định dạng (số phần ≠ 5 hoặc không phải số). */
        fun parse(s: String): AppScale? {
            val p = s.split(",")
            if (p.size != 5) return null
            val n = IntArray(5)
            for (i in 0 until 5) n[i] = p[i].trim().toIntOrNull() ?: return null
            return AppScale(n[0], n[1], n[2], n[3], n[4])
        }

        /** map pkg→AppScale → "pkg=dpi,l,t,r,b|...". Bỏ pkg rỗng. */
        fun serializeMap(m: Map<String, AppScale>): String =
            m.entries.filter { it.key.isNotEmpty() }
                .joinToString("|") { "${it.key}=${it.value.serialize()}" }

        /** "pkg=dpi,l,t,r,b|..." → map. Bỏ qua entry hỏng (không ném). */
        fun parseMap(s: String): Map<String, AppScale> {
            if (s.isBlank()) return emptyMap()
            val out = LinkedHashMap<String, AppScale>()
            for (part in s.split("|")) {
                if (part.isBlank()) continue
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val pkg = part.substring(0, eq).trim()
                if (pkg.isEmpty()) continue
                val sc = parse(part.substring(eq + 1)) ?: continue
                out[pkg] = sc
            }
            return out
        }
    }
}

package com.byd.clusternav

/**
 * Logic NAV-DOMAIN cho chuỗi đưa lên cụm: rút gọn tên đường VN + map lệnh rẽ -> mã icon AMAP.
 * Tách khỏi graphics (BitmapUtil) vì thay đổi vì lý do khác (buffer firmware / ngôn ngữ vs xử lý bitmap).
 */
object NavFormat {

    // ── Tên đường: ô NEXT_PATHNAME trên cụm là buffer cố định UTF-16LE (~16 byte = ~8 ký tự BMP),
    //    firmware Hán hoá -> tên VN dài bị CỤT ("Nguyễn Huệ" -> "Nguyễn "). Cụm hard-cut, KHÔNG marquee.
    //    => rút gọn phía client TRƯỚC khi putExtra. (Số 8 là đo thực nghiệm; chỉnh nếu còn cụt.)
    //    Mỗi ký tự VN có dấu vẫn 1 code-unit UTF-16 = 2 byte, nên BỎ DẤU không giúp tiết kiệm -> giữ dấu.
    //    ĐO TRÊN XE (2026-06-23): ô hiện ~7 ký tự rồi "…" -> đặt 7 cho hiện gọn, không bị "…".
    const val ROAD_MAX_UNITS = 7

    // tiền tố động từ rẽ + FILLER vô nghĩa ("về hướng"...) — bỏ để chỉ còn tên đường.
    private val MANEUVER_PREFIX = Regex(
        "^(rẽ phải vào|rẽ trái vào|re phai vao|re trai vao|đi thẳng( trên)?|di thang( tren)?|" +
        "quay đầu( tại)?|quay dau( tai)?|tiếp tục( trên| đi)?|tiep tuc( tren| di)?|nhập vào|nhap vao|" +
        "về hướng|ve huong|về phía|ve phia|hướng về|huong ve|hướng tới|huong toi|đi về|di ve|" +
        "vào|vao|theo|đi tới|di toi|turn (left|right) onto|onto|continue onto|" +
        "head (north|south|east|west)( on)?|merge onto|take the)\\s+",
        RegexOption.IGNORE_CASE
    )
    // tiền tố loại đường THƯỜNG -> bỏ hẳn (KHÔNG bỏ hầm/cầu/bến — những cái có nghĩa, giữ lại).
    private val ROAD_PREFIX = Regex(
        "^(đường|duong|phố|pho|ngõ|ngo|hẻm|hem|ngách|ngach)\\s+",
        RegexOption.IGNORE_CASE
    )
    // loại đường có-số -> viết tắt, dính liền số: "Quốc lộ 1A" -> "QL1A"
    private val ROAD_CLASS = listOf(
        Regex("^(quốc lộ|quoc lo)\\s*", RegexOption.IGNORE_CASE) to "QL",
        Regex("^(tỉnh lộ|tinh lo)\\s*", RegexOption.IGNORE_CASE) to "TL",
        Regex("^(đường tỉnh|duong tinh)\\s*", RegexOption.IGNORE_CASE) to "DT",
        Regex("^(cao tốc|cao toc)\\s*", RegexOption.IGNORE_CASE) to "CT",
        Regex("^(đại lộ|dai lo)\\s*", RegexOption.IGNORE_CASE) to "ĐL",
    )
    // Từ-loại CÓ NGHĨA ở đầu tên -> GIỮ (không bỏ, không viết tắt): hầm/cầu/bến...
    private val KEEP_CLASS = setOf("hầm", "ham", "cầu", "cau", "bến", "ben")

    /** Dọn tên đường (bỏ filler/động từ/ngoặc/Đường-Phố, GIỮ hầm/cầu) — trả tên ĐẦY ĐỦ (dùng cho marquee). */
    fun cleanRoadName(road: String): String {
        var s = road.trim()
        if (s.isEmpty()) return s
        s = s.substringBefore(",").trim()                       // bỏ phần sau dấu phẩy
        s = s.replace(Regex("\\s*\\(.*?\\)\\s*"), " ").trim()    // bỏ phần trong ngoặc
        s = MANEUVER_PREFIX.replace(s, "").trim()                // bỏ động từ + filler ("về hướng")
        for ((re, abbr) in ROAD_CLASS) if (re.containsMatchIn(s)) { s = re.replace(s, abbr); break }
        s = ROAD_PREFIX.replace(s, "").trim()                    // bỏ "Đường/Phố/Ngõ..." (giữ hầm/cầu)
        return s.replace(Regex("\\s+"), " ").trim().ifEmpty { road.trim() }
    }

    /** Rút gọn cho ô cụm khi KHÔNG cuộn (~7 ký tự). "Nguyễn Hữu Cảnh"->"NHC"; "hầm X Y"->"hầm XY". */
    fun fitRoadName(road: String): String {
        val s = cleanRoadName(road)
        if (s.length <= ROAD_MAX_UNITS) return s
        val words = s.split(" ")
        // giữ nguyên từ-loại có nghĩa ở đầu (hầm/cầu) + viết tắt phần còn lại: "hầm Nguyễn Hữu Cảnh" -> "hầm NHC"
        if (words.size >= 2 && words[0].lowercase() in KEEP_CLASS) {
            val acr = words.drop(1).joinToString("") { it.take(1) }
            val cand = "${words[0]} $acr"
            return if (cand.length <= ROAD_MAX_UNITS) cand else cand.take(ROAD_MAX_UNITS)
        }
        if (words.size >= 2) {
            val dotted = words.dropLast(1).joinToString("") { it.take(1) + "." } + words.last()
            if (dotted.length <= ROAD_MAX_UNITS) return dotted        // "Trần Trọng Kim" -> "T.T.Kim"
            val acronym = words.joinToString("") { it.take(1) }       // dài hơn -> "NHC"/"CMTT"
            if (acronym.length <= ROAD_MAX_UNITS) return acronym
            return dotted.take(ROAD_MAX_UNITS)
        }
        return s.take(ROAD_MAX_UNITS)
    }

    /** MARQUEE: cửa sổ [width] ký tự của [name], trượt theo [tick] (mỗi nhịp +1). Tên ngắn -> trả nguyên. */
    fun roadWindow(name: String, tick: Int, width: Int): String {
        if (name.length <= width) return name
        val loop = "$name   "                                        // tên + khoảng nghỉ trước khi lặp
        val off = ((tick % loop.length) + loop.length) % loop.length
        return (loop + loop).substring(off, off + width)
    }

    /**
     * Lệnh rẽ (text VN/EN) -> mã NEW_ICON của AMAP (index 0..28; AmapService TỰ remap qua TurnIdMapToCAN,
     * KHÔNG tự remap ở đây). Bảng từ AmapService.TURN_STRING (đã decompile, chuẩn cho firmware này).
     * VN đi bên phải (RHT) -> dùng hàng RHT (vòng xuyến 11, quay đầu 8).
     */
    fun maneuverToAmapIcon(text: String): Int = maneuverVerbIcon(text) ?: 9

    /** Như trên nhưng trả null nếu chữ KHÔNG có động từ rẽ (để builder fallback sang đọc ẢNH mũi tên). */
    fun maneuverVerbIcon(text: String): Int? {
        val t = text.lowercase()
        return when {
            Regex("quay đầu|quay dau|u-?turn|làm vòng").containsMatchIn(t) -> 8
            Regex("ngoặt trái|ngoat trai|sharp left").containsMatchIn(t) -> 6
            Regex("ngoặt phải|ngoat phai|sharp right").containsMatchIn(t) -> 7
            Regex("chếch trái|chech trai|hơi trái|hoi trai|slight left|keep left").containsMatchIn(t) -> 4
            Regex("chếch phải|chech phai|hơi phải|hoi phai|slight right|keep right").containsMatchIn(t) -> 5
            Regex("rẽ trái|re trai|quẹo trái|turn left|left onto").containsMatchIn(t) -> 2
            Regex("rẽ phải|re phai|quẹo phải|turn right|right onto").containsMatchIn(t) -> 3
            Regex("vòng xuyến|vong xuyen|bùng binh|bung binh|roundabout|rotary").containsMatchIn(t) -> 11
            Regex("đến nơi|den noi|điểm đến|diem den|arrive|destination").containsMatchIn(t) -> 15
            Regex("đi thẳng|di thang|go straight|^straight").containsMatchIn(t) -> 9
            Regex("tiếp tục|tiep tuc|continue|theo đường|theo duong|follow").containsMatchIn(t) -> 20
            else -> null   // không có động từ -> để tên small-icon (IconResource) quyết định
        }
    }

    /** Bỏ dấu + gộp ký tự lạ thành '_' -> token AN TOÀN cho shell-arg (đường dadb/NavOpen, args tách bằng space).
     *  Mirror HudTextSanitizer của reference (NFD strip + đ→d). KHÔNG dùng cho path broadcast (ô đó nhận space/dấu OK). */
    fun asciiToken(s: String): String {
        val noDiac = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace("đ", "d").replace("Đ", "D")
        return noDiac.trim().replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifEmpty { "Road" }
    }

    /** Số nhánh ra ở vòng xuyến nếu lệnh có ("lối ra thứ 3" / "3rd exit" / "take the 2nd exit"). -1 nếu không có. */
    fun roundaboutExit(text: String): Int {
        val t = text.lowercase()
        // B3: KHÔNG để "the" trơ — nó khớp "on the 1", "the 5 freeway" → ép glyph vòng-xuyến giả cho lệnh đi thẳng.
        // Chỉ nhận "the" khi có ngữ cảnh vòng-xuyến ("take/at the N"); còn lại là lối-ra/nhánh/exit/thứ-tự-số + "exit".
        val m = Regex("""(?:lối ra|loi ra|nhánh|nhanh|exit|(?:take|at) the)\s*(?:thứ|thu)?\s*(\d+)""").find(t)
            ?: Regex("""(\d+)\s*(?:st|nd|rd|th)\s+exit""").find(t)
        return m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
    }
}

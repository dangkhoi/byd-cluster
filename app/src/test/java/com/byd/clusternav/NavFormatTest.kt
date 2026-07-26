package com.byd.clusternav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Unit test THUẦN cho NavFormat — dọn/rút gọn tên đường VN + map lệnh rẽ → mã icon AMAP. */
class NavFormatTest {

    @Test fun `cleanRoadName bỏ động từ rẽ`() {
        assertEquals("Nguyễn Huệ", NavFormat.cleanRoadName("Rẽ phải vào Nguyễn Huệ"))
    }

    @Test fun `cleanRoadName bỏ tiền tố Đường-Phố`() {
        assertEquals("Nguyễn Huệ", NavFormat.cleanRoadName("Đường Nguyễn Huệ"))
    }

    @Test fun `cleanRoadName viết tắt loại đường có số`() {
        assertEquals("QL1A", NavFormat.cleanRoadName("Quốc lộ 1A"))
    }

    @Test fun `cleanRoadName GIỮ hầm-cầu (có nghĩa)`() {
        assertEquals("Hầm Thủ Thiêm", NavFormat.cleanRoadName("Hầm Thủ Thiêm"))
    }

    @Test fun `fitRoadName rút gọn về acronym khi quá dài`() {
        assertEquals("NHC", NavFormat.fitRoadName("Nguyễn Hữu Cảnh"))
    }

    @Test fun `fitRoadName giữ từ-loại hầm ở đầu`() {
        assertEquals("Hầm TT", NavFormat.fitRoadName("Hầm Thủ Thiêm"))
    }

    // ── T5: HUD street-name abbreviation — fitRoadName(road, maxUnits) + NFC (D4/F7/F8) ──

    @Test fun `fitRoadName 'Trần Trọng Kim' @7 → dotted 'T_T_Kim' (len 7)`() {
        val out = NavFormat.fitRoadName("Trần Trọng Kim", 7)
        assertEquals("T.T.Kim", out)
        assertEquals(7, out.length)
    }

    @Test fun `fitRoadName 'Võ Nguyên Giáp' @7 → acronym 'VNG' (dotted 'V_N_Giáp' là 8 gt 7)`() {
        // dotted "V.N.Giáp" = 8 code-unit > 7 → rớt xuống acronym.
        assertEquals("VNG", NavFormat.fitRoadName("Võ Nguyên Giáp", 7))
    }

    @Test fun `fitRoadName 'Võ Nguyên Giáp' @8 → dotted 'V_N_Giáp'`() {
        val out = NavFormat.fitRoadName("Võ Nguyên Giáp", 8)
        assertEquals("V.N.Giáp", out)
        assertEquals(8, out.length)
    }

    @Test fun `fitRoadName 'Nguyễn Hữu Cảnh' @7 → acronym 'NHC'`() {
        // dotted "N.H.Cảnh" = 8 > 7 → acronym "NHC".
        assertEquals("NHC", NavFormat.fitRoadName("Nguyễn Hữu Cảnh", 7))
    }

    @Test fun `fitRoadName 'Quốc lộ 1A' → 'QL1A' (viết tắt loại đường, cả @7 và default)`() {
        assertEquals("QL1A", NavFormat.fitRoadName("Quốc lộ 1A", 7))
        assertEquals("QL1A", NavFormat.fitRoadName("Quốc lộ 1A"))
    }

    @Test fun `fitRoadName 'hầm Thủ Thiêm' @7 → giữ từ-loại 'hầm TT'`() {
        // "hầm" ∈ KEEP_CLASS → giữ nguyên + viết tắt phần còn lại. "hầm TT" = 6 ≤ 7.
        assertEquals("hầm TT", NavFormat.fitRoadName("hầm Thủ Thiêm", 7))
    }

    @Test fun `fitRoadName single word — ngắn giữ nguyên, dài thì cắt`() {
        assertEquals("Láng", NavFormat.fitRoadName("Láng", 7))       // 1 từ, ≤ budget → nguyên
        assertEquals("Cati", NavFormat.fitRoadName("Catinat", 4))     // 1 từ, > budget → take(maxUnits)
    }

    @Test fun `fitRoadName empty-blank → empty`() {
        assertEquals("", NavFormat.fitRoadName("", 7))
        assertEquals("", NavFormat.fitRoadName("   ", 7))
        assertEquals("", NavFormat.fitRoadName("", NavFormat.HUD_ROAD_MAX_UNITS))
    }

    @Test fun `fitRoadName NFC-normalizes NFD input — length correct (không viết tắt oan)`() {
        // "Hà Nội": NFC = 6 code-unit (≤7 → GIỮ NGUYÊN). NFD (dấu tách rời) = 9 code-unit (>7).
        // Không normalize → tưởng dài >7 → viết tắt nhầm. Normalize xong đo đúng 6 → giữ nguyên.
        val nfc = "Hà Nội"
        val nfd = java.text.Normalizer.normalize(nfc, java.text.Normalizer.Form.NFD)
        assertTrue(nfd.length > nfc.length, "NFD phải nhiều code-unit hơn NFC")
        val out = NavFormat.fitRoadName(nfd, 7)
        assertEquals(NavFormat.fitRoadName(nfc, 7), out, "NFD phải cho KẾT QUẢ như NFC (normalize bên trong)")
        assertEquals(6, out.length, "đo theo NFC (6), KHÔNG phải NFD (9)")
        assertTrue(java.text.Normalizer.isNormalized(out, java.text.Normalizer.Form.NFC), "output ở dạng NFC")
        assertFalse(out.contains('\u0300'), "không sót combining grave (U+0300)")
    }

    @Test fun `fitRoadName NFC-normalizes NFD input — take(1) giữ dấu khi viết tắt`() {
        // "Ông Ích Khiêm" >7 → acronym. Chữ đầu MANG DẤU (Ô, Í). take(1) trên NFD cắt base 'O'/'I'
        // rời khỏi dấu kết hợp → mất dấu ("OIK"). Normalize NFC trước → giữ "ÔÍK" (dấu nguyên vẹn).
        val nfd = java.text.Normalizer.normalize("Ông Ích Khiêm", java.text.Normalizer.Form.NFD)
        val out = NavFormat.fitRoadName(nfd, 7)
        assertEquals(NavFormat.fitRoadName("Ông Ích Khiêm", 7), out)
        assertEquals(3, out.length, "acronym 3 ký tự, mỗi ký tự 1 code-unit NFC")
        assertTrue(java.text.Normalizer.isNormalized(out, java.text.Normalizer.Form.NFC))
        assertFalse(out.contains('O'), "KHÔNG thoái hoá về ASCII 'O' (mất dấu Ô)")
        assertFalse(out.contains('\u0302'), "không sót combining circumflex (U+0302)")
    }

    @Test fun `fitRoadName byte-budget ≤ 2×maxUnits (UTF-16LE, đúng buffer BYTE của cụm-HUD)`() {
        val samples = listOf(
            "Trần Trọng Kim", "Võ Nguyên Giáp", "Nguyễn Hữu Cảnh",
            "Điện Biên Phủ", "Cách Mạng Tháng Tám", "hầm Thủ Thiêm", "Quốc lộ 1A", "Hà Nội"
        )
        for (u in listOf(7, 8)) for (r in samples) {
            val out = NavFormat.fitRoadName(r, u)
            val bytes = out.toByteArray(Charsets.UTF_16LE).size
            assertTrue(bytes <= 2 * u, "'$r'@$u → '$out' (${out.length} unit) = $bytes byte > ${2 * u}")
            assertTrue(out.length <= u, "'$r'@$u → '$out' dài ${out.length} unit > $u")
        }
    }

    @Test fun `fitRoadName default-arg == gọi tường minh ROAD_MAX_UNITS (backward-compat cụm)`() {
        val samples = listOf(
            "Nguyễn Hữu Cảnh", "Trần Trọng Kim", "Quốc lộ 1A", "Hầm Thủ Thiêm",
            "Võ Nguyên Giáp", "Hà Nội", "Láng", "", "Rẽ phải vào Nguyễn Huệ"
        )
        for (r in samples) {
            assertEquals(
                NavFormat.fitRoadName(r, NavFormat.ROAD_MAX_UNITS), NavFormat.fitRoadName(r),
                "default-arg phải == ROAD_MAX_UNITS cho '$r'"
            )
        }
    }

    @Test fun `HUD_ROAD_MAX_UNITS = 7 = ROAD_MAX_UNITS (F7 — an toàn theo đo xe, KHÔNG 8)`() {
        // F7 BINDING: buffer HUD chưa trace → giữ = ngân sách cụm đã đo (7). Test này khoá lại
        // để ai nâng lên 8 mà chưa trace xe sẽ bị đỏ (tràn = firmware bỏ tên = bug đang chữa).
        assertEquals(7, NavFormat.HUD_ROAD_MAX_UNITS)
        assertEquals(NavFormat.ROAD_MAX_UNITS, NavFormat.HUD_ROAD_MAX_UNITS)
    }

    @Test fun `roadWindow tên ngắn trả nguyên, tên dài đúng bề rộng`() {
        assertEquals("ABC", NavFormat.roadWindow("ABC", 5, 7))
        val w = NavFormat.roadWindow("ABCDEFGHIJ", 0, 5)
        assertEquals(5, w.length)
        assertEquals("ABCDE", w)
    }

    @Test fun `maneuverVerbIcon map đúng hướng`() {
        assertEquals(3, NavFormat.maneuverVerbIcon("Rẽ phải vào X"))
        assertEquals(2, NavFormat.maneuverVerbIcon("rẽ trái"))
        assertEquals(8, NavFormat.maneuverVerbIcon("quay đầu"))
        assertEquals(9, NavFormat.maneuverVerbIcon("đi thẳng"))
        assertEquals(11, NavFormat.maneuverVerbIcon("vòng xuyến"))
        assertNull(NavFormat.maneuverVerbIcon("blah blah"))
    }

    @Test fun `maneuverToAmapIcon fallback đi thẳng`() {
        assertEquals(9, NavFormat.maneuverToAmapIcon("chuỗi không có động từ"))
    }

    @Test fun `roundaboutExit rút số nhánh`() {
        assertEquals(3, NavFormat.roundaboutExit("lối ra thứ 3"))
        assertEquals(2, NavFormat.roundaboutExit("take the 2nd exit"))
        assertEquals(-1, NavFormat.roundaboutExit("đi thẳng"))
    }

    @Test fun `asciiToken bỏ dấu + gộp ký tự lạ`() {
        assertEquals("Nguyen_Hue", NavFormat.asciiToken("Nguyễn Huệ"))
        assertEquals("Duong", NavFormat.asciiToken("Đường"))
        assertEquals("Road", NavFormat.asciiToken("!!!"))   // rỗng sau khi lọc → fallback
    }

    @Test fun `asciiToken an toàn cho shell-arg (chỉ chữ-số-gạch dưới)`() {
        val t = NavFormat.asciiToken("Cầu Sài Gòn (Q.2); rm -rf /")
        assertTrue(Regex("^[A-Za-z0-9_]+$").matches(t), "token phải an toàn shell: '$t'")
    }
}

package com.byd.clusternav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** So sánh version — thuần, test off-device. Khoá logic quyết định "có bản mới". */
class UpdateCheckerTest {
    @Test fun `moi hon`() {
        assertTrue(UpdateChecker.cmp("0.57", "0.56") > 0)
        assertTrue(UpdateChecker.cmp("0.56", "0.9") > 0, "0.56 > 0.9 vì 56 > 9 (không phải so chuỗi)")
        assertTrue(UpdateChecker.cmp("1.0", "0.99") > 0)
        assertTrue(UpdateChecker.cmp("0.56.1", "0.56") > 0)
    }
    @Test fun `bang hoac cu hon`() {
        assertEquals(0, UpdateChecker.cmp("0.56", "0.56"))
        assertEquals(0, UpdateChecker.cmp("0.56", "0.56.0"))
        assertTrue(UpdateChecker.cmp("0.55", "0.56") < 0)
    }

    // ── issue #A (1.31): OTA cài lại cùng version vô hạn ──
    @Test fun `shouldOffer chi khi thuc su moi hon`() {
        assertTrue(UpdateChecker.shouldOffer("1.31", "1.30"))            // mới hơn → mời
        assertFalse(UpdateChecker.shouldOffer("1.30", "1.30"))           // bằng → KHÔNG mời (hết loop)
        assertFalse(UpdateChecker.shouldOffer("1.30", "1.31"))           // cũ hơn → không mời
    }
    @Test fun `shouldOffer FAIL-CLOSED khi khong doc duoc version cai`() {
        // getPackageInfo trả rỗng → currentVersion()=UNKNOWN. Trước đây cmp("1.30","?")>0 → mời mãi (loop).
        assertFalse(UpdateChecker.shouldOffer("1.30", UpdateChecker.UNKNOWN))
    }
    @Test fun `shouldInstall chan cai lai khi khong moi hon theo versionCode`() {
        assertTrue(UpdateChecker.shouldInstall(131, 130))   // apk mới hơn → cài
        assertFalse(UpdateChecker.shouldInstall(130, 130))  // BẰNG vc → KHÔNG cài lại (đóng loop)
        assertFalse(UpdateChecker.shouldInstall(129, 130))  // apk cũ hơn → không cài
    }
    @Test fun `shouldInstall fail-open khi khong doc duoc versionCode`() {
        // getPackageArchiveInfo/getPackageInfo lỗi (-1) → không chặn (check() đã gác trước; tránh chặn nhầm update thật).
        assertTrue(UpdateChecker.shouldInstall(-1, 130))
        assertTrue(UpdateChecker.shouldInstall(131, -1))
        assertTrue(UpdateChecker.shouldInstall(-1, -1))
    }
}

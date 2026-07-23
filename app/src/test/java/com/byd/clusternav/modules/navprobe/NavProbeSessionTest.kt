package com.byd.clusternav.modules.navprobe

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Test SESSION-KEY v0.60-debug (RT2.1) — [NavProbe.shouldReuseFile].
 *
 * SỬA GỐC "log lẫn lộn mới/cũ" (feedback 23/07): file navprobe chỉ được tái dùng trong CÙNG phiên
 * (versionName + boot_count). Đổi version HOẶC reboot → phiên mới → FILE MỚI → không trộn 07-22 với hôm nay.
 * PURE → off-device, không cần xe.
 */
class NavProbeSessionTest {

    @Test
    fun `tai dung khi cung phien va file ton tai`() {
        assertTrue(NavProbe.shouldReuseFile("0.60@b5", "0.60@b5", savedFileExists = true))
    }

    /** ★ ĐÚNG BUG HIỆN TRƯỜNG: 07-22 (boot cũ) vs hôm nay (đã reboot) → boot_count đổi → KHÔNG tái dùng. */
    @Test
    fun `KHONG tai dung khi da reboot (boot_count doi)`() {
        assertFalse(NavProbe.shouldReuseFile("0.60@b4", "0.60@b5", savedFileExists = true))
    }

    @Test
    fun `KHONG tai dung khi doi version`() {
        assertFalse(NavProbe.shouldReuseFile("0.59@b5", "0.60@b5", savedFileExists = true))
    }

    @Test
    fun `KHONG tai dung khi file khong con ton tai`() {
        assertFalse(NavProbe.shouldReuseFile("0.60@b5", "0.60@b5", savedFileExists = false))
    }

    @Test
    fun `KHONG tai dung khi chua co phien luu (null)`() {
        assertFalse(NavProbe.shouldReuseFile(null, "0.60@b5", savedFileExists = true))
    }
}

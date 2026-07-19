package com.byd.clusternav.modules

import android.content.Context
import android.view.ViewGroup
import com.byd.clusternav.NavState

/** Kết quả self-test trên xe — tín hiệu keep-or-kill. ok=true → chấm xanh; detail luôn hiện để debug. */
data class SelfTest(val ok: Boolean, val detail: String) {
    companion object {
        fun pass(d: String = "OK") = SelfTest(true, d)
        fun fail(d: String) = SelfTest(false, d)
    }
}

/**
 * Một thí nghiệm/tính năng TỰ CHỨA. Cố tình buồn tẻ: module biết (a) tên, (b) cách tự dựng UI vào container
 * host cấp, và (tuỳ chọn) (c) phản ứng NavState live + (d) tự kiểm tra trên xe.
 *
 * Đường nav broadcast đã chứng minh KHÔNG BAO GIỜ gọi vào đây — [ModuleHost] gọi, và ModuleHost cũng xoá được.
 * Đăng ký = thêm 1 dòng trong [ModuleRegistry]. KHÔNG init/DI/annotation/reflection-scan.
 *
 * Quy ước XOÁ SẠCH: 1 module = 1 thư mục modules/<tên>/. Xoá = xoá thư mục + xoá 1 dòng trong ModuleRegistry.
 * Module KHÔNG phải Activity → không có dòng Manifest riêng → không residue.
 */
interface ClusterModule {
    /** Hiện trên nút launcher (MainActivity) + tiêu đề panel. Tiếng Việt OK. Dùng làm key Prefs. */
    val title: String

    /**
     * Dựng UI module vào [parent] (LinearLayout dọc, host sở hữu). Module tự add Button/TextView ở đây.
     * Gọi 1 lần khi user mở module. Việc nặng (dadb/HAL) PHẢI chạy trong Thread{}.
     */
    fun buildView(ctx: Context, parent: ViewGroup)

    /**
     * (Tuỳ chọn) Mỗi NavState live đường nav phát ra, CHỈ khi panel mở. CÙNG object cụm đã nhận — READ-ONLY.
     * Mặc định no-op. Host bọc trong try-catch nên ném ở đây KHÔNG giết feed cụm.
     */
    fun onNavState(ctx: Context, s: NavState) {}

    /**
     * (Tuỳ chọn) Self-test trên xe — cổng keep-or-kill. Chạy ở BACKGROUND thread (dadb/HAL block).
     * Mặc định pass ("không có self-test").
     */
    fun selfTest(ctx: Context): SelfTest = SelfTest.pass("không có self-test")

    /** (Tuỳ chọn) Panel hiện → bắt đầu poll/đăng ký. Mặc định no-op. */
    fun onShow(ctx: Context) {}

    /** (Tuỳ chọn) Panel ẩn/pause → dừng poll, nhả tài nguyên. Mặc định no-op. */
    fun onHide(ctx: Context) {}
}

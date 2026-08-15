package com.byd.clusternav.navigation

/**
 * Chế độ chọn nguồn dẫn đường. Chỉ là dữ liệu, không dính lưu trữ.
 *
 * Trước 2026-07-27 hai hằng số này nằm trong `Prefs` — lớp đọc/ghi SharedPreferences. Vì thế
 * `SourceArbiter`, một bộ quyết định thuần, phải phụ thuộc vào Android chỉ để biết hai con số. Giá trị
 * giữ nguyên (0 và 2) để dữ liệu đã lưu trên máy người dùng vẫn đọc đúng.
 */
object NavSourceMode {
    const val AUTO = 0
    const val PREFER_GMAPS = 2
}

package com.byd.clusternav.modules

import com.byd.clusternav.modules.dash.DashModule

/**
 * Nguồn sự thật DUY NHẤT về module nào tồn tại. MỘT dòng / module.
 * THÊM module = import + 1 dòng. XOÁ module = xoá thư mục modules/<tên>/ + xoá 1 dòng (+import). Hết.
 *
 * Đã DỌN (keep/kill, đã chứng minh trên xe): xoá dadbnav/inprochal/mapmode — ghi cụm in-proc/dadb trả rc=0
 * nhưng KHÔNG render (cụm chỉ vẽ qua AmapService/broadcast). GIỮ: tpms/vehicle (đọc HAL in-proc CHẠY THẬT),
 */
object ModuleRegistry {
    // Đã bỏ 2026-07-27 (đưa công tắc lên Home như V1): `NavRealtimeModule` và `NavAccessibilityModule` —
    // mỗi màn chỉ có một công tắc + một khung debug, tức một việc bật/tắt tốn hai lần bấm. Công tắc
    // `Prefs.interpolate` và `Prefs.accBooster` giờ nằm trên thẻ Navigation ở Home; `NavAccessibilityService`
    // (phần chạy thật, đọc màn dẫn đường) KHÔNG bị xoá, nó vẫn khai trong Manifest và vẫn đọc pref đó.
    //
    // Đã bỏ trước đó cùng ngày: `RemoteViewsModule` (vắt field ẩn trong RemoteViews của noti GMaps) và
    // `AudioCueModule` (bắt xung audio dẫn đường). Cả hai là màn DÒ THỬ: không tham chiếu từ đâu, không
    // ghi setting nào, nên không có gì trong sản phẩm phụ thuộc chúng. Giữ lại chỉ làm màn hình nhiều nút
    // hơn mà không thêm việc gì làm được.
    val MODULES: List<ClusterModule> = listOf(
        DashModule,         // bảng dữ liệu xe LIVE (TPMS 4 lốp + tốc độ/gear/nhiệt/pin) — đọc HAL no-root
    )
}

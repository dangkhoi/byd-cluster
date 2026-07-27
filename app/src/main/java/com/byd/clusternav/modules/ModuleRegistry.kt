package com.byd.clusternav.modules

import com.byd.clusternav.modules.dash.DashModule
import com.byd.clusternav.modules.navaccess.NavAccessibilityModule
import com.byd.clusternav.modules.navrealtime.NavRealtimeModule

/**
 * Nguồn sự thật DUY NHẤT về module nào tồn tại. MỘT dòng / module.
 * THÊM module = import + 1 dòng. XOÁ module = xoá thư mục modules/<tên>/ + xoá 1 dòng (+import). Hết.
 *
 * Đã DỌN (keep/kill, đã chứng minh trên xe): xoá dadbnav/inprochal/mapmode — ghi cụm in-proc/dadb trả rc=0
 * nhưng KHÔNG render (cụm chỉ vẽ qua AmapService/broadcast). GIỮ: tpms/vehicle (đọc HAL in-proc CHẠY THẬT),
 */
object ModuleRegistry {
    // Đã bỏ 2026-07-27: `RemoteViewsModule` (vắt field ẩn trong RemoteViews của noti GMaps) và
    // `AudioCueModule` (bắt xung audio dẫn đường). Cả hai là màn DÒ THỬ: không tham chiếu từ đâu, không
    // ghi setting nào, nên không có gì trong sản phẩm phụ thuộc chúng. Giữ lại chỉ làm màn hình nhiều nút
    // hơn mà không thêm việc gì làm được.
    val MODULES: List<ClusterModule> = listOf(
        NavRealtimeModule,  // nội suy cự ly-tới-rẽ theo tốc độ (hạ lag GMaps) — toggle + debug
        NavAccessibilityModule, // booster đọc UI GMaps trên màn -> tinh chỉnh cự ly (cần <service> Manifest + xml)
        DashModule,         // bảng dữ liệu xe LIVE (TPMS 4 lốp + tốc độ/gear/nhiệt/pin) — đọc HAL no-root
    )
}

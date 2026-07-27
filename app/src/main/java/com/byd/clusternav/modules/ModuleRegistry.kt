package com.byd.clusternav.modules

import com.byd.clusternav.modules.dash.DashModule
import com.byd.clusternav.modules.navaccess.NavAccessibilityModule
import com.byd.clusternav.modules.navaudiocue.AudioCueModule
import com.byd.clusternav.modules.navrealtime.NavRealtimeModule
import com.byd.clusternav.modules.navremoteviews.RemoteViewsModule

/**
 * Nguồn sự thật DUY NHẤT về module nào tồn tại. MỘT dòng / module.
 * THÊM module = import + 1 dòng. XOÁ module = xoá thư mục modules/<tên>/ + xoá 1 dòng (+import). Hết.
 *
 * Đã DỌN (keep/kill, đã chứng minh trên xe): xoá dadbnav/inprochal/mapmode — ghi cụm in-proc/dadb trả rc=0
 * nhưng KHÔNG render (cụm chỉ vẽ qua AmapService/broadcast). GIỮ: tpms/vehicle (đọc HAL in-proc CHẠY THẬT),
 */
object ModuleRegistry {
    val MODULES: List<ClusterModule> = listOf(
        NavRealtimeModule,  // nội suy cự ly-tới-rẽ theo tốc độ (hạ lag GMaps) — toggle + debug
        NavAccessibilityModule, // booster đọc UI GMaps trên màn -> tinh chỉnh cự ly (cần <service> Manifest + xml)
        RemoteViewsModule,  // vắt field ẩn trong RemoteViews noti GMaps (lever chưa khai thác)
        AudioCueModule,     // bắt xung audio dẫn đường (usage=12) — phần audio ăn được no-root
        DashModule,         // bảng dữ liệu xe LIVE (TPMS 4 lốp + tốc độ/gear/nhiệt/pin) — đọc HAL no-root
    )
}

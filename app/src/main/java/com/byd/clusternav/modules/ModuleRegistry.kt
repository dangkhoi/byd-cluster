package com.byd.clusternav.modules

import com.byd.clusternav.modules.dash.DashModule
import com.byd.clusternav.modules.deadreckon.DeadReckonModule
import com.byd.clusternav.modules.mockloc.MockLocModule
import com.byd.clusternav.modules.navaccess.NavAccessibilityModule
import com.byd.clusternav.modules.navaudiocue.AudioCueModule
import com.byd.clusternav.modules.navrealtime.NavRealtimeModule
import com.byd.clusternav.modules.navremoteviews.RemoteViewsModule
import com.byd.clusternav.modules.navtrace.NotifTraceModule
import com.byd.clusternav.modules.vdmap.VdMapModule

/**
 * Nguồn sự thật DUY NHẤT về module nào tồn tại. MỘT dòng / module.
 * THÊM module = import + 1 dòng. XOÁ module = xoá thư mục modules/<tên>/ + xoá 1 dòng (+import). Hết.
 *
 * Đã DỌN (keep/kill, đã chứng minh trên xe): xoá dadbnav/inprochal/mapmode — ghi cụm in-proc/dadb trả rc=0
 * nhưng KHÔNG render (cụm chỉ vẽ qua AmapService/broadcast). GIỮ: tpms/vehicle (đọc HAL in-proc CHẠY THẬT),
 * vdmap (map màn giữa, đang nâng cấp bằng dadb shell-fallback). Lõi nav broadcast nằm ngoài modules/.
 */
object ModuleRegistry {
    val MODULES: List<ClusterModule> = listOf(
        NavRealtimeModule,  // nội suy cự ly-tới-rẽ theo tốc độ (hạ lag GMaps) — toggle + debug
        NavAccessibilityModule, // booster đọc UI GMaps trên màn -> tinh chỉnh cự ly (cần <service> Manifest + xml)
        NotifTraceModule,   // soi nhịp noti + chứng minh noti SỐNG khi GMaps bị YouTube che
        RemoteViewsModule,  // vắt field ẩn trong RemoteViews noti GMaps (lever chưa khai thác)
        AudioCueModule,     // bắt xung audio dẫn đường (usage=12) — phần audio ăn được no-root
        MockLocModule,      // test mock-location có đè GMaps không (go/no-go dead-reckoning)
        DeadReckonModule,   // service nền vá GPS trong hầm (dead-reckoning → mock-location)
        DashModule,         // bảng dữ liệu xe LIVE (TPMS 4 lốp + tốc độ/gear/nhiệt/pin) — đọc HAL no-root
        VdMapModule,        // chiếu map thật lên màn giữa (VirtualDisplay) — cần dadb shell-fallback
    )
}

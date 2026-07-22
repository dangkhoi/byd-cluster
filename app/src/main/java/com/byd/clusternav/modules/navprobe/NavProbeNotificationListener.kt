package com.byd.clusternav.modules.navprobe

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * KÊNH 1 của máy dò — listener notification RIÊNG, tách hẳn khỏi `NavNotificationListener` đang chạy thật.
 *
 * Vì sao không dùng chung: listener chính lọc cứng `MAPS_PACKAGES` và đang phục vụ đường dẫn đường thật.
 * Nhét nhánh khảo sát vào đó là (a) sửa code đang chạy tốt, (b) mọi notification của mọi app đi qua hot-path
 * của tính năng chính. Component riêng thì bật/tắt riêng, và xoá đi không để lại dấu vết.
 *
 * Người dùng phải cấp quyền RIÊNG cho listener này trong Cài đặt → Quyền truy cập thông báo.
 */
class NavProbeNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        // Trace còn hạn (app vừa bị kill và bind lại) → dựng lại kênh HAL, và quét luôn noti đang treo.
        runCatching {
            if (!NavProbe.isOn(applicationContext)) return@runCatching
            NavProbeHal.start(applicationContext)
            NavProbeBroadcast.start(applicationContext)
            NavProbeMedia.start(applicationContext)
            activeNotifications?.forEach { NavProbe.recordNotification(applicationContext, it) }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        runCatching { NavProbe.recordNotification(applicationContext, sbn) }
    }
}

package com.byd.clusternav.modules.clustercast

import android.app.Activity
import android.content.Intent
import android.provider.Settings

/**
 * Ba hộp thoại chỉ-đọc của nhóm "Khắc phục sự cố": hướng dẫn thao tác mà ClusterNav KHÔNG được tự làm
 * thay người dùng, cộng một lối mở màn cài đặt hệ thống.
 *
 * Ở riêng file vì `MainActivityCastController` có ngưỡng 500 dòng do `CastRendererContractTest` canh
 * (cùng lý do `CastActivityAutoStart`, `CastRowActions`, `CastClearClusterAction` từng tách ra). Không
 * giữ trạng thái nào — chỉ nhận `Activity` để dựng dialog, đúng như các binding cùng nhóm.
 */
internal object CastGuidanceDialogs {

    /**
     * Phiên CarPlay/Android Auto do ĐIỆN THOẠI làm chủ. ClusterNav cố ý không tự ngắt hộ: cắt phiên của
     * người khác giữa lúc lái là thứ chỉ chủ xe được quyết, và mọi đường "ngắt hộ" đều phải force-stop
     * một tiến trình hệ thống (CLAUDE.md §4). Nên ở đây chỉ nói đúng việc cần làm rồi để họ làm.
     */
    fun phoneDisconnect(activity: Activity) = activity.alert(
        "Ngắt kết nối điện thoại",
        "Ngắt CarPlay/Android Auto trên điện thoại hoặc rút cáp. Quay lại đây sau khi phiên báo đã ngắt; " +
            "ClusterNav không tự tắt phiên điện thoại.",
    )

    /**
     * "Không dùng adb reboot làm bằng chứng" là kết luận thật, không phải câu doạ: `adb reboot` khởi động
     * lại phần Android chứ không power-cycle màn cụm, nên nó KHÔNG chứng minh được đường phục hồi vật lý
     * có hoạt động hay không.
     */
    fun physicalRecovery(activity: Activity) = activity.alert(
        "Khôi phục thủ công",
        "Dừng xe ở vị trí an toàn, dùng nút nguồn vật lý của màn hình để power-cycle hoàn toàn, sau đó mở " +
            "lại Chẩn đoán. Không dùng adb reboot làm bằng chứng.",
    )

    /** Mở màn hồ sơ người dùng của hệ thống; ngã về màn Cài đặt chung nếu ROM không có màn riêng. */
    fun openProfileSetup(activity: Activity, toast: (String) -> Unit) {
        val opened = runCatching { activity.startActivity(Intent("android.settings.USER_SETTINGS")) }
            .recoverCatching { activity.startActivity(Intent(Settings.ACTION_SETTINGS)) }.isSuccess
        if (!opened) toast("Thiết bị không cung cấp màn hình thiết lập hồ sơ")
    }

    private fun Activity.alert(title: String, message: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle(title).setMessage(message).setPositiveButton("Đã hiểu", null).show()
    }
}

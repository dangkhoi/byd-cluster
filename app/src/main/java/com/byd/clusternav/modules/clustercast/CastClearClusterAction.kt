package com.byd.clusternav.modules.clustercast

import android.app.Activity
import android.widget.Button
import com.byd.clusternav.Lang

/**
 * Nút cứu hộ "Trả cụm về đồng hồ" — hộp thoại xác nhận + lượt chạy nền + nói đúng ba kết cục.
 *
 * Ở riêng file vì `MainActivityCastController` có ngưỡng 500 dòng do `CastRendererContractTest` canh
 * (cùng lý do `CastActivityAutoStart` và `CastRowActions` từng tách ra). Không giữ trạng thái nào ngoài
 * chính cái nút, và mọi phụ thuộc truyền vào dưới dạng hàm nên lớp này không biết gì về vòng đời Activity.
 *
 * **Vì sao có phép này.** Đêm 31/07–01/08 trên DiLink3, một transaction kẹt `RECOVERING` khiến chủ xe
 * phải `pm clear` NĂM lần: nút Dừng bị từ chối (thang STOP đòi baseline geometry đã journal), còn
 * `recover()` thì vô dụng vì thang đặt app không khai báo bước bù hoàn nào. `clearCluster` bỏ qua toàn bộ
 * sổ sách và làm việc theo QUAN SÁT TƯƠI: thấy app nào trên cụm thì trả app đó về màn chính, rồi đóng
 * đường chiếu OEM để cụm hiện lại đồng hồ.
 */
internal class CastClearClusterAction(
    private val activity: Activity,
    private val facade: CastFacade,
    /** Làn nền dành cho thao tác dừng/cứu hộ — KHÔNG phải luồng vẽ. */
    private val background: (() -> Unit) -> Unit,
    private val postUi: (() -> Unit) -> Unit,
    private val toast: (String) -> Unit,
    /** Đặt dòng trạng thái trên Home trong lúc chạy. */
    private val showStatus: (String) -> Unit,
    /** Dọn trạng thái thao tác cũ trước khi bắt đầu, để không lẫn với lượt trước. */
    private val resetStatus: () -> Unit,
    private val refresh: () -> Unit,
) {
    private var button: Button? = null

    /**
     * Nút này CỐ Ý không bao giờ bị tắt theo hợp đồng hành động của render model.
     *
     * Mọi nút khắc phục khác bắt đầu tắt rồi chờ `refresh()` bật lên theo `CastRenderModel.actions` —
     * đúng cho thao tác thường, nhưng SAI cho cứu hộ: những trạng thái cần cứu nhất
     * (`COMPENSATION_EXHAUSTED`, `OBSERVATION_DIVERGED_*`, envelope hỏng, quan sát không đọc nổi) chính là
     * những trạng thái KHÔNG xuất ra hành động nào. Gate theo hợp đồng ⇒ nút tắt đúng lúc cần nhất ⇒ lại
     * phải xoá dữ liệu app như đêm 31/07. `clearCluster` tự phòng vệ bằng quan sát tươi bên trong (không
     * đọc được display cụm ⇒ `Blocked`, không phát lệnh nào), nên để nó luôn bấm được vừa an toàn vừa
     * trung thực hơn.
     */
    fun bind(button: Button) {
        this.button = button
        button.setOnClickListener { confirm() }
    }

    /**
     * Hộp thoại nói đúng việc nó làm, không doạ: rào cản phải đủ thấp để người đang kẹt dám bấm, nhưng
     * vẫn phải xác nhận vì đây là lệnh thật ra xe và làm mất phiên chiếu đang có.
     */
    private fun confirm() {
        android.app.AlertDialog.Builder(activity)
            .setTitle(Lang.t("Trả cụm về đồng hồ?", "Return cluster to clock?"))
            .setMessage(Lang.t(
                "Đưa mọi app đang chiếm cụm về màn chính, rồi đóng đường chiếu để cụm hiện lại đồng hồ gốc.\n\n" +
                    "Dùng khi cụm bị kẹt và nút Dừng không ăn. Phiên chiếu hiện tại sẽ mất; chiếu lại được ngay sau đó.",
                "Move all apps occupying the cluster back to the main screen, then close the projection so the cluster shows the original clock.\n\n" +
                    "Use when the cluster is stuck and the Stop button doesn't work. The current cast session will be lost; you can cast again immediately.",
            ))
            .setNegativeButton(Lang.t("Hủy", "Cancel"), null)
            .setPositiveButton(Lang.t("Trả cụm", "Return cluster")) { _, _ -> execute() }
            .show()
    }

    /**
     * KHÔNG đi qua `runOperation`: hàm đó mở một "mutation" rồi `refresh()`, tức đúng đường đang hỏng khi
     * cụm kẹt. Cứu hộ phải chạy được cả khi bản ghi bền đã hỏng, nên nó tự quản làn nền và chỉ đọc lại
     * trạng thái SAU KHI xong.
     *
     * Ba kết cục được nói khác nhau, KHÔNG gộp. `Incomplete` tuyệt đối không được vẽ như thành công —
     * đó đúng là kiểu nói dối đã làm mất cả đêm 31/07 (app báo xong trong khi cụm vẫn nguyên hình cũ).
     */
    private fun execute() {
        button?.isEnabled = false
        resetStatus()
        showStatus(Lang.t("Đang trả cụm về đồng hồ…", "Returning cluster to clock…"))
        background {
            val outcome = runCatching { facade.clearCluster() }.getOrElse { failure ->
                CastFacade.ClusterClearOutcome.Blocked(Lang.t("Lỗi khi trả cụm: ${failure.message.orEmpty()}", "Error returning cluster: ${failure.message.orEmpty()}"))
            }
            val message = when (outcome) {
                is CastFacade.ClusterClearOutcome.Cleared ->
                    if (outcome.returned.isEmpty()) Lang.t("Đã đóng đường chiếu; cụm đã về đồng hồ", "Projection closed; cluster returned to clock")
                    else Lang.t("Đã trả ${outcome.returned.size} app về màn chính; cụm đã về đồng hồ", "Returned ${outcome.returned.size} app(s) to main screen; cluster returned to clock")
                is CastFacade.ClusterClearOutcome.Incomplete -> Lang.t("CHƯA xong: ${outcome.reason}", "INCOMPLETE: ${outcome.reason}")
                is CastFacade.ClusterClearOutcome.Blocked -> Lang.t("Không trả được: ${outcome.reason}", "Cannot return: ${outcome.reason}")
            }
            facade.recordOperation("clear-cluster: $message")
            postUi {
                button?.isEnabled = true
                toast(message)
                refresh()
            }
        }
    }
}

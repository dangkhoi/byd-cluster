package com.byd.clusternav.modules.clustercast

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner

/**
 * Tỉ lệ chia đôi cụm (50-50 / 30-70 / 70-30) — nơi lưu, và ô chọn trên panel Cluster Cast của Home.
 *
 * **Vì sao là một file riêng.** `MainActivityCastController` có ngưỡng 500 dòng do
 * `CastRendererContractTest` canh (`activity.lineSequence().count() < 501`) — cùng lý do
 * `CastAutoStartBinding`, `CastClearClusterAction` và `CastGuidanceDialogs` đã tách ra trước đó.
 *
 * **Vì sao ở đây không có kiểu nào của tầng dưới.** `TwoPipelineStaticBoundaryTest` (trong `:core`) chốt
 * cứng danh sách file `:app` được phép nhắc tới package `…clustercast` phiên bản 2 — thêm một file vào
 * danh sách đó là phá hợp đồng phân tầng, và file này không có lý do gì để nằm trong danh sách ấy. Nên
 * tỉ lệ đi qua ranh giới dưới dạng **phần trăm bề ngang của ô TRÁI** (`SplitRatioChoice.leftPercent`),
 * đúng bằng trường `leftPercent` của enum `ClusterSplitRatio` trong `:core`. Không phải "số ma": chỉ có
 * MỘT chỗ dịch số ↔ enum, là `CastFacade.splitRatio`, và cũng chỉ MỘT chỗ dựng danh sách lựa chọn, là
 * [CastFacade.splitRatioChoices]. Ở đây không có bộ chữ hay bộ số thứ hai.
 */
internal class CastSplitRatioStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Tỉ lệ đang chọn. Mặc định — và cũng là chiều lùi khi giá trị đã lưu không còn hợp lệ — là 50-50,
     * tỉ lệ DUY NHẤT đã đo thật trên xe (DiLink3, 31/07).
     *
     * Lọc lại lúc ĐỌC chứ không chỉ lúc ghi: một giá trị lọt vào prefs bằng đường khác (sửa tay, bản
     * cũ, danh sách tỉ lệ của `:core` đổi) sẽ được [CastFacade.runHalfIntent] dịch thành 50-50 chứ
     * không ném — nhưng lọc ngay ở đây thì ô chọn trên màn hình cũng hiện đúng thứ sẽ thật sự chạy,
     * thay vì hiện một tỉ lệ mà xe không bao giờ dùng.
     */
    fun leftPercent(): Int {
        val stored = prefs.getInt(KEY, CastFacade.DEFAULT_SPLIT_LEFT_PERCENT)
        return if (CastFacade.splitRatioChoices().any { it.leftPercent == stored }) {
            stored
        } else {
            CastFacade.DEFAULT_SPLIT_LEFT_PERCENT
        }
    }

    /** `commit()` chứ không `apply()`: cùng lệ với `CastAppCatalog` — ghi xong là ghi xong. */
    fun setLeftPercent(value: Int) {
        require(CastFacade.splitRatioChoices().any { it.leftPercent == value }) {
            "tỉ lệ chia cụm không hợp lệ: $value"
        }
        check(prefs.edit().putInt(KEY, value).commit())
    }

    private companion object {
        const val PREFS = "cast-v2-split"
        const val KEY = "leftPercent"
    }
}

/**
 * Dropdown "Tỉ lệ chia đôi cụm" trên panel Cluster Cast của Home.
 *
 * Cùng khuôn với [CastAutoStartBinding]: mọi phụ thuộc truyền vào dưới dạng hàm, nên lớp này không tự
 * biết gì về vòng đời Activity và không giữ trạng thái nào ngoài lựa chọn đang hiển thị.
 *
 * Đọc/ghi prefs đi qua [background]: `SharedPreferences` lần đầu là một lần đọc file, mà Home tick mỗi
 * giây — façade đã phải sinh ra để chặn đúng lớp lỗi "UI chạm I/O ngay trong đường vẽ".
 */
internal class CastSplitRatioBinding(
    private val activity: Activity,
    private val store: CastSplitRatioStore,
    private val background: (() -> Unit) -> Unit,
    private val postUi: (() -> Unit) -> Unit,
) {
    fun bind(spinner: Spinner) {
        background {
            val choices = CastFacade.splitRatioChoices()
            val current = runCatching { store.leftPercent() }
                .getOrDefault(CastFacade.DEFAULT_SPLIT_LEFT_PERCENT)
            postUi {
                spinner.adapter = ArrayAdapter(
                    activity, android.R.layout.simple_spinner_dropdown_item, choices.map { it.label },
                )
                val index = choices.indexOfFirst { it.leftPercent == current }
                if (index >= 0) spinner.setSelection(index)
                // Lựa chọn đang áp, giữ ở đây thay vì đọc lại prefs trong listener: `Spinner` của Android
                // bắn `onItemSelected` MỘT LẦN cho lựa chọn đặt bằng code (lần layout đầu sau
                // `setAdapter`), nên nếu ghi vô điều kiện thì chỉ mở Home cũng đủ ghi lại prefs. Đó chính
                // là lớp lỗi đã giết yêu cầu tự-chiếu ở `CastAutoStartBinding` — ở đây hậu quả nhẹ hơn
                // (không có revision nào tăng), nhưng cửa idempotent thì vẫn phải có.
                var applied = current
                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        val chosen = choices.getOrNull(position)?.leftPercent ?: return
                        if (chosen == applied) return
                        applied = chosen
                        background { runCatching { store.setLeftPercent(chosen) } }
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }
        }
    }
}

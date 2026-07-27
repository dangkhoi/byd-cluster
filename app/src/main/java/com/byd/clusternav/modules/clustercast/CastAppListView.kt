package com.byd.clusternav.modules.clustercast

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.byd.clusternav.cast.platform.CastAppCatalog
import com.byd.clusternav.cast.platform.CastAppEntry

/**
 * Danh sách app hai khu của màn Cluster Cast: app đã chọn ở trên kèm panel cấu hình, app còn lại nằm dưới.
 *
 * Ở riêng để `ClusterCastActivity` không phình quá ngưỡng 501 dòng mà `CastRendererContractTest` canh — và
 * vì việc "phân lại hai khu" là một mối lo tự thân, không cần Activity biết chi tiết.
 *
 * `reflow` KHÔNG truy vấn package manager: nó dựng lại từ danh sách đã nạp một lần, nên tick/bỏ tick chỉ
 * dựng lại hai container, nhanh và không nhấp nháy cả màn. Đọc `favorites()` sau khi hàng đã ghi ĐỒNG BỘ
 * nên luôn thấy đúng trạng thái vừa đổi — đó là lý do app nhảy khu ngay khi chạm.
 */
class CastAppListView(
    private val context: Context,
    private val chosenSection: LinearLayout,
    private val chosenEmpty: TextView,
    private val restGrid: android.view.ViewGroup,
    private val catalog: CastAppCatalog,
    private val rowActions: CastAppRows.Actions,
) {
    private var loaded: List<CastAppEntry> = emptyList()

    fun setApps(values: List<CastAppEntry>) {
        loaded = values
        reflow()
    }

    fun reflow() {
        restGrid.removeAllViews()
        chosenSection.removeAllViews()
        if (loaded.isEmpty()) {
            chosenEmpty.visibility = View.VISIBLE
            return
        }
        val favorites = catalog.favorites()
        val (picked, rest) = loaded.partition { it.packageName in favorites }
        picked.forEach { chosenSection.addView(CastAppRows.build(context, it, rowActions)) }
        chosenEmpty.visibility = if (picked.isEmpty()) View.VISIBLE else View.GONE
        rest.forEach { restGrid.addView(CastAppRows.build(context, it, rowActions)) }
    }
}

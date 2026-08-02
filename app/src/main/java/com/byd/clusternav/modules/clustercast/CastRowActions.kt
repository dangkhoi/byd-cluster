package com.byd.clusternav.modules.clustercast

import android.os.Handler
import android.os.Looper
import com.byd.clusternav.cast.platform.CastAppCatalog
import com.byd.clusternav.modules.clustercast.v2.AcceptedGeometry
import com.byd.clusternav.modules.clustercast.v2.CastRect

/**
 * Cầu nối giữa panel kích thước (CastAppRows) và phần máy móc của V2.
 *
 * Ở riêng một file vì `MainActivity` có ngưỡng LOC do test canh (kế thừa lý do file này tồn tại từ
 * `ClusterCastActivity` trước khi màn đó bị xoá — xem docs/specs/cast-simplified-active-app-toggle.html).
 * Mọi phụ thuộc truyền vào dưới dạng hàm, nên lớp này không biết gì về Activity và kiểm được không cần thiết bị.
 */
class CastRowActions(
    private val catalog: CastAppCatalog,
    private val background: (() -> Unit) -> Unit,
    private val clusterSize: () -> Pair<Int, Int>,
    private val activeTarget: () -> String?,
    private val profileId: () -> String,
    private val stillAlive: () -> Boolean,
    private val applyGeometry: (AcceptedGeometry) -> Unit,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : CastAppRows.Actions {

    override fun scaleOf(packageName: String) = catalog.scaleOf(packageName)

    override fun setScale(packageName: String, scale: AppScale) {
        background { catalog.setScale(packageName, scale) }
    }

    override fun clusterSize(): Pair<Int, Int> = clusterSize.invoke()

    /**
     * Áp khung qua DEBOUNCE, và chỉ khi app này ĐANG chiếm cụm.
     *
     * Hai điều kiện đều lấy từ v0.3x: gộp loạt nhấn thành một lần vì áp từng lần mở nhiều kết nối và gây lag
     * trên xe; và chỉnh cho app chưa chiếu thì chỉ LƯU, không phát lệnh nào — giá trị đã lưu theo app nên
     * lần chiếu sau vẫn đúng khung.
     */
    override fun applySoon(packageName: String) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(
            {
                if (!stillAlive()) return@postDelayed
                if (activeTarget() != packageName) return@postDelayed
                val scale = catalog.scaleOf(packageName)
                val (width, height) = clusterSize.invoke()
                val bounds = scale.boundsOn(width, height)
                applyGeometry(
                    AcceptedGeometry(
                        CastRect(bounds[0], bounds[1], bounds[2], bounds[3]),
                        scale.dpi,
                        profileId(),
                    ),
                )
            },
            CastAppRows.APPLY_DEBOUNCE_MS,
        )
    }
}

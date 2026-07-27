package com.byd.clusternav.modules.clustercast

import android.os.Handler
import android.os.Looper
import com.byd.clusternav.cast.platform.CastAppCatalog
import com.byd.clusternav.modules.clustercast.v2.AcceptedGeometry
import com.byd.clusternav.modules.clustercast.v2.CastRect

/**
 * Cầu nối giữa hàng app kiểu v0.3x và phần máy móc của V2.
 *
 * Ở riêng một file vì `ClusterCastActivity` có ngưỡng 501 dòng do `CastRendererContractTest` canh — và
 * ngưỡng đó vừa bắt được đúng lần này: thêm cầu nối vào Activity là 520 dòng. Nâng ngưỡng thì dễ, nhưng
 * ngưỡng tồn tại chính để chặn việc Activity phình ra thành nơi chứa mọi thứ.
 *
 * Mọi phụ thuộc truyền vào dưới dạng hàm, nên lớp này không biết gì về Activity và kiểm được không cần thiết bị.
 */
class CastRowActions(
    private val catalog: CastAppCatalog,
    private val favoriteWriter: (String, Boolean) -> Unit,
    private val onChosen: (String) -> Unit,
    private val background: (() -> Unit) -> Unit,
    private val clusterSize: () -> Pair<Int, Int>,
    private val activeTarget: () -> String?,
    private val profileId: () -> String,
    private val stillAlive: () -> Boolean,
    private val applyGeometry: (AcceptedGeometry) -> Unit,
    private val castNow: (String) -> Unit,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : CastAppRows.Actions {

    override fun chosen(packageName: String) = packageName in catalog.favorites()

    override fun setChosen(packageName: String, enabled: Boolean) {
        favoriteWriter(packageName, enabled)
        if (enabled) onChosen(packageName)
    }

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

    override fun cast(packageName: String) = castNow(packageName)
}

package com.byd.clusternav

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Kho trạng thái in-process. NotificationListener (đọc) và ClusterNavActivity (vẽ) chạy
 * cùng 1 app process nên chỉ cần observer đơn giản, không cần IPC.
 */
object NavRepository {

    @Volatile
    var state: NavState = NavState()
        private set

    private val listeners = CopyOnWriteArrayList<(NavState) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun update(s: NavState) {
        state = s
        main.post { listeners.forEach { it(s) } }
    }

    fun clear() = update(NavState())

    /** Card fallback có đang mở không — để listener chỉ decode bitmap mũi tên khi cần. */
    fun hasListeners(): Boolean = listeners.isNotEmpty()

    fun addListener(l: (NavState) -> Unit) {
        listeners.add(l)
        main.post { l(state) }   // phát ngay state hiện tại
    }

    fun removeListener(l: (NavState) -> Unit) {
        listeners.remove(l)
    }
}

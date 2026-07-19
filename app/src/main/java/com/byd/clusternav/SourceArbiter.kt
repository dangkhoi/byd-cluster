package com.byd.clusternav

import java.util.concurrent.ConcurrentHashMap

/**
 * Trọng tài chọn nguồn khi mở >1 app dẫn đường. Máy trạng thái THUẦN (không Android Context) —
 * caller truyền mode (Prefs.AUTO/PREFER_*). UI đọc snapshot [activeSource] thay vì chọc vào listener.
 *
 * AUTO: app nào dẫn TRƯỚC giữ khoá; app khác bị bỏ qua tới khi nguồn giữ DỪNG (release) hoặc IM > STALE.
 * PREFER_*: app/nhóm được ưu tiên CÒN dẫn (tươi) thì luôn lên; app kia chỉ lên khi nó ngừng.
 */
object SourceArbiter {
    const val STALE_MS = 6000L
    private val GMAPS_PKGS = setOf("com.google.android.apps.maps", "app.revanced.android.apps.maps")

    @Volatile var activeSource: String? = null; private set
    @Volatile private var activeSeen: Long = 0L
    private val lastSeenByPkg = ConcurrentHashMap<String, Long>()

    fun shouldFeed(pkg: String, mode: Int, now: Long): Boolean {
        lastSeenByPkg[pkg] = now
        val allow = when (mode) {
            Prefs.PREFER_GMAPS -> pkg in GMAPS_PKGS || !isGroupFresh(GMAPS_PKGS, now)
            else -> {
                val h = activeSource
                h == null || h == pkg || now - activeSeen > STALE_MS
            }
        }
        if (allow) { activeSource = pkg; activeSeen = now }
        return allow
    }

    /** Gọi khi noti dẫn đường của [pkg] bị gỡ. true nếu [pkg] đang giữ khoá (caller nên dừng cụm). */
    fun release(pkg: String): Boolean {
        if (pkg == activeSource) { activeSource = null; activeSeen = 0L; return true }
        return false
    }

    /** Nhả khoá hoàn toàn (vd nav stale -> idle). */
    fun clear() { activeSource = null; activeSeen = 0L }

    /** Nguồn đang giữ còn tươi không (UI hiện trạng thái). */
    fun isFresh(now: Long): Boolean {
        activeSource ?: return false
        return now - activeSeen <= STALE_MS
    }

    private fun isGroupFresh(group: Set<String>, now: Long): Boolean =
        group.any { val t = lastSeenByPkg[it] ?: 0L; t > 0 && now - t <= STALE_MS }
}

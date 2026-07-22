package com.byd.clusternav

import android.content.Context

/**
 * Đa ngôn ngữ NHẸ — Tiếng Việt (gốc) + English. Cố ý KHÔNG dùng resource `values-en/strings.xml`:
 *
 * app này dựng UI bằng code với chuỗi inline (khoảng 150 chuỗi rải trong các Activity), không tham chiếu
 * `@string/…`. Chuyển hết sang resource sẽ phải (a) đặt id cho từng chuỗi, (b) đổi mọi call site sang
 * getString, (c) thêm cơ chế đổi locale runtime cho UI-dựng-bằng-code — nhiều churn, dễ sót, rủi ro cao cho
 * app chạy trên xe. Thay vào đó để bản dịch NGAY TẠI call site: `Lang.t("Chiếu lên cụm", "Cast to cluster")`.
 * Đọc code là thấy cả hai thứ tiếng, không phải nhảy sang file khác.
 *
 * Đổi ngôn ngữ → Activity gọi `recreate()` để dựng lại UI bằng cache mới.
 */
object Lang {

    enum class L(val code: String, val label: String) {
        VI("vi", "Tiếng Việt"),
        EN("en", "English"),
    }

    private const val PREF = "clusternav_lang"
    private const val K = "lang"

    @Volatile private var cache: L? = null

    /** Nạp lựa chọn ngôn ngữ vào cache. Gọi ở đầu `onCreate` của mỗi Activity, TRƯỚC khi dựng UI. */
    fun load(ctx: Context): L {
        cache?.let { return it }
        val sp = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val saved = sp.getString(K, null)
        val l = L.entries.firstOrNull { it.code == saved } ?: defaultFor(ctx)
        cache = l
        return l
    }

    /** Lần đầu chạy: đoán theo locale máy — máy tiếng Việt → VI, còn lại → EN. */
    private fun defaultFor(ctx: Context): L =
        if (runCatching {
                ctx.resources.configuration.locales[0].language
            }.getOrNull() == "vi") L.VI else L.EN

    fun cur(ctx: Context): L = load(ctx)

    fun set(ctx: Context, l: L) {
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(K, l.code).apply()
        cache = l
    }

    fun toggle(ctx: Context): L {
        val next = if (cur(ctx) == L.VI) L.EN else L.VI
        set(ctx, next); return next
    }

    /**
     * Chọn chuỗi theo ngôn ngữ ĐANG dùng (từ cache đã nạp). Mặc định VI nếu chưa nạp.
     * Dùng dạng không-Context để call site gọn; Activity chịu trách nhiệm `load()` trước khi dựng UI.
     */
    fun t(vi: String, en: String): String = if (cache == L.EN) en else vi
}

package com.byd.clusternav

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.byd.clusternav.navigation.ArrayPixelFrame
import com.byd.clusternav.navigation.ArrowClassifier
import com.byd.clusternav.navigation.ArrowSource
import com.byd.clusternav.navigation.ManeuverSignature
import com.byd.clusternav.navigation.NavArrowTrace
import com.byd.clusternav.navigation.NavArrowTraceEntry
import com.byd.clusternav.navigation.NavFormat
import com.byd.clusternav.navigation.PixelFrame
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Vết TỪNG lớp phân loại icon rẽ ra CSV + PNG mũi tên, dựng sau lỗi đo 2026-07-30: "rẽ trái mà cụm hiện
 * thẳng mãi" không lần ra được lớp nào trong 4 lớp fallback (`AmapFrameBuilder.buildGuidanceFrame`) sai,
 * vì log cũ (`ClusterBroadcaster.sendFrame`) chỉ có icon CUỐI CÙNG, không phải verdict từng lớp.
 *
 * Gọi TỪNG LỚP lại một lần nữa ở đây (rẻ — ảnh 54×54) THAY VÌ đọc lại từ chuỗi elvis thật của
 * [AmapFrameBuilder]: chuỗi đó short-circuit ở lớp đầu thắng, nên lớp sau không hề chạy — mà đúng cái cần
 * thấy để so sánh là "lớp 2/3/4 SẼ ra gì nếu được hỏi", không chỉ lớp thắng.
 *
 * CHỈ ĐỌC, KHÔNG đụng frame đã dựng: không lớp nào ở đây đổi được icon gửi ra cụm.
 *
 * Mở lazy, flush mỗi dòng — mirror [NavDistanceLog]. PNG chỉ ghi khi NỘI DUNG ảnh đổi (nhịp tim gửi lại
 * y nguyên NavState mỗi ~0.4s sẽ không tạo hàng trăm file trùng nhau).
 * Pull: adb pull /sdcard/Android/data/com.byd.clusternav/files/ (lấy cả nav_arrow_log_<stamp>.csv và
 * thư mục nav_arrow_pngs_<stamp> cùng lúc).
 */
object NavArrowLog {
    private const val TAG = "NavArrowLog"
    private var w: BufferedWriter? = null
    private var pngDir: File? = null
    private var lastHash: Long? = null
    private var failures = 0
    @Volatile var csvPath: String = ""; private set

    // D3 (closeout 1.28): the heavy pixel-read + signature + PNG-compress + CSV-flush used to run on the MAIN
    // thread (ClusterBroadcaster.sendFrame, ~4×/s). Now (a) record() early-returns unless NavLog.verbose (default
    // OFF — tuning is done) and (b) the heavy work runs on this single-thread daemon Executor so even when verbose
    // is ON it can never block the main thread / ANR. File format is unchanged. Lazy → the thread is not created
    // until verbose actually turns on. Arrow bitmaps are never recycled in this app, so background access is safe.
    private val io: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "navarrowlog").apply { isDaemon = true } }
    }

    @Synchronized
    private fun ensure(ctx: Context) {
        if (w != null) return
        runCatching {
            val stamp = System.currentTimeMillis()
            val base = ctx.applicationContext.getExternalFilesDir(null)
            val f = File(base, "nav_arrow_log_$stamp.csv")
            w = f.bufferedWriter().also { it.appendLine(NavArrowTrace.CSV_HEADER) }
            csvPath = f.absolutePath
            pngDir = File(base, "nav_arrow_pngs_$stamp").apply { mkdirs() }
            // Nói ra ĐƯỜNG DẪN ngay khi mở được: nếu logcat không có dòng này thì cả chuyến KHÔNG có vết,
            // biết ngay lúc đang lái chứ không phải lúc pull về mới phát hiện đi không (§11 — app tự báo).
            Log.i(TAG, "vết icon rẽ -> $csvPath (+ ${pngDir?.name})")
        }.onFailure { blame("không mở được file vết icon rẽ", it) }
    }

    /**
     * Không nuốt im lặng (Code Health: cấm `except: pass` cho hạ tầng) nhưng cũng không spam logcat 2.5
     * lần/giây và đè mất log khác: kêu lần ĐẦU rồi mỗi 250 lần (~100s), luôn kèm tổng số lần.
     */
    private fun blame(what: String, e: Throwable) {
        failures++
        if (failures == 1 || failures % 250 == 0) Log.e(TAG, "$what (lần thứ $failures)", e)
    }

    /**
     * Gọi mỗi lần [com.byd.clusternav.ClusterBroadcaster] gửi một frame guidance — cùng nhịp với
     * [NavDistanceLog.record], kể cả nhịp tim (để thấy icon sai TỒN TẠI BAO LÂU, không chỉ lúc nó xuất hiện).
     *
     * [frameArrow] = ảnh ĐI CÙNG frame mà chuỗi elvis thật đã chấm. [liveArrow] = ảnh mới nhất còn giữ ở
     * [NavRepository] — CHỈ dùng khi [frameArrow] null, và khi đó cột `arrow_src` ghi `live` để người đọc
     * biết lớp 2/4 trong hàng đó là "SẼ ra gì nếu được đưa ảnh", không phải cái đã quyết định icon gửi ra.
     * (Cần vì từ 0.72 đường lên cụm dựng lại NavState qua `NavigationFrameContent` — không có trường bitmap
     * — nên `s.arrow` LUÔN null tại `sendFrame`: xem NavRepository.toNavState.)
     */
    fun record(
        ctx: Context,
        maneuverText: String,
        displayRoad: String,
        rawRoad: String,
        distance: String,
        smallIconAmap: Int,
        finalIcon: Int,
        frameArrow: Bitmap?,
        liveArrow: Bitmap? = null,
    ) {
        if (!NavLog.verbose) return
        val atMs = System.currentTimeMillis()
        io.execute {
            recordLocked(ctx, atMs, maneuverText, displayRoad, rawRoad, distance, smallIconAmap, finalIcon, frameArrow, liveArrow)
        }
    }

    private fun recordLocked(
        ctx: Context,
        atMs: Long,
        maneuverText: String,
        displayRoad: String,
        rawRoad: String,
        distance: String,
        smallIconAmap: Int,
        finalIcon: Int,
        frameArrow: Bitmap?,
        liveArrow: Bitmap?,
    ) {
        ensure(ctx)
        val writer = w ?: return
        runCatching {
            val arrow = frameArrow ?: liveArrow
            val source = when {
                frameArrow != null -> ArrowSource.FRAME
                liveArrow != null -> ArrowSource.LIVE
                else -> ArrowSource.NONE
            }
            // Đọc điểm ảnh MỘT lần rồi dùng lại cho cả 2 classifier + hash: BitmapPixelFrame.argb() cấp phát
            // mảng mới mỗi lần gọi (3 lần × ~11KB × 2.5 nhịp/s = rác vô ích trên đầu xe yếu).
            val frame: PixelFrame? = arrow?.asPixelFrame()
                ?.let { f -> f.argb()?.let { ArrayPixelFrame(f.width, f.height, it) } }
            // Tên + mã lấy CÙNG NHAU: đọc ManeuverSignature.lastName sau lời gọi có thể lấy tên của khung
            // khác (AmapFrameBuilder chấm cùng lúc trên luồng worker cluster-lane).
            val sig = ManeuverSignature.classifyDetailed(frame)
            // Y HỆT biểu thức chuỗi thật dùng (AmapFrameBuilder: `s.maneuverText.ifBlank { s.road }`) — road
            // THÔ, không phải bản đã cleanRoadName, nếu không cột này có thể lệch với quyết định thật.
            val verbAmap = NavFormat.maneuverVerbIcon(maneuverText.ifBlank { rawRoad })
            val heuristicAmap = ArrowClassifier.classify(frame)
            val hash = NavArrowTrace.bitmapHash(frame)

            // pngDir null (mkdirs hỏng) mà vẫn dựng File(null, name) là ĐƯỜNG DẪN TƯƠNG ĐỐI -> ghi vào cwd
            // của tiến trình ("/") mỗi frame; thà bỏ PNG còn hơn đâm vào gốc hệ thống 2.5 lần/giây.
            val dir = pngDir
            if (dir != null && arrow != null && hash != null && hash != lastHash) {
                lastHash = hash
                runCatching {
                    val png = File(dir, "$hash.png")
                    if (!png.exists()) {
                        png.outputStream().use { out -> arrow.compress(Bitmap.CompressFormat.PNG, 100, out) }
                    }
                }.onFailure { blame("không ghi được PNG mũi tên", it) }
            }

            val entry = NavArrowTraceEntry(
                atEpochMillis = atMs, maneuverText = maneuverText,
                displayRoad = displayRoad, rawRoad = rawRoad, distance = distance,
                smallIconAmap = smallIconAmap, sigName = sig.name, sigAmap = sig.amap,
                verbAmap = verbAmap, heuristicAmap = heuristicAmap, finalIcon = finalIcon,
                arrowSource = source, bitmapHash = hash,
            )
            writer.appendLine(NavArrowTrace.toCsvRow(entry))
            writer.flush()
        }.onFailure { blame("không ghi được dòng vết icon rẽ", it) }
    }
}

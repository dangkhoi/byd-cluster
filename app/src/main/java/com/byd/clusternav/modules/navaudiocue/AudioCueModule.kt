package com.byd.clusternav.modules.navaudiocue

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.TextView
import com.byd.clusternav.modules.ClusterModule
import com.byd.clusternav.modules.ModuleUi
import com.byd.clusternav.modules.SelfTest

/**
 * MODULE: bắt XUNG audio dẫn đường — phần DUY NHẤT của kênh audio ăn được no-root (registerAudioPlaybackCallback,
 * KHÔNG cần quyền). Lọc usage = ASSISTANCE_NAVIGATION_GUIDANCE(12) -> biết "nav vừa phát 1 câu" làm điểm re-anchor.
 * KHÔNG lấy được nội dung/cự ly (framework chặn capture usage=12; GMaps/Waze TTS nhúng riêng) — chỉ là tín hiệu.
 *
 * Test trên xe (đây là phép thử then chốt): lái có dẫn đường + bật module. Nếu thấy 'nav cue' đếm lên mỗi lần
 * GMaps đọc giọng -> usage LỘ trên ROM BYD, dùng được làm xung. Nếu usage toàn UNKNOWN -> ROM ẩn, bỏ hướng này.
 * Sống cả khi GMaps bị che (audio chạy nền). XOÁ: xoá modules/navaudiocue/ + dòng Registry.
 */
object AudioCueModule : ClusterModule {
    override val title = "Xung audio dẫn đường"

    private const val NAV = AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE   // = 12

    @Volatile private var running = false
    private var view: TextView? = null
    private val main = Handler(Looper.getMainLooper())
    private var am: AudioManager? = null
    private var cb: AudioManager.AudioPlaybackCallback? = null

    private val usagesSeen = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    @Volatile private var navCues = 0
    @Volatile private var lastNavCueMs = 0L
    @Volatile private var activeNow = 0
    @Volatile private var navActiveNow = false
    @Volatile private var wasNav = false

    override fun selfTest(ctx: Context): SelfTest {
        val a = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return SelfTest.fail("không lấy được AudioManager")
        val cfgs = runCatching { a.activePlaybackConfigurations }.getOrNull() ?: emptyList()
        val usages = cfgs.map { usageName(it.audioAttributes.usage) }.distinct()
        return SelfTest.pass("đang phát ${cfgs.size} luồng · usage: ${usages.joinToString(",").ifEmpty { "(im lặng)" }} " +
            "· có NAV=${cfgs.any { it.audioAttributes.usage == NAV }}")
    }

    override fun buildView(ctx: Context, parent: ViewGroup) {
        val ui = ModuleUi(ctx, parent)
        ui.text("Đếm mỗi lần luồng audio usage=NAVIGATION_GUIDANCE(12) bật lên = GMaps vừa đọc 1 câu. " +
            "Dùng làm xung re-anchor (không có cự ly). Lái + nghe giọng dẫn -> 'nav cue' phải tăng. Sống khi bị che.")
        ui.btn("Reset đếm") { navCues = 0; usagesSeen.clear() }
        val dp = ctx.resources.displayMetrics.density
        view = TextView(ctx).apply {
            textSize = 14f; typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#1A1F24")); setBackgroundColor(Color.parseColor("#F1EFE8"))
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
            text = "..."
        }
        parent.addView(view)
    }

    override fun onShow(ctx: Context) {
        if (running) return
        running = true
        val a = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        am = a
        val callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                val list = configs ?: return
                activeNow = list.size
                list.forEach { usagesSeen.add(it.audioAttributes.usage) }
                val nav = list.any { it.audioAttributes.usage == NAV }
                navActiveNow = nav
                if (nav && !wasNav) { navCues++; lastNavCueMs = SystemClock.elapsedRealtime() }  // rising edge = 1 câu mới
                wasNav = nav
            }
        }
        cb = callback
        runCatching { a.registerAudioPlaybackCallback(callback, main) }
        Thread {
            while (running) {
                val t = runCatching { render() }.getOrElse { "lỗi: $it" }
                main.post { view?.text = t }
                try { Thread.sleep(500) } catch (_: InterruptedException) {}
            }
        }.start()
    }

    override fun onHide(ctx: Context) {
        running = false
        cb?.let { c -> runCatching { am?.unregisterAudioPlaybackCallback(c) } }
        cb = null
    }

    private fun render(): String {
        val since = if (lastNavCueMs > 0) "${SystemClock.elapsedRealtime() - lastNavCueMs}ms trước" else "—"
        return buildString {
            append("luồng đang phát : $activeNow\n")
            append("usage đã thấy   : ${usagesSeen.map { usageName(it) }.joinToString(", ").ifEmpty { "—" }}\n")
            append("NAV đang phát   : $navActiveNow\n")
            append("nav cue (đếm)   : $navCues   (cue cuối: $since)\n")
            append("→ usage=12 lộ?  : ${if (usagesSeen.contains(NAV)) "CÓ ✓ (dùng được làm xung)" else "chưa thấy (ROM ẩn? hay chưa có giọng dẫn)"}")
        }
    }

    private fun usageName(u: Int): String = when (u) {
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> "NAV(12)"
        AudioAttributes.USAGE_MEDIA -> "MEDIA"
        AudioAttributes.USAGE_UNKNOWN -> "UNKNOWN"
        AudioAttributes.USAGE_GAME -> "GAME"
        AudioAttributes.USAGE_VOICE_COMMUNICATION -> "VOICE"
        AudioAttributes.USAGE_ASSISTANT -> "ASSISTANT"
        AudioAttributes.USAGE_NOTIFICATION -> "NOTIF"
        AudioAttributes.USAGE_ALARM -> "ALARM"
        else -> "u$u"
    }
}

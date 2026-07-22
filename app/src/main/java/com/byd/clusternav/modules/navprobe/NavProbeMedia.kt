package com.byd.clusternav.modules.navprobe

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

/**
 * KÊNH 5 của máy dò — MediaSession. **Đây là kênh DUY NHẤT đã chứng minh được là mang dữ liệu CarPlay
 * sang phía Android của đầu xe.**
 *
 * Vì sao chắc (đọc từ firmware đã decompile, không đoán):
 *  • `sysapk/jadx-mediacontroller/...` — app hệ thống `com.byd.mediacontroller` gọi
 *    `MediaSessionManager.getActiveSessions(null)` rồi ghi tên bài hát lên cụm qua `INSTRUMENT_MUSIC_INFO_SET`.
 *    Đó chính là đường mà chủ xe quan sát thấy: cắm CarPlay thì tên bài hát hiện trên cụm.
 *  • `sysapk/jadx-carplay/.../MediaManager.java` — `com.byd.carplay.ui` tạo `MediaSessionCompat` thật và
 *    `setMetadata` / `setPlaybackState` khi iPhone đổi bài.
 *
 * Ta KHÔNG có `MEDIA_CONTENT_CONTROL` (quyền hệ thống), nhưng `getActiveSessions(ComponentName)` còn một cửa
 * hợp lệ nữa: **gọi từ một NotificationListenerService đã được người dùng cấp quyền** — máy dò có sẵn
 * [NavProbeNotificationListener]. Vì thế truyền đúng ComponentName của nó, KHÔNG truyền null.
 *
 * ⚠ Kỳ vọng đúng mực: kênh này mang **nhạc** (tên bài, nghệ sĩ, thời lượng). Chưa có bằng chứng nào cho thấy
 * CarPlay nhét dữ liệu dẫn đường vào đây. Nhưng nếu wrapper BYD có giấu gì thêm thì nó sẽ lộ ra ở
 * `MediaMetadata` — nên dump TOÀN BỘ key, không lọc trước.
 */
object NavProbeMedia {

    @Volatile private var mgr: MediaSessionManager? = null
    @Volatile private var listener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private val watched = java.util.concurrent.ConcurrentHashMap<String, MediaController.Callback>()

    @Synchronized
    fun start(ctx: Context) {
        if (!NavProbe.isOn(ctx) || listener != null) return
        val app = ctx.applicationContext
        val comp = ComponentName(app, NavProbeNotificationListener::class.java)
        val m = runCatching {
            app.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        }.getOrNull()
        if (m == null) { NavProbe.append(app, "[MEDIA] không lấy được MediaSessionManager\n\n"); return }

        val l = MediaSessionManager.OnActiveSessionsChangedListener { list ->
            runCatching { bind(app, list ?: emptyList()) }
        }
        runCatching { m.addOnActiveSessionsChangedListener(l, comp, Handler(Looper.getMainLooper())) }
            .onSuccess {
                mgr = m; listener = l
                NavProbe.append(app, "[MEDIA] đã nghe MediaSession (qua quyền notification-listener)\n\n")
                runCatching { bind(app, m.getActiveSessions(comp)) }
            }
            .onFailure {
                // Thiếu quyền notification-listener là nguyên nhân số 1 — nói thẳng để người dùng còn đi cấp.
                NavProbe.append(app, "[MEDIA] KHÔNG nghe được: ${it.javaClass.simpleName}: ${it.message}\n" +
                    "        (cần cấp quyền ĐỌC THÔNG BÁO cho máy dò thì kênh này mới chạy)\n\n")
            }
    }

    @Synchronized
    fun stop() {
        val m = mgr; val l = listener
        listener = null; mgr = null
        if (m != null && l != null) runCatching { m.removeOnActiveSessionsChangedListener(l) }
        watched.clear()
    }

    /** Gắn callback vào từng session để bắt lúc metadata ĐỔI, chứ không chỉ chụp một lần. */
    private fun bind(ctx: Context, list: List<MediaController>) {
        for (c in list) {
            val pkg = c.packageName ?: continue
            if (watched.containsKey(pkg)) continue
            dump(ctx, pkg, c.metadata, c.playbackState, "hiện có")
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(md: MediaMetadata?) = dump(ctx, pkg, md, null, "đổi bài")
                override fun onPlaybackStateChanged(st: PlaybackState?) = dump(ctx, pkg, null, st, "đổi trạng thái")
            }
            runCatching { c.registerCallback(cb, Handler(Looper.getMainLooper())); watched[pkg] = cb }
        }
    }

    /** Dump MỌI key trong MediaMetadata — không đoán trước trường nào đáng quan tâm. */
    private fun dump(ctx: Context, pkg: String, md: MediaMetadata?, st: PlaybackState?, why: String) {
        runCatching {
            val sb = StringBuilder("=== ").append(stamp()).append(" · ").append(pkg)
                .append(" [MEDIA ").append(why).append("]\n")
            md?.let { m ->
                for (k in m.keySet().sorted()) {
                    val v = runCatching { m.getString(k) }.getOrNull()
                        ?: runCatching { m.getLong(k).toString() }.getOrNull()
                        ?: continue
                    if (v.isBlank() || v == "0") continue
                    sb.append("  ").append(k).append(" = ").append(v.take(200)).append('\n')
                }
                if (m.keySet().isEmpty()) sb.append("  (metadata rỗng)\n")
            }
            st?.let { sb.append("  state=").append(it.state).append(" pos=").append(it.position).append('\n') }
            NavProbe.append(ctx, sb.append('\n').toString())
            NavProbe.noteSeen("$pkg (media)")
        }
    }

    private fun stamp() =
        java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
}

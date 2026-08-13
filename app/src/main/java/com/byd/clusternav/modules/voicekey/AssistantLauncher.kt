package com.byd.clusternav.modules.voicekey

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.util.Log
import com.byd.clusternav.voicekey.VoiceKeyTarget

/**
 * Phóng intent mở trợ lý giọng nói theo [VoiceKeyTarget]. Gọi từ [com.byd.clusternav.modules.navaccess.NavAccessibilityService]
 * (không có Activity context) → cần FLAG_ACTIVITY_NEW_TASK. Thử lần lượt các intent ứng viên, dừng ở cái
 * đầu tiên có activity xử lý (bắt ActivityNotFoundException như mọi startActivity trên IVI khoá).
 *
 * ⚠️ Đích/intent chính xác cho từng trợ lý (Google Assistant/Gemini vs BYD "小迪") xác nhận TRÊN XE — đây là
 * danh sách ứng viên + fallback theo tài liệu Android (VoiceInteraction/Assist, API 29). Xem spec T3.
 */
object AssistantLauncher {
    private const val TAG = "VoiceKeyLauncher"

    /** BYD voice assistant "小迪" — có trong firmware DiLink3 (RE: com.byd.autovoice). */
    const val BYD_VOICE_PKG = "com.byd.autovoice"

    /** Gemini app độc lập — mở thẳng nó ra "robin" car voice surface (xác nhận trên xe 2026-08-13). */
    const val GEMINI_PKG = "com.google.android.apps.bard"

    /** Google app (host Assistant/Gemini + VoiceInteractionService). */
    const val GOOGLE_PKG = "com.google.android.googlequicksearchbox"

    fun launch(ctx: Context, target: VoiceKeyTarget): Boolean {
        val app = ctx.applicationContext
        val candidates: List<Intent> = when (target) {
            VoiceKeyTarget.ASSIST -> buildList {
                // Ưu tiên MỞ THẲNG app Gemini/Google (xác nhận trên xe: bard → "robin" car voice surface).
                // Intent CHUNG ACTION_ASSIST/VOICE_COMMAND dính hộp chọn (ResolverActivity) khi máy chưa set
                // trợ lý mặc định → mở NHẦM (owner gặp: bật Bluetooth). Nên thử package trực tiếp trước.
                app.packageManager.getLaunchIntentForPackage(GEMINI_PKG)?.let { add(it) }
                app.packageManager.getLaunchIntentForPackage(GOOGLE_PKG)?.let { add(it) }
                add(Intent(Intent.ACTION_ASSIST).setPackage(GOOGLE_PKG))
                add(Intent(Intent.ACTION_VOICE_COMMAND).setPackage(GOOGLE_PKG))
                add(Intent(Intent.ACTION_ASSIST))          // fallback: trợ lý mặc định (nếu đã set)
                add(Intent(Intent.ACTION_VOICE_COMMAND))
            }
            VoiceKeyTarget.BYD_VOICE -> buildList {
                app.packageManager.getLaunchIntentForPackage(BYD_VOICE_PKG)?.let { add(it) }
                add(Intent(Intent.ACTION_VOICE_COMMAND).setPackage(BYD_VOICE_PKG))
                add(Intent(Intent.ACTION_VOICE_COMMAND))   // fallback: trợ lý mặc định của hệ thống
            }
            VoiceKeyTarget.RECOGNIZER -> listOf(
                Intent(RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE),
                Intent(RecognizerIntent.ACTION_WEB_SEARCH),
            )
        }
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = runCatching { app.startActivity(intent); true }.getOrDefault(false)
            if (ok) {
                Log.i(TAG, "launched assistant target=$target via ${intent.action ?: intent.getPackage()}")
                return true
            }
        }
        Log.e(TAG, "no assistant activity handled target=$target")
        return false
    }
}

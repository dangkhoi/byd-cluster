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

    fun launch(ctx: Context, target: VoiceKeyTarget): Boolean {
        val app = ctx.applicationContext
        val candidates: List<Intent> = when (target) {
            VoiceKeyTarget.ASSIST -> listOf(
                Intent(Intent.ACTION_VOICE_COMMAND),
                Intent(Intent.ACTION_ASSIST),
                Intent(RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE),
            )
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

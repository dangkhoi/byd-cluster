package com.byd.clusternav.modules.voicekey

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.util.Log
import com.byd.clusternav.Prefs

/**
 * Mở đích của "Nút vật lý → mở app". Rework 1.19: đích = 1 STRING — hoặc **package name** (mở thẳng app),
 * hoặc 1 trong 2 **sentinel** đặc biệt. Gọi từ [com.byd.clusternav.modules.navaccess.NavAccessibilityService]
 * (không có Activity context) → cần FLAG_ACTIVITY_NEW_TASK.
 *
 * BỎ kiểu đoán ACTION_ASSIST theo enum trợ lý (thủ phạm 1.18: chọn Kiki nhưng mở Gemini vì intent chung
 * dính trợ lý mặc định). Chọn app trực tiếp → launch-intent của đúng package → không lạc app khác.
 */
object AssistantLauncher {
    private const val TAG = "VoiceKeyLauncher"

    // Nguồn CHÂN LÝ DUY NHẤT của 2 sentinel = Prefs (nơi lưu/migrate spec + nơi MainActivity dựng dropdown).
    // Delegate compile-time const → không thể lệch literal giữa producer (Prefs/MainActivity) và consumer (đây).
    /** Trợ lý mặc định hệ thống (ghim đầu list). */
    const val TARGET_ASSIST = Prefs.VK_TARGET_ASSIST

    /** Nhận dạng giọng nói — RecognizerIntent (ghim đầu list). */
    const val TARGET_RECOGNIZER = Prefs.VK_TARGET_RECOGNIZER

    /** @param spec package name của app, hoặc [TARGET_ASSIST]/[TARGET_RECOGNIZER]. */
    fun launch(ctx: Context, spec: String): Boolean {
        val app = ctx.applicationContext
        val candidates: List<Intent> = when (spec) {
            TARGET_ASSIST -> listOf(Intent(Intent.ACTION_ASSIST), Intent(Intent.ACTION_VOICE_COMMAND))
            TARGET_RECOGNIZER -> listOf(
                Intent(RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE),
                Intent(RecognizerIntent.ACTION_WEB_SEARCH),
            )
            else -> buildList {
                // Mở THẲNG app đã chọn: launch-intent của package; fallback ACTION_MAIN+LAUNCHER setPackage.
                app.packageManager.getLaunchIntentForPackage(spec)?.let { add(it) }
                add(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(spec))
            }
        }
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = runCatching { app.startActivity(intent); true }.getOrDefault(false)
            if (ok) {
                Log.i(TAG, "launched target=$spec via ${intent.getPackage() ?: intent.action}")
                return true
            }
        }
        Log.e(TAG, "no activity handled target=$spec")
        return false
    }
}

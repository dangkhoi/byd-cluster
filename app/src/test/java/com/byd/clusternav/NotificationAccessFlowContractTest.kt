package com.byd.clusternav

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * WIRING contract for the in-app notification-access grant (on-car fix 2026-08-11).
 *
 * The head unit ships to end users WITHOUT a laptop/adb, so the app must open the system
 * "Notification access" screen itself. Runtime behavior needs Android, so — like the other
 * contract tests — this locks the wiring by reading the source.
 */
class NotificationAccessFlowContractTest {

    private fun app(relative: String): Path {
        val current = Path.of(System.getProperty("user.dir"))
        return if (Files.exists(current.resolve("src"))) current.resolve(relative) else current.resolve("app").resolve(relative)
    }

    private val main by lazy {
        app("src/main/java/com/byd/clusternav/MainActivity.kt").toFile().readText()
    }

    private fun body(signature: String): String {
        val start = main.indexOf(signature)
        require(start >= 0) { "missing $signature" }
        val after = start + signature.length
        val next = listOf("\n    private fun ", "\n    override fun ", "\n    fun ", "\n}")
            .mapNotNull { main.indexOf(it, after).takeIf { i -> i >= 0 } }
            .minOrNull() ?: main.length
        return main.substring(start, next)
    }

    @Test
    fun `reconnect button opens notification access when permission missing`() {
        // The whole point: a real user (no adb) can reach the toggle. Grep proved this action was
        // entirely absent before the fix.
        assertTrue(main.contains("Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),
            "app opens the system Notification-access list")
        val opener = body("private fun openNotificationAccessSettings()")
        assertTrue(opener.contains("ACTION_NOTIFICATION_LISTENER_SETTINGS"), "list screen is a path")
        assertTrue(
            opener.contains("SDK_INT >= 30") && opener.contains("ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS"),
            "API 30+ deep-links straight to ClusterNav's entry, guarded",
        )
        assertTrue(opener.contains("ACTION_APPLICATION_DETAILS_SETTINGS"), "app-details is the last fallback")
    }

    @Test
    fun `button branches on permission and prompts when missing`() {
        assertTrue(main.contains("if (notificationAccessGranted()) {"), "button checks the permission first")
        assertTrue(main.contains("promptNotificationAccess()"), "missing → prompt + open settings")
        assertTrue(main.contains("NavConnect.reconnect(applicationContext)"), "granted → reconnect (rebind)")
    }

    @Test
    fun `onResume auto-binds after returning from settings`() {
        val resume = body("override fun onResume()")
        assertTrue(
            resume.contains("notificationAccessGranted() && !NavNotificationListener.connected"),
            "granted but not yet bound → force a bind on return",
        )
        assertTrue(resume.contains("NavConnect.ensureConnected(applicationContext)"), "binds via the dadb helper")
    }
}

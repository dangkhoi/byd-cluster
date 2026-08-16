package com.byd.clusternav

import android.content.Context

/**
 * Cheap IN-MEMORY gate for verbose diagnostic logging.
 *
 * OTA ships a RELEASE apk (no `BuildConfig.DEBUG`) and the owner debugs on-car via logcat, so verbosity is a
 * RUNTIME flag defaulting OFF — flipped by the hidden long-press on the version label (MainActivity) and mirrored
 * here so per-frame hot paths (BydHal keep-alive, ClusterBroadcaster / NavArrowLog / NavDistanceLog,
 * ManeuverSignature.note) read a `@Volatile` field instead of hitting SharedPreferences ~4×/second.
 *
 * The persisted source of truth is [Prefs.navVerboseLog]; [init] refreshes this mirror at the app entry points
 * that always run (MainActivity.onCreate, NavNotificationListener.onListenerConnected) and the D5 toggle keeps
 * both in sync.
 */
object NavLog {
    @Volatile
    var verbose = false

    /** Refresh the in-memory gate from the persisted flag. Call at entry points that always run. */
    fun init(ctx: Context) {
        verbose = Prefs.navVerboseLog(ctx)
    }
}

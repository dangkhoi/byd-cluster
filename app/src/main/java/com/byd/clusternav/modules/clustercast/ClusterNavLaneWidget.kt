package com.byd.clusternav.modules.clustercast

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.byd.clusternav.Prefs
import com.byd.clusternav.modules.clustercast.simplified.SimpleCastRuntime
import java.util.concurrent.Executors

/**
 * Nav-track activator for the OEM "simple navigation" cluster widget (clusterDebug opcode 39).
 *
 * WHY (on-car proof 2026-08-12, ClusterNav 1.06 / DiLink3.0, parked): with the cluster on native
 * gauges, broadcasting AUTONAVI nav frames alone did NOT surface the nav overlay — issuing
 * `service call AutoContainer 2 i32 1000 i32 39` ("simple navigation") is what makes the OEM nav
 * overlay (turn arrow + distance + road + live ETA) appear on the cluster, fed by the ongoing nav
 * broadcast. It persisted across opcodes 12/17/16 in the probe sweep and reproduced cleanly via
 * 18→0→39 (see docs/diagnostics/oncar-probes-2026-08-11.md result tables).
 *
 * TWO-TRACK boundary: op 39 is asserted ONLY in nav-only mode (Cast master switch OFF). When Cast
 * is ON, the Cast track owns the cluster surface and we must not fight it. The gate reads the
 * persisted `castEnabled` flag (a user config, not live cast-control state), keeping the tracks
 * decoupled, and reuses the same shell the nav listener already uses for WazeHUD polling
 * ([SimpleCastCoordinator.executeShell]) rather than opening a second transport.
 *
 * Idempotent + debounced: asserts once per active nav session and re-asserts at most every
 * [REASSERT_MS] so a mid-session cluster refresh restores the widget without spamming the bus. All
 * shell I/O runs on a dedicated single thread so notification-listener callbacks never block.
 */
object ClusterNavLaneWidget {
    private const val TAG = "ClusterNavWidget"
    private const val REASSERT_MS = 30_000L

    /** clusterDebug opcode: 39 = "simple navigation" (raise the OEM nav overlay on the cluster). */
    const val OP_SIMPLE_NAV = 39

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "cluster-nav-widget") }

    @Volatile private var active = false
    @Volatile private var lastAssertAtMs = 0L

    /**
     * Pure decision (unit-tested): assert the cluster nav widget only when navigation is active, the
     * Cast master switch is OFF (nav-only mode), AND the user picked the centre+ETA display mode.
     * Casting owns the cluster otherwise; and the "small/top" display mode intentionally does NOT
     * raise the op-39 overlay (see [onNavActive]).
     */
    fun shouldAssert(navActive: Boolean, castEnabled: Boolean, centerMode: Boolean): Boolean =
        navActive && !castEnabled && centerMode

    /**
     * Nav became / stayed active. If in nav-only mode AND the user chose the centre+ETA display mode
     * ([Prefs.NAV_MODE_CENTER_ETA]), assert op 39 (debounced by [REASSERT_MS]). Safe to call at
     * notification rate and from any thread; the debounce short-circuits cheaply and the shell call is
     * offloaded.
     *
     * Display-mode gate (owner 2026-08-12): for [Prefs.NAV_MODE_SMALL_TOP] we deliberately do NOT
     * assert op 39 — the ongoing nav broadcast draws the cluster's default (smaller/top) nav strip on
     * its own. The exact opcode for a distinct "small/top" OEM overlay has NOT been probed on-car yet,
     * so this branch is provisional: revisit once an on-car opcode sweep identifies it (candidate
     * space around the op 12/16/17 family already probed for the centre overlay).
     */
    fun onNavActive(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (active && now - lastAssertAtMs < REASSERT_MS) return
        val appCtx = context.applicationContext
        io.execute {
            val castEnabled = runCatching {
                SimpleCastRuntime.coordinator(appCtx).prefs.castEnabled()
            }.getOrDefault(false)
            val centerMode = runCatching {
                Prefs.navClusterMode(appCtx) == Prefs.NAV_MODE_CENTER_ETA
            }.getOrDefault(true)
            if (!shouldAssert(navActive = true, castEnabled = castEnabled, centerMode = centerMode)) return@execute
            val t = SystemClock.elapsedRealtime()
            if (active && t - lastAssertAtMs < REASSERT_MS) return@execute
            active = true
            lastAssertAtMs = t
            val result = runCatching {
                SimpleCastRuntime.coordinator(appCtx)
                    .executeShell("service call AutoContainer 2 i32 1000 i32 $OP_SIMPLE_NAV s16 \"\"")
            }
            result.onSuccess { Log.i(TAG, "cluster nav widget asserted (op $OP_SIMPLE_NAV) ok=${it.success}") }
                .onFailure { Log.w(TAG, "op $OP_SIMPLE_NAV assert failed", it) }
        }
    }

    /**
     * Nav stopped. Resets the debounce so the next session re-asserts promptly.
     *
     * NOTE (on-car verification pending): a dedicated teardown of the simple-navigation widget on
     * nav stop is intentionally NOT issued here — the nav broadcast already sends STATE_STOP which
     * clears the overlay content, and the exact opcode to return the cluster to full gauges was not
     * verified on-car. Revisit once verified (candidate: op 0 refresh).
     */
    fun onNavIdle() {
        active = false
        lastAssertAtMs = 0L
    }
}

package com.byd.clusternav.modules.wazehud

import android.util.Log
import com.byd.clusternav.NavState
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads WazeMod HLP/1 JSON (logcat tag "WazeHUD") and converts to NavState.
 *
 * WazeMod's HudLink producer streams a FULL-snapshot JSON line per frame to logcat when
 * `hud_link_log=true` (default) in SharedPreferences `waze_hud_gw`. Protocol HLP/1 verified
 * 2026-08-05 against https://wazemod.chisadin.id.vn/tai-lieu/esp32:
 * envelope `{"v":1,"t":"s",...}`; fields nav/spd/lim/over/trn/trn2/dst/exit/st/st2/eta/rmin/rkm/
 * avg/alr/alrD/alrV/ts.
 *
 * TRANSPORT (fixed 2026-08-05): logcat is read through the privileged dadb shell (uid 2000), NOT an
 * in-process `logcat` subprocess. On Android 7+ an app-uid `logcat` only sees its OWN logs unless it
 * holds READ_LOGS; WazeMod logs from a different process, so in-process reading returned nothing on
 * the vehicle (root cause of "Waze nav+speed dead on car"). The shell (uid 2000) has full log access,
 * so we POLL `logcat -d -v raw -s WazeHUD:V -t N` through it and de-duplicate by the monotonic `ts`.
 * Level is `:V` (all) — the producer's LogSink is a debug stream and `:I` dropped debug/verbose lines.
 *
 * @param shell runs a shell command via the privileged transport; returns stdout, or null on failure.
 */
class WazeHudSource(private val shell: (command: String) -> String?) {

    companion object {
        private const val TAG = "WazeHudSource"
        private const val LOGCAT_TAG = "WazeHUD"
        private const val POLL_MS = 900L
        private const val TAIL_LINES = 60
        private const val DUMP_CMD = "logcat -d -v raw -s $LOGCAT_TAG:V -t $TAIL_LINES"
        // Belt-and-suspenders: also grant READ_LOGS so any in-app log read works too. Idempotent;
        // uid-2000 shell may grant it (READ_LOGS has the `development` protection flag). Harmless if it fails.
        private const val GRANT_CMD = "pm grant com.byd.clusternav android.permission.READ_LOGS"

        /** Map HLP/1 turn enum to amap-compatible maneuver code used by ClusterNav. */
        fun hlpTurnToManeuverCode(trn: Int): Int = when (trn) {
            0 -> 0    // none
            1 -> 9    // straight (depart/continue)
            2 -> 2    // turn left
            3 -> 3    // turn right
            4 -> 12   // slight left
            5 -> 13   // slight right
            6 -> 10   // sharp left
            7 -> 11   // sharp right
            8 -> 17   // u-turn
            10 -> 15  // roundabout (generic)
            11 -> 15  // roundabout left
            12 -> 15  // roundabout right
            13 -> 12  // keep left (same as slight left)
            14 -> 13  // keep right (same as slight right)
            15 -> 12  // exit left
            16 -> 13  // exit right
            17 -> 19  // arrived
            else -> 0
        }
    }

    private val running = AtomicBoolean(false)
    private var scheduler: ScheduledExecutorService? = null

    /** De-dup cursor: last emitted producer uptime (`ts`), and last raw line when `ts` is absent. */
    @Volatile private var lastEmittedTs: Long = -1L
    @Volatile private var lastRaw: String? = null

    @Volatile var lastState: WazeHudState? = null
        private set

    @Volatile var listener: ((WazeHudState) -> Unit)? = null

    fun start() {
        if (running.getAndSet(true)) return
        val exec = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "waze-hud-poll").apply { isDaemon = true }
        }
        scheduler = exec
        Log.i(TAG, "polling '$DUMP_CMD' via privileged shell every ${POLL_MS}ms")
        // Best-effort READ_LOGS grant runs ON the poll thread, never on start()'s caller. start() is
        // invoked from NotificationListenerService callbacks (main thread) and shell() opens a blocking
        // dadb connection — doing it inline would put network I/O on the main thread (ANR risk). The
        // single-thread executor runs this before the first (0-delay) poll; the shell-read path does
        // not depend on it anyway.
        exec.execute { runCatching { shell(GRANT_CMD) } }
        exec.scheduleWithFixedDelay({
            runCatching {
                val dump = shell(DUMP_CMD)
                if (dump.isNullOrBlank()) return@runCatching
                val state = pollOnce(dump) ?: return@runCatching
                lastState = state
                listener?.invoke(state)
            }.onFailure { Log.e(TAG, "poll failed", it) }
        }, 0L, POLL_MS, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        running.set(false)
        scheduler?.shutdownNow()
        scheduler = null
        lastState = null
        lastEmittedTs = -1L
        lastRaw = null
    }

    /**
     * Parse a chronological logcat dump and return the NEWEST not-yet-emitted state, or null if
     * nothing new. `logcat -t` prints oldest→newest, so the last parseable line is the freshest
     * snapshot. De-dup by monotonic `ts`; when `ts` is absent (0) fall back to raw-line comparison.
     * Internal for unit testing.
     */
    internal fun pollOnce(dump: String): WazeHudState? {
        var newest: WazeHudState? = null
        var newestRaw: String? = null
        for (line in dump.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("{")) continue
            val parsed = runCatching { parseHlp(trimmed) }.getOrNull() ?: continue
            newest = parsed
            newestRaw = trimmed
        }
        val state = newest ?: return null
        if (state.timestampMs != 0L) {
            // De-dup a re-read of the SAME newest frame. Drop only on an EXACT ts match, not `<=`:
            // `ts` is the producer's monotonic uptime, so a strictly-smaller value means WazeMod
            // restarted (uptime reset). Rejecting `< lastEmittedTs` would stall the feed until the new
            // uptime climbed back past the old value; accepting it recovers immediately. A single
            // monotonic stream cannot otherwise deliver an out-of-order older line.
            if (state.timestampMs == lastEmittedTs) return null
            lastEmittedTs = state.timestampMs
        } else if (newestRaw == lastRaw) {
            return null
        }
        lastRaw = newestRaw
        return state
    }

    /**
     * Parse a single HLP/1 JSON line into WazeHudState.
     * Returns null for non-state messages (handshake, etc).
     */
    internal fun parseHlp(json: String): WazeHudState? {
        val obj = JSONObject(json)
        if (obj.optInt("v", 0) != 1) return null
        if (obj.optString("t") != "s") return null

        return WazeHudState(
            navigating = obj.optInt("nav", 0) == 1,
            speedKmh = obj.optInt("spd", 0),
            speedLimitKmh = obj.optInt("lim", 0),
            overSpeed = obj.optInt("over", 0) == 1,
            turnCode = obj.optInt("trn", 0),
            turnCode2 = obj.optInt("trn2", 0),
            distanceMeters = obj.optInt("dst", -1),
            exitNumber = obj.optInt("exit", 0),
            currentStreet = obj.optString("st", ""),
            nextStreet = obj.optString("st2", ""),
            eta = obj.optString("eta", ""),
            remainingMinutes = obj.optInt("rmin", -1),
            remainingKm = obj.optDouble("rkm", -1.0),
            alertType = obj.optInt("alr", 0),
            alertDistanceMeters = obj.optInt("alrD", -1),
            alertValue = obj.optInt("alrV", 0),
            avgZone = obj.optInt("avg", 0) == 1,
            timestampMs = obj.optLong("ts", 0),
        )
    }

    /**
     * Convert WazeHudState to NavState for the existing ClusterNav pipeline.
     * This allows WazeMod to feed into the same lane/HUD/cluster outputs.
     */
    fun toNavState(state: WazeHudState): NavState {
        val distanceText = when {
            state.distanceMeters < 0 -> ""
            state.distanceMeters >= 1000 -> String.format("%.1f km", state.distanceMeters / 1000.0)
            else -> "${state.distanceMeters} m"
        }
        val etaText = buildString {
            if (state.eta.isNotBlank()) append(state.eta)
            if (state.remainingKm > 0) {
                if (isNotEmpty()) append(" · ")
                append(String.format("%.1f km", state.remainingKm))
            }
            if (state.remainingMinutes > 0) {
                if (isNotEmpty()) append(" · ")
                append("${state.remainingMinutes} min")
            }
        }
        return NavState(
            active = state.navigating,
            distance = distanceText,
            road = state.nextStreet.ifBlank { state.currentStreet },
            maneuverIcon = hlpTurnToManeuverCode(state.turnCode),
            eta = etaText,
            updatedAt = System.currentTimeMillis(),
        )
    }
}

/**
 * Parsed HLP/1 state snapshot from WazeMod.
 * Immutable data class — each logcat line produces a new instance.
 */
data class WazeHudState(
    val navigating: Boolean,
    val speedKmh: Int,
    val speedLimitKmh: Int,
    val overSpeed: Boolean,
    val turnCode: Int,
    val turnCode2: Int,
    val distanceMeters: Int,
    val exitNumber: Int,
    val currentStreet: String,
    val nextStreet: String,
    val eta: String,
    val remainingMinutes: Int,
    val remainingKm: Double,
    val alertType: Int,
    val alertDistanceMeters: Int,
    val alertValue: Int,
    val avgZone: Boolean,
    val timestampMs: Long,
)

package com.byd.clusternav

import com.byd.clusternav.navigation.NavParse
import com.byd.clusternav.navigation.SourceArbiter
import com.byd.clusternav.modules.clustercast.simplified.SimpleCastRuntime
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.byd.clusternav.navigation.ClusterLaneAdapter
import com.byd.clusternav.navigation.HudAdapter
import com.byd.clusternav.navigation.InteractionContext
import com.byd.clusternav.navigation.Maneuver
import com.byd.clusternav.navigation.NavigationFrame
import com.byd.clusternav.navigation.NavigationFrameContent
import com.byd.clusternav.navigation.NavigationFrameDelivery
import com.byd.clusternav.navigation.NavigationFramePersistence
import com.byd.clusternav.navigation.NavigationOutputTarget
import com.byd.clusternav.navigation.NavigationPermission
import com.byd.clusternav.navigation.NavigationSessionCoordinator
import com.byd.clusternav.navigation.NavigationSourceIdentity
import com.byd.clusternav.navigation.NavigationUiState
import com.byd.clusternav.navigation.PersistentNavigationFrameStore
import com.byd.clusternav.navigation.StoredNavigationSession
import java.util.concurrent.CopyOnWriteArrayList

/** Authoritative Navigation runtime facade. UI consumers remain read-only observers. */
object NavRepository {
    @Volatile var state: NavState = NavState()
        private set

    private val listeners = CopyOnWriteArrayList<(NavState) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    @Volatile private var coordinator: NavigationSessionCoordinator? = null

    /** Owns the cluster center-nav HAL writer (Giữa+ETA). Created with the coordinator; stopped on nav stop. */
    @Volatile private var hudOwner: NavigationHudOwner? = null

    fun connect(context: Context, permission: NavigationPermission): NavigationSessionCoordinator = synchronized(lock) {
        coordinator ?: createCoordinator(context.applicationContext).also { runtime ->
            coordinator = runtime
            runtime.setPermission(permission)
            runtime.rehydrate()
            runtime.setOutputEnabled(NavigationOutputTarget.CLUSTER_LANE, Prefs.lane(context))
            runtime.setOutputEnabled(NavigationOutputTarget.HUD, Prefs.hud(context))
        }
    }

    fun setPermission(context: Context, permission: NavigationPermission) {
        connect(context, permission).setPermission(permission)
    }

    fun ingest(context: Context, packageName: String, displayName: String?, value: NavState) {
        val runtime = connect(context, NavigationPermission.GRANTED)
        val source = NavigationSourceIdentity(packageName, displayName?.takeIf(String::isNotBlank))
        val current = runtime.snapshot().source
        if (current.sessionId == null || current.identity != source) runtime.startSession(source)
        runtime.acceptFrame(
            source,
            NavigationFrameContent(
                maneuverCode = value.maneuverIcon.takeIf { it >= 0 },
                maneuverText = value.maneuverText.takeIf(String::isNotBlank),
                distanceMeters = NavParse.parseMeters(value.distance).takeIf { it >= 0 },
                roadName = value.road.takeIf(String::isNotBlank),
                etaEpochMs = null,
                routeRemainingMeters = NavParse.parseEta(value.eta).first.takeIf { it >= 0 },
                routeRemainingSeconds = NavParse.parseEta(value.eta).second.takeIf { it >= 0 },
                arrivalClock = NavParse.extractArrivalClock(value.eta),
                // Chốt maneuver TRUNG LẬP MỘT LẦN tại đây (biên đầu vào): ưu tiên maneuver đã có sẵn
                // (nguồn trực tiếp), nếu không thì bắc cầu từ mã AMAP đã phân loại. Cả hai đầu ra encode từ đây.
                maneuver = value.maneuver ?: Maneuver.fromAmapIcon(value.maneuverIcon),
            ),
        )
        publish(value)
    }

    fun setOutputEnabled(context: Context, target: NavigationOutputTarget, enabled: Boolean) {
        connect(context, permission()).setOutputEnabled(target, enabled)
        if (!enabled && target == NavigationOutputTarget.HUD) ClusterBroadcaster.stopHud(context)
        if (target == NavigationOutputTarget.CLUSTER_LANE) {
            // Cluster center-nav HAL owner follows the cluster-lane toggle (same surface concern).
            if (enabled) hudOwner?.start() else { ClusterBroadcaster.stopLane(context); hudOwner?.stop() }
        }
    }

    fun stop(context: Context) {
        synchronized(lock) { coordinator }?.stopSession()
        ClusterBroadcaster.stop(context)
        hudOwner?.stop()
        SourceArbiter.clear()
        publish(NavState())
    }

    fun snapshot(context: Context, interaction: InteractionContext = InteractionContext.UNKNOWN): NavigationUiState =
        connect(context, permission()).also { it.refreshFreshness() }.snapshot(interaction)

    /** Compatibility for demo-only UI; production notification ingestion uses [ingest]. */
    fun update(value: NavState) = publish(value)
    fun clear() = publish(NavState())
    fun hasListeners(): Boolean = listeners.isNotEmpty()
    fun addListener(listener: (NavState) -> Unit) { listeners += listener; main.post { listener(state) } }
    fun removeListener(listener: (NavState) -> Unit) { listeners -= listener }

    private fun createCoordinator(context: Context): NavigationSessionCoordinator {
        val appCtx = context.applicationContext
        val owner = NavigationHudOwner(appCtx).also { it.start() }
        hudOwner = owner
        val lane = ClusterLaneAdapter(NavigationFrameDelivery { frame ->
            ClusterBroadcaster.emitLane(context, frame.toNavState())
            // Cluster CENTER "Giữa + ETA" via the proven HAL path (SET_NAVI_SCREEN_STATUS + GUIDE_INFO_SIMPLE) —
            // replaces the no-op ch1000 op39. TWO-TRACK: only in nav-only mode (Cast master OFF); when Cast is ON
            // the Cast track owns the cluster surface and we must not fight it. Broadcast heartbeat keeps content
            // fresh; this write sets/holds the OEM nav-screen MODE (Đơn giản/full) the broadcast alone cannot.
            if (navOnlyMode(appCtx)) {
                owner.push(
                    icon = frame.content.maneuverCode ?: 11,
                    segMeters = frame.content.distanceMeters ?: -1,
                    hudRoad = frame.content.roadName.orEmpty(),
                )
            }
        })
        val hud = HudAdapter(NavigationFrameDelivery { ClusterBroadcaster.emitHud(context, it.toNavState()) })
        return NavigationSessionCoordinator(
            PersistentNavigationFrameStore(PreferencesPersistence(context)), lane, hud,
        )
    }

    /** TWO-TRACK gate: cluster center-nav HAL write only when the Cast master switch is OFF (persisted flag,
     *  a user config — not live cast-control state), keeping the Navigation and Cast tracks decoupled. */
    private fun navOnlyMode(appContext: Context): Boolean = runCatching {
        !SimpleCastRuntime.coordinator(appContext).prefs.castEnabled()
    }.getOrDefault(true)

    private fun NavigationFrame.toNavState() = NavState(
        active = true,
        distance = content.distanceMeters?.let { "$it m" }.orEmpty(),
        road = content.roadName.orEmpty(),
        maneuverText = content.maneuverText.orEmpty(),
        // maneuver TRUNG LẬP là nguồn sự thật; maneuverIcon (AMAP) suy ra cho làn cụm (khứ hồi chính xác).
        maneuverIcon = content.maneuver?.toAmapIcon() ?: content.maneuverCode ?: -1,
        maneuver = content.maneuver,
        eta = buildString {
            content.arrivalClock?.let { append(it) }
            content.routeRemainingMeters?.let { m -> if (isNotEmpty()) append(" · "); append(NavParse.formatMeters(m)) }
            content.routeRemainingSeconds?.let { s -> if (isNotEmpty()) append(" · "); append(NavParse.formatSeconds(s)) }
        },
        updatedAt = receivedAtEpochMs,
    )

    private fun publish(value: NavState) {
        state = value
        main.post { listeners.forEach { it(value) } }
    }

    private fun permission(): NavigationPermission =
        if (NavNotificationListener.connected) NavigationPermission.GRANTED else NavigationPermission.UNKNOWN

    private class PreferencesPersistence(context: Context) : NavigationFramePersistence {
        private val prefs = context.getSharedPreferences("navigation_session_v2", Context.MODE_PRIVATE)
        override fun load(): StoredNavigationSession? {
            return try {
                val id = prefs.getString("sessionId", null) ?: return null
                val pkg = prefs.getString("sourcePackage", null) ?: return null
                val source = NavigationSourceIdentity(pkg, prefs.getString("sourceDisplay", null))
                val sequence = prefs.getLong("sequence", 0L)
                val frame = if (sequence > 0) NavigationFrame(
                    id, source, sequence, prefs.getLong("receivedAt", 0L),
                    NavigationFrameContent(
                        prefs.getInt("maneuverCode", Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE },
                        prefs.getString("maneuverText", null),
                        prefs.getInt("distanceMeters", Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE },
                        prefs.getString("roadName", null),
                        prefs.getLong("eta", Long.MIN_VALUE).takeUnless { it == Long.MIN_VALUE },
                        prefs.getInt("routeRemainingMeters", Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE },
                        prefs.getInt("routeRemainingSeconds", Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE },
                        prefs.getString("arrivalClock", null),
                        maneuver = prefs.getString("maneuver", null)
                            ?.let { name -> runCatching { Maneuver.valueOf(name) }.getOrNull() }
                            ?: prefs.getInt("maneuverCode", Int.MIN_VALUE)
                                .takeUnless { it == Int.MIN_VALUE }?.let { Maneuver.fromAmapIcon(it) },
                    ),
                ) else null
                StoredNavigationSession(id, source, prefs.getLong("startedAt", 0L), frame)
            } catch (e: Exception) {
                // Fail closed: corrupted persistence must not crash Home. Clear and return empty.
                android.util.Log.e("NavPersistence", "load corruption — clearing", e)
                runCatching { prefs.edit().clear().commit() }
                null
            }
        }

        override fun save(session: StoredNavigationSession) {
            val frame = session.latestFrame
            check(prefs.edit().clear()
                .putString("sessionId", session.sessionId)
                .putString("sourcePackage", session.source.packageName)
                .putString("sourceDisplay", session.source.displayName)
                .putLong("startedAt", session.startedAtEpochMs)
                .putLong("sequence", frame?.sequence ?: 0L)
                .putLong("receivedAt", frame?.receivedAtEpochMs ?: 0L)
                .putInt("maneuverCode", frame?.content?.maneuverCode ?: Int.MIN_VALUE)
                .putString("maneuver", frame?.content?.maneuver?.name)
                .putString("maneuverText", frame?.content?.maneuverText)
                .putInt("distanceMeters", frame?.content?.distanceMeters ?: Int.MIN_VALUE)
                .putString("roadName", frame?.content?.roadName)
                .putLong("eta", frame?.content?.etaEpochMs ?: Long.MIN_VALUE)
                .putInt("routeRemainingMeters", frame?.content?.routeRemainingMeters ?: Int.MIN_VALUE)
                .putInt("routeRemainingSeconds", frame?.content?.routeRemainingSeconds ?: Int.MIN_VALUE)
                .putString("arrivalClock", frame?.content?.arrivalClock)
                .commit()) { "failed to persist Navigation session" }
        }

        override fun clear() { check(prefs.edit().clear().commit()) { "failed to clear Navigation session" } }
    }
}

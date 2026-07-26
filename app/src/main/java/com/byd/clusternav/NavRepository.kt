package com.byd.clusternav

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.byd.clusternav.navigation.ClusterLaneAdapter
import com.byd.clusternav.navigation.HudAdapter
import com.byd.clusternav.navigation.InteractionContext
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
            ),
        )
        publish(value)
    }

    fun setOutputEnabled(context: Context, target: NavigationOutputTarget, enabled: Boolean) {
        connect(context, permission()).setOutputEnabled(target, enabled)
        if (!enabled && target == NavigationOutputTarget.HUD) ClusterBroadcaster.stopHud(context)
        if (!enabled && target == NavigationOutputTarget.CLUSTER_LANE) ClusterBroadcaster.stopLane(context)
    }

    fun stop(context: Context) {
        synchronized(lock) { coordinator }?.stopSession()
        ClusterBroadcaster.stop(context)
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
        val lane = ClusterLaneAdapter(NavigationFrameDelivery { ClusterBroadcaster.emitLane(context, it.toNavState()) })
        val hud = HudAdapter(NavigationFrameDelivery { ClusterBroadcaster.emitHud(context, it.toNavState()) })
        return NavigationSessionCoordinator(
            PersistentNavigationFrameStore(PreferencesPersistence(context)), lane, hud,
        )
    }

    private fun NavigationFrame.toNavState() = NavState(
        active = true,
        distance = content.distanceMeters?.let { "$it m" }.orEmpty(),
        road = content.roadName.orEmpty(),
        maneuverText = content.maneuverText.orEmpty(),
        maneuverIcon = content.maneuverCode ?: -1,
        eta = content.etaEpochMs?.toString().orEmpty(),
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
                ),
            ) else null
            return StoredNavigationSession(id, source, prefs.getLong("startedAt", 0L), frame)
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
                .putString("maneuverText", frame?.content?.maneuverText)
                .putInt("distanceMeters", frame?.content?.distanceMeters ?: Int.MIN_VALUE)
                .putString("roadName", frame?.content?.roadName)
                .putLong("eta", frame?.content?.etaEpochMs ?: Long.MIN_VALUE)
                .commit()) { "failed to persist Navigation session" }
        }

        override fun clear() { check(prefs.edit().clear().commit()) { "failed to clear Navigation session" } }
    }
}

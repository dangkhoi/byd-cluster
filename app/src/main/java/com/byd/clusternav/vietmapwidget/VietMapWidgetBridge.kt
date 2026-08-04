package com.byd.clusternav.vietmapwidget

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.byd.clusternav.vietmapwidget.VietMapWidgetTextParser.parseSnapshot
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet

private const val VIETMAP_PACKAGE = "vn.vietmap.live"

enum class VietMapWidgetSlot(
    internal val preferenceKey: String,
    val displayName: String,
    providerClassName: String,
) {
    SPEED_LIMIT(
        "speed_limit_widget_id",
        "Speed limit",
        "vn.vietmap.live.homewidget.VMOnlySpeedLimitWidgetProvider",
    ),
    ALERTS(
        "alerts_widget_id",
        "Road alerts",
        "vn.vietmap.live.homewidget.VMAlertWidgetProvider",
    );

    val component = ComponentName(VIETMAP_PACKAGE, providerClassName)
}

enum class VietMapWidgetOwner {
    NAVIGATION,
    DIAGNOSTICS,
}

data class VietMapWidgetBindingStatus(
    val slot: VietMapWidgetSlot,
    val appWidgetId: Int?,
    val providerAvailable: Boolean,
    val bound: Boolean,
)

sealed interface VietMapWidgetBindResult {
    data class Bound(val slot: VietMapWidgetSlot) : VietMapWidgetBindResult
    data class ConsentRequired(
        val slot: VietMapWidgetSlot,
        val appWidgetId: Int,
        val intent: Intent,
    ) : VietMapWidgetBindResult
    data class Failed(
        val slot: VietMapWidgetSlot,
        val reason: VietMapWidgetUnavailableReason,
        val detail: String,
    ) : VietMapWidgetBindResult
}

class VietMapWidgetBridge private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val manager = AppWidgetManager.getInstance(appContext)
    private val prefs = VietMapWidgetPrefs(appContext)
    private val main = Handler(Looper.getMainLooper())
    private val host = VietMapAppWidgetHost(appContext, HOST_ID, ::onHostViewUpdated)
    private val owners = linkedSetOf<VietMapWidgetOwner>()
    private val listeners = CopyOnWriteArraySet<(VietMapWidgetSnapshot) -> Unit>()
    private val views = mutableMapOf<VietMapWidgetSlot, AppWidgetHostView>()
    private val slotsById = mutableMapOf<Int, VietMapWidgetSlot>()
    private val unsupportedSlots = mutableSetOf<VietMapWidgetSlot>()

    private var remoteResources: Resources? = null
    private var listening = false
    private var speedRaw: VietMapWidgetRawValues? = null
    private var alertsRaw: VietMapWidgetRawValues? = null
    private var speedUpdatedAt: Long? = null
    private var alertsUpdatedAt: Long? = null

    @Volatile
    private var published = unavailable(VietMapWidgetUnavailableReason.NOT_BOUND)

    private val publishDebounced = Runnable { publishSnapshot() }
    private val freshnessTick = object : Runnable {
        override fun run() {
            if (!listening) return
            publishSnapshot()
            main.postDelayed(this, FRESHNESS_TICK_MS)
        }
    }

    fun start(owner: VietMapWidgetOwner) = onMain {
        owners.add(owner)
        if (listening) return@onMain
        try {
            host.startListening()
            listening = true
            restoreBoundViews()
            main.removeCallbacks(freshnessTick)
            main.post(freshnessTick)
            Log.i(TAG, "widget host listening")
        } catch (error: RuntimeException) {
            Log.e(TAG, "widget host start failed", error)
            listening = false
            setUnavailable(VietMapWidgetUnavailableReason.HOST_ERROR)
        }
    }

    fun stop(owner: VietMapWidgetOwner) = onMain {
        if (!owners.remove(owner) || owners.isNotEmpty() || !listening) return@onMain
        main.removeCallbacks(freshnessTick)
        main.removeCallbacks(publishDebounced)
        try {
            host.stopListening()
        } catch (error: RuntimeException) {
            Log.e(TAG, "widget host stop failed", error)
        }
        listening = false
        clearRuntimeValues()
        publishSnapshot()
        Log.i(TAG, "widget host stopped")
    }

    fun addListener(listener: (VietMapWidgetSnapshot) -> Unit) {
        listeners += listener
        main.post { listener(published) }
    }

    fun removeListener(listener: (VietMapWidgetSnapshot) -> Unit) {
        listeners -= listener
    }

    fun snapshot(): VietMapWidgetSnapshot = published

    fun bindingStatuses(): List<VietMapWidgetBindingStatus> = VietMapWidgetSlot.entries.map { slot ->
        val id = prefs.widgetId(slot)
        val available = providerInfo(slot) != null
        val bound = id != null && manager.getAppWidgetInfo(id)?.provider == slot.component
        VietMapWidgetBindingStatus(slot, id, available, bound)
    }

    /** Allocates at most one ID per explicit user action. */
    fun beginBinding(slot: VietMapWidgetSlot): VietMapWidgetBindResult {
        val provider = providerInfo(slot)
            ?: return VietMapWidgetBindResult.Failed(
                slot,
                VietMapWidgetUnavailableReason.PROVIDER_MISSING,
                "VietMap provider is not installed",
            )
        bindingStatuses().first { it.slot == slot }.takeIf { it.bound }?.let {
            return VietMapWidgetBindResult.Bound(slot)
        }

        val appWidgetId = try {
            host.allocateAppWidgetId()
        } catch (error: RuntimeException) {
            Log.e(TAG, "widget ID allocation failed", error)
            return VietMapWidgetBindResult.Failed(
                slot,
                VietMapWidgetUnavailableReason.HOST_ERROR,
                "Cannot allocate widget ID",
            )
        }

        val bound = try {
            manager.bindAppWidgetIdIfAllowed(appWidgetId, provider.provider)
        } catch (error: SecurityException) {
            Log.w(TAG, "direct widget bind not allowed")
            false
        } catch (error: IllegalArgumentException) {
            deleteAllocatedId(appWidgetId)
            return VietMapWidgetBindResult.Failed(
                slot,
                VietMapWidgetUnavailableReason.HOST_ERROR,
                "Widget provider rejected the binding",
            )
        }

        if (bound && completeBinding(slot, appWidgetId, granted = true)) {
            return VietMapWidgetBindResult.Bound(slot)
        }
        if (bound) {
            return VietMapWidgetBindResult.Failed(
                slot,
                VietMapWidgetUnavailableReason.HOST_ERROR,
                "Android did not retain the widget binding",
            )
        }

        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider)
        }
        if (intent.resolveActivity(appContext.packageManager) == null) {
            deleteAllocatedId(appWidgetId)
            return VietMapWidgetBindResult.Failed(
                slot,
                VietMapWidgetUnavailableReason.BIND_UI_UNAVAILABLE,
                "This Android build has no widget binding confirmation screen",
            )
        }
        return VietMapWidgetBindResult.ConsentRequired(slot, appWidgetId, intent)
    }

    fun completeBinding(slot: VietMapWidgetSlot, appWidgetId: Int, granted: Boolean): Boolean {
        val actuallyBound = manager.getAppWidgetInfo(appWidgetId)?.provider == slot.component
        if (!granted || !actuallyBound) {
            deleteAllocatedId(appWidgetId)
            publishSnapshot()
            return false
        }
        prefs.widgetId(slot)?.takeIf { it != appWidgetId }?.let(::deleteAllocatedId)
        prefs.saveWidgetId(slot, appWidgetId, providerVersion())
        if (listening) restoreBoundViews()
        return true
    }

    fun unbindAll() = onMain {
        VietMapWidgetSlot.entries.forEach { slot ->
            prefs.widgetId(slot)?.let(::deleteAllocatedId)
        }
        prefs.clearAll()
        clearRuntimeValues()
        publishSnapshot()
        Log.i(TAG, "widget bindings removed")
    }

    private fun restoreBoundViews() {
        clearRuntimeValues()
        remoteResources = loadRemoteResources()
        VietMapWidgetSlot.entries.forEach { slot ->
            val id = prefs.widgetId(slot) ?: return@forEach
            val info = manager.getAppWidgetInfo(id)
            if (info?.provider != slot.component || providerInfo(slot) == null) {
                deleteAllocatedId(id)
                prefs.clearWidgetId(slot)
                return@forEach
            }
            slotsById[id] = slot
            try {
                views[slot] = host.createView(appContext, id, info)
            } catch (error: RuntimeException) {
                Log.e(TAG, "host view creation failed for ${slot.name}", error)
                unsupportedSlots += slot
            }
        }
        publishSnapshot()
    }

    private fun onHostViewUpdated(appWidgetId: Int, view: AppWidgetHostView) = onMain {
        val slot = slotsById[appWidgetId] ?: return@onMain
        val extracted = when (slot) {
            VietMapWidgetSlot.SPEED_LIMIT -> extractSpeed(view)
            VietMapWidgetSlot.ALERTS -> extractAlerts(view)
        }
        if (extracted == null) {
            unsupportedSlots += slot
        } else {
            unsupportedSlots -= slot
            val now = SystemClock.elapsedRealtime()
            when (slot) {
                VietMapWidgetSlot.SPEED_LIMIT -> {
                    speedRaw = extracted
                    speedUpdatedAt = now
                }
                VietMapWidgetSlot.ALERTS -> {
                    alertsRaw = extracted
                    alertsUpdatedAt = now
                }
            }
        }
        main.removeCallbacks(publishDebounced)
        main.postDelayed(publishDebounced, UPDATE_DEBOUNCE_MS)
    }

    private fun extractSpeed(root: AppWidgetHostView): VietMapWidgetRawValues? {
        val names = VietMapWidgetViewNames.speedRequired
        if (!VietMapWidgetTextParser.supportsSpeedShape(resolvedNames(names))) return null
        val current = text(root, VietMapWidgetViewNames.CURRENT_SPEED) ?: return null
        val limit = text(root, VietMapWidgetViewNames.SPEED_LIMIT) ?: return null
        return VietMapWidgetRawValues(
            currentSpeedText = current.text.toString().takeIf { effectivelyVisible(current, root) },
            speedLimitText = limit.text.toString().takeIf { effectivelyVisible(limit, root) },
        )
    }

    private fun extractAlerts(root: AppWidgetHostView): VietMapWidgetRawValues? {
        val names = VietMapWidgetViewNames.alertsRequired
        if (!VietMapWidgetTextParser.supportsAlertsShape(resolvedNames(names))) return null
        val required = names.associateWith { name -> view(root, name) ?: return null }
        val textNames = names - setOf(
            VietMapWidgetViewNames.FIRST_ALERT_IMAGE,
            VietMapWidgetViewNames.SECOND_ALERT_IMAGE,
        )
        if (textNames.any { required[it] !is TextView }) return null
        fun visibleText(name: String): String? {
            val text = required.getValue(name) as TextView
            return text.text.toString().takeIf { effectivelyVisible(text, root) }
        }
        val firstImage = required.getValue(VietMapWidgetViewNames.FIRST_ALERT_IMAGE) as? ImageView ?: return null
        val secondImage = required.getValue(VietMapWidgetViewNames.SECOND_ALERT_IMAGE) as? ImageView ?: return null
        val firstVisible = effectivelyVisible(firstImage, root)
        val secondVisible = effectivelyVisible(secondImage, root)
        return VietMapWidgetRawValues(
            firstAlertSpeedLimitText = visibleText(VietMapWidgetViewNames.FIRST_ALERT_LIMIT),
            firstAlertDistanceText = visibleText(VietMapWidgetViewNames.FIRST_ALERT_DISTANCE),
            firstAlertImageVisible = firstVisible,
            firstAlertImageHash = drawableHash(firstImage).takeIf { firstVisible },
            secondAlertSpeedLimitText = visibleText(VietMapWidgetViewNames.SECOND_ALERT_LIMIT),
            secondAlertDistanceText = visibleText(VietMapWidgetViewNames.SECOND_ALERT_DISTANCE),
            secondAlertImageVisible = secondVisible,
            secondAlertImageHash = drawableHash(secondImage).takeIf { secondVisible },
        )
    }

    private fun publishSnapshot() {
        val reason = unavailableReason()
        val updatedAt = if (speedUpdatedAt != null && alertsUpdatedAt != null) {
            minOf(speedUpdatedAt!!, alertsUpdatedAt!!)
        } else {
            null
        }
        val speed = speedRaw ?: VietMapWidgetRawValues()
        val alerts = alertsRaw ?: VietMapWidgetRawValues()
        val raw = speed.copy(
            firstAlertSpeedLimitText = alerts.firstAlertSpeedLimitText,
            firstAlertDistanceText = alerts.firstAlertDistanceText,
            firstAlertImageVisible = alerts.firstAlertImageVisible,
            firstAlertImageHash = alerts.firstAlertImageHash,
            secondAlertSpeedLimitText = alerts.secondAlertSpeedLimitText,
            secondAlertDistanceText = alerts.secondAlertDistanceText,
            secondAlertImageVisible = alerts.secondAlertImageVisible,
            secondAlertImageHash = alerts.secondAlertImageHash,
        )
        val next = parseSnapshot(raw, providerVersion(), updatedAt, SystemClock.elapsedRealtime(), reason)
        if (next == published) return
        published = next
        listeners.forEach { listener ->
            try {
                listener(next)
            } catch (error: RuntimeException) {
                Log.e(TAG, "widget snapshot listener failed", error)
            }
        }
    }

    private fun unavailableReason(): VietMapWidgetUnavailableReason? = when {
        VietMapWidgetSlot.entries.any { providerInfo(it) == null } -> VietMapWidgetUnavailableReason.PROVIDER_MISSING
        unsupportedSlots.isNotEmpty() -> VietMapWidgetUnavailableReason.UNSUPPORTED_SHAPE
        bindingStatuses().any { !it.bound } -> VietMapWidgetUnavailableReason.NOT_BOUND
        else -> null
    }

    private fun providerInfo(slot: VietMapWidgetSlot): AppWidgetProviderInfo? =
        manager.installedProviders.firstOrNull { it.provider == slot.component }

    private fun resolvedNames(names: Set<String>): Set<String> = names.filterTo(linkedSetOf()) { id(it) != 0 }

    private fun view(root: View, name: String): View? = id(name).takeIf { it != 0 }?.let(root::findViewById)

    private fun text(root: View, name: String): TextView? = view(root, name) as? TextView

    private fun id(name: String): Int = remoteResources?.getIdentifier(name, "id", VIETMAP_PACKAGE) ?: 0

    private fun loadRemoteResources(): Resources? = try {
        appContext.packageManager.getResourcesForApplication(VIETMAP_PACKAGE)
    } catch (error: PackageManager.NameNotFoundException) {
        null
    }

    private fun effectivelyVisible(view: View, root: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current.visibility != View.VISIBLE) return false
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

    private fun drawableHash(image: ImageView): String? {
        val drawable = image.drawable ?: return null
        return try {
            val width = drawable.intrinsicWidth.takeIf { it > 0 }?.coerceAtMost(MAX_HASH_EDGE) ?: 1
            val height = drawable.intrinsicHeight.takeIf { it > 0 }?.coerceAtMost(MAX_HASH_EDGE) ?: 1
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val copy = drawable.constantState?.newDrawable()?.mutate() ?: drawable.mutate()
            copy.setBounds(0, 0, width, height)
            copy.draw(Canvas(bitmap))
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            bitmap.recycle()
            val bytes = ByteBuffer.allocate(pixels.size * Int.SIZE_BYTES)
            pixels.forEach(bytes::putInt)
            MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
        } catch (error: RuntimeException) {
            Log.w(TAG, "alert image hash unavailable: ${error.javaClass.simpleName}")
            null
        }
    }

    private fun providerVersion(): String? = try {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            appContext.packageManager.getPackageInfo(VIETMAP_PACKAGE, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(VIETMAP_PACKAGE, 0)
        }
        info.versionName
    } catch (error: PackageManager.NameNotFoundException) {
        null
    }

    private fun deleteAllocatedId(appWidgetId: Int) {
        try {
            host.deleteAppWidgetId(appWidgetId)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "widget ID was already removed")
        } catch (error: RuntimeException) {
            Log.e(TAG, "widget ID removal failed", error)
        }
        slotsById.remove(appWidgetId)
        views.entries.removeAll { it.value.appWidgetId == appWidgetId }
    }

    private fun clearRuntimeValues() {
        views.clear()
        slotsById.clear()
        unsupportedSlots.clear()
        speedRaw = null
        alertsRaw = null
        speedUpdatedAt = null
        alertsUpdatedAt = null
    }

    private fun setUnavailable(reason: VietMapWidgetUnavailableReason) {
        published = unavailable(reason)
        listeners.forEach { listener ->
            try {
                listener(published)
            } catch (error: RuntimeException) {
                Log.e(TAG, "widget snapshot listener failed", error)
            }
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    companion object {
        private const val TAG = "VietMapWidget"
        private const val HOST_ID = 0x564D
        private const val UPDATE_DEBOUNCE_MS = 120L
        private const val FRESHNESS_TICK_MS = 1_000L
        private const val MAX_HASH_EDGE = 256

        @Volatile private var instance: VietMapWidgetBridge? = null

        fun get(context: Context): VietMapWidgetBridge = instance ?: synchronized(this) {
            instance ?: VietMapWidgetBridge(context).also { instance = it }
        }

        private fun unavailable(reason: VietMapWidgetUnavailableReason) = VietMapWidgetSnapshot(
            currentSpeedKph = null,
            speedLimitKph = null,
            alerts = emptyList(),
            providerVersion = null,
            updatedAtElapsedMs = null,
            freshness = VietMapWidgetFreshness.UNAVAILABLE,
            reason = reason,
        )
    }
}

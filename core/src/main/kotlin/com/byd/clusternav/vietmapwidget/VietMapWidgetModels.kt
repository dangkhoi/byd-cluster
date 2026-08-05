package com.byd.clusternav.vietmapwidget

/**
 * Widget slot identity for VietMap provider widgets.
 * Each slot represents one widget provider and has independent lifecycle.
 *
 * The [preferenceKey] is used for persistence. Component mapping to Android's
 * ComponentName is handled in the app module via the `component` extension property
 * in VietMapWidgetSlotExt.kt.
 */
enum class VietMapWidgetSlot(val preferenceKey: String, val displayName: String) {
    SPEED_LIMIT("widget_id_speed_limit", "Tốc độ"),
    ALERTS("widget_id_alerts", "Cảnh báo"),
}

/** Provider-neutral values exposed by the acquisition POC. */
data class VietMapWidgetSnapshot(
    val currentSpeedKph: Int?,
    val speedLimitKph: Int?,
    val alerts: List<VietMapRoadAlert>,
    val providerVersion: String?,
    val updatedAtElapsedMs: Long?,
    val freshness: VietMapWidgetFreshness,
    val reason: VietMapWidgetUnavailableReason?,
    /** Per-slot freshness: speed provider independent of alerts provider. */
    val speedFreshness: VietMapWidgetFreshness = freshness,
    val alertsFreshness: VietMapWidgetFreshness = freshness,
    val speedUpdatedAtElapsedMs: Long? = updatedAtElapsedMs,
    val alertsUpdatedAtElapsedMs: Long? = updatedAtElapsedMs,
)

/**
 * Typed per-provider snapshot: each provider (speed, alerts) maintains its own
 * freshness/reason/generation independently. Bad data in one provider never
 * invalidates the other.
 */
data class VietMapProviderSnapshot<T>(
    val slot: VietMapWidgetSlot,
    val values: T?,
    val updatedAtElapsedMs: Long?,
    val freshness: VietMapWidgetFreshness,
    val reason: VietMapWidgetUnavailableReason?,
    val generation: Long,
)

data class VietMapRoadAlert(
    val speedLimitKph: Int?,
    val distanceText: String?,
    val distanceMeters: Int?,
    val imageVisible: Boolean,
    val imageHash: String?,
)

data class VietMapParsedDistance(
    val text: String,
    val meters: Int?,
)

/** Raw text and image metadata read from the applied RemoteViews hierarchy. */
data class VietMapWidgetRawValues(
    val currentSpeedText: String? = null,
    val speedLimitText: String? = null,
    val firstAlertSpeedLimitText: String? = null,
    val firstAlertDistanceText: String? = null,
    val firstAlertImageVisible: Boolean = false,
    val firstAlertImageHash: String? = null,
    val secondAlertSpeedLimitText: String? = null,
    val secondAlertDistanceText: String? = null,
    val secondAlertImageVisible: Boolean = false,
    val secondAlertImageHash: String? = null,
)

enum class VietMapWidgetFreshness {
    FRESH,
    STALE,
    UNAVAILABLE,
}

enum class VietMapWidgetUnavailableReason {
    NOT_BOUND,
    PROVIDER_MISSING,
    UNSUPPORTED_SHAPE,
    NO_UPDATE,
    HOST_ERROR,
    BIND_UI_UNAVAILABLE,
    BIND_DENIED,
}

/** States for the retryable speed-sign clear state machine. */
enum class SpeedSignClearState {
    /** Speed limit is actively displayed. */
    ACTIVE,
    /** Clear has been issued, awaiting acknowledgment. */
    CLEARING,
    /** Clear acknowledged — terminal success. */
    CLEARED,
    /** Clear failed, retry scheduled with backoff. */
    RETRY_PENDING,
}

/** Trigger events that initiate a speed-sign clear. */
enum class SpeedSignClearTrigger {
    MASTER_OFF,
    STALE_THRESHOLD,
    PROVIDER_DISCONNECT,
    SERVICE_DESTROY,
    PROCESS_BOOTSTRAP,
}

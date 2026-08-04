package com.byd.clusternav.vietmapwidget

/** Provider-neutral values exposed by the acquisition POC. */
data class VietMapWidgetSnapshot(
    val currentSpeedKph: Int?,
    val speedLimitKph: Int?,
    val alerts: List<VietMapRoadAlert>,
    val providerVersion: String?,
    val updatedAtElapsedMs: Long?,
    val freshness: VietMapWidgetFreshness,
    val reason: VietMapWidgetUnavailableReason?,
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

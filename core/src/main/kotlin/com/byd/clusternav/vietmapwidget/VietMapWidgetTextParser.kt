package com.byd.clusternav.vietmapwidget

import kotlin.math.roundToInt

object VietMapWidgetViewNames {
    const val CURRENT_SPEED = "osw_current_speed_tv"
    const val SPEED_LIMIT = "speed_limit_widget_text_view"
    const val ALERT_CURRENT_SPEED = "current_speed_textview"
    const val FIRST_ALERT_LIMIT = "warning_speed_limit_widget_text_view"
    const val FIRST_ALERT_DISTANCE = "warning_speed_distance_text_view"
    const val FIRST_ALERT_IMAGE = "warning_alert_image"
    const val SECOND_ALERT_LIMIT = "second_speed_limit_widget_text_view"
    const val SECOND_ALERT_DISTANCE = "second_warning_speed_distance_text_view"
    const val SECOND_ALERT_IMAGE = "second_warning_alert_image"

    val speedRequired = setOf(CURRENT_SPEED, SPEED_LIMIT)
    val alertsRequired = setOf(
        ALERT_CURRENT_SPEED,
        SPEED_LIMIT,
        FIRST_ALERT_LIMIT,
        FIRST_ALERT_DISTANCE,
        FIRST_ALERT_IMAGE,
        SECOND_ALERT_LIMIT,
        SECOND_ALERT_DISTANCE,
        SECOND_ALERT_IMAGE,
    )
}

object VietMapWidgetTextParser {
    const val FRESH_FOR_MS = 5_000L
    const val UNAVAILABLE_AFTER_MS = 30_000L

    private val integer = Regex("^[0-9]{1,3}$")
    private val distance = Regex("^([0-9]+(?:[.,][0-9]+)?)\\s*(m|km)$", RegexOption.IGNORE_CASE)
    private val sentinels = setOf("", "--", "!", "-")

    fun parseCurrentSpeed(text: CharSequence?): Int? = parseInteger(text, 0..300)

    fun parseSpeedLimit(text: CharSequence?): Int? = parseInteger(text, 1..300)

    fun parseDistance(text: CharSequence?): VietMapParsedDistance? {
        val normalized = normalize(text)
        if (normalized.lowercase() in sentinels) return null
        val match = distance.matchEntire(normalized)
        val meters = match?.let {
            val value = it.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@let null
            val multiplier = if (it.groupValues[2].equals("km", ignoreCase = true)) 1_000.0 else 1.0
            (value * multiplier).takeIf { candidate -> candidate.isFinite() && candidate in 0.0..1_000_000.0 }
                ?.roundToInt()
        }
        return VietMapParsedDistance(normalized, meters)
    }

    fun supportsSpeedShape(availableNames: Set<String>): Boolean =
        availableNames.containsAll(VietMapWidgetViewNames.speedRequired)

    fun supportsAlertsShape(availableNames: Set<String>): Boolean =
        availableNames.containsAll(VietMapWidgetViewNames.alertsRequired)

    fun freshness(
        updatedAtElapsedMs: Long?,
        nowElapsedMs: Long,
        unavailableReason: VietMapWidgetUnavailableReason? = null,
    ): Pair<VietMapWidgetFreshness, VietMapWidgetUnavailableReason?> {
        if (unavailableReason != null) return VietMapWidgetFreshness.UNAVAILABLE to unavailableReason
        if (updatedAtElapsedMs == null || updatedAtElapsedMs < 0L) {
            return VietMapWidgetFreshness.UNAVAILABLE to VietMapWidgetUnavailableReason.NO_UPDATE
        }
        val age = (nowElapsedMs - updatedAtElapsedMs).coerceAtLeast(0L)
        return when {
            age <= FRESH_FOR_MS -> VietMapWidgetFreshness.FRESH to null
            age <= UNAVAILABLE_AFTER_MS -> VietMapWidgetFreshness.STALE to null
            else -> VietMapWidgetFreshness.UNAVAILABLE to VietMapWidgetUnavailableReason.NO_UPDATE
        }
    }

    fun parseSnapshot(
        raw: VietMapWidgetRawValues,
        providerVersion: String?,
        updatedAtElapsedMs: Long?,
        nowElapsedMs: Long,
        unavailableReason: VietMapWidgetUnavailableReason? = null,
    ): VietMapWidgetSnapshot {
        val (freshness, reason) = freshness(updatedAtElapsedMs, nowElapsedMs, unavailableReason)
        if (freshness != VietMapWidgetFreshness.FRESH) {
            return VietMapWidgetSnapshot(
                currentSpeedKph = null,
                speedLimitKph = null,
                alerts = emptyList(),
                providerVersion = providerVersion,
                updatedAtElapsedMs = updatedAtElapsedMs,
                freshness = freshness,
                reason = reason,
            )
        }

        val alerts = listOf(
            alert(
                raw.firstAlertSpeedLimitText,
                raw.firstAlertDistanceText,
                raw.firstAlertImageVisible,
                raw.firstAlertImageHash,
            ),
            alert(
                raw.secondAlertSpeedLimitText,
                raw.secondAlertDistanceText,
                raw.secondAlertImageVisible,
                raw.secondAlertImageHash,
            ),
        ).filterNotNull()

        return VietMapWidgetSnapshot(
            currentSpeedKph = parseCurrentSpeed(raw.currentSpeedText),
            speedLimitKph = parseSpeedLimit(raw.speedLimitText),
            alerts = alerts,
            providerVersion = providerVersion,
            updatedAtElapsedMs = updatedAtElapsedMs,
            freshness = freshness,
            reason = null,
        )
    }

    private fun alert(
        speedLimitText: String?,
        distanceText: String?,
        imageVisible: Boolean,
        imageHash: String?,
    ): VietMapRoadAlert? {
        val limit = parseSpeedLimit(speedLimitText)
        val parsedDistance = parseDistance(distanceText)
        val hash = imageHash?.takeIf { imageVisible }
        if (limit == null && parsedDistance == null && !imageVisible) return null
        return VietMapRoadAlert(
            speedLimitKph = limit,
            distanceText = parsedDistance?.text,
            distanceMeters = parsedDistance?.meters,
            imageVisible = imageVisible,
            imageHash = hash,
        )
    }

    private fun parseInteger(text: CharSequence?, allowed: IntRange): Int? {
        val normalized = normalize(text)
        if (normalized.lowercase() in sentinels || !integer.matches(normalized)) return null
        return normalized.toIntOrNull()?.takeIf { it in allowed }
    }

    private fun normalize(text: CharSequence?): String = text
        ?.toString()
        .orEmpty()
        .replace('\u00a0', ' ')
        .trim()
        .replace(Regex("\\s+"), " ")
}

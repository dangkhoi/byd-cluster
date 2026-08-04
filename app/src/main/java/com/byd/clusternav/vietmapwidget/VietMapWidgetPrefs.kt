package com.byd.clusternav.vietmapwidget

import android.content.Context

internal class VietMapWidgetPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun widgetId(slot: VietMapWidgetSlot): Int? =
        prefs.getInt(slot.preferenceKey, NO_WIDGET_ID).takeUnless { it == NO_WIDGET_ID }

    fun saveWidgetId(slot: VietMapWidgetSlot, appWidgetId: Int, providerVersion: String?) {
        prefs.edit()
            .putInt(slot.preferenceKey, appWidgetId)
            .putString(KEY_PROVIDER_VERSION, providerVersion)
            .commit()
    }

    fun clearWidgetId(slot: VietMapWidgetSlot) {
        prefs.edit().remove(slot.preferenceKey).commit()
    }

    fun clearAll() {
        prefs.edit()
            .remove(VietMapWidgetSlot.SPEED_LIMIT.preferenceKey)
            .remove(VietMapWidgetSlot.ALERTS.preferenceKey)
            .remove(KEY_PROVIDER_VERSION)
            .commit()
    }

    companion object {
        private const val FILE_NAME = "vietmap_widget_bridge"
        private const val KEY_PROVIDER_VERSION = "provider_version"
        private const val NO_WIDGET_ID = -1
    }
}

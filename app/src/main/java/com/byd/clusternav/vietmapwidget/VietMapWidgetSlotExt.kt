package com.byd.clusternav.vietmapwidget

import android.content.ComponentName

private const val VIETMAP_PACKAGE = "vn.vietmap.live"

/**
 * Maps each [VietMapWidgetSlot] to its Android [ComponentName].
 * Lives in the app module because core is pure JVM and cannot reference Android classes.
 */
val VietMapWidgetSlot.component: ComponentName
    get() = when (this) {
        VietMapWidgetSlot.SPEED_LIMIT ->
            ComponentName(VIETMAP_PACKAGE, "$VIETMAP_PACKAGE.homewidget.VMOnlySpeedLimitWidgetProvider")
        VietMapWidgetSlot.ALERTS ->
            ComponentName(VIETMAP_PACKAGE, "$VIETMAP_PACKAGE.homewidget.VMOnlyStickyAlertWidgetProvider")
    }

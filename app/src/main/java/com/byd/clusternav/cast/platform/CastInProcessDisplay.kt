package com.byd.clusternav.cast.platform

import com.byd.clusternav.modules.clustercast.v2.*

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics

/**
 * In-process cluster measurement.
 *
 * `dumpsys display` text is the primary truth, but one firmware wording change turns the whole
 * runtime read-only: no display identity means no plan, no cast, no Stop. `DisplayManager` reports
 * the same display without shell, without permissions and without parsing, so it is used as an
 * explicit fallback whenever the dump cannot be resolved. It never overrides a dump that did parse.
 */
internal object CastInProcessDisplay {

    private val NAME_HINTS = listOf("fission", "xdja")

    fun measure(context: Context): NamedClusterDisplay? = runCatching {
        val manager = context.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return null
        val candidates = manager.displays.orEmpty().filter { display ->
            display.displayId != 0 && NAME_HINTS.all { hint -> display.name.orEmpty().lowercase().contains(hint) }
        }
        val display = candidates.singleOrNull() ?: return null
        val size = Point().also { @Suppress("DEPRECATION") display.getRealSize(it) }
        if (size.x <= 0 || size.y <= 0) return null
        val metrics = DisplayMetrics().also { @Suppress("DEPRECATION") display.getMetrics(it) }
        val density = metrics.densityDpi.takeIf { it > 0 } ?: return null
        NamedClusterDisplay(display.displayId, display.name.orEmpty(), size.x, size.y, density)
    }.getOrNull()
}

package com.byd.clusternav.vietmapwidget

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

private const val VIETMAP_PACKAGE = "vn.vietmap.live"
private const val MAX_HASH_EDGE = 256
private const val TAG = "WidgetExtract"

/**
 * Data extraction from RemoteViews host views. Separated from VietMapWidgetBridge
 * to keep binding lifecycle code clean and ensure drawable hashing runs off main thread.
 */
internal class VietMapWidgetExtraction(context: Context) {
    private val appContext = context.applicationContext
    private val hashExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "widget-hash").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    @Volatile var remoteResources: Resources? = null
        private set

    fun reloadRemoteResources() {
        remoteResources = try {
            appContext.packageManager.getResourcesForApplication(VIETMAP_PACKAGE)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun releaseResources() {
        remoteResources = null
    }

    fun extractSpeed(root: AppWidgetHostView): VietMapWidgetRawValues? {
        val names = VietMapWidgetViewNames.speedRequired
        if (!VietMapWidgetTextParser.supportsSpeedShape(resolvedNames(names))) return null
        val current = text(root, VietMapWidgetViewNames.CURRENT_SPEED) ?: return null
        val limit = text(root, VietMapWidgetViewNames.SPEED_LIMIT) ?: return null
        return VietMapWidgetRawValues(
            currentSpeedText = current.text.toString().takeIf { effectivelyVisible(current, root) },
            speedLimitText = limit.text.toString().takeIf { effectivelyVisible(limit, root) },
        )
    }

    fun extractAlerts(root: AppWidgetHostView): VietMapWidgetRawValues? {
        val names = VietMapWidgetViewNames.alertsRequired
        if (!VietMapWidgetTextParser.supportsAlertsShape(resolvedNames(names))) return null
        val required = names.associateWith { name -> view(root, name) ?: return null }
        val textNames = names - setOf(
            VietMapWidgetViewNames.FIRST_ALERT_IMAGE,
            VietMapWidgetViewNames.SECOND_ALERT_IMAGE,
        )
        if (textNames.any { required[it] !is TextView }) return null
        fun visibleText(name: String): String? {
            val tv = required.getValue(name) as TextView
            return tv.text.toString().takeIf { effectivelyVisible(tv, root) }
        }
        val firstImage = required.getValue(VietMapWidgetViewNames.FIRST_ALERT_IMAGE) as? ImageView ?: return null
        val secondImage = required.getValue(VietMapWidgetViewNames.SECOND_ALERT_IMAGE) as? ImageView ?: return null
        val firstVisible = effectivelyVisible(firstImage, root)
        val secondVisible = effectivelyVisible(secondImage, root)
        return VietMapWidgetRawValues(
            firstAlertSpeedLimitText = visibleText(VietMapWidgetViewNames.FIRST_ALERT_LIMIT),
            firstAlertDistanceText = visibleText(VietMapWidgetViewNames.FIRST_ALERT_DISTANCE),
            firstAlertImageVisible = firstVisible,
            firstAlertImageHash = null, // hash computed asynchronously
            secondAlertSpeedLimitText = visibleText(VietMapWidgetViewNames.SECOND_ALERT_LIMIT),
            secondAlertDistanceText = visibleText(VietMapWidgetViewNames.SECOND_ALERT_DISTANCE),
            secondAlertImageVisible = secondVisible,
            secondAlertImageHash = null, // hash computed asynchronously
        )
    }

    /**
     * Submit drawable hashing work to background thread. Returns a future pair of (firstHash, secondHash).
     * Caller must capture the bitmap data on main thread (drawable → pixel array) then hash off-thread.
     */
    fun hashAlertsAsync(root: AppWidgetHostView): Future<Pair<String?, String?>> {
        val firstImage = view(root, VietMapWidgetViewNames.FIRST_ALERT_IMAGE) as? ImageView
        val secondImage = view(root, VietMapWidgetViewNames.SECOND_ALERT_IMAGE) as? ImageView
        // Capture pixel arrays on main thread (drawable access requires it), hash off-thread.
        val firstPixels = firstImage?.let { capturePixels(it) }
        val secondPixels = secondImage?.let { capturePixels(it) }
        return hashExecutor.submit<Pair<String?, String?>> {
            val h1 = firstPixels?.let { computeHash(it) }
            val h2 = secondPixels?.let { computeHash(it) }
            h1 to h2
        }
    }

    fun close() {
        hashExecutor.shutdownNow()
    }

    // --- Private helpers ---

    private fun capturePixels(image: ImageView): IntArray? {
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
            pixels
        } catch (error: RuntimeException) {
            Log.w(TAG, "pixel capture failed: ${error.javaClass.simpleName}")
            null
        }
    }

    private fun computeHash(pixels: IntArray): String {
        val bytes = ByteBuffer.allocate(pixels.size * Int.SIZE_BYTES)
        pixels.forEach(bytes::putInt)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array())
            .joinToString("") { "%02x".format(it) }
    }

    private fun resolvedNames(names: Set<String>): Set<String> =
        names.filterTo(linkedSetOf()) { id(it) != 0 }

    private fun view(root: View, name: String): View? =
        id(name).takeIf { it != 0 }?.let(root::findViewById)

    private fun text(root: View, name: String): TextView? = view(root, name) as? TextView

    private fun id(name: String): Int =
        remoteResources?.getIdentifier(name, "id", VIETMAP_PACKAGE) ?: 0

    private fun effectivelyVisible(view: View, root: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current.visibility != View.VISIBLE) return false
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }
}

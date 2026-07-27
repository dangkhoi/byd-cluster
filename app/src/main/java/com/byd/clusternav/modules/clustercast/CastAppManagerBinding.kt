package com.byd.clusternav.modules.clustercast

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.Button
import com.byd.clusternav.modules.clustercast.v2.AppIconState
import com.byd.clusternav.modules.clustercast.v2.CastAppCatalog
import com.byd.clusternav.modules.clustercast.v2.ClusterStyle
import com.byd.clusternav.modules.clustercast.v2.CastAppEntry
import com.byd.clusternav.modules.clustercast.v2.CastAppPresentation
import com.byd.clusternav.modules.clustercast.v2.CastAutomationSettings

/**
 * Application presentation and durable-write binding kept outside the Activity.
 *
 * Every durable write is dispatched to the caller's background lane, and Cast execution is delegated
 * to the existing manual-intent path. Building or binding a row dispatches zero Cast operations.
 */
class CastAppManagerBinding(
    private val context: Context,
    private val catalog: CastAppCatalog,
    private val automation: CastAutomationSettings,
    private val background: (() -> Unit) -> Unit,
    private val cast: (String) -> Unit,
) : CastAppManagerDialog.Actions {

    /** Reads durable config and the installed catalog. Must run off the main thread. */
    fun model(castExported: Boolean): CastAppManagerDialog.Model {
        migrateLegacyDefaultOnce()
        val config = automation.config()
        return CastAppManagerDialog.Model(
            entries = catalog.installed(config.defaultPackage),
            defaultPackage = config.defaultPackage,
            disposition = automation.disposition(),
            castExported = castExported,
            bubbleEnabled = catalog.bubbleEnabled(),
            overlayPermitted = Settings.canDrawOverlays(context),
            densityByPackage = catalog.installed(config.defaultPackage)
                .mapNotNull { entry -> catalog.clusterDensityDpi(entry.packageName)?.let { entry.packageName to it } }
                .toMap(),
            rectStylePackages = catalog.installed(config.defaultPackage)
                .filter { catalog.clusterStyle(it.packageName) == ClusterStyle.RECT }
                .map { it.packageName }.toSet(),
        )
    }

    /** Local preselection only: an explicit saved choice wins, and nothing is dispatched. */
    fun preselect(savedPackage: String?, entries: List<CastAppEntry>): String? =
        CastAppPresentation.preselect(
            savedPackage,
            automation.config().defaultPackage,
            CastAppPresentation.rows(entries.map { it.row() }, automation.config().defaultPackage),
        )

    private fun migrateLegacyDefaultOnce() {
        val legacy = catalog.legacyDefaultCandidate() ?: return
        runCatching { automation.migrateLegacyDefault(legacy) }
            .onSuccess { catalog.clearLegacyDefaultCandidate() }
    }

    override fun setFavorite(packageName: String, enabled: Boolean) =
        background { catalog.setFavorite(packageName, enabled) }

    override fun setUserProtection(packageName: String, enabled: Boolean) =
        background { runCatching { catalog.setProtected(packageName, enabled) } }

    override fun setClusterDensityDpi(packageName: String, densityDpi: Int?) =
        background { runCatching { catalog.setClusterDensityDpi(packageName, densityDpi) } }

    override fun setClusterStyle(packageName: String, rect: Boolean) = background {
        runCatching {
            catalog.setClusterStyle(packageName, if (rect) ClusterStyle.RECT else ClusterStyle.CURVED)
        }
    }

    override fun setDefault(packageName: String?) = background { automation.setDefault(packageName) }

    override fun enableAutoCast() = background { automation.accept() }

    override fun disableAutoCast() = background {
        automation.revoke()
        automation.supersedeIfEffectFree(
            com.byd.clusternav.modules.clustercast.v2.AutomationReason.CONSENT_REVOKED,
        )
    }

    override fun cast(packageName: String) = cast.invoke(packageName)

    /**
     * Presentation-only opt-in. Enabling starts nothing without overlay permission, and disabling
     * immediately stops the overlay host without sending any Cast Stop request.
     */
    override fun setBubbleEnabled(enabled: Boolean) = background {
        CastBubbleControl.apply(context, enabled)
    }

    override fun openOverlaySettings() = CastBubbleControl.requestOverlay(context)
}

/** Shared icon+label binding for Activity tiles and quick switch. */
object CastAppTiles {
    /**
     * Marks the tile of the app currently chosen to cast.
     *
     * Selection used to exist only as the sentence "Đã chọn: X" in the status card, so the grid gave no
     * feedback at all when a tile was tapped. Tiles carry their package as their tag, which lets the
     * mark move without rebuilding the grid or re-querying the package manager.
     */
    fun markSelected(grid: android.view.ViewGroup, selectedPackage: String?) {
        for (index in 0 until grid.childCount) {
            val child = grid.getChildAt(index) as? Button ?: continue
            val chosen = child.tag != null && child.tag == selectedPackage
            child.isSelected = chosen
            val end = if (chosen) {
                child.resources.getDrawable(com.byd.clusternav.R.drawable.ic_check_selected, null).apply {
                    val side = (22 * child.resources.displayMetrics.density + .5f).toInt()
                    setBounds(0, 0, side, side)
                }
            } else {
                null
            }
            child.setCompoundDrawablesRelative(child.compoundDrawablesRelative[0], null, end, null)
            child.contentDescription = buildString {
                append(child.text)
                if (chosen) append(". Đang chọn để chiếu")
            }
        }
    }

    fun bind(button: Button, entry: CastAppEntry, onClick: () -> Unit) {
        button.tag = entry.packageName
        button.text = buildString {
            if (entry.favorite) append("★ ")
            append(entry.label)
            if (entry.isDefault) append(" · mặc định")
        }
        val icon = entry.icon
        if (icon != null && entry.iconState == AppIconState.LOADED) {
            val size = (24 * button.resources.displayMetrics.density + .5f).toInt()
            icon.setBounds(0, 0, size, size)
            button.setCompoundDrawablesRelative(icon, null, null, null)
        } else {
            button.setCompoundDrawablesRelative(null, null, null, null)
        }
        button.compoundDrawablePadding = (8 * button.resources.displayMetrics.density + .5f).toInt()
        button.contentDescription = buildString {
            append(entry.label)
            if (entry.isDefault) append(". Ứng dụng mặc định")
            if (entry.favorite) append(". Yêu thích")
        }
        // Card look from the project's own tokens: the tile grid used to be indistinguishable from the
        // action buttons above it, so the screen read as one undifferentiated wall of grey.
        button.setBackgroundResource(com.byd.clusternav.R.drawable.segment_bg)
        button.isSelected = entry.isDefault
        button.setTextColor(
            button.resources.getColor(
                if (entry.isDefault) com.byd.clusternav.R.color.brand else com.byd.clusternav.R.color.text_primary,
                null,
            ),
        )
        button.textSize = 14f
        button.isAllCaps = false
        button.gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
        button.isFocusable = true
        button.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        button.setOnClickListener { onClick() }
    }
}

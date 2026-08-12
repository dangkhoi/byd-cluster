package com.byd.clusternav.modules.clustercast

import android.app.Activity
import android.widget.Button
import com.byd.clusternav.Prefs
import com.byd.clusternav.R

/**
 * Two-button segmented selector for the nav-on-cluster display mode (Nav + HUD card).
 *
 * [ Nhỏ / ở trên ] ([R.id.btn_nav_mode_small] → [Prefs.NAV_MODE_SMALL_TOP]) and
 * [ Giữa + ETA ]  ([R.id.btn_nav_mode_center] → [Prefs.NAV_MODE_CENTER_ETA], default). The chosen
 * button is highlighted with [R.drawable.btn_primary]; the other uses [R.drawable.btn_outline].
 *
 * A separate file (not [MainActivity]/[MainActivityCastController] inline wiring) to keep those under
 * their LOC contracts. Belongs to the NAV track: it only persists the pref that
 * [ClusterNavLaneWidget.onNavActive] reads to decide whether to assert the op-39 centre overlay. No
 * cast state, no shell — pure prefs + view highlight, so it is cheap to call from onCreate.
 */
internal class NavClusterModeButtons(private val activity: Activity) {

    fun bind() {
        val small = activity.findViewById<Button>(R.id.btn_nav_mode_small) ?: return
        val center = activity.findViewById<Button>(R.id.btn_nav_mode_center) ?: return

        small.setOnClickListener {
            Prefs.setNavClusterMode(activity, Prefs.NAV_MODE_SMALL_TOP)
            render(small, center)
        }
        center.setOnClickListener {
            Prefs.setNavClusterMode(activity, Prefs.NAV_MODE_CENTER_ETA)
            render(small, center)
        }
        render(small, center)
    }

    /** Highlight the selected button (btn_primary + white text) and outline the other. */
    private fun render(small: Button, center: Button) {
        val centerSelected = Prefs.navClusterMode(activity) == Prefs.NAV_MODE_CENTER_ETA
        style(center, centerSelected)
        style(small, !centerSelected)
    }

    private fun style(button: Button, selected: Boolean) {
        button.setBackgroundResource(if (selected) R.drawable.btn_primary else R.drawable.btn_outline)
        button.setTextColor(
            activity.getColor(if (selected) android.R.color.white else R.color.btn_text),
        )
    }
}

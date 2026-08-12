package com.byd.clusternav.modules.clustercast

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.CheckBox
import android.widget.Spinner
import com.byd.clusternav.Lang
import com.byd.clusternav.R
import com.byd.clusternav.modules.clustercast.simplified.AppMover
import com.byd.clusternav.modules.clustercast.simplified.CastProfile
import com.byd.clusternav.modules.clustercast.simplified.SimpleCastCoordinator
import com.byd.clusternav.modules.clustercast.simplified.SimpleCastPrefs

/**
 * Encapsulates the autostart checkbox/spinner SETUP UI only.
 *
 * Extracted from MainActivityCastController to keep each file ≤ 400 LOC.
 * Owns: the autostart full/split checkboxes + app spinners (which persist the user's choices to
 * prefs) and the split-ratio spinner.
 *
 * It does NOT dispatch autostart. The sole autostart driver is [FloatingBubbleService], which runs
 * even when this Activity is closed; having the Activity ALSO dispatch caused a two-driver
 * SLOT_OCCUPIED race that wiped CastingSplit (T1 / R1 — docs/specs/cast-freeform-resize-split.html).
 */
internal class CastAutostart(
    private val activity: Activity,
    private val coordinator: SimpleCastCoordinator,
) {
    private val castPrefs: SimpleCastPrefs = coordinator.prefs

    fun setup() {
        val autoStartCheckbox = activity.findViewById<CheckBox>(R.id.cb_autostart)
        autoStartCheckbox.isEnabled = true
        autoStartCheckbox.isChecked = castPrefs.autoStartEnabled()
        autoStartCheckbox.setOnCheckedChangeListener { _, isChecked ->
            castPrefs.setAutoStartEnabled(isChecked)
            if (isChecked) {
                castPrefs.setAutoStartSplitEnabled(false)
                activity.findViewById<CheckBox>(R.id.cb_autostart_split).isChecked = false
            }
        }

        val spinnerAutoApp = activity.findViewById<Spinner>(R.id.spinner_autostart_app)
        spinnerAutoApp.isEnabled = true
        populateAutoStartSpinner(spinnerAutoApp, "full")

        // Split autostart
        val autoStartSplitCheckbox = activity.findViewById<CheckBox>(R.id.cb_autostart_split)
        autoStartSplitCheckbox.isChecked = castPrefs.autoStartSplitEnabled()
        autoStartSplitCheckbox.setOnCheckedChangeListener { _, isChecked ->
            castPrefs.setAutoStartSplitEnabled(isChecked)
            if (isChecked) {
                castPrefs.setAutoStartEnabled(false)
                autoStartCheckbox.isChecked = false
            }
        }

        val spinnerAutoLeft = activity.findViewById<Spinner>(R.id.spinner_autostart_left)
        val spinnerAutoRight = activity.findViewById<Spinner>(R.id.spinner_autostart_right)
        populateAutoStartSpinner(spinnerAutoLeft, "left")
        populateAutoStartSpinner(spinnerAutoRight, "right")

        // Split ratio spinner
        val spinnerRatio = activity.findViewById<Spinner>(R.id.spinner_split_ratio)
        spinnerRatio.isEnabled = true
        populateSplitRatioSpinner(spinnerRatio)

        // NOTE: autostart is intentionally NOT dispatched here. FloatingBubbleService is the sole
        // driver (R1) — a second Activity-side dispatcher caused the SLOT_OCCUPIED race (T1).
    }

    /**
     * No listeners or executors are held anymore — autostart dispatch moved entirely to
     * [FloatingBubbleService] (R1). Kept for lifecycle symmetry with [MainActivityCastController]
     * (which calls it on destroy) and as a safe extension point if this class ever registers
     * observers again.
     */
    fun destroy() {
        // Intentionally empty: this controller only wires the setup UI now.
    }

    private fun populateAutoStartSpinner(spinner: Spinner, slot: String) {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = activity.packageManager.queryIntentActivities(launchIntent, 0)
        val excluded = setOf(activity.packageName, "com.android.launcher", "com.android.launcher3")
        // R2 (#3): a launcher/home must never be an autostart target either — same guard the bubble
        // dispatcher uses for tap-cast. AppMover.isLauncher catches Dudu + any *launcher* id beyond
        // the two hard-coded above.
        val apps = resolveInfos
            .filter { it.activityInfo.packageName !in excluded && !AppMover.isLauncher(it.activityInfo.packageName) }
            .map { it.loadLabel(activity.packageManager).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }

        val labels = mutableListOf(Lang.t("— Chọn app —", "— Select app —"))
        labels.addAll(apps.map { it.first })

        val adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        val savedPkg = when (slot) {
            "left" -> castPrefs.autoStartLeftPackage()
            "right" -> castPrefs.autoStartRightPackage()
            else -> castPrefs.autoStartPackage()
        }
        if (savedPkg != null) {
            val idx = apps.indexOfFirst { it.second == savedPkg }
            if (idx >= 0) spinner.setSelection(idx + 1)
        }

        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val pkg = if (position == 0) null else apps[position - 1].second
                when (slot) {
                    "left" -> castPrefs.setAutoStartLeftPackage(pkg)
                    "right" -> castPrefs.setAutoStartRightPackage(pkg)
                    else -> castPrefs.setAutoStartPackage(pkg)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun populateSplitRatioSpinner(spinner: Spinner) {
        // R3 (#4): all 9 split ratios, data-driven from CastProfile.SPLIT_PERCENTS ({10,20,…,90}).
        // Value = leftPercent; label = "left/right" (10/90 … 90/10). One spinner covers both
        // directions because leftPercent alone determines the whole split.
        val percentValues = CastProfile.SPLIT_PERCENTS
        val options = percentValues.map { "$it/${100 - it}" }

        val adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        // R3 backfill: any previously-stored ratio outside SPLIT_PERCENTS (e.g. legacy 55, 0) falls
        // back to the default (50) and is re-persisted so the coordinator reads a valid ratio.
        val savedPct = castPrefs.splitRatioLeftPercent()
        val effectivePct = CastProfile.normalizePercent(savedPct)
        if (effectivePct != savedPct) castPrefs.setSplitRatioLeftPercent(effectivePct)
        spinner.setSelection(percentValues.indexOf(effectivePct))

        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                castPrefs.setSplitRatioLeftPercent(percentValues[position])
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }
}

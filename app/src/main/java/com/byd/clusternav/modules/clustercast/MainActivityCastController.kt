package com.byd.clusternav.modules.clustercast

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.byd.clusternav.Lang
import com.byd.clusternav.R
import com.byd.clusternav.cast.platform.CastAppCatalog
import com.byd.clusternav.modules.clustercast.simplified.AppMover
import com.byd.clusternav.modules.clustercast.simplified.ClusterSlotSide
import com.byd.clusternav.modules.clustercast.simplified.SimpleCastIntent
import com.byd.clusternav.modules.clustercast.simplified.SimpleCastPrefs
import com.byd.clusternav.modules.clustercast.simplified.SimpleCastRuntime
import com.byd.clusternav.modules.clustercast.simplified.SimpleCastState

/**
 * Toàn bộ máy móc Cluster Cast của Home — simplified architecture only.
 *
 * V2 runtime (CastAndroidRuntime, CastFacade, CastCoordinator) removed 2026-08-03.
 * Only SimpleCastRuntime controls projection lifecycle now.
 */
internal class MainActivityCastController(private val activity: Activity) {
    private lateinit var catalog: CastAppCatalog
    @Volatile private var destroyed = false

    private lateinit var status: TextView
    private lateinit var statusDot: View
    private lateinit var geometryContainer: FrameLayout
    private lateinit var stopButton: Button
    private lateinit var diagnosticsButton: Button

    fun onCreate() {
        catalog = CastAppCatalog(activity.applicationContext)

        // Simplified architecture owns projection lifecycle.
        SimpleCastRuntime.coordinator(activity.applicationContext).openProjection()

        status = activity.findViewById(R.id.txt_cast_status)
        statusDot = activity.findViewById(R.id.dot_cast)
        geometryContainer = activity.findViewById(R.id.cast_geometry_container)

        stopButton = activity.findViewById<Button>(R.id.cast_stop).apply { setOnClickListener { executeStop() } }
        diagnosticsButton = activity.findViewById<Button>(R.id.cast_diagnostics).apply {
            setOnClickListener { activity.startActivity(Intent(activity, DiagActivity::class.java)) }
        }

        // Recovery toggle — still wired for UI visibility but buttons disabled
        val recoveryToggle = activity.findViewById<TextView>(R.id.cast_recovery_toggle)
        val recoveryActions = activity.findViewById<View>(R.id.cast_recovery_actions)
        recoveryToggle.setOnClickListener {
            recoveryActions.visibility = if (recoveryActions.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        // Clear cluster button — stop casting and close projection to return cluster to gauges
        activity.findViewById<Button>(R.id.cast_clear_cluster).apply {
            isEnabled = true
            setOnClickListener {
                val coord = SimpleCastRuntime.coordinator(activity.applicationContext)
                coord.dispatch(SimpleCastIntent.Stop())
                coord.closeProjection()
                Toast.makeText(activity, Lang.t("Đã trả cụm về đồng hồ · đang mở lại…", "Cluster reset · reopening…"), Toast.LENGTH_SHORT).show()
                // Re-open after brief pause so user sees gauges then ready state
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ coord.openProjection() }, 2000)
            }
        }

        // Cast zone buttons: pick app or stop if already casting
        activity.findViewById<Button>(R.id.cast_zone_full).apply {
            contentDescription = Lang.t("Chiếu full cụm", "Cast full cluster")
        }.setOnClickListener {
            val state = SimpleCastRuntime.coordinator(activity.applicationContext).state
            if (state is SimpleCastState.CastingFull || state is SimpleCastState.CastingSplit) {
                SimpleCastRuntime.coordinator(activity.applicationContext).dispatch(SimpleCastIntent.Stop())
            } else {
                showAppPicker(null)
            }
        }
        activity.findViewById<Button>(R.id.cast_zone_left).apply {
            contentDescription = Lang.t("Chiếu nửa trái", "Cast left half")
        }.setOnClickListener {
            val state = SimpleCastRuntime.coordinator(activity.applicationContext).state
            if (state is SimpleCastState.CastingSplit && state.left != null) {
                SimpleCastRuntime.coordinator(activity.applicationContext).dispatch(SimpleCastIntent.Stop(ClusterSlotSide.LEFT))
            } else {
                showAppPicker(ClusterSlotSide.LEFT)
            }
        }
        activity.findViewById<Button>(R.id.cast_zone_right).apply {
            contentDescription = Lang.t("Chiếu nửa phải", "Cast right half")
        }.setOnClickListener {
            val state = SimpleCastRuntime.coordinator(activity.applicationContext).state
            if (state is SimpleCastState.CastingSplit && state.right != null) {
                SimpleCastRuntime.coordinator(activity.applicationContext).dispatch(SimpleCastIntent.Stop(ClusterSlotSide.RIGHT))
            } else {
                showAppPicker(ClusterSlotSide.RIGHT)
            }
        }

        // Auto-start checkbox and spinner
        val coordinator = SimpleCastRuntime.coordinator(activity.applicationContext)
        val castPrefs = coordinator.prefs

        val autoStartCheckbox = activity.findViewById<CheckBox>(R.id.cb_autostart)
        autoStartCheckbox.isEnabled = true
        autoStartCheckbox.isChecked = castPrefs.autoStartEnabled()
        autoStartCheckbox.setOnCheckedChangeListener { _, isChecked ->
            castPrefs.setAutoStartEnabled(isChecked)
        }

        val spinnerAutoApp = activity.findViewById<Spinner>(R.id.spinner_autostart_app)
        spinnerAutoApp.isEnabled = true
        populateAutoStartSpinner(spinnerAutoApp, castPrefs)

        // Split ratio spinner
        val spinnerRatio = activity.findViewById<Spinner>(R.id.spinner_split_ratio)
        spinnerRatio.isEnabled = true
        populateSplitRatioSpinner(spinnerRatio, castPrefs)

        // Autostart logic: if enabled and package set, wait for Idle then dispatch
        if (castPrefs.autoStartEnabled()) {
            val autoStartPkg = castPrefs.autoStartPackage()
            if (!autoStartPkg.isNullOrBlank()) {
                coordinator.addStateListener(object : (SimpleCastState) -> Unit {
                    override fun invoke(state: SimpleCastState) {
                        if (state is SimpleCastState.Idle) {
                            coordinator.removeStateListener(this)
                            coordinator.dispatch(
                                SimpleCastIntent.CastFull(autoStartPkg, AppMover.classifyApp(autoStartPkg))
                            )
                        }
                    }
                })
            }
        }

        // Vietmap bubble experiment
        // VietMap bubble experiment removed — function not implemented

        // Initial render
        postUi { refresh() }
    }

    fun onResume() {
        refresh()
    }

    fun tick() { refresh() }

    fun onDestroy() {
        destroyed = true
        SimpleCastRuntime.coordinator(activity.applicationContext).closeProjection()
    }

    private fun dispatchCast(pkg: String) {
        SimpleCastRuntime.coordinator(activity.applicationContext)
            .dispatch(SimpleCastIntent.CastFull(pkg, AppMover.classifyApp(pkg)))
    }

    private fun executeStop() {
        SimpleCastRuntime.coordinator(activity.applicationContext).dispatch(SimpleCastIntent.Stop())
        status.text = Lang.t("Đang trả app về…", "Returning app…")
    }

    private fun showAppPicker(slot: ClusterSlotSide?) {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = activity.packageManager.queryIntentActivities(launchIntent, 0)
        val excluded = setOf(activity.packageName, "com.android.launcher", "com.android.launcher3")
        val apps = resolveInfos
            .filter { it.activityInfo.packageName !in excluded }
            .map { (it.loadLabel(activity.packageManager).toString()) to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }

        val labels = apps.map { it.first }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle(Lang.t("Chọn app chiếu", "Choose app to cast"))
            .setItems(labels) { dialog, which ->
                val pkg = apps[which].second
                val coordinator = SimpleCastRuntime.coordinator(activity.applicationContext)
                if (slot == null) {
                    coordinator.dispatch(SimpleCastIntent.CastFull(pkg, AppMover.classifyApp(pkg)))
                } else {
                    coordinator.dispatch(SimpleCastIntent.CastSlot(pkg, slot))
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun refresh() {
        if (destroyed || activity.isFinishing || activity.isDestroyed) return
        val simplifiedState = SimpleCastRuntime.coordinator(activity.applicationContext).state
        status.text = when (simplifiedState) {
            is SimpleCastState.Off -> Lang.t("Tắt", "Off")
            is SimpleCastState.Opening -> Lang.t("Đang mở cụm…", "Opening cluster…")
            is SimpleCastState.Idle -> Lang.t("Sẵn sàng · bấm nút nổi để chiếu", "Ready · tap bubble to cast")
            is SimpleCastState.CastingFull -> Lang.t(
                "Đang chiếu: ${simplifiedState.targetPkg.substringAfterLast('.')}",
                "Casting: ${simplifiedState.targetPkg.substringAfterLast('.')}"
            )
            is SimpleCastState.CastingSplit -> {
                val l = simplifiedState.left?.pkg?.substringAfterLast('.') ?: "—"
                val r = simplifiedState.right?.pkg?.substringAfterLast('.') ?: "—"
                Lang.t("Chia đôi: $l | $r", "Split: $l | $r")
            }
            is SimpleCastState.Stopping -> Lang.t("Đang trả app về…", "Returning app…")
            is SimpleCastState.Closing -> Lang.t("Đang đóng cụm…", "Closing cluster…")
            is SimpleCastState.Error -> Lang.t(
                "Lỗi: ${simplifiedState.message}",
                "Error: ${simplifiedState.message}"
            )
            else -> simplifiedState.toString()
        }
        statusDot.tint(
            when (simplifiedState) {
                is SimpleCastState.CastingFull, is SimpleCastState.CastingSplit -> R.color.ok_green
                is SimpleCastState.Error -> R.color.err_red
                else -> R.color.warn_amber
            },
        )
        stopButton.isEnabled = simplifiedState is SimpleCastState.CastingFull || simplifiedState is SimpleCastState.CastingSplit
        diagnosticsButton.isEnabled = true

        // Zone button state
        val btnFull = activity.findViewById<Button>(R.id.cast_zone_full)
        val btnLeft = activity.findViewById<Button>(R.id.cast_zone_left)
        val btnRight = activity.findViewById<Button>(R.id.cast_zone_right)
        when (simplifiedState) {
            is SimpleCastState.CastingFull -> {
                btnFull.text = Lang.t("▣ Đang chiếu: ${simplifiedState.targetPkg.substringAfterLast('.')}", "▣ Casting: ${simplifiedState.targetPkg.substringAfterLast('.')}")
                btnFull.setBackgroundResource(R.drawable.btn_warning_outline)
                btnLeft.isEnabled = false
                btnRight.isEnabled = false
            }
            is SimpleCastState.CastingSplit -> {
                btnFull.text = Lang.t("▣ Chiếu full cụm", "▣ Cast full")
                btnFull.setBackgroundResource(R.drawable.btn_primary)
                btnFull.isEnabled = false
                btnLeft.text = if (simplifiedState.left != null)
                    Lang.t("◧ ${simplifiedState.left!!.pkg.substringAfterLast('.')}", "◧ ${simplifiedState.left!!.pkg.substringAfterLast('.')}")
                else Lang.t("◧ Trái", "◧ Left")
                btnRight.text = if (simplifiedState.right != null)
                    Lang.t("◨ ${simplifiedState.right!!.pkg.substringAfterLast('.')}", "◨ ${simplifiedState.right!!.pkg.substringAfterLast('.')}")
                else Lang.t("◨ Phải", "◨ Right")
                btnLeft.isEnabled = true
                btnRight.isEnabled = true
            }
            is SimpleCastState.Idle -> {
                btnFull.text = Lang.t("▣ Chiếu full cụm", "▣ Cast full")
                btnFull.setBackgroundResource(R.drawable.btn_primary)
                btnFull.isEnabled = true
                btnLeft.text = Lang.t("◧ Trái", "◧ Left")
                btnLeft.isEnabled = true
                btnLeft.setBackgroundResource(R.drawable.btn_outline)
                btnRight.text = Lang.t("◨ Phải", "◨ Right")
                btnRight.isEnabled = true
                btnRight.setBackgroundResource(R.drawable.btn_outline)
            }
            else -> {
                btnFull.text = Lang.t("▣ Chiếu full cụm", "▣ Cast full")
                btnFull.isEnabled = false
                btnLeft.text = Lang.t("◧ Trái", "◧ Left")
                btnLeft.isEnabled = false
                btnRight.text = Lang.t("◨ Phải", "◨ Right")
                btnRight.isEnabled = false
            }
        }

        // Hide geometry when casting a protected app; show resize controls for NORMAL apps.
        // During transient states (Opening/Stopping/Closing), keep current visibility to avoid
        // panel flicker on rapid state transitions (CastingFull → Stopping → Idle → CastingFull).
        when {
            simplifiedState is SimpleCastState.CastingFull && simplifiedState.appType.isProtected -> {
                geometryContainer.visibility = View.GONE
            }
            simplifiedState is SimpleCastState.CastingFull && simplifiedState.appType.isResizable -> {
                geometryContainer.visibility = View.VISIBLE
                setupResizeControls(simplifiedState)
            }
            simplifiedState is SimpleCastState.CastingSplit -> {
                geometryContainer.visibility = View.VISIBLE
                setupSplitDpiControls(simplifiedState)
            }
            simplifiedState is SimpleCastState.Idle || simplifiedState is SimpleCastState.Off || simplifiedState is SimpleCastState.Error -> {
                geometryContainer.visibility = View.GONE
            }
            simplifiedState is SimpleCastState.Opening || simplifiedState is SimpleCastState.Stopping || simplifiedState is SimpleCastState.Closing -> {
                // Transient states: keep current visibility — don't change
            }
            else -> {
                geometryContainer.visibility = View.GONE
            }
        }
    }

    private var densityIndex = 0
    private val densityCycle = intArrayOf(320, 240, 160)

    private fun setupResizeControls(state: SimpleCastState.CastingFull) {
        // Only rebuild if not already set up (avoid flicker on each refresh)
        if (geometryContainer.tag == "resize_rect_${state.targetPkg}") return
        geometryContainer.tag = "resize_rect_${state.targetPkg}"
        geometryContainer.removeAllViews()

        val ctx = activity
        val coordinator = SimpleCastRuntime.coordinator(activity.applicationContext)
        val dp = ctx.resources.displayMetrics.density

        val outerLayout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
        }

        // Rectangle editor — height ~140dp, aspect ratio preserved by view scaling
        val resizeView = CastResizeView(ctx, 1920, 720) { l, t, r, b ->
            coordinator.resizeActiveTarget(l, t, r, b)
        }
        val rectHeight = (140 * dp).toInt()
        resizeView.layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            rectHeight,
        )
        // Default bounds
        resizeView.setBounds(0, 90, 1920, 630)

        outerLayout.addView(resizeView)

        // Button row below
        val btnRow = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(0, (8 * dp).toInt(), 0, 0)
        }

        val btnDpi = Button(ctx).apply {
            text = "DPI: ${densityCycle[densityIndex]}"
            textSize = 13f
            setOnClickListener {
                densityIndex = (densityIndex + 1) % densityCycle.size
                text = "DPI: ${densityCycle[densityIndex]}"
                coordinator.setDensity(densityCycle[densityIndex])
            }
        }

        val btnReset = Button(ctx).apply {
            text = Lang.t("\u0110\u1eb7t l\u1ea1i", "Reset")
            textSize = 13f
            setOnClickListener {
                resizeView.setBounds(0, 90, 1920, 630)
                coordinator.resizeActiveTarget(0, 90, 1920, 630)
            }
        }

        btnRow.addView(btnDpi)
        btnRow.addView(btnReset)
        outerLayout.addView(btnRow)

        geometryContainer.addView(outerLayout)
    }

    private fun setupSplitDpiControls(state: SimpleCastState.CastingSplit) {
        val tag = "split_dpi"
        if (geometryContainer.tag == tag) return
        geometryContainer.removeAllViews()
        geometryContainer.tag = tag

        val coordinator = SimpleCastRuntime.coordinator(activity.applicationContext)
        val castPrefs = coordinator.prefs

        val layout = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }

        val leftState = state.left
        val rightState = state.right

        if (leftState != null) {
            val savedDpi = castPrefs.displayConfigFor(leftState.pkg)?.density ?: "reset"
            val btnLeft = Button(activity).apply {
                text = Lang.t("◧ DPI: $savedDpi", "◧ DPI: $savedDpi")
                textSize = 12f
                setOnClickListener {
                    val current = castPrefs.displayConfigFor(leftState.pkg)?.density?.toIntOrNull() ?: 320
                    val next = when (current) { 320 -> 240; 240 -> 160; else -> 320 }
                    coordinator.setDensityForPkg(next, leftState.pkg)
                    text = Lang.t("◧ DPI: $next", "◧ DPI: $next")
                }
            }
            layout.addView(btnLeft, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        if (rightState != null) {
            val savedDpi = castPrefs.displayConfigFor(rightState.pkg)?.density ?: "reset"
            val btnRight = Button(activity).apply {
                text = Lang.t("◨ DPI: $savedDpi", "◨ DPI: $savedDpi")
                textSize = 12f
                setOnClickListener {
                    val current = castPrefs.displayConfigFor(rightState.pkg)?.density?.toIntOrNull() ?: 320
                    val next = when (current) { 320 -> 240; 240 -> 160; else -> 320 }
                    coordinator.setDensityForPkg(next, rightState.pkg)
                    text = Lang.t("◨ DPI: $next", "◨ DPI: $next")
                }
            }
            layout.addView(btnRight, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        geometryContainer.addView(layout)
    }

    private fun populateAutoStartSpinner(spinner: Spinner, castPrefs: SimpleCastPrefs) {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = activity.packageManager.queryIntentActivities(launchIntent, 0)
        val excluded = setOf(activity.packageName, "com.android.launcher", "com.android.launcher3")
        val apps = resolveInfos
            .filter { it.activityInfo.packageName !in excluded }
            .map { (it.loadLabel(activity.packageManager).toString()) to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }

        val labels = mutableListOf(Lang.t("— Chọn app —", "— Select app —"))
        labels.addAll(apps.map { it.first })

        val adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        // Select currently saved package
        val savedPkg = castPrefs.autoStartPackage()
        if (savedPkg != null) {
            val idx = apps.indexOfFirst { it.second == savedPkg }
            if (idx >= 0) spinner.setSelection(idx + 1) // +1 for header
        }

        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 0) {
                    castPrefs.setAutoStartPackage(null)
                } else {
                    castPrefs.setAutoStartPackage(apps[position - 1].second)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun populateSplitRatioSpinner(spinner: Spinner, castPrefs: SimpleCastPrefs) {
        val options = listOf("50/50", "60/40", "40/60", "70/30", "30/70")
        val percentValues = listOf(50, 60, 40, 70, 30)

        val adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        // Select currently saved ratio
        val savedPct = castPrefs.splitRatioLeftPercent()
        val idx = percentValues.indexOf(savedPct)
        if (idx >= 0) spinner.setSelection(idx)

        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                castPrefs.setSplitRatioLeftPercent(percentValues[position])
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun View.tint(color: Int) {
        backgroundTintList = android.content.res.ColorStateList.valueOf(activity.getColor(color))
    }

    private fun postUi(block: () -> Unit) =
        activity.runOnUiThread { if (!destroyed && !activity.isFinishing && !activity.isDestroyed) block() }
}

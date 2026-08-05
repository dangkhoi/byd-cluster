package com.byd.clusternav.modules.clustercast

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.byd.clusternav.modules.clustercast.v2.BubbleZone
import com.byd.clusternav.modules.clustercast.v2.CastBubbleProjection
import com.byd.clusternav.modules.clustercast.simplified.SimpleCastState

/**
 * Renders bubble zones and repaints them based on simplified coordinator state.
 *
 * Extracted from FloatingBubbleService to keep each file ≤ 400 LOC.
 * Owns: zone view creation, painting (occupied/empty/disabled), full-state refresh.
 */
internal class BubbleRenderer(private val context: Context) {

    /** Zone views indexed by [BubbleZone] — populated by [createZoneView]. */
    val zoneViews = LinkedHashMap<BubbleZone, TextView>()

    /**
     * Creates a zone view and registers it in [zoneViews].
     *
     * Each zone is at least [ZONE_MIN_DP] (48dp) per axis to meet the 48dp automotive
     * touch-target guideline. `minimumWidth`/`minimumHeight` enforces this even if layout
     * params request a smaller size.
     */
    fun createZoneView(
        zone: BubbleZone,
        widthPx: Int,
        heightPx: Int,
        leftMarginPx: Int,
        onTap: (BubbleZone) -> Unit,
    ): TextView = TextView(context).apply {
        text = CastBubbleProjection.zoneShortLabel(zone)
        textSize = ZONE_TEXT_SP
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        isFocusable = true
        minimumWidth = dp(ZONE_MIN_DP)
        minimumHeight = dp(ZONE_MIN_DP)
        setPadding(dp(8), dp(5), dp(8), dp(5))
        layoutParams = LinearLayout.LayoutParams(widthPx, heightPx).apply { leftMargin = leftMarginPx }
        contentDescription = CastBubbleProjection.zoneShortLabel(zone)
        paintEmpty(this)
        setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            onTap(zone)
        }
        zoneViews[zone] = this
    }

    /**
     * Build the bubble layout: ONE horizontal row of three equal-size zones,
     * order **Trái · Phải · Full**. All three zones use the same size
     * ([HALF_ZONE_WIDTH_DP] × [ZONE_MIN_DP]); [createZoneView] still enforces the
     * ≥48dp automotive minimum via `minimumWidth`/`minimumHeight`. Small [ZONE_GAP_DP]
     * gaps separate the zones. Tap wiring and the [zoneViews] map are registered by
     * [createZoneView], so ordering here does not affect painting/state lookups.
     */
    fun buildBubbleLayout(onTap: (BubbleZone) -> Unit): LinearLayout {
        val zoneHeight = dp(ZONE_MIN_DP)
        val zoneWidth = dp(HALF_ZONE_WIDTH_DP)
        val gap = dp(ZONE_GAP_DP)

        val leftZone = createZoneView(BubbleZone.LEFT, zoneWidth, zoneHeight, 0, onTap)
        val rightZone = createZoneView(BubbleZone.RIGHT, zoneWidth, zoneHeight, gap, onTap)
        val fullZone = createZoneView(BubbleZone.FULL, zoneWidth, zoneHeight, gap, onTap)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 0)
            contentDescription = "Cluster Cast"
            addView(leftZone)
            addView(rightZone)
            addView(fullZone)
        }
    }

    /** Repaint all zones based on current simplified coordinator state. Returns true if painted. */
    fun refreshFromState(state: SimpleCastState): Boolean {
        val fullView = zoneViews[BubbleZone.FULL] ?: return false
        val leftView = zoneViews[BubbleZone.LEFT]
        val rightView = zoneViews[BubbleZone.RIGHT]
        when (state) {
            is SimpleCastState.CastingFull -> {
                paintOccupied(fullView, state.targetPkg.substringAfterLast('.'))
                leftView?.let { paintDisabled(it, "Đang chiếu full") }
                rightView?.let { paintDisabled(it, "Đang chiếu full") }
            }
            is SimpleCastState.CastingSplit -> {
                paintDisabled(fullView, "Đang chia đôi")
                val leftSlot = state.left
                val rightSlot = state.right
                leftView?.let {
                    if (leftSlot != null) paintOccupied(it, leftSlot.pkg.substringAfterLast('.'))
                    else paintEmpty(it)
                }
                rightView?.let {
                    if (rightSlot != null) paintOccupied(it, rightSlot.pkg.substringAfterLast('.'))
                    else paintEmpty(it)
                }
            }
            is SimpleCastState.Idle -> {
                paintEmpty(fullView)
                leftView?.let { paintEmpty(it) }
                rightView?.let { paintEmpty(it) }
            }
            else -> {
                // Off/Opening/Stopping/Closing/Error — all disabled
                val reason = when (state) {
                    is SimpleCastState.Opening -> "Đang mở cụm"
                    is SimpleCastState.Stopping -> "Đang trả app"
                    is SimpleCastState.Closing -> "Đang đóng"
                    is SimpleCastState.Error -> "Lỗi: ${state.message}"
                    else -> "Chưa sẵn sàng"
                }
                paintDisabled(fullView, reason)
                leftView?.let { paintDisabled(it, reason) }
                rightView?.let { paintDisabled(it, reason) }
            }
        }
        return true
    }

    fun paintOccupied(view: TextView, label: String) {
        view.setTextColor(Color.WHITE)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ZONE_CORNER_DP).toFloat()
            setColor(FILL_OCCUPIED)
            setStroke(dp(ZONE_STROKE_DP), BRAND)
        }
        view.alpha = 1f
        view.isEnabled = true
        view.contentDescription = "$label · chạm để trả về"
    }

    fun paintEmpty(view: TextView) {
        view.setTextColor(BRAND)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ZONE_CORNER_DP).toFloat()
            setColor(FILL_EMPTY)
            setStroke(dp(ZONE_STROKE_DP), BRAND)
        }
        view.alpha = 1f
        view.isEnabled = true
        view.contentDescription = "${view.text} · chạm để chiếu"
    }

    /**
     * Paint a disabled zone: visually distinct (low alpha), and taps are no-op.
     * Content description states WHY it is disabled for accessibility.
     */
    fun paintDisabled(view: TextView, reason: String = "") {
        view.setTextColor(BRAND)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ZONE_CORNER_DP).toFloat()
            setColor(FILL_DISABLED)
            setStroke(dp(ZONE_STROKE_DP), BRAND)
        }
        view.alpha = DISABLED_ZONE_ALPHA
        view.isEnabled = false
        view.contentDescription = if (reason.isNotBlank()) "${view.text} · không khả dụng: $reason" else "${view.text} · không khả dụng"
    }

    /** Check if a zone view is disabled (tap should be no-op). */
    fun isZoneDisabled(zone: BubbleZone): Boolean = zoneViews[zone]?.isEnabled == false

    fun clearViews() { zoneViews.clear() }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()

    companion object {
        /**
         * Minimum touch target per zone (dp). Held at the 48dp automotive touch-target
         * guideline — the smallest size that still honours it (owner wanted compact zones).
         * Enforced via `minimumWidth`/`minimumHeight` in [createZoneView] even when the
         * layout params request a smaller width.
         */
        internal const val ZONE_MIN_DP = 48
        internal const val HALF_ZONE_WIDTH_DP = 35
        internal const val ZONE_GAP_DP = 2
        internal const val ZONE_CORNER_DP = 5
        internal const val ZONE_STROKE_DP = 1
        internal const val ZONE_TEXT_SP = 7f
        internal const val DISABLED_ZONE_ALPHA = 0.35f

        /** Opaque brand blue — used for zone strokes and empty-zone text so labels stay legible. */
        internal val BRAND = 0xFF1565C0.toInt()

        /**
         * Translucent zone fills so live cluster content shows through the bubble in every state.
         * All share the brand-blue RGB (0x1565C0); only the alpha byte differs:
         *  - [FILL_OCCUPIED] ≈ 0x99 (~60%): clearly "casting" yet still see-through.
         *  - [FILL_EMPTY]    ≈ 0x33 (~20%): barely tinted so idle cluster content dominates.
         *  - [FILL_DISABLED] ≈ 0x1F (~12%): faintest fill, paired with view-level [DISABLED_ZONE_ALPHA].
         */
        internal val FILL_OCCUPIED = 0x991565C0.toInt()
        internal val FILL_EMPTY = 0x331565C0.toInt()
        internal val FILL_DISABLED = 0x1F1565C0.toInt()
    }
}

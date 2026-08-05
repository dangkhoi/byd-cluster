package com.byd.clusternav.modules.clustercast

import android.content.Context
import android.os.Handler
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handles all touch gestures (drag + tap) for the floating bubble.
 *
 * Extracted from FloatingBubbleService to keep each file ≤ 400 LOC.
 * Owns: drag mechanics, tap-token gate (only one cast/stop operation in flight),
 * executor-based tap dispatch (no raw Thread).
 */
internal class BubbleGestureHandler(
    private val context: Context,
    private val handler: Handler,
    private val onDragEnd: (x: Int, y: Int) -> Unit,
    private val onWake: () -> Unit,
) {
    /**
     * Tap token: only one cast/stop operation allowed at a time.
     * Set to true when a tap fires an action; cleared when action completes.
     * Duplicate taps while token is held are rejected with a visible toast.
     */
    private val tapInFlight = AtomicBoolean(false)

    /** Single-thread executor replaces raw `Thread { }.start()` for tap coalescing. */
    private val tapExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bubble-tap-exec").apply { isDaemon = true }
    }

    /** Acquire tap token. Returns true if acquired, false if already in-flight. */
    fun acquireTapToken(): Boolean = tapInFlight.compareAndSet(false, true)

    /** Release tap token after operation completes (call from action dispatcher). */
    fun releaseTapToken() { tapInFlight.set(false) }

    /** Whether a tap operation is currently in flight. */
    val isTapInFlight: Boolean get() = tapInFlight.get()

    /**
     * Submit a tap action on the coalescing executor. Only one action runs at a time.
     * Returns false if token could not be acquired (duplicate tap rejected).
     */
    fun submitTapAction(name: String, action: () -> Unit): Boolean {
        if (!acquireTapToken()) return false
        tapExecutor.execute {
            try {
                action()
            } finally {
                releaseTapToken()
            }
        }
        return true
    }

    /**
     * Attach drag gesture to EVERY touch surface in the view tree.
     * This ensures any child view (even those with click listeners) can be dragged.
     */
    fun attachDragToEveryTouchSurface(
        view: View,
        root: View,
        layout: WindowManager.LayoutParams,
        manager: WindowManager,
        clampX: (Int, View?) -> Int,
        clampY: (Int, View?) -> Int,
    ) {
        attachDrag(view, root, layout, manager, clampX, clampY)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                attachDragToEveryTouchSurface(view.getChildAt(i), root, layout, manager, clampX, clampY)
            }
        }
    }

    /**
     * Single touch arbiter for one view: distinguishes drag from tap.
     *
     * Consumes all touch events (returns true from ACTION_DOWN) so View.onTouchEvent never fires,
     * preventing double-fire of click events. Tap is dispatched via performClick() only when
     * the finger hasn't exceeded touch slop.
     */
    private fun attachDrag(
        handle: View,
        root: View,
        layout: WindowManager.LayoutParams,
        manager: WindowManager,
        clampX: (Int, View?) -> Int,
        clampY: (Int, View?) -> Int,
    ) {
        var downX = 0f
        var downY = 0f
        var originX = 0
        var originY = 0
        var dragging = false
        val slop = ViewConfiguration.get(context).scaledTouchSlop

        handle.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    onWake()
                    downX = event.rawX; downY = event.rawY
                    originX = layout.x; originY = layout.y; dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (!dragging && dx * dx + dy * dy <= slop * slop) return@setOnTouchListener true
                    dragging = true
                    layout.x = clampX(originX + dx, root)
                    layout.y = clampY(originY + dy, root)
                    runCatching { manager.updateViewLayout(root, layout) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        onDragEnd(layout.x, layout.y)
                    } else if (event.actionMasked == MotionEvent.ACTION_UP && touched.isClickable && touched.isEnabled) {
                        // Only fire click on enabled views — disabled zones are no-op
                        touched.performClick()
                    }
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    /** Shutdown executor. Call on service destroy. */
    fun shutdown() {
        tapInFlight.set(false)
        tapExecutor.shutdownNow()
    }
}

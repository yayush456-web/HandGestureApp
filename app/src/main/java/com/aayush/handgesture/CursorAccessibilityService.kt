package com.aayush.handgesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Needed because a regular app (even with the overlay permission) cannot synthesize a real
 * touch event outside its own windows, nor trigger system actions like Back/Home/Recents -
 * both require the Accessibility API. The user has to turn this on manually in
 * Settings > Accessibility, the same way the overlay/write-settings permissions are granted -
 * there's no programmatic way to enable it.
 *
 * Despite the name, this now backs everything that needs system-level control: cursor
 * clicks/drags (dispatchGesture) and Quick Actions like Back/Home/Recents/Notifications/
 * Screenshot (performGlobalAction).
 */
class CursorAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "CursorAccessibility"

        @Volatile
        private var instance: CursorAccessibilityService? = null

        fun isConnected(): Boolean = instance != null

        /** Dispatches a short tap at the given absolute screen coordinates. No-op if the service isn't enabled. */
        fun performClick(x: Float, y: Float) {
            val svc = instance ?: run {
                Log.w(TAG, "performClick called but accessibility service isn't connected")
                return
            }
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 60)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            svc.dispatchGesture(gesture, null, null)
        }

        /**
         * Replays a recorded finger path (absolute screen coordinates, in order) as a single
         * drag/scroll gesture over [durationMs]. Used for Cursor mode's pinch-and-move.
         */
        fun performDrag(path: List<Pair<Float, Float>>, durationMs: Long) {
            if (path.size < 2) return
            val svc = instance ?: run {
                Log.w(TAG, "performDrag called but accessibility service isn't connected")
                return
            }
            val gesturePath = Path().apply {
                moveTo(path[0].first, path[0].second)
                for (i in 1 until path.size) {
                    lineTo(path[i].first, path[i].second)
                }
            }
            val stroke = GestureDescription.StrokeDescription(gesturePath, 0, durationMs.coerceAtLeast(50))
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            svc.dispatchGesture(gesture, null, null)
        }

        /** Triggers a system-level action (back/home/recents/notifications/screenshot). No-op if not connected. */
        fun triggerGlobalAction(action: Int) {
            val svc = instance ?: run {
                Log.w(TAG, "triggerGlobalAction called but accessibility service isn't connected")
                return
            }
            svc.performGlobalAction(action)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed - this service only performs gestures/global actions, it doesn't inspect screen content.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }
}

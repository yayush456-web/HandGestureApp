package com.aayush.handgesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Needed because a regular app (even with the overlay permission) cannot synthesize a real
 * touch event outside its own windows - that requires the Accessibility API's
 * dispatchGesture(), which is what actually lets the "pinch to click" cursor tap things in
 * other apps. The user has to turn this on manually in Settings > Accessibility, the same way
 * the overlay/write-settings permissions are granted - there's no programmatic way to enable it.
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
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed - this service only performs gestures, it doesn't inspect screen content.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }
}

package com.aayush.handgesture

import android.app.AlertDialog
import android.content.Context
import androidx.appcompat.app.AppCompatActivity

object TutorialHelper {

    private const val PREFS = "hand_gesture_prefs"
    private const val KEY_SHOWN = "tutorial_shown"

    data class Step(val title: String, val body: String)

    private val steps = listOf(
        Step(
            "1. Grant permissions",
            "You've already tapped the 3 permission buttons - camera, overlay, and " +
                "modify system settings. All three are required before Start will work."
        ),
        Step(
            "2. Activate",
            "Tap Start. A small floating box appears in the top-right corner of your " +
                "screen showing your camera feed and a status line - that's how the app " +
                "watches your hand.\n\nHold your OPEN PALM up to the front camera to activate."
        ),
        Step(
            "3. Open the menu",
            "Once active, make a FIST to open the quick menu. The status line will show " +
                "you're in menu mode."
        ),
        Step(
            "4. Pick what to adjust",
            "Hold up 1 FINGER (index) to select Brightness.\nHold up 2 FINGERS " +
                "(index + middle) to select Volume."
        ),
        Step(
            "5. Adjust the value",
            "Pinch your thumb and index finger together like you're holding a tiny knob, " +
                "then rotate your wrist:\n\nClockwise = increase\nCounterclockwise = decrease\n\n" +
                "Keep pinching while you rotate - release the pinch to stop adjusting."
        ),
        Step(
            "6. Go back / deactivate",
            "Make a FIST at any time to return to the quick menu.\nShow your OPEN PALM at " +
                "any time to deactivate completely.\n\nTip: hold each gesture steady for about " +
                "half a second - the app waits for a stable gesture before acting, so quick " +
                "hand movements while repositioning won't trigger anything by accident."
        )
    )

    fun shouldShowOnFirstLaunch(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return !prefs.getBoolean(KEY_SHOWN, false)
    }

    private fun markShown(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOWN, true).apply()
    }

    fun show(activity: AppCompatActivity, onFinished: (() -> Unit)? = null) {
        showStep(activity, 0, onFinished)
    }

    private fun showStep(activity: AppCompatActivity, index: Int, onFinished: (() -> Unit)?) {
        if (activity.isFinishing) return
        if (index >= steps.size) {
            markShown(activity)
            onFinished?.invoke()
            return
        }
        val step = steps[index]
        val isLast = index == steps.size - 1
        AlertDialog.Builder(activity)
            .setTitle(step.title)
            .setMessage(step.body)
            .setCancelable(false)
            .setPositiveButton(if (isLast) "Got it, let's go" else "Next") { _, _ ->
                showStep(activity, index + 1, onFinished)
            }
            .apply {
                if (index > 0) {
                    setNegativeButton("Skip tutorial") { _, _ ->
                        markShown(activity)
                        onFinished?.invoke()
                    }
                }
            }
            .show()
    }
}

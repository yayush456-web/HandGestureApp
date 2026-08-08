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
            "You've already tapped the permission buttons - camera, overlay, and modify " +
                "system settings. Those three are required before Start will work. The 4th " +
                "one (Accessibility) is optional and only needed for the Cursor and Quick " +
                "Actions modes (clicking, dragging, Back/Home/Recents/Screenshot)."
        ),
        Step(
            "2. Activate",
            "Tap Start. A small floating box appears in the top-right corner of your " +
                "screen showing your camera feed and a status line - that's how the app " +
                "watches your hand.\n\nHold your OPEN PALM up to the front camera to activate."
        ),
        Step(
            "3. Open the menu",
            "Once active, give a THUMBS UP to open the quick menu. The status line will show " +
                "you're in menu mode."
        ),
        Step(
            "4. Pick what to control",
            "Hold up 1 FINGER for Brightness.\nHold up 2 FINGERS (index + middle) for " +
                "Volume.\nHold up 3 FINGERS (index + middle + ring) for Music.\nHold up just " +
                "your PINKY for the Cursor.\nHold up INDEX + PINKY together (a 'rock' shape) " +
                "for Quick Actions."
        ),
        Step(
            "5. Brightness / Volume",
            "Pinch your thumb and index finger together like you're holding a tiny knob, " +
                "then rotate your wrist:\n\nClockwise = increase\nCounterclockwise = decrease\n\n" +
                "The status line shows the live percentage as you turn. Releasing the pinch just " +
                "pauses adjusting - it won't back you out, so a hand that opens up mid-turn won't " +
                "kick you out by accident. Only a THUMBS UP returns you to the menu."
        ),
        Step(
            "6. Music",
            "A quick PINCH (tap your thumb and index finger together, no rotating needed) " +
                "toggles play/pause on whatever's currently playing - Spotify, YouTube Music, " +
                "whatever has media focus."
        ),
        Step(
            "7. Cursor",
            "Move your INDEX FINGER in front of the camera and a small ring follows it on " +
                "screen.\n\nA quick PINCH taps wherever the ring is.\nA PINCH that MOVES before " +
                "releasing drags/scrolls instead - same idea as a trackpad: tap vs. click-and-" +
                "drag.\n\nWorks in other apps too, not just this one. Needs the Accessibility " +
                "permission from step 1."
        ),
        Step(
            "8. Quick Actions",
            "A HUD list appears in the center of your screen: Camera, Flashlight, " +
                "Screenshot, Back, Home, Recents, Notifications.\n\nShow 2 FINGERS to move the " +
                "highlight down the list (wraps around).\nPINCH to run whichever one's " +
                "highlighted.\n\nBack/Home/Recents/Screenshot/Notifications need the " +
                "Accessibility permission; Camera and Flashlight don't."
        ),
        Step(
            "9. Go back / deactivate",
            "Give a THUMBS UP at any time to return to the quick menu.\nShow your OPEN PALM " +
                "while active or in the menu to deactivate completely.\n\nTip: hold each gesture " +
                "steady for about half a second - the app waits for a stable gesture before " +
                "acting, so quick hand movements while repositioning won't trigger anything by " +
                "accident."
        ),
        Step(
            "10. About the floating box",
            "The camera thumbnail only shows while this app is open on screen, so you can see " +
                "what the tracker sees while you practice. Once you switch to another app, the " +
                "thumbnail disappears and the box shrinks to just a small mode label - gesture " +
                "tracking keeps working in the background either way."
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

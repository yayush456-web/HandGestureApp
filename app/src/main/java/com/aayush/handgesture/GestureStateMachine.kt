package com.aayush.handgesture

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

class GestureStateMachine(
    private val onStateText: (String) -> Unit,
    private val onAdjust: (target: Target, delta: Int) -> Unit
) {
    enum class Mode { IDLE, ACTIVE, MENU, ADJUST }
    enum class Target { NONE, BRIGHTNESS, VOLUME }

    private var mode = Mode.IDLE
    private var target = Target.NONE

    // debounce: require N consecutive frames of the same gesture before acting
    private var lastGesture: GestureUtils.Gesture? = null
    private var sameGestureCount = 0
    private val debounceFrames = 5

    // rotation tracking while pinching
    private var lastPinchAngle: Float? = null
    private var accumulatedAngle = 0f
    private val degreesPerStep = 12f

    // frames with no hand detected -> auto reset to idle
    private var missingFrames = 0
    private val missingFramesToReset = 45 // ~1.5s at 30fps

    fun onNoHand() {
        missingFrames++
        if (missingFrames > missingFramesToReset && mode != Mode.IDLE) {
            reset()
        }
        lastPinchAngle = null
    }

    fun onLandmarks(lm: List<NormalizedLandmark>) {
        missingFrames = 0
        val gesture = GestureUtils.classify(lm)

        // debounce logic - PINCH is time-sensitive so it bypasses debounce once in ADJUST mode
        if (mode == Mode.ADJUST && gesture == GestureUtils.Gesture.PINCH) {
            handlePinchRotation(lm)
            publishStatus()
            return
        } else {
            lastPinchAngle = null
        }

        if (gesture == lastGesture) {
            sameGestureCount++
        } else {
            lastGesture = gesture
            sameGestureCount = 1
        }

        if (sameGestureCount == debounceFrames) {
            handleStableGesture(gesture)
        }
        publishStatus()
    }

    private fun handleStableGesture(gesture: GestureUtils.Gesture) {
        when (mode) {
            Mode.IDLE -> {
                if (gesture == GestureUtils.Gesture.OPEN_PALM) {
                    mode = Mode.ACTIVE
                }
            }
            Mode.ACTIVE -> {
                when (gesture) {
                    GestureUtils.Gesture.THUMBS_UP -> mode = Mode.MENU
                    GestureUtils.Gesture.OPEN_PALM -> mode = Mode.IDLE // toggle off
                    else -> {}
                }
            }
            Mode.MENU -> {
                when (gesture) {
                    GestureUtils.Gesture.ONE_FINGER -> {
                        target = Target.BRIGHTNESS
                        mode = Mode.ADJUST
                    }
                    GestureUtils.Gesture.TWO_FINGER -> {
                        target = Target.VOLUME
                        mode = Mode.ADJUST
                    }
                    GestureUtils.Gesture.OPEN_PALM -> mode = Mode.IDLE
                    else -> {}
                }
            }
            Mode.ADJUST -> {
                when (gesture) {
                    GestureUtils.Gesture.THUMBS_UP -> {
                        mode = Mode.MENU
                        target = Target.NONE
                    }
                    GestureUtils.Gesture.OPEN_PALM -> {
                        mode = Mode.IDLE
                        target = Target.NONE
                    }
                    else -> {}
                }
            }
        }
    }

    private fun handlePinchRotation(lm: List<NormalizedLandmark>) {
        val angle = GestureUtils.pinchAngleDeg(lm)
        val prev = lastPinchAngle
        if (prev != null) {
            val delta = GestureUtils.angleDelta(prev, angle)
            accumulatedAngle += delta
            while (accumulatedAngle >= degreesPerStep) {
                accumulatedAngle -= degreesPerStep
                onAdjust(target, +1) // clockwise = increase
            }
            while (accumulatedAngle <= -degreesPerStep) {
                accumulatedAngle += degreesPerStep
                onAdjust(target, -1) // counterclockwise = decrease
            }
        }
        lastPinchAngle = angle
    }

    private fun reset() {
        mode = Mode.IDLE
        target = Target.NONE
        lastGesture = null
        sameGestureCount = 0
        lastPinchAngle = null
        publishStatus()
    }

    private fun publishStatus() {
        val text = when (mode) {
            Mode.IDLE -> "○ idle - show palm to activate"
            Mode.ACTIVE -> "● active - thumbs up for menu"
            Mode.MENU -> "☰ menu - 1=brightness 2=volume"
            Mode.ADJUST -> {
                val label = if (target == Target.BRIGHTNESS) "brightness" else "volume"
                "◐ $label - pinch + rotate wrist"
            }
        }
        onStateText(text)
    }
}

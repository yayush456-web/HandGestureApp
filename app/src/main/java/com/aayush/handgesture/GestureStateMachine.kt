package com.aayush.handgesture

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

class GestureStateMachine(
    private val onStateText: (full: String, minimal: String) -> Unit,
    private val onAdjust: (target: Target, delta: Int) -> Int, // returns the resulting level, 0-100
    private val onQueryLevel: (target: Target) -> Int, // returns current level, 0-100, without changing it
    private val onMusicToggle: () -> Unit,
    private val onCursorActive: (Boolean) -> Unit, // fires once whenever CURSOR mode is entered/left
    private val onCursorMove: (x: Float, y: Float) -> Unit, // normalized (0-1) index fingertip position, every frame
    private val onCursorClick: () -> Unit
) {
    enum class Mode { IDLE, ACTIVE, MENU, ADJUST, MUSIC, CURSOR }
    enum class Target { NONE, BRIGHTNESS, VOLUME }

    private var mode = Mode.IDLE
    private var target = Target.NONE
    private var currentLevelPct: Int? = null

    // debounce: require N consecutive frames of the same gesture before acting.
    // Used for gestures you deliberately HOLD for a moment (menu picks, thumbs up, open palm).
    private var lastGesture: GestureUtils.Gesture? = null
    private var sameGestureCount = 0
    private val debounceFrames = 5

    // Click/toggle in CURSOR and MUSIC modes are fast pinch-and-release snaps, not held
    // poses - they almost never survive `debounceFrames` consecutive identical frames, which
    // was why clicking only worked a fraction of the time. Instead this fires the instant a
    // pinch starts (rising edge), needing only a couple of frames to filter out single-frame
    // noise, and won't refire again until the pinch is released and re-closed.
    private var pinchStreak = 0
    private var pinchClickArmed = true
    private val pinchClickConfirmFrames = 2

    // rotation tracking while pinching
    private var lastPinchAngle: Float? = null
    private var accumulatedAngle = 0f
    private val degreesPerStep = 6f

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
        val pinchingNow = GestureUtils.isPinching(lm)

        // Cursor mode needs the index fingertip position every single frame, not just on a
        // stable/debounced gesture change - the whole point is that it tracks continuously.
        if (mode == Mode.CURSOR) {
            val tip = lm[8]
            onCursorMove(tip.x(), tip.y())
        }

        // Fast-path click/toggle detection - see field comments above for why this bypasses
        // the general debounce below. Runs every frame regardless of mode so pinchStreak/
        // pinchClickArmed always reflect reality; only MUSIC/CURSOR actually act on it.
        if (pinchingNow) {
            pinchStreak++
        } else {
            pinchStreak = 0
            pinchClickArmed = true
        }
        if ((mode == Mode.MUSIC || mode == Mode.CURSOR) &&
            pinchClickArmed && pinchStreak >= pinchClickConfirmFrames
        ) {
            pinchClickArmed = false
            when (mode) {
                Mode.MUSIC -> onMusicToggle()
                Mode.CURSOR -> onCursorClick()
                else -> {}
            }
        }

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
                        currentLevelPct = onQueryLevel(target)
                    }
                    GestureUtils.Gesture.TWO_FINGER -> {
                        target = Target.VOLUME
                        mode = Mode.ADJUST
                        currentLevelPct = onQueryLevel(target)
                    }
                    GestureUtils.Gesture.THREE_FINGER -> mode = Mode.MUSIC
                    GestureUtils.Gesture.PINKY_ONLY -> {
                        mode = Mode.CURSOR
                        onCursorActive(true)
                    }
                    GestureUtils.Gesture.OPEN_PALM -> mode = Mode.IDLE
                    else -> {}
                }
            }
            Mode.ADJUST -> {
                // Only THUMBS_UP leaves ADJUST - OPEN_PALM is deliberately not handled here,
                // since fingers spreading open mid-pinch (releasing the pinch) was being
                // misread as an open palm and kicking the user out to IDLE unintentionally.
                when (gesture) {
                    GestureUtils.Gesture.THUMBS_UP -> {
                        mode = Mode.MENU
                        target = Target.NONE
                        currentLevelPct = null
                    }
                    else -> {}
                }
            }
            Mode.MUSIC -> {
                when (gesture) {
                    GestureUtils.Gesture.THUMBS_UP -> mode = Mode.MENU
                    else -> {}
                }
            }
            Mode.CURSOR -> {
                when (gesture) {
                    GestureUtils.Gesture.THUMBS_UP -> {
                        mode = Mode.MENU
                        onCursorActive(false)
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
                currentLevelPct = onAdjust(target, +1) // clockwise = increase
            }
            while (accumulatedAngle <= -degreesPerStep) {
                accumulatedAngle += degreesPerStep
                currentLevelPct = onAdjust(target, -1) // counterclockwise = decrease
            }
        }
        lastPinchAngle = angle
    }

    private fun reset() {
        if (mode == Mode.CURSOR) onCursorActive(false)
        mode = Mode.IDLE
        target = Target.NONE
        currentLevelPct = null
        lastGesture = null
        sameGestureCount = 0
        lastPinchAngle = null
        pinchStreak = 0
        pinchClickArmed = true
        publishStatus()
    }

    /** Short label used by the overlay when the app itself isn't in the foreground - still shows
     *  the live level for brightness/volume, just without the full instructions. */
    private fun modeLabel(): String = when (mode) {
        Mode.IDLE -> "Idle"
        Mode.ACTIVE -> "Active"
        Mode.MENU -> "Menu"
        Mode.ADJUST -> {
            val label = if (target == Target.BRIGHTNESS) "Brightness" else "Volume"
            currentLevelPct?.let { "$label $it%" } ?: label
        }
        Mode.MUSIC -> "Music"
        Mode.CURSOR -> "Cursor"
    }

    private fun publishStatus() {
        val text = when (mode) {
            Mode.IDLE -> "○ idle - show palm to activate"
            Mode.ACTIVE -> "● active - thumbs up for menu"
            Mode.MENU -> "☰ menu - 1=brightness 2=volume 3=music pinky=cursor"
            Mode.ADJUST -> {
                val label = if (target == Target.BRIGHTNESS) "brightness" else "volume"
                val icon = if (target == Target.BRIGHTNESS) "☀" else "🔊"
                val pctText = currentLevelPct?.let { " $it%" } ?: ""
                "$icon $label$pctText - pinch + rotate, thumbs up to go back"
            }
            Mode.MUSIC -> "🎵 music - pinch to play/pause, thumbs up to go back"
            Mode.CURSOR -> "🖱 cursor - move index finger, pinch to click, thumbs up to go back"
        }
        onStateText(text, modeLabel())
    }
}

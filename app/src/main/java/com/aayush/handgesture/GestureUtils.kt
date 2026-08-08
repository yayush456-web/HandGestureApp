package com.aayush.handgesture

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Landmark index reference (MediaPipe Hand Landmarker, 21 points):
 * 0 wrist
 * 1-4 thumb (cmc, mcp, ip, tip)
 * 5-8 index (mcp, pip, dip, tip)
 * 9-12 middle
 * 13-16 ring
 * 17-20 pinky
 */
object GestureUtils {

    private fun dist(a: NormalizedLandmark, b: NormalizedLandmark): Float {
        return hypot((a.x() - b.x()).toDouble(), (a.y() - b.y()).toDouble()).toFloat()
    }

    /** Rough hand scale reference so thresholds work at any distance from camera. */
    private fun handScale(lm: List<NormalizedLandmark>): Float {
        return dist(lm[0], lm[9]).coerceAtLeast(0.02f) // wrist to middle_mcp
    }

    /** true if a given non-thumb finger (tip/pip/mcp indices) is extended. */
    private fun isFingerExtended(lm: List<NormalizedLandmark>, tip: Int, pip: Int, mcp: Int): Boolean {
        val tipToWrist = dist(lm[tip], lm[0])
        val pipToWrist = dist(lm[pip], lm[0])
        val mcpToWrist = dist(lm[mcp], lm[0])
        // extended if tip is meaningfully further from wrist than pip/mcp
        return tipToWrist > pipToWrist && tipToWrist > mcpToWrist * 1.15f
    }

    private fun isThumbExtended(lm: List<NormalizedLandmark>): Boolean {
        val scale = handScale(lm)
        val tipToPinkyMcp = dist(lm[4], lm[17])
        val mcpToPinkyMcp = dist(lm[2], lm[17])
        return tipToPinkyMcp > mcpToPinkyMcp * 0.9f && dist(lm[4], lm[0]) > scale * 0.9f
    }

    data class FingerState(
        val thumb: Boolean,
        val index: Boolean,
        val middle: Boolean,
        val ring: Boolean,
        val pinky: Boolean
    ) {
        fun extendedCount(): Int = listOf(thumb, index, middle, ring, pinky).count { it }
    }

    fun fingerState(lm: List<NormalizedLandmark>): FingerState {
        return FingerState(
            thumb = isThumbExtended(lm),
            index = isFingerExtended(lm, 8, 6, 5),
            middle = isFingerExtended(lm, 12, 10, 9),
            ring = isFingerExtended(lm, 16, 14, 13),
            pinky = isFingerExtended(lm, 20, 18, 17)
        )
    }

    enum class Gesture { OPEN_PALM, FIST, THUMBS_UP, ONE_FINGER, TWO_FINGER, THREE_FINGER, PINKY_ONLY, ROCK, PINCH, UNKNOWN }

    fun classify(lm: List<NormalizedLandmark>): Gesture {
        val fs = fingerState(lm)
        if (isPinching(lm)) return Gesture.PINCH
        return when {
            fs.extendedCount() >= 4 -> Gesture.OPEN_PALM
            fs.extendedCount() == 0 -> Gesture.FIST
            fs.thumb && !fs.index && !fs.middle && !fs.ring && !fs.pinky -> Gesture.THUMBS_UP
            fs.index && fs.pinky && !fs.middle && !fs.ring -> Gesture.ROCK
            fs.pinky && !fs.index && !fs.middle && !fs.ring -> Gesture.PINKY_ONLY
            fs.index && fs.middle && fs.ring && !fs.pinky -> Gesture.THREE_FINGER
            fs.index && fs.middle && !fs.ring && !fs.pinky -> Gesture.TWO_FINGER
            fs.index && !fs.middle && !fs.ring && !fs.pinky -> Gesture.ONE_FINGER
            else -> Gesture.UNKNOWN
        }
    }

    /** Thumb tip close to index tip = pinch (used to grab the "knob", click, toggle, select). */
    fun isPinching(lm: List<NormalizedLandmark>): Boolean {
        val scale = handScale(lm)
        val pinchDist = dist(lm[4], lm[8])
        return pinchDist < scale * 0.7f
    }

    /** Angle (degrees) of the pinch point relative to the wrist, for rotation tracking. */
    fun pinchAngleDeg(lm: List<NormalizedLandmark>): Float {
        val midX = (lm[4].x() + lm[8].x()) / 2f
        val midY = (lm[4].y() + lm[8].y()) / 2f
        val dx = (midX - lm[0].x()).toDouble()
        val dy = (midY - lm[0].y()).toDouble()
        return Math.toDegrees(atan2(dy, dx)).toFloat()
    }

    /** Shortest signed angular difference from a to b, in degrees (-180..180). */
    fun angleDelta(a: Float, b: Float): Float {
        var d = b - a
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        return d
    }
}

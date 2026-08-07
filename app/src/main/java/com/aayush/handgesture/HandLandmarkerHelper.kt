package com.aayush.handgesture

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

class HandLandmarkerHelper(
    private val context: Context,
    private val onResult: (List<NormalizedLandmark>?) -> Unit
) {
    private var landmarker: HandLandmarker? = null
    private val TAG = "HandLandmarkerHelper"

    fun setup(): Boolean {
        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(ModelManager.localModelFile(context).absolutePath)
                .setDelegate(Delegate.CPU)
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener { result: HandLandmarkerResult, _ ->
                    val landmarks = result.landmarks()
                    if (landmarks.isNotEmpty()) {
                        onResult(landmarks[0])
                    } else {
                        onResult(null)
                    }
                }
                .setErrorListener { e -> Log.e(TAG, "Landmarker error", e) }
                .build()

            landmarker = HandLandmarker.createFromOptions(context, options)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set up HandLandmarker", e)
            false
        }
    }

    fun detectAsync(bitmap: Bitmap, timestampMs: Long) {
        val mpImage = BitmapImageBuilder(bitmap).build()
        landmarker?.detectAsync(mpImage, timestampMs)
    }

    fun close() {
        landmarker?.close()
        landmarker = null
    }
}

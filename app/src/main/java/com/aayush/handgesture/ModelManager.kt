package com.aayush.handgesture

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The MediaPipe hand_landmarker.task model (~7-30MB) isn't bundled in the repo to keep it light.
 * It's fetched once, on first launch, straight from Google's public model bucket and cached
 * in app-internal storage. No account/API key needed - it's a public asset.
 */
object ModelManager {

    private const val MODEL_URL =
        "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
    private const val MODEL_FILENAME = "hand_landmarker.task"
    private const val TAG = "ModelManager"

    fun localModelFile(context: Context): File = File(context.filesDir, MODEL_FILENAME)

    fun isModelReady(context: Context): Boolean {
        val f = localModelFile(context)
        return f.exists() && f.length() > 1_000_000L // sanity check, real file is several MB
    }

    /** Blocking download - call from a background thread/coroutine, not the main thread. */
    fun downloadModelBlocking(context: Context): Boolean {
        val dest = localModelFile(context)
        if (isModelReady(context)) return true
        return try {
            val url = URL(MODEL_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.connect()
            if (conn.responseCode !in 200..299) {
                Log.e(TAG, "Model download failed, HTTP ${conn.responseCode}")
                return false
            }
            val tmp = File(context.filesDir, "$MODEL_FILENAME.part")
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            tmp.renameTo(dest)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Model download error", e)
            false
        }
    }
}

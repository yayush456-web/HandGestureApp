package com.aayush.handgesture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class GestureOverlayService : LifecycleService() {

    companion object {
        private const val TAG = "GestureOverlayService"
        private const val CHANNEL_ID = "gesture_control_channel"
        private const val NOTIF_ID = 42
        private const val MAX_BRIGHTNESS = 255
    }

    private lateinit var overlay: OverlayManager
    private lateinit var stateMachine: GestureStateMachine
    private var handLandmarkerHelper: HandLandmarkerHelper? = null
    private var cameraExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var audioManager: AudioManager

    private var lastStatusText = "○ idle"

    override fun onCreate() {
        super.onCreate()
        overlay = OverlayManager(this)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        stateMachine = GestureStateMachine(
            onStateText = { text ->
                lastStatusText = text
                overlay.update(text)
            },
            onAdjust = { targetEnum, delta -> applyAdjustment(targetEnum, delta) }
        )

        startForegroundNotification()
        overlay.show()

        CoroutineScope(Dispatchers.IO).launch {
            if (!ModelManager.isModelReady(this@GestureOverlayService)) {
                overlay.update("downloading hand model...")
                val ok = ModelManager.downloadModelBlocking(this@GestureOverlayService)
                if (!ok) {
                    overlay.update("model download failed - check internet")
                    return@launch
                }
            }
            handLandmarkerHelper = HandLandmarkerHelper(this@GestureOverlayService) { landmarks ->
                onLandmarksResult(landmarks)
            }
            val setupOk = handLandmarkerHelper?.setup() ?: false
            if (setupOk) {
                startCamera()
            } else {
                overlay.update("hand tracker setup failed")
            }
        }
    }

    private fun onLandmarksResult(landmarks: List<NormalizedLandmark>?) {
        // This callback fires on a MediaPipe worker thread, not the main thread.
        // Everything downstream (state machine + overlay.update) is safe to call from
        // here because OverlayManager.update() hops to the main thread internally,
        // and the state machine itself does no UI work directly.
        try {
            if (landmarks == null) {
                stateMachine.onNoHand()
            } else {
                stateMachine.onLandmarks(landmarks)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gesture processing error", e)
            overlay.update("gesture error - see logs")
        }
    }

    private fun applyAdjustment(target: GestureStateMachine.Target, delta: Int) {
        when (target) {
            GestureStateMachine.Target.BRIGHTNESS -> adjustBrightness(delta)
            GestureStateMachine.Target.VOLUME -> adjustVolume(delta)
            GestureStateMachine.Target.NONE -> {}
        }
    }

    private fun adjustBrightness(delta: Int) {
        if (!Settings.System.canWrite(this)) {
            overlay.update("need 'modify system settings' permission")
            return
        }
        val step = 13 // ~5% of 255 per rotation step
        val current = try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Exception) {
            128
        }
        val next = (current + delta * step).coerceIn(1, MAX_BRIGHTNESS)
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, next)
        val pct = (next * 100) / MAX_BRIGHTNESS
        overlay.update("☀ brightness $pct%")
    }

    private fun adjustVolume(delta: Int) {
        val stream = AudioManager.STREAM_MUSIC
        val max = audioManager.getStreamMaxVolume(stream)
        val current = audioManager.getStreamVolume(stream)
        val next = (current + delta).coerceIn(0, max)
        audioManager.setStreamVolume(stream, next, 0)
        val pct = (next * 100) / max
        overlay.update("🔊 volume $pct%")
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            cameraExecutor = Executors.newSingleThreadExecutor()

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor!!) { imageProxy ->
                try {
                    val bitmap = ImageUtils.imageProxyToBitmap(imageProxy)
                    if (bitmap != null) {
                        handLandmarkerHelper?.detectAsync(bitmap, System.currentTimeMillis())
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Frame processing error", e)
                } finally {
                    imageProxy.close()
                }
            }

            val preview = Preview.Builder().build()
            val previewView = overlay.getPreviewView()
            if (previewView != null) {
                preview.setSurfaceProvider(previewView.surfaceProvider)
            }

            val selector = CameraSelector.DEFAULT_FRONT_CAMERA
            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, selector, preview, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                overlay.update("camera bind failed")
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Gesture Control", NotificationManager.IMPORTANCE_MIN
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hand Gesture Control active")
            .setContentText("Watching for hand gestures")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
        cameraExecutor?.shutdown()
        handLandmarkerHelper?.close()
        overlay.hide()
    }
}

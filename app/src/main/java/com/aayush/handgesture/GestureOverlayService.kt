package com.aayush.handgesture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ProcessLifecycleOwner
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

    // Raw (unsmoothed source) + smoothed on-screen cursor position, updated every frame while
    // in Cursor mode. Only ever touched from the MediaPipe result thread (same thread that
    // delivers onCursorMove/onCursorClick), so plain fields are fine - same assumption the
    // rest of the gesture pipeline already relies on.
    private var cursorScreenX = 0f
    private var cursorScreenY = 0f
    private var cursorInitialized = false

    // Reflects whether the app itself (any of its activities) is on screen right now.
    // The overlay only shows the live camera thumbnail while this is true; otherwise it
    // falls back to a minimal text-only mode indicator so the camera preview isn't left
    // rendering off-screen for no reason.
    private val appForegroundObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            overlay.setAppForeground(true)
        }
        override fun onStop(owner: LifecycleOwner) {
            overlay.setAppForeground(false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        overlay = OverlayManager(this)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        stateMachine = GestureStateMachine(
            onStateText = { full, minimal ->
                lastStatusText = full
                overlay.update(full, minimal)
            },
            onAdjust = { targetEnum, delta -> applyAdjustment(targetEnum, delta) },
            onQueryLevel = { targetEnum -> queryLevel(targetEnum) },
            onMusicToggle = { toggleMusicPlayback() },
            onCursorActive = { active ->
                cursorInitialized = false
                overlay.setCursorVisible(active)
            },
            onCursorMove = { nx, ny -> updateCursorPosition(nx, ny) },
            onCursorClick = { CursorAccessibilityService.performClick(cursorScreenX, cursorScreenY) }
        )

        startForegroundNotification()
        overlay.show()
        ProcessLifecycleOwner.get().lifecycle.addObserver(appForegroundObserver)

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

    private fun applyAdjustment(target: GestureStateMachine.Target, delta: Int): Int {
        return when (target) {
            GestureStateMachine.Target.BRIGHTNESS -> adjustBrightness(delta)
            GestureStateMachine.Target.VOLUME -> adjustVolume(delta)
            GestureStateMachine.Target.NONE -> 0
        }
    }

    /** Current level for `target` as a 0-100 percentage, without changing anything. */
    private fun queryLevel(target: GestureStateMachine.Target): Int {
        return when (target) {
            GestureStateMachine.Target.BRIGHTNESS -> currentBrightnessPct()
            GestureStateMachine.Target.VOLUME -> currentVolumePct()
            GestureStateMachine.Target.NONE -> 0
        }
    }

    private fun currentBrightnessRaw(): Int = try {
        Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    } catch (e: Exception) {
        128
    }

    private fun currentBrightnessPct(): Int = (currentBrightnessRaw() * 100) / MAX_BRIGHTNESS

    private fun currentVolumePct(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) (current * 100) / max else 0
    }

    /** Adjusts brightness by one step and returns the resulting percentage, for the overlay to display live. */
    private fun adjustBrightness(delta: Int): Int {
        if (!Settings.System.canWrite(this)) {
            overlay.update("need 'modify system settings' permission", "Permission needed")
            return currentBrightnessPct()
        }
        val step = 13 // ~5% of 255 per rotation step
        val next = (currentBrightnessRaw() + delta * step).coerceIn(1, MAX_BRIGHTNESS)
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, next)
        return (next * 100) / MAX_BRIGHTNESS
    }

    /** Adjusts volume by one step and returns the resulting percentage, for the overlay to display live. */
    private fun adjustVolume(delta: Int): Int {
        val stream = AudioManager.STREAM_MUSIC
        val max = audioManager.getStreamMaxVolume(stream)
        val current = audioManager.getStreamVolume(stream)
        val next = (current + delta).coerceIn(0, max)
        audioManager.setStreamVolume(stream, next, 0)
        return if (max > 0) (next * 100) / max else 0
    }

    /**
     * Sends a media play/pause key event - the same mechanism a Bluetooth headset button uses.
     * The system routes it to whichever app currently holds media focus (Spotify, YouTube
     * Music, etc.), so this doesn't need to know or care what's actually playing.
     */
    private fun toggleMusicPlayback() {
        val eventTime = System.currentTimeMillis()
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)
        )
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)
        )
    }

    /**
     * Maps the index fingertip's normalized position (0-1, from the front camera's raw,
     * unmirrored frame) to absolute screen pixel coordinates, smooths it to cut down on
     * camera jitter, and moves the cursor dot there.
     *
     * X is flipped (1 - nx) because the front camera's raw coordinate frame is mirrored
     * relative to how you're moving your hand - without the flip, moving your hand right
     * would send the cursor left. If it ever feels backwards on a given device, flip this.
     */
    private fun updateCursorPosition(nx: Float, ny: Float) {
        val dm = resources.displayMetrics
        val rawX = (1f - nx) * dm.widthPixels
        val rawY = ny * dm.heightPixels
        if (!cursorInitialized) {
            cursorScreenX = rawX
            cursorScreenY = rawY
            cursorInitialized = true
        } else {
            cursorScreenX += (rawX - cursorScreenX) * 0.4f
            cursorScreenY += (rawY - cursorScreenY) * 0.4f
        }
        overlay.updateCursorPosition(cursorScreenX, cursorScreenY)
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
        ProcessLifecycleOwner.get().lifecycle.removeObserver(appForegroundObserver)
        cameraProvider?.unbindAll()
        cameraExecutor?.shutdown()
        handLandmarkerHelper?.close()
        overlay.hide()
    }
}

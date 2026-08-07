package com.aayush.handgesture

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.camera.view.PreviewView

/**
 * A small always-on-top panel showing a live camera thumbnail (so you can see what the
 * hand tracker sees) plus a status line. All updates are marshalled onto the main thread
 * because MediaPipe's result callback fires on a background thread, and Views can only
 * be touched from the UI thread - forgetting this was the cause of the "opens then closes"
 * crash in the first build.
 */
class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var container: LinearLayout? = null
    private var statusText: TextView? = null
    private var previewView: PreviewView? = null

    /** Safe to call from any thread; hops to main internally. */
    fun show() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show() }
            return
        }
        if (container != null) return

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.x = 24
        params.y = 120

        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val preview = PreviewView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(140), dp(187)) // 3:4 thumbnail
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }

        val status = TextView(context).apply {
            text = "● idle"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(4), dp(6), dp(4), 0)
        }

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_overlay_panel)
            addView(preview)
            addView(status)
        }

        container = layout
        statusText = status
        previewView = preview
        windowManager.addView(layout, params)
    }

    /** Safe to call from any thread. */
    fun update(text: String) {
        mainHandler.post { statusText?.text = text }
    }

    /** Returns the PreviewView to bind CameraX's Preview use case to, once the overlay is shown. */
    fun getPreviewView(): PreviewView? = previewView

    fun hide() {
        mainHandler.post {
            container?.let {
                try {
                    windowManager.removeView(it)
                } catch (_: Exception) {
                }
            }
            container = null
            statusText = null
            previewView = null
        }
    }
}

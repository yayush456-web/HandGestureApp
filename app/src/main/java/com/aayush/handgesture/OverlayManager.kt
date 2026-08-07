package com.aayush.handgesture

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.camera.view.PreviewView

/**
 * A small always-on-top panel. While the app itself is open on screen it shows a live
 * camera thumbnail (so you can see what the hand tracker sees) plus a detailed status
 * line. Once you leave the app, the thumbnail hides itself (nothing to gain from
 * rendering a preview no one's looking at) and the panel shrinks to a minimal one-word
 * mode indicator (Idle / Active / Menu / Brightness / Volume) instead.
 *
 * All updates are marshalled onto the main thread because MediaPipe's result callback
 * fires on a background thread, and Views can only be touched from the UI thread -
 * forgetting this was the cause of the "opens then closes" crash in the first build.
 */
class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var container: LinearLayout? = null
    private var statusText: TextView? = null
    private var previewView: PreviewView? = null

    // Assume foreground by default: the service is only ever started from an on-screen
    // Activity, and the real state arrives moments later via setAppForeground().
    private var isAppForeground = true
    private var lastFullText = "○ idle"
    private var lastMinimalText = "Idle"

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
            visibility = if (isAppForeground) View.VISIBLE else View.GONE
        }

        val status = TextView(context).apply {
            text = if (isAppForeground) lastFullText else lastMinimalText
            setTextColor(Color.WHITE)
            textSize = if (isAppForeground) 13f else 12f
            setPadding(dp(4), if (isAppForeground) dp(6) else dp(4), dp(4), 0)
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

    /** Safe to call from any thread. Pass whether the app (any of its activities) is on screen right now. */
    fun setAppForeground(foreground: Boolean) {
        isAppForeground = foreground
        mainHandler.post {
            previewView?.visibility = if (foreground) View.VISIBLE else View.GONE
            statusText?.text = if (foreground) lastFullText else lastMinimalText
            statusText?.textSize = if (foreground) 13f else 12f
        }
    }

    /**
     * Safe to call from any thread.
     * [full] is the detailed line (with instructions/percentages) shown while the app is
     * in the foreground; [minimal] is the one-word mode name shown while it isn't.
     */
    fun update(full: String, minimal: String) {
        lastFullText = full
        lastMinimalText = minimal
        mainHandler.post {
            statusText?.text = if (isAppForeground) full else minimal
        }
    }

    /** For one-off diagnostic messages (setup errors, download progress) that aren't tied to a gesture mode. */
    fun update(text: String) {
        update(text, text)
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

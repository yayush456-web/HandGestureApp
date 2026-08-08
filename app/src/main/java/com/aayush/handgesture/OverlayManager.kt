package com.aayush.handgesture

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
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
 * Manages three separate always-on-top overlay windows:
 *  1. The status panel (top-right) - camera thumbnail + status line while the app is
 *     foregrounded, shrinking to a minimal mode label otherwise.
 *  2. The cursor reticle - a small hollow ring, shown only in Cursor mode, following the
 *     index fingertip.
 *  3. The Quick Actions HUD - a centered scrolling list, shown only in Quick Actions mode,
 *     highlighting whichever item is currently selected.
 *
 * All updates are marshalled onto the main thread because MediaPipe's result callback
 * fires on a background thread, and Views can only be touched from the UI thread -
 * forgetting this was the cause of the "opens then closes" crash in the first build.
 */
class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // --- status panel ---
    private var container: LinearLayout? = null
    private var statusText: TextView? = null
    private var previewView: PreviewView? = null
    private var isAppForeground = true
    private var lastFullText = "○ idle"
    private var lastMinimalText = "Idle"

    // --- cursor reticle ---
    private var cursorDot: View? = null
    private var cursorParams: WindowManager.LayoutParams? = null
    private var cursorDotSizePx = 0

    // --- quick actions HUD ---
    private var quickActionsPanel: LinearLayout? = null
    private var quickActionsParams: WindowManager.LayoutParams? = null
    private var quickActionsRows: List<TextView> = emptyList()

    private fun overlayType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    /** Safe to call from any thread; hops to main internally. */
    fun show() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show() }
            return
        }
        if (container != null) return
        buildStatusPanel()
        buildCursorDot()
    }

    private fun buildStatusPanel() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.x = 24
        params.y = 120

        val preview = PreviewView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(140), dp(187)) // 3:4 thumbnail
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            visibility = if (isAppForeground) View.VISIBLE else View.GONE
        }

        val status = TextView(context).apply {
            text = if (isAppForeground) lastFullText else lastMinimalText
            setTextColor(Color.parseColor("#FF29F1FF"))
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.02f
            textSize = if (isAppForeground) 12f else 11f
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

    private fun buildCursorDot() {
        cursorDotSizePx = dp(28)
        val dot = View(context).apply {
            setBackgroundResource(R.drawable.bg_cursor_dot)
            visibility = View.GONE
        }
        val dotParams = WindowManager.LayoutParams(
            cursorDotSizePx, cursorDotSizePx,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        cursorDot = dot
        cursorParams = dotParams
        windowManager.addView(dot, dotParams)
    }

    /** Safe to call from any thread. Pass whether the app (any of its activities) is on screen right now. */
    fun setAppForeground(foreground: Boolean) {
        isAppForeground = foreground
        mainHandler.post {
            previewView?.visibility = if (foreground) View.VISIBLE else View.GONE
            statusText?.text = if (foreground) lastFullText else lastMinimalText
            statusText?.textSize = if (foreground) 12f else 11f
        }
    }

    /**
     * Safe to call from any thread.
     * [full] is the detailed line (with instructions/percentages) shown while the app is
     * in the foreground; [minimal] is the mode name (with live level, where relevant) shown
     * while it isn't.
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

    /** Moves the cursor reticle to the given absolute screen coordinates. Caller handles smoothing. */
    fun updateCursorPosition(screenX: Float, screenY: Float) {
        mainHandler.post {
            val dot = cursorDot ?: return@post
            val params = cursorParams ?: return@post
            params.x = (screenX - cursorDotSizePx / 2f).toInt()
            params.y = (screenY - cursorDotSizePx / 2f).toInt()
            try {
                windowManager.updateViewLayout(dot, params)
            } catch (_: Exception) {
            }
        }
    }

    /** Shows or hides the cursor reticle. Call with false when leaving Cursor mode. */
    fun setCursorVisible(visible: Boolean) {
        mainHandler.post {
            cursorDot?.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    /**
     * Shows or hides the centered Quick Actions HUD list, building it fresh from [items]
     * (icon, label) each time it's shown. Only called once per mode entry/exit, so rebuilding
     * a ~7-row list here is cheap.
     */
    fun showQuickActionsHud(visible: Boolean, items: List<Pair<String, String>>) {
        mainHandler.post {
            if (!visible) {
                quickActionsPanel?.let {
                    try {
                        windowManager.removeView(it)
                    } catch (_: Exception) {
                    }
                }
                quickActionsPanel = null
                quickActionsParams = null
                quickActionsRows = emptyList()
                return@post
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.CENTER

            val rows = items.map { (icon, label) ->
                TextView(context).apply {
                    text = "$icon  ${label.uppercase()}"
                    typeface = Typeface.MONOSPACE
                    letterSpacing = 0.05f
                    textSize = 15f
                    setTextColor(Color.parseColor("#9929F1FF"))
                    setPadding(0, dp(4), 0, dp(4))
                }
            }

            val panel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_hud_panel)
                rows.forEach { addView(it) }
            }

            quickActionsPanel = panel
            quickActionsParams = params
            quickActionsRows = rows
            windowManager.addView(panel, params)
        }
    }

    /** Highlights row [index] in the Quick Actions HUD (brighter text), dims the rest. */
    fun updateQuickActionsHighlight(index: Int) {
        mainHandler.post {
            quickActionsRows.forEachIndexed { i, row ->
                if (i == index) {
                    row.setTextColor(Color.parseColor("#FF29F1FF"))
                    row.text = "▶ ${row.text.toString().removePrefix("▶ ")}"
                } else {
                    row.setTextColor(Color.parseColor("#9929F1FF"))
                    row.text = row.text.toString().removePrefix("▶ ")
                }
            }
        }
    }

    /** Returns the PreviewView to bind CameraX's Preview use case to, once the overlay is shown. */
    fun getPreviewView(): PreviewView? = previewView

    fun hide() {
        mainHandler.post {
            container?.let { safeRemove(it) }
            cursorDot?.let { safeRemove(it) }
            quickActionsPanel?.let { safeRemove(it) }
            container = null
            statusText = null
            previewView = null
            cursorDot = null
            cursorParams = null
            quickActionsPanel = null
            quickActionsParams = null
            quickActionsRows = emptyList()
        }
    }

    private fun safeRemove(view: View) {
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
    }
}

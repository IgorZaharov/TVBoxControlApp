package com.tvboxremote.receiver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.tvboxremote.receiver.protocol.PairingDisplay
import com.tvboxremote.receiver.protocol.PairingGate
import com.tvboxremote.receiver.protocol.ReceiverSession
import com.tvboxremote.receiver.protocol.SessionHost
import com.tvboxremote.receiver.server.ClientLink
import com.tvboxremote.receiver.server.LineHandler
import com.tvboxremote.receiver.server.ReceiverServer
import java.io.IOException
import kotlin.concurrent.thread

/**
 * The receiver. While the user keeps this accessibility service on, it:
 * - runs the TCP server of docs/RECEIVER_PROTOCOL.md,
 * - announces the server with Bonjour,
 * - draws the cursor and the pairing code in overlay windows,
 * - taps and swipes the screen with gestures.
 *
 * All overlay and gesture work runs on the main thread.
 */
class CursorAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var cursorView: CursorView? = null
    private var pairingView: PairingCodeView? = null
    private var server: ReceiverServer? = null
    private var advertiser: NsdAdvertiser? = null
    private lateinit var tokens: PrefsTokenStore
    private lateinit var pairingGate: PairingGate

    @Volatile
    private var screenWidth = 1920

    @Volatile
    private var screenHeight = 1080

    // Main thread only.
    private var cursorX = 0f
    private var cursorY = 0f
    private var cursorVisible = false
    private var started = false

    /** Changes each time the gate shows or hides a code, so a stale expiry timer does nothing. */
    private var pairingGeneration = 0

    private val hideCursor = Runnable { setCursorVisible(false) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (started) return
        started = true
        tokens = PrefsTokenStore(this)
        pairingGate = PairingGate(object : PairingDisplay {
            // The gate calls these on connection threads with its lock held. Only post.
            override fun show(phoneName: String, code: String, expiresAtMillis: Long) {
                main.post { showPairing(phoneName, code, expiresAtMillis) }
            }

            override fun hide() {
                main.post { hidePairing() }
            }
        })
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        updateScreenSize()
        cursorX = screenWidth / 2f
        cursorY = screenHeight / 2f
        addCursorOverlay()
        startServer()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenSize()
        cursorX = cursorX.coerceIn(0f, (screenWidth - 1).toFloat())
        cursorY = cursorY.coerceIn(0f, (screenHeight - 1).toFloat())
        cursorView?.update(cursorX, cursorY, cursorVisible)
    }

    // --- Server and announcement -------------------------------------------------

    private fun startServer() {
        val newServer = ReceiverServer(handlerFactory = ::createHandler)
        server = newServer
        ReceiverState.clientCountProvider = { newServer.clientCount }
        // Socket work must not run on the main thread.
        thread(name = "receiver-start", isDaemon = true) {
            val port = try {
                newServer.start()
            } catch (e: IOException) {
                Log.e(TAG, "The server could not start", e)
                return@thread
            }
            main.post {
                if (server !== newServer) {
                    // The service stopped while the server started.
                    thread(isDaemon = true) { newServer.stop() }
                    return@post
                }
                ReceiverState.port = port
                ReceiverState.running = true
                advertiser = NsdAdvertiser(this).also { it.register(boxName(), port) }
            }
        }
    }

    private fun teardown() {
        if (!started) return
        started = false
        advertiser?.unregister()
        advertiser = null
        server?.let { old -> thread(isDaemon = true) { old.stop() } }
        server = null
        ReceiverState.running = false
        ReceiverState.port = -1
        ReceiverState.clientCountProvider = { 0 }
        main.removeCallbacksAndMessages(null)
        pairingGeneration++
        removeView(pairingView)
        pairingView = null
        removeView(cursorView)
        cursorView = null
    }

    private fun boxName(): String {
        val name = try {
            Settings.Global.getString(contentResolver, "device_name")
        } catch (e: SecurityException) {
            null
        }
        return name?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }

    private fun createHandler(link: ClientLink): LineHandler {
        val host = object : SessionHost {
            override fun send(line: String) = link.send(line)
            override fun close() = link.close()
            override fun boxName(): String = this@CursorAccessibilityService.boxName()
            override fun screenWidth(): Int = screenWidth
            override fun screenHeight(): Int = screenHeight

            override fun move(dx: Float, dy: Float) {
                main.post { moveCursor(dx, dy) }
            }

            override fun click() {
                main.post { tap(CLICK_DURATION_MS) }
            }

            override fun longPress() {
                main.post { tap(LONG_PRESS_DURATION_MS) }
            }

            override fun scroll(dx: Float, dy: Float) {
                main.post { swipe(dx, dy) }
            }

            override fun setCursorVisible(visible: Boolean) {
                main.post { this@CursorAccessibilityService.setCursorVisible(visible) }
            }
        }
        val session = ReceiverSession(host, tokens, pairingGate)
        return object : LineHandler {
            override fun onLine(line: String) = session.onLine(line)
            override fun onClosed() = session.onClosed()
        }
    }

    // --- Cursor ---------------------------------------------------------------------

    private fun updateScreenSize() {
        val wm = windowManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                screenWidth = bounds.width()
                screenHeight = bounds.height()
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(metrics)
                screenWidth = metrics.widthPixels
                screenHeight = metrics.heightPixels
            }
        } catch (e: RuntimeException) {
            val metrics = resources.displayMetrics
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
        screenWidth = screenWidth.coerceAtLeast(1)
        screenHeight = screenHeight.coerceAtLeast(1)
    }

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun addCursorOverlay() {
        val view = CursorView(this)
        val params = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        try {
            windowManager?.addView(view, params)
            cursorView = view
        } catch (e: RuntimeException) {
            Log.e(TAG, "The cursor overlay could not open", e)
        }
    }

    private fun moveCursor(dx: Float, dy: Float) {
        cursorX = (cursorX + dx).coerceIn(0f, (screenWidth - 1).toFloat())
        cursorY = (cursorY + dy).coerceIn(0f, (screenHeight - 1).toFloat())
        setCursorVisible(true)
    }

    private fun setCursorVisible(visible: Boolean) {
        cursorVisible = visible
        cursorView?.update(cursorX, cursorY, visible)
        main.removeCallbacks(hideCursor)
        if (visible) main.postDelayed(hideCursor, CURSOR_HIDE_DELAY_MS)
    }

    // --- Gestures -------------------------------------------------------------------

    private fun tap(durationMs: Long) {
        setCursorVisible(true)
        val x = cursorX
        val y = cursorY
        // A stroke needs a non-empty path. Move 1 px, which is far below the touch slop.
        val endX = if (x + 1f <= screenWidth - 1) x + 1f else x - 1f
        val path = Path().apply {
            moveTo(x, y)
            lineTo(endX.coerceAtLeast(0f), y)
        }
        dispatch(path, durationMs)
    }

    private fun swipe(dx: Float, dy: Float) {
        setCursorVisible(true)
        val startX = cursorX
        val startY = cursorY
        val endX = (startX - dx).coerceIn(0f, (screenWidth - 1).toFloat())
        val endY = (startY - dy).coerceIn(0f, (screenHeight - 1).toFloat())
        if (endX == startX && endY == startY) return
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        dispatch(path, SCROLL_DURATION_MS)
    }

    private fun dispatch(path: Path, durationMs: Long) {
        try {
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (e: RuntimeException) {
            Log.w(TAG, "The gesture failed", e)
        }
    }

    // --- Pairing code overlay -------------------------------------------------------

    private fun showPairing(phoneName: String, code: String, expiresAtMillis: Long) {
        if (!started) return
        val generation = ++pairingGeneration
        val delay = (expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(0)
        main.postDelayed({ if (pairingGeneration == generation) hidePairing() }, delay)
        val view = pairingView ?: PairingCodeView(this).also { newView ->
            val params = overlayParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (48 * resources.displayMetrics.density).toInt()
            }
            try {
                windowManager?.addView(newView, params)
                pairingView = newView
            } catch (e: RuntimeException) {
                Log.e(TAG, "The pairing overlay could not open", e)
                return
            }
        }
        view.show(code, phoneName)
    }

    private fun hidePairing() {
        pairingGeneration++
        removeView(pairingView)
        pairingView = null
    }

    private fun removeView(view: android.view.View?) {
        if (view == null) return
        try {
            windowManager?.removeView(view)
        } catch (e: RuntimeException) {
            // The view was not attached.
        }
    }

    private companion object {
        const val TAG = "CursorService"
        const val CLICK_DURATION_MS = 40L
        const val LONG_PRESS_DURATION_MS = 600L
        const val SCROLL_DURATION_MS = 250L
        const val CURSOR_HIDE_DELAY_MS = 8_000L
    }
}

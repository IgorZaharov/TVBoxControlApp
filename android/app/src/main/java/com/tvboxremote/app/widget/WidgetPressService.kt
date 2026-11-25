package com.tvboxremote.app.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.tvboxremote.app.R
import com.tvboxremote.app.data.AppStore
import com.tvboxremote.app.remote.RemoteKey
import com.tvboxremote.app.withAppLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sends the widget key presses. A tap on a widget button starts this short foreground service.
 *
 * A plain broadcast was not enough: with the app closed, Android (and the battery savers of some phone makers)
 * blocks the network of an app in the background. The presses then waited until the app opened. A foreground
 * service started from a widget tap is allowed, and its process keeps the network.
 *
 * The service stops [IDLE_STOP_MS] after the last press. Android shows the notification of a foreground service only
 * after about 10 seconds, so a normal use of the widget shows none.
 */
class WidgetPressService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var stopJob: Job? = null
    private var lastPress: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A service started with startForegroundService must call startForeground first, also for a bad intent.
        goForeground()
        stopJob?.cancel()
        val app = applicationContext
        val deviceId = intent?.getStringExtra(RemoteWidget.EXTRA_DEVICE)
        val key = intent?.getStringExtra(RemoteWidget.EXTRA_KEY)
            ?.let { name -> RemoteKey.entries.firstOrNull { it.name == name } }
        val device = deviceId?.let { id -> AppStore(app).savedDevices.firstOrNull { it.id == id } }
        if (device != null && key != null) {
            lastPress = scope.launch {
                try {
                    // A press that waits too long behind a slow connection is dropped; it does not show as a failure.
                    val sent = withTimeoutOrNull(PRESS_TIMEOUT_MS) { WidgetSender.send(app, device, key) }
                    if (sent != null && RemoteWidget.setFailed(app, device.id, !sent)) RemoteWidget.updateAll(app)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // For example a permission that was revoked during the press.
                    runCatching { if (RemoteWidget.setFailed(app, device.id, true)) RemoteWidget.updateAll(app) }
                }
            }
        }
        val press = lastPress
        stopJob = scope.launch {
            // WidgetSender sends one press at a time, so the newest press finishes last.
            press?.join()
            delay(IDLE_STOP_MS)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val context = applicationContext.withAppLanguage()
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.widget_channel), NotificationManager.IMPORTANCE_MIN),
            )
        }
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.widget_sending))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** A short service must stop in about 3 minutes. The idle stop comes much earlier; this is the safety net. */
    override fun onTimeout(startId: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "widget"
        private const val NOTIFICATION_ID = 1
        private const val PRESS_TIMEOUT_MS = 9_000L

        /** Less than the 10 s after which Android shows the notification. */
        const val IDLE_STOP_MS = 8_000L

        fun intent(context: Context): Intent = Intent(context, WidgetPressService::class.java)
    }
}

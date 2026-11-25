package com.tvboxremote.app.widget

import com.tvboxremote.app.withAppLanguage
import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import com.tvboxremote.app.MainActivity
import com.tvboxremote.app.R
import com.tvboxremote.app.bluetooth.BluetoothHidRemote
import com.tvboxremote.app.data.AppStore
import com.tvboxremote.app.remote.RemoteKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The home screen remote (docs/UI_SPEC.md, "Home screen widget").
 *
 * - No saved device: a card that opens the app.
 * - Android 12 and later, two or more devices: a card stack (StackView). Swipe up or down for the next device.
 * - Otherwise: one card. On Android 9 to 11 with two or more devices, a tap on the name goes to the next device.
 *
 * A key press starts [WidgetPressService], which sends the key. The app does not open.
 */
open class RemoteWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { RemoteWidget.render(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        RemoteWidget.render(context, manager, id)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        RemoteWidget.forget(context, ids)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            RemoteWidget.ACTION_KEY -> {
                val deviceId = intent.getStringExtra(RemoteWidget.EXTRA_DEVICE) ?: return
                val key = intent.getStringExtra(RemoteWidget.EXTRA_KEY)
                    ?.let { name -> RemoteKey.entries.firstOrNull { it.name == name } } ?: return
                val device = AppStore(context).savedDevices.firstOrNull { it.id == deviceId } ?: return
                val app = context.applicationContext
                val pending = goAsync()
                RemoteWidget.scope.launch {
                    try {
                        // A broadcast must finish in time, also when presses wait behind a slow connection.
                        // A press that times out while it waits is dropped; it does not show as a failure.
                        val sent = withTimeoutOrNull(9_000) { WidgetSender.send(app, device, key) }
                        if (sent != null && RemoteWidget.setFailed(app, device.id, !sent)) RemoteWidget.updateAll(app)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // For example a permission that was revoked during the press.
                        runCatching { if (RemoteWidget.setFailed(app, device.id, true)) RemoteWidget.updateAll(app) }
                    } finally {
                        pending.finish()
                    }
                }
            }
            RemoteWidget.ACTION_NEXT -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    RemoteWidget.next(context, id)
                    RemoteWidget.render(context, AppWidgetManager.getInstance(context), id)
                }
            }
            else -> super.onReceive(context, intent)
        }
    }
}

/** The compact widget (4 x 2 cells): the same remote, with the D-pad on the left and the other buttons on the right. */
class RemoteWidgetCompactProvider : RemoteWidgetProvider()

object RemoteWidget {
    const val ACTION_KEY = "com.tvboxremote.app.widget.KEY"
    const val ACTION_NEXT = "com.tvboxremote.app.widget.NEXT"
    const val EXTRA_DEVICE = "device"
    const val EXTRA_KEY = "key"

    /** A "Cannot reach" line shows for this long after a failed press. */
    private const val FAILURE_SHOWN_MS = 60_000L

    /** The card stack is this many times one card, so that the cards behind fall outside the widget. */
    private const val STACK_SCALE = 4f

    /** StackView puts each card in a frame with this padding on every side. */
    private const val FRAME_PADDING_DP = 4f

    /** A widget lower than this uses the compact card: the D-pad on the left, the other buttons on the right. */
    private const val COMPACT_HEIGHT_DP = 150f

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val BUTTONS = listOf(
        R.id.w_up to RemoteKey.UP,
        R.id.w_down to RemoteKey.DOWN,
        R.id.w_left to RemoteKey.LEFT,
        R.id.w_right to RemoteKey.RIGHT,
        R.id.w_ok to RemoteKey.OK,
        R.id.w_power to RemoteKey.POWER,
        R.id.w_back to RemoteKey.BACK,
        R.id.w_home to RemoteKey.HOME,
    )

    private fun prefs(context: Context) = context.getSharedPreferences("widget", Context.MODE_PRIVATE)

    /** Redraws every widget. Call when the saved devices change. */
    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        listOf(RemoteWidgetProvider::class.java, RemoteWidgetCompactProvider::class.java).forEach { provider ->
            manager.getAppWidgetIds(ComponentName(context, provider)).forEach { render(context, manager, it) }
        }
    }

    fun render(appContext: Context, manager: AppWidgetManager, id: Int) {
        // Strings in the app language (Preferences), also on Android 12 and earlier.
        val context = appContext.withAppLanguage()
        val devices = usableDevices(context)
        val options = manager.getAppWidgetOptions(id)
        val compact = widgetSize(context, options).height < COMPACT_HEIGHT_DP
        val views = when {
            devices.isEmpty() -> emptyCard(context)
            devices.size >= 2 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                stack(context, id, devices, options, compact)
            else -> {
                val index = prefs(context).getInt("index_$id", 0).mod(devices.size)
                singleCard(context, id, devices[index], canCycle = devices.size >= 2, compact = compact)
            }
        }
        manager.updateAppWidget(id, views)
    }

    /** Android 9 to 11: the next device for one widget. */
    fun next(context: Context, id: Int) {
        val p = prefs(context)
        p.edit().putInt("index_$id", p.getInt("index_$id", 0) + 1).apply()
    }

    fun forget(context: Context, ids: IntArray) {
        val editor = prefs(context).edit()
        ids.forEach { editor.remove("index_$it") }
        editor.apply()
    }

    /** Stores whether the last press on [deviceId] failed. Returns true if the shown state changed. */
    fun setFailed(context: Context, deviceId: String, failed: Boolean): Boolean {
        val p = prefs(context)
        val key = "failed_$deviceId"
        val wasShown = failureShown(context, deviceId)
        if (failed) p.edit().putLong(key, System.currentTimeMillis()).apply() else p.edit().remove(key).apply()
        return failed || wasShown
    }

    private fun failureShown(context: Context, deviceId: String): Boolean {
        val at = prefs(context).getLong("failed_$deviceId", 0L)
        return at > 0 && System.currentTimeMillis() - at < FAILURE_SHOWN_MS
    }

    /**
     * The saved devices that the widget can use: Wi-Fi boxes that are still paired,
     * and Bluetooth devices that are still bonded (if the app may read the bonded list).
     */
    private fun usableDevices(context: Context): List<SavedDevice> {
        val store = AppStore(context)
        val canReadBonded = Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val bonded = if (canReadBonded) {
            BluetoothHidRemote.get(context).bondedDevices().map { it.address }.toSet()
        } else {
            null
        }
        return store.savedDevices.filter { d ->
            when {
                d.isBluetooth -> bonded == null || d.address in bonded
                else -> d.host?.let { store.fingerprint(it) } != null
            }
        }
    }

    // ---------------------------------------------------------------- views

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun emptyCard(context: Context): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_empty).apply {
            setOnClickPendingIntent(R.id.w_empty_root, openAppIntent(context))
        }

    /** The card of one device with its own click intents (one card, no stack). */
    private fun singleCard(context: Context, widgetId: Int, device: SavedDevice, canCycle: Boolean, compact: Boolean): RemoteViews {
        val views = card(context, device, compact)
        BUTTONS.forEach { (viewId, key) ->
            val intent = WidgetPressService.intent(context)
                .setAction(ACTION_KEY)
                // A distinct data URI per widget and key, so that each button gets its own PendingIntent.
                .setData(Uri.parse("tvboxremote://widget/$widgetId/${key.name}"))
                .putExtra(EXTRA_DEVICE, device.id)
                .putExtra(EXTRA_KEY, key.name)
            views.setOnClickPendingIntent(
                viewId,
                PendingIntent.getForegroundService(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
        }
        views.setOnClickPendingIntent(R.id.w_open, openAppIntent(context))
        if (canCycle) {
            views.setViewVisibility(R.id.w_next, View.VISIBLE)
            val next = Intent(context, RemoteWidgetProvider::class.java)
                .setAction(ACTION_NEXT)
                .setData(Uri.parse("tvboxremote://widget/$widgetId/next"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            views.setOnClickPendingIntent(
                R.id.w_header,
                PendingIntent.getBroadcast(context, 0, next, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
        }
        return views
    }

    /** The card stack: one card per device. Each button fills in its device and key into one broadcast template. */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun stack(context: Context, widgetId: Int, devices: List<SavedDevice>, options: Bundle?, compact: Boolean): RemoteViews {
        val size = widgetSize(context, options)
        val items = RemoteViews.RemoteCollectionItems.Builder()
            .setHasStableIds(true)
            .setViewTypeCount(1)
        devices.forEach { device ->
            val card = card(context, device, compact)
            BUTTONS.forEach { (viewId, key) ->
                card.setOnClickFillInIntent(viewId, Intent().putExtra(EXTRA_DEVICE, device.id).putExtra(EXTRA_KEY, key.name))
            }
            // The app button sits on the frame above the stack. Collection items can only send the one template.
            card.setViewVisibility(R.id.w_open, View.INVISIBLE)
            // Exactly the widget's size: StackView measures cards "at most" and would shrink them.
            card.setViewLayoutWidth(R.id.w_card_root, size.width, TypedValue.COMPLEX_UNIT_DIP)
            card.setViewLayoutHeight(R.id.w_card_root, size.height, TypedValue.COMPLEX_UNIT_DIP)
            items.addItem(device.id.hashCode().toLong(), card)
        }
        val views = RemoteViews(context.packageName, R.layout.widget_stack)
        views.setRemoteAdapter(R.id.w_stack, items.build())
        val template = WidgetPressService.intent(context)
            .setAction(ACTION_KEY)
            .setData(Uri.parse("tvboxremote://widget/$widgetId/stack"))
        views.setPendingIntentTemplate(
            R.id.w_stack,
            // Mutable: each button fills in its device and key.
            PendingIntent.getForegroundService(context, 0, template, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE),
        )
        views.setOnClickPendingIntent(R.id.w_stack_open, openAppIntent(context))

        // The front card is at the bottom left of the stack. A stack STACK_SCALE times the card puts the cards
        // behind it a full card away, outside the widget, so they do not show. Move the stack up to put the front
        // card exactly on the widget.
        val pad = FRAME_PADDING_DP
        val frameW = size.width + 2 * pad
        val frameH = size.height + 2 * pad
        val dip = TypedValue.COMPLEX_UNIT_DIP
        views.setViewLayoutWidth(R.id.w_stack, frameW * STACK_SCALE, dip)
        views.setViewLayoutHeight(R.id.w_stack, frameH * STACK_SCALE, dip)
        views.setViewLayoutMargin(R.id.w_stack, RemoteViews.MARGIN_LEFT, -pad, dip)
        views.setViewLayoutMargin(R.id.w_stack, RemoteViews.MARGIN_TOP, -(frameH * STACK_SCALE - frameH) - pad, dip)
        return views
    }

    /** The card content of one device, without click intents. */
    private fun card(context: Context, device: SavedDevice, compact: Boolean): RemoteViews =
        RemoteViews(context.packageName, if (compact) R.layout.widget_card_compact else R.layout.widget_card).apply {
            setTextViewText(R.id.w_name, device.name)
            if (failureShown(context, device.id)) {
                setTextViewText(R.id.w_message, context.getString(R.string.widget_cannot_reach, device.name))
                setViewVisibility(R.id.w_message, View.VISIBLE)
            }
            setContentDescription(R.id.w_up, context.getString(R.string.key_up))
            setContentDescription(R.id.w_down, context.getString(R.string.key_down))
            setContentDescription(R.id.w_left, context.getString(R.string.key_left))
            setContentDescription(R.id.w_right, context.getString(R.string.key_right))
            setContentDescription(R.id.w_ok, context.getString(R.string.key_ok))
            setContentDescription(R.id.w_power, context.getString(R.string.key_power))
            setContentDescription(R.id.w_back, context.getString(R.string.key_back))
            setContentDescription(R.id.w_home, context.getString(R.string.key_home))
            setContentDescription(R.id.w_open, context.getString(R.string.widget_open_app))
            setContentDescription(R.id.w_next, context.getString(R.string.widget_next_device))
        }

    private data class SizeDp(val width: Float, val height: Float)

    /**
     * The widget size in dp in the current orientation. Android 12 and later list the exact sizes:
     * portrait is the narrowest entry, landscape the widest.
     */
    private fun widgetSize(context: Context, options: Bundle?): SizeDp {
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // The size list needs Android 12, and its typed getter Android 13.
        @Suppress("DEPRECATION")
        val sizes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            options?.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                ?.filter { it.width > 0f && it.height > 0f }
        } else {
            null
        }
        if (!sizes.isNullOrEmpty()) {
            val s = if (landscape) sizes.maxBy { it.width } else sizes.minBy { it.width }
            return SizeDp(s.width, s.height)
        }
        fun opt(key: String) = options?.getInt(key, 0) ?: 0
        val w = opt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val h = opt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        return if (w > 0 && h > 0) SizeDp(w.toFloat(), h.toFloat()) else SizeDp(250f, 250f)
    }
}

package com.tvboxremote.receiver

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/** The receiver screen on the TV: status, settings shortcut, and instructions. */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var pairedText: TextView
    private lateinit var tokens: PrefsTokenStore

    private val refresher = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tokens = PrefsTokenStore(this)
        statusText = findViewById(R.id.status_text)
        pairedText = findViewById(R.id.paired_text)

        val openSettings = findViewById<Button>(R.id.open_settings_button)
        openSettings.setOnClickListener { openAccessibilitySettings() }
        findViewById<Button>(R.id.forget_button).setOnClickListener {
            tokens.clear()
            Toast.makeText(this, R.string.forget_done, Toast.LENGTH_LONG).show()
            refreshStatus()
        }
        openSettings.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun refreshStatus() {
        val enabled = isServiceEnabled()
        statusText.text = when {
            ReceiverState.running -> getString(
                R.string.status_running,
                ReceiverState.localIpv4Address() ?: getString(R.string.status_no_address),
                ReceiverState.port,
                ReceiverState.clientCountProvider(),
            )
            enabled -> getString(R.string.status_starting)
            else -> getString(R.string.status_off)
        }
        pairedText.text = getString(R.string.paired_count, tokens.count())
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val component = ComponentName(this, CursorAccessibilityService::class.java)
        return enabled.split(':').any {
            ComponentName.unflattenFromString(it) == component
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.settings_missing, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 2_000L
    }
}

package com.tvboxremote.receiver

import android.content.Context
import com.tvboxremote.receiver.protocol.Secrets
import com.tvboxremote.receiver.protocol.TokenStore

/**
 * Keeps the tokens of paired phones in SharedPreferences. It stores only the
 * SHA-256 hash of each token, with the phone name as the value.
 */
class PrefsTokenStore(context: Context) : TokenStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun isValid(token: String): Boolean = prefs.contains(Secrets.sha256Hex(token))

    override fun add(token: String, phoneName: String) {
        prefs.edit().putString(Secrets.sha256Hex(token), phoneName).apply()
    }

    fun count(): Int = prefs.all.size

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "paired_phones"
    }
}

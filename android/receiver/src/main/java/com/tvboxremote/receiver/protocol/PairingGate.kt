package com.tvboxremote.receiver.protocol

import java.security.MessageDigest

/** Shows or hides the one pairing code on the TV. Calls come with the gate lock held: do not block. */
interface PairingDisplay {
    fun show(phoneName: String, code: String, expiresAtMillis: Long)
    fun hide()
}

/**
 * The pairing state of the whole receiver, shared by all connections.
 *
 * - There is at most one active code. A phone that asks for pairing while a code
 *   is active gets the same code and the remaining tries.
 * - Wrong codes count across all connections. A new connection does not reset the count.
 * - After [MAX_WRONG_CODES] wrong codes, pairing is locked for [LOCK_DURATION_MS].
 * - Wrong codes older than [LOCK_DURATION_MS] are forgotten.
 *
 * Thread-safe. It has no Android dependencies.
 */
class PairingGate(
    private val display: PairingDisplay,
    private val clock: () -> Long = System::currentTimeMillis,
    private val codeGenerator: () -> String = Secrets::pairingCode,
) {
    sealed interface Request {
        data class Code(val expiresAtMillis: Long) : Request
        data class Locked(val untilMillis: Long) : Request
    }

    sealed interface Submit {
        object Correct : Submit
        data class Wrong(val triesLeft: Int) : Submit
        data class NowLocked(val untilMillis: Long) : Submit
        data class Locked(val untilMillis: Long) : Submit

        /** The code expired, or another phone used it. Ask for a new code. */
        object NoCode : Submit
    }

    private val lock = Any()
    private var code: String? = null
    private var codeExpiresAt = 0L
    private var codePhoneName = ""
    private var wrongCodes = 0
    private var lastWrongAt = 0L
    private var lockedUntil = 0L
    private val waiters = HashSet<Any>()

    /** The phone of [key] wants to pair. Shows the active code, or a new one. */
    fun request(key: Any, phoneName: String): Request = synchronized(lock) {
        val now = clock()
        if (now < lockedUntil) return Request.Locked(lockedUntil)
        dropStaleState(now)
        val active = code
        if (active == null) {
            val newCode = codeGenerator()
            code = newCode
            codeExpiresAt = now + CODE_LIFETIME_MS
            codePhoneName = phoneName
        }
        waiters.add(key)
        display.show(codePhoneName, code!!, codeExpiresAt)
        Request.Code(codeExpiresAt)
    }

    /** The phone of [key] typed [entered]. */
    fun submit(key: Any, entered: String): Submit = synchronized(lock) {
        val now = clock()
        if (now < lockedUntil) return Submit.Locked(lockedUntil)
        dropStaleState(now)
        val active = code ?: return Submit.NoCode
        if (constantTimeEquals(entered, active)) {
            clearCode()
            wrongCodes = 0
            waiters.remove(key)
            return Submit.Correct
        }
        wrongCodes++
        lastWrongAt = now
        if (wrongCodes >= MAX_WRONG_CODES) {
            lockedUntil = now + LOCK_DURATION_MS
            wrongCodes = 0
            clearCode()
            waiters.remove(key)
            return Submit.NowLocked(lockedUntil)
        }
        Submit.Wrong(MAX_WRONG_CODES - wrongCodes)
    }

    /** The phone of [key] no longer waits (closed, paired, or locked out). The code stays active. */
    fun leave(key: Any) = synchronized(lock) {
        if (waiters.remove(key) && waiters.isEmpty() && code != null) display.hide()
    }

    /** For tests and the status screen. */
    val wrongCodeCount: Int get() = synchronized(lock) { wrongCodes }

    private fun dropStaleState(now: Long) {
        if (code != null && now > codeExpiresAt) clearCode()
        if (wrongCodes > 0 && now - lastWrongAt > LOCK_DURATION_MS) wrongCodes = 0
    }

    private fun clearCode() {
        code = null
        display.hide()
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    companion object {
        const val CODE_LIFETIME_MS = 2 * 60 * 1000L
        const val MAX_WRONG_CODES = 5
        const val LOCK_DURATION_MS = 5 * 60 * 1000L
    }
}

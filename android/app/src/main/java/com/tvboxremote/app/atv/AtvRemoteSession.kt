package com.tvboxremote.app.atv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

data class VolumeState(val level: Int, val max: Int, val muted: Boolean)

/**
 * The control session on port 6466. It answers the box's configure, set-active
 * and ping messages, and sends keys and text.
 */
class AtvRemoteSession(
    private val identity: ClientIdentity,
    val host: String,
    private val port: Int,
    private val expectedFingerprint: String,
    private val model: String,
    private val vendor: String,
    private val appVersion: String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connection: AtvConnection? = null
    private val outbox = Channel<ByteArray>(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @Volatile private var imeCounter = 0
    @Volatile private var fieldCounter = 0

    private val _ready = MutableStateFlow(false)
    /** True after the box sent set-active. Keys sent before this can be lost. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _powerOn = MutableStateFlow<Boolean?>(null)
    val powerOn: StateFlow<Boolean?> = _powerOn.asStateFlow()

    private val _volume = MutableStateFlow<VolumeState?>(null)
    val volume: StateFlow<VolumeState?> = _volume.asStateFlow()

    private val _imeShown = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Emits when the box opens its on-screen keyboard. */
    val imeShown: SharedFlow<Unit> = _imeShown.asSharedFlow()

    private val _closed = MutableStateFlow<Throwable?>(null)
    /** Set to the reason when the session ends. */
    val closed: StateFlow<Throwable?> = _closed.asStateFlow()

    /** Connects and starts the reader. Throws if the TLS handshake or the pin check fails. */
    suspend fun start() {
        val c = AtvConnection.openCancellable(identity, host, port, expectedFingerprint)
        // The box pings about every 5 seconds. No data for 16 seconds means the link is dead.
        c.setReadTimeout(16_000)
        connection = c
        scope.launch { readLoop(c) }
        scope.launch { writeLoop(c) }
    }

    private fun readLoop(c: AtvConnection) {
        try {
            while (true) handle(c, c.receive())
        } catch (e: Exception) {
            finish(if (e is IOException) e else IOException(e))
        }
    }

    private fun handle(c: AtvConnection, m: ProtoMessage) {
        when {
            m.has(Remote.F_CONFIGURE) -> c.send(Remote.configure(model, vendor, appVersion))
            m.has(Remote.F_SET_ACTIVE) -> {
                c.send(Remote.setActive())
                _ready.value = true
            }
            m.has(Remote.F_PING_REQUEST) -> c.send(Remote.pingResponse(m.message(Remote.F_PING_REQUEST)?.int(1) ?: 0))
            m.has(Remote.F_START) -> _powerOn.value = m.message(Remote.F_START)?.bool(1) ?: false
            m.has(Remote.F_SET_VOLUME_LEVEL) -> m.message(Remote.F_SET_VOLUME_LEVEL)?.let {
                _volume.value = VolumeState(level = it.int(7), max = it.int(6), muted = it.bool(8))
            }
            m.has(Remote.F_IME_BATCH_EDIT) -> m.message(Remote.F_IME_BATCH_EDIT)?.let {
                imeCounter = it.int(1)
                fieldCounter = it.int(2)
            }
            m.has(Remote.F_IME_SHOW_REQUEST) -> _imeShown.tryEmit(Unit)
            m.has(Remote.F_ERROR) -> Unit // The box reports a bad message. Keep the session.
        }
    }

    fun sendKey(keyCode: Int, direction: Int = Remote.DIRECTION_SHORT) = post(Remote.key(keyCode, direction))

    /** Replaces the text of the focused field on the box. */
    fun sendText(text: String) = post(Remote.text(imeCounter, fieldCounter, text))

    fun launchAppLink(link: String) = post(Remote.appLink(link))

    /** Messages go through one queue, so the box gets them in order. */
    private fun post(message: ByteArray) {
        outbox.trySend(message)
    }

    private suspend fun writeLoop(c: AtvConnection) {
        try {
            for (message in outbox) c.send(message)
        } catch (e: IOException) {
            finish(e)
        }
    }

    private fun finish(reason: Throwable) {
        if (_closed.value == null) _closed.value = reason
        _ready.value = false
        outbox.close()
        connection?.close()
    }

    fun close() {
        finish(IOException("Closed"))
        scope.cancel()
    }
}

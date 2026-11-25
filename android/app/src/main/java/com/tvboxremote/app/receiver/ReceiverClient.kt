package com.tvboxremote.app.receiver

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** The state of the link to the box receiver. See docs/RECEIVER_PROTOCOL.md. */
sealed interface ReceiverState {
    data object Disconnected : ReceiverState
    data object Connecting : ReceiverState
    /**
     * The box shows a 4-digit code. [wrongCode] is true after a wrong try.
     * [attempt] grows with each request, so that two equal requests are still two state changes.
     */
    data class PairRequired(val wrongCode: Boolean, val attempt: Int) : ReceiverState
    data class Ready(val boxName: String, val width: Int, val height: Int) : ReceiverState
    /** [message] is a string resource. [detail] is the technical cause, if any. */
    data class Failed(@androidx.annotation.StringRes val message: Int, val detail: String? = null) : ReceiverState
}

/**
 * The phone side of the receiver protocol. Messages go through one queue, so
 * they arrive in order. If the queue is full, the oldest message is dropped.
 */
class ReceiverClient(
    private val host: String,
    private val port: Int,
    private val phoneName: String,
    private val token: String?,
    private val onToken: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outbox = Channel<JSONObject>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    @Volatile private var socket: Socket? = null
    @Volatile private var closed = false
    private var pairAttempt = 0

    private val _state = MutableStateFlow<ReceiverState>(ReceiverState.Disconnected)
    val state: StateFlow<ReceiverState> = _state.asStateFlow()

    fun start() {
        _state.value = ReceiverState.Connecting
        scope.launch {
            try {
                val s = Socket()
                // Set the field before connect, so that close() can stop a connect in progress.
                socket = s
                if (closed) return@launch
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), 4000)
                if (closed) { runCatching { s.close() }; return@launch }
                // The receiver answers each ping, so 15 seconds of silence means the link is dead.
                s.soTimeout = 15_000
                val out = s.getOutputStream()
                write(out, JSONObject().put("t", "hello").put("v", 1).put("name", phoneName).apply {
                    if (token != null) put("token", token)
                })
                launch { writeLoop(out) }
                launch { pingLoop() }
                readLoop(BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8)))
            } catch (e: Exception) {
                fail(e.message ?: "Connection failed")
            }
        }
    }

    private fun readLoop(reader: BufferedReader) {
        while (true) {
            val line = reader.readLine() ?: throw java.io.IOException("Receiver closed the connection")
            if (line.length > 4096) continue
            val msg = runCatching { JSONObject(line) }.getOrNull() ?: continue
            when (msg.optString("t")) {
                "welcome" -> _state.value = ReceiverState.Ready(
                    boxName = msg.optString("name"),
                    width = msg.optInt("w"),
                    height = msg.optInt("h"),
                )
                "pair_required" -> _state.value = ReceiverState.PairRequired(wrongCode = false, attempt = ++pairAttempt)
                "paired" -> msg.optString("token").takeIf { it.isNotEmpty() }?.let(onToken)
                "error" -> when (msg.optString("code")) {
                    "bad_code" -> _state.value = ReceiverState.PairRequired(wrongCode = true, attempt = ++pairAttempt)
                    "pairing_locked" -> _state.value =
                        ReceiverState.Failed(com.tvboxremote.app.R.string.receiver_locked)
                    "too_many_clients" -> _state.value =
                        ReceiverState.Failed(com.tvboxremote.app.R.string.receiver_full)
                    else -> Unit
                }
            }
        }
    }

    private suspend fun writeLoop(out: OutputStream) {
        try {
            for (msg in outbox) write(out, msg)
        } catch (e: java.io.IOException) {
            // An uncaught error here would crash the app, because the scope has no handler.
            fail(e.message ?: "Connection lost")
        }
    }

    private suspend fun pingLoop() {
        while (scope.isActive) {
            delay(5000)
            send(JSONObject().put("t", "ping"))
        }
    }

    private fun write(out: OutputStream, msg: JSONObject) {
        out.write((msg.toString() + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun send(msg: JSONObject) {
        outbox.trySend(msg)
    }

    private val isReady get() = _state.value is ReceiverState.Ready

    fun pair(code: String) = send(JSONObject().put("t", "pair").put("code", code))

    fun move(dx: Float, dy: Float) {
        if (isReady) send(JSONObject().put("t", "move").put("dx", dx.toDouble()).put("dy", dy.toDouble()))
    }

    fun click() { if (isReady) send(JSONObject().put("t", "click")) }

    fun longPress() { if (isReady) send(JSONObject().put("t", "longpress")) }

    fun scroll(dx: Float, dy: Float) {
        if (isReady) send(JSONObject().put("t", "scroll").put("dx", dx.toDouble()).put("dy", dy.toDouble()))
    }

    fun showCursor(show: Boolean) { if (isReady) send(JSONObject().put("t", "cursor").put("show", show)) }

    /** Ends the link. A Failed state set before (for example by pairing_locked) keeps its message. */
    private fun fail(message: String) {
        val current = _state.value
        if (current !is ReceiverState.Disconnected && current !is ReceiverState.Failed) {
            _state.value = ReceiverState.Failed(com.tvboxremote.app.R.string.receiver_failed, message)
        }
        close(keepState = true)
    }

    fun close(keepState: Boolean = false) {
        closed = true
        if (!keepState) _state.value = ReceiverState.Disconnected
        outbox.close()
        runCatching { socket?.close() }
        scope.cancel()
    }
}

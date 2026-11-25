package com.tvboxremote.receiver.protocol

import java.security.MessageDigest
import java.security.SecureRandom

/** Stores the tokens of paired phones. Implementations must be thread-safe. */
interface TokenStore {
    fun isValid(token: String): Boolean
    fun add(token: String, phoneName: String)
}

/**
 * What a session needs from the outside world. The session calls these
 * methods on the thread that calls [ReceiverSession.onLine].
 */
interface SessionHost {
    fun send(line: String)
    fun close()
    fun boxName(): String
    fun screenWidth(): Int
    fun screenHeight(): Int
    fun move(dx: Float, dy: Float)
    fun click()
    fun longPress()
    fun scroll(dx: Float, dy: Float)
    fun setCursorVisible(visible: Boolean)
}

/**
 * The protocol state of one phone connection. It has no Android dependencies,
 * so JVM unit tests can drive it. One instance serves one connection and is
 * not thread-safe. The [gate] is shared by all sessions.
 */
class ReceiverSession(
    private val host: SessionHost,
    private val tokens: TokenStore,
    private val gate: PairingGate,
    private val tokenGenerator: () -> String = Secrets::token,
) {
    enum class State { AWAIT_HELLO, AWAIT_PAIR, AUTHORIZED, CLOSED }

    var state: State = State.AWAIT_HELLO
        private set

    var phoneName: String = MessageParser.DEFAULT_PHONE_NAME
        private set

    /** Identifies this session in the gate. */
    private val key = Any()

    fun onLine(line: String) {
        if (state == State.CLOSED) return
        val message = MessageParser.parse(line)
        if (message == null) {
            host.send(ServerMessages.error(ErrorCodes.BAD_MESSAGE, "The line is not a valid message."))
            return
        }
        handle(message)
    }

    /** Call when the connection closed for any reason. */
    fun onClosed() {
        if (state == State.AWAIT_PAIR) gate.leave(key)
        state = State.CLOSED
    }

    private fun handle(message: ClientMessage) {
        when (message) {
            is ClientMessage.Ping -> host.send(ServerMessages.pong())
            is ClientMessage.Hello -> onHello(message)
            is ClientMessage.PairCode -> onPair(message.code)
            is ClientMessage.Unknown -> Unit
            else -> if (state == State.AUTHORIZED) onCommand(message)
        }
    }

    private fun onHello(hello: ClientMessage.Hello) {
        if (state != State.AWAIT_HELLO) return
        phoneName = hello.name
        val token = hello.token
        if (token != null && tokens.isValid(token)) {
            authorize()
        } else {
            requestCode()
        }
    }

    private fun requestCode() {
        when (val result = gate.request(key, phoneName)) {
            is PairingGate.Request.Code -> {
                state = State.AWAIT_PAIR
                host.send(ServerMessages.pairRequired())
            }
            is PairingGate.Request.Locked -> lockOut()
        }
    }

    private fun onPair(entered: String) {
        if (state != State.AWAIT_PAIR) {
            host.send(ServerMessages.error(ErrorCodes.NOT_PAIRING, "Pairing is not in progress."))
            return
        }
        when (val result = gate.submit(key, entered)) {
            PairingGate.Submit.Correct -> {
                val token = tokenGenerator()
                tokens.add(token, phoneName)
                host.send(ServerMessages.paired(token))
                authorize()
            }
            is PairingGate.Submit.Wrong ->
                host.send(ServerMessages.error(ErrorCodes.BAD_CODE, "The code is not correct."))
            is PairingGate.Submit.NowLocked, is PairingGate.Submit.Locked -> lockOut()
            PairingGate.Submit.NoCode -> {
                host.send(ServerMessages.error(ErrorCodes.CODE_EXPIRED, "The code expired. A new code is on the TV."))
                requestCode()
            }
        }
    }

    private fun lockOut() {
        val wasWaiting = state == State.AWAIT_PAIR
        state = State.CLOSED
        if (wasWaiting) gate.leave(key)
        host.send(ServerMessages.error(ErrorCodes.PAIRING_LOCKED, "Too many wrong codes. Pairing is locked for a few minutes."))
        host.close()
    }

    private fun authorize() {
        if (state == State.AWAIT_PAIR) gate.leave(key)
        state = State.AUTHORIZED
        host.send(ServerMessages.welcome(host.boxName(), host.screenWidth(), host.screenHeight()))
    }

    private fun onCommand(message: ClientMessage) {
        when (message) {
            is ClientMessage.Move -> host.move(message.dx, message.dy)
            is ClientMessage.Click -> host.click()
            is ClientMessage.LongPress -> host.longPress()
            is ClientMessage.Scroll -> host.scroll(message.dx, message.dy)
            is ClientMessage.Cursor -> host.setCursorVisible(message.show)
            else -> Unit
        }
    }
}

object Secrets {
    private val random = SecureRandom()

    /** Four random digits, for example "0427". */
    fun pairingCode(): String = random.nextInt(10_000).toString().padStart(4, '0')

    /** 32 random bytes as 64 lower-case hexadecimal characters. */
    fun token(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.toHex()
    }

    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

package com.tvboxremote.receiver.protocol

import org.json.JSONException
import org.json.JSONObject

/** Protocol version that this receiver speaks. */
const val PROTOCOL_VERSION = 1

/** A message from the phone. See docs/RECEIVER_PROTOCOL.md. */
sealed class ClientMessage {
    data class Hello(val version: Int, val name: String, val token: String?) : ClientMessage()
    data class PairCode(val code: String) : ClientMessage()
    data class Move(val dx: Float, val dy: Float) : ClientMessage()
    data object Click : ClientMessage()
    data object LongPress : ClientMessage()
    data class Scroll(val dx: Float, val dy: Float) : ClientMessage()
    data class Cursor(val show: Boolean) : ClientMessage()
    data object Ping : ClientMessage()
    data class Unknown(val type: String) : ClientMessage()
}

object MessageParser {
    /** Largest movement or scroll in one message, in pixels. Larger values are clamped. */
    const val MAX_DELTA = 10_000f
    const val MAX_NAME_LENGTH = 64
    const val MAX_TOKEN_LENGTH = 128
    const val DEFAULT_PHONE_NAME = "Phone"

    /**
     * Parses one line. Returns null if the line is not a JSON object with a
     * non-empty string field "t".
     */
    fun parse(line: String): ClientMessage? {
        val obj = try {
            JSONObject(line)
        } catch (e: JSONException) {
            return null
        }
        val type = obj.opt("t") as? String
        if (type.isNullOrEmpty()) return null
        return when (type) {
            "hello" -> ClientMessage.Hello(
                version = obj.optInt("v", PROTOCOL_VERSION),
                name = sanitizeName(obj.optStringOrNull("name")),
                token = obj.optStringOrNull("token")
                    ?.takeIf { it.isNotEmpty() && it.length <= MAX_TOKEN_LENGTH },
            )
            "pair" -> ClientMessage.PairCode(obj.optStringOrNull("code")?.trim().orEmpty())
            "move" -> ClientMessage.Move(obj.delta("dx"), obj.delta("dy"))
            "click" -> ClientMessage.Click
            "longpress" -> ClientMessage.LongPress
            "scroll" -> ClientMessage.Scroll(obj.delta("dx"), obj.delta("dy"))
            "cursor" -> ClientMessage.Cursor(obj.optBoolean("show", true))
            "ping" -> ClientMessage.Ping
            else -> ClientMessage.Unknown(type)
        }
    }

    internal fun sanitizeName(raw: String?): String {
        val cleaned = raw.orEmpty()
            .filter { !it.isISOControl() }
            .trim()
            .take(MAX_NAME_LENGTH)
        return cleaned.ifEmpty { DEFAULT_PHONE_NAME }
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return when (val value = opt(key)) {
            is String -> value
            is Number, is Boolean -> value.toString()
            else -> null
        }
    }

    private fun JSONObject.delta(key: String): Float {
        val value = optDouble(key, 0.0)
        if (value.isNaN() || value.isInfinite()) return 0f
        return value.toFloat().coerceIn(-MAX_DELTA, MAX_DELTA)
    }
}

/** Builds the lines that the receiver sends. Each result is one JSON object with no newline. */
object ServerMessages {
    fun welcome(boxName: String, width: Int, height: Int): String = JSONObject()
        .put("t", "welcome")
        .put("v", PROTOCOL_VERSION)
        .put("name", boxName)
        .put("w", width)
        .put("h", height)
        .toString()

    fun pairRequired(): String = JSONObject().put("t", "pair_required").toString()

    fun paired(token: String): String = JSONObject().put("t", "paired").put("token", token).toString()

    fun error(code: String, message: String): String = JSONObject()
        .put("t", "error")
        .put("code", code)
        .put("msg", message)
        .toString()

    fun pong(): String = JSONObject().put("t", "pong").toString()
}

/** Error codes in "error" messages. */
object ErrorCodes {
    const val BAD_CODE = "bad_code"
    const val CODE_EXPIRED = "code_expired"
    const val NOT_PAIRING = "not_pairing"
    const val BAD_MESSAGE = "bad_message"
    const val TOO_MANY_CLIENTS = "too_many_clients"
    const val PAIRING_LOCKED = "pairing_locked"
}

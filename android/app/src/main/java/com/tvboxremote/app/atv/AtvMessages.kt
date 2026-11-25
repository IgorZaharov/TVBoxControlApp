package com.tvboxremote.app.atv

import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey

/** Field numbers and builders for the Android TV Remote v2 protocol. See docs/ANDROID_TV_REMOTE_PROTOCOL.md. */
object Pairing {
    const val PROTOCOL_VERSION = 2
    const val STATUS_OK = 200

    const val F_VERSION = 1
    const val F_STATUS = 2
    const val F_REQUEST = 10
    const val F_REQUEST_ACK = 11
    const val F_OPTION = 20
    const val F_CONFIGURATION = 30
    const val F_CONFIGURATION_ACK = 31
    const val F_SECRET = 40
    const val F_SECRET_ACK = 41

    private const val ENCODING_HEX = 3
    private const val SYMBOL_LENGTH = 6
    private const val ROLE_INPUT = 1

    private fun envelope(body: ProtoWriter.() -> Unit): ByteArray =
        ProtoWriter().int(F_VERSION, PROTOCOL_VERSION).int(F_STATUS, STATUS_OK).apply(body).toByteArray()

    fun request(clientName: String) = envelope {
        message(F_REQUEST) { string(1, "atvremote"); string(2, clientName) }
    }

    fun option() = envelope {
        message(F_OPTION) {
            message(1) { int(1, ENCODING_HEX); int(2, SYMBOL_LENGTH) }
            int(3, ROLE_INPUT)
        }
    }

    fun configuration() = envelope {
        message(F_CONFIGURATION) {
            message(1) { int(1, ENCODING_HEX); int(2, SYMBOL_LENGTH) }
            int(2, ROLE_INPUT)
        }
    }

    fun secret(secret: ByteArray) = envelope {
        message(F_SECRET) { bytes(1, secret) }
    }
}

object PairingSecret {
    /** True if [code] has the form of a pairing code: 6 hexadecimal characters. */
    fun isWellFormed(code: String): Boolean = code.length == 6 && code.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }

    /**
     * Computes the pairing secret. Returns null if the code does not match the
     * keys (the first byte of the hash must equal the first byte of the code).
     */
    fun compute(clientKey: RSAPublicKey, serverKey: RSAPublicKey, code: String): ByteArray? {
        if (!isWellFormed(code)) return null
        val codeBytes = hex(code)
        val digest = MessageDigest.getInstance("SHA-256").run {
            update(CertUtil.unsignedBytes(clientKey.modulus))
            update(CertUtil.unsignedBytes(clientKey.publicExponent))
            update(CertUtil.unsignedBytes(serverKey.modulus))
            update(CertUtil.unsignedBytes(serverKey.publicExponent))
            update(codeBytes, 1, codeBytes.size - 1)
            digest()
        }
        return if (digest[0] == codeBytes[0]) digest else null
    }

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

object Remote {
    const val F_CONFIGURE = 1
    const val F_SET_ACTIVE = 2
    const val F_ERROR = 3
    const val F_PING_REQUEST = 8
    const val F_PING_RESPONSE = 9
    const val F_KEY_INJECT = 10
    const val F_IME_KEY_INJECT = 20
    const val F_IME_BATCH_EDIT = 21
    const val F_IME_SHOW_REQUEST = 22
    const val F_START = 40
    const val F_SET_VOLUME_LEVEL = 50
    const val F_APP_LINK = 90

    /** Ping, key, IME, power, volume, app link. */
    const val FEATURES = 1 or 2 or 4 or 32 or 64 or 512

    const val DIRECTION_START_LONG = 1
    const val DIRECTION_END_LONG = 2
    const val DIRECTION_SHORT = 3

    fun configure(model: String, vendor: String, appVersion: String) = ProtoWriter().message(F_CONFIGURE) {
        int(1, FEATURES)
        message(2) {
            string(1, model); string(2, vendor); int(3, 1); string(4, "1")
            string(5, "atvremote"); string(6, appVersion)
        }
    }.toByteArray()

    fun setActive() = ProtoWriter().message(F_SET_ACTIVE) { int(1, FEATURES) }.toByteArray()

    fun pingResponse(value: Int) = ProtoWriter().message(F_PING_RESPONSE) { int(1, value) }.toByteArray()

    fun key(keyCode: Int, direction: Int = DIRECTION_SHORT) =
        ProtoWriter().message(F_KEY_INJECT) { int(1, keyCode); int(2, direction) }.toByteArray()

    fun text(imeCounter: Int, fieldCounter: Int, text: String): ByteArray {
        val end = text.length - 1
        return ProtoWriter().message(F_IME_BATCH_EDIT) {
            int(1, imeCounter)
            int(2, fieldCounter)
            message(3) {
                int(1, 1)
                message(2) { int(1, end); int(2, end); string(3, text) }
            }
        }.toByteArray()
    }

    fun appLink(link: String) = ProtoWriter().message(F_APP_LINK) { string(1, link) }.toByteArray()
}

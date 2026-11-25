package com.tvboxremote.app.atv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Why pairing failed. [status] is the status code of the box, if any. */
class PairingException(val reason: Reason, val status: Int = 0) : IOException("Pairing failed: $reason $status") {
    enum class Reason { REFUSED_CODE, REFUSED_CONFIG, STATUS, UNEXPECTED }
}

/**
 * Pairs the phone with a box on port 6467.
 *
 * [askCode] is called when the TV shows the code. It returns the code that the
 * user typed, or null to cancel. If the code is wrong, [askCode] is called again
 * with `wrongCode = true`.
 *
 * Returns the SHA-256 fingerprint of the box certificate, to pin later connections.
 */
class AtvPairing(
    private val identity: ClientIdentity,
    private val host: String,
    private val clientName: String,
    private val port: Int = 6467,
) {
    suspend fun run(askCode: suspend (wrongCode: Boolean) -> String?): String? {
        val connection = AtvConnection.openCancellable(identity, host, port, expectedFingerprint = null)
        try {
            connection.setReadTimeout(30_000)
            io { connection.send(Pairing.request(clientName)) }
            expect(connection, Pairing.F_REQUEST_ACK)
            io { connection.send(Pairing.option()) }
            expect(connection, Pairing.F_OPTION)
            io { connection.send(Pairing.configuration()) }
            expect(connection, Pairing.F_CONFIGURATION_ACK)

            // The TV now shows the code. The user can take some time to type it.
            var wrong = false
            val secret: ByteArray
            while (true) {
                val code = askCode(wrong)?.trim()?.uppercase() ?: return null
                val computed = PairingSecret.compute(identity.publicKey, connection.serverPublicKey, code)
                if (computed != null) { secret = computed; break }
                wrong = true
            }
            io { connection.send(Pairing.secret(secret)) }
            expect(connection, Pairing.F_SECRET_ACK)
            return connection.serverFingerprint
        } finally {
            connection.close()
        }
    }

    private suspend fun expect(connection: AtvConnection, field: Int) {
        val message = connection.receiveCancellable()
        val status = message.int(Pairing.F_STATUS)
        if (status != Pairing.STATUS_OK) {
            throw when (status) {
                402 -> PairingException(PairingException.Reason.REFUSED_CODE, status)
                401 -> PairingException(PairingException.Reason.REFUSED_CONFIG, status)
                else -> PairingException(PairingException.Reason.STATUS, status)
            }
        }
        if (!message.has(field)) throw PairingException(PairingException.Reason.UNEXPECTED)
    }

    private suspend fun io(block: () -> Unit) = withContext(Dispatchers.IO) { block() }
}

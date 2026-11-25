package com.tvboxremote.app.atv

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class CertificateMismatchException : IOException("The box certificate changed. Pair the box again.")

/**
 * A TLS socket that sends and receives varint length-prefixed protobuf messages.
 * All calls block. Run them on an IO thread.
 */
class AtvConnection private constructor(private val socket: SSLSocket) {
    private val input: InputStream = socket.inputStream
    private val output: OutputStream = socket.outputStream
    private val decoder = FrameDecoder()
    private val pending = ArrayDeque<ByteArray>()
    private val readBuffer = ByteArray(4096)
    private val writeLock = Any()

    val serverCertificate: X509Certificate = socket.session.peerCertificates[0] as X509Certificate
    val serverPublicKey: RSAPublicKey
        get() = serverCertificate.publicKey as? RSAPublicKey
            ?: throw IOException("The box certificate does not use an RSA key")
    val serverFingerprint: String = CertUtil.sha256Fingerprint(serverCertificate)

    fun send(message: ByteArray) {
        val framed = ProtoWriter.frame(message)
        synchronized(writeLock) {
            output.write(framed)
            output.flush()
        }
    }

    /** Blocks until a full message arrives. Throws IOException when the socket closes. */
    fun receive(): ProtoMessage {
        while (pending.isEmpty()) {
            val n = input.read(readBuffer)
            if (n < 0) throw IOException("Connection closed by the box")
            pending.addAll(decoder.feed(readBuffer, n))
        }
        return ProtoMessage.parse(pending.removeFirst())
    }

    /**
     * Like [receive], but suspends. If the coroutine is cancelled, the socket
     * closes, because a blocking socket read does not react to thread interrupts.
     */
    suspend fun receiveCancellable(): ProtoMessage = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { close() }
        thread(name = "atv-receive", isDaemon = true) {
            try {
                cont.resume(receive())
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }
    }

    fun setReadTimeout(millis: Int) {
        socket.soTimeout = millis
    }

    fun close() {
        runCatching { socket.close() }
    }

    companion object {
        /**
         * Like [open], but on an IO thread. If the coroutine is cancelled while the
         * blocking open runs, the new connection is closed instead of leaked.
         */
        suspend fun openCancellable(
            identity: ClientIdentity,
            host: String,
            port: Int,
            expectedFingerprint: String?,
        ): AtvConnection {
            var opened: AtvConnection? = null
            try {
                withContext(Dispatchers.IO) { opened = open(identity, host, port, expectedFingerprint) }
                return opened!!
            } catch (e: CancellationException) {
                opened?.close()
                throw e
            }
        }

        /**
         * Connects and finishes the TLS handshake. If [expectedFingerprint] is set,
         * the server certificate must match it.
         */
        fun open(
            identity: ClientIdentity,
            host: String,
            port: Int,
            expectedFingerprint: String?,
            connectTimeoutMs: Int = 5000,
        ): AtvConnection {
            val factory = identity.sslContext().socketFactory
            val raw = java.net.Socket()
            try {
                raw.tcpNoDelay = true
                raw.connect(InetSocketAddress(host, port), connectTimeoutMs)
                raw.soTimeout = 10_000
                val ssl = factory.createSocket(raw, host, port, true) as SSLSocket
                ssl.startHandshake()
                val connection = AtvConnection(ssl)
                if (expectedFingerprint != null && expectedFingerprint != connection.serverFingerprint) {
                    connection.close()
                    throw CertificateMismatchException()
                }
                return connection
            } catch (e: Exception) {
                runCatching { raw.close() }
                throw e
            }
        }
    }
}

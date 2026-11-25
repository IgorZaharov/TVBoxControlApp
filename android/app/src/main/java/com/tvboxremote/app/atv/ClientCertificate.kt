package com.tvboxremote.app.atv

import android.annotation.SuppressLint
import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * The self-signed client certificate that identifies this phone to the box.
 * The box remembers paired phones by this certificate, so it must stay the same.
 */
class ClientIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {
    val publicKey: RSAPublicKey get() = certificate.publicKey as RSAPublicKey

    /**
     * Makes a TLS context that presents this identity. It accepts any server
     * certificate. The caller pins the server certificate after the handshake.
     */
    fun sslContext(): SSLContext {
        val keyManager = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
            override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
            override fun getCertificateChain(alias: String?) = arrayOf(certificate)
            override fun getPrivateKey(alias: String?) = privateKey
        }
        // The box has a self-signed certificate, so no CA can check it. Instead, the
        // phone pins the certificate fingerprint at pairing (trust on first use).
        // AtvConnection.open refuses a different certificate after pairing.
        @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply {
            init(arrayOf(keyManager), arrayOf(trustAll), SecureRandom())
        }
    }

    companion object {
        private const val ALIAS = "client"
        private const val KEY_FILE = "atv_client.key"
        private const val CERT_FILE = "atv_client.crt"

        @Volatile private var cached: ClientIdentity? = null

        /** Loads the identity from app storage, or makes a new one on first use. */
        fun get(context: Context): ClientIdentity {
            cached?.let { return it }
            synchronized(this) {
                cached?.let { return it }
                val dir = context.noBackupFilesDir
                val identity = load(dir) ?: create().also { save(dir, it) }
                cached = identity
                return identity
            }
        }

        private fun load(dir: File): ClientIdentity? = runCatching {
            val keyFile = File(dir, KEY_FILE)
            val certFile = File(dir, CERT_FILE)
            if (!keyFile.exists() || !certFile.exists()) return null
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certFile.readBytes())) as X509Certificate
            ClientIdentity(key, cert)
        }.getOrNull()

        private fun save(dir: File, identity: ClientIdentity) {
            File(dir, KEY_FILE).writeBytes(identity.privateKey.encoded)
            File(dir, CERT_FILE).writeBytes(identity.certificate.encoded)
        }

        /** Makes a new RSA-2048 key and a self-signed X.509 v3 certificate. */
        fun create(commonName: String = "atvremote", now: Date = Date()): ClientIdentity {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val der = SelfSignedCertificate.build(
                subjectPublicKeyInfo = keyPair.public.encoded,
                commonName = commonName,
                notBefore = Date(now.time - DAY_MS),
                notAfter = Date(now.time + 20L * 365 * DAY_MS),
                sign = { tbs ->
                    Signature.getInstance("SHA256withRSA").run {
                        initSign(keyPair.private); update(tbs); sign()
                    }
                },
            )
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            return ClientIdentity(keyPair.private, cert)
        }

        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

/** Builds the DER bytes of a self-signed certificate. It has no Android dependency, so tests can run it. */
object SelfSignedCertificate {
    private val SHA256_WITH_RSA = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)
    private val COMMON_NAME = byteArrayOf(0x55, 0x04, 0x03)

    fun build(
        subjectPublicKeyInfo: ByteArray,
        commonName: String,
        notBefore: Date,
        notAfter: Date,
        sign: (ByteArray) -> ByteArray,
    ): ByteArray {
        val algorithm = Der.sequence(Der.tlv(0x06, SHA256_WITH_RSA), Der.tlv(0x05, ByteArray(0)))
        val name = Der.sequence(
            Der.tlv(0x31, Der.sequence(Der.tlv(0x06, COMMON_NAME), Der.tlv(0x0C, commonName.toByteArray(Charsets.UTF_8))))
        )
        val serial = BigInteger(63, SecureRandom()).add(BigInteger.ONE)
        val tbs = Der.sequence(
            Der.tlv(0xA0, Der.integer(BigInteger.valueOf(2))), // version v3
            Der.integer(serial),
            algorithm,
            name,
            Der.sequence(Der.time(notBefore), Der.time(notAfter)),
            name,
            subjectPublicKeyInfo,
        )
        val signature = sign(tbs)
        return Der.sequence(tbs, algorithm, Der.tlv(0x03, byteArrayOf(0) + signature))
    }
}

/** A tiny DER encoder for the certificate above. */
object Der {
    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        out.write(length(content.size))
        out.write(content)
        return out.toByteArray()
    }

    fun sequence(vararg items: ByteArray): ByteArray =
        tlv(0x30, items.fold(ByteArray(0)) { acc, b -> acc + b })

    fun integer(value: BigInteger): ByteArray = tlv(0x02, value.toByteArray())

    /** UTCTime for years before 2050, GeneralizedTime after, as RFC 5280 requires. */
    fun time(date: Date): ByteArray {
        val year = SimpleDateFormat("yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(date).toInt()
        return if (year < 2050) {
            tlv(0x17, format(date, "yyMMddHHmmss'Z'"))
        } else {
            tlv(0x18, format(date, "yyyyMMddHHmmss'Z'"))
        }
    }

    fun length(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n <= 0xFF -> byteArrayOf(0x81.toByte(), n.toByte())
        n <= 0xFFFF -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    }

    private fun format(date: Date, pattern: String): ByteArray =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(date).toByteArray(Charsets.US_ASCII)
}

object CertUtil {
    fun sha256Fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

    /** Unsigned big-endian bytes with no leading zero bytes. */
    fun unsignedBytes(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        var start = 0
        while (start < bytes.size - 1 && bytes[start] == 0.toByte()) start++
        return bytes.copyOfRange(start, bytes.size)
    }
}

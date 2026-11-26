package com.tvboxremote.app

import com.tvboxremote.app.atv.CertUtil
import com.tvboxremote.app.atv.ClientIdentity
import com.tvboxremote.app.atv.FrameDecoder
import com.tvboxremote.app.atv.ImeCounters
import com.tvboxremote.app.atv.Pairing
import com.tvboxremote.app.atv.PairingSecret
import com.tvboxremote.app.atv.ProtoMessage
import com.tvboxremote.app.atv.ProtoWriter
import com.tvboxremote.app.atv.Remote
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey

class ProtocolTest {
    @Test
    fun varintRoundTrip() {
        for (v in listOf(0, 1, 127, 128, 300, 16_383, 16_384, 2_000_000, Int.MAX_VALUE)) {
            val msg = ProtoWriter().int(1, v).toByteArray()
            assertEquals(v, ProtoMessage.parse(msg).int(1))
        }
        assertArrayEquals(byteArrayOf(0xAC.toByte(), 0x02), ProtoWriter.encodeVarint(300))
    }

    @Test
    fun negativeIntUsesTenBytes() {
        val msg = ProtoWriter().int(1, -1).toByteArray()
        assertEquals(11, msg.size) // 1 tag byte + 10 varint bytes
        assertEquals(-1, ProtoMessage.parse(msg).int(1))
    }

    @Test
    fun frameDecoderHandlesSplitAndJoinedChunks() {
        val a = Pairing.request("Phone")
        val b = Remote.key(19)
        val stream = ProtoWriter.frame(a) + ProtoWriter.frame(b)
        val decoder = FrameDecoder()
        val out = ArrayList<ByteArray>()
        for (i in stream.indices) out += decoder.feed(byteArrayOf(stream[i]))
        assertEquals(2, out.size)
        assertArrayEquals(a, out[0])
        assertArrayEquals(b, out[1])

        val joined = FrameDecoder().feed(stream)
        assertEquals(2, joined.size)
    }

    @Test
    fun pairingRequestHasEnvelopeAndNames() {
        val m = ProtoMessage.parse(Pairing.request("Pixel"))
        assertEquals(2, m.int(Pairing.F_VERSION))
        assertEquals(200, m.int(Pairing.F_STATUS))
        val req = m.message(Pairing.F_REQUEST)!!
        assertEquals("atvremote", req.string(1))
        assertEquals("Pixel", req.string(2))
    }

    @Test
    fun keyAndTextMessages() {
        val key = ProtoMessage.parse(Remote.key(23)).message(Remote.F_KEY_INJECT)!!
        assertEquals(23, key.int(1))
        assertEquals(Remote.DIRECTION_SHORT, key.int(2))

        val edit = ProtoMessage.parse(Remote.text(4, 7, "abc")).message(Remote.F_IME_BATCH_EDIT)!!
        assertEquals(4, edit.int(1))
        assertEquals(7, edit.int(2))
        val info = edit.message(3)!!
        assertEquals(1, info.int(1))
        val obj = info.message(2)!!
        assertEquals(3, obj.int(1))
        assertEquals(3, obj.int(2))
        assertEquals("abc", obj.string(3))
    }

    @Test
    fun imeCountersComeFromTheFocusedField() {
        val counters = ImeCounters()
        // A batch edit from the box gives the counters when nothing else did.
        counters.update(ProtoMessage.parse(ProtoWriter().message(Remote.F_IME_BATCH_EDIT) { int(1, 2); int(2, 3) }.toByteArray()))
        assertEquals(2, counters.ime)
        assertEquals(3, counters.field)

        // The key inject of a newly focused field: app_info.counter and text_field_status.counter_field.
        val inject = ProtoWriter().message(Remote.F_IME_KEY_INJECT) {
            message(1) { int(1, 41); string(12, "com.android.chrome") }
            message(2) { int(1, 17); string(2, "") }
        }.toByteArray()
        assertTrue(counters.update(ProtoMessage.parse(inject)))
        assertEquals(41, counters.ime)
        assertEquals(17, counters.field)

        // The keyboard opens on another field: only the field counter changes.
        val show = ProtoWriter().message(Remote.F_IME_SHOW_REQUEST) { message(2) { int(1, 18) } }.toByteArray()
        counters.update(ProtoMessage.parse(show))
        assertEquals(41, counters.ime)
        assertEquals(18, counters.field)

        // A later batch edit does not replace the counters of the focused field.
        counters.update(ProtoMessage.parse(ProtoWriter().message(Remote.F_IME_BATCH_EDIT) { int(1, 2); int(2, 3) }.toByteArray()))
        assertEquals(41, counters.ime)
        assertEquals(18, counters.field)
    }

    @Test
    fun configureAnswerAdvertisesFeatures() {
        val m = ProtoMessage.parse(Remote.configure("Pixel", "Google", "1.0.0")).message(Remote.F_CONFIGURE)!!
        assertEquals(615, m.int(1))
        assertEquals("atvremote", m.message(2)!!.string(5))
    }

    private fun rsaKey(): RSAPublicKey =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public as RSAPublicKey

    @Test
    fun pairingSecretMatchesManualComputation() {
        val client = rsaKey()
        val server = rsaKey()
        val suffix = "3F9A"
        fun manual(): ByteArray = MessageDigest.getInstance("SHA-256").run {
            update(BigInteger(1, client.modulus.toByteArray()).toByteArray().dropWhile { it == 0.toByte() }.toByteArray())
            update(byteArrayOf(1, 0, 1))
            update(server.modulus.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())
            update(byteArrayOf(1, 0, 1))
            update(PairingSecret.hex(suffix))
            digest()
        }
        val expected = manual()
        val code = "%02X".format(expected[0]) + suffix
        assertArrayEquals(expected, PairingSecret.compute(client, server, code))

        // The same code with a different check byte is wrong.
        val wrong = "%02X".format((expected[0] + 1).toByte()) + suffix
        assertNull(PairingSecret.compute(client, server, wrong))
        // Lower case is accepted.
        assertNotNull(PairingSecret.compute(client, server, code.lowercase()))
    }

    @Test
    fun malformedCodesAreRejected() {
        val k = rsaKey()
        assertNull(PairingSecret.compute(k, k, "12345"))
        assertNull(PairingSecret.compute(k, k, "12345G"))
    }

    @Test
    fun unsignedBytesStripsSignByte() {
        assertArrayEquals(byteArrayOf(1, 0, 1), CertUtil.unsignedBytes(BigInteger.valueOf(65537)))
        assertArrayEquals(byteArrayOf(0xFF.toByte()), CertUtil.unsignedBytes(BigInteger.valueOf(255)))
    }

    @Test
    fun selfSignedCertificateParsesAndVerifies() {
        val identity = ClientIdentity.create()
        val cert = identity.certificate
        cert.checkValidity()
        cert.verify(cert.publicKey)
        assertEquals(3, cert.version)
        assertTrue(cert.subjectX500Principal.name.contains("CN=atvremote"))
        assertEquals(2048, identity.publicKey.modulus.bitLength())
    }
}

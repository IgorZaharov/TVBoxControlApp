package com.tvboxremote.app.atv

import java.io.ByteArrayOutputStream

/**
 * A minimal protobuf (proto3) writer. It supports the wire types that the
 * Android TV Remote protocol uses: varint (0) and length-delimited (2).
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun int(field: Int, value: Int): ProtoWriter {
        if (value != 0) {
            tag(field, 0)
            // Negative int32 values are sign-extended to 64 bits, as protobuf requires.
            varint(value.toLong())
        }
        return this
    }

    fun bool(field: Int, value: Boolean): ProtoWriter = int(field, if (value) 1 else 0)

    fun string(field: Int, value: String): ProtoWriter {
        if (value.isNotEmpty()) bytes(field, value.toByteArray(Charsets.UTF_8))
        return this
    }

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, 2)
        varint(value.size.toLong())
        out.write(value)
        return this
    }

    /** Writes an embedded message. An empty message is still written, because presence matters. */
    fun message(field: Int, build: ProtoWriter.() -> Unit): ProtoWriter =
        bytes(field, ProtoWriter().apply(build).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wireType: Int) = varint(((field shl 3) or wireType).toLong())

    private fun varint(v: Long) {
        var value = v
        while (true) {
            if (value and 0x7FL.inv() == 0L) {
                out.write(value.toInt())
                return
            }
            out.write(((value and 0x7F) or 0x80).toInt())
            value = value ushr 7
        }
    }

    companion object {
        fun encodeVarint(value: Int): ByteArray {
            val w = ByteArrayOutputStream()
            var v = value.toLong() and 0xFFFFFFFFL
            while (true) {
                if (v and 0x7FL.inv() == 0L) {
                    w.write(v.toInt()); break
                }
                w.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            return w.toByteArray()
        }

        /** Adds a varint length prefix, as the protocol frames every message. */
        fun frame(message: ByteArray): ByteArray = encodeVarint(message.size) + message
    }
}

/** One decoded field. [varint] is set for wire type 0, [bytes] for wire type 2. */
class ProtoField(val number: Int, val wireType: Int, val varint: Long, val bytes: ByteArray?) {
    val int: Int get() = varint.toInt()
    val bool: Boolean get() = varint != 0L
    val string: String get() = bytes?.toString(Charsets.UTF_8) ?: ""
    fun message(): ProtoMessage = ProtoMessage.parse(bytes ?: ByteArray(0))
}

/** A decoded message: a list of fields in wire order. */
class ProtoMessage(val fields: List<ProtoField>) {
    fun field(number: Int): ProtoField? = fields.lastOrNull { it.number == number }
    fun has(number: Int): Boolean = fields.any { it.number == number }
    fun int(number: Int): Int = field(number)?.int ?: 0
    fun bool(number: Int): Boolean = field(number)?.bool ?: false
    fun string(number: Int): String = field(number)?.string ?: ""
    fun message(number: Int): ProtoMessage? = field(number)?.message()

    companion object {
        fun parse(data: ByteArray): ProtoMessage {
            val fields = ArrayList<ProtoField>()
            var pos = 0
            fun readVarint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    if (pos >= data.size) throw ProtoException("Truncated varint")
                    val b = data[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                    if (shift >= 64) throw ProtoException("Varint too long")
                }
            }
            while (pos < data.size) {
                val key = readVarint()
                val number = (key ushr 3).toInt()
                when (val wireType = (key and 7).toInt()) {
                    0 -> fields += ProtoField(number, 0, readVarint(), null)
                    1 -> { if (pos + 8 > data.size) throw ProtoException("Truncated fixed64"); pos += 8 }
                    2 -> {
                        val len = readVarint()
                        if (len < 0 || pos + len > data.size) throw ProtoException("Truncated bytes")
                        fields += ProtoField(number, 2, 0, data.copyOfRange(pos, pos + len.toInt()))
                        pos += len.toInt()
                    }
                    5 -> { if (pos + 4 > data.size) throw ProtoException("Truncated fixed32"); pos += 4 }
                    else -> throw ProtoException("Unsupported wire type $wireType")
                }
            }
            return ProtoMessage(fields)
        }
    }
}

class ProtoException(message: String) : Exception(message)

/**
 * Splits a byte stream into varint length-prefixed messages.
 * Feed it bytes as they arrive. It returns each complete message.
 */
class FrameDecoder(private val maxMessageSize: Int = 1 shl 20) {
    private var buffer = ByteArray(0)

    fun feed(chunk: ByteArray, count: Int = chunk.size): List<ByteArray> {
        buffer += chunk.copyOf(count)
        val messages = ArrayList<ByteArray>()
        while (true) {
            var pos = 0
            var length = 0L
            var shift = 0
            var complete = false
            while (pos < buffer.size && pos < 5) {
                val b = buffer[pos++].toInt() and 0xFF
                length = length or ((b and 0x7F).toLong() shl shift)
                shift += 7
                if (b and 0x80 == 0) { complete = true; break }
            }
            if (!complete) {
                if (buffer.size >= 5) throw ProtoException("Bad frame length")
                break
            }
            if (length > maxMessageSize) throw ProtoException("Frame too large: $length")
            if (buffer.size - pos < length) break
            messages += buffer.copyOfRange(pos, pos + length.toInt())
            buffer = buffer.copyOfRange(pos + length.toInt(), buffer.size)
        }
        return messages
    }
}

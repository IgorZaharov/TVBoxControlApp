package com.tvboxremote.receiver.server

import java.io.IOException
import java.io.InputStream

class LineTooLongException(limit: Int) : IOException("Line is longer than $limit bytes")

/**
 * Reads UTF-8 lines that end with '\n' and are at most [maxBytes] long.
 * A trailing '\r' is removed. Wrap [input] in a BufferedInputStream.
 */
class LineReader(private val input: InputStream, private val maxBytes: Int) {
    private val buffer = ByteArray(maxBytes)

    /** Returns the next line, or null at the end of the stream. */
    fun readLine(): String? {
        var length = 0
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (length == 0) null else decode(length)
            }
            if (b == '\n'.code) return decode(length)
            if (length == maxBytes) throw LineTooLongException(maxBytes)
            buffer[length++] = b.toByte()
        }
    }

    private fun decode(length: Int): String {
        var end = length
        if (end > 0 && buffer[end - 1] == '\r'.code.toByte()) end--
        return String(buffer, 0, end, Charsets.UTF_8)
    }
}

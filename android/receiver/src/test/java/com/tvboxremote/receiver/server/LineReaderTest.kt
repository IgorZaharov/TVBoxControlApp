package com.tvboxremote.receiver.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class LineReaderTest {

    private fun reader(text: String, max: Int = 4096) =
        LineReader(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), max)

    @Test
    fun readsLinesAndStripsCarriageReturn() {
        val r = reader("one\ntwo\r\n\nthree")
        assertEquals("one", r.readLine())
        assertEquals("two", r.readLine())
        assertEquals("", r.readLine())
        assertEquals("three", r.readLine())
        assertNull(r.readLine())
    }

    @Test
    fun decodesUtf8() {
        assertEquals("Пульт ТВ", reader("Пульт ТВ\n").readLine())
    }

    @Test
    fun lineAtTheLimitIsAccepted() {
        assertEquals("abcd", reader("abcd\n", max = 4).readLine())
    }

    @Test(expected = LineTooLongException::class)
    fun lineOverTheLimitThrows() {
        reader("abcde\n", max = 4).readLine()
    }
}

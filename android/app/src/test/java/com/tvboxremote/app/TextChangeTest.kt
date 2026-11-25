package com.tvboxremote.app

import com.tvboxremote.app.remote.TextChange
import org.junit.Assert.assertEquals
import org.junit.Test

class TextChangeTest {
    @Test
    fun typingSendsOnlyTheNewLetter() {
        // The bug: "test" became "ttetestest", because each change sent the whole field.
        var box = ""
        var sent = ""
        for (field in listOf("t", "te", "tes", "test")) {
            val change = TextChange.of(sent, field)
            box = box.dropLast(change.deletes) + change.insert
            sent = field
        }
        assertEquals("test", box)
    }

    @Test
    fun deleteAndReplace() {
        assertEquals(TextChange(1, ""), TextChange.of("test", "tes"))
        assertEquals(TextChange(2, "xt"), TextChange.of("test", "text"))
        assertEquals(TextChange(4, ""), TextChange.of("test", ""))
        assertEquals(TextChange(0, ""), TextChange.of("same", "same"))
    }

    @Test
    fun keepsSurrogatePairsWhole() {
        assertEquals(TextChange(2, "😃"), TextChange.of("a😀", "a😃"))
    }
}

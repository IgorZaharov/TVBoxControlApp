package com.tvboxremote.receiver.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageParserTest {

    @Test
    fun parsesHelloWithToken() {
        val msg = MessageParser.parse("""{"t":"hello","v":1,"name":"Pixel 8","token":"abc"}""")
        assertEquals(ClientMessage.Hello(1, "Pixel 8", "abc"), msg)
    }

    @Test
    fun helloWithoutTokenOrNullTokenHasNoToken() {
        assertEquals(ClientMessage.Hello(1, "P", null), MessageParser.parse("""{"t":"hello","v":1,"name":"P"}"""))
        assertEquals(ClientMessage.Hello(1, "P", null), MessageParser.parse("""{"t":"hello","name":"P","token":null}"""))
        assertEquals(ClientMessage.Hello(1, "P", null), MessageParser.parse("""{"t":"hello","name":"P","token":""}"""))
    }

    @Test
    fun helloNameIsCleanedAndLimited() {
        val msg = MessageParser.parse("""{"t":"hello","name":"  A\u0000B\n  "}""") as ClientMessage.Hello
        assertEquals("AB", msg.name)
        val long = "x".repeat(200)
        val longMsg = MessageParser.parse("""{"t":"hello","name":"$long"}""") as ClientMessage.Hello
        assertEquals(MessageParser.MAX_NAME_LENGTH, longMsg.name.length)
        val empty = MessageParser.parse("""{"t":"hello"}""") as ClientMessage.Hello
        assertEquals(MessageParser.DEFAULT_PHONE_NAME, empty.name)
    }

    @Test
    fun tooLongTokenIsDropped() {
        val token = "a".repeat(MessageParser.MAX_TOKEN_LENGTH + 1)
        val msg = MessageParser.parse("""{"t":"hello","name":"P","token":"$token"}""") as ClientMessage.Hello
        assertNull(msg.token)
    }

    @Test
    fun parsesPairCodeAsStringOrNumber() {
        assertEquals(ClientMessage.PairCode("4821"), MessageParser.parse("""{"t":"pair","code":"4821"}"""))
        assertEquals(ClientMessage.PairCode("4821"), MessageParser.parse("""{"t":"pair","code":4821}"""))
        assertEquals(ClientMessage.PairCode(""), MessageParser.parse("""{"t":"pair"}"""))
    }

    @Test
    fun parsesMoveAndScrollWithClamping() {
        assertEquals(ClientMessage.Move(12.5f, -3f), MessageParser.parse("""{"t":"move","dx":12.5,"dy":-3}"""))
        assertEquals(ClientMessage.Move(0f, 0f), MessageParser.parse("""{"t":"move"}"""))
        assertEquals(ClientMessage.Move(0f, 0f), MessageParser.parse("""{"t":"move","dx":"abc","dy":"NaN"}"""))
        assertEquals(
            ClientMessage.Scroll(MessageParser.MAX_DELTA, -MessageParser.MAX_DELTA),
            MessageParser.parse("""{"t":"scroll","dx":1e9,"dy":-1e9}"""),
        )
    }

    @Test
    fun parsesSimpleCommands() {
        assertEquals(ClientMessage.Click, MessageParser.parse("""{"t":"click"}"""))
        assertEquals(ClientMessage.LongPress, MessageParser.parse("""{"t":"longpress"}"""))
        assertEquals(ClientMessage.Ping, MessageParser.parse("""{"t":"ping","extra":1}"""))
        assertEquals(ClientMessage.Cursor(false), MessageParser.parse("""{"t":"cursor","show":false}"""))
        assertEquals(ClientMessage.Cursor(true), MessageParser.parse("""{"t":"cursor"}"""))
    }

    @Test
    fun unknownTypeIsUnknown() {
        assertEquals(ClientMessage.Unknown("future"), MessageParser.parse("""{"t":"future","x":1}"""))
    }

    @Test
    fun invalidLinesGiveNull() {
        assertNull(MessageParser.parse("not json"))
        assertNull(MessageParser.parse("[1,2]"))
        assertNull(MessageParser.parse("""{"x":1}"""))
        assertNull(MessageParser.parse("""{"t":""}"""))
        assertNull(MessageParser.parse("""{"t":5}"""))
    }

    @Test
    fun serverMessagesAreSingleLineJson() {
        val welcome = ServerMessages.welcome("Living\nRoom", 1920, 1080)
        assertFalse(welcome.contains('\n'))
        val obj = JSONObject(welcome)
        assertEquals("welcome", obj.getString("t"))
        assertEquals(1, obj.getInt("v"))
        assertEquals("Living\nRoom", obj.getString("name"))
        assertEquals(1920, obj.getInt("w"))
        assertEquals(1080, obj.getInt("h"))

        assertEquals("pair_required", JSONObject(ServerMessages.pairRequired()).getString("t"))
        assertEquals("tok", JSONObject(ServerMessages.paired("tok")).getString("token"))
        val error = JSONObject(ServerMessages.error("bad_code", "msg"))
        assertEquals("error", error.getString("t"))
        assertEquals("bad_code", error.getString("code"))
        assertEquals("msg", error.getString("msg"))
        assertEquals("pong", JSONObject(ServerMessages.pong()).getString("t"))
    }

    @Test
    fun secretsHaveTheRightShape() {
        repeat(50) {
            val code = Secrets.pairingCode()
            assertTrue(code, code.matches(Regex("\\d{4}")))
        }
        val token = Secrets.token()
        assertTrue(token.matches(Regex("[0-9a-f]{64}")))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Secrets.sha256Hex("abc"),
        )
    }
}

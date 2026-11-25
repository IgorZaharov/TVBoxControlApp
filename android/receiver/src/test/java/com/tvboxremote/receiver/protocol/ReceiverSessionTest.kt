package com.tvboxremote.receiver.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeTokens : TokenStore {
    val tokens = mutableMapOf<String, String>()
    override fun isValid(token: String) = tokens.containsKey(token)
    override fun add(token: String, phoneName: String) {
        tokens[token] = phoneName
    }
}

class FakeDisplay : PairingDisplay {
    var code: String? = null
    var phoneName: String? = null
    var expiresAt = 0L
    var showCount = 0

    override fun show(phoneName: String, code: String, expiresAtMillis: Long) {
        this.code = code
        this.phoneName = phoneName
        expiresAt = expiresAtMillis
        showCount++
    }

    override fun hide() {
        code = null
    }
}

class FakeHost : SessionHost {
    val sent = mutableListOf<JSONObject>()
    val actions = mutableListOf<String>()
    var closed = false

    override fun send(line: String) {
        assertFalse("A line must not contain a newline", line.contains('\n'))
        sent += JSONObject(line)
    }
    override fun close() {
        closed = true
    }
    override fun boxName() = "Living Room TV"
    override fun screenWidth() = 1920
    override fun screenHeight() = 1080
    override fun move(dx: Float, dy: Float) {
        actions += "move $dx $dy"
    }
    override fun click() {
        actions += "click"
    }
    override fun longPress() {
        actions += "longpress"
    }
    override fun scroll(dx: Float, dy: Float) {
        actions += "scroll $dx $dy"
    }
    override fun setCursorVisible(visible: Boolean) {
        actions += "cursor $visible"
    }

    fun types() = sent.map { it.getString("t") }
    fun errorCodes() = sent.filter { it.getString("t") == "error" }.map { it.getString("code") }
}

class ReceiverSessionTest {

    private lateinit var host: FakeHost
    private lateinit var tokens: FakeTokens
    private lateinit var display: FakeDisplay
    private lateinit var gate: PairingGate
    private var now = 1_000_000L
    private var nextCode = "1234"
    private lateinit var session: ReceiverSession

    @Before
    fun setUp() {
        host = FakeHost()
        tokens = FakeTokens()
        display = FakeDisplay()
        gate = PairingGate(display, clock = { now }, codeGenerator = { nextCode })
        session = newSession(host)
    }

    private fun newSession(h: FakeHost) =
        ReceiverSession(host = h, tokens = tokens, gate = gate, tokenGenerator = { "token-1" })

    @Test
    fun knownTokenIsWelcomedAtOnce() {
        tokens.add("good", "Old phone")
        session.onLine("""{"t":"hello","v":1,"name":"Pixel","token":"good"}""")
        assertEquals(listOf("welcome"), host.types())
        val welcome = host.sent[0]
        assertEquals("Living Room TV", welcome.getString("name"))
        assertEquals(1920, welcome.getInt("w"))
        assertEquals(1080, welcome.getInt("h"))
        assertEquals(1, welcome.getInt("v"))
        assertEquals(ReceiverSession.State.AUTHORIZED, session.state)
        assertNull(display.code)
    }

    @Test
    fun unknownTokenStartsPairingAndCorrectCodeAuthorizes() {
        session.onLine("""{"t":"hello","v":1,"name":"Pixel","token":"bad"}""")
        assertEquals(listOf("pair_required"), host.types())
        assertEquals("1234", display.code)
        assertEquals("Pixel", display.phoneName)
        assertEquals(now + PairingGate.CODE_LIFETIME_MS, display.expiresAt)

        session.onLine("""{"t":"pair","code":"1234"}""")
        assertEquals(listOf("pair_required", "paired", "welcome"), host.types())
        assertEquals("token-1", host.sent[1].getString("token"))
        assertEquals("Pixel", tokens.tokens["token-1"])
        assertNull(display.code)
        assertEquals(ReceiverSession.State.AUTHORIZED, session.state)
    }

    @Test
    fun commandsAreIgnoredUntilAuthorized() {
        session.onLine("""{"t":"move","dx":5,"dy":5}""")
        session.onLine("""{"t":"hello","name":"Pixel"}""")
        session.onLine("""{"t":"click"}""")
        session.onLine("""{"t":"cursor","show":true}""")
        assertTrue(host.actions.isEmpty())

        session.onLine("""{"t":"pair","code":"1234"}""")
        session.onLine("""{"t":"move","dx":5,"dy":-2.5}""")
        session.onLine("""{"t":"click"}""")
        session.onLine("""{"t":"longpress"}""")
        session.onLine("""{"t":"scroll","dx":0,"dy":120}""")
        session.onLine("""{"t":"cursor","show":false}""")
        assertEquals(
            listOf("move 5.0 -2.5", "click", "longpress", "scroll 0.0 120.0", "cursor false"),
            host.actions,
        )
    }

    @Test
    fun pingIsAlwaysAnswered() {
        session.onLine("""{"t":"ping"}""")
        assertEquals(listOf("pong"), host.types())
    }

    @Test
    fun fiveWrongCodesLockPairingAndClose() {
        session.onLine("""{"t":"hello","name":"Pixel"}""")
        repeat(4) { session.onLine("""{"t":"pair","code":"0000"}""") }
        assertFalse(host.closed)
        assertEquals("1234", display.code)
        assertEquals(List(4) { ErrorCodes.BAD_CODE }, host.errorCodes())

        session.onLine("""{"t":"pair","code":"0000"}""")
        assertEquals(ErrorCodes.PAIRING_LOCKED, host.errorCodes().last())
        assertTrue(host.closed)
        assertNull(display.code)
        assertEquals(ReceiverSession.State.CLOSED, session.state)

        val before = host.sent.size
        session.onLine("""{"t":"pair","code":"1234"}""")
        assertEquals(before, host.sent.size)
        assertTrue(tokens.tokens.isEmpty())
    }

    @Test
    fun reconnectDoesNotResetTheTries() {
        session.onLine("""{"t":"hello","name":"Pixel"}""")
        repeat(3) { session.onLine("""{"t":"pair","code":"0000"}""") }
        session.onClosed()

        // A new connection reuses the active code and the 2 remaining tries.
        nextCode = "9999"
        val host2 = FakeHost()
        val session2 = newSession(host2)
        session2.onLine("""{"t":"hello","name":"Pixel"}""")
        assertEquals(listOf("pair_required"), host2.types())
        assertEquals("1234", display.code)

        session2.onLine("""{"t":"pair","code":"0000"}""")
        assertFalse(host2.closed)
        session2.onLine("""{"t":"pair","code":"0000"}""")
        assertEquals(listOf(ErrorCodes.BAD_CODE, ErrorCodes.PAIRING_LOCKED), host2.errorCodes())
        assertTrue(host2.closed)
    }

    @Test
    fun helloWhileLockedIsRefusedUntilTheLockExpires() {
        session.onLine("""{"t":"hello","name":"Pixel"}""")
        repeat(PairingGate.MAX_WRONG_CODES) { session.onLine("""{"t":"pair","code":"0000"}""") }

        val host2 = FakeHost()
        newSession(host2).onLine("""{"t":"hello","name":"Pixel"}""")
        assertEquals(listOf(ErrorCodes.PAIRING_LOCKED), host2.errorCodes())
        assertTrue(host2.closed)
        assertNull(display.code)

        // A known token still works while pairing is locked.
        tokens.add("good", "Old phone")
        val host3 = FakeHost()
        newSession(host3).onLine("""{"t":"hello","name":"Old phone","token":"good"}""")
        assertEquals(listOf("welcome"), host3.types())

        now += PairingGate.LOCK_DURATION_MS
        nextCode = "5678"
        val host4 = FakeHost()
        val session4 = newSession(host4)
        session4.onLine("""{"t":"hello","name":"Pixel"}""")
        assertEquals(listOf("pair_required"), host4.types())
        assertEquals("5678", display.code)
        session4.onLine("""{"t":"pair","code":"5678"}""")
        assertEquals(ReceiverSession.State.AUTHORIZED, session4.state)
    }

    @Test
    fun expiredCodeIsRefusedAndANewCodeIsShown() {
        session.onLine("""{"t":"hello","name":"Pixel"}""")
        now += PairingGate.CODE_LIFETIME_MS + 1
        nextCode = "5678"
        session.onLine("""{"t":"pair","code":"1234"}""")
        assertEquals(listOf("pair_required", "error", "pair_required"), host.types())
        assertEquals(ErrorCodes.CODE_EXPIRED, host.sent[1].getString("code"))
        assertEquals("5678", display.code)
        assertTrue(tokens.tokens.isEmpty())

        session.onLine("""{"t":"pair","code":"5678"}""")
        assertEquals(ReceiverSession.State.AUTHORIZED, session.state)
    }

    @Test
    fun codeUsedByAnotherPhoneGivesANewCode() {
        val host2 = FakeHost()
        val session2 = newSession(host2)
        session.onLine("""{"t":"hello","name":"Pixel"}""")
        session2.onLine("""{"t":"hello","name":"iPhone"}""")
        assertEquals("1234", display.code)

        session.onLine("""{"t":"pair","code":"1234"}""")
        assertEquals(ReceiverSession.State.AUTHORIZED, session.state)

        nextCode = "4321"
        session2.onLine("""{"t":"pair","code":"1234"}""")
        assertEquals(listOf(ErrorCodes.CODE_EXPIRED), host2.errorCodes())
        assertEquals("4321", display.code)
        assertEquals(ReceiverSession.State.AWAIT_PAIR, session2.state)
    }

    @Test
    fun pairWithoutPairingIsAnError() {
        session.onLine("""{"t":"pair","code":"1234"}""")
        assertEquals(ErrorCodes.NOT_PAIRING, host.sent.single().getString("code"))
    }

    @Test
    fun badLineGivesErrorAndUnknownTypeIsIgnored() {
        session.onLine("garbage")
        session.onLine("""{"t":"future"}""")
        assertEquals(listOf("error"), host.types())
        assertEquals(ErrorCodes.BAD_MESSAGE, host.sent[0].getString("code"))
        assertFalse(host.closed)
    }

    @Test
    fun secondHelloIsIgnored() {
        session.onLine("""{"t":"hello","name":"Pixel"}""")
        nextCode = "9999"
        session.onLine("""{"t":"hello","name":"Other"}""")
        assertEquals(listOf("pair_required"), host.types())
        assertEquals("1234", display.code)
    }

    @Test
    fun closingTheOnlyWaitingSessionHidesTheCode() {
        session.onLine("""{"t":"hello","name":"Pixel"}""")
        session.onClosed()
        assertNull(display.code)
        assertEquals(ReceiverSession.State.CLOSED, session.state)
    }
}

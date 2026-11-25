package com.tvboxremote.receiver.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class PairingGateTest {

    private lateinit var display: FakeDisplay
    private lateinit var gate: PairingGate
    private var now = 5_000_000L
    private var generated = 0
    private val a = Any()
    private val b = Any()

    @Before
    fun setUp() {
        display = FakeDisplay()
        gate = PairingGate(display, clock = { now }, codeGenerator = { "%04d".format(1111 * ++generated) })
    }

    private fun lockOut(key: Any) {
        gate.request(key, "Phone")
        repeat(PairingGate.MAX_WRONG_CODES - 1) {
            assertEquals(PairingGate.Submit.Wrong(PairingGate.MAX_WRONG_CODES - 1 - it), gate.submit(key, "0000"))
        }
        assertEquals(PairingGate.Submit.NowLocked(now + PairingGate.LOCK_DURATION_MS), gate.submit(key, "0000"))
    }

    @Test
    fun activeCodeIsReusedByANewRequest() {
        gate.request(a, "Pixel")
        gate.leave(a)
        assertNull(display.code)

        gate.request(b, "iPhone")
        assertEquals("1111", display.code)
        assertEquals(1, generated)
    }

    @Test
    fun triesAreSharedAcrossKeys() {
        gate.request(a, "Pixel")
        gate.submit(a, "0000")
        gate.submit(a, "0000")
        gate.leave(a)
        gate.request(b, "Pixel")
        assertEquals(PairingGate.Submit.Wrong(2), gate.submit(b, "0000"))
        assertEquals(3, gate.wrongCodeCount)
    }

    @Test
    fun lockRefusesRequestsAndCodesUntilItExpires() {
        lockOut(a)
        val until = now + PairingGate.LOCK_DURATION_MS
        assertNull(display.code)
        assertEquals(PairingGate.Request.Locked(until), gate.request(b, "Pixel"))
        assertEquals(PairingGate.Submit.Locked(until), gate.submit(b, "1111"))

        now = until - 1
        assertEquals(PairingGate.Request.Locked(until), gate.request(b, "Pixel"))

        now = until
        assertEquals(PairingGate.Request.Code(now + PairingGate.CODE_LIFETIME_MS), gate.request(b, "Pixel"))
        assertEquals("2222", display.code)
        assertEquals(0, gate.wrongCodeCount)
        assertEquals(PairingGate.Submit.Wrong(4), gate.submit(b, "0000"))
    }

    @Test
    fun correctCodeResetsTriesAndConsumesTheCode() {
        gate.request(a, "Pixel")
        gate.submit(a, "0000")
        assertEquals(PairingGate.Submit.Correct, gate.submit(a, "1111"))
        assertEquals(0, gate.wrongCodeCount)
        assertNull(display.code)
        assertEquals(PairingGate.Submit.NoCode, gate.submit(a, "1111"))
    }

    @Test
    fun expiredCodeKeepsTheTriesAndGivesNoCode() {
        gate.request(a, "Pixel")
        gate.submit(a, "0000")
        now += PairingGate.CODE_LIFETIME_MS + 1
        assertEquals(PairingGate.Submit.NoCode, gate.submit(a, "1111"))
        assertNull(display.code)
        assertEquals(1, gate.wrongCodeCount)

        gate.request(a, "Pixel")
        assertEquals("2222", display.code)
    }

    @Test
    fun oldWrongCodesAreForgotten() {
        gate.request(a, "Pixel")
        repeat(4) { gate.submit(a, "0000") }
        now += PairingGate.LOCK_DURATION_MS + 1
        gate.request(a, "Pixel")
        assertEquals(0, gate.wrongCodeCount)
        assertEquals(PairingGate.Submit.Wrong(4), gate.submit(a, "0000"))
    }

    @Test
    fun codeHidesOnlyWhenTheLastWaiterLeaves() {
        gate.request(a, "Pixel")
        gate.request(b, "iPhone")
        gate.leave(a)
        assertEquals("1111", display.code)
        gate.leave(b)
        assertNull(display.code)
    }
}

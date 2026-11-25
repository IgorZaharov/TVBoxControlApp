package com.tvboxremote.app.bluetooth

/**
 * The HID report descriptor and report builders. The phone shows to the box as
 * one combined device with three reports:
 *  1. Keyboard: modifiers, reserved byte, 6 key slots.
 *  2. Mouse: 3 buttons, relative X, Y and wheel (signed 8 bits each).
 *  3. Consumer control: one 16-bit usage.
 *  4. Gamepad: 16 buttons, a hat switch (8 directions), and four axes X, Y, Z, Rz (signed 8 bits each).
 */
object HidReports {
    const val ID_KEYBOARD = 1
    const val ID_MOUSE = 2
    const val ID_CONSUMER = 3
    const val ID_GAMEPAD = 4

    /** The size in bytes of each report, without the report ID. */
    fun reportSize(id: Int): Int = when (id) {
        ID_KEYBOARD -> 8
        ID_MOUSE -> 4
        ID_CONSUMER -> 2
        ID_GAMEPAD -> 7
        else -> 0
    }

    val DESCRIPTOR: ByteArray = intArrayOf(
        // Keyboard
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, ID_KEYBOARD,
        0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01,
        0x75, 0x01, 0x95, 0x08, 0x81, 0x02,             // 8 modifier bits
        0x95, 0x01, 0x75, 0x08, 0x81, 0x01,             // reserved byte
        0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
        0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00, // 6 key slots
        0xC0,
        // Mouse
        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, ID_MOUSE,
        0x09, 0x01, 0xA1, 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01,
        0x95, 0x03, 0x75, 0x01, 0x81, 0x02,             // 3 buttons
        0x95, 0x01, 0x75, 0x05, 0x81, 0x01,             // padding
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
        0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06, // X, Y, wheel (relative)
        0xC0, 0xC0,
        // Consumer control
        0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, ID_CONSUMER,
        0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03,
        0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
        0xC0,
        // Gamepad
        0x05, 0x01, 0x09, 0x05, 0xA1, 0x01, 0x85, ID_GAMEPAD,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x10, 0x15, 0x00, 0x25, 0x01,
        0x75, 0x01, 0x95, 0x10, 0x81, 0x02,             // 16 buttons
        0x05, 0x01, 0x09, 0x39, 0x15, 0x00, 0x25, 0x07,
        0x35, 0x00, 0x46, 0x3B, 0x01, 0x65, 0x14,       // 0 to 315 degrees
        0x75, 0x04, 0x95, 0x01, 0x81, 0x42,             // hat switch, with a null state
        0x65, 0x00, 0x45, 0x00,
        0x75, 0x04, 0x95, 0x01, 0x81, 0x01,             // padding
        0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35,
        0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x04, 0x81, 0x02, // X, Y, Z, Rz (absolute)
        0xC0,
    ).map { it.toByte() }.toByteArray()

    const val MOD_LEFT_SHIFT = 0x02

    fun keyboard(modifiers: Int, usage: Int): ByteArray =
        byteArrayOf(modifiers.toByte(), 0, usage.toByte(), 0, 0, 0, 0, 0)

    val KEYBOARD_RELEASE = ByteArray(8)

    fun mouse(buttons: Int, dx: Int, dy: Int, wheel: Int): ByteArray =
        byteArrayOf(buttons.toByte(), clamp8(dx).toByte(), clamp8(dy).toByte(), clamp8(wheel).toByte())

    fun consumer(usage: Int): ByteArray = byteArrayOf((usage and 0xFF).toByte(), (usage shr 8 and 0xFF).toByte())

    val CONSUMER_RELEASE = ByteArray(2)

    /** The gamepad report: [buttons] is a bit mask (bit 0 is button 1), [hat] is 0..7 or 8 for none. */
    fun gamepad(buttons: Int, hat: Int, x: Int, y: Int, z: Int, rz: Int): ByteArray = byteArrayOf(
        (buttons and 0xFF).toByte(), (buttons shr 8 and 0xFF).toByte(),
        (hat and 0x0F).toByte(),
        clamp8(x).toByte(), clamp8(y).toByte(), clamp8(z).toByte(), clamp8(rz).toByte(),
    )

    fun clamp8(v: Int) = v.coerceIn(-127, 127)
}

/** HID keyboard usages (usage page 0x07). */
object KeyUsage {
    const val ENTER = 0x28
    const val ESCAPE = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52

    data class Stroke(val usage: Int, val shift: Boolean)

    private const val UNSHIFTED = "-=[]\\;'`,./"
    private const val SHIFTED = "_+{}|:\"~<>?"
    private val PUNCTUATION_USAGES = intArrayOf(0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38)
    private const val SHIFTED_DIGITS = "!@#$%^&*()"

    /** Maps a character to a key on a US keyboard. Returns null if there is no such key. */
    fun forChar(c: Char): Stroke? = when (c) {
        in 'a'..'z' -> Stroke(0x04 + (c - 'a'), false)
        in 'A'..'Z' -> Stroke(0x04 + (c - 'A'), true)
        in '1'..'9' -> Stroke(0x1E + (c - '1'), false)
        '0' -> Stroke(0x27, false)
        ' ' -> Stroke(SPACE, false)
        '\n' -> Stroke(ENTER, false)
        '\t' -> Stroke(TAB, false)
        in UNSHIFTED -> Stroke(PUNCTUATION_USAGES[UNSHIFTED.indexOf(c)], false)
        in SHIFTED -> Stroke(PUNCTUATION_USAGES[SHIFTED.indexOf(c)], true)
        in SHIFTED_DIGITS -> Stroke(if (c == ')') 0x27 else 0x1E + SHIFTED_DIGITS.indexOf(c), true)
        else -> null
    }
}

/** HID consumer usages (usage page 0x0C). */
object ConsumerUsage {
    const val POWER = 0x30
    const val MENU = 0x40
    const val GUIDE = 0x8D
    const val CHANNEL_UP = 0x9C
    const val CHANNEL_DOWN = 0x9D
    const val FAST_FORWARD = 0xB3
    const val REWIND = 0xB4
    const val NEXT = 0xB5
    const val PREVIOUS = 0xB6
    const val PLAY_PAUSE = 0xCD
    const val MUTE = 0xE2
    const val VOLUME_UP = 0xE9
    const val VOLUME_DOWN = 0xEA
    const val CONTROL_PANEL = 0x19F
    const val SEARCH = 0x221
    const val HOME = 0x223
    const val BACK = 0x224
}

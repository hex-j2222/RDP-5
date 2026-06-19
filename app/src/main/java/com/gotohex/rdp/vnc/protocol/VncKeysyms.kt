package com.gotohex.rdp.vnc.protocol

/**
 * Maps PC keyboard scan codes (the same set used by [com.gotohex.rdp.ui.screens.ExtraKeysBar]
 * and physical-keyboard input throughout the session UI) to X11 keysyms, as
 * required by RFB's KeyEvent message (RFC 6143 §7.5.4).
 *
 * Only covers the keys the app's UI can actually generate (extra-keys bar +
 * common navigation/function keys); printable characters are sent directly
 * via [com.gotohex.rdp.vnc.protocol.VncClient.sendUnicodeChar] instead, since
 * Latin-1/Unicode codepoints below 0x100 are valid keysyms as-is.
 */
object VncKeysyms {
    const val XK_BACKSPACE = 0xFF08
    const val XK_TAB = 0xFF09
    const val XK_RETURN = 0xFF0D
    const val XK_ESCAPE = 0xFF1B
    const val XK_DELETE = 0xFFFF
    const val XK_HOME = 0xFF50
    const val XK_END = 0xFF57
    const val XK_PAGE_UP = 0xFF55
    const val XK_PAGE_DOWN = 0xFF56
    const val XK_INSERT = 0xFF63
    const val XK_PRINT = 0xFF61

    const val XK_LEFT = 0xFF51
    const val XK_UP = 0xFF52
    const val XK_RIGHT = 0xFF53
    const val XK_DOWN = 0xFF54

    const val XK_SHIFT_L = 0xFFE1
    const val XK_CONTROL_L = 0xFFE3
    const val XK_ALT_L = 0xFFE9
    const val XK_SUPER_L = 0xFFEB // "Windows" key

    const val XK_F1 = 0xFFBE
    // F1..F12 are contiguous in the X11 keysym space.
    fun fKey(n: Int): Int = XK_F1 + (n - 1)

    /**
     * @param scanCode PC/AT scan code as used elsewhere in this app (see
     *        [com.gotohex.rdp.ui.screens.ExtraKeysBar]'s `SpecialKey.scanCode`).
     * @param extended whether the E0-prefixed ("extended") form was set.
     */
    fun scanCodeToKeysym(scanCode: Int, extended: Boolean): Int? = when (scanCode) {
        0x01 -> XK_ESCAPE
        0x0F -> XK_TAB
        0x1D -> XK_CONTROL_L
        0x38 -> XK_ALT_L
        0x5B -> XK_SUPER_L
        0x53 -> if (extended) XK_DELETE else null // numpad '.' otherwise, unused here
        0x47 -> if (extended) XK_HOME else null
        0x4F -> if (extended) XK_END else null
        0x49 -> if (extended) XK_PAGE_UP else null
        0x51 -> if (extended) XK_PAGE_DOWN else null
        0x52 -> if (extended) XK_INSERT else null
        0x37 -> if (extended) XK_PRINT else null
        0x3B -> fKey(1)
        0x3C -> fKey(2)
        0x3D -> fKey(3)
        0x3E -> fKey(4)
        0x3F -> fKey(5)
        0x40 -> fKey(6)
        0x41 -> fKey(7)
        0x42 -> fKey(8)
        0x43 -> fKey(9)
        0x44 -> fKey(10)
        0x57 -> fKey(11)
        0x58 -> fKey(12)
        0x0E -> XK_BACKSPACE
        0x1C -> XK_RETURN
        0x48 -> XK_UP
        0x50 -> XK_DOWN
        0x4B -> XK_LEFT
        0x4D -> XK_RIGHT
        else -> null
    }
}

package app.padly.android.proto

import android.view.KeyEvent

/**
 * HID Usage Page 0x07 keycodes — Android KeyEvent → HID mapping.
 * Used by transport.Encoder when forwarding keyboard input to the Mac.
 */
object HidCodes {
    /** Android KeyEvent.KEYCODE_* → HID Usage Page 0x07 code. Returns 0 if unmapped. */
    fun fromAndroid(keyCode: Int): UShort = (when (keyCode) {
        KeyEvent.KEYCODE_A -> 0x04
        KeyEvent.KEYCODE_B -> 0x05
        KeyEvent.KEYCODE_C -> 0x06
        KeyEvent.KEYCODE_D -> 0x07
        KeyEvent.KEYCODE_E -> 0x08
        KeyEvent.KEYCODE_F -> 0x09
        KeyEvent.KEYCODE_G -> 0x0A
        KeyEvent.KEYCODE_H -> 0x0B
        KeyEvent.KEYCODE_I -> 0x0C
        KeyEvent.KEYCODE_J -> 0x0D
        KeyEvent.KEYCODE_K -> 0x0E
        KeyEvent.KEYCODE_L -> 0x0F
        KeyEvent.KEYCODE_M -> 0x10
        KeyEvent.KEYCODE_N -> 0x11
        KeyEvent.KEYCODE_O -> 0x12
        KeyEvent.KEYCODE_P -> 0x13
        KeyEvent.KEYCODE_Q -> 0x14
        KeyEvent.KEYCODE_R -> 0x15
        KeyEvent.KEYCODE_S -> 0x16
        KeyEvent.KEYCODE_T -> 0x17
        KeyEvent.KEYCODE_U -> 0x18
        KeyEvent.KEYCODE_V -> 0x19
        KeyEvent.KEYCODE_W -> 0x1A
        KeyEvent.KEYCODE_X -> 0x1B
        KeyEvent.KEYCODE_Y -> 0x1C
        KeyEvent.KEYCODE_Z -> 0x1D
        KeyEvent.KEYCODE_1 -> 0x1E
        KeyEvent.KEYCODE_2 -> 0x1F
        KeyEvent.KEYCODE_3 -> 0x20
        KeyEvent.KEYCODE_4 -> 0x21
        KeyEvent.KEYCODE_5 -> 0x22
        KeyEvent.KEYCODE_6 -> 0x23
        KeyEvent.KEYCODE_7 -> 0x24
        KeyEvent.KEYCODE_8 -> 0x25
        KeyEvent.KEYCODE_9 -> 0x26
        KeyEvent.KEYCODE_0 -> 0x27
        KeyEvent.KEYCODE_ENTER -> 0x28
        KeyEvent.KEYCODE_ESCAPE -> 0x29
        KeyEvent.KEYCODE_DEL -> 0x2A
        KeyEvent.KEYCODE_TAB -> 0x2B
        KeyEvent.KEYCODE_SPACE -> 0x2C
        KeyEvent.KEYCODE_MINUS -> 0x2D
        KeyEvent.KEYCODE_EQUALS -> 0x2E
        KeyEvent.KEYCODE_LEFT_BRACKET -> 0x2F
        KeyEvent.KEYCODE_RIGHT_BRACKET -> 0x30
        KeyEvent.KEYCODE_BACKSLASH -> 0x31
        KeyEvent.KEYCODE_SEMICOLON -> 0x33
        KeyEvent.KEYCODE_APOSTROPHE -> 0x34
        KeyEvent.KEYCODE_GRAVE -> 0x35
        KeyEvent.KEYCODE_COMMA -> 0x36
        KeyEvent.KEYCODE_PERIOD -> 0x37
        KeyEvent.KEYCODE_SLASH -> 0x38
        KeyEvent.KEYCODE_F1 -> 0x3A
        KeyEvent.KEYCODE_F2 -> 0x3B
        KeyEvent.KEYCODE_F3 -> 0x3C
        KeyEvent.KEYCODE_F4 -> 0x3D
        KeyEvent.KEYCODE_F5 -> 0x3E
        KeyEvent.KEYCODE_F6 -> 0x3F
        KeyEvent.KEYCODE_F7 -> 0x40
        KeyEvent.KEYCODE_F8 -> 0x41
        KeyEvent.KEYCODE_F9 -> 0x42
        KeyEvent.KEYCODE_F10 -> 0x43
        KeyEvent.KEYCODE_F11 -> 0x44
        KeyEvent.KEYCODE_F12 -> 0x45
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0x4F
        KeyEvent.KEYCODE_DPAD_LEFT -> 0x50
        KeyEvent.KEYCODE_DPAD_DOWN -> 0x51
        KeyEvent.KEYCODE_DPAD_UP -> 0x52
        KeyEvent.KEYCODE_MOVE_HOME -> 0x4A
        KeyEvent.KEYCODE_MOVE_END -> 0x4D
        KeyEvent.KEYCODE_PAGE_UP -> 0x4B
        KeyEvent.KEYCODE_PAGE_DOWN -> 0x4E
        KeyEvent.KEYCODE_INSERT -> 0x49
        KeyEvent.KEYCODE_FORWARD_DEL -> 0x4C
        else -> 0
    }).toUShort()

    /** ASCII char → HID code for typed characters that the soft keyboard delivers. */
    fun fromChar(c: Char): UShort {
        if (c in 'a'..'z') return ((c - 'a') + 0x04).toUShort()
        if (c in 'A'..'Z') return ((c - 'A') + 0x04).toUShort()
        if (c == '0') return 0x27u
        if (c in '1'..'9') return ((c - '1') + 0x1E).toUShort()
        return when (c) {
            ' ' -> 0x2Cu
            '\n' -> 0x28u
            '\t' -> 0x2Bu
            '-', '_' -> 0x2Du
            '=', '+' -> 0x2Eu
            '[', '{' -> 0x2Fu
            ']', '}' -> 0x30u
            '\\', '|' -> 0x31u
            ';', ':' -> 0x33u
            '\'', '"' -> 0x34u
            '`', '~' -> 0x35u
            ',', '<' -> 0x36u
            '.', '>' -> 0x37u
            '/', '?' -> 0x38u
            else -> 0u
        }
    }
}

package app.padly.android

import app.padly.android.proto.HidCodes
import app.padly.android.ui.textDiff
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardInputTest {
    @Test
    fun appendIsPureInsert() = assertEquals(0 to "lo", textDiff("hel", "hello"))

    @Test
    fun autocorrectIsDeleteThenRetype() = assertEquals(1 to "lo", textDiff("helo", "hello"))

    @Test
    fun clearingSendsOneBackspacePerChar() = assertEquals(3 to "", textDiff("abc", ""))

    @Test
    fun emojiDeletesAsOneCharacter() = assertEquals(1 to "", textDiff("a😀", "a"))

    @Test
    fun cyrillicInsertIsPlainText() = assertEquals(0 to "привет", textDiff("", "привет"))

    @Test
    fun cyrillicShortcutsHitLatinPhysicalKeys() {
        assertEquals(HidCodes.fromChar('c'), HidCodes.fromCyrillic('с'))
        assertEquals(HidCodes.fromChar('v'), HidCodes.fromCyrillic('м'))
        assertEquals(HidCodes.fromChar('q'), HidCodes.fromCyrillic('Й'))
        assertEquals(0.toUShort(), HidCodes.fromCyrillic('z'))
    }
}

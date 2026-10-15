package app.padly.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.padly.android.proto.HidCodes
import app.padly.android.proto.ModifierBits
import app.padly.android.proto.PadlyMessage
import app.padly.android.transport.WSClient

private const val HID_RETURN: UShort = 0x28u
private const val HID_ESCAPE: UShort = 0x29u
private const val HID_BACKSPACE: UShort = 0x2Au
private const val HID_TAB: UShort = 0x2Bu
private const val HID_RIGHT: UShort = 0x4Fu
private const val HID_LEFT: UShort = 0x50u
private const val HID_DOWN: UShort = 0x51u
private const val HID_UP: UShort = 0x52u

@Composable
fun KeyboardOverlay(connection: WSClient?) {
    var text by remember { mutableStateOf("") }
    var modifiers by remember { mutableStateOf<UByte>(0u) }

    fun now() = System.currentTimeMillis().toULong()
    fun tap(code: UShort, mods: UByte = modifiers) {
        connection?.send(PadlyMessage.Key(code, mods, true, now()))
        connection?.send(PadlyMessage.Key(code, mods, false, now()))
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0E0E11)).padding(16.dp)) {
        Text("Клавиатура", color = Color(0xFFEDEDEF), style = TextStyle(fontSize = 16.sp))
        Spacer(modifier = Modifier.padding(top = 8.dp))

        ModifierRow(value = modifiers, onChange = { modifiers = it })

        Spacer(modifier = Modifier.padding(top = 12.dp))

        OutlinedTextField(
            value = text,
            onValueChange = { new ->
                val (backspaces, inserted) = textDiff(text, new)
                repeat(backspaces) { tap(HID_BACKSPACE, 0u) }
                sendInserted(connection, inserted, modifiers, ::tap)
                text = new
            },
            label = { Text("Печатай здесь — текст уйдёт на Mac") },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF1F1F24),
                unfocusedContainerColor = Color(0xFF1F1F24),
                focusedTextColor = Color(0xFFEDEDEF),
                unfocusedTextColor = Color(0xFFEDEDEF),
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.padding(top = 12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            SpecialKey("esc", Modifier.weight(1f)) { tap(HID_ESCAPE) }
            SpecialKey("⇥", Modifier.weight(1f)) { tap(HID_TAB) }
            SpecialKey("⌫", Modifier.weight(1f)) { tap(HID_BACKSPACE) }
            SpecialKey("⏎", Modifier.weight(1f)) { tap(HID_RETURN) }
        }
        Spacer(modifier = Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            SpecialKey("←", Modifier.weight(1f)) { tap(HID_LEFT) }
            SpecialKey("↑", Modifier.weight(1f)) { tap(HID_UP) }
            SpecialKey("↓", Modifier.weight(1f)) { tap(HID_DOWN) }
            SpecialKey("→", Modifier.weight(1f)) { tap(HID_RIGHT) }
        }

        Spacer(modifier = Modifier.padding(top = 16.dp))

        Box(modifier = Modifier.fillMaxWidth().background(Color(0x14_7C_5C_FF), RoundedCornerShape(8.dp)).padding(12.dp)) {
            Text(
                "Включи ⌘/⌃/⌥ и нажми букву — уйдёт сочетание (⌘+C, ⌘+Tab…). Работает и на русской раскладке.",
                color = Color(0xFF8C8C97),
                style = TextStyle(fontSize = 12.sp),
            )
        }
    }
}

/**
 * Plain text goes as one Unicode `text` message (any language, independent of
 * the Mac's layout). With ⌘/⌃/⌥ active, characters become key shortcuts on the
 * physical key they sit on — Cyrillic included, so ⌘+С copies like ⌘+C.
 */
private fun sendInserted(
    connection: WSClient?,
    inserted: String,
    modifiers: UByte,
    tap: (UShort, UByte) -> Unit,
) {
    if (inserted.isEmpty()) return
    val shortcutMods = ModifierBits.CMD or ModifierBits.CTRL or ModifierBits.OPT
    val chunk = StringBuilder()
    fun flush() {
        if (chunk.isEmpty()) return
        var s = chunk.toString()
        if ((modifiers and ModifierBits.SHIFT) != 0u.toUByte()) s = s.uppercase()
        connection?.send(PadlyMessage.Text(s, System.currentTimeMillis().toULong()))
        chunk.clear()
    }
    for (ch in inserted) {
        when {
            ch == '\n' -> { flush(); tap(HID_RETURN, 0u) }
            ch == '\t' -> { flush(); tap(HID_TAB, 0u) }
            (modifiers and shortcutMods) != 0u.toUByte() -> {
                flush()
                val code = HidCodes.fromChar(ch).takeIf { it != 0u.toUShort() } ?: HidCodes.fromCyrillic(ch)
                if (code != 0u.toUShort()) {
                    val shift = if (ch.isUpperCase()) ModifierBits.SHIFT else 0u
                    tap(code, modifiers or shift)
                }
            }
            else -> chunk.append(ch)
        }
    }
    flush()
}

/**
 * What changed between two versions of the field: how many characters were
 * deleted from the end of the common prefix, and what was inserted after it.
 * Handles IME autocorrect (which rewrites words) as delete + retype. Deletions
 * are counted in code points so an emoji costs one backspace, not two.
 */
internal fun textDiff(old: String, new: String): Pair<Int, String> {
    var prefix = 0
    val max = minOf(old.length, new.length)
    while (prefix < max && old[prefix] == new[prefix]) prefix++
    if (prefix > 0 && Character.isHighSurrogate(old[prefix - 1]) && prefix < old.length) prefix--
    val removed = old.substring(prefix)
    return removed.codePointCount(0, removed.length) to new.substring(prefix)
}

@Composable
private fun SpecialKey(label: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Text(label, style = TextStyle(fontSize = 14.sp))
    }
}

@Composable
private fun ModifierRow(value: UByte, onChange: (UByte) -> Unit) {
    val chips = listOf(
        "⌘" to ModifierBits.CMD,
        "⌥" to ModifierBits.OPT,
        "⌃" to ModifierBits.CTRL,
        "⇧" to ModifierBits.SHIFT,
        "fn" to ModifierBits.FN,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        chips.forEach { (label, bit) ->
            FilterChip(
                selected = (value and bit) != 0u.toUByte(),
                onClick = { onChange(value xor bit) },
                label = { Text(label) },
            )
        }
    }
}

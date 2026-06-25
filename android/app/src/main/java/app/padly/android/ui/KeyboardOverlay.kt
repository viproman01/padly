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

@Composable
fun KeyboardOverlay(connection: WSClient?) {
    var text by remember { mutableStateOf("") }
    var modifiers by remember { mutableStateOf<UByte>(0u) }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0E0E11)).padding(16.dp)) {
        Text("Keyboard", color = Color(0xFFEDEDEF), style = TextStyle(fontSize = 16.sp))
        Spacer(modifier = Modifier.padding(top = 8.dp))

        ModifierRow(value = modifiers, onChange = { modifiers = it })

        Spacer(modifier = Modifier.padding(top = 12.dp))

        OutlinedTextField(
            value = text,
            onValueChange = { new ->
                val added = new.removePrefix(text)
                added.forEach { ch ->
                    val code = HidCodes.fromChar(ch)
                    if (code != 0u.toUShort()) {
                        val mods = if (ch.isUpperCase() || ch in "~!@#\$%^&*()_+{}|:\"<>?") ModifierBits.SHIFT else 0u
                        connection?.send(PadlyMessage.Key(code, mods or modifiers, true, System.currentTimeMillis().toULong()))
                        connection?.send(PadlyMessage.Key(code, mods or modifiers, false, System.currentTimeMillis().toULong()))
                    }
                }
                text = new
            },
            label = { Text("Type here") },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF1F1F24),
                unfocusedContainerColor = Color(0xFF1F1F24),
                focusedTextColor = Color(0xFFEDEDEF),
                unfocusedTextColor = Color(0xFFEDEDEF),
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.padding(top = 16.dp))

        Box(modifier = Modifier.fillMaxWidth().background(Color(0x14_7C_5C_FF), RoundedCornerShape(8.dp)).padding(12.dp)) {
            Text(
                "Hold a modifier chip before tapping a letter to send shortcuts like ⌘+C.",
                color = Color(0xFF8C8C97),
                style = TextStyle(fontSize = 12.sp),
            )
        }
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

package app.padly.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.padly.android.auth.SecretStore
import app.padly.android.settings.SettingsStore
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val store = remember { SecretStore(context) }
    val scope = rememberCoroutineScope()

    val sensitivity by settings.cursorSensitivity.collectAsState(initial = 1.0f)
    val natural by settings.naturalScroll.collectAsState(initial = true)
    val haptic by settings.hapticEnabled.collectAsState(initial = true)
    val hapticStrength by settings.hapticStrength.collectAsState(initial = 0.6f)

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0E0E11)).padding(20.dp)) {
        Text("Settings", color = Color(0xFFEDEDEF), style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold))
        Spacer(modifier = Modifier.padding(top = 16.dp))

        SectionLabel("Cursor")
        SliderRow(label = "Sensitivity", value = sensitivity, range = 0.3f..3.0f) {
            scope.launch { settings.setCursorSensitivity(it) }
        }

        SwitchRow(label = "Natural scroll", checked = natural) {
            scope.launch { settings.setNaturalScroll(it) }
        }

        Spacer(modifier = Modifier.padding(top = 16.dp))
        SectionLabel("Feedback")
        SwitchRow(label = "Haptic on tap", checked = haptic) {
            scope.launch { settings.setHapticEnabled(it) }
        }
        SliderRow(label = "Haptic strength", value = hapticStrength, range = 0f..1f) {
            scope.launch { settings.setHapticStrength(it) }
        }

        Spacer(modifier = Modifier.padding(top = 16.dp))
        SectionLabel("Paired Macs")
        store.list().forEach { mac ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(mac.name, color = Color(0xFFEDEDEF))
                    Text("${mac.ip}:${mac.port}", color = Color(0xFF8C8C97), style = TextStyle(fontSize = 11.sp))
                }
                Text("Forget", color = Color(0xFFFB7185), style = TextStyle(fontSize = 13.sp), modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (store.list().isEmpty()) {
            Text("No paired Macs.", color = Color(0xFF8C8C97), style = TextStyle(fontSize = 12.sp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), color = Color(0xFF8C8C97), style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold))
    Spacer(modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color(0xFFEDEDEF))
            Text("%.2f".format(value), color = Color(0xFF8C8C97), style = TextStyle(fontSize = 12.sp))
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color(0xFFEDEDEF), modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

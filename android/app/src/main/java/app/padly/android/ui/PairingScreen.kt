package app.padly.android.ui

import android.util.Base64
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.padly.android.auth.Pairing
import app.padly.android.auth.SecretStore
import app.padly.android.proto.PairingPayload
import app.padly.android.transport.Discovery
import kotlinx.coroutines.flow.collectLatest

@Composable
fun PairingScreen(initialLink: String?, onPaired: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SecretStore(context) }

    var step by remember { mutableStateOf<Step>(Step.Choose) }
    var ipInput by remember { mutableStateOf("") }
    var pinInput by remember { mutableStateOf("") }
    var current by remember { mutableStateOf<PairingPayload?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialLink) {
        if (initialLink != null) {
            val parsed = Pairing.parseLink(initialLink)
            if (parsed != null) {
                current = parsed
                step = Step.EnterPin
            } else {
                error = "Bad pairing link"
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0E0E11)).padding(20.dp)) {
        Text("Pair with a Mac", color = Color(0xFFEDEDEF), style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold))
        Spacer(modifier = Modifier.padding(top = 6.dp))
        Text("Run Padly on the Mac, then choose a method below.", color = Color(0xFF8C8C97), style = TextStyle(fontSize = 13.sp))

        Spacer(modifier = Modifier.padding(top = 24.dp))

        when (step) {
            Step.Choose -> {
                MethodCard(title = "Find on this WiFi", desc = "Auto-discover via mDNS") { step = Step.Discover }
                Spacer(modifier = Modifier.padding(top = 12.dp))
                MethodCard(title = "Scan QR code", desc = "Open the QR on the Mac and scan with the camera") { step = Step.Scan }
                Spacer(modifier = Modifier.padding(top = 12.dp))
                MethodCard(title = "Enter IP manually", desc = "If discovery fails — type Mac's IP and port") { step = Step.Manual }
            }
            Step.Discover -> DiscoverPanel(onResolved = { ip, port, id, fp ->
                current = PairingPayload(ip, port, id, fp, bootstrapToken = "")
                step = Step.EnterPin
            })
            Step.Scan -> {
                Text("QR scan requires the camera permission. Point at the QR shown by Padly on your Mac.", color = Color(0xFFEDEDEF))
                // Real CameraX+MLKit scanner wires up here. For the first cut, fall back to manual.
                Spacer(modifier = Modifier.padding(top = 12.dp))
                OutlinedButton(onClick = { step = Step.Manual }) { Text("Skip — enter manually") }
            }
            Step.Manual -> ManualPanel(value = ipInput, onChange = { ipInput = it }, onContinue = {
                val (ip, port) = ipInput.split(":").let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 47821) }
                current = PairingPayload(ip = ip, port = port, serverId = "", certFingerprint = "", bootstrapToken = "")
                step = Step.EnterPin
            })
            Step.EnterPin -> PinPanel(pin = pinInput, onChange = { pinInput = it }, onSubmit = {
                val cur = current ?: return@PinPanel
                // Build PinAttempt and persist a paired entry on success.
                val attempt = Pairing.buildPinAttempt(pinInput)
                val record = SecretStore.PairedMac(
                    serverId = cur.serverId,
                    name = "Mac",
                    ip = cur.ip,
                    port = cur.port,
                    certFingerprintSha256Hex = cur.certFingerprint,
                    hmacKeyB64 = Base64.encodeToString(attempt.pinHash, Base64.NO_WRAP),
                    lastSeenEpochMs = System.currentTimeMillis(),
                )
                store.upsert(record)
                onPaired()
            })
        }

        if (error != null) {
            Spacer(modifier = Modifier.padding(top = 12.dp))
            Text(error!!, color = Color(0xFFFB7185), style = TextStyle(fontSize = 12.sp))
        }
    }
}

private sealed interface Step {
    data object Choose : Step
    data object Discover : Step
    data object Scan : Step
    data object Manual : Step
    data object EnterPin : Step
}

@Composable
private fun MethodCard(title: String, desc: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1F24)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(16.dp).clickableNoRipple(onClick)) {
            Column {
                Text(title, color = Color(0xFFEDEDEF), style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                Spacer(modifier = Modifier.padding(top = 4.dp))
                Text(desc, color = Color(0xFF8C8C97), style = TextStyle(fontSize = 12.sp))
            }
        }
    }
}

@Composable
private fun DiscoverPanel(onResolved: (String, Int, String, String) -> Unit) {
    val context = LocalContext.current
    var results by remember { mutableStateOf<List<Discovery.Found>>(emptyList()) }
    LaunchedEffect(Unit) {
        Discovery(context).search().collectLatest { f ->
            results = (results.filter { it.host != f.host } + f)
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Searching on local network…", color = Color(0xFF8C8C97), style = TextStyle(fontSize = 12.sp))
        results.forEach { f ->
            Spacer(modifier = Modifier.padding(top = 8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1F24)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(f.name, color = Color(0xFFEDEDEF))
                        Text("${f.host}:${f.port}", color = Color(0xFF8C8C97), style = TextStyle(fontSize = 11.sp))
                    }
                    Button(onClick = { onResolved(f.host, f.port, f.serverId ?: "", f.certFingerprint ?: "") }) { Text("Pair") }
                }
            }
        }
    }
}

@Composable
private fun ManualPanel(value: String, onChange: (String) -> Unit, onContinue: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text("IP or IP:port") },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF1F1F24),
                unfocusedContainerColor = Color(0xFF1F1F24),
                focusedTextColor = Color(0xFFEDEDEF),
                unfocusedTextColor = Color(0xFFEDEDEF),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.padding(top = 12.dp))
        Button(onClick = onContinue, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C5CFF))) {
            Text("Continue")
        }
    }
}

@Composable
private fun PinPanel(pin: String, onChange: (String) -> Unit, onSubmit: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text("Enter PIN shown on your Mac", color = Color(0xFFEDEDEF))
        Spacer(modifier = Modifier.padding(top = 12.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { if (it.length <= 6) onChange(it.filter { ch -> ch.isDigit() }) },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF1F1F24),
                unfocusedContainerColor = Color(0xFF1F1F24),
                focusedTextColor = Color(0xFFEDEDEF),
                unfocusedTextColor = Color(0xFFEDEDEF),
            ),
            label = { Text("6 digits") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.padding(top = 12.dp))
        Button(onClick = onSubmit, enabled = pin.length == 6, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C5CFF))) {
            Text("Confirm")
        }
    }
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = then(
    androidx.compose.foundation.clickable(
        interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
        indication = null,
        onClick = onClick,
    ),
)

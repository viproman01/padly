package app.padly.android.ui

import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import app.padly.android.transport.WSClient
import kotlinx.coroutines.flow.collectLatest

/**
 * Pairing flow: pick a Mac (mDNS / QR link / manual IP), connect, then type
 * the PIN the Mac displays. On pairOk the long-term key is stored and the
 * caller flips to the trackpad.
 */
@Composable
fun PairingScreen(initialLink: String?, onPaired: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SecretStore(context) }

    var step by remember { mutableStateOf<Step>(Step.Choose) }
    var ipInput by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<PairingPayload?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialLink) {
        if (initialLink != null) {
            val parsed = Pairing.parseLink(initialLink)
            if (parsed != null) {
                target = parsed
                step = Step.Connect
            } else {
                error = "Неверная ссылка для пары"
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0E0E11)).padding(20.dp)) {
        Text("Подключение к Mac", color = Color(0xFFEDEDEF), style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold))
        Spacer(modifier = Modifier.padding(top = 6.dp))
        Text("Открой Padly на Mac → «Подключить телефон…» — там появится PIN.", color = Color(0xFF8C8C97), style = TextStyle(fontSize = 13.sp))

        Spacer(modifier = Modifier.padding(top = 24.dp))

        when (step) {
            Step.Choose -> {
                MethodCard(title = "Найти в этой WiFi", desc = "Телефон и Mac должны быть в одной сети") { step = Step.Discover }
                Spacer(modifier = Modifier.padding(top = 12.dp))
                MethodCard(title = "Ввести IP вручную", desc = "Если поиск не нашёл — IP показан в меню Padly на Mac") { step = Step.Manual }
            }
            Step.Discover -> DiscoverPanel(onResolved = { ip, port, id, name ->
                target = PairingPayload(ip = ip, port = port, serverId = id, name = name)
                step = Step.Connect
            })
            Step.Manual -> ManualPanel(value = ipInput, onChange = { ipInput = it }, onContinue = {
                val (ip, port) = ipInput.split(":").let { it[0].trim() to (it.getOrNull(1)?.trim()?.toIntOrNull() ?: 47821) }
                if (ip.isNotEmpty()) {
                    target = PairingPayload(ip = ip, port = port, serverId = "", name = "Mac")
                    step = Step.Connect
                }
            })
            Step.Connect -> {
                val payload = target
                if (payload == null) {
                    step = Step.Choose
                } else {
                    ConnectAndPinPanel(
                        payload = payload,
                        store = store,
                        onPaired = onPaired,
                        onError = { msg ->
                            error = msg
                            step = Step.Choose
                        },
                    )
                }
            }
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
    data object Manual : Step
    data object Connect : Step
}

/** Owns a WSClient for the duration of the pairing attempt. */
@Composable
private fun ConnectAndPinPanel(
    payload: PairingPayload,
    store: SecretStore,
    onPaired: () -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }

    val client = remember(payload) {
        WSClient(
            host = payload.ip,
            port = payload.port,
            deviceId = store.deviceId(),
            deviceName = android.os.Build.MODEL ?: "Android",
            ltk = null,
            onPaired = { serverId, name, ltk ->
                store.upsert(
                    SecretStore.PairedMac(
                        serverId = serverId,
                        name = name,
                        ip = payload.ip,
                        port = payload.port,
                        ltkB64 = Base64.encodeToString(ltk, Base64.NO_WRAP),
                        lastSeenEpochMs = System.currentTimeMillis(),
                    ),
                )
            },
        ).also { it.connect() }
    }
    DisposableEffect(client) {
        onDispose { client.close() }
    }

    val state by client.state.collectAsState()

    LaunchedEffect(state) {
        when (val s = state) {
            is WSClient.State.Ready -> {
                client.close()
                onPaired()
            }
            is WSClient.State.Failed -> onError(friendlyError(s.reason))
            else -> {}
        }
    }

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        when (val s = state) {
            is WSClient.State.Connecting, WSClient.State.Handshaking, WSClient.State.Idle -> {
                CircularProgressIndicator(color = Color(0xFF7C5CFF))
                Spacer(modifier = Modifier.padding(top = 12.dp))
                Text("Подключаюсь к ${payload.name}…", color = Color(0xFF8C8C97))
            }
            is WSClient.State.NeedPin, is WSClient.State.PinRejected -> {
                if (s is WSClient.State.PinRejected) {
                    Text("Неверный PIN — осталось попыток: ${s.attemptsLeft}", color = Color(0xFFFB7185), style = TextStyle(fontSize = 12.sp))
                    Spacer(modifier = Modifier.padding(top = 8.dp))
                }
                Text("Введи PIN из меню Padly на Mac", color = Color(0xFFEDEDEF))
                Spacer(modifier = Modifier.padding(top = 12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 6) pin = it.filter { ch -> ch.isDigit() } },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF1F1F24),
                        unfocusedContainerColor = Color(0xFF1F1F24),
                        focusedTextColor = Color(0xFFEDEDEF),
                        unfocusedTextColor = Color(0xFFEDEDEF),
                    ),
                    label = { Text("6 цифр") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.padding(top = 12.dp))
                Button(
                    onClick = { client.submitPin(pin) },
                    enabled = pin.length == 6,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C5CFF)),
                ) { Text("Подтвердить") }
            }
            is WSClient.State.Ready -> {
                Text("Готово!", color = Color(0xFF34D399))
            }
            is WSClient.State.Failed -> {
                Text(s.reason, color = Color(0xFFFB7185))
            }
        }
    }
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
        Text("Ищу Mac в сети…", color = Color(0xFF8C8C97), style = TextStyle(fontSize = 12.sp))
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
                    Button(onClick = { onResolved(f.host, f.port, f.serverId ?: "", f.name) }) { Text("Выбрать") }
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
            label = { Text("IP или IP:порт") },
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
            Text("Дальше")
        }
    }
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )

/** Human wording for WSClient failure reasons shown on the pairing screen. */
private fun friendlyError(reason: String): String = when {
    "pairing-not-active" in reason -> "На Mac не открыт PIN: нажми «Подключить телефон…» в меню Padly и попробуй снова."
    "expired" in reason -> "PIN истёк — возьми новый в меню Padly на Mac."
    "locked" in reason -> "Слишком много неверных PIN — подожди минуту."
    else -> "Не удалось подключиться к Mac. Проверь, что Padly запущен и оба устройства в одной WiFi. ($reason)"
}

package app.padly.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.Settings
import app.padly.android.auth.SecretStore
import app.padly.android.transport.WSClient

@Composable
fun MainScreen(initialPairingLink: String?) {
    val context = LocalContext.current
    val store = remember { SecretStore(context) }
    val paired = remember { store.list() }

    var screen by rememberSaveable { mutableStateOf(if (paired.isEmpty()) Screen.Pairing else Screen.Trackpad) }
    var pairedNow by remember { mutableStateOf(paired.isNotEmpty()) }

    // The connection is owned by the MainScreen so all child surfaces share one socket.
    val connection = remember(pairedNow) {
        if (!pairedNow) null else store.list().firstOrNull()?.let {
            WSClient(
                host = it.ip,
                port = it.port,
                certPinSha256Hex = it.certFingerprintSha256Hex,
                hmacKey = android.util.Base64.decode(it.hmacKeyB64, android.util.Base64.NO_WRAP),
            ).also { c -> c.connect() }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0E0E11))) {
        Box(modifier = Modifier.weight(1f)) {
            when (screen) {
                Screen.Trackpad -> TrackpadSurface(connection = connection)
                Screen.Keyboard -> KeyboardOverlay(connection = connection)
                Screen.Presenter -> PresenterMode(connection = connection)
                Screen.Settings -> SettingsScreen()
                Screen.Pairing -> PairingScreen(
                    initialLink = initialPairingLink,
                    onPaired = {
                        pairedNow = true
                        screen = Screen.Trackpad
                    },
                )
            }
        }
        if (pairedNow) {
            HorizontalDivider(color = Color(0xFF1F1F24), thickness = 1.dp)
            BottomNav(
                current = screen,
                onSelected = { screen = it },
            )
        }
    }
}

enum class Screen { Trackpad, Keyboard, Presenter, Settings, Pairing }

@Composable
private fun BottomNav(current: Screen, onSelected: (Screen) -> Unit) {
    NavigationBar(containerColor = Color(0xFF0E0E11)) {
        NavigationBarItem(
            selected = current == Screen.Trackpad,
            onClick = { onSelected(Screen.Trackpad) },
            icon = { Icon(Icons.Outlined.Gesture, contentDescription = null) },
            label = { Text("Trackpad") },
        )
        NavigationBarItem(
            selected = current == Screen.Keyboard,
            onClick = { onSelected(Screen.Keyboard) },
            icon = { Icon(Icons.Outlined.Keyboard, contentDescription = null) },
            label = { Text("Keyboard") },
        )
        NavigationBarItem(
            selected = current == Screen.Presenter,
            onClick = { onSelected(Screen.Presenter) },
            icon = { Icon(Icons.Outlined.Slideshow, contentDescription = null) },
            label = { Text("Presenter") },
        )
        NavigationBarItem(
            selected = current == Screen.Settings,
            onClick = { onSelected(Screen.Settings) },
            icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
            label = { Text("Settings") },
        )
    }
}

package app.padly.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.padly.android.auth.Pairing
import app.padly.android.ui.MainScreen
import app.padly.android.ui.theme.PadlyTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialLink = intent.dataString
        setContent {
            PadlyTheme {
                val context = LocalContext.current
                var pendingLink by remember { mutableStateOf<String?>(initialLink) }
                LaunchedEffect(intent) {
                    intent.dataString?.let { pendingLink = it }
                }
                MainScreen(initialPairingLink = pendingLink)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

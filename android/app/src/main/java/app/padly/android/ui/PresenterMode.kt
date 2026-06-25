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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.padly.android.proto.PadlyMessage
import app.padly.android.proto.PresenterKind
import app.padly.android.transport.WSClient

@Composable
fun PresenterMode(connection: WSClient?) {
    var laserOn by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0E0E11)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Presenter", color = Color(0xFFEDEDEF), style = TextStyle(fontSize = 16.sp))
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                laserOn = false
                connection?.send(PadlyMessage.Presenter(PresenterKind.Stop, null, null, System.currentTimeMillis().toULong()))
            }) { Text("Exit") }
        }

        Spacer(modifier = Modifier.padding(top = 16.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x14_7C_5C_FF), RoundedCornerShape(12.dp))
                .padding(16.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("Slide controls", color = Color(0xFF8C8C97), style = TextStyle(fontSize = 12.sp))
                Spacer(modifier = Modifier.padding(top = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(
                        onClick = { connection?.send(PadlyMessage.Presenter(PresenterKind.Prev, null, null, System.currentTimeMillis().toULong())) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F1F24)),
                    ) { Text("◀  Prev", style = TextStyle(fontSize = 14.sp)) }
                    Button(
                        onClick = { connection?.send(PadlyMessage.Presenter(PresenterKind.Next, null, null, System.currentTimeMillis().toULong())) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C5CFF)),
                    ) { Text("Next  ▶", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold)) }
                }
            }
        }

        Spacer(modifier = Modifier.padding(top = 16.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1F1F24), RoundedCornerShape(12.dp))
                .padding(16.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = {
                        connection?.send(PadlyMessage.Presenter(PresenterKind.Black, null, null, System.currentTimeMillis().toULong()))
                    }) { Text("Black") }
                    OutlinedButton(onClick = {
                        connection?.send(PadlyMessage.Presenter(PresenterKind.White, null, null, System.currentTimeMillis().toULong()))
                    }) { Text("White") }
                }
            }
        }

        Spacer(modifier = Modifier.padding(top = 16.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (laserOn) Color(0x33_FB_71_85) else Color(0xFF1F1F24), RoundedCornerShape(12.dp))
                .padding(16.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (laserOn) "Drag on the area below to aim the laser." else "Tap to enable the laser pointer.",
                    color = Color(0xFFEDEDEF),
                    style = TextStyle(fontSize = 13.sp),
                )
                Spacer(modifier = Modifier.padding(top = 12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0E0E11), RoundedCornerShape(8.dp))
                        .padding(24.dp)
                        .pointerInteropFilter { ev ->
                            if (!laserOn) {
                                laserOn = true
                                connection?.send(PadlyMessage.Presenter(PresenterKind.Pointer, 0.5f, 0.5f, System.currentTimeMillis().toULong()))
                                return@pointerInteropFilter true
                            }
                            val nx = (ev.x / ev.device.motionRange?.range!!.coerceAtLeast(1f)).coerceIn(0f, 1f)
                            val ny = (ev.y / ev.device.motionRange?.range!!.coerceAtLeast(1f)).coerceIn(0f, 1f)
                            connection?.send(PadlyMessage.Presenter(PresenterKind.Pointer, nx, 1 - ny, System.currentTimeMillis().toULong()))
                            true
                        },
                ) {
                    Text(
                        if (laserOn) "● laser on" else "tap to start",
                        color = if (laserOn) Color(0xFFFB7185) else Color(0xFF8C8C97),
                        style = TextStyle(fontSize = 12.sp),
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }
}

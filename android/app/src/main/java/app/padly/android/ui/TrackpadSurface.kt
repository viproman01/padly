package app.padly.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.MotionEvent
import android.view.View
import app.padly.android.gesture.GestureClassifier
import app.padly.android.gesture.HapticDriver
import app.padly.android.proto.GestureKind
import app.padly.android.proto.GesturePhase
import app.padly.android.proto.MouseButton
import app.padly.android.proto.PadlyMessage
import app.padly.android.transport.WSClient
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Composable
fun TrackpadSurface(connection: WSClient?) {
    val context = LocalContext.current
    val haptic = remember { HapticDriver(context) }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0E0E11))) {
        TopStatusBar(connection = connection)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
                .background(Color(0x14_7C_5C_FF), RoundedCornerShape(16.dp)),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    TouchView(ctx) { action ->
                        dispatch(connection, haptic, action)
                    }
                },
            )
            Text(
                "1f cursor · 2f scroll · 3f spaces · 4f mission control",
                color = Color(0xFF8C8C97),
                style = TextStyle(fontSize = 11.sp),
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
            )
        }
    }
}

@Composable
private fun TopStatusBar(connection: WSClient?) {
    val ev = connection?.events?.collectAsState(initial = null)
    val state = ev?.value
    val (label, color) = when (state) {
        is WSClient.Event.Connected -> "Connected" to Color(0xFF4ADE80)
        is WSClient.Event.Connecting -> "Connecting…" to Color(0xFFFACC15)
        is WSClient.Event.Closed, is WSClient.Event.Error -> "Reconnecting…" to Color(0xFFFB7185)
        else -> "Idle" to Color(0xFF8C8C97)
    }
    Row(modifier = Modifier.fillMaxSize().height(36.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.background(color, RoundedCornerShape(50)).padding(6.dp))
        Spacer(modifier = Modifier.padding(4.dp))
        Text(label, color = Color(0xFFEDEDEF), style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium))
    }
}

private class TouchView(
    ctx: android.content.Context,
    private val onAction: (GestureClassifier.Action) -> Unit,
) : View(ctx) {
    private val classifier = GestureClassifier()
    override fun onTouchEvent(event: MotionEvent): Boolean {
        classifier.consume(event) { onAction(it) }
        return true
    }
}

private fun dispatch(
    connection: WSClient?,
    haptic: HapticDriver,
    action: GestureClassifier.Action,
) {
    val ts = System.currentTimeMillis().toULong()
    when (action) {
        is GestureClassifier.Action.CursorMove ->
            connection?.send(PadlyMessage.CursorMove(action.dx, action.dy, ts))
        is GestureClassifier.Action.Tap -> {
            haptic.tick()
            val btn = when (action.button) {
                GestureClassifier.Button.Left -> MouseButton.Left
                GestureClassifier.Button.Right -> MouseButton.Right
                GestureClassifier.Button.Middle -> MouseButton.Middle
            }
            connection?.send(PadlyMessage.CursorClick(btn, down = true, ts = ts))
            connection?.send(PadlyMessage.CursorClick(btn, down = false, ts = ts))
        }
        GestureClassifier.Action.DoubleTap -> {
            haptic.tick(0.9f)
            repeat(2) {
                connection?.send(PadlyMessage.CursorClick(MouseButton.Left, true, ts))
                connection?.send(PadlyMessage.CursorClick(MouseButton.Left, false, ts))
            }
        }
        is GestureClassifier.Action.Scroll ->
            connection?.send(PadlyMessage.Scroll(action.dx, action.dy, momentum = false, ts = ts))
        is GestureClassifier.Action.PinchUpdate -> {
            val buf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(action.scale).array()
            connection?.send(PadlyMessage.GestureMsg(GestureKind.PinchZoom, GesturePhase.Update, buf, ts))
        }
        is GestureClassifier.Action.Swipe3 -> {
            val buf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(action.distancePx).array()
            val kind = when (action.direction) {
                GestureClassifier.Direction.Up -> GestureKind.Swipe3Up
                GestureClassifier.Direction.Down -> GestureKind.Swipe3Down
                GestureClassifier.Direction.Left -> GestureKind.Swipe3Left
                GestureClassifier.Direction.Right -> GestureKind.Swipe3Right
            }
            connection?.send(PadlyMessage.GestureMsg(kind, GesturePhase.End, buf, ts))
        }
        is GestureClassifier.Action.Swipe4 -> {
            val buf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(action.distancePx).array()
            val kind = when (action.direction) {
                GestureClassifier.Direction.Up -> GestureKind.Swipe4Up
                GestureClassifier.Direction.Down -> GestureKind.Swipe4Down
                GestureClassifier.Direction.Left -> GestureKind.Swipe4Left
                GestureClassifier.Direction.Right -> GestureKind.Swipe4Right
            }
            connection?.send(PadlyMessage.GestureMsg(kind, GesturePhase.End, buf, ts))
        }
    }
}

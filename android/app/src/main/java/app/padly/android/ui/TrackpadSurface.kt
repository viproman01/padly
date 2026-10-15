package app.padly.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import app.padly.android.settings.SettingsStore
import app.padly.android.transport.WSClient
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Phone-side preferences applied to outgoing input. */
private data class InputPrefs(
    val sensitivity: Float,
    val naturalScroll: Boolean,
    val haptic: Boolean,
    val hapticStrength: Float,
)

@Composable
fun TrackpadSurface(connection: WSClient?, onRepair: () -> Unit) {
    val context = LocalContext.current
    val haptic = remember { HapticDriver(context) }
    val settings = remember { SettingsStore(context) }

    val sensitivity by settings.cursorSensitivity.collectAsState(initial = 1.0f)
    val natural by settings.naturalScroll.collectAsState(initial = true)
    val hapticOn by settings.hapticEnabled.collectAsState(initial = true)
    val hapticStrength by settings.hapticStrength.collectAsState(initial = 0.6f)
    // The touch view is created once; it reads the latest prefs through this State.
    val prefs = rememberUpdatedState(InputPrefs(sensitivity, natural, hapticOn, hapticStrength))

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0E0E11))) {
        TopStatusBar(connection = connection, onRepair = onRepair)
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
                        dispatch(connection, haptic, prefs, action)
                    }
                },
            )
            Text(
                "1 палец — курсор · тап — клик · 2 — скролл/зум, тап — правый клик · 3 — назад/вперёд · 4 — Mission Control / Spaces",
                color = Color(0xFF8C8C97),
                style = TextStyle(fontSize = 11.sp),
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
            )
        }
    }
}

@Composable
private fun TopStatusBar(connection: WSClient?, onRepair: () -> Unit) {
    val state = connection?.state?.collectAsState()?.value
    val (label, color) = when (state) {
        is WSClient.State.Ready -> "Подключено к ${state.macName}" to Color(0xFF4ADE80)
        is WSClient.State.Connecting, WSClient.State.Handshaking -> "Подключение…" to Color(0xFFFACC15)
        is WSClient.State.NeedPin, is WSClient.State.PinRejected -> "Mac не узнал телефон — нужна повторная пара" to Color(0xFFFB7185)
        is WSClient.State.Failed -> "Нет связи: ${state.reason}" to Color(0xFFFB7185)
        else -> "Не подключено" to Color(0xFF8C8C97)
    }
    val needsRepair = state is WSClient.State.NeedPin || state is WSClient.State.PinRejected ||
        (state is WSClient.State.Failed && state.reason.startsWith("pairing"))
    Row(
        modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(10.dp).background(color, RoundedCornerShape(50)))
        Spacer(modifier = Modifier.padding(4.dp))
        Text(
            label,
            color = Color(0xFFEDEDEF),
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        if (needsRepair || state is WSClient.State.Failed) {
            TextButton(onClick = onRepair) { Text("Пара заново", style = TextStyle(fontSize = 12.sp)) }
        }
    }
}

/** Converts MotionEvents into classifier input: phase + pointers still down after the event. */
private class TouchView(
    ctx: android.content.Context,
    private val onAction: (GestureClassifier.Action) -> Unit,
) : View(ctx) {
    private val classifier = GestureClassifier()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val masked = event.actionMasked
        val phase = when (masked) {
            MotionEvent.ACTION_DOWN -> GestureClassifier.Phase.Down
            MotionEvent.ACTION_POINTER_DOWN -> GestureClassifier.Phase.PointerDown
            MotionEvent.ACTION_MOVE -> GestureClassifier.Phase.Move
            MotionEvent.ACTION_POINTER_UP -> GestureClassifier.Phase.PointerUp
            MotionEvent.ACTION_UP -> GestureClassifier.Phase.Up
            MotionEvent.ACTION_CANCEL -> GestureClassifier.Phase.Cancel
            else -> return true
        }
        val pointers = if (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL) {
            emptyList()
        } else {
            val lifted = if (masked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
            (0 until event.pointerCount)
                .filter { it != lifted }
                .map { i -> GestureClassifier.Pointer(event.getPointerId(i), event.getX(i), event.getY(i)) }
        }
        classifier.onTouch(phase, pointers, event.eventTime, onAction)
        return true
    }
}

private fun dispatch(
    connection: WSClient?,
    haptic: HapticDriver,
    prefsState: State<InputPrefs>,
    action: GestureClassifier.Action,
) {
    val prefs = prefsState.value
    val ts = System.currentTimeMillis().toULong()
    when (action) {
        is GestureClassifier.Action.CursorMove ->
            connection?.send(PadlyMessage.CursorMove(action.dx * prefs.sensitivity, action.dy * prefs.sensitivity, ts))
        is GestureClassifier.Action.Tap -> {
            if (prefs.haptic) haptic.tick(prefs.hapticStrength)
            val btn = when (action.button) {
                GestureClassifier.Button.Left -> MouseButton.Left
                GestureClassifier.Button.Right -> MouseButton.Right
                GestureClassifier.Button.Middle -> MouseButton.Middle
            }
            // The Mac counts consecutive clicks itself (double/triple click).
            connection?.send(PadlyMessage.CursorClick(btn, down = true, ts = ts))
            connection?.send(PadlyMessage.CursorClick(btn, down = false, ts = ts))
        }
        is GestureClassifier.Action.Scroll -> {
            val sign = if (prefs.naturalScroll) 1f else -1f
            connection?.send(PadlyMessage.Scroll(action.dx * sign, action.dy * sign, momentum = false, ts = ts))
        }
        is GestureClassifier.Action.Pinch -> {
            val buf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(action.scaleStep).array()
            connection?.send(PadlyMessage.GestureMsg(GestureKind.PinchZoom, GesturePhase.Update, buf, ts))
        }
        is GestureClassifier.Action.Swipe3 -> {
            if (prefs.haptic) haptic.tick(prefs.hapticStrength)
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
            if (prefs.haptic) haptic.tick(prefs.hapticStrength)
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

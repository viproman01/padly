package app.padly.android.gesture

import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Stateful multi-touch classifier. Receives raw [MotionEvent]s and emits a
 * stream of high-level [Action] values that the screen forwards to WSClient.
 */
class GestureClassifier {
    sealed interface Action {
        data class CursorMove(val dx: Float, val dy: Float) : Action
        data class Tap(val button: Button) : Action
        data class Scroll(val dx: Float, val dy: Float) : Action
        data class PinchUpdate(val scale: Float) : Action
        data class Swipe3(val direction: Direction, val distancePx: Float) : Action
        data class Swipe4(val direction: Direction, val distancePx: Float) : Action
        data object DoubleTap : Action
    }

    enum class Direction { Up, Down, Left, Right }
    enum class Button { Left, Right, Middle }

    private var downTimeMs: Long = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var pointerCount = 0
    private var initialPinchDistance = 0f
    private var lastTapTimeMs = 0L
    private var totalDx = 0f
    private var totalDy = 0f
    private val tapMoveSlopPx = 8f
    private val tapTimeoutMs = 220L
    private val doubleTapWindowMs = 300L

    fun consume(event: MotionEvent, emit: (Action) -> Unit) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTimeMs = event.eventTime
                lastX = event.x
                lastY = event.y
                totalDx = 0f
                totalDy = 0f
                pointerCount = 1
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                pointerCount = event.pointerCount.coerceAtMost(4)
                if (pointerCount == 2) {
                    initialPinchDistance = pinchDistance(event)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                handleMove(event, emit)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                pointerCount = (event.pointerCount - 1).coerceAtLeast(1)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val duration = event.eventTime - downTimeMs
                if (pointerCount == 1 && duration < tapTimeoutMs &&
                    hypot(totalDx, totalDy) < tapMoveSlopPx) {
                    if (event.eventTime - lastTapTimeMs < doubleTapWindowMs) emit(Action.DoubleTap)
                    else emit(Action.Tap(Button.Left))
                    lastTapTimeMs = event.eventTime
                } else if (pointerCount == 2 && duration < tapTimeoutMs &&
                    hypot(totalDx, totalDy) < tapMoveSlopPx) {
                    emit(Action.Tap(Button.Right))
                }
                pointerCount = 0
            }
        }
    }

    private fun handleMove(event: MotionEvent, emit: (Action) -> Unit) {
        if (pointerCount == 1) {
            val dx = event.x - lastX
            val dy = event.y - lastY
            totalDx += abs(dx)
            totalDy += abs(dy)
            lastX = event.x; lastY = event.y
            if (dx != 0f || dy != 0f) emit(Action.CursorMove(dx, dy))
            return
        }
        if (pointerCount == 2) {
            val dx = event.x - lastX
            val dy = event.y - lastY
            lastX = event.x; lastY = event.y
            emit(Action.Scroll(-dx, -dy))
            val newDist = pinchDistance(event)
            if (initialPinchDistance > 0f && abs(newDist - initialPinchDistance) > 30f) {
                emit(Action.PinchUpdate(newDist / initialPinchDistance))
            }
            return
        }
        if (pointerCount == 3 || pointerCount == 4) {
            val dx = event.x - lastX
            val dy = event.y - lastY
            lastX = event.x; lastY = event.y
            val threshold = 80f
            if (hypot(dx, dy) > threshold) {
                val dir = when {
                    abs(dx) > abs(dy) -> if (dx > 0) Direction.Right else Direction.Left
                    else -> if (dy > 0) Direction.Down else Direction.Up
                }
                val dist = hypot(dx, dy)
                if (pointerCount == 3) emit(Action.Swipe3(dir, dist))
                else emit(Action.Swipe4(dir, dist))
            }
        }
    }

    private fun pinchDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return hypot(dx, dy)
    }
}

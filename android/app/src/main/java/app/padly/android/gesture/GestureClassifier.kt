package app.padly.android.gesture

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Stateful multi-touch classifier. Pure Kotlin (no android.view) so it is
 * unit-testable on the JVM; TrackpadSurface converts MotionEvents into
 * [Phase] + the list of pointers still down after the event.
 *
 * One "gesture" spans first finger down → last finger up:
 * - 1 finger moving → cursor deltas
 * - quick tap with 1/2/3 fingers (by max fingers seen) → left/right/middle click
 * - 2 fingers → locks into either scroll or pinch, never both
 * - 3/4 fingers → one swipe per gesture once travel passes a threshold
 * Any change in the set of fingers re-bases the centroid, so lifting or adding
 * a finger never makes the cursor jump.
 */
class GestureClassifier(
    private val tapTimeoutMs: Long = 250L,
    private val multiTapTimeoutMs: Long = 300L,
    private val tapSlopPx: Float = 12f,
    private val swipeThresholdPx: Float = 120f,
    private val scrollLockPx: Float = 12f,
    private val pinchLockPx: Float = 50f,
    private val pinchStep: Float = 1.15f,
) {
    enum class Phase { Down, PointerDown, Move, PointerUp, Up, Cancel }
    data class Pointer(val id: Int, val x: Float, val y: Float)

    sealed interface Action {
        data class CursorMove(val dx: Float, val dy: Float) : Action
        data class Tap(val button: Button) : Action
        /** Raw finger movement (screen coords, +y down); the Mac maps it to wheel direction. */
        data class Scroll(val dx: Float, val dy: Float) : Action
        /** Zoom step: > 1 zoom in, < 1 zoom out. Emitted every ~15% change in finger spread. */
        data class Pinch(val scaleStep: Float) : Action
        data class Swipe3(val direction: Direction, val distancePx: Float) : Action
        data class Swipe4(val direction: Direction, val distancePx: Float) : Action
    }

    enum class Direction { Up, Down, Left, Right }
    enum class Button { Left, Right, Middle }

    private enum class TwoMode { Undecided, Scroll, Pinch }

    private var gestureStartMs = 0L
    private var maxPointers = 0
    private var travel = 0f
    private var consumed = false // scroll/pinch/swipe happened → never a tap

    private var lastIds: Set<Int> = emptySet()
    private var lastCx = 0f
    private var lastCy = 0f

    private var twoMode = TwoMode.Undecided
    private var twoTravel = 0f
    private var spreadBase = 0f
    private var pinchRef = 0f

    private var swipeDx = 0f
    private var swipeDy = 0f
    private var swipeFired = false

    fun onTouch(phase: Phase, pointers: List<Pointer>, timeMs: Long, emit: (Action) -> Unit) {
        when (phase) {
            Phase.Down -> {
                resetGesture(timeMs)
                maxPointers = max(1, pointers.size)
                rebase(pointers)
            }
            Phase.PointerDown, Phase.PointerUp -> {
                maxPointers = max(maxPointers, pointers.size)
                rebase(pointers)
            }
            Phase.Move -> move(pointers, emit)
            Phase.Up -> {
                maybeTap(timeMs, emit)
                resetGesture(timeMs)
            }
            Phase.Cancel -> resetGesture(timeMs)
        }
    }

    private fun move(pointers: List<Pointer>, emit: (Action) -> Unit) {
        if (pointers.isEmpty()) return
        if (pointers.map { it.id }.toSet() != lastIds) {
            rebase(pointers)
            return
        }
        val cx = pointers.sumOf { it.x.toDouble() }.toFloat() / pointers.size
        val cy = pointers.sumOf { it.y.toDouble() }.toFloat() / pointers.size
        val dx = cx - lastCx
        val dy = cy - lastCy
        lastCx = cx
        lastCy = cy
        travel += hypot(dx, dy)

        when (pointers.size) {
            1 -> if (dx != 0f || dy != 0f) emit(Action.CursorMove(dx, dy))
            2 -> twoFinger(pointers, dx, dy, emit)
            3, 4 -> swipe(pointers.size, dx, dy, emit)
            else -> Unit
        }
    }

    private fun twoFinger(pointers: List<Pointer>, dx: Float, dy: Float, emit: (Action) -> Unit) {
        val spread = spread(pointers)
        when (twoMode) {
            TwoMode.Undecided -> {
                twoTravel += hypot(dx, dy)
                val spreadChange = abs(spread - spreadBase)
                if (spreadChange > pinchLockPx && spreadChange > twoTravel * 1.5f) {
                    twoMode = TwoMode.Pinch
                    consumed = true
                    pinchRef = spreadBase
                    pinchCheck(spread, emit)
                } else if (twoTravel > scrollLockPx && twoTravel >= spreadChange) {
                    twoMode = TwoMode.Scroll
                    consumed = true
                    emit(Action.Scroll(dx, dy))
                }
            }
            TwoMode.Scroll -> if (dx != 0f || dy != 0f) emit(Action.Scroll(dx, dy))
            TwoMode.Pinch -> pinchCheck(spread, emit)
        }
    }

    private fun pinchCheck(spread: Float, emit: (Action) -> Unit) {
        if (pinchRef <= 0f) {
            pinchRef = spread
            return
        }
        val ratio = spread / pinchRef
        if (ratio >= pinchStep || ratio <= 1f / pinchStep) {
            emit(Action.Pinch(ratio))
            pinchRef = spread
        }
    }

    private fun swipe(fingers: Int, dx: Float, dy: Float, emit: (Action) -> Unit) {
        if (swipeFired) return
        swipeDx += dx
        swipeDy += dy
        val dist = hypot(swipeDx, swipeDy)
        if (dist < swipeThresholdPx) return
        val dir = if (abs(swipeDx) > abs(swipeDy)) {
            if (swipeDx > 0) Direction.Right else Direction.Left
        } else {
            if (swipeDy > 0) Direction.Down else Direction.Up
        }
        emit(if (fingers == 3) Action.Swipe3(dir, dist) else Action.Swipe4(dir, dist))
        swipeFired = true
        consumed = true
    }

    private fun maybeTap(timeMs: Long, emit: (Action) -> Unit) {
        val timeout = if (maxPointers > 1) multiTapTimeoutMs else tapTimeoutMs
        if (consumed || travel >= tapSlopPx || timeMs - gestureStartMs > timeout) return
        when (maxPointers) {
            1 -> emit(Action.Tap(Button.Left))
            2 -> emit(Action.Tap(Button.Right))
            3 -> emit(Action.Tap(Button.Middle))
        }
    }

    /** New finger set: restart centroid tracking and per-finger-count accumulators. */
    private fun rebase(pointers: List<Pointer>) {
        lastIds = pointers.map { it.id }.toSet()
        if (pointers.isNotEmpty()) {
            lastCx = pointers.sumOf { it.x.toDouble() }.toFloat() / pointers.size
            lastCy = pointers.sumOf { it.y.toDouble() }.toFloat() / pointers.size
        }
        twoMode = TwoMode.Undecided
        twoTravel = 0f
        spreadBase = if (pointers.size == 2) spread(pointers) else 0f
        pinchRef = spreadBase
        swipeDx = 0f
        swipeDy = 0f
    }

    private fun resetGesture(timeMs: Long) {
        gestureStartMs = timeMs
        maxPointers = 0
        travel = 0f
        consumed = false
        swipeFired = false
        lastIds = emptySet()
        twoMode = TwoMode.Undecided
        twoTravel = 0f
        spreadBase = 0f
        pinchRef = 0f
        swipeDx = 0f
        swipeDy = 0f
    }

    private fun spread(pointers: List<Pointer>): Float {
        if (pointers.size < 2) return 0f
        return hypot(pointers[0].x - pointers[1].x, pointers[0].y - pointers[1].y)
    }
}

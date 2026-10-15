package app.padly.android

import app.padly.android.gesture.GestureClassifier
import app.padly.android.gesture.GestureClassifier.Action
import app.padly.android.gesture.GestureClassifier.Button
import app.padly.android.gesture.GestureClassifier.Direction
import app.padly.android.gesture.GestureClassifier.Phase
import app.padly.android.gesture.GestureClassifier.Pointer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureClassifierTest {
    private val c = GestureClassifier()
    private val out = mutableListOf<Action>()

    private fun feed(phase: Phase, t: Long, vararg p: Pointer) = c.onTouch(phase, p.toList(), t) { out += it }
    private fun p(id: Int, x: Float, y: Float) = Pointer(id, x, y)

    @Test
    fun singleFingerMoveEmitsCursorDeltas() {
        feed(Phase.Down, 0, p(0, 100f, 100f))
        feed(Phase.Move, 10, p(0, 110f, 105f))
        feed(Phase.Move, 20, p(0, 115f, 105f))
        assertEquals(listOf(Action.CursorMove(10f, 5f), Action.CursorMove(5f, 0f)), out)
    }

    @Test
    fun quickTapIsLeftClick() {
        feed(Phase.Down, 0, p(0, 100f, 100f))
        feed(Phase.Up, 100)
        assertEquals(listOf<Action>(Action.Tap(Button.Left)), out)
    }

    @Test
    fun longPressIsNotAClick() {
        feed(Phase.Down, 0, p(0, 100f, 100f))
        feed(Phase.Up, 600)
        assertTrue(out.isEmpty())
    }

    @Test
    fun twoFingerTapIsRightClickEvenIfFingersLiftApart() {
        feed(Phase.Down, 0, p(0, 100f, 100f))
        feed(Phase.PointerDown, 20, p(0, 100f, 100f), p(1, 300f, 100f))
        feed(Phase.PointerUp, 120, p(1, 300f, 100f)) // finger 0 lifts first
        feed(Phase.Up, 140)
        assertEquals(listOf<Action>(Action.Tap(Button.Right)), out)
    }

    @Test
    fun liftingOneFingerDoesNotJumpTheCursor() {
        feed(Phase.Down, 0, p(0, 100f, 100f))
        feed(Phase.PointerDown, 10, p(0, 100f, 100f), p(1, 500f, 500f))
        feed(Phase.PointerUp, 20, p(1, 500f, 500f))
        feed(Phase.Move, 30, p(1, 505f, 500f))
        assertEquals(listOf<Action>(Action.CursorMove(5f, 0f)), out)
    }

    @Test
    fun threeFingerSwipeFiresExactlyOnce() {
        feed(Phase.Down, 0, p(0, 400f, 300f))
        feed(Phase.PointerDown, 5, p(0, 400f, 300f), p(1, 450f, 300f))
        feed(Phase.PointerDown, 10, p(0, 400f, 300f), p(1, 450f, 300f), p(2, 500f, 300f))
        var t = 20L
        for (step in 1..8) {
            val d = -40f * step
            feed(Phase.Move, t, p(0, 400f + d, 300f), p(1, 450f + d, 300f), p(2, 500f + d, 300f))
            t += 16
        }
        feed(Phase.Up, t)
        val swipes = out.filterIsInstance<Action.Swipe3>()
        assertEquals(1, swipes.size)
        assertEquals(Direction.Left, swipes[0].direction)
        assertTrue(out.none { it is Action.Tap })
    }

    @Test
    fun fourFingerSwipeUp() {
        val xs = listOf(300f, 350f, 400f, 450f)
        feed(Phase.Down, 0, p(0, xs[0], 800f))
        feed(Phase.PointerDown, 5, *xs.take(2).mapIndexed { i, x -> p(i, x, 800f) }.toTypedArray())
        feed(Phase.PointerDown, 6, *xs.take(3).mapIndexed { i, x -> p(i, x, 800f) }.toTypedArray())
        feed(Phase.PointerDown, 7, *xs.mapIndexed { i, x -> p(i, x, 800f) }.toTypedArray())
        for (step in 1..5) {
            feed(Phase.Move, 10L + step * 16, *xs.mapIndexed { i, x -> p(i, x, 800f - 50f * step) }.toTypedArray())
        }
        val swipes = out.filterIsInstance<Action.Swipe4>()
        assertEquals(1, swipes.size)
        assertEquals(Direction.Up, swipes[0].direction)
    }

    @Test
    fun twoFingerScrollSendsRawFingerDirection() {
        feed(Phase.Down, 0, p(0, 200f, 200f))
        feed(Phase.PointerDown, 5, p(0, 200f, 200f), p(1, 300f, 200f))
        for (step in 1..6) {
            val y = 200f + 10f * step
            feed(Phase.Move, 10L + step * 16, p(0, 200f, y), p(1, 300f, y))
        }
        val scrolls = out.filterIsInstance<Action.Scroll>()
        assertTrue("expected scroll events", scrolls.isNotEmpty())
        assertTrue("fingers moved down → dy > 0", scrolls.all { it.dy > 0f })
        assertTrue(out.none { it is Action.Pinch || it is Action.CursorMove || it is Action.Tap })
    }

    @Test
    fun spreadingTwoFingersZoomsInWithoutScrolling() {
        feed(Phase.Down, 0, p(0, 300f, 400f))
        feed(Phase.PointerDown, 5, p(0, 300f, 400f), p(1, 400f, 400f))
        for (step in 1..10) {
            val d = 15f * step
            feed(Phase.Move, 10L + step * 16, p(0, 300f - d, 400f), p(1, 400f + d, 400f))
        }
        feed(Phase.Up, 400)
        val pinches = out.filterIsInstance<Action.Pinch>()
        assertTrue("expected zoom steps", pinches.isNotEmpty())
        assertTrue(pinches.all { it.scaleStep > 1f })
        assertTrue(out.none { it is Action.Scroll || it is Action.Tap })
    }
}

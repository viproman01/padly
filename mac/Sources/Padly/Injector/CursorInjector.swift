import AppKit
import CoreGraphics
import Foundation

/// Posts CGEvents for cursor motion, clicks, and scroll.
final class CursorInjector {
    private var heldButtons: Set<MouseButton> = []
    private var clicks = ClickCounter()
    private var scrollAccumulator = ScrollAccumulator()

    /// `dx`/`dy` are phone pixels (already scaled by the user's sensitivity).
    /// A mild speed-based gain makes fast flicks cross the screen while slow
    /// movements stay precise — the phone sends raw deltas, no OS acceleration.
    func moveBy(dx: CGFloat, dy: CGFloat) {
        let gain = PointerAcceleration.gain(forDelta: hypot(dx, dy))
        let current = currentLocation()
        var next = CGPoint(x: current.x + dx * gain, y: current.y + dy * gain)
        if let bounds = displaysBounds() {
            next.x = max(bounds.minX, min(bounds.maxX - 1, next.x))
            next.y = max(bounds.minY, min(bounds.maxY - 1, next.y))
        }
        let type: CGEventType
        let button: CGMouseButton
        if heldButtons.contains(.left) {
            type = .leftMouseDragged; button = .left
        } else if heldButtons.contains(.right) {
            type = .rightMouseDragged; button = .right
        } else {
            type = .mouseMoved; button = .left
        }
        let event = CGEvent(mouseEventSource: nil, mouseType: type, mouseCursorPosition: next, mouseButton: button)
        event?.post(tap: .cghidEventTap)
    }

    func click(button: MouseButton, down: Bool) {
        let pos = currentLocation()
        let type: CGEventType
        let cgButton: CGMouseButton
        switch button {
        case .left:
            type = down ? .leftMouseDown : .leftMouseUp
            cgButton = .left
        case .right:
            type = down ? .rightMouseDown : .rightMouseUp
            cgButton = .right
        case .middle:
            type = down ? .otherMouseDown : .otherMouseUp
            cgButton = .center
        }
        if down { heldButtons.insert(button) } else { heldButtons.remove(button) }

        // Synthetic events carry no click count unless we set it — without
        // this a double tap reaches apps as two single clicks (no file open,
        // no word select).
        let count = down
            ? clicks.registerDown(button: button, at: pos, time: ProcessInfo.processInfo.systemUptime,
                                  interval: NSEvent.doubleClickInterval)
            : clicks.current
        let event = CGEvent(mouseEventSource: nil, mouseType: type, mouseCursorPosition: pos, mouseButton: cgButton)
        event?.setIntegerValueField(.mouseEventClickState, value: count)
        event?.post(tap: .cghidEventTap)
    }

    /// `dx`/`dy`: finger movement in phone pixels (+y = fingers moving down).
    /// Natural scrolling: content follows the fingers, like a Mac trackpad.
    func scroll(dx: CGFloat, dy: CGFloat, naturalScroll: Bool) {
        let sign: CGFloat = naturalScroll ? 1 : -1
        let (wheelY, wheelX) = scrollAccumulator.add(dy: dy * sign * 0.8, dx: dx * sign * 0.8)
        guard wheelY != 0 || wheelX != 0 else { return }
        let event = CGEvent(scrollWheelEvent2Source: nil,
                            units: .pixel, wheelCount: 2,
                            wheel1: wheelY, wheel2: wheelX, wheel3: 0)
        event?.post(tap: .cghidEventTap)
    }

    func currentLocation() -> CGPoint {
        CGEvent(source: nil)?.location ?? .zero
    }

    /// A lost connection can interrupt a drag before its mouse-up arrives.
    func releaseAllHeldButtons() {
        for button in Array(heldButtons) {
            click(button: button, down: false)
        }
    }

    /// Union of all active displays (global display coordinates).
    private func displaysBounds() -> CGRect? {
        var count: UInt32 = 0
        guard CGGetActiveDisplayList(0, nil, &count) == .success, count > 0 else { return nil }
        var ids = [CGDirectDisplayID](repeating: 0, count: Int(count))
        guard CGGetActiveDisplayList(count, &ids, &count) == .success else { return nil }
        return ids.prefix(Int(count)).map { CGDisplayBounds($0) }.reduce(CGRect.null) { $0.union($1) }
    }
}

/// Speed → gain curve for relative cursor motion. `delta` is the magnitude of
/// one move event (~8 ms apart on a 120 Hz phone).
enum PointerAcceleration {
    static func gain(forDelta delta: CGFloat) -> CGFloat {
        // 1.0 for slow precise moves, rising smoothly to 2.2 for fast flicks.
        1.0 + 1.2 * min(max(delta - 2, 0) / 30, 1)
    }
}

/// Counts consecutive clicks the way the system does for real hardware:
/// same button, within the double-click interval, within a few points.
struct ClickCounter {
    private(set) var current: Int64 = 1
    private var lastTime: TimeInterval = -.infinity
    private var lastPos: CGPoint = .zero
    private var lastButton: MouseButton?

    mutating func registerDown(button: MouseButton, at pos: CGPoint, time: TimeInterval,
                               interval: TimeInterval) -> Int64
    {
        let near = hypot(pos.x - lastPos.x, pos.y - lastPos.y) <= 6
        if button == lastButton, time - lastTime <= interval, near {
            current += 1
        } else {
            current = 1
        }
        lastTime = time
        lastPos = pos
        lastButton = button
        return current
    }
}

/// Pixel scroll deltas are integers; keep the fractional remainder so slow
/// two-finger scrolls still move instead of truncating to zero.
struct ScrollAccumulator {
    private var restY: CGFloat = 0
    private var restX: CGFloat = 0

    mutating func add(dy: CGFloat, dx: CGFloat) -> (Int32, Int32) {
        restY += dy
        restX += dx
        let y = restY.rounded(.towardZero)
        let x = restX.rounded(.towardZero)
        restY -= y
        restX -= x
        return (Int32(y), Int32(x))
    }
}

import CoreGraphics
import Foundation

/// Posts CGEvents for cursor motion, clicks, and scroll. Tracks its own
/// position (last known mouse location).
final class CursorInjector {
    private var heldButtons: Set<MouseButton> = []

    func moveBy(dx: CGFloat, dy: CGFloat) {
        let current = currentLocation()
        var next = CGPoint(x: current.x + dx, y: current.y + dy)
        // Clamp to the union of screen frames.
        if let bounds = screensBounds() {
            next.x = max(bounds.minX, min(bounds.maxX - 1, next.x))
            next.y = max(bounds.minY, min(bounds.maxY - 1, next.y))
        }
        let type: CGEventType = heldButtons.contains(.left) ? .leftMouseDragged
            : heldButtons.contains(.right) ? .rightMouseDragged
            : .mouseMoved
        let event = CGEvent(mouseEventSource: nil, mouseType: type, mouseCursorPosition: next, mouseButton: .left)
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
        let event = CGEvent(mouseEventSource: nil, mouseType: type, mouseCursorPosition: pos, mouseButton: cgButton)
        event?.post(tap: .cghidEventTap)
    }

    func scroll(dx: CGFloat, dy: CGFloat, naturalScroll: Bool) {
        let sign: Int32 = naturalScroll ? 1 : -1
        let scrollY = Int32(dy * 2) * sign
        let scrollX = Int32(dx * 2) * sign
        let event = CGEvent(scrollWheelEvent2Source: nil,
                            units: .pixel, wheelCount: 2,
                            wheel1: scrollY, wheel2: scrollX, wheel3: 0)
        event?.post(tap: .cghidEventTap)
    }

    func currentLocation() -> CGPoint {
        if let event = CGEvent(source: nil) {
            return event.location
        }
        return .zero
    }

    private func screensBounds() -> CGRect? {
        let screens = NSScreenArray()
        guard !screens.isEmpty else { return nil }
        var union = screens[0]
        for r in screens.dropFirst() { union = union.union(r) }
        return union
    }

    private func NSScreenArray() -> [CGRect] {
        // CG-only context (no AppKit import in this file).
        // We approximate via main display bounds.
        let mainID = CGMainDisplayID()
        let bounds = CGDisplayBounds(mainID)
        return [bounds]
    }
}

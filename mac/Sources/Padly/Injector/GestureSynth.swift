import CoreGraphics
import Foundation

/// macOS does not let third-party apps post raw multi-touch trackpad events,
/// but every system gesture has a keyboard or magnification shortcut we can
/// emit instead. This class maps wire-protocol gestures to the closest
/// equivalent CGEvent sequence.
final class GestureSynth {
    func synthesize(_ g: Gesture) {
        switch g.kind {
        case .swipe4Up:
            // Mission Control: ctrl + Up Arrow
            postKey(virtual: 0x7E, with: .maskControl)
        case .swipe4Down:
            // Application windows: ctrl + Down
            postKey(virtual: 0x7D, with: .maskControl)
        case .swipe4Left:
            // Next Space: ctrl + Right Arrow
            postKey(virtual: 0x7C, with: .maskControl)
        case .swipe4Right:
            // Prev Space: ctrl + Left Arrow
            postKey(virtual: 0x7B, with: .maskControl)
        case .spread4:
            // Show Desktop: F11 (depending on user mapping)
            postKey(virtual: 0x67, with: [])
        case .pinch4:
            // Launchpad: F4 by default
            postKey(virtual: 0x76, with: [])
        case .swipe3Up, .swipe3Down, .swipe3Left, .swipe3Right:
            // 3-finger swipes default to forward/back in apps that respect them
            // (Safari, Finder). We approximate via cmd + [ / cmd + ] or arrow.
            switch g.kind {
            case .swipe3Left: postKey(virtual: 0x21, with: .maskCommand)  // cmd + [
            case .swipe3Right: postKey(virtual: 0x1E, with: .maskCommand) // cmd + ]
            default: break
            }
        case .pinchZoom:
            // Forward as scroll wheel with momentum so apps treat it as zoom.
            postScrollZoom(payload: g.data)
        case .rotate:
            // No public CGEvent for rotate — drop silently for now.
            break
        }
    }

    private func postKey(virtual code: CGKeyCode, with flags: CGEventFlags) {
        let down = CGEvent(keyboardEventSource: nil, virtualKey: code, keyDown: true)
        down?.flags = flags
        down?.post(tap: .cghidEventTap)
        let up = CGEvent(keyboardEventSource: nil, virtualKey: code, keyDown: false)
        up?.flags = flags
        up?.post(tap: .cghidEventTap)
    }

    private func postScrollZoom(payload: Data) {
        guard payload.count >= 4 else { return }
        var bits: UInt32 = 0
        for i in 0 ..< 4 { bits = (bits << 8) | UInt32(payload[payload.startIndex + i]) }
        let scale = Float(bitPattern: bits)
        let delta = Int32((scale - 1.0) * 80)
        let event = CGEvent(scrollWheelEvent2Source: nil,
                            units: .pixel, wheelCount: 1,
                            wheel1: delta, wheel2: 0, wheel3: 0)
        event?.flags = .maskCommand // cmd-scroll = zoom in most apps
        event?.post(tap: .cghidEventTap)
    }
}

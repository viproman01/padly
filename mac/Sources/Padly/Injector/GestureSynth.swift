import CoreGraphics
import Foundation

/// macOS does not let third-party apps post raw multi-touch trackpad events,
/// but every system gesture has a keyboard shortcut we can emit instead. This
/// class maps wire-protocol gestures to the closest equivalent CGEvent sequence.
final class GestureSynth {
    // Mission Control / Spaces hotkeys are registered as ⌃+fn+arrow (that is
    // what a real arrow key produces), so synthetic events must carry fn too.
    private let ctrlArrow: CGEventFlags = [.maskControl, .maskSecondaryFn, .maskNumericPad]

    func synthesize(_ g: Gesture) {
        switch g.kind {
        case .swipe4Up:
            postKey(0x7E, ctrlArrow)          // Mission Control: ⌃↑
        case .swipe4Down:
            postKey(0x7D, ctrlArrow)          // App windows: ⌃↓
        case .swipe4Left:
            postKey(0x7C, ctrlArrow)          // Space to the right: ⌃→
        case .swipe4Right:
            postKey(0x7B, ctrlArrow)          // Space to the left: ⌃←
        case .spread4:
            postKey(0x67, [])                 // Show Desktop: F11
        case .pinch4:
            postKey(0x76, [])                 // Launchpad: F4
        case .swipe3Left:
            postKey(0x21, .maskCommand)       // Back: ⌘[
        case .swipe3Right:
            postKey(0x1E, .maskCommand)       // Forward: ⌘]
        case .swipe3Up, .swipe3Down:
            break
        case .pinchZoom:
            zoom(payload: g.data)
        case .rotate:
            break                             // no public equivalent
        }
    }

    /// Payload: big-endian f32 scale step (> 1 zoom in, < 1 zoom out). ⌘= / ⌘−
    /// is the zoom shortcut in browsers, Preview, Finder, editors.
    private func zoom(payload: Data) {
        guard payload.count >= 4 else { return }
        var bits: UInt32 = 0
        for i in 0 ..< 4 { bits = (bits << 8) | UInt32(payload[payload.startIndex + i]) }
        let scale = Float(bitPattern: bits)
        guard scale.isFinite, scale != 1 else { return }
        postKey(scale > 1 ? 0x18 : 0x1B, .maskCommand)   // ⌘=  /  ⌘-
    }

    private func postKey(_ code: CGKeyCode, _ flags: CGEventFlags) {
        for down in [true, false] {
            let event = CGEvent(keyboardEventSource: nil, virtualKey: code, keyDown: down)
            event?.flags = flags
            event?.post(tap: .cghidEventTap)
        }
    }
}

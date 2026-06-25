import CoreGraphics
import Foundation

/// Posts CGEvents for keyboard input. Translates HID Usage Page 0x07 codes
/// from the wire protocol to macOS virtual keycodes.
final class KeyInjector {
    private var held: Set<UInt16> = []

    func send(event: KeyEvent) {
        guard let macKey = HidToMac.translate(hid: event.code) else { return }
        if event.down { held.insert(event.code) } else { held.remove(event.code) }

        let cgEvent = CGEvent(keyboardEventSource: nil,
                              virtualKey: macKey,
                              keyDown: event.down)
        cgEvent?.flags = cgFlags(modifiers: event.modifiers)
        cgEvent?.post(tap: .cghidEventTap)
    }

    /// Synthetic KeyUp for every held key — called on disconnect so the
    /// user is not left with a stuck modifier.
    func releaseAllHeldKeys() {
        for code in held {
            guard let macKey = HidToMac.translate(hid: code) else { continue }
            let cgEvent = CGEvent(keyboardEventSource: nil, virtualKey: macKey, keyDown: false)
            cgEvent?.post(tap: .cghidEventTap)
        }
        held.removeAll()
    }

    private func cgFlags(modifiers: UInt8) -> CGEventFlags {
        var flags: CGEventFlags = []
        if modifiers & ModifierBits.cmd != 0 { flags.insert(.maskCommand) }
        if modifiers & ModifierBits.opt != 0 { flags.insert(.maskAlternate) }
        if modifiers & ModifierBits.ctrl != 0 { flags.insert(.maskControl) }
        if modifiers & ModifierBits.shift != 0 { flags.insert(.maskShift) }
        if modifiers & ModifierBits.fn != 0 { flags.insert(.maskSecondaryFn) }
        return flags
    }
}

/// HID Usage Page 0x07 → macOS virtual keycodes.
/// Partial — covers the common codes Padly needs. Extend as required.
enum HidToMac {
    static func translate(hid: UInt16) -> CGKeyCode? {
        switch hid {
        // Letters
        case 0x04: return 0x00 // A
        case 0x05: return 0x0B // B
        case 0x06: return 0x08 // C
        case 0x07: return 0x02 // D
        case 0x08: return 0x0E // E
        case 0x09: return 0x03 // F
        case 0x0A: return 0x05 // G
        case 0x0B: return 0x04 // H
        case 0x0C: return 0x22 // I
        case 0x0D: return 0x26 // J
        case 0x0E: return 0x28 // K
        case 0x0F: return 0x25 // L
        case 0x10: return 0x2E // M
        case 0x11: return 0x2D // N
        case 0x12: return 0x1F // O
        case 0x13: return 0x23 // P
        case 0x14: return 0x0C // Q
        case 0x15: return 0x0F // R
        case 0x16: return 0x01 // S
        case 0x17: return 0x11 // T
        case 0x18: return 0x20 // U
        case 0x19: return 0x09 // V
        case 0x1A: return 0x0D // W
        case 0x1B: return 0x07 // X
        case 0x1C: return 0x10 // Y
        case 0x1D: return 0x06 // Z
        // Digits 1..0
        case 0x1E: return 0x12
        case 0x1F: return 0x13
        case 0x20: return 0x14
        case 0x21: return 0x15
        case 0x22: return 0x17
        case 0x23: return 0x16
        case 0x24: return 0x1A
        case 0x25: return 0x1C
        case 0x26: return 0x19
        case 0x27: return 0x1D
        // Control
        case 0x28: return 0x24 // Return
        case 0x29: return 0x35 // Escape
        case 0x2A: return 0x33 // Delete (backspace)
        case 0x2B: return 0x30 // Tab
        case 0x2C: return 0x31 // Space
        case 0x2D: return 0x1B // -
        case 0x2E: return 0x18 // =
        case 0x2F: return 0x21 // [
        case 0x30: return 0x1E // ]
        case 0x31: return 0x2A // \
        case 0x33: return 0x29 // ;
        case 0x34: return 0x27 // '
        case 0x35: return 0x32 // `
        case 0x36: return 0x2B // ,
        case 0x37: return 0x2F // .
        case 0x38: return 0x2C // /
        // F1..F12
        case 0x3A: return 0x7A
        case 0x3B: return 0x78
        case 0x3C: return 0x63
        case 0x3D: return 0x76
        case 0x3E: return 0x60
        case 0x3F: return 0x61
        case 0x40: return 0x62
        case 0x41: return 0x64
        case 0x42: return 0x65
        case 0x43: return 0x6D
        case 0x44: return 0x67
        case 0x45: return 0x6F
        // Arrows / Nav
        case 0x4F: return 0x7C // Right
        case 0x50: return 0x7B // Left
        case 0x51: return 0x7D // Down
        case 0x52: return 0x7E // Up
        case 0x4A: return 0x73 // Home
        case 0x4D: return 0x77 // End
        case 0x4B: return 0x74 // PgUp
        case 0x4E: return 0x79 // PgDn
        case 0x49: return 0x72 // Insert
        case 0x4C: return 0x75 // Forward Delete
        default: return nil
        }
    }
}

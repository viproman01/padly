import Foundation

/// All decoded message shapes after the secure channel opens a frame.
enum PadlyMessage {
    case pinProof(Data)
    case ping(ts: UInt64)
    case cursorMove(dx: Float, dy: Float, ts: UInt64)
    case cursorClick(button: MouseButton, down: Bool, ts: UInt64)
    case scroll(dx: Float, dy: Float, momentum: Bool, ts: UInt64)
    case gesture(Gesture)
    case key(KeyEvent)
    case text(String)
    case presenter(PresenterAction)
    case bye(reason: String)
}

/// Plaintext handshake message: first frame from the phone, carries its
/// identity and ephemeral public key. Not encrypted — nothing secret yet.
struct HelloE {
    let deviceId: String
    let deviceName: String
    let appVer: String
    let capabilities: [String]
    /// Uncompressed P-256 point, 65 bytes.
    let publicKey: Data
}

enum MouseButton: UInt8 {
    case left = 0, right = 1, middle = 2
}

struct Gesture {
    enum Phase: UInt8 { case begin = 0, update = 1, end = 2 }
    enum Kind: UInt8 {
        case pinchZoom = 0
        case rotate = 1
        case swipe3Up = 2, swipe3Down = 3, swipe3Left = 4, swipe3Right = 5
        case swipe4Up = 6, swipe4Down = 7, swipe4Left = 8, swipe4Right = 9
        case spread4 = 10, pinch4 = 11
    }
    let kind: Kind
    let phase: Phase
    let data: Data
    let ts: UInt64
}

struct KeyEvent {
    /// HID Usage Page 0x07 code.
    let code: UInt16
    /// Bitmask: cmd=1, opt=2, ctrl=4, shift=8, fn=16.
    let modifiers: UInt8
    let down: Bool
    let ts: UInt64
}

struct PresenterAction {
    enum Kind: UInt8 {
        case next = 0, prev = 1, black = 2, white = 3, pointer = 4, stop = 5
    }
    let kind: Kind
    let pointerXY: CGPoint?
    let ts: UInt64
}

/// Persisted on disk in Keychain.
struct PairedDevice: Identifiable, Codable, Equatable {
    let id: String
    var name: String
    /// Long-term key mixed into the HKDF on reconnect (mutual auth).
    var ltk: Data
    var lastSeen: Date
}

/// Modifier bits used by KeyInjector when posting CGEvent.
struct ModifierBits {
    static let cmd: UInt8 = 1
    static let opt: UInt8 = 2
    static let ctrl: UInt8 = 4
    static let shift: UInt8 = 8
    static let fn: UInt8 = 16
}

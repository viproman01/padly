import AppKit
import CryptoKit
import Foundation

/// Drives the pairing flow:
/// - generates the 6-digit PIN the user types on the phone
/// - validates PIN proofs bound to the ECDH session key (v2 protocol)
final class Pairing {
    private let keyStore: KeyStore
    private var pendingPin: String?
    private var pendingExpiresAt: Date?

    /// Failed-attempt counter for brute-force lockout.
    private var pinAttempts: Int = 0
    private var lockoutUntil: Date?

    init(keyStore: KeyStore) {
        self.keyStore = keyStore
    }

    /// Open a pairing window: fresh PIN + QR payload with connect coordinates.
    /// The QR carries no secrets — the PIN typed on the phone is what binds
    /// the ECDH channel to the human in front of both screens.
    func beginQrPairing(state: AppState) {
        let pin = String(format: "%06d", Int.random(in: 0 ..< 1_000_000))
        let expiresAt = Date().addingTimeInterval(120)
        pendingPin = pin
        pendingExpiresAt = expiresAt
        pinAttempts = 0
        lockoutUntil = nil

        let payload = QRGenerator.payload(
            serverId: keyStore.serverId,
            name: Host.current().localizedName ?? "Mac",
            port: state.settings.preferredPort,
            ipHint: NetworkUtil.bestLocalIP()
        )
        DispatchQueue.main.async {
            state.qrPayload = payload
            state.currentPin = pin
            state.pinExpiresAt = expiresAt
        }
    }

    /// True while a pairing window (PIN) is open and unexpired.
    var hasActivePin: Bool {
        guard pendingPin != nil, let expiry = pendingExpiresAt else { return false }
        return expiry > Date()
    }

    enum PinResult {
        case ok
        case retry(attemptsLeft: Int)
        case lockedOut(until: Date)
        case expired
    }

    /// Verify the phone's proof = HMAC-SHA256(sessionKey, "padly-pin-v2" || pin).
    /// Binding the proof to the session key defeats an active MITM: an attacker
    /// terminating two separate ECDH channels cannot transplant the proof.
    func tryPinProof(_ proof: Data, sessionKey: SymmetricKey) -> PinResult {
        if let lock = lockoutUntil, lock > Date() { return .lockedOut(until: lock) }
        guard let pin = pendingPin,
              let expiry = pendingExpiresAt,
              expiry > Date() else { return .expired }

        let expected = HandshakeCrypto.pinProof(sessionKey: sessionKey, pin: pin)
        if HMACUtil.equal(expected, proof) {
            pendingPin = nil
            pendingExpiresAt = nil
            pinAttempts = 0
            lockoutUntil = nil
            return .ok
        } else {
            pinAttempts += 1
            if pinAttempts >= 5 {
                let until = Date().addingTimeInterval(60)
                lockoutUntil = until
                return .lockedOut(until: until)
            }
            return .retry(attemptsLeft: 5 - pinAttempts)
        }
    }
}

enum NetworkUtil {
    /// Best-effort: returns the first non-loopback IPv4 address. Good enough for QR.
    static func bestLocalIP() -> String {
        var addr: String = "127.0.0.1"
        var ifaddrPtr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddrPtr) == 0, let first = ifaddrPtr else { return addr }
        defer { freeifaddrs(ifaddrPtr) }

        var ptr: UnsafeMutablePointer<ifaddrs>? = first
        while let p = ptr {
            let flags = Int32(p.pointee.ifa_flags)
            let family = p.pointee.ifa_addr.pointee.sa_family
            if (flags & (IFF_UP | IFF_RUNNING)) == (IFF_UP | IFF_RUNNING),
               (flags & IFF_LOOPBACK) == 0,
               family == UInt8(AF_INET) {
                var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                let salen = socklen_t(p.pointee.ifa_addr.pointee.sa_len)
                if getnameinfo(p.pointee.ifa_addr, salen,
                               &host, socklen_t(host.count),
                               nil, 0, NI_NUMERICHOST) == 0 {
                    addr = String(cString: host)
                }
            }
            ptr = p.pointee.ifa_next
        }
        return addr
    }
}

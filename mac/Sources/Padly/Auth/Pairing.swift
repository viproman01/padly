import Foundation
import AppKit

/// Drives the pairing flow:
/// - generates a one-shot bootstrap token (used as initial HMAC key)
/// - generates the 6-digit PIN the user types on the phone
/// - validates PIN attempts and, on success, emits a fresh persistent hmacKey
final class Pairing {
    private let keyStore: KeyStore
    private var pendingBootstrap: Data?
    private var pendingPin: String?
    private var pendingSalt: Data?
    private var pendingExpiresAt: Date?

    /// Failed-attempt counter for brute-force lockout.
    private var pinAttempts: Int = 0
    private var lockoutUntil: Date?

    init(keyStore: KeyStore) {
        self.keyStore = keyStore
    }

    /// Begin a QR-based pairing: generate fresh PIN + bootstrap token + QR payload, hand to state.
    func beginQrPairing(state: AppState) {
        let bootstrap = HMACUtil.randomBytes(16)
        let pin = String(format: "%06d", Int.random(in: 0 ..< 1_000_000))
        let salt = HMACUtil.randomBytes(16)
        let expiry = Date().addingTimeInterval(60)

        pendingBootstrap = bootstrap
        pendingPin = pin
        pendingSalt = salt
        pendingExpiresAt = expiry
        pinAttempts = 0
        lockoutUntil = nil

        let payload = QRGenerator.payload(
            serverId: keyStore.serverId,
            port: state.settings.preferredPort,
            ipHint: NetworkUtil.bestLocalIP(),
            certFingerprint: TLSIdentity.shared.certificateFingerprint(),
            bootstrapToken: bootstrap
        )
        DispatchQueue.main.async {
            state.qrPayload = payload
            state.currentPin = pin
        }
    }

    /// Returns the bootstrap key while it is still valid, else nil.
    func currentBootstrapKey() -> Data? {
        guard let expiry = pendingExpiresAt, expiry > Date() else { return nil }
        return pendingBootstrap
    }

    /// Check a PIN attempt from the phone.
    /// `incomingHash` = HMAC-SHA256(pin, incomingSalt). We recompute on our side with our salt
    /// to ensure both sides bind to the same salt.
    enum PinResult {
        case ok(hmacKey: Data)
        case retry(attemptsLeft: Int)
        case lockedOut(until: Date)
        case expired
    }

    func tryPin(incomingHash: Data, incomingSalt: Data) -> PinResult {
        if let lock = lockoutUntil, lock > Date() { return .lockedOut(until: lock) }
        guard let pin = pendingPin,
              let expiry = pendingExpiresAt,
              expiry > Date() else { return .expired }

        let recomputed = HMACUtil.sha256(key: Data(pin.utf8), parts: [incomingSalt])
        if HMACUtil.equal(recomputed, incomingHash) {
            let hmacKey = HMACUtil.randomBytes(32)
            pendingBootstrap = nil
            pendingPin = nil
            pendingExpiresAt = nil
            pinAttempts = 0
            lockoutUntil = nil
            return .ok(hmacKey: hmacKey)
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

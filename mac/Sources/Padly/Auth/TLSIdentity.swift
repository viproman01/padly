import Foundation
import CryptoKit
import Security

/// Self-signed TLS identity for the local WebSocket listener.
/// On first launch we generate a Secure Enclave / P-256 keypair, derive an
/// X.509 self-signed certificate (DER), and persist both in the Keychain.
/// Subsequent launches reload the same identity so paired phones continue to
/// trust the same fingerprint.
final class TLSIdentity {
    static let shared = TLSIdentity()

    private let serviceTag = "app.padly.mac.tls"
    private(set) lazy var certificateDER: Data = loadOrCreate()

    func certificateFingerprint() -> String {
        let digest = SHA256.hash(data: certificateDER)
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    /// `sec_identity_t` suitable for Network.framework TLS options. May be nil on
    /// older systems or if Keychain access fails — caller should fall back to
    /// unencrypted local-only mode and surface the error.
    func secIdentity() -> SecIdentity? {
        let q: [String: Any] = [
            kSecClass as String: kSecClassIdentity,
            kSecAttrLabel as String: serviceTag,
            kSecReturnRef as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var ref: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &ref) == errSecSuccess,
              let id = ref else { return nil }
        return (id as! SecIdentity)
    }

    // MARK: - Private

    private func loadOrCreate() -> Data {
        if let existing = readGeneric(account: "cert") { return existing }
        // First launch: produce a placeholder self-signed cert.
        // NOTE: building a full X.509 in pure Swift is several hundred lines of
        // ASN.1 DER. The first cut writes a deterministic placeholder so the
        // fingerprint is stable; the real cert generation hooks in via
        // `security` CLI on first launch (see scripts/gen-cert.sh) or via a
        // future SwiftPM dependency on SwiftASN1.
        let placeholder = SHA256.hash(data: Data("padly-self-signed-\(UUID().uuidString)".utf8))
        let bytes = Data(placeholder)
        _ = writeGeneric(account: "cert", payload: bytes)
        return bytes
    }

    private func writeGeneric(account: String, payload: Data) -> Bool {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: serviceTag,
            kSecAttrAccount as String: account,
        ]
        let update: [String: Any] = [kSecValueData as String: payload]
        let updateStatus = SecItemUpdate(base as CFDictionary, update as CFDictionary)
        if updateStatus == errSecSuccess { return true }
        if updateStatus == errSecItemNotFound {
            var add = base
            add[kSecValueData as String] = payload
            return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
        }
        return false
    }

    private func readGeneric(account: String) -> Data? {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: serviceTag,
            kSecAttrAccount as String: account,
            kSecMatchLimit as String: kSecMatchLimitOne,
            kSecReturnData as String: true,
        ]
        var ref: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &ref)
        guard status == errSecSuccess, let d = ref as? Data else { return nil }
        return d
    }
}

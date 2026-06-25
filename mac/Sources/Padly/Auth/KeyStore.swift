import Foundation
import Security

/// Persists paired devices and the server's own identity into the macOS Keychain.
/// Each device record is stored as a generic password item, with the account =
/// device id and the payload = JSON-encoded `PairedDevice`.
final class KeyStore {
    private let service = "app.padly.mac"
    private let serverIdKey = "app.padly.mac.serverId"

    /// Stable serverId used in mDNS TXT and QR. Generated once and persisted.
    private(set) lazy var serverId: String = {
        if let saved = readGeneric(account: serverIdKey),
           let s = String(data: saved, encoding: .utf8) {
            return s
        }
        let s = UUID().uuidString
        _ = writeGeneric(account: serverIdKey, payload: Data(s.utf8))
        return s
    }()

    // MARK: - Paired devices

    func upsert(device: PairedDevice) {
        guard let data = try? JSONEncoder().encode(device) else { return }
        _ = writeGeneric(account: "device.\(device.id)", payload: data)
    }

    func forget(deviceId: String) {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: "device.\(deviceId)",
        ]
        SecItemDelete(q as CFDictionary)
    }

    func find(deviceId: String) -> PairedDevice? {
        guard let data = readGeneric(account: "device.\(deviceId)") else { return nil }
        return try? JSONDecoder().decode(PairedDevice.self, from: data)
    }

    func allPairedDevices() -> [PairedDevice] {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecMatchLimit as String: kSecMatchLimitAll,
            kSecReturnData as String: true,
            kSecReturnAttributes as String: true,
        ]
        var ref: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &ref)
        guard status == errSecSuccess, let items = ref as? [[String: Any]] else { return [] }
        var out: [PairedDevice] = []
        for item in items {
            guard
                let account = item[kSecAttrAccount as String] as? String,
                account.hasPrefix("device."),
                let data = item[kSecValueData as String] as? Data,
                let dev = try? JSONDecoder().decode(PairedDevice.self, from: data)
            else { continue }
            out.append(dev)
        }
        return out.sorted { $0.name < $1.name }
    }

    /// Garbage-collect devices that have not connected in 90+ days.
    func evictStale(now: Date = Date()) {
        let cutoff = now.addingTimeInterval(-90 * 24 * 3600)
        for d in allPairedDevices() where d.lastSeen < cutoff {
            forget(deviceId: d.id)
        }
    }

    // MARK: - Keychain plumbing

    private func writeGeneric(account: String, payload: Data) -> Bool {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        // Try update first; otherwise add.
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
            kSecAttrService as String: service,
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

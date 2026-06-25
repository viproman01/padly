import CryptoKit
import Foundation

enum HMACUtil {
    /// HMAC-SHA256 over the concatenation of `parts`, using `key`.
    static func sha256(key: Data, parts: [Data]) -> Data {
        var hasher = HMAC<SHA256>(key: SymmetricKey(data: key))
        for part in parts {
            hasher.update(data: part)
        }
        return Data(hasher.finalize())
    }

    /// Constant-time comparison.
    static func equal(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for i in 0 ..< a.count {
            diff |= a[i] ^ b[i]
        }
        return diff == 0
    }

    /// Cryptographically random bytes.
    static func randomBytes(_ n: Int) -> Data {
        var d = Data(count: n)
        _ = d.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, n, $0.baseAddress!) }
        return d
    }
}

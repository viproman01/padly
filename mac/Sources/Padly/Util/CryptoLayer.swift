import CryptoKit
import Foundation

/// Protocol v2 crypto:
/// - Handshake: ephemeral P-256 ECDH on both sides, HKDF-SHA256 key derivation.
///   Pairing derives with salt "padly-pair-v2"; reconnect mixes the stored
///   long-term key (ltk) into the HKDF info, giving mutual authentication.
/// - Transport: every post-handshake frame is AES-256-GCM sealed:
///     ver(0x02) | seq(4 LE) | nonce(12) | ciphertext+tag
///   AAD = ver||seq. seq is strictly increasing per direction (replay guard).
enum CryptoError: Error {
    case badPeerKey
    case truncated
    case badVersion(UInt8)
    case replay(seen: UInt32, incoming: UInt32)
    case decryptFailed
}

/// One side's ephemeral ECDH keypair + derivation helpers.
struct HandshakeCrypto {
    let privateKey: P256.KeyAgreement.PrivateKey

    init() {
        privateKey = P256.KeyAgreement.PrivateKey()
    }

    /// Uncompressed X9.63 point (65 bytes, 0x04 || X || Y) — same shape Android produces.
    var publicKeyBytes: Data {
        privateKey.publicKey.x963Representation
    }

    static let pairSalt = Data("padly-pair-v2".utf8)
    static let resumeSalt = Data("padly-resume-v2".utf8)
    static let pinContext = Data("padly-pin-v2".utf8)

    /// sessionKey = HKDF(ikm: ECDH(priv, peerPub), salt, info: clientPub || serverPub [|| ltk])
    /// `clientPub` is always the phone's key, `serverPub` the Mac's, so both
    /// sides concatenate in the same order.
    func deriveSessionKey(peerPublicKey: Data,
                          clientPub: Data,
                          serverPub: Data,
                          ltk: Data?) throws -> SymmetricKey
    {
        guard let peer = try? P256.KeyAgreement.PublicKey(x963Representation: peerPublicKey) else {
            throw CryptoError.badPeerKey
        }
        let shared = try privateKey.sharedSecretFromKeyAgreement(with: peer)
        var info = Data()
        info.append(clientPub)
        info.append(serverPub)
        let salt: Data
        if let ltk = ltk {
            info.append(ltk)
            salt = Self.resumeSalt
        } else {
            salt = Self.pairSalt
        }
        return shared.hkdfDerivedSymmetricKey(using: SHA256.self,
                                              salt: salt,
                                              sharedInfo: info,
                                              outputByteCount: 32)
    }

    /// proof = HMAC-SHA256(sessionKey, "padly-pin-v2" || pin-utf8)
    static func pinProof(sessionKey: SymmetricKey, pin: String) -> Data {
        var msg = pinContext
        msg.append(Data(pin.utf8))
        let mac = HMAC<SHA256>.authenticationCode(for: msg, using: sessionKey)
        return Data(mac)
    }
}

/// Sealed transport channel. One instance per connection; direction byte
/// distinguishes phone→mac (0x01) from mac→phone (0x02) nonces so the same
/// session key never sees a repeated nonce.
final class SecureChannel {
    static let version: UInt8 = 0x02

    private let key: SymmetricKey
    private let sendDirection: UInt8
    private var sendSeq: UInt32 = 1
    private var lastRecvSeq: UInt32 = 0

    /// Mac uses sendDirection 0x02; the phone uses 0x01.
    init(key: SymmetricKey, sendDirection: UInt8) {
        self.key = key
        self.sendDirection = sendDirection
    }

    func seal(_ payload: Data) throws -> Data {
        let seq = sendSeq
        sendSeq &+= 1

        var seqBytes = Data(count: 4)
        seqBytes[0] = UInt8(seq & 0xFF)
        seqBytes[1] = UInt8((seq >> 8) & 0xFF)
        seqBytes[2] = UInt8((seq >> 16) & 0xFF)
        seqBytes[3] = UInt8((seq >> 24) & 0xFF)

        // Deterministic nonce: direction(1) || zero(3) || seq(4 LE) || zero(4).
        // Unique per (key, direction, seq); seq never repeats within a session.
        var nonceBytes = Data(count: 12)
        nonceBytes[0] = sendDirection
        nonceBytes[4] = seqBytes[0]
        nonceBytes[5] = seqBytes[1]
        nonceBytes[6] = seqBytes[2]
        nonceBytes[7] = seqBytes[3]

        var aad = Data([Self.version])
        aad.append(seqBytes)

        let nonce = try AES.GCM.Nonce(data: nonceBytes)
        let sealed = try AES.GCM.seal(payload, using: key, nonce: nonce, authenticating: aad)

        var frame = Data([Self.version])
        frame.append(seqBytes)
        frame.append(nonceBytes)
        frame.append(sealed.ciphertext)
        frame.append(sealed.tag)
        return frame
    }

    func open(_ frame: Data) throws -> Data {
        // ver(1) + seq(4) + nonce(12) + tag(16) = 33 minimum (empty payload).
        guard frame.count >= 33 else { throw CryptoError.truncated }
        let raw = Data(frame) // normalise indices

        let ver = raw[0]
        guard ver == Self.version else { throw CryptoError.badVersion(ver) }

        let seqBytes = raw.subdata(in: 1 ..< 5)
        let seq = UInt32(seqBytes[0])
            | (UInt32(seqBytes[1]) << 8)
            | (UInt32(seqBytes[2]) << 16)
            | (UInt32(seqBytes[3]) << 24)
        guard seq > lastRecvSeq else { throw CryptoError.replay(seen: lastRecvSeq, incoming: seq) }

        let nonceBytes = raw.subdata(in: 5 ..< 17)
        let ciphertext = raw.subdata(in: 17 ..< raw.count - 16)
        let tag = raw.subdata(in: raw.count - 16 ..< raw.count)

        var aad = Data([ver])
        aad.append(seqBytes)

        guard let nonce = try? AES.GCM.Nonce(data: nonceBytes),
              let box = try? AES.GCM.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tag),
              let plain = try? AES.GCM.open(box, using: key, authenticating: aad) else {
            throw CryptoError.decryptFailed
        }
        lastRecvSeq = seq
        return plain
    }
}

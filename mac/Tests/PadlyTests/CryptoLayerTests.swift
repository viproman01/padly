import CryptoKit
import XCTest
@testable import Padly

final class CryptoLayerTests: XCTestCase {
    /// Simulate both sides of the ECDH handshake and check they derive the
    /// same session key, then round-trip a sealed frame between channels.
    func testHandshakeAndSealedRoundTrip() throws {
        let phone = HandshakeCrypto()
        let mac = HandshakeCrypto()

        let phoneKey = try phone.deriveSessionKey(peerPublicKey: mac.publicKeyBytes,
                                                  clientPub: phone.publicKeyBytes,
                                                  serverPub: mac.publicKeyBytes,
                                                  ltk: nil)
        let macKey = try mac.deriveSessionKey(peerPublicKey: phone.publicKeyBytes,
                                              clientPub: phone.publicKeyBytes,
                                              serverPub: mac.publicKeyBytes,
                                              ltk: nil)
        XCTAssertEqual(phoneKey.withUnsafeBytes { Data($0) },
                       macKey.withUnsafeBytes { Data($0) })

        let phoneSend = SecureChannel(key: phoneKey, sendDirection: 0x01)
        let macRecv = SecureChannel(key: macKey, sendDirection: 0x02)

        let payload = Data("cursor-move dx=1.5".utf8)
        let frame = try phoneSend.seal(payload)
        let opened = try macRecv.open(frame)
        XCTAssertEqual(opened, payload)
    }

    func testLtkChangesDerivedKey() throws {
        let phone = HandshakeCrypto()
        let mac = HandshakeCrypto()
        let plain = try phone.deriveSessionKey(peerPublicKey: mac.publicKeyBytes,
                                               clientPub: phone.publicKeyBytes,
                                               serverPub: mac.publicKeyBytes,
                                               ltk: nil)
        let withLtk = try phone.deriveSessionKey(peerPublicKey: mac.publicKeyBytes,
                                                 clientPub: phone.publicKeyBytes,
                                                 serverPub: mac.publicKeyBytes,
                                                 ltk: Data(repeating: 7, count: 32))
        XCTAssertNotEqual(plain.withUnsafeBytes { Data($0) },
                          withLtk.withUnsafeBytes { Data($0) })
    }

    func testTamperedFrameRejected() throws {
        let key = SymmetricKey(size: .bits256)
        let sender = SecureChannel(key: key, sendDirection: 0x01)
        let receiver = SecureChannel(key: key, sendDirection: 0x02)

        var frame = try sender.seal(Data("hello".utf8))
        frame[frame.count - 1] ^= 0xFF // flip a tag bit
        XCTAssertThrowsError(try receiver.open(frame))
    }

    func testReplayRejected() throws {
        let key = SymmetricKey(size: .bits256)
        let sender = SecureChannel(key: key, sendDirection: 0x01)
        let receiver = SecureChannel(key: key, sendDirection: 0x02)

        let frame = try sender.seal(Data("once".utf8))
        _ = try receiver.open(frame)
        XCTAssertThrowsError(try receiver.open(frame)) // same seq again
    }

    func testPinProofDeterministic() {
        let key = SymmetricKey(data: Data(repeating: 3, count: 32))
        let a = HandshakeCrypto.pinProof(sessionKey: key, pin: "123456")
        let b = HandshakeCrypto.pinProof(sessionKey: key, pin: "123456")
        let c = HandshakeCrypto.pinProof(sessionKey: key, pin: "654321")
        XCTAssertEqual(a, b)
        XCTAssertNotEqual(a, c)
        XCTAssertEqual(a.count, 32)
    }

    func testBadPeerKeyThrows() {
        let phone = HandshakeCrypto()
        XCTAssertThrowsError(try phone.deriveSessionKey(peerPublicKey: Data(repeating: 9, count: 65),
                                                        clientPub: Data(),
                                                        serverPub: Data(),
                                                        ltk: nil))
    }
}

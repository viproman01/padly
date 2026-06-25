import XCTest
@testable import Padly

final class DecoderTests: XCTestCase {
    func testCursorMoveRoundTrip() throws {
        let key = Data("test-key".utf8)
        let payload: MsgPack = .map([
            (.str("t"), .str("cursorMove")),
            (.str("d"), .map([
                (.str("dx"), .float(1.5)),
                (.str("dy"), .float(-2.0)),
                (.str("ts"), .uint(100)),
            ])),
        ])
        let body = MsgPackCodec.encode(payload)

        let ver: UInt8 = 0x01
        var seqBytes = Data([0x01, 0x00, 0x00, 0x00])
        let hmac = HMACUtil.sha256(key: key, parts: [Data([ver]), seqBytes, body])
        var frame = Data()
        frame.append(ver)
        frame.append(seqBytes)
        frame.append(body)
        frame.append(hmac)

        let dec = Decoder()
        let msg = try dec.decode(frame: frame, hmacKey: key)
        if case let .cursorMove(dx, dy, ts) = msg {
            XCTAssertEqual(dx, 1.5)
            XCTAssertEqual(dy, -2.0)
            XCTAssertEqual(ts, 100)
        } else {
            XCTFail("expected cursorMove, got \(msg)")
        }
        _ = seqBytes
    }

    func testBadHmacIsRejected() {
        let key = Data("test-key".utf8)
        let frame = Data(repeating: 0, count: 1 + 4 + 8 + 32) // mostly zeros — hmac wrong
        let dec = Decoder()
        XCTAssertThrowsError(try dec.decode(frame: frame, hmacKey: key))
    }
}

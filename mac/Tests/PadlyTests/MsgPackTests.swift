import XCTest
@testable import Padly

final class MsgPackTests: XCTestCase {
    func testRoundTripPrimitives() throws {
        let cases: [MsgPack] = [
            .nil_,
            .bool(true), .bool(false),
            .uint(0), .uint(127), .uint(255), .uint(65535), .uint(123_456_789),
            .int(-1), .int(-32), .int(-128), .int(-12345),
            .float(1.5), .double(3.14159265),
            .str(""), .str("hello"), .str(String(repeating: "x", count: 40)),
            .bin(Data([0xCA, 0xFE])),
        ]
        for v in cases {
            let enc = MsgPackCodec.encode(v)
            let dec = try MsgPackCodec.decode(enc)
            XCTAssertEqual(v, dec, "round-trip failed for \(v)")
        }
    }

    func testRoundTripMap() throws {
        let v: MsgPack = .map([
            (.str("t"), .str("hello")),
            (.str("d"), .map([
                (.str("deviceId"), .str("phone-1")),
                (.str("ts"), .uint(42)),
            ])),
        ])
        let enc = MsgPackCodec.encode(v)
        let dec = try MsgPackCodec.decode(enc)
        XCTAssertEqual(v, dec)
    }

    func testRoundTripArray() throws {
        let v: MsgPack = .array([.uint(1), .str("two"), .bool(false)])
        let dec = try MsgPackCodec.decode(MsgPackCodec.encode(v))
        XCTAssertEqual(v, dec)
    }
}

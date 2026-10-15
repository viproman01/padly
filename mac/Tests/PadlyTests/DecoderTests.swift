import XCTest
@testable import Padly

final class InterpreterTests: XCTestCase {
    func testCursorMoveInterpret() throws {
        let payload: MsgPack = .map([
            (.str("t"), .str("cursorMove")),
            (.str("d"), .map([
                (.str("dx"), .float(1.5)),
                (.str("dy"), .float(-2.0)),
                (.str("ts"), .uint(100)),
            ])),
        ])
        let body = MsgPackCodec.encode(payload)

        let msg = try MessageInterpreter().interpret(body)
        if case let .cursorMove(dx, dy, ts) = msg {
            XCTAssertEqual(dx, 1.5)
            XCTAssertEqual(dy, -2.0)
            XCTAssertEqual(ts, 100)
        } else {
            XCTFail("expected cursorMove, got \(msg)")
        }
    }

    func testHelloEParse() throws {
        let pub = Data(repeating: 0x04, count: 65)
        let payload: MsgPack = .map([
            (.str("t"), .str("helloE")),
            (.str("d"), .map([
                (.str("deviceId"), .str("dev-1")),
                (.str("deviceName"), .str("Pixel")),
                (.str("appVer"), .str("0.1.0")),
                (.str("caps"), .array([.str("gesture")])),
                (.str("pub"), .bin(pub)),
            ])),
        ])
        let hello = try MessageInterpreter().parseHelloE(MsgPackCodec.encode(payload))
        XCTAssertEqual(hello.deviceId, "dev-1")
        XCTAssertEqual(hello.deviceName, "Pixel")
        XCTAssertEqual(hello.publicKey, pub)
        XCTAssertEqual(hello.capabilities, ["gesture"])
    }

    func testHelloEWithShortKeyRejected() {
        let payload: MsgPack = .map([
            (.str("t"), .str("helloE")),
            (.str("d"), .map([
                (.str("deviceId"), .str("dev-1")),
                (.str("deviceName"), .str("Pixel")),
                (.str("appVer"), .str("0.1.0")),
                (.str("pub"), .bin(Data(repeating: 1, count: 10))),
            ])),
        ])
        XCTAssertThrowsError(try MessageInterpreter().parseHelloE(MsgPackCodec.encode(payload)))
    }

    func testPinProofInterpret() throws {
        let proof = Data(repeating: 0xAB, count: 32)
        let payload: MsgPack = .map([
            (.str("t"), .str("pin")),
            (.str("d"), .map([(.str("proof"), .bin(proof))])),
        ])
        let msg = try MessageInterpreter().interpret(MsgPackCodec.encode(payload))
        if case let .pinProof(p) = msg {
            XCTAssertEqual(p, proof)
        } else {
            XCTFail("expected pinProof")
        }
    }

    func testUnknownTypeThrows() {
        let payload: MsgPack = .map([
            (.str("t"), .str("nonsense")),
            (.str("d"), .map([])),
        ])
        XCTAssertThrowsError(try MessageInterpreter().interpret(MsgPackCodec.encode(payload)))
    }
}

import CoreGraphics
import XCTest
@testable import Padly

final class InjectorLogicTests: XCTestCase {
    func testDoubleTapBecomesClickCountTwo() {
        var c = ClickCounter()
        let p = CGPoint(x: 100, y: 100)
        XCTAssertEqual(c.registerDown(button: .left, at: p, time: 10.0, interval: 0.5), 1)
        XCTAssertEqual(c.registerDown(button: .left, at: p, time: 10.2, interval: 0.5), 2)
        XCTAssertEqual(c.registerDown(button: .left, at: p, time: 10.4, interval: 0.5), 3)
    }

    func testSlowOrFarOrOtherButtonResetsCount() {
        var c = ClickCounter()
        let p = CGPoint(x: 100, y: 100)
        _ = c.registerDown(button: .left, at: p, time: 10.0, interval: 0.5)
        XCTAssertEqual(c.registerDown(button: .left, at: p, time: 11.0, interval: 0.5), 1)
        XCTAssertEqual(c.registerDown(button: .left, at: CGPoint(x: 150, y: 100), time: 11.1, interval: 0.5), 1)
        XCTAssertEqual(c.registerDown(button: .right, at: CGPoint(x: 150, y: 100), time: 11.2, interval: 0.5), 1)
    }

    func testScrollAccumulatorKeepsFractions() {
        var s = ScrollAccumulator()
        var total: Int32 = 0
        for _ in 0 ..< 10 { total += s.add(dy: 0.4, dx: 0).0 }
        XCTAssertEqual(total, 4) // 10 × 0.4 — nothing lost to truncation
        XCTAssertEqual(s.add(dy: -3.5, dx: 0).0, -3)
    }

    func testAccelerationIsOneForSlowAndCappedForFast() {
        XCTAssertEqual(PointerAcceleration.gain(forDelta: 1), 1.0, accuracy: 0.0001)
        XCTAssertEqual(PointerAcceleration.gain(forDelta: 500), 2.2, accuracy: 0.0001)
        XCTAssertGreaterThan(PointerAcceleration.gain(forDelta: 20), 1.0)
    }

    func testTextMessageInterpret() throws {
        let payload: MsgPack = .map([
            (.str("t"), .str("text")),
            (.str("d"), .map([(.str("s"), .str("привет 👋")), (.str("ts"), .uint(1))])),
        ])
        let msg = try MessageInterpreter().interpret(MsgPackCodec.encode(payload))
        guard case let .text(s) = msg else { return XCTFail("expected text, got \(msg)") }
        XCTAssertEqual(s, "привет 👋")
    }
}

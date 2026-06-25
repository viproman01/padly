import XCTest
@testable import Padly

final class HMACTests: XCTestCase {
    func testHmacIsDeterministic() {
        let key = Data("super-secret-key".utf8)
        let parts: [Data] = [Data([0x01]), Data([0xAA, 0xBB]), Data("hello".utf8)]
        let a = HMACUtil.sha256(key: key, parts: parts)
        let b = HMACUtil.sha256(key: key, parts: parts)
        XCTAssertEqual(a, b)
        XCTAssertEqual(a.count, 32)
    }

    func testConstantTimeEqual() {
        let a = Data([1, 2, 3, 4])
        let b = Data([1, 2, 3, 4])
        let c = Data([1, 2, 3, 5])
        XCTAssertTrue(HMACUtil.equal(a, b))
        XCTAssertFalse(HMACUtil.equal(a, c))
        XCTAssertFalse(HMACUtil.equal(a, Data([1, 2, 3])))
    }

    func testRandomBytesAreSized() {
        let r = HMACUtil.randomBytes(32)
        XCTAssertEqual(r.count, 32)
    }
}

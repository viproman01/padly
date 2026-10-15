import Foundation
import XCTest
@testable import Padly

final class BrowserHTTPTests: XCTestCase {
    private func parse(_ text: String) -> BrowserHTTPParseResult {
        BrowserHTTPParser.parse(Data(text.utf8))
    }

    func testGetAndHeadWithQuery() {
        XCTAssertEqual(parse("GET / HTTP/1.1\r\nHost: 192.168.1.4:47822\r\n\r\n"),
                       .request(BrowserHTTPRequest(method: "GET", path: "/")))
        XCTAssertEqual(parse("HEAD /app.js?v=2 HTTP/1.1\r\nHost: localhost\r\n\r\n"),
                       .request(BrowserHTTPRequest(method: "HEAD", path: "/app.js")))
    }

    func testIncompleteAndOversizedHeaders() {
        XCTAssertEqual(parse("GET / HTTP/1.1\r\nHost: localhost\r\n"), .incomplete)
        let huge = Data(repeating: 65, count: BrowserHTTPParser.maximumHeaderBytes + 1)
        XCTAssertEqual(BrowserHTTPParser.parse(huge), .rejected(431))
    }

    func testRejectsBodiesPipeliningAndAmbiguousHeaders() {
        XCTAssertEqual(parse("GET / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1\r\n\r\n"), .rejected(400))
        XCTAssertEqual(parse("GET / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"), .rejected(400))
        XCTAssertEqual(parse("GET / HTTP/1.1\r\nHost: localhost\r\nHost: other\r\n\r\n"), .rejected(400))
        XCTAssertEqual(parse("GET / HTTP/1.1\r\nHost: localhost\r\n\r\nx"), .rejected(400))
        XCTAssertEqual(parse("GET / HTTP/1.1\r\n\r\n"), .rejected(400))
        XCTAssertEqual(parse("POST / HTTP/1.1\r\nHost: localhost\r\n\r\n"), .rejected(405))
        XCTAssertEqual(parse("GET / HTTP/1.1\r\nHost: local\nhost\r\n\r\n"), .rejected(400))
    }

    func testOnlyFixedRoutesArePublic() {
        let router = BrowserHTTPRouter(webSocketPort: 47821, serverId: "test")
        for path in ["/../Package.swift", "/%2e%2e/Package.swift", "/BrowserResources/app.js", "/favicon.ico"] {
            XCTAssertEqual(router.response(to: BrowserHTTPRequest(method: "GET", path: path)).status, 404)
        }
        for path in ["/", "/index.html", "/app.js", "/styles.css"] {
            let response = router.response(to: BrowserHTTPRequest(method: "GET", path: path))
            XCTAssertEqual(response.status, 200, "Missing bundled browser resource: \(path)")
            XCTAssertFalse(response.body.isEmpty)
        }
    }

    func testConfigUsesJSONEncodingAndContainsNoSecrets() throws {
        let id = "id\"<script>\n"
        let router = BrowserHTTPRouter(webSocketPort: 47821, serverId: id)
        let response = router.response(to: BrowserHTTPRequest(method: "GET", path: "/config.json"))
        let json = try XCTUnwrap(try JSONSerialization.jsonObject(with: response.body) as? [String: Any])
        XCTAssertEqual(Set(json.keys), Set(["wsPort", "serverId"]))
        XCTAssertEqual(json["wsPort"] as? Int, 47821)
        XCTAssertEqual(json["serverId"] as? String, id)
    }

    func testHeadKeepsContentLengthWithoutBody() throws {
        let response = BrowserHTTPResponse(status: 200, contentType: "text/plain", body: Data("hello".utf8))
        let head = try XCTUnwrap(String(data: response.encoded(headOnly: true), encoding: .utf8))
        XCTAssertTrue(head.contains("Content-Length: 5\r\n"))
        XCTAssertTrue(head.hasSuffix("\r\n\r\n"))
        XCTAssertFalse(head.contains("hello"))
        XCTAssertTrue(String(decoding: response.encoded(), as: UTF8.self).hasSuffix("\r\n\r\nhello"))
    }

    func testPortBoundaries() {
        XCTAssertEqual(BrowserHTTPServer.httpPort(forWebSocketPort: 47821), 47822)
        XCTAssertEqual(BrowserHTTPServer.httpPort(forWebSocketPort: 65534), 65535)
        XCTAssertNil(BrowserHTTPServer.httpPort(forWebSocketPort: 65535))
        XCTAssertNil(BrowserHTTPServer.httpPort(forWebSocketPort: -1))
        XCTAssertNil(BrowserHTTPServer.httpPort(forWebSocketPort: 0))
    }

    func testBrowserWatchdogWaitsForPinAndClosesOnlyStaleAuthenticatedSessions() {
        XCTAssertFalse(BrowserClientLiveness.shouldClose(isAuthenticated: false, lastValidFrameAt: 10, now: 100))
        XCTAssertFalse(BrowserClientLiveness.shouldClose(isAuthenticated: true, lastValidFrameAt: 10, now: 25))
        XCTAssertTrue(BrowserClientLiveness.shouldClose(isAuthenticated: true, lastValidFrameAt: 10, now: 25.1))
        XCTAssertFalse(BrowserClientLiveness.shouldClose(isAuthenticated: true, lastValidFrameAt: 24, now: 25.1))
    }
}

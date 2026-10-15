import Foundation
import Network

/// Deliberately small HTTP/1 server: the only public files are the bundled UI
/// and a JSON configuration containing the public WebSocket coordinates.
final class BrowserHTTPServer {
    private let queue = DispatchQueue(label: "padly.browser.http")
    private var listener: NWListener?
    private var clients: [ObjectIdentifier: BrowserHTTPConnection] = [:]
    private let onError: (String) -> Void

    init(onError: @escaping (String) -> Void) {
        self.onError = onError
    }

    static func httpPort(forWebSocketPort port: Int) -> Int? {
        guard (1 ... 65534).contains(port) else { return nil }
        return port + 1
    }

    func start(webSocketPort: Int, serverId: String, onReady: @escaping (Int) -> Void) {
        guard let port = Self.httpPort(forWebSocketPort: webSocketPort),
              let endpointPort = NWEndpoint.Port(rawValue: UInt16(port)) else {
            onError("Browser server requires a WebSocket port between 1 and 65534.")
            return
        }
        do {
            let newListener = try NWListener(using: .tcp, on: endpointPort)
            let router = BrowserHTTPRouter(webSocketPort: webSocketPort, serverId: serverId)
            newListener.stateUpdateHandler = { [weak self] state in
                switch state {
                case .ready:
                    onReady(port)
                case .failed(let error):
                    self?.onError("Browser server failed: \(error.localizedDescription)")
                default:
                    break
                }
            }
            newListener.newConnectionHandler = { [weak self] connection in
                guard let self, self.clients.count < 16 else { connection.cancel(); return }
                let client = BrowserHTTPConnection(connection: connection, router: router)
                let id = ObjectIdentifier(client)
                client.onClose = { [weak self] in
                    self?.queue.async { self?.clients.removeValue(forKey: id) }
                }
                self.clients[id] = client
                client.start()
            }
            listener = newListener
            newListener.start(queue: queue)
        } catch {
            onError("Browser server failed: \(error.localizedDescription)")
        }
    }

    func stop() {
        queue.async { [weak self] in
            guard let self else { return }
            self.listener?.cancel()
            self.listener = nil
            let active = Array(self.clients.values)
            self.clients.removeAll()
            active.forEach { $0.stop() }
        }
    }
}

struct BrowserHTTPRequest: Equatable {
    let method: String
    let path: String
}

enum BrowserHTTPParseResult: Equatable {
    case incomplete
    case request(BrowserHTTPRequest)
    case rejected(Int)
}

/// No request bodies, pipelining, percent-decoded file paths or dynamic HTML.
/// Parsing is independent of Network.framework so it can be tested without a
/// listener and cannot allocate based on a client-supplied length.
enum BrowserHTTPParser {
    static let maximumHeaderBytes = 16 * 1024

    static func parse(_ data: Data) -> BrowserHTTPParseResult {
        guard data.count <= maximumHeaderBytes else { return .rejected(431) }
        guard let end = data.range(of: Data([13, 10, 13, 10])) else { return .incomplete }
        guard end.upperBound == data.endIndex else { return .rejected(400) }
        guard data.allSatisfy({ $0 == 9 || $0 == 10 || $0 == 13 || (32 ... 126).contains($0) }),
              let text = String(data: data, encoding: .ascii) else { return .rejected(400) }
        let lines = text.components(separatedBy: "\r\n")
        let first = lines[0].split(separator: " ", omittingEmptySubsequences: false)
        guard first.count == 3, first[2] == "HTTP/1.1" || first[2] == "HTTP/1.0" else {
            return .rejected(400)
        }
        let method = String(first[0])
        guard method == "GET" || method == "HEAD" else { return .rejected(405) }
        let target = String(first[1])
        guard target.hasPrefix("/"), !target.contains("#"), !target.contains("\\"),
              !target.contains("\r"), !target.contains("\n"), !target.contains("\t") else {
            return .rejected(400)
        }
        var headers: [String: String] = [:]
        for line in lines.dropFirst().dropLast(2) {
            guard let colon = line.firstIndex(of: ":"), colon != line.startIndex else { return .rejected(400) }
            let name = String(line[..<colon])
            let tokenChars = "!#$%&'*+-.^_`|~0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            guard name.allSatisfy({ tokenChars.contains($0) }) else { return .rejected(400) }
            let key = name.lowercased()
            guard headers[key] == nil else { return .rejected(400) }
            let value = String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
            guard !value.contains("\r"), !value.contains("\n") else { return .rejected(400) }
            headers[key] = value
        }
        guard headers["transfer-encoding"] == nil,
              headers["content-length"] == nil || headers["content-length"] == "0" else { return .rejected(400) }
        if first[2] == "HTTP/1.1", headers["host"]?.isEmpty != false { return .rejected(400) }
        let path = target.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false)[0]
        return .request(BrowserHTTPRequest(method: method, path: String(path)))
    }
}

struct BrowserHTTPResponse {
    let status: Int
    let contentType: String
    let body: Data

    static func error(_ status: Int) -> BrowserHTTPResponse {
        let phrase = reason(for: status)
        return BrowserHTTPResponse(status: status, contentType: "text/plain; charset=utf-8", body: Data("\(phrase)\n".utf8))
    }

    func encoded(headOnly: Bool = false) -> Data {
        var headers = "HTTP/1.1 \(status) \(Self.reason(for: status))\r\n"
        headers += "Content-Type: \(contentType)\r\nContent-Length: \(body.count)\r\n"
        headers += "Connection: close\r\nCache-Control: no-store\r\n"
        headers += "X-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n"
        headers += "Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self' ws:; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'\r\n"
        if status == 405 { headers += "Allow: GET, HEAD\r\n" }
        headers += "\r\n"
        var bytes = Data(headers.utf8)
        if !headOnly { bytes.append(body) }
        return bytes
    }

    private static func reason(for status: Int) -> String {
        switch status {
        case 200: return "OK"
        case 400: return "Bad Request"
        case 404: return "Not Found"
        case 405: return "Method Not Allowed"
        case 408: return "Request Timeout"
        case 431: return "Request Header Fields Too Large"
        default: return "Internal Server Error"
        }
    }
}

struct BrowserHTTPRouter {
    let webSocketPort: Int
    let serverId: String

    // Installed apps keep resources in Contents/Resources. SwiftPM's generated
    // Bundle.module accessor also supports the unbundled `swift run` layout.
    private var resources: Bundle {
        if let url = Bundle.main.resourceURL?.appendingPathComponent("Padly_Padly.bundle"),
           let bundle = Bundle(url: url) { return bundle }
        return Bundle.module
    }

    func response(to request: BrowserHTTPRequest) -> BrowserHTTPResponse {
        if request.path == "/config.json" {
            struct Configuration: Encodable {
                let wsPort: Int
                let serverId: String
            }
            guard let data = try? JSONEncoder().encode(Configuration(wsPort: webSocketPort, serverId: serverId)) else {
                return .error(500)
            }
            return BrowserHTTPResponse(status: 200, contentType: "application/json; charset=utf-8", body: data)
        }
        let file: String
        let ext: String
        let mime: String
        switch request.path {
        case "/", "/index.html":
            file = "index"; ext = "html"; mime = "text/html; charset=utf-8"
        case "/app.js":
            file = "app"; ext = "js"; mime = "text/javascript; charset=utf-8"
        case "/styles.css":
            file = "styles"; ext = "css"; mime = "text/css; charset=utf-8"
        default:
            return .error(404)
        }
        guard let url = resources.url(forResource: file, withExtension: ext, subdirectory: "BrowserResources"),
              let data = try? Data(contentsOf: url) else {
            return .error(500)
        }
        return BrowserHTTPResponse(status: 200, contentType: mime, body: data)
    }
}

private final class BrowserHTTPConnection {
    private let connection: NWConnection
    private let router: BrowserHTTPRouter
    private let queue = DispatchQueue(label: "padly.browser.request")
    private var received = Data()
    private var finished = false
    private var responding = false
    var onClose: (() -> Void)?

    init(connection: NWConnection, router: BrowserHTTPRouter) {
        self.connection = connection
        self.router = router
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready: self?.receiveNext()
            case .failed, .cancelled: self?.finish()
            default: break
            }
        }
        connection.start(queue: queue)
        queue.asyncAfter(deadline: .now() + 10) { [weak self] in
            guard let self, !self.finished else { return }
            if !self.responding { self.respond(.error(408)) }
            self.queue.asyncAfter(deadline: .now() + 1) { [weak self] in self?.finish() }
        }
    }

    func stop() {
        queue.async { self.finish() }
    }

    private func receiveNext() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self] data, _, complete, error in
            guard let self, !self.finished, !self.responding else { return }
            if let data { self.received.append(data) }
            switch BrowserHTTPParser.parse(self.received) {
            case .request(let request):
                self.respond(self.router.response(to: request), headOnly: request.method == "HEAD")
            case .rejected(let status):
                self.respond(.error(status))
            case .incomplete:
                if complete || error != nil { self.finish() }
                else { self.receiveNext() }
            }
        }
    }

    private func respond(_ response: BrowserHTTPResponse, headOnly: Bool = false) {
        guard !finished, !responding else { return }
        responding = true
        connection.send(content: response.encoded(headOnly: headOnly), contentContext: .finalMessage, isComplete: true,
                        completion: .contentProcessed { [weak self] _ in self?.finish() })
    }

    private func finish() {
        guard !finished else { return }
        finished = true
        connection.cancel()
        onClose?()
        onClose = nil
    }
}

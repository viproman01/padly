// padly-smoke — end-to-end smoke test client for the Padly Mac server.
//
// Speaks protocol v2 with a deliberately independent implementation (its own
// micro-msgpack, its own framing) so it doubles as an interop check for the
// phone side. Flow: connect → helloE → welcomeE → ECDH → pin proof →
// pairOk → sealed ping → pong → print PADLY_SMOKE_OK.
//
// Usage: padly-smoke <host> <port> <pin>

import CryptoKit
import Foundation

// MARK: - Micro msgpack (subset: str, bin, bool, uint, map, array)

enum MP {
    case str(String), bin(Data), bool(Bool), uint(UInt64), array([MP]), map([(String, MP)])

    var encoded: Data {
        var out = Data()
        encode(into: &out)
        return out
    }

    private func encode(into out: inout Data) {
        switch self {
        case .bool(let b): out.append(b ? 0xC3 : 0xC2)
        case .uint(let u):
            if u <= 0x7F { out.append(UInt8(u)) }
            else if u <= UInt64(UInt8.max) { out.append(0xCC); out.append(UInt8(u)) }
            else if u <= UInt64(UInt16.max) {
                out.append(0xCD)
                out.append(UInt8(u >> 8)); out.append(UInt8(u & 0xFF))
            } else if u <= UInt64(UInt32.max) {
                out.append(0xCE)
                for shift in stride(from: 24, through: 0, by: -8) { out.append(UInt8((u >> UInt64(shift)) & 0xFF)) }
            } else {
                out.append(0xCF)
                for shift in stride(from: 56, through: 0, by: -8) { out.append(UInt8((u >> UInt64(shift)) & 0xFF)) }
            }
        case .str(let s):
            let utf8 = Data(s.utf8)
            if utf8.count < 32 { out.append(0xA0 | UInt8(utf8.count)) }
            else { out.append(0xD9); out.append(UInt8(utf8.count)) }
            out.append(utf8)
        case .bin(let d):
            out.append(0xC4); out.append(UInt8(d.count))
            out.append(d)
        case .array(let items):
            out.append(0x90 | UInt8(items.count))
            for i in items { i.encode(into: &out) }
        case .map(let pairs):
            out.append(0x80 | UInt8(pairs.count))
            for (k, v) in pairs {
                MP.str(k).encode(into: &out)
                v.encode(into: &out)
            }
        }
    }
}

/// Tiny reader for the server's replies. Handles the subset the server emits.
struct MPReader {
    let data: Data
    var cursor = 0

    init(_ d: Data) { data = Data(d) }

    mutating func readValue() -> Any? {
        guard cursor < data.count else { return nil }
        let b = data[cursor]; cursor += 1
        switch b {
        case 0xC0: return nil
        case 0xC2: return false
        case 0xC3: return true
        case 0xCC: return UInt64(next(1))
        case 0xCD: return UInt64(next(2))
        case 0xCE: return UInt64(next(4))
        case 0xCF: return UInt64(next(8))
        case 0xC4:
            let n = Int(next(1)); return readBytes(n)
        case 0xC5:
            let n = Int(next(2)); return readBytes(n)
        case 0xD9:
            let n = Int(next(1)); return readString(n)
        case 0xDA:
            let n = Int(next(2)); return readString(n)
        default:
            if b <= 0x7F { return UInt64(b) }
            if b & 0xE0 == 0xA0 { return readString(Int(b & 0x1F)) }
            if b & 0xF0 == 0x90 {
                let n = Int(b & 0x0F)
                var arr: [Any?] = []
                for _ in 0 ..< n { arr.append(readValue()) }
                return arr
            }
            if b & 0xF0 == 0x80 {
                let n = Int(b & 0x0F)
                var dict: [String: Any?] = [:]
                for _ in 0 ..< n {
                    let k = readValue() as? String ?? "?"
                    dict[k] = readValue()
                }
                return dict
            }
            return nil
        }
    }

    private mutating func next(_ n: Int) -> UInt64 {
        var v: UInt64 = 0
        for _ in 0 ..< n {
            v = (v << 8) | UInt64(data[cursor]); cursor += 1
        }
        return v
    }

    private mutating func readBytes(_ n: Int) -> Data {
        let d = data.subdata(in: cursor ..< cursor + n); cursor += n; return d
    }

    private mutating func readString(_ n: Int) -> String {
        let d = readBytes(n); return String(data: d, encoding: .utf8) ?? ""
    }
}

func parseMessage(_ raw: Data) -> (type: String, body: [String: Any?])? {
    var reader = MPReader(raw)
    guard let root = reader.readValue() as? [String: Any?],
          let t = root["t"] as? String else { return nil }
    let d = root["d"] as? [String: Any?] ?? [:]
    return (t, d)
}

// MARK: - Frame crypto (independent v2 implementation)

final class SmokeChannel {
    private let key: SymmetricKey
    private let direction: UInt8
    private var seq: UInt32 = 1

    init(key: SymmetricKey, direction: UInt8) {
        self.key = key
        self.direction = direction
    }

    func seal(_ payload: Data) throws -> Data {
        var seqBytes = Data(count: 4)
        seqBytes[0] = UInt8(seq & 0xFF)
        seqBytes[1] = UInt8((seq >> 8) & 0xFF)
        seqBytes[2] = UInt8((seq >> 16) & 0xFF)
        seqBytes[3] = UInt8((seq >> 24) & 0xFF)
        seq &+= 1

        var nonceBytes = Data(count: 12)
        nonceBytes[0] = direction
        for i in 0 ..< 4 { nonceBytes[4 + i] = seqBytes[i] }

        var aad = Data([0x02])
        aad.append(seqBytes)

        let sealed = try AES.GCM.seal(payload, using: key,
                                      nonce: AES.GCM.Nonce(data: nonceBytes),
                                      authenticating: aad)
        var frame = Data([0x02])
        frame.append(seqBytes)
        frame.append(nonceBytes)
        frame.append(sealed.ciphertext)
        frame.append(sealed.tag)
        return frame
    }

    func open(_ frame: Data) throws -> Data {
        let raw = Data(frame)
        guard raw.count >= 33, raw[0] == 0x02 else { throw SmokeError.badFrame }
        let seqBytes = raw.subdata(in: 1 ..< 5)
        let nonceBytes = raw.subdata(in: 5 ..< 17)
        let ciphertext = raw.subdata(in: 17 ..< raw.count - 16)
        let tag = raw.subdata(in: raw.count - 16 ..< raw.count)
        var aad = Data([0x02])
        aad.append(seqBytes)
        let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: nonceBytes),
                                        ciphertext: ciphertext, tag: tag)
        return try AES.GCM.open(box, using: key, authenticating: aad)
    }
}

enum SmokeError: Error { case badFrame, timeout, handshake(String), pairing(String) }

// MARK: - Main

let args = CommandLine.arguments
guard args.count >= 4, let port = Int(args[2]) else {
    print("usage: padly-smoke <host> <port> <pin>")
    exit(2)
}
let host = args[1]
let pin = args[3]

let url = URL(string: "ws://\(host):\(port)")!
let session = URLSession(configuration: .default)
let ws = session.webSocketTask(with: url)
ws.resume()

let priv = P256.KeyAgreement.PrivateKey()
let myPub = priv.publicKey.x963Representation

func send(_ data: Data) async throws {
    try await ws.send(.data(data))
}

func receive(timeout: TimeInterval = 5) async throws -> Data {
    try await withThrowingTaskGroup(of: Data.self) { group in
        group.addTask {
            let msg = try await ws.receive()
            if case .data(let d) = msg { return d }
            throw SmokeError.badFrame
        }
        group.addTask {
            try await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
            throw SmokeError.timeout
        }
        let first = try await group.next()!
        group.cancelAll()
        return first
    }
}

func run() async throws {
    // 1. helloE
    let hello = MP.map([
        ("t", .str("helloE")),
        ("d", .map([
            ("deviceId", .str("smoke-\(UUID().uuidString.prefix(8))")),
            ("deviceName", .str("SmokeClient")),
            ("appVer", .str("0.1.0")),
            ("caps", .array([.str("smoke")])),
            ("pub", .bin(myPub)),
        ])),
    ]).encoded
    try await send(hello)
    print("→ helloE sent")

    // 2. welcomeE
    let welcomeRaw = try await receive()
    guard let welcome = parseMessage(welcomeRaw), welcome.type == "welcomeE",
          let serverPub = welcome.body["pub"] as? Data, serverPub.count == 65 else {
        throw SmokeError.handshake("bad welcomeE")
    }
    print("← welcomeE from \(welcome.body["name"] as? String ?? "?")")

    // 3. derive session key (pairing path — no ltk)
    let peer = try P256.KeyAgreement.PublicKey(x963Representation: serverPub)
    let shared = try priv.sharedSecretFromKeyAgreement(with: peer)
    var info = Data()
    info.append(myPub)
    info.append(serverPub)
    let sessionKey = shared.hkdfDerivedSymmetricKey(using: SHA256.self,
                                                    salt: Data("padly-pair-v2".utf8),
                                                    sharedInfo: info,
                                                    outputByteCount: 32)
    let sendChannel = SmokeChannel(key: sessionKey, direction: 0x01)
    let recvChannel = SmokeChannel(key: sessionKey, direction: 0x02)

    // 4. sealed welcome{requirePin}
    let welcomeSealed = try await receive()
    let welcomePlain = try recvChannel.open(welcomeSealed)
    guard let w = parseMessage(welcomePlain), w.type == "welcome",
          (w.body["requirePin"] as? Bool) == true else {
        throw SmokeError.handshake("expected welcome{requirePin}")
    }
    print("← welcome requirePin=true (channel up)")

    // 5. pin proof
    var proofMsg = Data("padly-pin-v2".utf8)
    proofMsg.append(Data(pin.utf8))
    let proof = Data(HMAC<SHA256>.authenticationCode(for: proofMsg, using: sessionKey))
    let pinPayload = MP.map([
        ("t", .str("pin")),
        ("d", .map([("proof", .bin(proof))])),
    ]).encoded
    try await send(try sendChannel.seal(pinPayload))
    print("→ pin proof sent")

    // 6. pairOk{ltk}
    let pairRaw = try await receive()
    let pairPlain = try recvChannel.open(pairRaw)
    guard let pair = parseMessage(pairPlain) else { throw SmokeError.pairing("unparseable reply") }
    guard pair.type == "pairOk", let ltk = pair.body["ltk"] as? Data, ltk.count == 32 else {
        throw SmokeError.pairing("expected pairOk, got \(pair.type): \(pair.body)")
    }
    print("← pairOk, ltk received (\(ltk.count)B)")

    // 7. sealed ping → pong
    let pingPayload = MP.map([
        ("t", .str("ping")),
        ("d", .map([("ts", .uint(12345))])),
    ]).encoded
    try await send(try sendChannel.seal(pingPayload))
    let pongRaw = try await receive()
    let pongPlain = try recvChannel.open(pongRaw)
    guard let pong = parseMessage(pongPlain), pong.type == "pong",
          (pong.body["ts"] as? UInt64) == 12345 else {
        throw SmokeError.pairing("expected pong ts=12345")
    }
    print("← pong ts=12345")

    print("PADLY_SMOKE_OK")
    ws.cancel(with: .normalClosure, reason: nil)
}

let sem = DispatchSemaphore(value: 0)
Task {
    do {
        try await run()
        sem.signal()
        exit(0)
    } catch {
        print("PADLY_SMOKE_FAIL: \(error)")
        sem.signal()
        exit(1)
    }
}
sem.wait()

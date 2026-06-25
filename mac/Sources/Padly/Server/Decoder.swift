import Foundation

enum DecodeError: Error {
    case truncated
    case badVersion(UInt8)
    case badHmac
    case replay(seen: UInt32, incoming: UInt32)
    case unknownType(String)
    case malformed(String)
}

/// Parses an inbound binary frame:
///   ver(1) | seq(4 LE) | msgpack payload | hmac(32)
struct Frame {
    let ver: UInt8
    let seq: UInt32
    let payload: Data
    let hmac: Data
}

final class Decoder {
    private let supportedVersion: UInt8 = 0x01
    private var lastSeq: UInt32 = 0

    /// Verify HMAC and monotonic seq, then decode the payload to a `PadlyMessage`.
    func decode(frame raw: Data, hmacKey: Data) throws -> PadlyMessage {
        guard raw.count >= 1 + 4 + 32 + 1 else { throw DecodeError.truncated }

        let ver = raw[raw.startIndex]
        guard ver == supportedVersion else { throw DecodeError.badVersion(ver) }

        let seqBytes = raw.subdata(in: raw.startIndex + 1 ..< raw.startIndex + 5)
        let seq = seqBytes.withUnsafeBytes { ptr -> UInt32 in
            UInt32(ptr[0]) | (UInt32(ptr[1]) << 8) | (UInt32(ptr[2]) << 16) | (UInt32(ptr[3]) << 24)
        }

        let payloadStart = raw.startIndex + 5
        let payloadEnd = raw.endIndex - 32
        guard payloadStart < payloadEnd else { throw DecodeError.truncated }
        let payload = raw.subdata(in: payloadStart ..< payloadEnd)
        let hmac = raw.subdata(in: payloadEnd ..< raw.endIndex)

        let expected = HMACUtil.sha256(key: hmacKey, parts: [Data([ver]), seqBytes, payload])
        guard HMACUtil.equal(expected, hmac) else { throw DecodeError.badHmac }

        if seq <= lastSeq && lastSeq != 0 { throw DecodeError.replay(seen: lastSeq, incoming: seq) }
        lastSeq = seq

        let mp = try MsgPackCodec.decode(payload)
        return try interpret(mp)
    }

    func resetSeq() { lastSeq = 0 }

    // MARK: - Interpret MsgPack → PadlyMessage

    private func interpret(_ root: MsgPack) throws -> PadlyMessage {
        guard case .map(let pairs) = root else {
            throw DecodeError.malformed("root not map")
        }
        guard let tType = stringValue(forKey: "t", in: pairs),
              let dRaw = value(forKey: "d", in: pairs) else {
            throw DecodeError.malformed("missing t/d")
        }

        switch tType {
        case "hello":
            return .hello(try parseHello(dRaw))
        case "ping":
            return .ping(ts: try parseTs(dRaw))
        case "pin":
            return .pinAttempt(try parsePin(dRaw))
        case "cursorMove":
            let (dx, dy, ts) = try parseDxDy(dRaw)
            return .cursorMove(dx: dx, dy: dy, ts: ts)
        case "cursorClick":
            return try parseClick(dRaw)
        case "scroll":
            return try parseScroll(dRaw)
        case "gesture":
            return .gesture(try parseGesture(dRaw))
        case "key":
            return .key(try parseKey(dRaw))
        case "presenter":
            return .presenter(try parsePresenter(dRaw))
        case "bye":
            return .bye(reason: stringValue(forKey: "reason", in: extractMap(dRaw)) ?? "")
        default:
            throw DecodeError.unknownType(tType)
        }
    }

    // MARK: - Specific parsers

    private func parseHello(_ d: MsgPack) throws -> Hello {
        let m = extractMap(d)
        guard let id = stringValue(forKey: "deviceId", in: m),
              let name = stringValue(forKey: "deviceName", in: m),
              let ver = stringValue(forKey: "appVer", in: m) else {
            throw DecodeError.malformed("hello fields")
        }
        var caps: [String] = []
        if case .array(let arr)? = value(forKey: "caps", in: m) {
            for v in arr { if case .str(let s) = v { caps.append(s) } }
        }
        return Hello(deviceId: id, deviceName: name, appVer: ver, capabilities: caps)
    }

    private func parsePin(_ d: MsgPack) throws -> PinAttempt {
        let m = extractMap(d)
        guard case .bin(let hash)? = value(forKey: "hash", in: m),
              case .bin(let salt)? = value(forKey: "salt", in: m) else {
            throw DecodeError.malformed("pin fields")
        }
        return PinAttempt(hash: hash, salt: salt)
    }

    private func parseTs(_ d: MsgPack) throws -> UInt64 {
        let m = extractMap(d)
        return uintValue(forKey: "ts", in: m) ?? 0
    }

    private func parseDxDy(_ d: MsgPack) throws -> (Float, Float, UInt64) {
        let m = extractMap(d)
        let dx = floatValue(forKey: "dx", in: m) ?? 0
        let dy = floatValue(forKey: "dy", in: m) ?? 0
        let ts = uintValue(forKey: "ts", in: m) ?? 0
        return (dx, dy, ts)
    }

    private func parseClick(_ d: MsgPack) throws -> PadlyMessage {
        let m = extractMap(d)
        let btnRaw = uintValue(forKey: "btn", in: m) ?? 0
        let btn = MouseButton(rawValue: UInt8(btnRaw)) ?? .left
        let down = boolValue(forKey: "down", in: m) ?? false
        let ts = uintValue(forKey: "ts", in: m) ?? 0
        return .cursorClick(button: btn, down: down, ts: ts)
    }

    private func parseScroll(_ d: MsgPack) throws -> PadlyMessage {
        let m = extractMap(d)
        let dx = floatValue(forKey: "dx", in: m) ?? 0
        let dy = floatValue(forKey: "dy", in: m) ?? 0
        let momentum = boolValue(forKey: "momentum", in: m) ?? false
        let ts = uintValue(forKey: "ts", in: m) ?? 0
        return .scroll(dx: dx, dy: dy, momentum: momentum, ts: ts)
    }

    private func parseGesture(_ d: MsgPack) throws -> Gesture {
        let m = extractMap(d)
        let kindRaw = UInt8(uintValue(forKey: "kind", in: m) ?? 0)
        let phaseRaw = UInt8(uintValue(forKey: "phase", in: m) ?? 0)
        let ts = uintValue(forKey: "ts", in: m) ?? 0
        var payload = Data()
        if case .bin(let b)? = value(forKey: "data", in: m) { payload = b }
        guard let kind = Gesture.Kind(rawValue: kindRaw),
              let phase = Gesture.Phase(rawValue: phaseRaw) else {
            throw DecodeError.malformed("gesture kind/phase")
        }
        return Gesture(kind: kind, phase: phase, data: payload, ts: ts)
    }

    private func parseKey(_ d: MsgPack) throws -> KeyEvent {
        let m = extractMap(d)
        let code = UInt16(uintValue(forKey: "code", in: m) ?? 0)
        let mods = UInt8(uintValue(forKey: "mods", in: m) ?? 0)
        let down = boolValue(forKey: "down", in: m) ?? false
        let ts = uintValue(forKey: "ts", in: m) ?? 0
        return KeyEvent(code: code, modifiers: mods, down: down, ts: ts)
    }

    private func parsePresenter(_ d: MsgPack) throws -> PresenterAction {
        let m = extractMap(d)
        let kindRaw = UInt8(uintValue(forKey: "kind", in: m) ?? 0)
        guard let kind = PresenterAction.Kind(rawValue: kindRaw) else {
            throw DecodeError.malformed("presenter kind")
        }
        let ts = uintValue(forKey: "ts", in: m) ?? 0
        let x = floatValue(forKey: "x", in: m)
        let y = floatValue(forKey: "y", in: m)
        let point: CGPoint? = (x != nil && y != nil) ? CGPoint(x: CGFloat(x!), y: CGFloat(y!)) : nil
        return PresenterAction(kind: kind, pointerXY: point, ts: ts)
    }

    // MARK: - Map helpers

    private func extractMap(_ v: MsgPack) -> [(MsgPack, MsgPack)] {
        if case .map(let pairs) = v { return pairs }
        return []
    }

    private func value(forKey key: String, in pairs: [(MsgPack, MsgPack)]) -> MsgPack? {
        for (k, v) in pairs {
            if case .str(let s) = k, s == key { return v }
        }
        return nil
    }

    private func stringValue(forKey key: String, in pairs: [(MsgPack, MsgPack)]) -> String? {
        if case .str(let s)? = value(forKey: key, in: pairs) { return s }
        return nil
    }

    private func boolValue(forKey key: String, in pairs: [(MsgPack, MsgPack)]) -> Bool? {
        if case .bool(let b)? = value(forKey: key, in: pairs) { return b }
        return nil
    }

    private func uintValue(forKey key: String, in pairs: [(MsgPack, MsgPack)]) -> UInt64? {
        switch value(forKey: key, in: pairs) {
        case .uint(let u)?: return u
        case .int(let i)? where i >= 0: return UInt64(i)
        default: return nil
        }
    }

    private func floatValue(forKey key: String, in pairs: [(MsgPack, MsgPack)]) -> Float? {
        switch value(forKey: key, in: pairs) {
        case .float(let f)?: return f
        case .double(let d)?: return Float(d)
        case .int(let i)?: return Float(i)
        case .uint(let u)?: return Float(u)
        default: return nil
        }
    }
}

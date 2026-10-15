import Foundation

enum DecodeError: Error {
    case unknownType(String)
    case malformed(String)
}

/// Interprets decrypted MessagePack payloads into `PadlyMessage` values, and
/// parses the plaintext `helloE` handshake message. Framing, encryption, and
/// replay protection live in `SecureChannel`.
final class MessageInterpreter {
    // MARK: - Handshake (plaintext)

    /// Parses the phone's first message: raw msgpack, no frame header.
    func parseHelloE(_ raw: Data) throws -> HelloE {
        let root = try MsgPackCodec.decode(raw)
        guard case .map(let pairs) = root,
              stringValue(forKey: "t", in: pairs) == "helloE",
              let d = value(forKey: "d", in: pairs) else {
            throw DecodeError.malformed("not a helloE")
        }
        let m = extractMap(d)
        guard let id = stringValue(forKey: "deviceId", in: m),
              let name = stringValue(forKey: "deviceName", in: m),
              let ver = stringValue(forKey: "appVer", in: m),
              case .bin(let pub)? = value(forKey: "pub", in: m),
              pub.count == 65 else {
            throw DecodeError.malformed("helloE fields")
        }
        var caps: [String] = []
        if case .array(let arr)? = value(forKey: "caps", in: m) {
            for v in arr { if case .str(let s) = v { caps.append(s) } }
        }
        return HelloE(deviceId: id, deviceName: name, appVer: ver, capabilities: caps, publicKey: pub)
    }

    // MARK: - Post-handshake messages (already decrypted)

    func interpret(_ raw: Data) throws -> PadlyMessage {
        let root = try MsgPackCodec.decode(raw)
        guard case .map(let pairs) = root else {
            throw DecodeError.malformed("root not map")
        }
        guard let tType = stringValue(forKey: "t", in: pairs),
              let dRaw = value(forKey: "d", in: pairs) else {
            throw DecodeError.malformed("missing t/d")
        }

        switch tType {
        case "pin":
            let m = extractMap(dRaw)
            guard case .bin(let proof)? = value(forKey: "proof", in: m) else {
                throw DecodeError.malformed("pin proof")
            }
            return .pinProof(proof)
        case "ping":
            return .ping(ts: uintValue(forKey: "ts", in: extractMap(dRaw)) ?? 0)
        case "cursorMove":
            let m = extractMap(dRaw)
            return .cursorMove(dx: floatValue(forKey: "dx", in: m) ?? 0,
                               dy: floatValue(forKey: "dy", in: m) ?? 0,
                               ts: uintValue(forKey: "ts", in: m) ?? 0)
        case "cursorClick":
            let m = extractMap(dRaw)
            let btnRaw = uintValue(forKey: "btn", in: m) ?? 0
            let btn = MouseButton(rawValue: UInt8(truncatingIfNeeded: btnRaw)) ?? .left
            return .cursorClick(button: btn,
                                down: boolValue(forKey: "down", in: m) ?? false,
                                ts: uintValue(forKey: "ts", in: m) ?? 0)
        case "scroll":
            let m = extractMap(dRaw)
            return .scroll(dx: floatValue(forKey: "dx", in: m) ?? 0,
                           dy: floatValue(forKey: "dy", in: m) ?? 0,
                           momentum: boolValue(forKey: "momentum", in: m) ?? false,
                           ts: uintValue(forKey: "ts", in: m) ?? 0)
        case "gesture":
            return .gesture(try parseGesture(dRaw))
        case "key":
            return .key(try parseKey(dRaw))
        case "text":
            guard let s = stringValue(forKey: "s", in: extractMap(dRaw)) else {
                throw DecodeError.malformed("text")
            }
            return .text(s)
        case "presenter":
            return .presenter(try parsePresenter(dRaw))
        case "bye":
            return .bye(reason: stringValue(forKey: "reason", in: extractMap(dRaw)) ?? "")
        default:
            throw DecodeError.unknownType(tType)
        }
    }

    // MARK: - Specific parsers

    private func parseGesture(_ d: MsgPack) throws -> Gesture {
        let m = extractMap(d)
        let kindRaw = UInt8(truncatingIfNeeded: uintValue(forKey: "kind", in: m) ?? 0)
        let phaseRaw = UInt8(truncatingIfNeeded: uintValue(forKey: "phase", in: m) ?? 0)
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
        let code = UInt16(truncatingIfNeeded: uintValue(forKey: "code", in: m) ?? 0)
        let mods = UInt8(truncatingIfNeeded: uintValue(forKey: "mods", in: m) ?? 0)
        let down = boolValue(forKey: "down", in: m) ?? false
        let ts = uintValue(forKey: "ts", in: m) ?? 0
        return KeyEvent(code: code, modifiers: mods, down: down, ts: ts)
    }

    private func parsePresenter(_ d: MsgPack) throws -> PresenterAction {
        let m = extractMap(d)
        let kindRaw = UInt8(truncatingIfNeeded: uintValue(forKey: "kind", in: m) ?? 0)
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

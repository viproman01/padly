import Foundation

/// Minimal MessagePack codec covering the subset needed by Padly:
/// nil, bool, int (positive/negative fixint, int8/16/32/64, uint8/16/32/64),
/// float32, float64, str (fixstr, str8/16/32), bin (bin8/16/32), array, map.
///
/// Not optimised for throughput — designed for clarity and correctness on
/// small Padly frames (<1 KB).

indirect enum MsgPack: Equatable {
    case nil_
    case bool(Bool)
    case int(Int64)
    case uint(UInt64)
    case float(Float)
    case double(Double)
    case str(String)
    case bin(Data)
    case array([MsgPack])
    case map([(MsgPack, MsgPack)])

    static func == (lhs: MsgPack, rhs: MsgPack) -> Bool {
        // Equality used in tests only; map order matters here.
        switch (lhs, rhs) {
        case (.nil_, .nil_): return true
        case (.bool(let a), .bool(let b)): return a == b
        case (.int(let a), .int(let b)): return a == b
        case (.uint(let a), .uint(let b)): return a == b
        case (.float(let a), .float(let b)): return a == b
        case (.double(let a), .double(let b)): return a == b
        case (.str(let a), .str(let b)): return a == b
        case (.bin(let a), .bin(let b)): return a == b
        case (.array(let a), .array(let b)): return a == b
        case (.map(let a), .map(let b)):
            guard a.count == b.count else { return false }
            for i in 0 ..< a.count where a[i].0 != b[i].0 || a[i].1 != b[i].1 { return false }
            return true
        default: return false
        }
    }
}

enum MsgPackError: Error {
    case truncated
    case unsupportedTag(UInt8)
    case stringDecode
}

enum MsgPackCodec {
    // MARK: - Encode

    static func encode(_ value: MsgPack) -> Data {
        var out = Data()
        encode(value, into: &out)
        return out
    }

    private static func encode(_ value: MsgPack, into out: inout Data) {
        switch value {
        case .nil_:
            out.append(0xC0)
        case .bool(let b):
            out.append(b ? 0xC3 : 0xC2)
        case .int(let i):
            encodeInt(i, into: &out)
        case .uint(let u):
            encodeUInt(u, into: &out)
        case .float(let f):
            out.append(0xCA)
            out.append(contentsOf: f.bitPattern.bigEndianBytes)
        case .double(let d):
            out.append(0xCB)
            out.append(contentsOf: d.bitPattern.bigEndianBytes)
        case .str(let s):
            let utf8 = Data(s.utf8)
            encodeStrHeader(count: utf8.count, into: &out)
            out.append(utf8)
        case .bin(let data):
            encodeBinHeader(count: data.count, into: &out)
            out.append(data)
        case .array(let items):
            encodeArrayHeader(count: items.count, into: &out)
            for item in items { encode(item, into: &out) }
        case .map(let pairs):
            encodeMapHeader(count: pairs.count, into: &out)
            for (k, v) in pairs {
                encode(k, into: &out)
                encode(v, into: &out)
            }
        }
    }

    private static func encodeInt(_ i: Int64, into out: inout Data) {
        if i >= 0 { encodeUInt(UInt64(i), into: &out); return }
        if i >= -32 {
            out.append(UInt8(bitPattern: Int8(i)))
        } else if i >= Int64(Int8.min) {
            out.append(0xD0); out.append(UInt8(bitPattern: Int8(i)))
        } else if i >= Int64(Int16.min) {
            out.append(0xD1); out.append(contentsOf: Int16(i).bigEndianBytes)
        } else if i >= Int64(Int32.min) {
            out.append(0xD2); out.append(contentsOf: Int32(i).bigEndianBytes)
        } else {
            out.append(0xD3); out.append(contentsOf: i.bigEndianBytes)
        }
    }

    private static func encodeUInt(_ u: UInt64, into out: inout Data) {
        if u <= 0x7F {
            out.append(UInt8(u))
        } else if u <= UInt64(UInt8.max) {
            out.append(0xCC); out.append(UInt8(u))
        } else if u <= UInt64(UInt16.max) {
            out.append(0xCD); out.append(contentsOf: UInt16(u).bigEndianBytes)
        } else if u <= UInt64(UInt32.max) {
            out.append(0xCE); out.append(contentsOf: UInt32(u).bigEndianBytes)
        } else {
            out.append(0xCF); out.append(contentsOf: u.bigEndianBytes)
        }
    }

    private static func encodeStrHeader(count: Int, into out: inout Data) {
        if count < 32 {
            out.append(0xA0 | UInt8(count))
        } else if count <= UInt8.max {
            out.append(0xD9); out.append(UInt8(count))
        } else if count <= UInt16.max {
            out.append(0xDA); out.append(contentsOf: UInt16(count).bigEndianBytes)
        } else {
            out.append(0xDB); out.append(contentsOf: UInt32(count).bigEndianBytes)
        }
    }

    private static func encodeBinHeader(count: Int, into out: inout Data) {
        if count <= UInt8.max {
            out.append(0xC4); out.append(UInt8(count))
        } else if count <= UInt16.max {
            out.append(0xC5); out.append(contentsOf: UInt16(count).bigEndianBytes)
        } else {
            out.append(0xC6); out.append(contentsOf: UInt32(count).bigEndianBytes)
        }
    }

    private static func encodeArrayHeader(count: Int, into out: inout Data) {
        if count < 16 {
            out.append(0x90 | UInt8(count))
        } else if count <= UInt16.max {
            out.append(0xDC); out.append(contentsOf: UInt16(count).bigEndianBytes)
        } else {
            out.append(0xDD); out.append(contentsOf: UInt32(count).bigEndianBytes)
        }
    }

    private static func encodeMapHeader(count: Int, into out: inout Data) {
        if count < 16 {
            out.append(0x80 | UInt8(count))
        } else if count <= UInt16.max {
            out.append(0xDE); out.append(contentsOf: UInt16(count).bigEndianBytes)
        } else {
            out.append(0xDF); out.append(contentsOf: UInt32(count).bigEndianBytes)
        }
    }

    // MARK: - Decode

    static func decode(_ data: Data) throws -> MsgPack {
        var cursor = 0
        let value = try decode(data, cursor: &cursor)
        return value
    }

    private static func decode(_ data: Data, cursor: inout Int) throws -> MsgPack {
        guard cursor < data.count else { throw MsgPackError.truncated }
        let b = data[cursor]; cursor += 1

        switch b {
        case 0xC0: return .nil_
        case 0xC2: return .bool(false)
        case 0xC3: return .bool(true)
        case 0xCA: return .float(Float(bitPattern: try read32(data, &cursor)))
        case 0xCB: return .double(Double(bitPattern: try read64(data, &cursor)))
        case 0xCC: return .uint(UInt64(try read8(data, &cursor)))
        case 0xCD: return .uint(UInt64(try read16(data, &cursor)))
        case 0xCE: return .uint(UInt64(try read32(data, &cursor)))
        case 0xCF: return .uint(try read64(data, &cursor))
        case 0xD0: return .int(Int64(Int8(bitPattern: try read8(data, &cursor))))
        case 0xD1: return .int(Int64(Int16(bitPattern: try read16(data, &cursor))))
        case 0xD2: return .int(Int64(Int32(bitPattern: try read32(data, &cursor))))
        case 0xD3: return .int(Int64(bitPattern: try read64(data, &cursor)))
        case 0xD9: return try readString(data, &cursor, count: Int(try read8(data, &cursor)))
        case 0xDA: return try readString(data, &cursor, count: Int(try read16(data, &cursor)))
        case 0xDB: return try readString(data, &cursor, count: Int(try read32(data, &cursor)))
        case 0xC4: return .bin(try readBytes(data, &cursor, count: Int(try read8(data, &cursor))))
        case 0xC5: return .bin(try readBytes(data, &cursor, count: Int(try read16(data, &cursor))))
        case 0xC6: return .bin(try readBytes(data, &cursor, count: Int(try read32(data, &cursor))))
        case 0xDC: return try readArray(data, &cursor, count: Int(try read16(data, &cursor)))
        case 0xDD: return try readArray(data, &cursor, count: Int(try read32(data, &cursor)))
        case 0xDE: return try readMap(data, &cursor, count: Int(try read16(data, &cursor)))
        case 0xDF: return try readMap(data, &cursor, count: Int(try read32(data, &cursor)))
        default:
            if b <= 0x7F { return .uint(UInt64(b)) }
            if b >= 0xE0 { return .int(Int64(Int8(bitPattern: b))) }
            if b & 0xE0 == 0xA0 { return try readString(data, &cursor, count: Int(b & 0x1F)) }
            if b & 0xF0 == 0x90 { return try readArray(data, &cursor, count: Int(b & 0x0F)) }
            if b & 0xF0 == 0x80 { return try readMap(data, &cursor, count: Int(b & 0x0F)) }
            throw MsgPackError.unsupportedTag(b)
        }
    }

    private static func read8(_ d: Data, _ c: inout Int) throws -> UInt8 {
        guard c < d.count else { throw MsgPackError.truncated }
        let v = d[c]; c += 1; return v
    }

    private static func read16(_ d: Data, _ c: inout Int) throws -> UInt16 {
        guard c + 2 <= d.count else { throw MsgPackError.truncated }
        let v = (UInt16(d[c]) << 8) | UInt16(d[c + 1])
        c += 2; return v
    }

    private static func read32(_ d: Data, _ c: inout Int) throws -> UInt32 {
        guard c + 4 <= d.count else { throw MsgPackError.truncated }
        var v: UInt32 = 0
        for i in 0 ..< 4 { v = (v << 8) | UInt32(d[c + i]) }
        c += 4; return v
    }

    private static func read64(_ d: Data, _ c: inout Int) throws -> UInt64 {
        guard c + 8 <= d.count else { throw MsgPackError.truncated }
        var v: UInt64 = 0
        for i in 0 ..< 8 { v = (v << 8) | UInt64(d[c + i]) }
        c += 8; return v
    }

    private static func readString(_ d: Data, _ c: inout Int, count: Int) throws -> MsgPack {
        guard c + count <= d.count else { throw MsgPackError.truncated }
        let slice = d.subdata(in: c ..< c + count)
        c += count
        guard let s = String(data: slice, encoding: .utf8) else { throw MsgPackError.stringDecode }
        return .str(s)
    }

    private static func readBytes(_ d: Data, _ c: inout Int, count: Int) throws -> Data {
        guard c + count <= d.count else { throw MsgPackError.truncated }
        let slice = d.subdata(in: c ..< c + count)
        c += count
        return slice
    }

    private static func readArray(_ d: Data, _ c: inout Int, count: Int) throws -> MsgPack {
        var arr: [MsgPack] = []
        arr.reserveCapacity(count)
        for _ in 0 ..< count {
            arr.append(try decode(d, cursor: &c))
        }
        return .array(arr)
    }

    private static func readMap(_ d: Data, _ c: inout Int, count: Int) throws -> MsgPack {
        var pairs: [(MsgPack, MsgPack)] = []
        pairs.reserveCapacity(count)
        for _ in 0 ..< count {
            let k = try decode(d, cursor: &c)
            let v = try decode(d, cursor: &c)
            pairs.append((k, v))
        }
        return .map(pairs)
    }
}

// MARK: - BigEndian byte helpers

private extension FixedWidthInteger {
    var bigEndianBytes: [UInt8] {
        var v = self.bigEndian
        return withUnsafeBytes(of: &v) { Array($0) }
    }
}

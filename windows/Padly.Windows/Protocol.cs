using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;

namespace Padly.Windows;

internal sealed class ProtocolException(string message) : Exception(message);

/// <summary>Small bounded MessagePack subset used by the v2 wire protocol.</summary>
internal static class Protocol
{
    internal const int MaximumFrame = 65536;
    private static readonly UTF8Encoding Utf8 = new(false, true);

    internal sealed record Message(string Type, Dictionary<string, object?> Fields);

    internal static Message Decode(byte[] bytes)
    {
        if (bytes.Length == 0 || bytes.Length > MaximumFrame) throw new ProtocolException("Invalid packet length");
        var reader = new Reader(bytes);
        if (reader.Value(0) is not Dictionary<string, object?> map || reader.Offset != bytes.Length ||
            map.Count != 2 || !map.TryGetValue("t", out var type) || type is not string tag ||
            tag.Length is < 1 or > 32 || !map.TryGetValue("d", out var fields) ||
            fields is not Dictionary<string, object?> data) throw new ProtocolException("Invalid message envelope");
        return new Message(tag, data);
    }

    internal static byte[] Encode(string type, params (string Key, object? Value)[] fields)
    {
        using var stream = new MemoryStream();
        Write(stream, new Dictionary<string, object?> {
            ["t"] = type, ["d"] = fields.ToDictionary(p => p.Key, p => p.Value)
        });
        return stream.ToArray();
    }

    internal static string String(Dictionary<string, object?> fields, string key, int max, bool allowEmpty = false)
    {
        if (!fields.TryGetValue(key, out var raw) || raw is not string value || value.Length > max ||
            (!allowEmpty && value.Length == 0)) throw new ProtocolException("Invalid " + key);
        return value;
    }

    internal static byte[] Binary(Dictionary<string, object?> fields, string key, int length)
    {
        if (!fields.TryGetValue(key, out var raw) || raw is not byte[] bytes || bytes.Length != length)
            throw new ProtocolException("Invalid " + key);
        return bytes;
    }

    internal static bool Boolean(Dictionary<string, object?> fields, string key)
    {
        if (!fields.TryGetValue(key, out var raw) || raw is not bool value) throw new ProtocolException("Invalid " + key);
        return value;
    }

    internal static ulong Unsigned(Dictionary<string, object?> fields, string key, ulong max = ulong.MaxValue)
    {
        if (!fields.TryGetValue(key, out var raw) || raw is not ulong value || value > max)
            throw new ProtocolException("Invalid " + key);
        return value;
    }

    internal static float Number(Dictionary<string, object?> fields, string key, float max = 32768)
    {
        if (!fields.TryGetValue(key, out var raw)) throw new ProtocolException("Missing " + key);
        var value = raw switch { float v => v, double v => v, ulong v => v, long v => v, _ => double.NaN };
        if (!double.IsFinite(value) || Math.Abs(value) > max) throw new ProtocolException("Invalid " + key);
        return (float)value;
    }

    private sealed class Reader(byte[] data)
    {
        internal int Offset;
        private int nodes;
        private ReadOnlySpan<byte> Take(int length)
        {
            if (length < 0 || length > data.Length - Offset) throw new ProtocolException("Truncated MessagePack");
            var span = data.AsSpan(Offset, length); Offset += length; return span;
        }
        private byte Byte() => Take(1)[0];
        private int Count(uint count, int maximum)
        {
            if (count > maximum) throw new ProtocolException("MessagePack collection too large");
            return (int)count;
        }
        private string Text(int length) => Utf8.GetString(Take(Count((uint)length, 16384)));
        private byte[] Bin(int length) => Take(Count((uint)length, MaximumFrame)).ToArray();
        private object?[] Array(int count, int depth)
        {
            Count((uint)count, 128);
            if (count > data.Length - Offset) throw new ProtocolException("Truncated array");
            var array = new object?[count];
            for (var i = 0; i < count; i++) array[i] = Value(depth + 1);
            return array;
        }
        private Dictionary<string, object?> Map(int count, int depth)
        {
            Count((uint)count, 128);
            if (count > (data.Length - Offset) / 2) throw new ProtocolException("Truncated map");
            var map = new Dictionary<string, object?>(count, StringComparer.Ordinal);
            for (var i = 0; i < count; i++)
            {
                if (Value(depth + 1) is not string key || key.Length > 128 || map.ContainsKey(key))
                    throw new ProtocolException("Invalid or duplicate map key");
                map.Add(key, Value(depth + 1));
            }
            return map;
        }
        internal object? Value(int depth)
        {
            if (depth > 12 || ++nodes > 2048) throw new ProtocolException("MessagePack complexity limit");
            var code = Byte();
            if (code <= 0x7f) return (ulong)code;
            if (code >= 0xe0) return (long)(sbyte)code;
            if ((code & 0xe0) == 0xa0) return Text(code & 31);
            if ((code & 0xf0) == 0x90) return Array(code & 15, depth);
            if ((code & 0xf0) == 0x80) return Map(code & 15, depth);
            return code switch {
                0xc0 => null, 0xc2 => false, 0xc3 => true,
                0xc4 => Bin(Byte()), 0xc5 => Bin(BinaryPrimitives.ReadUInt16BigEndian(Take(2))),
                0xc6 => Bin(Count(BinaryPrimitives.ReadUInt32BigEndian(Take(4)), MaximumFrame)),
                0xca => BitConverter.Int32BitsToSingle(BinaryPrimitives.ReadInt32BigEndian(Take(4))),
                0xcb => BitConverter.Int64BitsToDouble(BinaryPrimitives.ReadInt64BigEndian(Take(8))),
                0xcc => (ulong)Byte(), 0xcd => (ulong)BinaryPrimitives.ReadUInt16BigEndian(Take(2)),
                0xce => (ulong)BinaryPrimitives.ReadUInt32BigEndian(Take(4)),
                0xcf => BinaryPrimitives.ReadUInt64BigEndian(Take(8)),
                0xd0 => (long)(sbyte)Byte(), 0xd1 => (long)BinaryPrimitives.ReadInt16BigEndian(Take(2)),
                0xd2 => (long)BinaryPrimitives.ReadInt32BigEndian(Take(4)), 0xd3 => BinaryPrimitives.ReadInt64BigEndian(Take(8)),
                0xd9 => Text(Byte()), 0xda => Text(BinaryPrimitives.ReadUInt16BigEndian(Take(2))),
                0xdb => Text(Count(BinaryPrimitives.ReadUInt32BigEndian(Take(4)), 16384)),
                0xdc => Array(BinaryPrimitives.ReadUInt16BigEndian(Take(2)), depth),
                0xdd => Array(Count(BinaryPrimitives.ReadUInt32BigEndian(Take(4)), 128), depth),
                0xde => Map(BinaryPrimitives.ReadUInt16BigEndian(Take(2)), depth),
                0xdf => Map(Count(BinaryPrimitives.ReadUInt32BigEndian(Take(4)), 128), depth),
                _ => throw new ProtocolException("Unsupported MessagePack value")
            };
        }
    }

    private static void Length(Stream stream, byte fixedTag, int fixedLimit, byte shortTag, byte longTag, int length)
    {
        if (length < fixedLimit) stream.WriteByte((byte)(fixedTag | length));
        else if (length <= byte.MaxValue && shortTag != 0) { stream.WriteByte(shortTag); stream.WriteByte((byte)length); }
        else { stream.WriteByte(longTag); Span<byte> b = stackalloc byte[2]; BinaryPrimitives.WriteUInt16BigEndian(b, checked((ushort)length)); stream.Write(b); }
    }
    private static void Write(Stream stream, object? value)
    {
        switch (value)
        {
            case null: stream.WriteByte(0xc0); break;
            case bool flag: stream.WriteByte(flag ? (byte)0xc3 : (byte)0xc2); break;
            case string text:
                var bytes = Utf8.GetBytes(text); Length(stream, 0xa0, 32, 0xd9, 0xda, bytes.Length); stream.Write(bytes); break;
            case byte[] binary:
                Length(stream, 0, 0, 0xc4, 0xc5, binary.Length); stream.Write(binary); break;
            case int number when number >= 0: Write(stream, (ulong)number); break;
            case uint number: Write(stream, (ulong)number); break;
            case ulong number:
                if (number < 128) stream.WriteByte((byte)number);
                else { stream.WriteByte(0xcf); Span<byte> b = stackalloc byte[8]; BinaryPrimitives.WriteUInt64BigEndian(b, number); stream.Write(b); }
                break;
            case Dictionary<string, object?> map:
                Length(stream, 0x80, 16, 0, 0xde, map.Count);
                foreach (var item in map) { Write(stream, item.Key); Write(stream, item.Value); } break;
            default: throw new InvalidOperationException("Unsupported encoded value");
        }
    }
}

internal sealed class Handshake : IDisposable
{
    private readonly ECDiffieHellman mine = ECDiffieHellman.Create(ECCurve.NamedCurves.nistP256);
    internal byte[] PublicKey
    {
        get { var p = mine.ExportParameters(false); return [4, .. p.Q.X!, .. p.Q.Y!]; }
    }
    internal byte[] Derive(byte[] peerPublic, byte[]? ltk)
    {
        if (peerPublic.Length != 65 || peerPublic[0] != 4) throw new ProtocolException("Invalid P-256 public key");
        using var peer = ECDiffieHellman.Create(new ECParameters {
            Curve = ECCurve.NamedCurves.nistP256,
            Q = new ECPoint { X = peerPublic[1..33], Y = peerPublic[33..65] }
        });
        var raw = mine.DeriveRawSecretAgreement(peer.PublicKey);
        try
        {
            // HKDF-SHA256, one output block (32 bytes), client public key first.
            var salt = Encoding.UTF8.GetBytes(ltk is null ? "padly-pair-v2" : "padly-resume-v2");
            var prk = HMACSHA256.HashData(salt, raw);
            try
            {
                byte[] info = [.. peerPublic, .. PublicKey, .. (ltk ?? []), 1];
                return HMACSHA256.HashData(prk, info);
            }
            finally { CryptographicOperations.ZeroMemory(prk); }
        }
        finally { CryptographicOperations.ZeroMemory(raw); }
    }
    public void Dispose() => mine.Dispose();
}

internal sealed class SecureChannel(byte[] key) : IDisposable
{
    private readonly AesGcm aes = new(key, 16);
    private uint sendSequence;
    private uint receiveSequence;
    internal byte[] Seal(byte[] plain)
    {
        if (sendSequence == uint.MaxValue || plain.Length > Protocol.MaximumFrame - 33)
            throw new ProtocolException("Session expired or frame too large");
        var seq = ++sendSequence;
        var frame = new byte[plain.Length + 33];
        frame[0] = 2; BinaryPrimitives.WriteUInt32LittleEndian(frame.AsSpan(1), seq);
        frame[5] = 2; BinaryPrimitives.WriteUInt32LittleEndian(frame.AsSpan(9), seq);
        aes.Encrypt(frame.AsSpan(5, 12), plain, frame.AsSpan(17, plain.Length), frame.AsSpan(17 + plain.Length, 16), frame.AsSpan(0, 5));
        return frame;
    }
    internal byte[] Open(byte[] frame)
    {
        if (frame.Length < 33 || frame.Length > Protocol.MaximumFrame || frame[0] != 2)
            throw new ProtocolException("Invalid sealed frame");
        var seq = BinaryPrimitives.ReadUInt32LittleEndian(frame.AsSpan(1));
        if (seq <= receiveSequence) throw new ProtocolException("Replay rejected");
        Span<byte> nonce = stackalloc byte[12]; nonce.Clear(); nonce[0] = 1;
        BinaryPrimitives.WriteUInt32LittleEndian(nonce[4..], seq);
        if (!CryptographicOperations.FixedTimeEquals(nonce, frame.AsSpan(5, 12))) throw new ProtocolException("Invalid nonce");
        var plain = new byte[frame.Length - 33];
        aes.Decrypt(nonce, frame.AsSpan(17, plain.Length), frame.AsSpan(frame.Length - 16, 16), plain, frame.AsSpan(0, 5));
        receiveSequence = seq;
        return plain;
    }
    public void Dispose() => aes.Dispose();
}

using System.ComponentModel;
using System.Runtime.InteropServices;

namespace Padly.Windows;

/// <summary>Injects only the input received from an authenticated Padly session.</summary>
public sealed class WindowsInputSink : IInputSink, IDisposable
{
    private readonly object gate = new();
    private readonly Dictionary<ushort, byte> heldKeys = [];
    private readonly HashSet<byte> heldButtons = [];
    private byte heldModifiers;
    private double moveX, moveY, wheelX, wheelY;
    private bool disposed;

    // A wheel notch is 120 units. Retaining fractional units preserves slow gestures.
    private const double WheelUnitsPerPixel = 2;
    private const uint KeyUp = 0x0002, Unicode = 0x0004, ScanCode = 0x0008, Extended = 0x0001;
    private static readonly byte[] ModifierOrder = [4, 8, 2, 1];

    public void Move(float dx, float dy)
    {
        lock (gate)
        {
            ThrowIfDisposed();
            if (!ValidDelta(dx, dy)) return;
            var x = Accumulate(ref moveX, Math.Clamp(dx, -4096, 4096));
            var y = Accumulate(ref moveY, Math.Clamp(dy, -4096, 4096));
            if (x != 0 || y != 0) Send([Mouse(0x0001, x, y)]);
        }
    }

    public void Button(byte button, bool down)
    {
        if (button > 2) return;
        lock (gate)
        {
            ThrowIfDisposed();
            if (heldButtons.Contains(button) == down) return;
            // Track an attempted press too: a partial native failure must remain releasable.
            if (down) heldButtons.Add(button);
            Send([Mouse(ButtonFlag(button, down))]);
            if (!down) heldButtons.Remove(button);
        }
    }

    public void Scroll(float dx, float dy)
    {
        lock (gate)
        {
            ThrowIfDisposed();
            if (!ValidDelta(dx, dy)) return;
            // Positive y means fingers/content down (wheel up). Windows horizontal
            // wheel uses the opposite sign, so content follows the fingers on both axes.
            var y = Accumulate(ref wheelY, Math.Clamp(dy, -4096, 4096) * WheelUnitsPerPixel);
            var x = Accumulate(ref wheelX, -Math.Clamp(dx, -4096, 4096) * WheelUnitsPerPixel);
            var packets = new List<Input>(2);
            if (y != 0) packets.Add(Mouse(0x0800, data: y));
            if (x != 0) packets.Add(Mouse(0x1000, data: x));
            Send(packets);
        }
    }

    public void Text(string text)
    {
        ArgumentNullException.ThrowIfNull(text);
        lock (gate)
        {
            ThrowIfDisposed();
            // Literal text should not become Ctrl/Alt shortcuts. Restore session-owned
            // modifiers afterwards; no clipboard, layout switch, or global key hook is used.
            var restore = heldModifiers;
            SetModifiers(0);
            try
            {
                // Send complete down/up pairs for each UTF-16 unit, including surrogate
                // pairs. Small batches avoid flooding the desktop input queue.
                for (var offset = 0; offset < text.Length; offset += 64)
                {
                    var packets = new List<Input>(128);
                    foreach (var unit in text.AsSpan(offset, Math.Min(64, text.Length - offset)))
                    {
                        packets.Add(Keyboard(0, unit, Unicode));
                        packets.Add(Keyboard(0, unit, Unicode | KeyUp));
                    }
                    Send(packets);
                }
            }
            finally { SetModifiers(restore); }
        }
    }

    public void Key(ushort code, byte mods, bool down)
    {
        var key = Translate(code);
        if (key is null) return;
        lock (gate)
        {
            ThrowIfDisposed();
            mods &= 0x0f; // cmd/opt/ctrl/shift; Windows has no generic Fn key.
            if (code is >= 0xe0 and <= 0xe7)
            {
                // Protocol modifiers normally arrive in mods. Also accept explicit HID
                // modifier events without double-pressing the same modifier.
                if (down) heldKeys[code] = (byte)(mods | ModifierForCode(code));
                else heldKeys.Remove(code);
                SetModifiers(DesiredModifiers());
                return;
            }
            if (down)
            {
                heldKeys[code] = mods;
                SetModifiers(DesiredModifiers());
                Send([KeyPacket(key.Value, false)]);
            }
            else
            {
                // Release the ordinary key while its modifiers are still held, then
                // release modifiers no longer needed by any other held key.
                if (!heldKeys.ContainsKey(code)) return;
                Send([KeyPacket(key.Value, true)]);
                heldKeys.Remove(code);
                SetModifiers(DesiredModifiers());
            }
        }
    }

    public void ReleaseAll()
    {
        lock (gate)
        {
            if (disposed) return;
            var packets = new List<Input>();
            foreach (var code in heldKeys.Keys.Reverse())
                if (code is not (>= 0xe0 and <= 0xe7) && Translate(code) is { } key)
                    packets.Add(KeyPacket(key, true));
            foreach (var bit in ModifierOrder.Reverse())
                if ((heldModifiers & bit) != 0) packets.Add(ModifierPacket(bit, true));
            foreach (var button in heldButtons.OrderDescending()) packets.Add(Mouse(ButtonFlag(button, false)));
            Send(packets);
            heldKeys.Clear();
            heldButtons.Clear();
            heldModifiers = 0;
            moveX = moveY = wheelX = wheelY = 0;
        }
    }

    public void Dispose()
    {
        lock (gate)
        {
            if (disposed) return;
            ReleaseAll();
            disposed = true;
        }
    }

    private byte DesiredModifiers()
    {
        byte mods = 0;
        foreach (var value in heldKeys.Values) mods |= value;
        return mods;
    }

    private void SetModifiers(byte desired)
    {
        foreach (var bit in ModifierOrder.Reverse())
        {
            if ((heldModifiers & bit) == 0 || (desired & bit) != 0) continue;
            Send([ModifierPacket(bit, true)]);
            heldModifiers &= (byte)~bit;
        }
        foreach (var bit in ModifierOrder)
        {
            if ((heldModifiers & bit) != 0 || (desired & bit) == 0) continue;
            heldModifiers |= bit;
            Send([ModifierPacket(bit, false)]);
        }
    }

    private void ThrowIfDisposed() => ObjectDisposedException.ThrowIf(disposed, this);
    private static bool ValidDelta(float dx, float dy) => float.IsFinite(dx) && float.IsFinite(dy);
    private static int Accumulate(ref double remainder, double value)
    {
        remainder += value;
        var whole = (int)Math.Truncate(remainder);
        remainder -= whole;
        return whole;
    }

    private static uint ButtonFlag(byte button, bool down) => button switch
    {
        0 => down ? 0x0002u : 0x0004u,
        1 => down ? 0x0008u : 0x0010u,
        _ => down ? 0x0020u : 0x0040u
    };

    private static byte ModifierForCode(ushort code) => (code & 3) switch
    { 0 => 4, 1 => 8, 2 => 2, _ => 1 };
    private static Input ModifierPacket(byte bit, bool up) => KeyPacket(bit switch
    {
        4 => new KeySpec(0x1d), 8 => new KeySpec(0x2a),
        2 => new KeySpec(0x38), _ => new KeySpec(0x5b, true)
    }, up);
    private static Input KeyPacket(KeySpec key, bool up) => key.VirtualKey != 0
        ? Keyboard(key.VirtualKey, 0, up ? KeyUp : 0)
        : Keyboard(0, key.Scan, ScanCode | (key.Extended ? Extended : 0) | (up ? KeyUp : 0));
    private readonly record struct KeySpec(ushort Scan, bool Extended = false, ushort VirtualKey = 0);

    private static KeySpec? Translate(ushort hid)
    {
        // USB HID page 0x07 -> PC/AT set-1 physical scan codes. Text() is used
        // for literal multilingual input; Key() respects the active Windows layout.
        ushort[] letters = [0x1e,0x30,0x2e,0x20,0x12,0x21,0x22,0x23,0x17,0x24,0x25,0x26,0x32,0x31,0x18,0x19,0x10,0x13,0x1f,0x14,0x16,0x2f,0x11,0x2d,0x15,0x2c];
        if (hid is >= 4 and <= 29) return new(letters[hid - 4]);
        if (hid is >= 30 and <= 38) return new((ushort)(hid - 28));
        if (hid == 39) return new(0x0b);
        if (hid is >= 58 and <= 67) return new((ushort)(hid + 1));
        if (hid is >= 104 and <= 115) return new(0, VirtualKey: (ushort)(0x7c + hid - 104)); // F13..F24
        return hid switch
        {
            40 => new(0x1c), 41 => new(0x01), 42 => new(0x0e), 43 => new(0x0f),
            44 => new(0x39), 45 => new(0x0c), 46 => new(0x0d), 47 => new(0x1a),
            48 => new(0x1b), 49 or 50 => new(0x2b), 51 => new(0x27), 52 => new(0x28),
            53 => new(0x29), 54 => new(0x33), 55 => new(0x34), 56 => new(0x35),
            57 => new(0x3a), 68 => new(0x57), 69 => new(0x58),
            70 => new(0x37, true), 71 => new(0x46), 72 => new(0, VirtualKey: 0x13),
            73 => new(0x52, true), 74 => new(0x47, true), 75 => new(0x49, true),
            76 => new(0x53, true), 77 => new(0x4f, true), 78 => new(0x51, true),
            79 => new(0x4d, true), 80 => new(0x4b, true), 81 => new(0x50, true), 82 => new(0x48, true),
            83 => new(0x45), 84 => new(0x35, true), 85 => new(0x37), 86 => new(0x4a),
            87 => new(0x4e), 88 => new(0x1c, true), 89 => new(0x4f), 90 => new(0x50),
            91 => new(0x51), 92 => new(0x4b), 93 => new(0x4c), 94 => new(0x4d),
            95 => new(0x47), 96 => new(0x48), 97 => new(0x49), 98 => new(0x52), 99 => new(0x53),
            100 => new(0x56), 101 => new(0x5d, true), 103 => new(0, VirtualKey: 0xbb),
            0xe0 => new(0x1d), 0xe1 => new(0x2a), 0xe2 => new(0x38), 0xe3 => new(0x5b, true),
            0xe4 => new(0x1d, true), 0xe5 => new(0x36), 0xe6 => new(0x38, true), 0xe7 => new(0x5c, true),
            _ => null
        };
    }

    private static Input Mouse(uint flags, int x = 0, int y = 0, int data = 0) => new()
    {
        Type = 0, Data = new InputUnion { Mouse = new MouseInput { X = x, Y = y, Data = unchecked((uint)data), Flags = flags } }
    };
    private static Input Keyboard(ushort vk, ushort scan, uint flags) => new()
    {
        Type = 1, Data = new InputUnion { Keyboard = new KeyboardInput { VirtualKey = vk, Scan = scan, Flags = flags } }
    };
    private static void Send(List<Input> packets)
    {
        if (packets.Count == 0) return;
        var inputs = packets.ToArray();
        var size = Marshal.SizeOf<Input>();
        if (size != (IntPtr.Size == 8 ? 40 : 28)) throw new InvalidOperationException("Unexpected Win32 INPUT layout.");
        var sent = SendInput((uint)inputs.Length, inputs, size);
        if (sent == inputs.Length) return;
        var error = Marshal.GetLastWin32Error();
        var detail = error == 0 ? "Windows blocked the input" : new Win32Exception(error).Message;
        throw new InvalidOperationException($"SendInput sent {sent}/{inputs.Length} events: {detail}. " +
            "Windows does not allow a normal application to control elevated administrator windows or the secure desktop (UIPI).");
    }

    // Sequential INPUT contains an explicitly overlaid union. Pointer-sized
    // dwExtraInfo gives the union 8-byte alignment on x64 (INPUT is 40 bytes).
    [StructLayout(LayoutKind.Sequential)]
    private struct Input { public uint Type; public InputUnion Data; }
    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion
    {
        [FieldOffset(0)] public MouseInput Mouse;
        [FieldOffset(0)] public KeyboardInput Keyboard;
        [FieldOffset(0)] public HardwareInput Hardware;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct MouseInput { public int X, Y; public uint Data, Flags, Time; public UIntPtr ExtraInfo; }
    [StructLayout(LayoutKind.Sequential)]
    private struct KeyboardInput { public ushort VirtualKey, Scan; public uint Flags, Time; public UIntPtr ExtraInfo; }
    [StructLayout(LayoutKind.Sequential)]
    private struct HardwareInput { public uint Message; public ushort ParamLow, ParamHigh; }
    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint count, [In] Input[] inputs, int size);
}

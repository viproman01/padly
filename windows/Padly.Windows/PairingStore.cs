using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text.Json;

namespace Padly.Windows;

public sealed record PairedDevice(string DeviceId, string DeviceName, DateTimeOffset LastSeen);

internal sealed record StoredDevice(string DeviceId, string DeviceName, byte[] Ltk, DateTimeOffset LastSeen)
{
    internal PairedDevice Public => new(DeviceId, DeviceName, LastSeen);
}

/// <summary>Current-user DPAPI storage. An unreadable store is an error, never a fresh pairing database.</summary>
internal sealed class PairingStore
{
    private sealed record Document(int Version, string ServerId, List<StoredDevice> Devices);
    private readonly object gate = new();
    private readonly string path;
    private readonly bool ephemeral;
    private Document document;
    internal string ServerId { get; }

    internal PairingStore(string directory, bool ephemeral)
    {
        this.ephemeral = ephemeral;
        path = Path.Combine(directory, "pairings.dpapi");
        if (!ephemeral && File.Exists(path))
        {
            if (new FileInfo(path).Length > 131072) throw new InvalidDataException("Хранилище Padly повреждено: слишком большой файл.");
            var plain = Dpapi.Unprotect(File.ReadAllBytes(path));
            try
            {
                document = JsonSerializer.Deserialize<Document>(plain) ?? throw new InvalidDataException("Пустое хранилище Padly.");
                if (document.Version != 1 || !Guid.TryParse(document.ServerId, out _) || document.Devices is null ||
                    document.Devices.Count > 128 || document.Devices.Select(x => x.DeviceId).Distinct().Count() != document.Devices.Count ||
                    document.Devices.Any(x => x.DeviceId is null || x.DeviceId.Length is < 1 or > 128 ||
                        x.DeviceName is null || x.DeviceName.Length is < 1 or > 128 || x.Ltk is null || x.Ltk.Length != 32))
                    throw new InvalidDataException("Некорректное хранилище подключённых устройств Padly.");
            }
            catch (JsonException e) { throw new InvalidDataException("Не удалось прочитать хранилище Padly. Существующие подключения сохранены.", e); }
            finally { CryptographicOperations.ZeroMemory(plain); }
        }
        else
        {
            document = new Document(1, Guid.NewGuid().ToString(), []);
            Save(document);
        }
        ServerId = document.ServerId;
    }

    internal IReadOnlyList<PairedDevice> Devices { get { lock (gate) return document.Devices.Select(d => d.Public).ToArray(); } }
    internal StoredDevice? Find(string id)
    {
        lock (gate)
        {
            var value = document.Devices.FirstOrDefault(d => d.DeviceId == id);
            return value is null ? null : value with { Ltk = (byte[])value.Ltk.Clone() };
        }
    }
    internal void Upsert(StoredDevice device)
    {
        lock (gate)
        {
            var next = document.Devices.Where(d => d.DeviceId != device.DeviceId).ToList();
            if (next.Count >= 128) throw new InvalidOperationException("Достигнут лимит подключённых устройств (128). Удалите старые подключения.");
            next.Add(device with { Ltk = (byte[])device.Ltk.Clone() });
            var updated = document with { Devices = next };
            Save(updated); document = updated;
        }
    }
    internal void Touch(string id)
    {
        lock (gate)
        {
            var next = document.Devices.Select(d => d.DeviceId == id ? d with { LastSeen = DateTimeOffset.UtcNow } : d).ToList();
            var updated = document with { Devices = next };
            Save(updated); document = updated;
        }
    }
    internal void ForgetAll()
    {
        lock (gate)
        {
            var updated = document with { Devices = [] };
            Save(updated);
            foreach (var device in document.Devices) CryptographicOperations.ZeroMemory(device.Ltk);
            document = updated;
        }
    }
    private void Save(Document next)
    {
        if (ephemeral) return;
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var plain = JsonSerializer.SerializeToUtf8Bytes(next);
        byte[] sealedData;
        try { sealedData = Dpapi.Protect(plain); }
        finally { CryptographicOperations.ZeroMemory(plain); }
        var temporary = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            using (var file = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None, 4096, FileOptions.WriteThrough))
            { file.Write(sealedData); file.Flush(true); }
            File.Move(temporary, path, true);
        }
        finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }

    private static class Dpapi
    {
        [StructLayout(LayoutKind.Sequential)]
        private struct Blob { internal int Size; internal IntPtr Data; }
        [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool CryptProtectData(ref Blob input, string? description, IntPtr entropy, IntPtr reserved, IntPtr prompt, int flags, out Blob output);
        [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool CryptUnprotectData(ref Blob input, IntPtr description, IntPtr entropy, IntPtr reserved, IntPtr prompt, int flags, out Blob output);
        [DllImport("kernel32.dll")] private static extern IntPtr LocalFree(IntPtr memory);
        internal static byte[] Protect(byte[] bytes) => Transform(bytes, true);
        internal static byte[] Unprotect(byte[] bytes) => Transform(bytes, false);
        private static byte[] Transform(byte[] bytes, bool protect)
        {
            var input = new Blob { Size = bytes.Length, Data = Marshal.AllocHGlobal(bytes.Length) };
            var output = new Blob();
            try
            {
                Marshal.Copy(bytes, 0, input.Data, bytes.Length);
                // CRYPTPROTECT_UI_FORBIDDEN, without LOCAL_MACHINE: only this Windows user can decrypt.
                var success = protect ? CryptProtectData(ref input, "Padly pairings", IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 1, out output)
                    : CryptUnprotectData(ref input, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 1, out output);
                if (!success) throw new Win32Exception(Marshal.GetLastWin32Error(), "Не удалось открыть защищённое хранилище Padly текущего пользователя Windows.");
                var result = new byte[output.Size]; Marshal.Copy(output.Data, result, 0, result.Length); return result;
            }
            finally
            {
                for (var i = 0; i < input.Size; i++) Marshal.WriteByte(input.Data, i, 0);
                Marshal.FreeHGlobal(input.Data);
                if (output.Data != IntPtr.Zero)
                {
                    for (var i = 0; i < output.Size; i++) Marshal.WriteByte(output.Data, i, 0);
                    LocalFree(output.Data);
                }
            }
        }
    }
}

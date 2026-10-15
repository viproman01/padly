using System.Collections.Concurrent;
using System.Diagnostics;
using System.Net;
using System.Net.WebSockets;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Server.Kestrel.Core;
using Microsoft.Extensions.Logging;

namespace Padly.Windows;

public interface IInputSink
{
    void Move(float dx, float dy);
    void Button(byte button, bool down);
    void Scroll(float dx, float dy);
    void Text(string text);
    void Key(ushort code, byte mods, bool down);
    void ReleaseAll();
}

/// <summary>LAN-only browser assets and the authenticated Padly v2 input receiver.</summary>
public sealed class ReceiverServer
{
    private readonly int wsPort;
    private readonly int httpPort;
    private readonly PairingStore store;
    private readonly Func<IInputSink> inputFactory;
    private readonly object gate = new();
    private readonly SemaphoreSlim sessionSlots = new(16, 16);
    private readonly ConcurrentDictionary<Guid, Session> sessions = new();
    private readonly Dictionary<string, (byte[] Bytes, string Mime)> assets = new(StringComparer.Ordinal);
    private WebApplication? application;
    private string? pin;
    private DateTimeOffset pinExpiresAt;
    private DateTimeOffset lockUntil;
    private int failedPins;
    private int credentialEpoch;

    public ReceiverServer(int wsPort, int httpPort, string dataDirectory, Func<IInputSink> inputFactory, bool ephemeral = false)
    {
        if (wsPort is < 1 or > 65535 || httpPort is < 1 or > 65535 || wsPort == httpPort)
            throw new ArgumentOutOfRangeException(nameof(wsPort));
        this.wsPort = wsPort; this.httpPort = httpPort; this.inputFactory = inputFactory;
        store = new PairingStore(dataDirectory, ephemeral);
    }
    public string ServerId => store.ServerId;
    public string Name => Environment.MachineName;
    public string? CurrentPin { get { lock (gate) return DateTimeOffset.UtcNow < pinExpiresAt && DateTimeOffset.UtcNow >= lockUntil ? pin : null; } }
    public DateTimeOffset PinExpiresAt { get { lock (gate) return pinExpiresAt; } }
    public bool IsPairing => CurrentPin is not null;
    public IReadOnlyList<PairedDevice> PairedDevices => store.Devices;
    public int ConnectedCount => sessions.Values.Count(s => s.IsAuthenticated);
    public event Action? Changed;
    public event Action<string>? Error;

    public void BeginPairing()
    {
        bool locked;
        lock (gate)
        {
            locked = DateTimeOffset.UtcNow < lockUntil;
            if (!locked)
            {
                pin = RandomNumberGenerator.GetInt32(1_000_000).ToString("D6");
                pinExpiresAt = DateTimeOffset.UtcNow.AddSeconds(120);
                if (lockUntil != default) { failedPins = 0; lockUntil = default; }
            }
        }
        if (locked) Report("Слишком много неверных PIN. Подождите минуту перед новым подключением.");
        Notify();
    }
    public void ForgetAll()
    {
        lock (gate)
        {
            // Persist revocation before changing runtime state. Failed writes keep the old store intact.
            store.ForgetAll(); credentialEpoch++; pin = null; pinExpiresAt = default;
        }
        DisconnectAll(); Notify();
    }
    public void DisconnectAll()
    {
        foreach (var session in sessions.Values) session.Stop();
        Notify();
    }

    public async Task StartAsync(CancellationToken cancellationToken = default)
    {
        if (application is not null) throw new InvalidOperationException("Padly уже запущен.");
        LoadAsset("index.html", "text/html; charset=utf-8");
        LoadAsset("app.js", "text/javascript; charset=utf-8");
        LoadAsset("styles.css", "text/css; charset=utf-8");
        var builder = WebApplication.CreateSlimBuilder(new WebApplicationOptions { Args = [], ApplicationName = typeof(ReceiverServer).Assembly.FullName });
        builder.Logging.ClearProviders();
        builder.WebHost.ConfigureKestrel(options =>
        {
            // IPv4 only; the middleware additionally refuses peers outside local address ranges.
            options.Listen(IPAddress.Any, wsPort, listen => listen.Protocols = HttpProtocols.Http1);
            options.Listen(IPAddress.Any, httpPort, listen => listen.Protocols = HttpProtocols.Http1);
            options.AddServerHeader = false;
            options.Limits.MaxConcurrentConnections = 64;
            options.Limits.MaxConcurrentUpgradedConnections = 16;
            options.Limits.MaxRequestBodySize = 0;
            options.Limits.MaxRequestHeadersTotalSize = 16384;
            options.Limits.RequestHeadersTimeout = TimeSpan.FromSeconds(10);
            options.Limits.KeepAliveTimeout = TimeSpan.FromSeconds(15);
        });
        var app = builder.Build();
        app.UseWebSockets(new WebSocketOptions { KeepAliveInterval = TimeSpan.FromSeconds(5) });
        app.Run(HandleRequest);
        try { await app.StartAsync(cancellationToken); application = app; Notify(); }
        catch { await app.DisposeAsync(); throw; }
    }
    public async Task StopAsync(CancellationToken cancellationToken = default)
    {
        var app = application; application = null;
        DisconnectAll();
        if (app is null) return;
        try { await app.StopAsync(cancellationToken); }
        finally { await app.DisposeAsync(); Notify(); }
    }

    private void LoadAsset(string file, string mime)
    {
        if (assets.ContainsKey("/" + file)) return;
        using var source = typeof(ReceiverServer).Assembly.GetManifestResourceStream("Padly.Windows.BrowserResources." + file)
            ?? throw new FileNotFoundException("Не найден встроенный интерфейс Padly: " + file);
        using var buffer = new MemoryStream(); source.CopyTo(buffer);
        assets.Add("/" + file, (buffer.ToArray(), mime));
    }
    private async Task HandleRequest(HttpContext context)
    {
        if (!IsLocalPeer(context.Connection.RemoteIpAddress) || !ValidHost(context))
        { context.Response.StatusCode = 403; return; }
        if (context.Connection.LocalPort == wsPort)
        {
            if (context.Request.Path != "/" || !context.WebSockets.IsWebSocketRequest)
            { context.Response.StatusCode = 404; return; }
            if (!ValidOrigin(context)) { context.Response.StatusCode = 403; return; }
            if (!sessionSlots.Wait(0)) { context.Response.StatusCode = 503; return; }
            Session? session = null;
            try
            {
                using var socket = await context.WebSockets.AcceptWebSocketAsync();
                session = new Session(this, socket, inputFactory(), Volatile.Read(ref credentialEpoch), context.RequestAborted);
                sessions[session.Id] = session;
                await session.Run();
            }
            catch (Exception e) when (e is not OutOfMemoryException and not StackOverflowException)
            { Report("Соединение Padly закрыто: " + e.Message); }
            finally
            {
                if (session is not null) { session.Stop(); sessions.TryRemove(session.Id, out _); }
                sessionSlots.Release(); Notify();
            }
            return;
        }
        context.Response.Headers.CacheControl = "no-store";
        context.Response.Headers.XContentTypeOptions = "nosniff";
        context.Response.Headers["Referrer-Policy"] = "no-referrer";
        context.Response.Headers.ContentSecurityPolicy = "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self' ws:; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";
        if (!HttpMethods.IsGet(context.Request.Method) && !HttpMethods.IsHead(context.Request.Method))
        { context.Response.StatusCode = 405; context.Response.Headers.Allow = "GET, HEAD"; return; }
        if (context.Request.Path == "/config.json")
        {
            context.Response.ContentType = "application/json; charset=utf-8";
            var bytes = JsonSerializer.SerializeToUtf8Bytes(new { wsPort, serverId = ServerId, platform = "windows", name = Name });
            context.Response.ContentLength = bytes.Length;
            if (!HttpMethods.IsHead(context.Request.Method)) await context.Response.Body.WriteAsync(bytes, context.RequestAborted);
            return;
        }
        var path = context.Request.Path == "/" ? "/index.html" : context.Request.Path.Value!;
        if (!assets.TryGetValue(path, out var asset)) { context.Response.StatusCode = 404; return; }
        context.Response.ContentType = asset.Mime; context.Response.ContentLength = asset.Bytes.Length;
        if (!HttpMethods.IsHead(context.Request.Method)) await context.Response.Body.WriteAsync(asset.Bytes, context.RequestAborted);
    }
    private static bool IsLocalPeer(IPAddress? address)
    {
        if (address is null) return false;
        address = address.MapToIPv4(); var b = address.GetAddressBytes();
        return IPAddress.IsLoopback(address) || b[0] == 10 || b[0] == 192 && b[1] == 168 ||
            b[0] == 172 && b[1] is >= 16 and <= 31 || b[0] == 169 && b[1] == 254;
    }
    private static bool ValidHost(HttpContext context)
    {
        var hostname = context.Request.Host.Host;
        if (hostname.Equals("localhost", StringComparison.OrdinalIgnoreCase)) return IPAddress.IsLoopback(context.Connection.LocalIpAddress!);
        if (!IPAddress.TryParse(hostname, out var host)) return false;
        return host.MapToIPv4().Equals(context.Connection.LocalIpAddress?.MapToIPv4());
    }
    private bool ValidOrigin(HttpContext context)
    {
        var origin = context.Request.Headers.Origin.ToString();
        // Native Android clients omit Origin. Browser clients must originate at this receiver's HTTP UI.
        return origin.Length == 0 || Uri.TryCreate(origin, UriKind.Absolute, out var uri) &&
            uri.Scheme == "http" && uri.Port == httpPort &&
            uri.Host.Equals(context.Request.Host.Host, StringComparison.OrdinalIgnoreCase);
    }
    private void Notify() { try { Changed?.Invoke(); } catch { /* UI callbacks cannot terminate a receiver session. */ } }
    private void Report(string message) { try { Error?.Invoke(message); } catch { } }

    private void ClaimControl(Session controller)
    {
        lock (gate)
        {
            if (controller.Epoch != credentialEpoch || controller.IsStopped)
                throw new ProtocolException("Pairing was revoked");
            // Windows has one physical cursor/keyboard. Release the old controller before
            // allowing a new one to hold keys, so one disconnect cannot release another's input.
            foreach (var active in sessions.Values)
                if (!ReferenceEquals(active, controller) && active.IsAuthenticated) active.Stop();
            controller.MarkAuthenticated();
        }
        Notify();
    }

    private (string? Reason, int Left, byte[]? Ltk) VerifyPin(string deviceId, string deviceName, byte[] proof, byte[] sessionKey, int epoch)
    {
        lock (gate)
        {
            var now = DateTimeOffset.UtcNow;
            if (epoch != credentialEpoch || pin is null || now >= pinExpiresAt) return ("expired", 0, null);
            if (now < lockUntil) return ("locked", 0, null);
            var expected = HMACSHA256.HashData(sessionKey, Encoding.UTF8.GetBytes("padly-pin-v2" + pin));
            try
            {
                if (!CryptographicOperations.FixedTimeEquals(expected, proof))
                {
                    failedPins++;
                    if (failedPins >= 5)
                    { lockUntil = now.AddSeconds(60); pin = null; pinExpiresAt = default; return ("locked", 0, null); }
                    return ("bad-pin", 5 - failedPins, null);
                }
            }
            finally { CryptographicOperations.ZeroMemory(expected); }
            var ltk = RandomNumberGenerator.GetBytes(32);
            try { store.Upsert(new StoredDevice(deviceId, deviceName, ltk, now)); }
            catch { CryptographicOperations.ZeroMemory(ltk); throw; }
            pin = null; pinExpiresAt = default; failedPins = 0;
            return (null, 0, ltk);
        }
    }

    private sealed class Session
    {
        private readonly ReceiverServer server;
        private readonly WebSocket socket;
        private readonly IInputSink input;
        private readonly int epoch;
        private readonly CancellationTokenSource lifetime;
        private readonly object inputGate = new();
        private int stopped;
        private int authenticated;
        private string deviceId = "";
        private string deviceName = "";
        private byte[]? key;
        private SecureChannel? channel;
        private bool known;
        private long lastValid = Stopwatch.GetTimestamp();
        internal Guid Id { get; } = Guid.NewGuid();
        internal int Epoch => epoch;
        internal bool IsStopped => Volatile.Read(ref stopped) != 0;
        internal bool IsAuthenticated => Volatile.Read(ref authenticated) != 0 && Volatile.Read(ref stopped) == 0;
        internal void MarkAuthenticated() => Volatile.Write(ref authenticated, 1);
        internal Session(ReceiverServer server, WebSocket socket, IInputSink input, int epoch, CancellationToken requestAborted)
        { this.server = server; this.socket = socket; this.input = input; this.epoch = epoch; lifetime = CancellationTokenSource.CreateLinkedTokenSource(requestAborted); }

        internal async Task Run()
        {
            try
            {
                var hello = Protocol.Decode(await Receive(TimeSpan.FromSeconds(10)));
                if (hello.Type != "helloE") throw new ProtocolException("Expected helloE");
                deviceId = Protocol.String(hello.Fields, "deviceId", 128);
                deviceName = Protocol.String(hello.Fields, "deviceName", 128);
                Protocol.String(hello.Fields, "appVer", 64);
                if (!hello.Fields.TryGetValue("caps", out var rawCaps) || rawCaps is not object?[] caps ||
                    caps.Length > 32 || caps.Any(c => c is not string text || text.Length > 32)) throw new ProtocolException("Invalid capabilities");
                var peer = Protocol.Binary(hello.Fields, "pub", 65);
                using var handshake = new Handshake();
                StoredDevice? paired;
                lock (server.gate) paired = epoch == server.credentialEpoch ? server.store.Find(deviceId) : null;
                known = paired is not null;
                try { key = handshake.Derive(peer, paired?.Ltk); }
                finally { if (paired is not null) CryptographicOperations.ZeroMemory(paired.Ltk); }
                channel = new SecureChannel(key);
                await SendRaw(Protocol.Encode("welcomeE", ("serverId", server.ServerId), ("name", server.Name),
                    ("serverVer", "0.2.0-windows"), ("pub", handshake.PublicKey), ("known", known)));
                if (!known && !server.IsPairing)
                { await Send("pairFail", ("reason", "pairing-not-active"), ("left", 0)); return; }
                await Send("welcome", ("accepted", true), ("requirePin", !known));
                var rejected = 0;
                var pairingDeadline = DateTimeOffset.UtcNow.AddSeconds(125);
                while (!lifetime.IsCancellationRequested)
                {
                    var timeout = IsAuthenticated ? TimeSpan.FromSeconds(15) - Stopwatch.GetElapsedTime(lastValid)
                        : known ? TimeSpan.FromSeconds(15) : pairingDeadline - DateTimeOffset.UtcNow;
                    if (timeout <= TimeSpan.Zero) break;
                    var frame = await Receive(timeout);
                    Protocol.Message message;
                    try { message = Protocol.Decode(channel.Open(frame)); }
                    catch (Exception e) when (e is ProtocolException or CryptographicException or DecoderFallbackException)
                    { if (++rejected >= 10) break; continue; }
                    if (epoch != Volatile.Read(ref server.credentialEpoch)) break;
                    if (known && !IsAuthenticated)
                    {
                        lock (server.gate)
                        {
                            if (epoch != server.credentialEpoch || Volatile.Read(ref stopped) != 0) break;
                            server.store.Touch(deviceId); server.ClaimControl(this);
                        }
                    }
                    try
                    {
                        if (!await Handle(message)) break;
                        lastValid = Stopwatch.GetTimestamp(); rejected = 0;
                    }
                    catch (ProtocolException) { if (++rejected >= 10) break; }
                }
            }
            catch (Exception e) when (e is OperationCanceledException or WebSocketException or ProtocolException or CryptographicException or DecoderFallbackException)
            { /* Invalid/disconnected clients never escape into the host. */ }
            catch (Exception e) when (e is not OutOfMemoryException and not StackOverflowException)
            { server.Report("Сеанс Padly закрыт: " + e.Message); }
            finally
            {
                Stop(); channel?.Dispose();
                if (key is not null) CryptographicOperations.ZeroMemory(key);
                lifetime.Dispose(); server.Notify();
            }
        }
        private async Task<bool> Handle(Protocol.Message message)
        {
            var fields = message.Fields;
            switch (message.Type)
            {
                case "bye": Protocol.String(fields, "reason", 256, true); return false;
                case "ping":
                    await Send("pong", ("ts", Protocol.Unsigned(fields, "ts")), ("serverTs", (ulong)DateTimeOffset.UtcNow.ToUnixTimeMilliseconds())); return true;
                case "pin" when !known && !IsAuthenticated:
                    var proof = Protocol.Binary(fields, "proof", 32);
                    var result = server.VerifyPin(deviceId, deviceName, proof, key!, epoch);
                    if (result.Reason is not null)
                    {
                        await Send("pairFail", ("reason", result.Reason), ("left", result.Left));
                        server.Notify(); return result.Reason == "bad-pin";
                    }
                    try { await Send("pairOk", ("ltk", result.Ltk!)); }
                    finally { CryptographicOperations.ZeroMemory(result.Ltk!); }
                    server.ClaimControl(this); return true;
            }
            // No unauthenticated message reaches an input sink.
            if (!IsAuthenticated) throw new ProtocolException("Authentication required");
            lock (inputGate)
            {
                if (Volatile.Read(ref stopped) != 0 || epoch != Volatile.Read(ref server.credentialEpoch)) return false;
                Protocol.Unsigned(fields, "ts");
                switch (message.Type)
                {
                    case "cursorMove": input.Move(Protocol.Number(fields, "dx"), Protocol.Number(fields, "dy")); break;
                    case "cursorClick": input.Button((byte)Protocol.Unsigned(fields, "btn", 2), Protocol.Boolean(fields, "down")); break;
                    case "scroll":
                        Protocol.Boolean(fields, "momentum"); input.Scroll(Protocol.Number(fields, "dx"), Protocol.Number(fields, "dy")); break;
                    case "text": input.Text(Protocol.String(fields, "s", 4096, true)); break;
                    case "key": input.Key((ushort)Protocol.Unsigned(fields, "code", ushort.MaxValue), (byte)Protocol.Unsigned(fields, "mods", 31), Protocol.Boolean(fields, "down")); break;
                    case "presenter":
                        var kind = Protocol.Unsigned(fields, "kind", 5);
                        if (kind <= 3)
                        { ushort code = kind switch { 0 => 0x4f, 1 => 0x50, 2 => 0x05, _ => 0x1a }; input.Key(code, 0, true); input.Key(code, 0, false); }
                        else if (kind == 4) { Protocol.Number(fields, "x", 1); Protocol.Number(fields, "y", 1); }
                        break;
                    default: throw new ProtocolException("Unsupported input type");
                }
            }
            return true;
        }
        private async Task<byte[]> Receive(TimeSpan timeout)
        {
            using var deadline = CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token);
            deadline.CancelAfter(timeout);
            using var data = new MemoryStream();
            var buffer = new byte[8192];
            while (true)
            {
                var result = await socket.ReceiveAsync(buffer.AsMemory(), deadline.Token);
                if (result.MessageType != WebSocketMessageType.Binary) throw new ProtocolException("Binary messages required");
                if (data.Length + result.Count > Protocol.MaximumFrame) throw new ProtocolException("Frame too large");
                data.Write(buffer, 0, result.Count);
                if (result.EndOfMessage) return data.ToArray();
            }
        }
        private Task Send(string type, params (string Key, object? Value)[] fields) => SendRaw(channel!.Seal(Protocol.Encode(type, fields)));
        private async Task SendRaw(byte[] data)
        {
            using var deadline = CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token);
            deadline.CancelAfter(TimeSpan.FromSeconds(3));
            await socket.SendAsync(data.AsMemory(), WebSocketMessageType.Binary, true, deadline.Token);
        }
        internal void Stop()
        {
            if (Interlocked.Exchange(ref stopped, 1) != 0) return;
            lifetime.Cancel();
            lock (inputGate)
            {
                try { input.ReleaseAll(); } catch (Exception e) { server.Report("Не удалось отпустить ввод Padly: " + e.Message); }
                try { (input as IDisposable)?.Dispose(); } catch (Exception e) { server.Report("Не удалось закрыть устройство ввода Padly: " + e.Message); }
            }
            socket.Abort();
        }
    }
}

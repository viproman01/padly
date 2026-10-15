using System.Text.Json;

namespace Padly.Windows;

internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        try
        {
            if (args.Contains("--headless") || args.Contains("--self-test-input"))
            { Console.InputEncoding = new System.Text.UTF8Encoding(false); Console.OutputEncoding = new System.Text.UTF8Encoding(false); }
            ApplicationConfiguration.Initialize();
            if (args.Contains("--self-test-input"))
            {
                var result = InputSelfTest.Run();
                Console.WriteLine(InputSelfTest.LastResult);
                return result;
            }
            var options = Options.Parse(args);
            if (args.Contains("--headless")) return RunHeadless(options, args.Contains("--diagnostic")).GetAwaiter().GetResult();
            using var instance = new Mutex(true, @"Local\Padly.Windows.Receiver", out var first);
            if (!first) { MessageBox.Show("Padly уже запущен. Откройте его значок в области уведомлений.", "Padly"); return 0; }
            Application.Run(new MainForm(options));
            return 0;
        }
        catch (Exception error)
        {
            if (args.Contains("--headless") || args.Contains("--self-test-input")) Console.Error.WriteLine(error.ToString());
            else MessageBox.Show(error.Message, "Padly — ошибка", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }

    private static async Task<int> RunHeadless(Options options, bool diagnostic)
    {
        using var stop = new CancellationTokenSource();
        Console.CancelKeyPress += (_, e) => { e.Cancel = true; stop.Cancel(); };
        var server = new ReceiverServer(options.WsPort, options.HttpPort, options.DataDirectory,
            () => diagnostic ? new RecordingInputSink() : new WindowsInputSink(), options.Ephemeral);
        server.Error += message => Emit(new { type = "error", message });
        await server.StartAsync(stop.Token);
        server.BeginPairing();
        void Ready() => Emit(new { type = "ready", pin = server.CurrentPin, serverId = server.ServerId, wsPort = options.WsPort, httpPort = options.HttpPort });
        Ready();
        try
        {
            while (!stop.IsCancellationRequested)
            {
                var line = await Console.In.ReadLineAsync(stop.Token);
                if (line is null) break;
                try
                {
                    using var message = JsonDocument.Parse(line);
                    switch (message.RootElement.GetProperty("command").GetString())
                    {
                        case "new-pin": server.BeginPairing(); Ready(); break;
                        case "forget": server.ForgetAll(); break;
                        case "disconnect": server.DisconnectAll(); break;
                        case "stop": stop.Cancel(); break;
                        default: Emit(new { type = "error", message = "Unknown local command." }); break;
                    }
                }
                catch (Exception error) when (error is JsonException or KeyNotFoundException or InvalidOperationException)
                { Emit(new { type = "error", message = error.Message }); }
            }
        }
        catch (OperationCanceledException) when (stop.IsCancellationRequested) { }
        finally { await server.StopAsync(); }
        return 0;
    }

    internal static void Emit(object value)
    {
        lock (typeof(Program)) { Console.WriteLine(JsonSerializer.Serialize(value)); Console.Out.Flush(); }
    }
}

internal sealed record Options(int WsPort, int HttpPort, string DataDirectory, bool Ephemeral)
{
    public static Options Parse(string[] args)
    {
        string? Value(string key)
        {
            var i = Array.IndexOf(args, key);
            if (i < 0) return null;
            if (i + 1 >= args.Length) throw new ArgumentException("Missing value: " + key);
            return args[i + 1];
        }
        return new Options(int.Parse(Value("--ws-port") ?? "47821"), int.Parse(Value("--http-port") ?? "47822"),
            Value("--data-dir") ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Padly"), args.Contains("--ephemeral"));
    }
}

internal sealed class RecordingInputSink : IInputSink
{
    public void Move(float dx, float dy) => Program.Emit(new { type = "input", kind = "move", dx, dy });
    public void Button(byte button, bool down) => Program.Emit(new { type = "input", kind = "button", button, down });
    public void Scroll(float dx, float dy) => Program.Emit(new { type = "input", kind = "scroll", dx, dy });
    public void Text(string text) => Program.Emit(new { type = "input", kind = "text", text });
    public void Key(ushort code, byte mods, bool down) => Program.Emit(new { type = "input", kind = "key", code, mods, down });
    public void ReleaseAll() => Program.Emit(new { type = "input", kind = "release" });
}

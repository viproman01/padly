using System.Net.NetworkInformation;
using System.Net.Sockets;
using QRCoder;

namespace Padly.Windows;

internal sealed class MainForm : Form
{
    private readonly Options options;
    private readonly ReceiverServer server;
    private readonly NotifyIcon tray;
    private readonly ComboBox addresses = new() { DropDownStyle = ComboBoxStyle.DropDownList, Dock = DockStyle.Fill };
    private readonly PictureBox qr = new() { SizeMode = PictureBoxSizeMode.Zoom, Width = 210, Height = 210, BackColor = Color.White };
    private readonly Label pin = new() { AutoSize = true, Font = new Font("Segoe UI", 30, FontStyle.Bold) };
    private readonly Label countdown = new() { AutoSize = true };
    private readonly Label status = new() { AutoSize = true, MaximumSize = new Size(540, 0) };
    private readonly Label devices = new() { AutoSize = true, MaximumSize = new Size(540, 0) };
    private readonly Button renew = new() { Text = "Новый PIN", AutoSize = true };
    private readonly Button retry = new() { Text = "Повторить запуск", AutoSize = true, Visible = false };
    private readonly System.Windows.Forms.Timer timer = new() { Interval = 1000 };
    private bool running;
    private bool exiting;
    private string? lastError;
    private readonly CancellationTokenSource lifetime = new();
    private readonly SemaphoreSlim lifecycle = new(1, 1);

    public MainForm(Options options)
    {
        this.options = options;
        server = new ReceiverServer(options.WsPort, options.HttpPort, options.DataDirectory, () => new WindowsInputSink(), options.Ephemeral);
        Text = "Padly — тачпад для Windows";
        ClientSize = new Size(600, 700);
        MinimumSize = new Size(570, 690);
        StartPosition = FormStartPosition.CenterScreen;
        Font = new Font("Segoe UI", 10);
        AutoScaleMode = AutoScaleMode.Dpi;
        Icon = SystemIcons.Application;
        var layout = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, WrapContents = false, AutoScroll = true, Padding = new Padding(24) };
        layout.Controls.Add(new Label { Text = "Смартфон → тачпад", Font = new Font(Font.FontFamily, 19, FontStyle.Bold), AutoSize = true });
        layout.Controls.Add(new Label { Text = "1. Подключите телефон и ПК к одной сети Wi-Fi.\n2. Откройте QR-код камерой телефона.\n3. Введите PIN ниже в браузере телефона.", AutoSize = true, Margin = new Padding(0, 12, 0, 12) });
        var row = new TableLayoutPanel { Width = 540, Height = 34, ColumnCount = 2 };
        row.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        row.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        var copy = new Button { Text = "Копировать", AutoSize = true, Dock = DockStyle.Fill };
        copy.Click += (_, _) => { if (addresses.SelectedItem is string url) Clipboard.SetText(url); };
        row.Controls.Add(addresses, 0, 0); row.Controls.Add(copy, 1, 0); layout.Controls.Add(row);
        layout.Controls.Add(qr);
        layout.Controls.Add(pin);
        layout.Controls.Add(countdown);
        var buttons = new FlowLayoutPanel { Width = 540, Height = 40, Margin = new Padding(0, 10, 0, 5) };
        buttons.Controls.Add(renew);
        var disconnect = new Button { Text = "Отключить телефон", AutoSize = true };
        var forget = new Button { Text = "Забыть устройства", AutoSize = true };
        var exit = new Button { Text = "Выход", AutoSize = true };
        exit.Click += async (_, _) => await ExitAsync();
        buttons.Controls.Add(disconnect); buttons.Controls.Add(forget); buttons.Controls.Add(exit); layout.Controls.Add(buttons);
        layout.Controls.Add(status); layout.Controls.Add(devices); layout.Controls.Add(retry);
        layout.Controls.Add(new Label { Text = "Если Windows запросит доступ к сети, разрешите его для частной сети.\nРаботает с обычными окнами. Для окон администратора ввод ограничен Windows.\nЗакрытие окна оставляет Padly в области уведомлений; выход — через его значок.", AutoSize = true, MaximumSize = new Size(540, 0), ForeColor = Color.DimGray, Margin = new Padding(0, 12, 0, 0) });
        Controls.Add(layout);
        var menu = new ContextMenuStrip();
        menu.Items.Add("Открыть Padly", null, (_, _) => ShowWindow());
        menu.Items.Add("Новый PIN", null, (_, _) => { server.BeginPairing(); ShowWindow(); RefreshState(); });
        menu.Items.Add("Выход", null, async (_, _) => await ExitAsync());
        tray = new NotifyIcon { Icon = Icon, Text = "Padly — тачпад", ContextMenuStrip = menu, Visible = true };
        tray.DoubleClick += (_, _) => ShowWindow();
        renew.Click += (_, _) => { lastError = null; server.BeginPairing(); RefreshState(); };
        disconnect.Click += (_, _) => server.DisconnectAll();
        forget.Click += (_, _) => { try { server.ForgetAll(); RefreshState(); } catch (Exception e) { ShowError(e.Message); } };
        retry.Click += async (_, _) => await StartReceiver();
        addresses.SelectedIndexChanged += (_, _) => UpdateQr();
        server.Changed += () => OnUi(RefreshState);
        server.Error += message => OnUi(() => ShowError(message));
        timer.Tick += (_, _) => RefreshState();
        Shown += async (_, _) => { RefreshAddresses(); await StartReceiver(); timer.Start(); };
        FormClosing += (_, e) =>
        {
            if (!exiting && e.CloseReason == CloseReason.UserClosing) { e.Cancel = true; Hide(); }
            else { exiting = true; lifetime.Cancel(); server.DisconnectAll(); }
        };
    }

    private async Task StartReceiver()
    {
        await lifecycle.WaitAsync();
        if (exiting) { lifecycle.Release(); return; }
        retry.Enabled = false;
        try
        {
            await server.StartAsync(lifetime.Token); running = true; lastError = null; retry.Visible = false;
            server.BeginPairing(); RefreshState(); UpdateQr();
        }
        catch (OperationCanceledException) when (exiting) { }
        catch (Exception e) { running = false; ShowError("Не удалось запустить приёмник: " + e.Message); retry.Visible = true; }
        finally { if (!IsDisposed) retry.Enabled = true; lifecycle.Release(); }
    }
    private void RefreshAddresses()
    {
        var urls = NetworkInterface.GetAllNetworkInterfaces().Where(n => n.OperationalStatus == OperationalStatus.Up && n.NetworkInterfaceType != NetworkInterfaceType.Loopback)
            .SelectMany(n => n.GetIPProperties().UnicastAddresses).Select(a => a.Address)
            .Where(a => a.AddressFamily == AddressFamily.InterNetwork)
            .Where(a => { var b = a.GetAddressBytes(); return b[0] == 10 || b[0] == 192 && b[1] == 168 || b[0] == 172 && b[1] is >= 16 and <= 31; })
            .Select(a => $"http://{a}:{options.HttpPort}").Distinct().ToArray();
        addresses.Items.Clear(); addresses.Items.AddRange(urls.Length > 0 ? urls : [$"http://127.0.0.1:{options.HttpPort}"]);
        addresses.SelectedIndex = 0;
        if (urls.Length == 0) ShowError("Адрес Wi-Fi не найден. Подключите ПК к частной сети и перезапустите Padly.");
    }
    private void UpdateQr()
    {
        var old = qr.Image;
        if (addresses.SelectedItem is string url)
        {
            using var data = QRCodeGenerator.GenerateQrCode(url, QRCodeGenerator.ECCLevel.M);
            using var code = new PngByteQRCode(data);
            using var stream = new MemoryStream(code.GetGraphic(7));
            using var bitmap = Image.FromStream(stream);
            qr.Image = new Bitmap(bitmap);
        }
        old?.Dispose();
    }
    private void RefreshState()
    {
        renew.Enabled = running;
        pin.Text = server.CurrentPin ?? "— — —";
        countdown.Text = server.CurrentPin is null ? "Для нового подключения нажмите «Новый PIN»." : $"PIN действует ещё {Math.Max(0, (int)(server.PinExpiresAt - DateTimeOffset.UtcNow).TotalSeconds)} сек.";
        status.ForeColor = lastError is null ? SystemColors.ControlText : Color.Firebrick;
        if (lastError is not null) status.Text = lastError;
        else if (running) status.Text = server.ConnectedCount > 0 ? "Телефон подключён. Можно управлять компьютером." : "Приёмник включён. Ожидание телефона…";
        devices.Text = "Сохранённые устройства: " + (server.PairedDevices.Count == 0 ? "нет" : string.Join(", ", server.PairedDevices.Select(d => d.DeviceName)));
    }
    private void ShowError(string message) { lastError = message; status.Text = message; status.ForeColor = Color.Firebrick; }
    private void OnUi(Action action) { if (!IsDisposed && IsHandleCreated) BeginInvoke(action); }
    private void ShowWindow() { Show(); WindowState = FormWindowState.Normal; Activate(); }
    private async Task ExitAsync()
    {
        if (exiting) return;
        exiting = true; timer.Stop(); lifetime.Cancel();
        await lifecycle.WaitAsync();
        try { await server.StopAsync(); }
        catch (Exception e) { MessageBox.Show(e.Message, "Padly — остановка", MessageBoxButtons.OK, MessageBoxIcon.Error); }
        finally { lifecycle.Release(); tray.Visible = false; Close(); }
    }
    protected override void Dispose(bool disposing)
    {
        if (disposing) { timer.Dispose(); tray.Dispose(); qr.Image?.Dispose(); }
        base.Dispose(disposing);
    }
}

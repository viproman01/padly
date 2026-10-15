using System.Runtime.InteropServices;

namespace Padly.Windows;

/// <summary>
/// Optional interactive smoke test. It sends input only while its own disposable
/// target has the foreground, aborts on focus loss, and restores the cursor.
/// </summary>
public static class InputSelfTest
{
    public static string LastResult { get; private set; } = "Not run.";

    public static Task<int> RunAsync()
    {
        var completion = new TaskCompletionSource<int>(TaskCreationOptions.RunContinuationsAsynchronously);
        var thread = new Thread(() =>
        {
            try { completion.SetResult(Run()); }
            catch (Exception error) { completion.SetException(error); }
        }) { IsBackground = false, Name = "Padly owned-window input self-test" };
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        return completion.Task;
    }

    public static int Run()
    {
        if (Thread.CurrentThread.GetApartmentState() != ApartmentState.STA)
            return RunAsync().GetAwaiter().GetResult();
        var originalCursor = Cursor.Position;
        var result = 1;
        using var sink = new WindowsInputSink();
        using var form = new TestTarget();
        form.Shown += async (_, _) =>
        {
            try
            {
                await Task.Delay(120);
                form.Activate();
                SetForegroundWindow(form.Handle);
                // A newly launched process may not have Windows foreground rights.
                // Wait for a click on this owned window; do not inject anything until
                // it is actually foreground. Once started, focus loss still aborts.
                await WaitForOwnedForeground(form);
                form.Instructions.Text = "Проверка началась. Не переключайте окно — оно закроется автоматически.";
                await Task.Delay(500);
                EnsureOwnedForeground(form);
                Require(IsWindowVisible(form.Handle), "The owned test window is hidden. Launch this interactive test with a normal visible window, not WindowStyle Hidden.");
                foreach (var key in new[] { 0x01, 0x02, 0x04, 0x10, 0x11, 0x12, 0x5b, 0x5c })
                    Require((GetAsyncKeyState(key) & 0x8000) == 0, "Release physical mouse buttons/modifiers before the input test.");

                form.Surface.Focus();
                var origin = form.Surface.PointToScreen(new Point(120, 80));
                EnsurePointerTarget(form.Surface, origin);
                SetOwnedCursor(form, origin);
                await Pause(form);
                Inject(form, () => sink.Move(5, 3));
                await Pause(form);
                Require(Cursor.Position.X > origin.X && Cursor.Position.Y > origin.Y, "Relative mouse movement was not observed.");
                EnsurePointerTarget(form.Surface, Cursor.Position);
                await WaitForMessage(form, () => form.Surface.MoveCount > 0);
                Require(form.Surface.MoveCount > 0, "The owned target did not receive WM_MOUSEMOVE. " + PointerDetails(form.Surface, Cursor.Position));

                form.Surface.Clear();
                foreach (byte button in new byte[] { 0, 1, 2 })
                {
                    Inject(form, () => sink.Button(button, true));
                    Inject(form, () => sink.Button(button, false));
                    await Pause(form);
                }
                Require(form.Surface.LeftDown > 0 && form.Surface.LeftUp > 0 &&
                        form.Surface.RightDown > 0 && form.Surface.RightUp > 0 &&
                        form.Surface.MiddleDown > 0 && form.Surface.MiddleUp > 0,
                    "Left/right/middle click messages were not all observed.");

                // Move outside the preceding click's double-click rectangle.
                SetOwnedCursor(form, form.Surface.PointToScreen(new Point(220, 80)));
                await Pause(form);
                form.Surface.Clear();
                for (var i = 0; i < 2; i++)
                {
                    Inject(form, () => sink.Button(0, true));
                    Inject(form, () => sink.Button(0, false));
                    await Task.Delay(35);
                }
                await Pause(form);
                Require(form.Surface.DoubleClicks > 0, "Windows did not recognize a native double click.");

                SetOwnedCursor(form, form.Surface.PointToScreen(new Point(320, 80)));
                await Pause(form);
                form.Surface.Clear();
                Inject(form, () => sink.Button(0, true));
                Inject(form, () => sink.Move(12, 4));
                await Pause(form);
                Inject(form, () => sink.Button(0, false));
                await Pause(form);
                Require(form.Surface.DragMoves > 0 && form.Surface.LeftUp > 0, "Native drag movement/release was not observed.");

                form.Surface.Focus();
                form.Surface.Clear();
                // Sub-unit deltas must survive accumulation (4 * .125 * 2 = 1).
                for (var i = 0; i < 4; i++) Inject(form, () => sink.Scroll(0.125f, 0.125f));
                await Pause(form);
                Require(form.Surface.WheelY == 1 && form.Surface.WheelX == -1,
                    $"Natural fractional scrolling failed: vertical {form.Surface.WheelY}, horizontal {form.Surface.WheelX}.");
                Inject(form, () => sink.Scroll(float.NaN, 1));
                Inject(form, () => sink.Move(float.PositiveInfinity, 1));
                await Pause(form);
                Require(form.Surface.WheelY == 1 && form.Surface.WheelX == -1, "Invalid input changed scroll state.");

                form.Text.Focus();
                form.Text.Clear();
                Inject(form, () => sink.Text("Padly Привет 😀"));
                await Pause(form);
                Require(form.Text.Text == "Padly Привет 😀", $"Unicode text mismatch: {form.Text.Text}");
                form.Text.Clear();
                Inject(form, () => sink.Text("ab"));
                Tap(form, sink, 80); // Left
                Inject(form, () => sink.Text("X"));
                Tap(form, sink, 42); // Backspace
                Tap(form, sink, 79); // Right
                Tap(form, sink, 40); // Enter
                Inject(form, () => sink.Text("Z"));
                await Pause(form);
                Require(form.Text.Text == "ab\r\nZ", $"Backspace/Enter/arrow handling failed: {form.Text.Text}");
                Tap(form, sink, 41); // Escape must arrive, without closing the test.
                Tap(form, sink, 4); // Physical HID A (text depends on the user's layout).
                Tap(form, sink, 30); // Physical HID 1.
                await Pause(form);
                Require(form.Text.KeyMessages.Contains("down:Escape"), "Escape was not delivered.");
                Require(form.Text.KeyMessages.Contains("down:A") && form.Text.KeyMessages.Contains("up:A") &&
                        form.Text.KeyMessages.Contains("down:D1") && form.Text.KeyMessages.Contains("up:D1"),
                    "HID letter/number mapping did not deliver complete key pairs.");

                form.Text.KeyMessages.Clear();
                Inject(form, () => sink.Key(80, 8, true)); // Shift+Left
                await Pause(form);
                Inject(form, () => sink.Key(80, 0, false));
                await Pause(form);
                var arrowUp = form.Text.KeyMessages.IndexOf("up:Left");
                var shiftUp = form.Text.KeyMessages.FindIndex(value => value is "up:ShiftKey" or "up:LShiftKey");
                Require(arrowUp >= 0 && shiftUp > arrowUp, "Modifiers were released before the ordinary key.");

                form.Text.KeyMessages.Clear();
                SetOwnedCursor(form, form.Text.PointToScreen(new Point(50, 50)));
                Inject(form, () => sink.Key(79, 4, true)); // Hold Ctrl+Right
                Inject(form, () => sink.Button(0, true));
                await Pause(form);
                Inject(form, sink.ReleaseAll);
                await Pause(form);
                Require((GetAsyncKeyState(0x11) & 0x8000) == 0 && (GetAsyncKeyState(0x01) & 0x8000) == 0,
                    "ReleaseAll left Ctrl or the left mouse button held.");
                Require(form.Text.KeyMessages.IndexOf("up:Right") >= 0, "ReleaseAll did not deliver the held key's key-up.");
                var ctrlUp = form.Text.KeyMessages.FindIndex(value => value is "up:ControlKey" or "up:LControlKey");
                Require(ctrlUp > form.Text.KeyMessages.IndexOf("up:Right"), "ReleaseAll released Ctrl before its held key.");
                LastResult = "INPUT_OK: actual Win32 relative move, left/right/middle click, double click, drag, fractional natural vertical/horizontal wheel, Unicode/Cyrillic/emoji, HID letter/number, Backspace/Enter/arrows/Escape, modifier ordering and disconnect release; owned window only.";
                Console.WriteLine(LastResult);
                result = 0;
            }
            catch (Exception error)
            {
                LastResult = "INPUT_FAILED: " + error.Message;
                Console.Error.WriteLine(LastResult);
            }
            finally
            {
                try { sink.ReleaseAll(); } catch (Exception error) { LastResult += " Release: " + error.Message; result = 1; }
                Cursor.Position = originalCursor;
                form.Close();
            }
        };
        Application.Run(form);
        return result;
    }

    private static void Tap(TestTarget form, WindowsInputSink sink, ushort code)
    {
        Inject(form, () => sink.Key(code, 0, true));
        Inject(form, () => sink.Key(code, 0, false));
    }
    private static void Inject(TestTarget form, Action action) { EnsureOwnedForeground(form); action(); }
    private static async Task Pause(TestTarget form) { await Task.Delay(70); EnsureOwnedForeground(form); }
    private static async Task WaitForMessage(TestTarget form, Func<bool> observed)
    {
        var started = System.Diagnostics.Stopwatch.GetTimestamp();
        while (!observed() && System.Diagnostics.Stopwatch.GetElapsedTime(started) < TimeSpan.FromSeconds(1))
        { await Task.Delay(20); EnsureOwnedForeground(form); }
    }
    private static void EnsurePointerTarget(Control target, Point point) =>
        Require(WindowFromPoint(new NativePoint { X = point.X, Y = point.Y }) == target.Handle,
            "The cursor target is covered or hidden. " + PointerDetails(target, point));
    private static string PointerDetails(Control target, Point point) =>
        $"Expected HWND=0x{target.Handle.ToInt64():x}; hit=0x{WindowFromPoint(new NativePoint { X = point.X, Y = point.Y }).ToInt64():x}; " +
        $"visible={IsWindowVisible(target.Handle)}; bounds={target.RectangleToScreen(target.ClientRectangle)}; cursor={point}.";
    private static async Task WaitForOwnedForeground(TestTarget form)
    {
        var started = System.Diagnostics.Stopwatch.GetTimestamp();
        while (System.Diagnostics.Stopwatch.GetElapsedTime(started) < TimeSpan.FromSeconds(15))
        {
            if (GetForegroundWindow() == form.Handle) return;
            if (form.IsDisposed || !form.Visible) throw new InvalidOperationException("Input test window was closed before it received focus.");
            await Task.Delay(100);
        }
        throw new InvalidOperationException("Input test did not start: click its owned window within 15 seconds to give it focus.");
    }
    private static void EnsureOwnedForeground(TestTarget form) =>
        Require(GetForegroundWindow() == form.Handle, "Input test stopped because its owned window lost focus.");
    private static void SetOwnedCursor(TestTarget form, Point point)
    {
        EnsureOwnedForeground(form);
        Require(form.RectangleToScreen(form.ClientRectangle).Contains(point), "Test cursor target is outside the owned window.");
        Cursor.Position = point;
    }
    private static void Require(bool condition, string message) { if (!condition) throw new InvalidOperationException(message); }

    private sealed class TestTarget : Form
    {
        public readonly Label Instructions = new() { Text = "Щёлкните по этому окну, чтобы начать проверку ввода (15 секунд).", AutoSize = true, Location = new(20, 18) };
        public readonly MouseTarget Surface = new() { Location = new(20, 48), Size = new(590, 170), BackColor = Color.FromArgb(215, 240, 200) };
        public new readonly KeyboardTarget Text = new() { Location = new(20, 240), Size = new(590, 130), Multiline = true, AcceptsReturn = true };
        public TestTarget()
        {
            base.Text = "Padly — проверка ввода в собственное окно";
            ClientSize = new(630, 400);
            StartPosition = FormStartPosition.CenterScreen;
            FormBorderStyle = FormBorderStyle.FixedToolWindow;
            TopMost = true;
            Controls.Add(Instructions);
            Controls.Add(Surface);
            Controls.Add(Text);
        }
    }

    private sealed class KeyboardTarget : TextBox
    {
        public readonly List<string> KeyMessages = [];
        protected override void WndProc(ref Message m)
        {
            if (m.Msg is 0x0100 or 0x0101 or 0x0104 or 0x0105)
                KeyMessages.Add((m.Msg is 0x0100 or 0x0104 ? "down:" : "up:") + ((Keys)(int)m.WParam).ToString());
            base.WndProc(ref m);
        }
        protected override bool IsInputKey(Keys keyData) => true;
    }

    private sealed class MouseTarget : Control
    {
        public int MoveCount, DragMoves, LeftDown, LeftUp, RightDown, RightUp, MiddleDown, MiddleUp, DoubleClicks, WheelX, WheelY;
        public MouseTarget() { TabStop = true; SetStyle(ControlStyles.Selectable | ControlStyles.StandardClick | ControlStyles.StandardDoubleClick, true); }
        public void Clear() => MoveCount = DragMoves = LeftDown = LeftUp = RightDown = RightUp = MiddleDown = MiddleUp = DoubleClicks = WheelX = WheelY = 0;
        protected override void WndProc(ref Message m)
        {
            switch (m.Msg)
            {
                case 0x0200: MoveCount++; if (((long)m.WParam & 1) != 0) DragMoves++; break;
                case 0x0201: LeftDown++; break;
                case 0x0202: LeftUp++; break;
                case 0x0203: DoubleClicks++; LeftDown++; break;
                case 0x0204: RightDown++; break;
                case 0x0205: RightUp++; break;
                case 0x0207: MiddleDown++; break;
                case 0x0208: MiddleUp++; break;
                case 0x020a: WheelY += unchecked((short)((long)m.WParam >> 16)); break;
                case 0x020e: WheelX += unchecked((short)((long)m.WParam >> 16)); break;
            }
            base.WndProc(ref m);
        }
    }

    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] [return: MarshalAs(UnmanagedType.Bool)] private static extern bool IsWindowVisible(IntPtr window);
    [DllImport("user32.dll")] private static extern IntPtr WindowFromPoint(NativePoint point);
    [StructLayout(LayoutKind.Sequential)] private struct NativePoint { public int X, Y; }
    [DllImport("user32.dll")] [return: MarshalAs(UnmanagedType.Bool)] private static extern bool SetForegroundWindow(IntPtr window);
    [DllImport("user32.dll")] private static extern short GetAsyncKeyState(int key);
}


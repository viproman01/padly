# Padly for Windows

Windows 10/11 x64 receiver for the phone/tablet browser touchpad. Portable, self-contained .NET 10 app; no separate runtime or administrator account required. This preview is unsigned.

1. Extract the Windows ZIP and run `Padly.Windows.exe` on the PC.
2. Connect the phone and PC to the same trusted Wi-Fi. If Windows asks, allow Padly on the private network. Padly does not change firewall rules or network profiles itself.
3. Scan the QR code with the phone camera, or open the displayed `http://<PC-IP>:47822` URL in its browser. If the PC has multiple network adapters, select the Wi-Fi address in Padly.
4. Enter the six-digit PIN from the PC. It expires after two minutes; use **Новый PIN** to open another pairing window. The PIN is never served over HTTP.
5. Use the touch surface for movement, taps for clicks, two fingers for scrolling/right click, the drag switch for held left button, and the text/keyboard controls for typing.

Closing the window keeps Padly in the notification area. Double-click its icon to reopen; choose **Выход** to stop. **Отключить телефон** closes the session; **Забыть устройства** also revokes stored pairing keys. The receiver allows one controlling device at a time; a newly authenticated device disconnects the previous one. Browser pairing is kept only for its current browser session.

The receiver uses HTTP 47822 for local assets and WebSocket 47821 for P-256/PIN-authenticated AES-GCM input. It only accepts loopback/private IPv4 network peers. The public connection website redirects to the local HTTP page; it does not relay input or accept your PIN. Use a trusted private network. Guest/client-isolated networks cannot connect devices.

The app controls the normal Windows desktop. Windows blocks input into elevated administrator windows and the UAC/lock screen. The Windows key is the browser keyboard's Cmd/Win modifier. Physical key events follow the PC keyboard layout; the text field sends Unicode including Cyrillic and emoji. No Bluetooth receiver or screen streaming is included.

## Build and checks

Install the .NET 10 SDK on Windows, then:

```powershell
pnpm --dir web install --frozen-lockfile
pnpm --dir web build
dotnet build windows/Padly.Windows/Padly.Windows.csproj -c Release
powershell -File windows/build.ps1
$env:PADLY_RECEIVER_EXE = (Resolve-Path windows/build/win-x64/Padly.Windows.exe).Path
node --test windows/tests/interop.mjs windows/tests/persistence.mjs
```

`--self-test-input` opens a disposable owned target and verifies actual native mouse/key/text input; keep physical mouse buttons/modifiers released while it runs. It aborts on focus loss and restores the cursor. Do not run this test on an unattended interactive desktop.

Protocol tests use `--headless --ephemeral --diagnostic`, which records events without controlling the OS, random local ports, and temporary pairing data. The normal app encrypts persistent device keys using DPAPI for the current Windows account. Headless JSON/stdin diagnostic control is local process I/O only; there are no HTTP pairing/admin endpoints.

Automated checks cover .NET/browser interoperability, PIN failures, authentication gates, replay/tamper rejection, revocation and disconnect/idle release. Physical phone Wi-Fi and Windows ARM64 devices require separate testing. The published download targets x64; a developer can build ARM64 with `windows/build.ps1 -Runtime win-arm64`.

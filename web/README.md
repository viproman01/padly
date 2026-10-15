# Padly in a browser

The browser client turns a phone or tablet into a Wi-Fi touchpad for Padly on
**macOS 14+ or Windows**. The phone needs only its browser; the computer must run
the matching Padly receiver. An ordinary webpage cannot inject mouse events into
the desktop by itself. macOS requires Accessibility permission. Windows does not
use the macOS permission flow.

## Use

1. Start the receiver on your computer:
   - **Windows:** run the portable `Padly.Windows.exe`. It includes its .NET
     runtime and displays a local URL, QR code and six-digit PIN in its window.
   - **Mac:** open Padly, then click **Подключить телефон…** in its menu. For a
     source build use `cd mac && swift run Padly`, or run
     `bash mac/scripts/make-app.sh` from the repository root and open
     `mac/build/Padly.app`.
2. **Windows:** use a private network profile for your home Wi-Fi and, if Windows
   Firewall asks, allow Padly on private networks. **Mac:** allow Padly in System
   Settings → Privacy & Security → Accessibility.
3. Connect the phone/tablet and computer to the same Wi-Fi. Open the displayed
   `http://<computer-IP>:47822/` address on the mobile device, or scan the QR with
   its camera. Use the actual address shown by Padly if its port differs. You can
   also enter that local address on the hosted connection page; it navigates to
   the receiver without sending the PIN to the hosted site.
4. Enter the six-digit PIN displayed by the computer. A new PIN is valid for two
   minutes. If the page was opened before the pairing window, open the window
   on the computer and tap **Подключить снова** on the page. On Windows the
   receiver window has a button to create a new PIN.
5. Move one finger to move the cursor; tap for left click. Move two fingers to
   scroll; tap two fingers for right click. Hold **Левый клик** with one finger
   while moving another finger on the pad to drag. The lower panel sends text,
   Backspace, Enter, Esc and arrows.

The page is self-contained: no CDN, fonts, account, cloud relay or internet is
needed at runtime. It targets current Android Chrome and iPhone/iPad Safari
(BigInt, Touch/Pointer Events, WebSocket, `crypto.getRandomValues`). Pairing keys
are kept in sessionStorage for the current tab, so reload/reconnect can resume.
A closed tab or disabled storage may require a new PIN next time. If iOS or the
browser asks for local-network access, allow it. Guest networks with client
isolation will prevent the phone from reaching the computer. Keep Padly running:
on Windows closing its window minimizes it to the notification area; use its tray
menu to exit.

## Build the browser resources

The generated files in `mac/Sources/Padly/BrowserResources` are committed, so
building the Mac app does **not** require Node or internet access. To change the UI:

```sh
cd web
pnpm install --frozen-lockfile
pnpm test
pnpm build
```

This bundles the pinned MessagePack and Noble crypto implementations, copies
the HTML/CSS and third-party notices, and produces `app.js`. SwiftPM copies the
resource directory; `make-app.sh` also copies the resource bundle into the app.
The Windows receiver serves the same self-contained browser UI. Rebuild the
browser resources before publishing either receiver after a UI change.

## Verification

`pnpm test` checks gesture classification and interoperates with an independent
Node implementation of the published Swift/Kotlin protocol (Node ECDH, HKDF,
AES-GCM and HMAC). It covers PIN, retry/lock/expiry/timeout, stored-key resume,
authenticated input, tamper and replay rejection.

```sh
pnpm exec playwright install chromium
pnpm test:browser
```

The real Chromium test uses a non-secure HTTP hostname, with `crypto.subtle`
unavailable, and verifies touch, drag/release/cancel, text, PIN/reconnect and
phone/tablet layout. Set `PADLY_BROWSER_CHANNEL=chrome` or `msedge` to use an
installed browser. On a Mac also run `cd mac && swift test`, build the app, and
verify actual cursor movement and Accessibility on a physical device. Browser
tests use a simulated receiver and never move the host computer's cursor. On
Windows, additionally verify the packaged receiver with a real browser and
Win32 input injection; desktop integration is outside the simulated tests.

## Transport and limits

Browser messages use the same v2 P-256/HKDF/AES-GCM/PIN protocol as the native
receivers. Crypto runs from a locally bundled library because WebCrypto's `subtle`
API requires a secure context and is unavailable at a plain LAN HTTP address.
HTTP delivers the page itself without TLS: use this mode on a trusted local
network; frame encryption does not authenticate downloaded JavaScript against
an attacker who can alter that HTTP page. Do not expose these ports publicly.

The HTTP server publishes only fixed assets and public connection coordinates;
it does not publish the PIN or long-term keys. It limits header size, concurrent
connections and request time, and sends a restrictive CSP. No input is accepted
until the existing PIN pairing succeeds. Mouse buttons are released when a
session closes, and browser sessions with no valid sealed activity are closed
after 15 seconds (checked every three seconds), including silent Wi-Fi loss.
Backgrounded mobile pages can be suspended and may need **Подключить снова**.

This browser UI implements the core touchpad and text controls. Bluetooth,
presenter mode and three/four-finger desktop gestures remain outside it.

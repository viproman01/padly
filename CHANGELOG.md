# Changelog

All notable changes to Padly are tracked here.

## [Unreleased] — browser touchpad

- Phone/tablet browser UI with cursor, tap/click, two-finger scroll/right-click,
  held-button drag, sensitivity, Unicode text and special keys.
- Mac serves offline HTML/CSS/JS on the WebSocket port plus one, shows an HTTP
  QR/address, and uses the existing v2 PIN pairing and sealed input protocol.
- Browser pairing credentials remain in the current tab's sessionStorage;
  reconnect/reload resumes without another PIN while the key is available.
- Bundled LAN-compatible crypto avoids requiring HTTPS or crypto.subtle.
- Mouse buttons are released on session close; browser sessions time out after
  15 seconds of lost heartbeat. PIN replies also have a client timeout.
- Node interop/gesture and real Chromium browser tests; Mac HTTP/resource and
  liveness XCTest coverage (requires macOS to execute).

## [Unreleased] — ready to run on real devices (2026-09-24)

Fixes for everything that would have stopped a first real launch, plus the
core trackpad interactions that were silently broken.

- **Android could not connect at all**: Android 9+ blocks cleartext sockets and
  the app talks `ws://` → added a network security config (frames are
  AES-GCM sealed, so cleartext transport is intentional).
- **Mac server only started after the menubar icon was clicked** → now listens
  from launch, so a paired phone reconnects on its own.
- **No Accessibility prompt** (events silently dropped) → prompts at launch,
  menu shows a warning + button to the settings pane until granted.
- **PIN hidden in a Settings tab** → PIN, countdown, QR and the Mac's IP:port
  are shown right in the menubar dropdown.
- **Gestures**: new pure-Kotlin classifier (unit-tested) — two-finger tap =
  right click (never fired before), 3/4-finger swipes fire once per gesture
  (threshold was per-event and unreachable), no cursor jump when a finger
  lifts, two-finger gesture locks into scroll *or* pinch.
- **Double-click** works: the Mac sets clickState on synthetic events.
- **Scroll** follows the fingers (natural), fractional deltas accumulate;
  mild pointer acceleration; multi-display cursor bounds.
- **Mission Control / Spaces** shortcuts carry the fn flag real arrow keys
  have, so ⌃↑/⌃←/⌃→ actually trigger.
- **Keyboard**: new `text` message types Unicode directly (Russian, emoji —
  independent of the Mac layout); Backspace/autocorrect via diff; special keys
  row (esc ⇥ ⌫ ⏎ arrows); ⌘-shortcuts work from the Cyrillic layout too.
- Android: re-pair/forget flow, IPv4 preference for mDNS results, reconnect
  backoff, 3 s WebSocket pings, unused camera/ML Kit deps and CAMERA
  permission removed.
- `mac/scripts/make-app.sh` builds Padly.app (used by CI too).
- Verified: Android unit tests 27/27; **real Kotlin client ↔ Swift server
  interop test** (pair, wrong-PIN reject, resume with ltk) + Swift smoke test.

## [Unreleased] — protocol v2, verified end-to-end

Both apps now compile clean and the protocol has been verified live
(headless Mac server + independent smoke client: pairing, wrong-PIN
lockout, sealed ping/pong — all pass).

### Protocol v2 — crypto rework
- Replaced the TLS-placeholder design (the old `TLSIdentity` never produced a
  real certificate, so the WiFi path could not work at all) with frame-layer
  crypto: ephemeral P-256 ECDH handshake → HKDF-SHA256 → AES-256-GCM on every
  frame. Plain `ws://` transport; nothing secret travels unencrypted.
- Pairing PIN is now a session-key-bound proof (defeats active MITM);
  5 attempts → 60 s lockout, phone gets explicit `pairFail{reason,left}`.
- Reconnect mixes the stored long-term key into HKDF — mutual auth with zero
  extra round-trips. Fixed: devices are persisted under the phone's real
  deviceId (reconnect used to be impossible — saved under a random UUID).
- The long-term key is delivered inside the encrypted channel (previously the
  design sent it over what was in practice a plaintext socket).

### Mac
- `swift build` green; XCTest suites for crypto (roundtrip, tamper, replay,
  PIN proof) and message interpreter — run in CI (local CLT lacks XCTest).
- mDNS advertisement merged into the main NWListener (was a second listener
  fighting for the same port).
- Presenter keys now tap down+up (previously stuck held).
- `--headless` mode + `PADLY_EPHEMERAL_KEYSTORE=1` (in-memory keystore) for
  CI/smoke runs — a rebuilt binary no longer hangs on Keychain ACL prompts.
- New `padly-smoke` executable: independent protocol implementation used as a
  live interop test (`PADLY_SMOKE_OK`).

### Android
- `assembleDebug` + unit tests green (12/12: ECDH both-sides-derive-same-key,
  GCM roundtrip, tamper/replay rejection, PIN proof, pubkey codec).
- PairingScreen now actually pairs: connect → PIN prompt → sealed proof →
  `pairOk` → persist ltk (the old screen never sent a single byte to the Mac
  and stored a PIN hash as the "key").
- WSClient rewritten as a v2 handshake state machine over plain `ws://`
  (was `wss://` pinned to a fingerprint of a certificate that never existed).
- Fixed presenter laser normalisation (used a nonexistent MotionEvent API).

## [Initial cut]

Initial cut of the monorepo:

- Wire protocol spec in `proto/`: MessagePack frame layout, message schemas, HID code mapping table.
- macOS app in `mac/`: SwiftUI menubar app with NWListener WebSocket server, CGEvent injector, mDNS advertiser, QR pairing flow, settings window. Embedded MessagePack codec + CryptoKit HMAC.
- Android app in `android/`: Kotlin + Jetpack Compose, OkHttp WSClient with cert pinning, BluetoothHidDevice fallback, GestureClassifier for multi-touch, foreground service, EncryptedSharedPreferences for pairing secrets.
- Landing in `landing/`: Next.js 15 + Tailwind + Framer Motion. Hero, features, screenshots, setup guide, FAQ, download buttons, OG image.
- Build and signing docs in `docs/build-and-sign.md`.

Not yet shipped: signed `.dmg` and `.apk` artifacts (require user-provisioned Apple Developer ID + Android keystore). Placeholder download files in `landing/public/downloads/` are stubs.

# Padly — Android trackpad for macOS

**Status:** Design
**Date:** 2026-06-24
**Author:** vip.roman.101@gmail.com

## 1. Goals

Turn an Android phone into a high-fidelity trackpad and keyboard for macOS over WiFi (primary) or Bluetooth (fallback). Match Apple Trackpad ergonomics: multi-touch gestures (3-4 finger swipes for Mission Control / Spaces), pinch-to-zoom, scroll with momentum, full keyboard with modifiers, and a presenter mode for talks.

**Non-goals:**
- iOS phone client
- Windows host (Mac only)
- File transfer, mirroring, screen casting
- Cloud relay (everything must be local network)

## 2. Success criteria

- End-to-end tap latency p50 ≤ 30 ms, p99 ≤ 60 ms on 5 GHz WiFi
- Multi-touch gestures (3-4 finger) trigger native macOS responses (Mission Control, Spaces, Launchpad)
- Pairing flow ≤ 30 seconds for a new device
- Reconnect after sleep/lock ≤ 3 seconds
- Bluetooth HID fallback works without the Mac app installed (degraded feature set: cursor + click + scroll + keyboard only)

## 3. Architecture

```
┌──────────────────────┐         ┌──────────────────────┐
│  Android (Kotlin)    │         │  macOS (Swift)       │
│  ┌────────────────┐  │  WSS    │  ┌─────────────────┐ │
│  │ Touch Surface  │──┼─────────┼─▶│ WS Server       │ │
│  │ + Keyboard UI  │  │  TLS    │  │ (NWListener)    │ │
│  └────────────────┘  │  HMAC   │  └────────┬────────┘ │
│  ┌────────────────┐  │         │           ▼          │
│  │ Gesture Detect │  │         │  ┌─────────────────┐ │
│  └────────────────┘  │         │  │ Event Injector  │ │
│  ┌────────────────┐  │         │  │ (CGEventCreate) │ │
│  │ Encoder        │  │         │  └─────────────────┘ │
│  │ (MsgPack+HMAC) │  │  BT-HID │  ┌─────────────────┐ │
│  └────────────────┘  │ (fbk)   │  │ mDNS Advertiser │ │
│  ┌────────────────┐  │         │  └─────────────────┘ │
│  │ BT-HID Profile │──┼─────────┼─▶ macOS BT stack      │
│  └────────────────┘  │         │  ┌─────────────────┐ │
│                      │         │  │ Menubar UI      │ │
│                      │         │  │ + Settings      │ │
│                      │         │  └─────────────────┘ │
└──────────────────────┘         └──────────────────────┘
```

**Transports:**
- Primary: WebSocket Secure (TLS 1.3) on the local network, MessagePack frames, HMAC-SHA256 authentication
- Fallback: Bluetooth HID (phone acts as a Combo HID device — mouse + keyboard). Mac app not required in this mode. Reduced feature set.

**Monorepo:**
```
Macms/
├── mac/         # Swift Package, menubar app
├── android/     # Gradle, Kotlin/Compose
├── proto/       # MessagePack schema + codegen
├── landing/     # Next.js landing on Vercel
└── docs/
```

## 4. Components

### 4.1 Android (Kotlin + Compose)

| Module | Responsibility |
|---|---|
| `ui.TrackpadSurface` | Full-screen Compose canvas, captures `MotionEvent` up to 10 pointers |
| `ui.KeyboardOverlay` | Hidden `EditText` captures soft-keyboard events; on-screen modifier row (cmd / opt / ctrl / shift) |
| `ui.PresenterMode` | Arrows ←→, virtual laser pointer, timer overlay |
| `gesture.GestureClassifier` | `MotionEvent` → tap, drag, scroll, pinch, swipe-3, swipe-4 |
| `gesture.HapticDriver` | `Vibrator` short pulse on tap/click |
| `transport.WSClient` | OkHttp WebSocket client, TLS pinning, reconnect with exp-backoff |
| `transport.BtHidService` | `BluetoothHidDevice` profile, emulates Combo HID |
| `transport.Encoder` | MessagePack + HMAC serialization |
| `transport.Discovery` | mDNS (`NsdManager`), QR scanner (CameraX + ZXing), manual IP form |
| `auth.Pairing` | Stores pair-tokens in `EncryptedSharedPreferences`, drives PIN flow |
| `settings.SettingsStore` | DataStore: sensitivity, scroll speed, haptic on/off |

### 4.2 macOS (Swift + SwiftUI)

| Module | Responsibility |
|---|---|
| `Server.WSListener` | `Network.framework` NWListener TLS, accept clients |
| `Server.Decoder` | MessagePack → Event, verify HMAC, verify monotonic `seq` |
| `Injector.CursorInjector` | `CGEvent.mouseEvent`, motion, clicks, scroll |
| `Injector.KeyInjector` | `CGEvent.keyboardEvent`, modifiers, composed keys |
| `Injector.GestureSynth` | Synthesizes multi-touch gestures: Mission Control = ctrl+up, Spaces = ctrl+←/→, Launchpad = pinch-in 4-finger emulated, Show Desktop = spread 4-finger |
| `Discovery.MDNSAdvertiser` | `NetService` advertises `_padly._tcp` |
| `Discovery.QRGenerator` | Generates QR with `{ip, port, serverId, certFingerprint, bootstrapToken}` |
| `Auth.Pairing` | 6-digit PIN gen, challenge-response, persists tokens to Keychain |
| `Auth.KeyStore` | TLS cert (self-signed, persisted), HMAC secrets, paired-device DB in Keychain |
| `UI.MenubarApp` | `MenuBarExtra` SwiftUI: status, devices list, "Show QR", "Open Settings" |
| `UI.SettingsWindow` | SwiftUI window: sensitivity, scroll inversion, haptic strength, devices manage |
| `Settings.Store` | `UserDefaults` |

## 5. Wire protocol

### Frame layout

```
┌──────────┬──────────────┬───────────────────┬──────────────┐
│ ver (u8) │ seq (u32 LE) │ msgpack_payload   │ hmac (32B)   │
└──────────┴──────────────┴───────────────────┴──────────────┘
       1B          4B           variable             32B
```

- `ver` = protocol version (start `0x01`)
- `seq` = per-session monotonic counter (replay protection)
- `payload` = MessagePack-encoded `Message`
- `hmac` = HMAC-SHA256(secretKey, ver || seq || payload)

### Message schema

```
Message = OneOf:
  Hello       { deviceId, deviceName, appVer, capabilities[] }
  Welcome     { sessionId, serverVer, accepted, requirePin? }
  Ping        { ts }
  Pong        { ts, serverTs }
  PinAttempt  { pinHash (HMAC(pin, salt)) }
  PairOk      { hmacKey (32B) }

  CursorMove  { dx, dy, ts }                          # delta
  CursorClick { button (Left|Right|Middle), down, ts }
  Scroll      { dx, dy, momentum, ts }

  Gesture     { kind, phase (Begin|Update|End), data, ts }
    # kinds: PinchZoom, Rotate, Swipe3{Up|Down|Left|Right},
    #        Swipe4{Up|Down|Left|Right}, Spread4, Pinch4

  Key         { code (HID Usage Page 0x07), modifiers (bitmask), down, ts }
    # modifier bits: cmd=1, opt=2, ctrl=4, shift=8, fn=16

  PresenterAction { kind (Next|Prev|Black|White|Pointer|Stop),
                    pointerXY?, ts }

  Disconnect  { reason }
```

### Frequencies

| Type | Max send rate |
|---|---|
| CursorMove (drag) | 120 Hz, coalesce |
| Scroll | 120 Hz |
| Gesture Update | 60 Hz |
| Click / Key | event-driven |
| Ping | 1 Hz |

### Encoding choice

MessagePack over JSON: ~2-3× smaller, no string→number parsing, mature libs (`org.msgpack:msgpack-core` Android, `MessagePacker` Swift).

## 6. Pairing & security

### Trust model

- Mac holds: self-signed TLS cert (EC P-256), list of `PairedDevice {id, name, hmacKey, certPin, lastSeen}` in Keychain
- Android holds: TLS cert pin (SHA256 of Mac cert), `hmacKey` (32B), `serverId` in `EncryptedSharedPreferences` protected by BiometricPrompt key

### Discovery paths

**A. mDNS:** Mac advertises `_padly._tcp`, TXT `id=<serverId>, fp=<certFingerprint>, ver=1`. Android `NsdManager` lists devices. User taps → pairing flow.

**B. QR:** Mac menubar → "Show pairing QR". QR carries JSON `{ip, port, serverId, certFingerprint, bootstrapToken}`. `bootstrapToken` is one-shot, valid 60 s. Android scans → pairing flow.

**C. Manual IP:** User enters `ip:port` on Android. Android connects, receives Hello response with `serverId + certFingerprint`. → pairing flow.

### Pairing flow

```
Phone                                    Mac
  │ TLS handshake (verify certPin)        │
  ├──────────────────────────────────────▶│
  │ Hello {deviceId, deviceName}          │
  ├──────────────────────────────────────▶│
  │            (Mac shows 6-digit PIN)    │
  │ ◀── Welcome {requirePin: true}        │
  │ (user types PIN on phone)             │
  │ PinAttempt {hash: HMAC(pin, salt)}    │
  ├──────────────────────────────────────▶│
  │            (Mac verifies hash)        │
  │ ◀── PairOk {hmacKey: 32B}             │
  │ Phone stores hmacKey + serverId       │
```

### Reconnect (after paired)

```
Phone                                    Mac
  │ TLS (verify certPin)                  │
  ├──────────────────────────────────────▶│
  │ Hello {deviceId}                      │
  ├──────────────────────────────────────▶│
  │ Welcome {accepted: true}              │
  │ ◀──────────────────────────────────── │
  │ ↓ frames HMAC-signed with saved key   │
```

### Threats and mitigations

| Threat | Mitigation |
|---|---|
| MITM on WiFi | TLS 1.3 + cert pinning on Android |
| Replay | strictly monotonic `seq`, Mac drops out-of-order |
| Frame forgery | HMAC covers ver + seq + payload |
| PIN brute force | 5 attempts → 60 s lock, new PIN |
| Phone theft | hmacKey encrypted under BiometricPrompt key (fingerprint / OS PIN required) |
| Abandoned pair | Mac evicts devices with `lastSeen > 90 days` |

### Bluetooth HID mode

Standard Bluetooth pairing (OS-level PIN). Phone advertises as Combo HID. The Padly Mac app is not involved; macOS handles the events. Only base features (cursor, click, scroll, keyboard).

## 7. Error handling & edge cases

### Connection

| Case | Behavior |
|---|---|
| WiFi drops mid-session | 3 reconnect attempts with exp-backoff (200 ms / 1 s / 3 s). Then UI banner "Reconnecting…" + auto-switch to BT if paired |
| Mac goes to sleep | TCP RST → Android sees close. Waits for mDNS announce on wake. Auto-resume |
| Phone screen lock | WSClient idles (Doze). Reconnect on unlock |
| TLS handshake fails (cert mismatch) | Android shows "Server changed cert. Re-pair?" — never auto-trust |
| HMAC verify fails on Mac | Drop frame, log. 10 failures in 1 s → disconnect client |
| `seq` decreases | Drop frame (replay protection) |
| Ping timeout (3 s no Pong) | Disconnect + reconnect |

### Pairing edge cases

| Case | Behavior |
|---|---|
| Wrong PIN | 5 attempts → 60 s lock, regenerate PIN |
| QR expired | Mac auto-refreshes |
| User closed Mac app mid-pair | bootstrapToken expires (60 s), Android shows "Mac unavailable" |
| Mac cert rotated | All paired devices see mismatch → require re-pair |

### Input edge cases

| Case | Behavior |
|---|---|
| Touch outside trackpad area | Ignored |
| Fast flick (>3000 px/s) | Momentum scroll |
| 5+ simultaneous fingers | Dropped (unsupported) |
| Scroll + pinch simultaneously | Largest displacement wins |
| Key held + WS disconnect | Mac sends synthetic KeyUp for all held keys |

### macOS permissions

- `Accessibility` (required for `CGEvent` injection). On first launch, deep-link to System Settings → Privacy → Accessibility.
- `Local Network` (mDNS, prompted automatically on macOS 15+).

### Android permissions

- `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE` (BT mode)
- `CAMERA` (QR scan)
- `INTERNET` (WiFi)
- `VIBRATE` (haptic)
- `POST_NOTIFICATIONS` (Foreground Service notification)

### Background

- Android Foreground Service with "Trackpad active" notification while session is live (Doze would otherwise kill the socket)
- Mac app runs as `LSUIElement` (menubar only, no Dock icon)

### Presenter mode

| Case | Behavior |
|---|---|
| Foreground app is Keynote / PowerPoint | Auto-map Next=→ Prev=← (no trackpad gestures) |
| Laser pointer | Mac draws borderless `NSWindow` overlay with red dot, top of every space |
| Exit | Double-tap on screen edge |

## 8. Testing strategy

| Level | What | Tool |
|---|---|---|
| Unit (Android) | Encoder, HMAC, GestureClassifier | JUnit5 + MockK |
| Unit (Mac) | Decoder, Injector, KeyStore | XCTest |
| Integration | Phone↔Mac via loopback | Custom harness, JSON scenario scripts |
| Manual latency | High-speed camera (250 fps), count frames from tap to click | Physical test |
| Latency budget | p50 ≤ 30 ms, p99 ≤ 60 ms on 5 GHz WiFi | benchmark script |

## 9. Landing (Next.js + Vercel)

**Stack:**
- Next.js 15 (App Router)
- TailwindCSS
- Framer Motion (subtle hero animations)
- Deploys on Vercel; custom domain `padly.app` (out of scope of MVP — user provisions later)

**Structure:**
```
landing/
├── app/
│   ├── page.tsx          # main landing
│   ├── download/page.tsx
│   ├── setup/page.tsx
│   ├── faq/page.tsx
│   └── layout.tsx
├── components/
│   ├── Hero.tsx
│   ├── Features.tsx
│   ├── Screenshots.tsx
│   ├── DownloadButtons.tsx
│   ├── SetupGuide.tsx
│   ├── FAQ.tsx
│   └── Footer.tsx
├── public/
│   ├── downloads/
│   │   ├── padly-android.apk  # placeholder until first build
│   │   └── Padly.dmg          # placeholder
│   ├── screenshots/
│   ├── og-image.png
│   └── favicon.svg
└── package.json
```

**Sections:**
- Hero: "Padly — твой Android как трекпад Mac". Subhead about multi-touch gestures, keyboard, presenter, WiFi+BT. Two CTAs: Download for Android / Download for Mac.
- Features (6 bullets): Multi-touch gestures, full keyboard with modifiers, presenter mode, WiFi <30 ms / Bluetooth offline, TLS + PIN security, open source.
- Screenshots of the Android UI in trackpad and presenter modes.
- Setup guide, 3 steps: install Mac app + grant Accessibility, install Android APK, scan QR + enter PIN.
- FAQ (iOS plans, offline use, latency, security, open source, App Store).
- Footer: GitHub, license, contact.

**Asset strategy:** placeholder `.apk` / `.dmg` files until first signed builds exist; landing copy is fully shippable from day one.

## 10. Out of scope (MVP)

- iOS phone client (later)
- Windows host (probably never)
- Cloud relay / remote control over internet
- Voice commands
- Custom gesture mappings (later, after basic gestures are stable)
- Multi-Mac switching from one phone (later)

## 11. Open questions

- Padly project name and domain — confirm before publishing landing (current working choice: `padly.app`).
- Apple Developer ID for code-signing the `.dmg`: required for distribution outside the Mac App Store without Gatekeeper warnings. User must provision the cert.
- Android keystore for signing the release `.apk`: required for non-Play distribution. User must provision the keystore.
- Open-source license: MIT assumed; confirm before publishing.

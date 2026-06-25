# Padly wire protocol

This folder is documentation only — it does not produce a compiled artifact. The Mac (Swift) and Android (Kotlin) sides each reimplement the types described here in their native language. When the two sides disagree, this file is the source of truth and the implementations need to be brought back in sync.

## Frame layout

Every WebSocket binary frame is structured as:

```
┌──────────┬──────────────┬───────────────────┬──────────────┐
│ ver (u8) │ seq (u32 LE) │ msgpack_payload   │ hmac (32B)   │
└──────────┴──────────────┴───────────────────┴──────────────┘
       1B          4B           variable             32B
```

- `ver` — protocol version, current `0x01`. Receivers reject other versions and disconnect.
- `seq` — strictly monotonic per session, big-endian agnostic but documented as little-endian. On the server side a frame with a non-increasing `seq` is dropped (replay protection).
- `msgpack_payload` — MessagePack-encoded `Message` (see [Messages](#messages)).
- `hmac` — `HMAC-SHA256(secretKey, ver || seq_bytes || payload)`. Receivers compute and compare in constant time. On mismatch the frame is dropped silently; 10 mismatches in 1 second triggers disconnect.

The `secretKey` is per-paired-device. It is established during the initial pairing exchange (see [Pairing](#pairing)) and persists across reconnects. The first frame in a session must always be a `Hello`, which is the only frame that uses the bootstrap key (the QR / mDNS one-shot token) instead of the persistent key.

## Messages

Messages are encoded as a MessagePack map with a single key `type` and a value-specific `data` payload. Concrete shape:

```
{ "t": "<TypeTag>", "d": { ...fields... } }
```

Short keys (`t`, `d`) keep frames small.

### Type tags

| Tag | Direction | Purpose |
|---|---|---|
| `hello` | Phone → Mac | Identify device, request session |
| `welcome` | Mac → Phone | Accept session, signal if PIN required |
| `ping` | both | Liveness probe (1 Hz) |
| `pong` | both | Liveness reply with server timestamp |
| `pin` | Phone → Mac | PIN attempt (hashed) |
| `pairOk` | Mac → Phone | Pairing succeeded, deliver `hmacKey` |
| `cursorMove` | Phone → Mac | Relative cursor delta |
| `cursorClick` | Phone → Mac | Mouse button event |
| `scroll` | Phone → Mac | Two-finger scroll |
| `gesture` | Phone → Mac | Multi-finger gesture |
| `key` | Phone → Mac | Keyboard event |
| `presenter` | Phone → Mac | Presenter mode action |
| `bye` | both | Graceful disconnect with reason |

### Field schemas

```
hello       { deviceId: str,    deviceName: str, appVer: str, caps: [str] }
welcome     { sessionId: str,   serverVer: str, accepted: bool, requirePin: bool }
ping        { ts: u64 }
pong        { ts: u64, serverTs: u64 }
pin         { hash: bin(32),    salt: bin(16) }     # HMAC-SHA256(pin, salt)
pairOk      { hmacKey: bin(32) }                    # delivered over TLS only

cursorMove  { dx: f32,  dy: f32,  ts: u64 }
cursorClick { btn: u8 (0=Left,1=Right,2=Middle), down: bool, ts: u64 }
scroll      { dx: f32,  dy: f32,  momentum: bool, ts: u64 }

gesture     { kind: u8,
              phase: u8 (0=Begin,1=Update,2=End),
              data: bin (kind-specific encoded floats),
              ts: u64 }

key         { code: u16 (HID Usage Page 0x07),
              mods: u8 bitmask,
              down: bool,
              ts: u64 }

presenter   { kind: u8, x: f32?, y: f32?, ts: u64 }

bye         { reason: str }
```

### Gesture kinds

| Kind ID | Name | `data` payload |
|---|---|---|
| 0 | PinchZoom | `f32 scale` (1.0 = neutral) |
| 1 | Rotate | `f32 radians` |
| 2 | Swipe3Up | `f32 distancePx` |
| 3 | Swipe3Down | `f32 distancePx` |
| 4 | Swipe3Left | `f32 distancePx` |
| 5 | Swipe3Right | `f32 distancePx` |
| 6 | Swipe4Up | `f32 distancePx` (→ Mission Control) |
| 7 | Swipe4Down | `f32 distancePx` |
| 8 | Swipe4Left | `f32 distancePx` (→ next Space) |
| 9 | Swipe4Right | `f32 distancePx` (→ prev Space) |
| 10 | Spread4 | `f32 amount` (→ Show Desktop) |
| 11 | Pinch4 | `f32 amount` (→ Launchpad) |

### Modifier bitmask

| Bit | Modifier |
|---|---|
| 1 | cmd |
| 2 | opt |
| 4 | ctrl |
| 8 | shift |
| 16 | fn |

### Presenter kinds

| Kind ID | Action |
|---|---|
| 0 | Next slide (sends key right-arrow) |
| 1 | Prev slide (sends key left-arrow) |
| 2 | Black screen (key B in Keynote/PowerPoint) |
| 3 | White screen (key W) |
| 4 | Pointer move (uses `x`,`y` in [0,1] normalised) |
| 5 | Stop pointer / exit presenter |

## Pairing

1. Mac advertises `_padly._tcp` via mDNS or shows a QR with `{ip, port, serverId, certFingerprint, bootstrapToken}`. `bootstrapToken` is one-shot and valid for 60 seconds.
2. Phone opens TLS to `ip:port`. It verifies the cert against `certFingerprint`. Any mismatch aborts pairing and is surfaced to the user; auto-trust is never granted.
3. Phone sends `hello` signed with HMAC derived from `bootstrapToken`.
4. Mac responds `welcome { requirePin: true }`. Mac displays a fresh 6-digit PIN in its menubar.
5. User types the PIN on the phone. Phone sends `pin { hash, salt }` where `hash = HMAC-SHA256(pin, salt)`.
6. Mac recomputes hash and compares constant-time. On success it generates a 32-byte random `hmacKey`, returns `pairOk { hmacKey }` over the TLS-protected channel.
7. Phone persists `{serverId, certFingerprint, hmacKey}` in `EncryptedSharedPreferences` (Android) under a key gated by `BiometricPrompt`.
8. Mac persists `{deviceId, deviceName, hmacKey, certPin, lastSeen}` in macOS Keychain.

Subsequent connections skip steps 3-6: the phone goes straight from TLS handshake to a `hello` signed with the persistent `hmacKey`, and the Mac replies `welcome { accepted: true }`.

## Rate limits and latency budget

| Type | Send rate cap | Latency budget |
|---|---|---|
| `cursorMove` | 120 Hz (coalesce) | p50 ≤ 30 ms tap-to-click |
| `scroll` | 120 Hz | — |
| `gesture` Update | 60 Hz | — |
| `key` / `cursorClick` | event-driven | — |
| `ping` | 1 Hz | timeout 3 s without `pong` → disconnect |

## Bluetooth HID fallback

When the network path is unavailable, the phone falls back to advertising a Combo HID profile (mouse + keyboard) over Bluetooth. This path is OS-level and does not use the Padly wire protocol at all: the macOS Bluetooth stack treats the phone as any other BT mouse/keyboard. Consequently the BT path only supports cursor, click, scroll, and keyboard — multi-touch gestures and presenter mode are unavailable.

# Padly wire protocol — v2

This folder is documentation only — it does not produce a compiled artifact. The Mac (Swift) and Android (Kotlin) sides each reimplement the types described here in their native language. When the two sides disagree, this file is the source of truth and the implementations need to be brought back in sync.

Reference implementations:
- Mac: `mac/Sources/Padly/Util/CryptoLayer.swift`, `mac/Sources/Padly/Server/`
- Android: `android/.../transport/CryptoSession.kt`, `transport/Encoder.kt`
- Independent interop client: `mac/Sources/PadlySmoke/main.swift`

## Transport model

Plain `ws://` WebSocket. There is **no TLS**: confidentiality, integrity, and
authentication live in the frame layer instead — an ECDH handshake derives an
AES-256-GCM session key, and every post-handshake frame is sealed. This avoids
self-signed-certificate juggling entirely while providing the same properties.

## Handshake (plaintext, first two messages)

The first message in each direction is raw MessagePack with **no frame header**:

```
phone → mac : helloE   { deviceId, deviceName, appVer, caps, pub: bin(65) }
mac → phone : welcomeE { serverId, name, serverVer, pub: bin(65), known: bool }
```

`pub` is an ephemeral P-256 public key in uncompressed X9.63 form
(`0x04 || X(32) || Y(32)`). Fresh keypairs are generated per connection —
compromise of one session never affects another (forward secrecy).

Both sides then derive the session key:

```
ecdh       = ECDH(my_ephemeral_priv, peer_ephemeral_pub)
info       = phone_pub || mac_pub [ || ltk ]        # fixed order: client first
sessionKey = HKDF-SHA256(ikm: ecdh, salt, info, 32 bytes)
```

- **Pairing** (Mac does not know `deviceId`): `salt = "padly-pair-v2"`, no ltk.
- **Resume** (Mac knows `deviceId`): `salt = "padly-resume-v2"`, `info` gets the
  stored 32-byte `ltk` appended. Only a phone holding the same ltk derives the
  same key — decrypting any frame proves identity in both directions (mutual
  auth), no extra round-trips.

`welcomeE.known` tells the phone which derivation to use.

## Sealed frame layout (everything after the handshake)

```
┌──────────┬──────────────┬─────────────┬──────────────────────┐
│ ver=0x02 │ seq (u32 LE) │ nonce (12B) │ AES-GCM ct + tag(16) │
└──────────┴──────────────┴─────────────┴──────────────────────┘
```

- AAD = `ver || seq_bytes` — a frame cannot be replayed under a different seq.
- `seq` strictly increases per direction; receivers drop non-increasing seq
  (replay protection). 10 rejected frames → disconnect.
- Nonce is deterministic: `direction(1B) || zero(3B) || seq(4B LE) || zero(4B)`.
  Direction byte: `0x01` phone→mac, `0x02` mac→phone — the two directions can
  never collide on a nonce under the shared key.

## Messages

Sealed payloads are MessagePack maps:

```
{ "t": "<TypeTag>", "d": { ...fields... } }
```

### Type tags

| Tag | Direction | Sealed | Purpose |
|---|---|---|---|
| `helloE` | Phone → Mac | no | Identity + ephemeral key |
| `welcomeE` | Mac → Phone | no | Identity + ephemeral key + `known` |
| `welcome` | Mac → Phone | yes | `{accepted, requirePin}` — proves channel works |
| `pin` | Phone → Mac | yes | PIN proof |
| `pairOk` | Mac → Phone | yes | Pairing succeeded, delivers `ltk` |
| `pairFail` | Mac → Phone | yes | `{reason, left}` — bad-pin / locked / expired |
| `ping` / `pong` | both | yes | Liveness (1 Hz), pong carries `serverTs` |
| `cursorMove` | Phone → Mac | yes | Relative cursor delta |
| `cursorClick` | Phone → Mac | yes | Mouse button event |
| `scroll` | Phone → Mac | yes | Two-finger scroll |
| `gesture` | Phone → Mac | yes | Multi-finger gesture |
| `key` | Phone → Mac | yes | Keyboard event (HID code — shortcuts, special keys) |
| `text` | Phone → Mac | yes | Literal Unicode text (any language, layout-independent) |
| `presenter` | Phone → Mac | yes | Presenter mode action |
| `bye` | both | yes | Graceful disconnect with reason |

### Field schemas

```
helloE      { deviceId: str, deviceName: str, appVer: str, caps: [str], pub: bin(65) }
welcomeE    { serverId: str, name: str, serverVer: str, pub: bin(65), known: bool }
welcome     { accepted: bool, requirePin: bool }
pin         { proof: bin(32) }        # HMAC-SHA256(sessionKey, "padly-pin-v2" || pin-utf8)
pairOk      { ltk: bin(32) }          # sealed — never travels in cleartext
pairFail    { reason: str, left: u64 }
ping        { ts: u64 }
pong        { ts: u64, serverTs: u64 }

cursorMove  { dx: f32,  dy: f32,  ts: u64 }
cursorClick { btn: u8 (0=Left,1=Right,2=Middle), down: bool, ts: u64 }
                                        # Mac counts consecutive clicks itself
                                        # (double/triple click via clickState)
scroll      { dx: f32,  dy: f32,  momentum: bool, ts: u64 }
                                        # finger movement, screen coords (+y down);
                                        # Mac maps to wheel so content follows
                                        # the fingers (natural scrolling)

gesture     { kind: u8,
              phase: u8 (0=Begin,1=Update,2=End),
              data: bin (kind-specific encoded floats),
              ts: u64 }

key         { code: u16 (HID Usage Page 0x07),
              mods: u8 bitmask,
              down: bool,
              ts: u64 }

text        { s: str, ts: u64 }       # typed on the Mac via Unicode key events

presenter   { kind: u8, x: f32?, y: f32?, ts: u64 }

bye         { reason: str }
```

### Gesture kinds

| Kind ID | Name | `data` payload |
|---|---|---|
| 0 | PinchZoom | `f32 scale step` — sent every ~15% spread change; > 1 → ⌘=, < 1 → ⌘− |
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
| 0 | Next slide (taps right-arrow, down+up) |
| 1 | Prev slide (taps left-arrow) |
| 2 | Black screen (key B in Keynote/PowerPoint) |
| 3 | White screen (key W) |
| 4 | Pointer move (uses `x`,`y` in [0,1] normalised) |
| 5 | Stop pointer / exit presenter |

## Pairing flow

1. Mac advertises `_padly._tcp` via mDNS (TXT: `id`, `ver=2`, `name`) and/or
   shows a QR: `padly://pair?v=2&ip=...&port=...&id=...&name=...`. The QR
   carries **no secrets** — just connect coordinates.
2. The Mac user clicks "Подключить телефон…" in the menubar, which shows a
   6-digit PIN (valid for 120 seconds) plus the Mac's IP:port right in the menu.
3. Phone connects, sends `helloE`; Mac replies `welcomeE {known: false}` and
   both derive the pairing session key. Mac sends sealed
   `welcome {requirePin: true}`.
4. User types the PIN on the phone. Phone sends sealed
   `pin { proof: HMAC-SHA256(sessionKey, "padly-pin-v2" || pin) }`.
   Binding the proof to the session key defeats an active MITM: an attacker
   terminating two separate ECDH channels holds two different session keys and
   cannot transplant the proof between them.
5. Mac verifies constant-time. 5 failures → 60 s lockout (`pairFail{locked}`).
   On success Mac generates a random 32-byte `ltk`, stores
   `{deviceId, deviceName, ltk, lastSeen}` in the Keychain, and sends sealed
   `pairOk {ltk}`.
6. Phone persists `{serverId, name, ip, port, ltk}` in
   `EncryptedSharedPreferences`.

**Resume:** phone sends `helloE` with the same `deviceId`; Mac replies
`welcomeE {known: true}`; both mix the ltk into HKDF. Mac sends sealed
`welcome {accepted: true}`. No PIN, one round-trip.

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

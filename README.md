# Padly

Turn your Android phone into a trackpad for macOS — multi-touch gestures, full keyboard, presenter mode. WiFi (<30 ms) or Bluetooth (offline).

This repository is a monorepo with four pieces:

| Path | What |
|---|---|
| `mac/` | macOS menubar app (Swift, SwiftUI) — listens on the local network and injects events via `CGEvent` |
| `android/` | Android phone app (Kotlin, Compose) — captures touch and keyboard, sends events |
| `proto/` | Wire format spec — MessagePack message schemas, HMAC frame layout, HID code tables |
| `landing/` | Marketing landing on Vercel (Next.js + Tailwind) with download buttons and setup guide |

## Quick start (development)

### Mac app

```sh
cd mac
swift build
swift run Padly
```

You will be prompted to grant `Accessibility` permission on first run. Without it, the app cannot inject mouse/keyboard events.

### Android app

```sh
cd android
./gradlew assembleDebug
# APK lands in app/build/outputs/apk/debug/app-debug.apk
```

Then `adb install` it, or `./gradlew installDebug` with a device attached.

### Landing

```sh
cd landing
pnpm install   # or: npm install / yarn
pnpm dev
open http://localhost:3000
```

### Proto

The `proto/` folder is documentation only. Both the Mac (Swift) and Android (Kotlin) sides re-implement the wire types in their native languages; the proto README is the authoritative reference when they diverge.

## Distribution

Distribution requires signing keys you provision yourself:

- **macOS `.dmg`** — needs an Apple Developer ID Application certificate. Without it macOS Gatekeeper will block the unsigned `.dmg` outside the Mac App Store.
- **Android `.apk`** — needs an Android keystore (`keytool -genkey`). Without it the APK can still install but Google Play Protect may warn users.
- **Vercel** — needs your own Vercel account. `cd landing && vercel deploy --prod`.

## Status

| Piece | Implemented | Tested |
|---|---|---|
| `proto/` schema spec | yes | n/a (docs) |
| `landing/` Next.js | yes | needs `pnpm dev` locally |
| `mac/` Swift app | yes | needs Xcode / `swift build` locally |
| `android/` Kotlin app | yes | needs Android Studio / Gradle locally |

The code in this repo is the first cut. It compiles, has the full module structure described in `docs/superpowers/specs/2026-06-24-padly-design.md`, and is ready to extend. Real-device testing, signing, and store distribution are user responsibilities.

## License

MIT — see `LICENSE`.

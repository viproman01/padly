# Padly

Turn a phone or tablet into a browser touchpad for macOS or Windows over local Wi-Fi. The repository also includes an Android app for the original macOS receiver.

This repository contains:

| Path | What |
|---|---|
| `mac/` | macOS menubar app (Swift, SwiftUI) — listens on the local network and injects events via `CGEvent` |
| `windows/` | Windows 10/11 receiver (.NET 10/WinForms) — QR/PIN pairing, local browser page and native `SendInput` |
| `android/` | Android phone app (Kotlin, Compose) — captures touch and keyboard, sends events |
| `web/` | Phone/tablet browser touchpad — local page served by the receiver, PIN pairing, cursor/click/scroll/drag/text |
| `proto/` | Wire format spec — MessagePack message schemas, HMAC frame layout, HID code tables |
| `landing/` | Marketing landing (Next.js static export + Tailwind) with download buttons and setup guide — live at <https://padly.109-172-47-162.sslip.io> |

## Quick start (development)

### Browser touchpad (phone or tablet)

Run Padly on Windows, or click **Подключить телефон…** in the Mac app's menu.
Scan the QR with the phone camera or open the displayed HTTP address in a browser.
Enter the PIN from the computer. Both devices must be on the same Wi-Fi; macOS
needs Accessibility permission. No phone app is required. Browser resources
are bundled with each receiver. See [windows/README.md](windows/README.md) for Windows setup and [web/README.md](web/README.md) for controls,
build steps and testing limits.

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
- **Landing** — static export served by nginx on a VPS: `bash landing/deploy/vps/deploy.sh` (builds `landing/out/`, rsyncs it, checks the pages). After the first GitHub release, set `releasesReady: true` in `landing/lib/site.ts` and redeploy.

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

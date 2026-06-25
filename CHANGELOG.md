# Changelog

All notable changes to Padly are tracked here.

## [Unreleased]

Initial cut of the monorepo:

- Wire protocol spec in `proto/`: MessagePack frame layout, message schemas, HID code mapping table.
- macOS app in `mac/`: SwiftUI menubar app with NWListener WebSocket server, CGEvent injector, mDNS advertiser, QR pairing flow, settings window. Embedded MessagePack codec + CryptoKit HMAC.
- Android app in `android/`: Kotlin + Jetpack Compose, OkHttp WSClient with cert pinning, BluetoothHidDevice fallback, GestureClassifier for multi-touch, foreground service, EncryptedSharedPreferences for pairing secrets.
- Landing in `landing/`: Next.js 15 + Tailwind + Framer Motion. Hero, features, screenshots, setup guide, FAQ, download buttons, OG image.
- Build and signing docs in `docs/build-and-sign.md`.

Not yet shipped: signed `.dmg` and `.apk` artifacts (require user-provisioned Apple Developer ID + Android keystore). Placeholder download files in `landing/public/downloads/` are stubs.

# Padly — macOS app

SwiftUI menubar app. Listens for paired Android devices on the local network, injects cursor/keyboard/gesture events into macOS.

## Build

```sh
cd mac
swift build
swift run Padly
```

Or open in Xcode:

```sh
open Package.swift
```

## Required permissions

On first launch macOS will prompt for:

- **Accessibility** — required for `CGEvent` posting. System Settings → Privacy & Security → Accessibility → tick Padly.
- **Local Network** — required for mDNS advertise (auto-prompt on macOS 15+).

Without Accessibility the app runs but cursor/keyboard events are silently dropped.

## Structure

```
Sources/Padly/
├── main.swift                 # SwiftUI App entry
├── Models/                    # Message types, errors
├── Util/                      # MessagePack codec, HMAC, logger
├── Auth/                      # KeyStore (Keychain), Pairing flow
├── Server/                    # NWListener, ClientSession, Decoder
├── Injector/                  # CGEvent posters (cursor, key, gesture synth)
├── Discovery/                 # mDNS advertise, QR generation
├── Settings/                  # UserDefaults wrapper
└── UI/                        # MenubarApp, SettingsWindow, PresenterOverlay
```

## Tests

```sh
swift test
```

## Distribution

```sh
swift build -c release
# Build a .app bundle: see Apple docs on bundling SwiftPM executables
# Sign: codesign --deep --force --options runtime --sign "Developer ID Application: <name>" Padly.app
# Notarize: xcrun notarytool submit Padly.dmg --keychain-profile <profile>
```

Distribution outside the Mac App Store requires an Apple Developer ID Application certificate ($99/year).

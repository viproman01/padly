#!/usr/bin/env bash
# Builds the Padly menubar app bundle from the Swift package.
#
#   mac/scripts/make-app.sh [version]        -> mac/build/Padly.app
#   UNIVERSAL=1 mac/scripts/make-app.sh 0.1.0  (arm64 + x86_64; needs full Xcode, CI sets it)
#
# Ad-hoc signed: runs locally as-is; a downloaded copy needs right-click → Open
# once (no Apple Developer ID). Used by .github/workflows/release.yml too, so
# local and release builds are identical.
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="${1:-0.1.0}"
ARCH_ARGS=""
if [[ "${UNIVERSAL:-0}" == "1" ]]; then
  ARCH_ARGS="--arch arm64 --arch x86_64"
fi

# shellcheck disable=SC2086
swift build -c release $ARCH_ARGS
# shellcheck disable=SC2086
BIN_DIR="$(swift build -c release $ARCH_ARGS --show-bin-path)"

APP="build/Padly.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN_DIR/Padly" "$APP/Contents/MacOS/Padly"
chmod +x "$APP/Contents/MacOS/Padly"
# Keep the SwiftPM bundle in the standard app resource directory. The browser
# router resolves this installed location before using Bundle.module for CLI.
cp -R "$BIN_DIR/Padly_Padly.bundle" "$APP/Contents/Resources/Padly_Padly.bundle"

cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key><string>Padly</string>
  <key>CFBundleDisplayName</key><string>Padly</string>
  <key>CFBundleIdentifier</key><string>app.padly.mac</string>
  <key>CFBundleVersion</key><string>${VERSION}</string>
  <key>CFBundleShortVersionString</key><string>${VERSION}</string>
  <key>CFBundleExecutable</key><string>Padly</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>LSUIElement</key><true/>
  <key>NSHighResolutionCapable</key><true/>
  <key>NSLocalNetworkUsageDescription</key><string>Padly принимает подключение телефона-трекпада в вашей локальной сети.</string>
  <key>NSBonjourServices</key>
  <array><string>_padly._tcp</string></array>
</dict>
</plist>
PLIST

codesign --force --deep --sign - "$APP"
echo "$APP"

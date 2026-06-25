# Download placeholders

This folder is where signed builds live in production:

- `padly-android.apk` — signed Android release
- `Padly.dmg` — signed macOS release

Until you have signed builds, drop in **placeholder files** so the download links don't 404. A few options:

1. **Empty placeholder** — `touch padly-android.apk Padly.dmg`. Users will see a 0-byte file.
2. **Stub README inside a zip** — package a README explaining the build is coming.
3. **Redirect** — patch `lib/site.ts` to point `downloads.*` at an external URL (GitHub Releases, for example).

Once you build and sign real artifacts, drop them here and redeploy the landing. The Vercel build picks them up automatically — they live in `/public`.

## Building the real artifacts

### Android

```sh
cd ../../android
# Set up your keystore once
keytool -genkey -v -keystore release.keystore -alias padly \
  -keyalg RSA -keysize 2048 -validity 10000

./gradlew assembleRelease
# APK lands at: app/build/outputs/apk/release/app-release.apk
cp app/build/outputs/apk/release/app-release.apk \
   ../landing/public/downloads/padly-android.apk
```

### macOS

```sh
cd ../../mac
# Requires Apple Developer ID Application certificate in Keychain
xcodebuild -scheme Padly -configuration Release \
  CODE_SIGN_IDENTITY="Developer ID Application: Your Name"

# Then create a .dmg with create-dmg or hdiutil
hdiutil create -volname Padly -srcfolder build/Release/Padly.app \
  -ov -format UDZO ../landing/public/downloads/Padly.dmg
```

Notarize the .dmg with `xcrun notarytool` before distribution.

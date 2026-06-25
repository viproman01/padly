# Build and sign

End-to-end instructions for producing signed Padly artifacts that you can drop into the landing's `public/downloads/`.

## macOS (Padly.dmg)

Prerequisites:

- Xcode 16 or newer
- An Apple Developer account ($99 / year) with a **Developer ID Application** certificate installed in your login Keychain
- An app-specific password for `notarytool`, stored in a Keychain profile

```sh
cd mac
swift build -c release
```

Wrap the executable in a `.app` bundle. The cleanest approach is to open `Package.swift` in Xcode (`open Package.swift`), let Xcode generate the implicit project, then archive: Product → Archive. From the Organizer choose **Distribute App → Developer ID → Upload**, and Xcode will notarize and staple automatically.

If you prefer the CLI:

```sh
# After swift build -c release
mkdir -p Padly.app/Contents/MacOS Padly.app/Contents/Resources
cp .build/release/Padly Padly.app/Contents/MacOS/Padly
# Write Info.plist (set CFBundleIdentifier=app.padly.mac, LSUIElement=true, etc.)
codesign --deep --force --options runtime \
         --sign "Developer ID Application: Your Name (TEAMID)" Padly.app

# Build a DMG
hdiutil create -volname Padly -srcfolder Padly.app \
               -ov -format UDZO Padly.dmg

# Notarize
xcrun notarytool submit Padly.dmg --keychain-profile padly --wait
xcrun stapler staple Padly.dmg

cp Padly.dmg ../landing/public/downloads/
```

The `LSUIElement=true` key in `Info.plist` makes the app menubar-only (no Dock icon). The `NSAccessibilityUsageDescription` key explains why `CGEvent` posting needs Accessibility approval.

## Android (padly-android.apk)

Prerequisites:

- JDK 17
- Android SDK with command-line tools

Generate a release keystore (one time):

```sh
cd android
keytool -genkey -v -keystore release.keystore \
    -alias padly -keyalg RSA -keysize 2048 -validity 10000
```

Create `android/keystore.properties` (gitignored):

```properties
storeFile=release.keystore
storePassword=<password>
keyAlias=padly
keyPassword=<password>
```

Build and sign:

```sh
./gradlew :app:assembleRelease
cp app/build/outputs/apk/release/app-release.apk \
   ../landing/public/downloads/padly-android.apk
```

For Play Store distribution, also run `./gradlew :app:bundleRelease` to produce an AAB and upload through Play Console.

## Landing

Once the signed artifacts are in `landing/public/downloads/`, deploy the landing:

```sh
cd landing
pnpm install
pnpm build
vercel --prod
```

Or push to `main` if Vercel is connected to the GitHub repo — it builds on every push.

## Why this is on you

Apple and Google both require human-controlled signing keys per the policies attached to their developer programs. The keys are not committable, not delegate-able, and not redirectable to a CI runner without auditable provisioning. Padly itself does not — and should not — embed or fetch them. Provision your own keys before distributing.

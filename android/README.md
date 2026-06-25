# Padly — Android app

Kotlin + Jetpack Compose. Captures touch, keyboard, and gestures; sends them to the Padly Mac app over WebSocket (WiFi) or directly via Bluetooth HID.

## Build

```sh
cd android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Open in Android Studio (Hedgehog or newer) for live development.

## Min SDK

`minSdk = 28` (Android 9). `BluetoothHidDevice` is only stable from API 28.

## Signing (release builds)

Create a keystore once:

```sh
keytool -genkey -v -keystore release.keystore -alias padly \
    -keyalg RSA -keysize 2048 -validity 10000
```

Add `keystore.properties` in this folder (gitignored):

```properties
storeFile=release.keystore
storePassword=<password>
keyAlias=padly
keyPassword=<password>
```

Then:

```sh
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

Drop the signed APK into `../landing/public/downloads/padly-android.apk` and redeploy the landing.

## Structure

```
app/src/main/java/app/padly/android/
├── MainActivity.kt
├── PadlyApp.kt
├── PadlyService.kt        # foreground service that owns the WS connection
├── ui/                    # Compose screens
├── gesture/               # touch → gesture classification
├── transport/             # WSClient, BT HID, MessagePack codec, discovery
├── auth/                  # pairing, secret store (EncryptedSharedPreferences)
├── settings/              # DataStore
└── proto/                 # message types + HID code table
```

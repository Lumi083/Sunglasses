# Sunglasses

Sunglasses provides input-transparent screen dimming overlays for Windows and
Android. Both applications are maintained in this monorepo and currently use
version `1.0.0`.

## Projects

- `desktop/`: Electron desktop application. Windows packaging is enabled;
  macOS and Linux packaging is temporarily disabled.
- `android/`: Native Android application using a foreground service and
  non-touchable overlay windows.

Both implementations require confirmation when an enabled overlay is set above
60% opacity. If it is not confirmed within 15 seconds, the opacity rolls back
to a safe value below the threshold.

## Versioning

`VERSION` is the shared semantic version source. Desktop commands verify that
`desktop/package.json` matches it. Android reads it directly and generates its
numeric version code as `major * 10000 + minor * 100 + patch`.

## Desktop

Requires Node.js 18 or newer.

```bash
cd desktop
npm install
npm start
npm run build:win
```

Build output is written to `desktop/release/`.

## Android

Requires JDK 17 and Android SDK 34.

```bash
cd android
gradle --no-daemon assembleDebug
```

The APK is written to `android/app/build/outputs/apk/debug/`.

## Branches

`main` is the combined development branch. The existing `web` and `android`
branches retain their platform-specific history and can be kept as references.

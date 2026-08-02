# Android墨镜

Android墨镜通过全屏纯色 Android 遮罩降低感知亮度。遮罩不可聚焦且不可触摸，
所有输入会继续传递到下方应用。

## Features

- Persistent foreground-service overlay.
- 0-100% opacity control, defaulting to 45%.
- Configurable `#RRGGBB` overlay color, defaulting to black.
- Mandatory confirmation when the enabled overlay is above 60% opacity.
- Automatic rollback after 15 seconds, enforced by both the activity and the
  foreground service.
- Optional status bar, navigation bar, and display-cutout coverage, disabled by
  default. Enabling it opens Android accessibility settings and uses a
  `TYPE_ACCESSIBILITY_OVERLAY`.
- Persistent total enabled-time tracking.
- Single-column Material3 home screen with live overlay, accessibility, and
  notification permission status plus direct links to each system setting.
- Optional hiding from Android's recent-tasks list without stopping the
  foreground overlay.
- Start/stop toggle action in the ongoing notification, serving as the Android
  equivalent of the desktop tray control.
- Launcher and notification artwork based on the shared pixel-glasses icon.

## Build

Requires JDK 17 and Android SDK 34. Open the repository in Android Studio or
run with Gradle 8.7:

```bash
gradle --no-daemon assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`.

GitHub Actions builds every push and pull request targeting `android`. A
successful run publishes the `android-glasses-debug` artifact.

## Usage

1. Install and open Android墨镜.
2. Grant the Android "display over other apps" permission.
3. Enable the overlay from the control screen.
4. Keep the foreground-service notification available as an emergency stop.

The accessibility service does not retrieve window content, observe general
events, or perform interface actions. It exists only to host the non-touchable
system-bar overlay. Android may still protect lock screens, permission surfaces,
and other secure system UI.

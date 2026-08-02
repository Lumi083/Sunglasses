# Sunglasses

Sunglasses uses a pure-black, semi-transparent, input-transparent overlay to
reduce perceived screen brightness without changing the system brightness or
interfering with other applications.

This branch contains the Electron desktop implementation.

Both implementations follow the same minimal model:

1. Cover the available display with a black overlay.
2. Control dimming by changing only the overlay alpha.
3. Keep the overlay non-focusable and non-interactive so all pointer, touch,
   and keyboard input continues to reach the application below it.
4. Provide an out-of-overlay control surface for changing opacity or stopping
   the overlay.

## Desktop Prototype

The `web` branch is the Electron desktop variant. It creates one click-through
overlay per connected display and exposes all controls through the system tray.
The application icon is maintained as `icon.svg`, with `icon.png` used as the
runtime-compatible window and tray icon.

### Run

Requires Node.js 18 or newer.

```bash
npm install
npm start
```

Platform packages can be built with `npm run build:win`,
`npm run build:linux`, or `npm run build:mac`. Outputs are written to
`release/`.

Use the Sunglasses tray menu to select a dimming level, temporarily disable the
overlay, or quit. The selected opacity is stored in Electron's user-data
directory.

Double-click the tray icon or choose **打开设置** to open the single-page
settings window. It includes:

- Global overlay toggle shortcut, defaulting to `Alt+I`.
- Opacity and overlay color controls.
- An optional taskbar-covering fullscreen mode, disabled by default.
- A 15-second confirmation and automatic rollback when the enabled overlay is
  set above 60% opacity.
- An optional force-top loop and its interval.
- Total time for which the overlay has actually been enabled.

The overlay is intentionally absent from the taskbar and never receives mouse
or keyboard focus. On Windows and macOS it uses Electron's `screen-saver`
always-on-top level. Behavior above exclusive fullscreen applications remains
platform-dependent. When the force-top loop is enabled, Sunglasses periodically
reapplies that level and moves each overlay to the top without stealing focus.
Operating-system secure desktops, including Windows lock and UAC screens,
cannot be covered by a normal desktop application.

Every push and pull request to the `web` branch builds Windows, Linux, and macOS
packages with GitHub Actions. Successful jobs publish `sunglasses-windows`,
`sunglasses-linux`, and `sunglasses-macos` workflow artifacts.

## Status

This repository currently contains an initial prototype. Packaging, signing,
startup integration, and production UI can be added after the core overlay
behavior is validated on target devices.

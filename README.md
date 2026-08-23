# Dragon Parallax

Android live-wallpaper shell for a gyroscope-controlled parallax scene.

The APK deliberately contains no user artwork. The settings screen asks Android's
system file picker for read-only access to an image; the selected original is
decoded into memory and is never edited, recompressed, overwritten, uploaded, or
copied into the app.

## Current engine

- Android 8.0+ (`minSdk 26`), optimized for Android 12 / JOYUI.
- Game rotation-vector sensor with rotation-vector fallback.
- Neutral-position calibration, dead zone, low-pass smoothing, movement clamp.
- Hardware Canvas with software fallback.
- Cover-scale plus overscan so tilt does not expose empty borders.
- Runtime sensitivity, strength, and axis inversion controls.
- No network permission and no third-party runtime dependencies.

## Build

```bash
gradle :app:assembleDebug
```

The installable debug build is written to:

`app/build/outputs/apk/debug/app-debug.apk`


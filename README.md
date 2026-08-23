# Parallax

Android live wallpaper for four gyroscope-controlled mythic-beast scenes.

The APK deliberately contains no user artwork. The settings screen asks Android's
system file picker for read-only access to four images (Dragon, Tiger, Turtle and
Snake, Bird). Selected originals are decoded into memory and are never edited,
recompressed, overwritten, uploaded, or copied into the app.

## Current engine

- Android 8.0+ (`minSdk 26`), optimized for Android 12 / JOYUI.
- Four independent image slots with a single decoded scene kept in memory.
- Auto selection: launcher pages when available, random fallback otherwise.
- Explicit page and random modes, plus manual random scene switching.
- Smooth 280 ms crossfade when the active animal changes.
- Game rotation-vector sensor with rotation-vector fallback.
- Neutral-position calibration, dead zone, low-pass smoothing, movement clamp.
- Hardware Canvas with software fallback.
- Cover-scale plus overscan so tilt does not expose empty borders.
- Runtime sensitivity, strength, and axis inversion controls.
- No network permission and no third-party runtime dependencies.

The current renderer applies single-plane gyroscope motion. The engine is ready
for the next stage: replacing each flat scene with protected foreground/subject/
background layers for true 2.5D depth.

## Build

```bash
gradle :app:assembleDebug
```

The installable debug build is written to:

`app/build/outputs/apk/debug/app-debug.apk`

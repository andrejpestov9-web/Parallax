# Parallax

Android live wallpaper for four gyroscope-controlled mythic-beast scenes.

Version 0.4.1 replaces the old Dragon depth warp with a true three-state tilt effect:
woman on the throne at the left extreme, standing woman in mist at the calibrated
center, and the full Azure Dragon at the right extreme. The three locked frames are
crossfaded directly from the rotation-vector sensor without generated intermediate
frames or anatomy warping.

The other beast scenes can still use an image alone or an image plus its matching depth map.
Files selected through Android's system picker are opened read-only and are never
edited, recompressed, overwritten, or uploaded.

## Current engine

- Android 8.0+ (`minSdk 26`), optimized for Android 12 / JOYUI.
- Four independent image/depth slots with a single decoded scene kept in memory.
- Built-in three-frame Dragon transformation works immediately after installation.
- Full-screen in-app sensor preview works without a firmware wallpaper picker.
- Direct Android live-wallpaper installation is attempted before vendor fallbacks.
- Auto selection: launcher pages when available, random fallback otherwise.
- Explicit page and random modes, plus manual random scene switching.
- Smooth 280 ms crossfade when the active animal changes.
- Game rotation-vector sensor with rotation-vector fallback.
- Neutral-position calibration, dead zone, low-pass smoothing, movement clamp.
- Direct gyroscope blend for the Dragon; no depth-mesh distortion on its character art.
- Real-time 32×58 depth mesh remains available for the other scenes.
- Cover-scale plus overscan so tilt does not expose empty borders.
- Runtime sensitivity, strength, and axis inversion controls.
- No network permission and no third-party runtime dependencies.

When a depth map is present, background, subject, and foreground move with
different amplitudes in real time. Scenes without a depth map safely fall back
to single-plane motion.

## Build

```bash
gradle :app:assembleDebug
```

The installable debug build is written to:

`app/build/outputs/apk/debug/app-debug.apk`

The pull-request build repeats host-side blend tests, validates the three
locked transformation frames, compiles the Android project, verifies the APK
signature, and publishes the installable APK artifact.

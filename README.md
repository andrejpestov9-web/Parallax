# Parallax

Android live wallpaper for four gyroscope-controlled mythic-beast scenes.

Version 0.3 includes the locked 4K dragon source and its protected depth map.
The other scenes can use an image alone or an image plus its matching depth map.
Files selected through Android's system picker are opened read-only and are never
edited, recompressed, overwritten, or uploaded.

## Current engine

- Android 8.0+ (`minSdk 26`), optimized for Android 12 / JOYUI.
- Four independent image/depth slots with a single decoded scene kept in memory.
- Built-in 4K dragon scene works immediately after installation.
- Auto selection: launcher pages when available, random fallback otherwise.
- Explicit page and random modes, plus manual random scene switching.
- Smooth 280 ms crossfade when the active animal changes.
- Game rotation-vector sensor with rotation-vector fallback.
- Neutral-position calibration, dead zone, low-pass smoothing, movement clamp.
- Real-time 32×58 depth mesh on Hardware Canvas with software fallback.
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

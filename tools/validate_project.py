#!/usr/bin/env python3
"""Static checks that do not require an Android SDK."""

from __future__ import annotations

import hashlib
import pathlib
import re
import struct
import sys
import xml.etree.ElementTree as ET


ROOT = pathlib.Path(__file__).resolve().parents[1]
DRAGON_SOURCE_SHA256 = "d7ed8651d5eb61d9fb1b6c6d757cee7b16ebcbc8ff5b72795359cd1b48599234"
DRAGON_DEPTH_SHA256 = "898ab5c339c3d3ac4e9de3d8b5c13dbc8801d18e3bc44b3dfc8a31cb478e7116"
HOLOGRAM_SHA256 = {
    "left.webp": "33afd3af8b43bb7d660996c1f85854dd225c5ee975cf55e26285225302c65b78",
    "center.webp": "090e94d8b76898fcfa49d9f42a279d84cb55285c74621fae437da13739fcb931",
    "right.webp": "81977377c4c3737e485a8e199808730a679336dc0a9a94e56369abf522573dbe",
}


def fail(message: str) -> None:
    print(f"FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def png_size(path: pathlib.Path) -> tuple[int, int]:
    header = path.read_bytes()[:24]
    if len(header) != 24 or header[:8] != b"\x89PNG\r\n\x1a\n":
        fail(f"invalid PNG header: {path.relative_to(ROOT)}")
    return struct.unpack(">II", header[16:24])


def main() -> None:
    activity_path = ROOT / "app/src/main/java/com/andrej/parallaxwallpaper/SettingsActivity.java"
    preview_path = ROOT / "app/src/main/java/com/andrej/parallaxwallpaper/HologramPreviewActivity.java"
    diagnostics_path = ROOT / "app/src/main/java/com/andrej/parallaxwallpaper/WallpaperDiagnosticsActivity.java"
    service_path = ROOT / "app/src/main/java/com/andrej/parallaxwallpaper/ParallaxWallpaperService.java"
    policy_path = ROOT / "app/src/main/java/com/andrej/parallaxwallpaper/SceneSelectionPolicy.java"
    required = [
        ROOT / "settings.gradle",
        ROOT / "build.gradle",
        ROOT / "app/build.gradle",
        ROOT / "app/src/main/AndroidManifest.xml",
        ROOT / "app/src/main/res/xml/wallpaper.xml",
        activity_path,
        preview_path,
        diagnostics_path,
        service_path,
        policy_path,
        ROOT / "app/src/main/java/com/andrej/parallaxwallpaper/HologramBlend.java",
        ROOT / "app/src/main/assets/dragon/source.png",
        ROOT / "app/src/main/assets/dragon/depth.png",
        ROOT / "app/src/main/assets/dragon_hologram/left.webp",
        ROOT / "app/src/main/assets/dragon_hologram/center.webp",
        ROOT / "app/src/main/assets/dragon_hologram/right.webp",
    ]
    for path in required:
        if not path.is_file() or path.stat().st_size == 0:
            fail(f"missing or empty: {path.relative_to(ROOT)}")

    for path in ROOT.rglob("*.xml"):
        try:
            ET.parse(path)
        except ET.ParseError as error:
            fail(f"invalid XML in {path.relative_to(ROOT)}: {error}")

    image_suffixes = {".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".avif"}
    bundled_images = [
        path.relative_to(ROOT)
        for path in ROOT.rglob("*")
        if path.is_file() and path.suffix.lower() in image_suffixes
    ]
    expected_images = {
        pathlib.Path("app/src/main/assets/dragon/source.png"),
        pathlib.Path("app/src/main/assets/dragon/depth.png"),
        pathlib.Path("app/src/main/assets/dragon_hologram/left.webp"),
        pathlib.Path("app/src/main/assets/dragon_hologram/center.webp"),
        pathlib.Path("app/src/main/assets/dragon_hologram/right.webp"),
    }
    if set(bundled_images) != expected_images:
        fail(f"unexpected bundled raster assets: {bundled_images}")

    dragon_source = ROOT / "app/src/main/assets/dragon/source.png"
    dragon_depth = ROOT / "app/src/main/assets/dragon/depth.png"
    if png_size(dragon_source) != (2088, 3840):
        fail("built-in dragon source must remain 2088x3840")
    if png_size(dragon_depth) != (2088, 3840):
        fail("built-in dragon depth map must remain 2088x3840")
    if hashlib.sha256(dragon_source.read_bytes()).hexdigest() != DRAGON_SOURCE_SHA256:
        fail("built-in dragon source pixels changed")
    if hashlib.sha256(dragon_depth.read_bytes()).hexdigest() != DRAGON_DEPTH_SHA256:
        fail("built-in dragon depth map changed")

    hologram_root = ROOT / "app/src/main/assets/dragon_hologram"
    for filename, expected_sha in HOLOGRAM_SHA256.items():
        payload = (hologram_root / filename).read_bytes()
        if payload[:4] != b"RIFF" or payload[8:12] != b"WEBP":
            fail(f"invalid WebP hologram frame: {filename}")
        if hashlib.sha256(payload).hexdigest() != expected_sha:
            fail(f"locked hologram frame changed: {filename}")

    manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    if "android.permission.INTERNET" in manifest:
        fail("network permission is forbidden")
    if "android.permission.BIND_WALLPAPER" not in manifest:
        fail("wallpaper service permission is missing")

    service = service_path.read_text(encoding="utf-8")
    activity = activity_path.read_text(encoding="utf-8")
    preview = preview_path.read_text(encoding="utf-8")
    diagnostics = diagnostics_path.read_text(encoding="utf-8")
    for source_name, source in (
            ("service", service),
            ("activity", activity),
            ("preview", preview),
            ("diagnostics", diagnostics),
    ):
        if source.count("{") != source.count("}"):
            fail(f"unbalanced braces in {source_name}")
        if re.search(r"https?://", source):
            fail(f"unexpected network URL in {source_name}")

    policy = policy_path.read_text(encoding="utf-8")
    if "SCENE_COUNT = 4" not in policy:
        fail("selection policy must expose exactly four scenes")
    for mode in ("MODE_AUTO", "MODE_PAGES", "MODE_RANDOM"):
        if mode not in policy:
            fail(f"selection mode is missing: {mode}")
    if "onOffsetsChanged" not in service or "chooseRandomConfigured" not in service:
        fail("page switching or random fallback is missing")
    if "drawBitmapMesh" not in service or "decodeDepthGrid" not in service:
        fail("real-time depth mesh renderer is missing")
    if "BUILTIN_DRAGON_SOURCE" not in service or "BUILTIN_DRAGON_DEPTH" not in service:
        fail("built-in dragon assets are not wired to the engine")
    if "BUILTIN_DRAGON_HOLOGRAM" not in service or "HologramBlend.fillWeights" not in service:
        fail("three-state gyroscope hologram is not wired to the engine")
    if "HologramPreviewActivity" not in manifest:
        fail("in-app hologram preview activity is not registered")
    if "WallpaperDiagnosticsActivity" not in manifest:
        fail("JOYUI diagnostics activity is not registered")
    if "HologramBlend.fillWeights" not in preview:
        fail("in-app preview does not use the locked three-state blend")
    if "ACTION_CHANGE_LIVE_WALLPAPER" not in preview:
        fail("in-app preview cannot launch the direct wallpaper installer")
    if "WallpaperService.SERVICE_INTERFACE" not in diagnostics:
        fail("diagnostics does not verify WallpaperService discovery")
    if "com.google.android.apps.wallpaper" not in activity:
        fail("trusted Google Wallpapers fallback is missing")

    digest = hashlib.sha256()
    for path in sorted(path for path in ROOT.rglob("*") if path.is_file()):
        if ".gradle" in path.parts or "build" in path.parts:
            continue
        digest.update(path.relative_to(ROOT).as_posix().encode("utf-8"))
        digest.update(path.read_bytes())

    print("PASS: XML parses")
    print("PASS: locked 4K dragon and matching depth map are bundled")
    print("PASS: locked throne/standing/dragon hologram frames are bundled")
    print("PASS: no network permission or URL")
    print("PASS: required wallpaper components exist")
    print("PASS: four-scene auto/pages/random selection is present")
    print("PASS: real-time protected depth mesh is present")
    print("PASS: three-state gyroscope transformation is present")
    print("PASS: firmware-independent full-screen preview is present")
    print("PASS: direct live-wallpaper installer fallback is present")
    print("PASS: JOYUI on-device diagnostics is present")
    print(f"PROJECT_SHA256: {digest.hexdigest()}")


if __name__ == "__main__":
    main()

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
    service_path = ROOT / "app/src/main/java/com/andrej/parallaxwallpaper/ParallaxWallpaperService.java"
    policy_path = ROOT / "app/src/main/java/com/andrej/parallaxwallpaper/SceneSelectionPolicy.java"
    required = [
        ROOT / "settings.gradle",
        ROOT / "build.gradle",
        ROOT / "app/build.gradle",
        ROOT / "app/src/main/AndroidManifest.xml",
        ROOT / "app/src/main/res/xml/wallpaper.xml",
        activity_path,
        service_path,
        policy_path,
        ROOT / "app/src/main/assets/dragon/source.png",
        ROOT / "app/src/main/assets/dragon/depth.png",
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

    manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    if "android.permission.INTERNET" in manifest:
        fail("network permission is forbidden")
    if "android.permission.BIND_WALLPAPER" not in manifest:
        fail("wallpaper service permission is missing")

    service = service_path.read_text(encoding="utf-8")
    activity = activity_path.read_text(encoding="utf-8")
    for source_name, source in (("service", service), ("activity", activity)):
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

    digest = hashlib.sha256()
    for path in sorted(path for path in ROOT.rglob("*") if path.is_file()):
        if ".gradle" in path.parts or "build" in path.parts:
            continue
        digest.update(path.relative_to(ROOT).as_posix().encode("utf-8"))
        digest.update(path.read_bytes())

    print("PASS: XML parses")
    print("PASS: locked 4K dragon and matching depth map are bundled")
    print("PASS: no network permission or URL")
    print("PASS: required wallpaper components exist")
    print("PASS: four-scene auto/pages/random selection is present")
    print("PASS: real-time protected depth mesh is present")
    print(f"PROJECT_SHA256: {digest.hexdigest()}")


if __name__ == "__main__":
    main()

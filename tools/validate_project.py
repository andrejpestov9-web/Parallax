#!/usr/bin/env python3
"""Static checks that do not require an Android SDK."""

from __future__ import annotations

import hashlib
import pathlib
import re
import sys
import xml.etree.ElementTree as ET


ROOT = pathlib.Path(__file__).resolve().parents[1]


def fail(message: str) -> None:
    print(f"FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    required = [
        ROOT / "settings.gradle",
        ROOT / "build.gradle",
        ROOT / "app/build.gradle",
        ROOT / "app/src/main/AndroidManifest.xml",
        ROOT / "app/src/main/res/xml/wallpaper.xml",
        ROOT / "app/src/main/java/com/andrej/parallaxwallpaper/SettingsActivity.java",
        ROOT / "app/src/main/java/com/andrej/parallaxwallpaper/ParallaxWallpaperService.java",
        ROOT / "app/src/main/java/com/andrej/parallaxwallpaper/SceneSelectionPolicy.java",
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
    if bundled_images:
        fail(f"artwork must not be bundled yet: {bundled_images}")

    manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    if "android.permission.INTERNET" in manifest:
        fail("network permission is forbidden")
    if "android.permission.BIND_WALLPAPER" not in manifest:
        fail("wallpaper service permission is missing")

    service = required[-2].read_text(encoding="utf-8")
    activity = required[-3].read_text(encoding="utf-8")
    for source_name, source in (("service", service), ("activity", activity)):
        if source.count("{") != source.count("}"):
            fail(f"unbalanced braces in {source_name}")
        if re.search(r"https?://", source):
            fail(f"unexpected network URL in {source_name}")

    policy = required[-1].read_text(encoding="utf-8")
    if "SCENE_COUNT = 4" not in policy:
        fail("selection policy must expose exactly four scenes")
    for mode in ("MODE_AUTO", "MODE_PAGES", "MODE_RANDOM"):
        if mode not in policy:
            fail(f"selection mode is missing: {mode}")
    if "onOffsetsChanged" not in service or "chooseRandomConfigured" not in service:
        fail("page switching or random fallback is missing")

    digest = hashlib.sha256()
    for path in sorted(path for path in ROOT.rglob("*") if path.is_file()):
        if ".gradle" in path.parts or "build" in path.parts:
            continue
        digest.update(path.relative_to(ROOT).as_posix().encode("utf-8"))
        digest.update(path.read_bytes())

    print("PASS: XML parses")
    print("PASS: no image is bundled")
    print("PASS: no network permission or URL")
    print("PASS: required wallpaper components exist")
    print("PASS: four-scene auto/pages/random selection is present")
    print(f"PROJECT_SHA256: {digest.hexdigest()}")


if __name__ == "__main__":
    main()

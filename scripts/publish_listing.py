#!/usr/bin/env python3
"""
Keeps the Google Play store listing in the repository and publishes it from there.

Layout (the fastlane `supply` layout, so fastlane can read the same tree):

    metadata/android/<locale>/title.txt               at most 30 characters
    metadata/android/<locale>/short_description.txt   at most 80 characters
    metadata/android/<locale>/full_description.txt    at most 4000 characters
    metadata/android/<locale>/images/featureGraphic.png         1024 x 500
    metadata/android/<locale>/images/phoneScreenshots/*.png     2 to 8, sorted by file name

Commands:

    validate   offline: lengths, image sizes and counts. Needs no key and no package.
    push       one edit with every difference against the store; nothing at all when nothing differs.
    pull       writes the store's current texts into the tree (images are not downloaded).

What is missing locally is left alone in the store, never deleted: a locale without a directory, a
text without its file, an image type without its file or folder. Images are compared by SHA-256 and
re-uploaded only when they differ, because every committed change goes to review again.
"""

import argparse
import hashlib
import os
import struct
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

DEFAULT_ROOT = Path(__file__).resolve().parent.parent / "metadata" / "android"

# Play's limits, checked here so a bad file fails before an edit is opened: the API answers a 400
# that discards the whole edit, and its message does not always name the field.
TEXTS = {
    # file name -> (API field, maximum length)
    "title.txt": ("title", 30),
    "short_description.txt": ("shortDescription", 80),
    "full_description.txt": ("fullDescription", 4000),
}
FEATURE_GRAPHIC = "featureGraphic"
PHONE_SCREENSHOTS = "phoneScreenshots"
SCREENSHOT_COUNT = (2, 8)
SCREENSHOT_SIDE = (320, 3840)
IMAGE_SUFFIXES = (".png", ".jpg", ".jpeg")


@dataclass
class Locale:
    language: str
    texts: Dict[str, str] = field(default_factory=dict)  # API field -> text, only files that exist
    images: Dict[str, List[Path]] = field(default_factory=dict)  # image type -> files in upload order


def read_text(path: Path) -> str:
    # Trailing newlines are an editor's, not the listing's; inner ones are paragraphs and stay.
    return path.read_text(encoding="utf-8").replace("\r\n", "\n").rstrip("\n")


def image_size(path: Path) -> Tuple[int, int]:
    """Width and height of a PNG or baseline/progressive JPEG, read from its header."""
    data = path.read_bytes()
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return struct.unpack(">II", data[16:24])
    if data[:2] == b"\xff\xd8":
        i = 2
        while i + 9 < len(data):
            if data[i] != 0xFF:
                i += 1
                continue
            marker = data[i + 1]
            if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
                i += 2
                continue
            length = struct.unpack(">H", data[i + 2 : i + 4])[0]
            if 0xC0 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC):
                height, width = struct.unpack(">HH", data[i + 5 : i + 9])
                return width, height
            i += 2 + length
    raise ValueError(f"{path}: not a PNG or JPEG")


def load(root: Path) -> List[Locale]:
    if not root.is_dir():
        raise SystemExit(f"Error: {root} does not exist")
    locales = []
    for directory in sorted(p for p in root.iterdir() if p.is_dir()):
        locale = Locale(directory.name)
        for name, (api_field, _) in TEXTS.items():
            if (directory / name).is_file():
                locale.texts[api_field] = read_text(directory / name)
        images = directory / "images"
        feature = [images / f"{FEATURE_GRAPHIC}{s}" for s in IMAGE_SUFFIXES]
        feature = [p for p in feature if p.is_file()]
        if feature:
            locale.images[FEATURE_GRAPHIC] = feature
        shots = images / PHONE_SCREENSHOTS
        if shots.is_dir():
            locale.images[PHONE_SCREENSHOTS] = sorted(
                p for p in shots.iterdir() if p.suffix.lower() in IMAGE_SUFFIXES
            )
        locales.append(locale)
    return locales


def problems(locales: List[Locale]) -> List[str]:
    found = []
    if not locales:
        found.append("no locale directories")
    for locale in locales:
        where = locale.language
        for name, (api_field, limit) in TEXTS.items():
            text = locale.texts.get(api_field)
            if text is None:
                continue
            if not text.strip():
                found.append(f"{where}/{name}: empty (delete the file to leave the store's text alone)")
            if len(text) > limit:
                found.append(f"{where}/{name}: {len(text)} characters, Play allows {limit}")
        feature = locale.images.get(FEATURE_GRAPHIC, [])
        if len(feature) > 1:
            found.append(f"{where}: more than one {FEATURE_GRAPHIC} file")
        for path in feature:
            size = image_size(path)
            if size != (1024, 500):
                found.append(f"{where}/{path.name}: {size[0]}x{size[1]}, must be 1024x500")
        if PHONE_SCREENSHOTS in locale.images:
            shots = locale.images[PHONE_SCREENSHOTS]
            low, high = SCREENSHOT_COUNT
            if not low <= len(shots) <= high:
                found.append(f"{where}: {len(shots)} phone screenshots, Play takes {low} to {high}")
            for path in shots:
                width, height = image_size(path)
                if not all(SCREENSHOT_SIDE[0] <= side <= SCREENSHOT_SIDE[1] for side in (width, height)):
                    found.append(f"{where}/{path.name}: {width}x{height}, each side must be {SCREENSHOT_SIDE[0]}-{SCREENSHOT_SIDE[1]} px")
                if max(width, height) > 2 * min(width, height):
                    found.append(f"{where}/{path.name}: {width}x{height}, the long side may be at most twice the short one")
    return found


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def mime(path: Path) -> str:
    return "image/png" if path.suffix.lower() == ".png" else "image/jpeg"


def plan_text(locale: Locale, remote: Optional[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    """The listing body to send, or None when the store already says the same."""
    current = remote or {}
    if all(current.get(k) == v for k, v in locale.texts.items()):
        return None
    if remote is None and "title" not in locale.texts:
        raise SystemExit(f"Error: {locale.language} is not in the store yet and has no title.txt")
    body = {k: current[k] for k in ("title", "shortDescription", "fullDescription") if k in current}
    body.update(locale.texts)
    body["language"] = locale.language
    return body


def plan_images(local: List[Path], remote_hashes: List[str]) -> bool:
    """True when the store's images of one type differ from the local ones, order included."""
    return [sha256(p) for p in local] != remote_hashes


def connect(key: Optional[str]) -> Any:
    # Shared with the release promotion: same key, same scope. `python -I` leaves this directory
    # off sys.path, so it is added rather than assumed.
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from promote_release import get_credentials
    from googleapiclient.discovery import build

    return build("androidpublisher", "v3", credentials=get_credentials(key), cache_discovery=False)


def push(service: Any, package: str, locales: List[Locale], dry_run: bool) -> None:
    from googleapiclient.errors import HttpError
    from googleapiclient.http import MediaFileUpload

    edits = service.edits()
    edit_id = edits.insert(body={}, packageName=package).execute()["id"]
    changed = []
    try:
        remote = {
            item["language"]: item
            for item in edits.listings().list(packageName=package, editId=edit_id).execute().get("listings", [])
        }
        for locale in locales:
            body = plan_text(locale, remote.get(locale.language))
            if body is not None:
                fields = sorted(k for k in locale.texts if remote.get(locale.language, {}).get(k) != locale.texts[k])
                print(f"{locale.language}: texts {', '.join(fields)}")
                edits.listings().update(
                    packageName=package, editId=edit_id, language=locale.language, body=body
                ).execute()
                changed.append(locale.language)
            for image_type, files in locale.images.items():
                listed = edits.images().list(
                    packageName=package, editId=edit_id, language=locale.language, imageType=image_type
                ).execute().get("images", [])
                if not plan_images(files, [image.get("sha256", "") for image in listed]):
                    continue
                print(f"{locale.language}: {image_type} ({len(listed)} -> {len(files)} files)")
                edits.images().deleteall(
                    packageName=package, editId=edit_id, language=locale.language, imageType=image_type
                ).execute()
                for path in files:
                    edits.images().upload(
                        packageName=package,
                        editId=edit_id,
                        language=locale.language,
                        imageType=image_type,
                        media_body=MediaFileUpload(str(path), mimetype=mime(path)),
                    ).execute()
                changed.append(f"{locale.language}/{image_type}")

        if not changed:
            print("The store listing already matches the repository; nothing to commit.")
            edits.delete(packageName=package, editId=edit_id).execute()
        elif dry_run:
            # The update and upload calls check each piece; validate is Play's check of the edit as
            # a whole, the one commit would run. It is also where a missing permission shows: the
            # pieces went through for a key without "Manage store presence", validate answered 403.
            edits.validate(packageName=package, editId=edit_id).execute()
            print("[DRY-RUN] Google Play validated the edit; deleting it without committing.")
            edits.delete(packageName=package, editId=edit_id).execute()
        else:
            edits.commit(packageName=package, editId=edit_id).execute()
            print(f"Committed: {', '.join(changed)}. Google Play reviews the listing again.")
    except HttpError as err:
        print(f"Google Play API error: {err}", file=sys.stderr)
        try:
            edits.delete(packageName=package, editId=edit_id).execute()
        except HttpError:
            pass
        sys.exit(1)


def pull(service: Any, package: str, root: Path) -> None:
    edits = service.edits()
    edit_id = edits.insert(body={}, packageName=package).execute()["id"]
    try:
        listings = edits.listings().list(packageName=package, editId=edit_id).execute().get("listings", [])
    finally:
        edits.delete(packageName=package, editId=edit_id).execute()
    for listing in listings:
        directory = root / listing["language"]
        directory.mkdir(parents=True, exist_ok=True)
        for name, (api_field, _) in TEXTS.items():
            if listing.get(api_field):
                (directory / name).write_text(listing[api_field] + "\n", encoding="utf-8")
        print(f"{listing['language']}: wrote {directory}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", choices=["validate", "push", "pull"])
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT, help="metadata/android by default")
    parser.add_argument("--package-name", default=os.environ.get("PACKAGE_NAME", "com.dazix.caelis"))
    parser.add_argument(
        "--key-json",
        default=os.environ.get("PLAY_STORE_JSON_KEY"),
        help="service account JSON or a path to it (default: $PLAY_STORE_JSON_KEY)",
    )
    parser.add_argument("--dry-run", action="store_true", help="push: let Play check the edit, then discard it")
    args = parser.parse_args()

    if args.command == "pull":
        pull(connect(args.key_json), args.package_name, args.root)
        return

    locales = load(args.root)
    found = problems(locales)
    if found:
        print("\n".join(f"Error: {p}" for p in found), file=sys.stderr)
        sys.exit(1)
    print(f"Listing valid: {', '.join(locale.language for locale in locales)}")
    if args.command == "push":
        push(connect(args.key_json), args.package_name, locales, args.dry_run)


if __name__ == "__main__":
    main()

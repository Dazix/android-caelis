#!/usr/bin/env python3
"""Tests for publish_listing.py. Run: python3 -m unittest discover -s scripts -p 'test_publish_listing.py'"""

import struct
import sys
import tempfile
import types
import unittest
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import publish_listing as pl  # noqa: E402

REPO_LISTING = Path(__file__).resolve().parent.parent / "metadata" / "android"


def png(width: int, height: int, seed: int = 0) -> bytes:
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"tEXt", bytes([seed])) + chunk(b"IEND", b"")


def jpeg(width: int, height: int) -> bytes:
    app0 = b"\xff\xe0" + struct.pack(">H", 16) + b"JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00"
    sof0 = b"\xff\xc0" + struct.pack(">HBHHB", 11, 8, height, width, 1) + b"\x01\x11\x00"
    return b"\xff\xd8" + app0 + sof0 + b"\xff\xd9"


class Tree:
    def __init__(self) -> None:
        self._dir = tempfile.TemporaryDirectory()
        self.root = Path(self._dir.name)

    def write(self, relative: str, content) -> Path:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(content, bytes):
            path.write_bytes(content)
        else:
            path.write_text(content, encoding="utf-8")
        return path

    def close(self) -> None:
        self._dir.cleanup()


class ValidateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tree = Tree()

    def tearDown(self) -> None:
        self.tree.close()

    def problems(self):
        return pl.problems(pl.load(self.tree.root))

    def test_the_committed_listing_is_valid(self):
        self.assertTrue(REPO_LISTING.is_dir(), "metadata/android is missing")
        self.assertEqual([], pl.problems(pl.load(REPO_LISTING)))

    def test_a_language_code_play_does_not_take_is_refused_with_its_spelling(self):
        self.tree.write("sk-SK/title.txt", "Caelis")
        self.tree.write("xx-YY/title.txt", "Caelis")
        found = self.problems()
        self.assertEqual(2, len(found), found)
        self.assertIn("sk-SK: not a language code Google Play accepts", found[0])
        self.assertIn("Play spells it sk", found[0])
        self.assertNotIn("spells", found[1])

    def test_codes_with_and_without_a_region_both_pass(self):
        for code in ("sk", "cs-CZ", "pl-PL", "de-DE", "en-US", "es-419"):
            self.tree.write(f"{code}/title.txt", "Caelis")
        self.assertEqual([], self.problems())

    def test_limits_are_play_limits(self):
        self.tree.write("en-US/title.txt", "x" * 31 + "\n")
        self.tree.write("en-US/short_description.txt", "x" * 81)
        self.tree.write("en-US/full_description.txt", "x" * 4001)
        found = self.problems()
        self.assertEqual(3, len(found), found)
        self.assertIn("31 characters, Play allows 30", found[0])

    def test_exact_limits_pass_and_trailing_newline_does_not_count(self):
        self.tree.write("en-US/title.txt", "x" * 30 + "\n\n")
        self.tree.write("en-US/short_description.txt", "x" * 80 + "\r\n")
        self.assertEqual([], self.problems())

    def test_an_empty_file_is_refused_rather_than_blanking_the_store(self):
        self.tree.write("en-US/title.txt", "\n")
        self.assertIn("empty", self.problems()[0])

    def test_feature_graphic_must_be_1024_by_500(self):
        self.tree.write("en-US/images/featureGraphic.png", png(1024, 512))
        self.assertIn("must be 1024x500", self.problems()[0])
        self.tree.write("en-US/images/featureGraphic.png", png(1024, 500))
        self.assertEqual([], self.problems())

    def test_jpeg_size_is_read_from_its_frame_header(self):
        path = self.tree.write("a.jpg", jpeg(1024, 500))
        self.assertEqual((1024, 500), pl.image_size(path))

    def test_screenshot_count_and_shape(self):
        self.tree.write("en-US/images/phoneScreenshots/01.png", png(1080, 1920))
        self.assertIn("1 phone screenshots", self.problems()[0])
        self.tree.write("en-US/images/phoneScreenshots/02.png", png(1080, 2400))
        self.assertIn("at most twice", self.problems()[0])
        self.tree.write("en-US/images/phoneScreenshots/02.png", png(200, 400))
        self.assertIn("each side must be", self.problems()[0])

    def test_screenshots_upload_in_file_name_order(self):
        for name in ("03.png", "01.png", "02.png"):
            self.tree.write(f"en-US/images/phoneScreenshots/{name}", png(1080, 1920))
        locale = pl.load(self.tree.root)[0]
        self.assertEqual(["01.png", "02.png", "03.png"], [p.name for p in locale.images["phoneScreenshots"]])


class PlanTest(unittest.TestCase):
    def test_same_text_sends_nothing(self):
        locale = pl.Locale("en-US", texts={"title": "Caelis"})
        self.assertIsNone(pl.plan_text(locale, {"language": "en-US", "title": "Caelis"}))

    def test_a_missing_file_keeps_the_store_value(self):
        locale = pl.Locale("en-US", texts={"title": "New"})
        remote = {"language": "en-US", "title": "Old", "shortDescription": "s", "fullDescription": "f"}
        body = pl.plan_text(locale, remote)
        self.assertEqual({"language": "en-US", "title": "New", "shortDescription": "s", "fullDescription": "f"}, body)

    def test_a_new_locale_needs_a_title(self):
        with self.assertRaises(SystemExit):
            pl.plan_text(pl.Locale("de-DE", texts={"shortDescription": "s"}), None)

    def test_images_compare_by_hash_and_order(self):
        tree = Tree()
        try:
            a = tree.write("a.png", png(1, 1, 1))
            b = tree.write("b.png", png(1, 1, 2))
            self.assertFalse(pl.plan_images([a, b], [pl.sha256(a), pl.sha256(b)]))
            self.assertTrue(pl.plan_images([a, b], [pl.sha256(b), pl.sha256(a)]))
            self.assertTrue(pl.plan_images([a], [pl.sha256(a), pl.sha256(b)]))
        finally:
            tree.close()


class FakeCall:
    def __init__(self, log, name, result=None):
        self.log, self.name, self.result = log, name, result

    def execute(self):
        self.log.append(self.name)
        return self.result or {}


class FakeEdits:
    """Records the calls push() makes; the store holds `listings` and `images`."""

    def __init__(self, listings, images):
        self.log = []
        self._listings, self._images = listings, images

    def insert(self, **_):
        return FakeCall(self.log, "insert", {"id": "e1"})

    def delete(self, **_):
        return FakeCall(self.log, "delete")

    def commit(self, **_):
        return FakeCall(self.log, "commit")

    def validate(self, **_):
        return FakeCall(self.log, "validate")

    def listings(self):
        edits = self

        class Listings:
            def list(self, **_):
                return FakeCall(edits.log, "listings.list", {"listings": edits._listings})

            def update(self, language, body, **_):
                return FakeCall(edits.log, f"listings.update {language} {sorted(body)}")

        return Listings()

    def images(self):
        edits = self

        class Images:
            def list(self, language, imageType, **_):
                hashes = edits._images.get((language, imageType), [])
                return FakeCall(edits.log, "images.list", {"images": [{"sha256": h} for h in hashes]})

            def deleteall(self, language, imageType, **_):
                return FakeCall(edits.log, f"images.deleteall {language} {imageType}")

            def upload(self, language, imageType, media_body, **_):
                return FakeCall(edits.log, f"images.upload {language} {imageType} {Path(media_body.path).name}")

        return Images()


class PushTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # push() imports these lazily; the real package is not needed to test what it decides.
        errors = types.ModuleType("googleapiclient.errors")
        errors.HttpError = type("HttpError", (Exception,), {})
        http = types.ModuleType("googleapiclient.http")
        http.MediaFileUpload = lambda path, mimetype: types.SimpleNamespace(path=path, mimetype=mimetype)
        cls._saved = {k: sys.modules.get(k) for k in ("googleapiclient", "googleapiclient.errors", "googleapiclient.http")}
        sys.modules.update({"googleapiclient": types.ModuleType("googleapiclient"), "googleapiclient.errors": errors, "googleapiclient.http": http})

    @classmethod
    def tearDownClass(cls):
        for name, module in cls._saved.items():
            if module is None:
                sys.modules.pop(name, None)
            else:
                sys.modules[name] = module

    def setUp(self):
        self.tree = Tree()
        self.tree.write("en-US/title.txt", "Caelis\n")
        self.shot1 = self.tree.write("en-US/images/phoneScreenshots/01.png", png(1080, 1920, 1))
        self.shot2 = self.tree.write("en-US/images/phoneScreenshots/02.png", png(1080, 1920, 2))

    def tearDown(self):
        self.tree.close()

    def run_push(self, listings, images, dry_run=False):
        edits = FakeEdits(listings, images)
        pl.push(types.SimpleNamespace(edits=lambda: edits), "pkg", pl.load(self.tree.root), dry_run)
        return edits.log

    def in_sync(self):
        listings = [{"language": "en-US", "title": "Caelis", "shortDescription": "s"}]
        images = {("en-US", "phoneScreenshots"): [pl.sha256(self.shot1), pl.sha256(self.shot2)]}
        return listings, images

    def test_nothing_differs_nothing_is_committed(self):
        log = self.run_push(*self.in_sync())
        self.assertNotIn("commit", log)
        self.assertEqual("delete", log[-1])
        self.assertFalse([c for c in log if "update" in c or "upload" in c or "deleteall" in c])

    def test_changed_text_updates_only_texts(self):
        listings, images = self.in_sync()
        listings[0]["title"] = "Old"
        log = self.run_push(listings, images)
        self.assertIn("listings.update en-US ['language', 'shortDescription', 'title']", log)
        self.assertFalse([c for c in log if "upload" in c])
        self.assertEqual("commit", log[-1])

    def test_changed_screenshots_are_replaced_in_order(self):
        listings, images = self.in_sync()
        images[("en-US", "phoneScreenshots")] = ["old"]
        log = self.run_push(listings, images)
        changes = [c for c in log if c.startswith("images.deleteall") or c.startswith("images.upload")]
        self.assertEqual(
            [
                "images.deleteall en-US phoneScreenshots",
                "images.upload en-US phoneScreenshots 01.png",
                "images.upload en-US phoneScreenshots 02.png",
            ],
            changes,
        )
        self.assertEqual("commit", log[-1])

    def test_dry_run_never_commits(self):
        listings, images = self.in_sync()
        listings[0]["title"] = "Old"
        log = self.run_push(listings, images, dry_run=True)
        self.assertNotIn("commit", log)
        self.assertEqual(["validate", "delete"], log[-2:])

    def test_a_refused_validate_fails_the_dry_run_and_leaves_no_edit(self):
        # A key without "Manage store presence" got every update and upload through and was refused
        # only by validate; the dry run exists to catch that before main does.
        listings, images = self.in_sync()
        listings[0]["title"] = "Old"
        edits = FakeEdits(listings, images)

        def refused(**_):
            raise sys.modules["googleapiclient.errors"].HttpError()

        edits.validate = refused
        with self.assertRaises(SystemExit):
            pl.push(types.SimpleNamespace(edits=lambda: edits), "pkg", pl.load(self.tree.root), True)
        self.assertNotIn("commit", edits.log)
        self.assertEqual("delete", edits.log[-1])

    def test_an_image_type_without_local_files_is_not_touched(self):
        listings, images = self.in_sync()
        images[("en-US", "featureGraphic")] = ["remote"]
        log = self.run_push(listings, images)
        self.assertFalse([c for c in log if "featureGraphic" in c])


if __name__ == "__main__":
    unittest.main()

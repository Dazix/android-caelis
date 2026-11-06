#!/usr/bin/env python3
"""Tests for release_notes.py. Run: python3 -m unittest discover -s scripts -p 'test_release_notes.py'"""

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import release_notes as rn  # noqa: E402

REPO = Path(__file__).resolve().parent.parent


class Repo:
    """A throwaway git repository with a listing and notes, committed step by step."""

    def __init__(self, locales=("cs-CZ", "en-US")) -> None:
        self._dir = tempfile.TemporaryDirectory()
        self.root = Path(self._dir.name)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "test@example.com")
        self.git("config", "user.name", "Test")
        self.git("config", "commit.gpgsign", "false")
        for locale in locales:
            self.write(f"metadata/android/{locale}/title.txt", "Caelis\n")

    def git(self, *args: str) -> str:
        return subprocess.run(["git", "-C", str(self.root), *args], check=True, capture_output=True, text=True).stdout

    def write(self, relative: str, content: str) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def notes(self, **by_locale) -> None:
        for locale, lines in by_locale.items():
            self.write(f"release-notes/{locale.replace('_', '-')}.txt", "".join(line + "\n" for line in lines))

    def commit(self, tag: str = None) -> str:
        self.git("add", "-A")
        self.git("commit", "-q", "--allow-empty", "-m", "change")
        if tag:
            self.git("tag", tag)
        return self.git("rev-parse", "HEAD").strip()

    def compute(self, base=None, head="HEAD"):
        return rn.compute(str(self.root), head, base)

    def close(self) -> None:
        self._dir.cleanup()


class ComputeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.repo = Repo()

    def tearDown(self) -> None:
        self.repo.close()

    def test_without_a_release_every_line_is_new(self) -> None:
        self.repo.notes(cs_CZ=["Jedna", "Dvě"], en_US=["One", "Two"])
        self.repo.commit()
        self.assertEqual(
            {"cs-CZ": "• Jedna\n• Dvě", "en-US": "• One\n• Two"},
            self.repo.compute(),
        )

    def test_only_lines_added_since_the_release_are_new(self) -> None:
        self.repo.notes(cs_CZ=["Staré"], en_US=["Old"])
        self.repo.commit(tag="v1.0.0")
        self.repo.notes(cs_CZ=["Staré", "První"], en_US=["Old", "First"])
        self.repo.commit()
        self.repo.notes(cs_CZ=["Staré", "První", "Druhé"], en_US=["Old", "First", "Second"])
        self.repo.commit(tag="v1.1.0-rc1")
        self.repo.notes(cs_CZ=["Staré", "První", "Druhé", "Po tagu"], en_US=["Old", "First", "Second", "After"])
        self.repo.commit()

        # A pre-release made of two merges carries both, and nothing merged after its tag.
        self.assertEqual(
            {"cs-CZ": "• První\n• Druhé", "en-US": "• First\n• Second"},
            self.repo.compute(base="v1.0.0", head="v1.1.0-rc1"),
        )

    def test_deleted_lines_are_ignored_and_an_edited_line_is_new(self) -> None:
        self.repo.notes(cs_CZ=["Smazat", "Upravit"], en_US=["Delete", "Edit"])
        self.repo.commit(tag="v1.0.0")
        self.repo.notes(cs_CZ=["Upravit jinak"], en_US=["Edit differently"])
        self.repo.commit()
        self.assertEqual(
            {"cs-CZ": "• Upravit jinak", "en-US": "• Edit differently"},
            self.repo.compute(base="v1.0.0"),
        )

    def test_nothing_new_gives_no_notes(self) -> None:
        self.repo.notes(cs_CZ=["Staré"], en_US=["Old"])
        self.repo.commit(tag="v1.0.0")
        self.repo.write("app/x.txt", "code only\n")
        self.repo.commit()
        self.assertEqual({}, self.repo.compute(base="v1.0.0"))

    def test_blank_lines_and_comments_are_not_notes(self) -> None:
        self.repo.notes(cs_CZ=["# komentář", "", "  Jedna  "], en_US=["# comment", "One"])
        self.repo.commit()
        self.assertEqual({"cs-CZ": "• Jedna", "en-US": "• One"}, self.repo.compute())

    def test_a_missing_translation_fails(self) -> None:
        self.repo.notes(cs_CZ=["Jedna", "Dvě"], en_US=["One"])
        self.repo.commit()
        with self.assertRaisesRegex(rn.NotesError, "cs-CZ: 2, en-US: 1"):
            self.repo.compute()

    def test_a_listing_locale_without_notes_fails(self) -> None:
        self.repo.write("metadata/android/de-DE/title.txt", "Caelis\n")
        self.repo.notes(cs_CZ=["Jedna"], en_US=["One"])
        self.repo.commit()
        with self.assertRaisesRegex(rn.NotesError, "release-notes/de-DE.txt is missing"):
            self.repo.compute()

    def test_notes_over_the_play_limit_fail(self) -> None:
        self.repo.notes(cs_CZ=["x" * 499], en_US=["y" * 10])
        self.repo.commit()
        with self.assertRaisesRegex(rn.NotesError, "cs-CZ: 501"):
            self.repo.compute()

    def test_notes_at_the_play_limit_pass(self) -> None:
        self.repo.notes(cs_CZ=["x" * 498], en_US=["y"])
        self.repo.commit()
        self.assertEqual(500, len(self.repo.compute()["cs-CZ"]))

    def test_a_base_missing_from_the_clone_fails(self) -> None:
        self.repo.notes(cs_CZ=["Jedna"], en_US=["One"])
        self.repo.commit()
        with self.assertRaisesRegex(rn.NotesError, "fetch-depth: 0"):
            self.repo.compute(base="v9.9.9")

    def test_build_writes_the_upload_actions_layout_and_nothing_when_empty(self) -> None:
        self.repo.notes(cs_CZ=["Jedna"], en_US=["One"])
        self.repo.commit(tag="v1.0.0")
        with tempfile.TemporaryDirectory() as out:
            self.assertEqual(0, rn.main(["build", "--repo", str(self.repo.root), "--out", out]))
            self.assertEqual(["whatsnew-cs-CZ", "whatsnew-en-US"], sorted(p.name for p in Path(out).iterdir()))
            self.assertEqual("• Jedna", (Path(out) / "whatsnew-cs-CZ").read_text(encoding="utf-8"))
        with tempfile.TemporaryDirectory() as out:
            self.assertEqual(0, rn.main(["build", "--repo", str(self.repo.root), "--base", "v1.0.0", "--out", out]))
            self.assertEqual([], list(Path(out).iterdir()))


class LatestReleaseTest(unittest.TestCase):
    @staticmethod
    def runner(returncode: int, stdout: str = "", stderr: str = ""):
        return lambda *args, **kwargs: subprocess.CompletedProcess(args, returncode, stdout, stderr)

    def test_the_tag_of_the_latest_release(self) -> None:
        self.assertEqual("v1.2.0", rn.latest_release_tag("o/r", self.runner(0, "v1.2.0\n")))

    def test_no_release_yet(self) -> None:
        self.assertIsNone(rn.latest_release_tag("o/r", self.runner(1, stderr="gh: Not Found (HTTP 404)")))

    def test_any_other_failure_is_not_taken_for_no_release(self) -> None:
        with self.assertRaisesRegex(rn.NotesError, "HTTP 401"):
            rn.latest_release_tag("o/r", self.runner(1, stderr="gh: Bad credentials (HTTP 401)"))


class RepositoryNotesTest(unittest.TestCase):
    """The repository's own notes, as they stand: what the next build would send."""

    def test_the_working_tree_notes_are_valid(self) -> None:
        locales = sorted(p.name for p in (REPO / "metadata" / "android").iterdir() if p.is_dir())
        for locale in locales:
            path = REPO / "release-notes" / f"{locale}.txt"
            self.assertTrue(path.is_file(), f"{path} is missing")
        counts = {
            locale: len([l for l in (REPO / "release-notes" / f"{locale}.txt").read_text(encoding="utf-8").splitlines()
                         if l.strip() and not l.strip().startswith("#")])
            for locale in locales
        }
        self.assertEqual(1, len(set(counts.values())), f"line counts differ: {counts}")


if __name__ == "__main__":
    unittest.main()

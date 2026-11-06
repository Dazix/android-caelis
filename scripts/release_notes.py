#!/usr/bin/env python3
"""
Google Play release notes ("What's new") computed from git, so a release needs no commit of its own.

`release-notes/<locale>.txt` holds one user-facing line per change, and a pull request only ever adds
lines. The notes of a build are the lines its commit has that the last production release did not:

    push to main / internal  ->  HEAD     against the latest GitHub release (not a pre-release)
    GitHub pre-release       ->  its tag  against the latest GitHub release
    GitHub release           ->  no build; promote_release.py keeps the notes beta already has

so the closed-testing track always shows everything waiting for the next release, and a release made
of several merges carries all of them. A deleted line is simply not new; an edited line is new again.

    release_notes.py build --head <ref> --out <dir> [--base <ref> | --base-latest-release <owner/repo>]
    release_notes.py check  [--base <ref> | --base-latest-release <owner/repo>]

`build` writes `whatsnew-<locale>` files in the layout `r0adkll/upload-google-play` reads, and nothing
when no line is new. Both fail when a listing locale has no notes file, when the locales disagree on
how many lines are new (a missing translation) or when one locale's notes exceed Play's 500 characters.
"""

import argparse
import os
import subprocess
import sys
from typing import Callable, Dict, List, Optional

PLAY_LIMIT = 500
BULLET = "• "
NOTES_DIR = "release-notes"
LISTING_DIR = os.path.join("metadata", "android")


class NotesError(Exception):
    pass


def git(repo: str, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", "-C", repo, *args], capture_output=True, text=True)


def listing_locales(repo: str, ref: str) -> List[str]:
    """The store listing's languages at [ref]: Play shows the notes in those, and only those."""
    result = git(repo, "ls-tree", "-d", "--name-only", f"{ref}:{LISTING_DIR}")
    if result.returncode != 0:
        raise NotesError(f"cannot list {LISTING_DIR} at {ref}: {result.stderr.strip()}")
    return sorted(line for line in result.stdout.splitlines() if line)


def note_lines(repo: str, ref: Optional[str], locale: str) -> Optional[List[str]]:
    """The notes of [locale] at [ref], blanks and `#` comments dropped; None when the file is absent."""
    if ref is None:
        return None
    result = git(repo, "show", f"{ref}:{NOTES_DIR}/{locale}.txt")
    if result.returncode != 0:
        return None
    return [line.strip() for line in result.stdout.splitlines() if line.strip() and not line.strip().startswith("#")]


def latest_release_tag(repo_slug: str, run: Callable[..., subprocess.CompletedProcess] = subprocess.run) -> Optional[str]:
    """
    The tag of the latest GitHub release, which by GitHub's definition is never a pre-release or a
    draft. None only for the answer "there is no release yet" (HTTP 404); any other failure is an
    error, because guessing "no release" would put every line ever written into the notes.
    """
    result = run(
        ["gh", "api", f"repos/{repo_slug}/releases/latest", "--jq", ".tag_name"],
        capture_output=True,
        text=True,
    )
    if result.returncode == 0:
        tag = result.stdout.strip()
        if not tag:
            raise NotesError("the latest release has no tag name")
        return tag
    if "HTTP 404" in result.stderr:
        return None
    raise NotesError(f"cannot read the latest release of {repo_slug}: {result.stderr.strip()}")


def compute(repo: str, head: str, base: Optional[str]) -> Dict[str, str]:
    """Locale -> notes text for the lines [head] has and [base] had not; {} when nothing is new."""
    if base is not None and git(repo, "rev-parse", "--verify", "--quiet", f"{base}^{{commit}}").returncode != 0:
        raise NotesError(f"base {base} is not in this clone; check out with fetch-depth: 0")

    locales = listing_locales(repo, head)
    if not locales:
        raise NotesError(f"no listing locale under {LISTING_DIR} at {head}")

    new_lines: Dict[str, List[str]] = {}
    for locale in locales:
        lines = note_lines(repo, head, locale)
        if lines is None:
            raise NotesError(f"{NOTES_DIR}/{locale}.txt is missing; every listing language needs notes")
        already = set(note_lines(repo, base, locale) or [])
        new_lines[locale] = [line for line in lines if line not in already]

    counts = {locale: len(lines) for locale, lines in new_lines.items()}
    if len(set(counts.values())) > 1:
        detail = ", ".join(f"{locale}: {count}" for locale, count in counts.items())
        raise NotesError(f"locales disagree on the number of new lines ({detail}); translate every line")

    if not any(new_lines.values()):
        return {}

    notes = {locale: "\n".join(BULLET + line for line in lines) for locale, lines in new_lines.items()}
    too_long = {locale: len(text) for locale, text in notes.items() if len(text) > PLAY_LIMIT}
    if too_long:
        detail = ", ".join(f"{locale}: {length}" for locale, length in too_long.items())
        raise NotesError(
            f"notes exceed Play's {PLAY_LIMIT} characters ({detail}); merge the unreleased lines into fewer"
        )
    return notes


def write(notes: Dict[str, str], out_dir: str) -> None:
    os.makedirs(out_dir, exist_ok=True)
    for locale, text in notes.items():
        with open(os.path.join(out_dir, f"whatsnew-{locale}"), "w", encoding="utf-8") as handle:
            handle.write(text)


def resolve_base(args: argparse.Namespace) -> Optional[str]:
    if args.base_latest_release:
        return latest_release_tag(args.base_latest_release)
    return args.base or None


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Google Play release notes from release-notes/<locale>.txt")
    parser.add_argument("command", choices=["build", "check"])
    parser.add_argument("--repo", default=".", help="git work tree (default: .)")
    parser.add_argument("--head", default="HEAD", help="commit the build is made from (default: HEAD)")
    parser.add_argument("--base", help="commit of the last production release; omitted means none")
    parser.add_argument("--base-latest-release", metavar="OWNER/REPO", help="use the latest GitHub release as base")
    parser.add_argument("--out", help="build: directory for the whatsnew-<locale> files")
    args = parser.parse_args(argv)

    if args.command == "build" and not args.out:
        parser.error("build needs --out")

    try:
        base = resolve_base(args)
        notes = compute(args.repo, args.head, base)
    except NotesError as err:
        print(f"Error: {err}", file=sys.stderr)
        return 1

    print(f"Base: {base or 'no release yet, every line is new'}")
    if not notes:
        print("No new release notes.")
    for locale, text in notes.items():
        print(f"--- {locale} ({len(text)}/{PLAY_LIMIT})\n{text}")
    if args.command == "build":
        write(notes, args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())

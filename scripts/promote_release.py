#!/usr/bin/env python3
"""
Universal Release Promotion Script for Google Play Developer API.

Promotes existing release binaries (versionCodes) between tracks (e.g. internal -> beta,
beta -> production) without recompiling or re-uploading the Android App Bundle.
"""

import argparse
import json
import os
import sys
from typing import Any, Dict, List, Optional


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Promote an Android release between Google Play tracks without recompilation."
    )
    parser.add_argument(
        "--package-name",
        default=os.environ.get("PACKAGE_NAME", "com.dazix.caelis"),
        help="Android Application Package Name (default: $PACKAGE_NAME or com.dazix.caelis)",
    )
    parser.add_argument(
        "--from-track",
        default=os.environ.get("FROM_TRACK"),
        required=not bool(os.environ.get("FROM_TRACK")),
        help="Source track to promote from (e.g., internal, beta, alpha)",
    )
    parser.add_argument(
        "--to-track",
        default=os.environ.get("TO_TRACK"),
        required=not bool(os.environ.get("TO_TRACK")),
        help="Destination track to promote to (e.g., beta, production)",
    )
    parser.add_argument(
        "--key-json",
        default=os.environ.get("PLAY_STORE_JSON_KEY"),
        help="Google Play Service Account JSON key content or file path (default: $PLAY_STORE_JSON_KEY)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Simulate the promotion without committing changes to Google Play.",
    )
    return parser.parse_args()


def get_credentials(key_input: Optional[str]) -> Any:
    if not key_input or not key_input.strip():
        print(
            "Error: Service account JSON key is missing. Set PLAY_STORE_JSON_KEY environment variable or pass --key-json.",
            file=sys.stderr,
        )
        sys.exit(1)

    try:
        from google.oauth2 import service_account
    except ImportError:
        print(
            "Error: 'google-auth' package is required. Install via: pip install google-auth google-api-python-client",
            file=sys.stderr,
        )
        sys.exit(1)

    scopes = ["https://www.googleapis.com/auth/androidpublisher"]

    key_input = key_input.strip()
    if key_input.startswith("{"):
        try:
            key_data = json.loads(key_input)
            return service_account.Credentials.from_service_account_info(
                key_data, scopes=scopes
            )
        except json.JSONDecodeError as err:
            print(f"Error parsing service account JSON content: {err}", file=sys.stderr)
            sys.exit(1)
    elif os.path.isfile(key_input):
        return service_account.Credentials.from_service_account_file(
            key_input, scopes=scopes
        )
    else:
        print(
            "Error: Provided key is neither valid JSON nor an existing file path.",
            file=sys.stderr,
        )
        sys.exit(1)


def promote_release(
    package_name: str,
    from_track: str,
    to_track: str,
    credentials: Any,
    dry_run: bool = False,
) -> None:
    try:
        from googleapiclient.discovery import build
        from googleapiclient.errors import HttpError
    except ImportError:
        print(
            "Error: 'google-api-python-client' package is required. Install via: pip install google-api-python-client",
            file=sys.stderr,
        )
        sys.exit(1)

    print(f"Connecting to Google Play Developer API for package: {package_name}...")
    service = build("androidpublisher", "v3", credentials=credentials)
    edits = service.edits()

    try:
        # Step 1: Create a new edit
        print("Creating a new Google Play edit session...")
        edit_request = edits.insert(body={}, packageName=package_name)
        edit = edit_request.execute()
        edit_id = edit["id"]
        print(f"Edit session created: ID={edit_id}")

        # Step 2: Fetch the active release(s) from source track
        print(f"Fetching active release from track '{from_track}'...")
        track_data = edits.tracks().get(
            packageName=package_name, editId=edit_id, track=from_track
        ).execute()

        releases: List[Dict[str, Any]] = track_data.get("releases", [])
        if not releases:
            print(
                f"Error: No releases found in source track '{from_track}'.",
                file=sys.stderr,
            )
            edits.delete(packageName=package_name, editId=edit_id).execute()
            sys.exit(1)

        # Find the primary active release with versionCodes
        source_release: Optional[Dict[str, Any]] = None
        for release in releases:
            if release.get("versionCodes"):
                source_release = release
                break

        if not source_release:
            print(
                f"Error: No release with versionCodes found in source track '{from_track}'. Releases: {releases}",
                file=sys.stderr,
            )
            edits.delete(packageName=package_name, editId=edit_id).execute()
            sys.exit(1)

        version_codes = source_release.get("versionCodes", [])
        release_name = source_release.get("name")
        release_notes = source_release.get("releaseNotes", [])
        print(
            f"Found source release: name='{release_name}', versionCodes={version_codes}, status='{source_release.get('status')}'"
        )

        # Step 3: Assign versionCodes to target track with status 'completed'
        target_release: Dict[str, Any] = {
            "status": "completed",
            "versionCodes": version_codes,
        }
        if release_name:
            target_release["name"] = release_name
        if release_notes:
            target_release["releaseNotes"] = release_notes

        target_track_body = {
            "track": to_track,
            "releases": [target_release],
        }

        print(f"Updating track '{to_track}' with versionCodes {version_codes} (status: completed)...")
        edits.tracks().update(
            packageName=package_name,
            editId=edit_id,
            track=to_track,
            body=target_track_body,
        ).execute()

        # Step 4: Commit or delete (if dry-run)
        if dry_run:
            print(
                f"[DRY-RUN] Changes validated successfully. Deleting edit session {edit_id} without committing."
            )
            edits.delete(packageName=package_name, editId=edit_id).execute()
        else:
            print("Committing edit to Google Play...")
            commit_result = edits.commit(
                packageName=package_name, editId=edit_id
            ).execute()
            print(
                f"SUCCESS: Release {version_codes} successfully promoted from '{from_track}' to '{to_track}'! (Commit ID: {commit_result.get('id', edit_id)})"
            )

    except HttpError as err:
        print(f"Google Play API HTTP Error: {err}", file=sys.stderr)
        try:
            error_details = json.loads(err.content.decode("utf-8"))
            print(
                f"API Error Details: {json.dumps(error_details, indent=2)}",
                file=sys.stderr,
            )
        except Exception:
            pass
        sys.exit(1)
    except Exception as err:
        print(f"Unexpected error during release promotion: {err}", file=sys.stderr)
        sys.exit(1)


def main() -> None:
    args = parse_arguments()
    credentials = get_credentials(args.key_json)
    promote_release(
        package_name=args.package_name,
        from_track=args.from_track,
        to_track=args.to_track,
        credentials=credentials,
        dry_run=args.dry_run,
    )


if __name__ == "__main__":
    main()

# Google Play store listing

What the store shows is versioned here and published by `.github/workflows/store-listing.yml`
through `scripts/publish_listing.py`. Edit the files, open a pull request, merge: the listing in the
store follows `main`.

```
metadata/android/<locale>/title.txt               at most 30 characters
metadata/android/<locale>/short_description.txt   at most 80 characters
metadata/android/<locale>/full_description.txt    at most 4000 characters
metadata/android/<locale>/images/featureGraphic.png         1024 x 500
metadata/android/<locale>/images/phoneScreenshots/*.png     2 to 8, uploaded in file-name order
```

Locales are Play's codes (`en-US`, `cs-CZ`). The layout is fastlane's, so `fastlane supply` reads the
same tree should it ever be wanted.

## What a run does

- **Pull request**: the script's tests and `validate` (lengths, image sizes, counts). No key needed.
- **Push to `main`**: one Play edit with every difference, committed. Texts are compared field by
  field, images by SHA-256 and order; an unchanged type is not touched. When nothing differs, nothing
  is committed.
- **Manual run** (Actions → Store listing → Run workflow, on `main`): `dry_run` on by default, which
  lets Play check the edit and then discards it.

Every committed change goes to Google's review again, so a run that sends nothing is the normal case.

## What is never deleted

Anything missing here is left alone in the store: a locale without a directory, a text without its
file, an image type without its file or folder. To change something, change its file; to stop
managing it from here, delete the file. An empty text file is refused rather than blanking the store.

## Locally

```bash
python3 scripts/publish_listing.py validate
python3 -m unittest discover -s scripts -p 'test_publish_listing.py'

# needs the Play service-account key and the packages from scripts/requirements-promote.txt
PLAY_STORE_JSON_KEY=/path/to/key.json python3 scripts/publish_listing.py pull          # store texts -> files
PLAY_STORE_JSON_KEY=/path/to/key.json python3 scripts/publish_listing.py push --dry-run
```

`pull` writes texts only; the images here are the source, rendered from the HTML in
`design/play-store/`.

# CAELIS logo — usage guide

Status: **applied** to the app (`app/src/main/res/drawable/ic_launcher_*.xml`, `ic_notification.xml`). These SVGs are the masters; the drawables are hand-converted from them (viewport 256, same geometry).

## Idea

One mark for all three features: a **viewfinder** (photography, AR aiming) around an **eight-point star** (four long and four short concave points)
(night sky, sun and moon as bodies in the sky), inside a **dial** (interval, time-lapse): the right half is a solid line (time already run),
the left half is four dots (6, 7:30, 9 and 10:30 h) joined by a thin line that never touches them, and an arrow at 12 h closes the loop into the next cycle.

## Palette

| Role | Name | HEX | Notes |
| --- | --- | --- | --- |
| Background | Night | `#0E1433` | icon tile, dark surfaces |
| Primary line | Ivory | `#F2EFE6` | viewfinder, dial, wordmark on dark. 15:1 on Night |
| Accent | Amber | `#FFB547` | the star only. 9:1 on Night |
| Dim tone | Dusk | `#343C70` | not used by the symbol; spare tone for UI on Night |
| Light-surface accent | Amber (dark) | `#E8920A` | the star on white/light surfaces |
| Light-surface dim tone | Mist | `#B9BFE0` | not used by the symbol; spare tone for UI on light surfaces |

## Print

**CMYK values below are naive sRGB→CMYK starting points, not profiled conversions.** They ignore paper and
ink behaviour and are especially unreliable for Night (it comes out K-heavy, 80 % black). Before print, convert the
HEX with the ICC profile your printer asks for (for coated paper in Europe usually ISO Coated v2 / FOGRA39) in
Illustrator or InDesign, then proof.

| Colour | HEX | Naive CMYK (C/M/Y/K) |
| --- | --- | --- |
| Night | `#0E1433` | 73 / 61 / 0 / 80 |
| Ivory | `#F2EFE6` | 0 / 1 / 5 / 5 (on paper: use the paper white, no ink) |
| Amber | `#FFB547` | 0 / 29 / 72 / 0 |
| Dusk | `#343C70` | 54 / 46 / 0 / 56 |
| Amber (dark) | `#E8920A` | 0 / 37 / 96 / 9 |

**Pantone:** the official Pantone values are behind Pantone Connect (paid), so no verified match was made.
For a two-colour job, compare against a physical Solid Coated swatch book and choose the closest of:
- Night (dark blue-violet): 2765 C, 2767 C, 5255 C
- Amber: 1365 C, 1235 C, 137 C

These candidates come from the colour families only and are **unverified**; do not quote a Pantone number as final until it has been checked against the swatch book.

**One-colour use:** pick `-black` when the job cannot do tints (stamps, engraving, embroidery, laser), `-black-tint`
when it can. The tint version screens only the thin dial line (35 %); in the solid versions it is solid too. Printed in a spot colour, use the same files and assign the ink. Strokes are live in the SVGs: have
the printer's artwork exported as outlined PDF (Illustrator: Object ▸ Path ▸ Outline Stroke) before sending.

## Files

| File | Use |
| --- | --- |
| `caelis-icon.svg` | App icon master (tile, rounded). Store listing, docs, README |
| `caelis-icon-small.svg` | Small-size cut for 16–48 px (favicon, tiny UI). Thin dial line removed; solid half, dots, arrow and viewfinder thickened |
| `caelis-symbol-dark.svg` / `-light.svg` | Symbol without tile, for dark / light surfaces |
| `caelis-lockup-horizontal-dark.svg` / `-light.svg` | Symbol + CAELIS wordmark |
| `caelis-lockup-vertical-dark.svg` / `-light.svg` | Symbol above the wordmark (square-ish placements, posters, splash) |
| `print/caelis-1c-*.svg` | One-colour print versions: symbol, horizontal and vertical lockup. `-black` = one solid ink, `-black-tint` = thin dial line at a 35 % screen of the same ink, `-white` = reversed (knock-out) |
| `android/ic_launcher_background.svg` | Adaptive icon background layer (solid Night) |
| `android/ic_launcher_foreground.svg` | Adaptive icon foreground, scaled to 92 % to stay inside the 66 dp safe zone |
| `android/ic_launcher_monochrome.svg` | Themed (Android 13+) icon; thin dial line at 45 % alpha |
| `android/ic_notification.svg` | Status-bar / notification icon, alpha only, small-size cut |
| `web/` | favicon.ico, PNG set, webmanifest, `<head>` snippet generated from the small-size cut |
| `png/` | PNG renders of the icon and Android layers for quick review |

The wordmark is custom-drawn geometry (round-capped strokes), not a font, so there is no font licence to track.
All files are plain SVG with no text, raster or filters; convert strokes to outlines before sending to print.

## Rules

- **Minimum size:** full icon 48 px; below that use the small-size cut. Lockup: symbol at least 24 px tall.
- **Clear space:** keep one star-width (the width of the star) free on all sides of the symbol and the lockup.
- **Backgrounds:** symbol-dark on Night or dark photos; symbol-light on white and light surfaces. Never place the dark version on mid-grey.
- **Do not:** recolour the star, change the number or position of dots, flip the arrow, rotate the symbol, add gradients or shadows, put the wordmark in a font, or squeeze the lockup.

## Android integration (done)

The SVGs here are the masters. `app/src/main/res/drawable/ic_launcher_{background,foreground,monochrome}.xml` and
`ic_notification.xml` are hand-converted from them (viewport 256, same geometry); if a master changes, change the
drawable with it. `ic_notification` is used as the small icon of both notifications and only its alpha counts.

## Open items

- Trademark / similarity check has not been done. Star motifs are common; the sharp eight-point star with long and short tips, the viewfinder and the dot dial with arrow together are what make the mark ours.
- Pantone numbers are unverified candidates and CMYK values are unprofiled (see Print). Stroke outlining and PDF export were not done here (no Illustrator/Inkscape available).
- Trademark: see `TRADEMARK.md`. It is a research checklist, not a legal opinion.
- Not yet tested on a physical launcher; the 16/32 px behaviour was judged from rendered previews only.

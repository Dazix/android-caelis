#!/usr/bin/env python3
"""Build the presentation site from site/ into site/dist/ (untracked; .github/workflows/pages.yml
builds it on every push to main and deploys it to GitHub Pages).

One static page per language (English at the root, the others under /<lang>/), so search engines
index every language with its own URL, title and description, linked together by hreflang.
Also writes sitemap.xml, robots.txt and site.webmanifest, and copies site/assets/.
The custom domain is set in the repository's Pages settings: a deploy from Actions ignores CNAME.

    python3 scripts/build_site.py                 # base URL https://caelis-app.com/
    CAELIS_SITE_URL=https://example.org/ python3 scripts/build_site.py
    python3 -m http.server -d site/dist           # preview
"""
import html
import json
import os
import re
import shutil
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "site"
OUT = SRC / "dist"

BASE = os.environ.get("CAELIS_SITE_URL", "https://caelis-app.com/").rstrip("/") + "/"
PLAY = "https://play.google.com/store/apps/details?id=com.dazix.caelis"
REPO = "https://github.com/Dazix/android-caelis"
LANGS = ["en", "cs", "sk", "pl", "de"]
DEFAULT = "en"
# Markup a string may carry into the page; everything else is escaped.
ALLOWED_TAGS = re.compile(r"&lt;(/?(?:em|strong|br))&gt;")


def url_for(lang: str) -> str:
    return BASE if lang == DEFAULT else f"{BASE}{lang}/"


def text(value: str) -> str:
    return ALLOWED_TAGS.sub(r"<\1>", html.escape(value, quote=False))


def attr(value: str) -> str:
    return html.escape(re.sub(r"<[^>]+>", "", value), quote=True)


def json_ld(lang: str, s: dict) -> str:
    data = {
        "@context": "https://schema.org",
        "@type": "MobileApplication",
        "name": "CAELIS",
        "alternateName": f"CAELIS – {s['app.category']}",
        "description": s["meta.desc"],
        "url": url_for(lang),
        "inLanguage": lang,
        "operatingSystem": "Android 11+",
        "applicationCategory": "PhotographyApplication",
        "applicationSubCategory": "Astronomy",
        "isAccessibleForFree": True,
        "offers": {"@type": "Offer", "price": "0", "priceCurrency": "EUR"},
        "installUrl": PLAY,
        "downloadUrl": PLAY,
        "license": "https://www.gnu.org/licenses/gpl-3.0.html",
        "image": f"{BASE}assets/img/icon-512.png",
        "screenshot": [f"{BASE}assets/img/{name}-{lang}-1080.webp"
                       for name in ("night-sky", "horizon-peaks", "sun-path", "time-lapse", "ramping", "setup")],
        "sameAs": [PLAY, REPO],
    }
    # "</" inside a script block would end it early.
    return json.dumps(data, ensure_ascii=False, indent=2).replace("</", "<\\/")


def render(template: str, lang: str, strings: dict) -> str:
    s = {**strings[DEFAULT], **strings[lang]}
    missing = set(strings[DEFAULT]) - set(strings[lang])
    if missing:
        raise SystemExit(f"{lang}: missing strings {sorted(missing)}")

    root = "" if lang == DEFAULT else "../"
    alternates = "\n".join(
        [f'  <link rel="alternate" hreflang="{l}" href="{url_for(l)}">' for l in LANGS]
        + [f'  <link rel="alternate" hreflang="x-default" href="{url_for(DEFAULT)}">'])
    og_alternates = "\n".join(
        f'  <meta property="og:locale:alternate" content="{strings[l]["og.locale"]}">' for l in LANGS if l != lang)
    lang_links = "\n".join(
        f'            <li><a href="{root}{"" if l == DEFAULT else l + "/"}" hreflang="{l}" lang="{l}" data-lang="{l}"'
        + (' aria-current="page"' if l == lang else "")
        + f'>{text(strings[l]["lang.name"])}</a></li>'
        for l in LANGS)

    values = {
        "lang": lang,
        "root": root,
        "home": root or "./",
        "base": BASE,
        "canonical": url_for(lang),
        "alternates": alternates,
        "og_locale": s["og.locale"],
        "og_alternates": og_alternates,
        "lang_links": lang_links,
        "lang_name": text(s["lang.name"]),
        "play": PLAY,
        "repo": REPO,
        "jsonld": json_ld(lang, s),
    }

    def substitute(match: re.Match) -> str:
        key = match.group(1)
        if key.startswith("a:"):
            return attr(s[key[2:]])
        if key in values:
            return values[key]
        if key in s:
            return text(s[key])
        raise SystemExit(f"unknown placeholder {{{{{key}}}}}")

    return re.sub(r"\{\{([\w.:]+)\}\}", substitute, template)


def sitemap() -> str:
    today = date.today().isoformat()
    links = "\n".join(f'    <xhtml:link rel="alternate" hreflang="{l}" href="{url_for(l)}"/>' for l in LANGS)
    links += f'\n    <xhtml:link rel="alternate" hreflang="x-default" href="{url_for(DEFAULT)}"/>'
    urls = "\n".join(
        f"  <url>\n    <loc>{url_for(l)}</loc>\n    <lastmod>{today}</lastmod>\n{links}\n  </url>" for l in LANGS)
    return ('<?xml version="1.0" encoding="UTF-8"?>\n'
            '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" '
            'xmlns:xhtml="http://www.w3.org/1999/xhtml">\n'
            f"{urls}\n</urlset>\n")


def manifest() -> str:
    return json.dumps({
        "name": "CAELIS – Time-lapse & Sky AR",
        "short_name": "CAELIS",
        "start_url": "./",
        "display": "browser",
        "background_color": "#0E1433",
        "theme_color": "#0E1433",
        "icons": [
            {"src": "assets/img/icon-192.png", "sizes": "192x192", "type": "image/png"},
            {"src": "assets/img/icon-512.png", "sizes": "512x512", "type": "image/png"},
        ],
    }, indent=2) + "\n"


def main() -> None:
    template = (SRC / "template.html").read_text(encoding="utf-8")
    strings = json.loads((SRC / "strings.json").read_text(encoding="utf-8"))
    # Start clean, so a file dropped from site/ never lingers on the published site.
    shutil.rmtree(OUT, ignore_errors=True)
    shutil.copytree(SRC / "assets", OUT / "assets")
    for lang in LANGS:
        target = OUT / ("index.html" if lang == DEFAULT else f"{lang}/index.html")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(render(template, lang, strings), encoding="utf-8")
        print(target.relative_to(ROOT))
    (OUT / "sitemap.xml").write_text(sitemap(), encoding="utf-8")
    (OUT / "robots.txt").write_text(f"User-agent: *\nAllow: /\n\nSitemap: {BASE}sitemap.xml\n", encoding="utf-8")
    (OUT / "site.webmanifest").write_text(manifest(), encoding="utf-8")
    print("site/dist/sitemap.xml, robots.txt, site.webmanifest, assets/")


if __name__ == "__main__":
    main()

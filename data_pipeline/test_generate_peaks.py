#!/usr/bin/env python3
"""
Tests for generate_peaks.py against a tiny synthetic POI file in the documented mapsforge schema.

    python3 -m unittest data_pipeline/test_generate_peaks.py        (from the repository root)
    python3 data_pipeline/test_generate_peaks.py --write-fixture <dir>

`--write-fixture` writes the `.peaks` file the Kotlin `LandmarkTileCodecTest` reads. The fixture is
produced here, not by the Kotlin writer, so that the two implementations are tested against each
other instead of against themselves.
"""

import os
import sqlite3
import struct
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import generate_peaks as gp  # noqa: E402

FIXTURE_TILE = "N50E015"

# (lat, lon, tags) - the fixture tile N50E015 holds the first seven.
PEAKS = [
    (50.7361, 15.7399, "name=Sněžka\rname:cs=Sněžka\rname:en=Sniezka\rname:de=Schneekoppe\r"
                       "name:pl=Śnieżka\rname:fr=Snezka\rele=1603.3\rnatural=peak"),
    (50.7350, 15.7300, "name=Śnieżka bis\rele=1 602 m"),
    (50.8000, 15.3000, "name:en=Only English\rele=1100"),
    (50.5000, 15.5000, "name=Smoji \U0001F3D4\rname:cs=Smoji\rele=900,5"),
    (50.6000, 15.6000, "name=富士山\rname:ja-Latn=Fujisan\rint_name=Fuji\rname:en=Mount Fuji\rele=3776"),
    (50.6100, 15.6100, "name=Όλυμπος\rint_name=Ολυμπος\rname:el-latn=Olympos\rele=2918"),
    (50.6200, 15.6200, "name=Эльбрус\rint_name=Elbrus\rname:sk=Elbrus\rele=5642"),
    (50.9000, 15.9000, "name=No height"),
    (50.1000, 15.1000, "ele=800"),                         # nameless: dropped
    (49.4502, 18.4524, "name=Lysá hora\rname:cs=Lysá hora\rele=1323"),
    (50.7361, 15.7399, "name=Sněžka\rele=1603"),            # duplicate of the first: dropped
    (51.6000, 17.6000, "name=Sliver Hill\rele=300"),       # in a tile the extract barely touches: dropped
]


def build_synthetic_poi(path, extra_shops=()):
    conn = sqlite3.connect(path)
    conn.executescript(
        """
        CREATE TABLE metadata (name TEXT, value TEXT);
        CREATE TABLE poi_categories (id INTEGER PRIMARY KEY, name TEXT, parent INTEGER);
        CREATE TABLE poi_category_map (id INTEGER, category INTEGER, PRIMARY KEY (id, category));
        CREATE TABLE poi_data (id INTEGER PRIMARY KEY, data BLOB);
        CREATE TABLE poi_index (id INTEGER PRIMARY KEY, lat REAL, lon REAL);
        INSERT INTO poi_categories VALUES (1, 'Root', NULL), (2, 'Peak', 1), (3, 'Volcano', 1), (4, 'Shop', 1);
        """
    )
    next_id = 1
    for lat, lon, tags in PEAKS:
        conn.execute("INSERT INTO poi_data VALUES (?, ?)", (next_id, tags.encode("utf-8")))
        conn.execute("INSERT INTO poi_index VALUES (?, ?, ?)", (next_id, lat, lon))
        conn.execute("INSERT INTO poi_category_map VALUES (?, 2)", (next_id,))
        next_id += 1
    # Enough ordinary POIs that these tiles count as covered: N48E016 has no peak at all, and the two
    # peak tiles need them because a peak alone does not make a tile covered.
    for lat, lon, count in [(48.5, 16.5, gp.MIN_POIS_FOR_COVERED_TILE), (50.01, 15.01, 20), (49.01, 18.01, 20)] + list(extra_shops):
        for i in range(count):
            conn.execute("INSERT INTO poi_data VALUES (?, ?)", (next_id, b"name=Shop"))
            conn.execute("INSERT INTO poi_index VALUES (?, ?, ?)", (next_id, lat, lon))
            conn.execute("INSERT INTO poi_category_map VALUES (?, 4)", (next_id,))
            next_id += 1
    # A lone shop in Poland: not enough to call the tile covered.
    conn.execute("INSERT INTO poi_data VALUES (?, ?)", (next_id, b"name=Stray"))
    conn.execute("INSERT INTO poi_index VALUES (?, 51.5, 17.5)", (next_id,))
    conn.execute("INSERT INTO poi_category_map VALUES (?, 4)", (next_id,))
    conn.commit()
    conn.close()


def decode(data):
    """A reader independent of encode_tile, in the layout the Kotlin side documents."""
    magic, version, count = struct.unpack_from(">iii", data, 0)
    assert magic == gp.MAGIC and version == gp.VERSION
    pos = 12
    records = []
    for _ in range(count):
        lat, lon, ele = struct.unpack_from(">iih", data, pos)
        pos += 10
        name, pos = read_utf(data, pos)
        (n,) = struct.unpack_from(">B", data, pos)
        pos += 1
        names = []
        for _ in range(n):
            key, pos = read_utf(data, pos)
            value, pos = read_utf(data, pos)
            names.append((key, value))
        assert names == sorted(names), "other names are sorted by key"
        records.append((lat, lon, ele, name, dict(names)))
    assert pos == len(data)
    return records


def read_utf(data, pos):
    (n,) = struct.unpack_from(">H", data, pos)
    raw = data[pos + 2:pos + 2 + n]
    # Modified UTF-8 writes a supplementary character as two 3-byte surrogates.
    text = raw.decode("utf-8", errors="surrogatepass").encode("utf-16", "surrogatepass").decode("utf-16")
    return text, pos + 2 + n


class ElevationTest(unittest.TestCase):
    def test_formats(self):
        cases = {
            "1602": 1602, "1602 m": 1602, "1 602": 1602, "1 602 m": 1602, "1,602": 1602,
            "1602,5": 1602, "1603.4": 1603, "5262 ft": 1604, "2.5 km": None, "": None,
            "abc": None, "0": None, "-5": None, "9000": None, None: None,
        }
        for raw, expected in cases.items():
            with self.subTest(raw=raw):
                self.assertEqual(gp.parse_elevation(raw), expected)


class JavaUtfTest(unittest.TestCase):
    def test_ascii_and_nul(self):
        self.assertEqual(gp.java_utf("ab"), b"\x00\x02ab")
        self.assertEqual(gp.java_utf("\x00"), b"\x00\x02\xc0\x80")

    def test_supplementary_is_two_surrogates(self):
        self.assertEqual(gp.java_utf("\U0001F3D4"), b"\x00\x06\xed\xa0\xbc\xed\xbf\x94")


class TileNameTest(unittest.TestCase):
    def test_names_match_dem_tile_key(self):
        self.assertEqual(gp.tile_name(50, 15), "N50E015")
        self.assertEqual(gp.tile_name(-1, -1), "S01W001")
        self.assertEqual(gp.tile_key(-500_000, -500_000), (-1, -1))
        self.assertEqual(gp.tile_key(50_000_000, 15_000_000), (50, 15))


class GenerateTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.poi = os.path.join(self.dir.name, "x.poi")
        self.out = os.path.join(self.dir.name, "out")
        build_synthetic_poi(self.poi)

    def read(self, name):
        with open(os.path.join(self.out, "tiles", name[:3], name + ".peaks"), "rb") as fh:
            return decode(fh.read())

    def test_counts_and_filters(self):
        peaks, tiles, total = gp.generate(self.out, [self.poi])
        # Kept: the seven of N50E015 and Lysá hora. Dropped: no height, nameless, duplicate, and
        # Sliver Hill (its tile is not covered).
        self.assertEqual(peaks, 8)
        # N50E015, N49E018, N48E016 (covered, empty); the stray shop's tile is not covered.
        self.assertEqual(tiles, 3)
        self.assertGreater(total, 0)
        self.assertFalse(os.path.exists(os.path.join(self.out, "tiles", "N51")))

    def test_fixture_tile_content(self):
        gp.generate(self.out, [self.poi])
        records = {r[3]: r for r in self.read(FIXTURE_TILE)}
        self.assertEqual(len(records), 7)
        sneka = records["Sněžka"]
        self.assertEqual(sneka[:3], (50_736_100, 15_739_900, 1603))
        # name:cs equals the local name and is left out; name:fr is not an app language.
        self.assertEqual(sneka[4], {"de": "Schneekoppe", "en": "Sniezka", "pl": "Śnieżka"})
        self.assertEqual(records["Śnieżka bis"][4], {})
        self.assertEqual(records["Only English"][4], {})
        self.assertEqual(records["Only English"][2], 1100)
        self.assertEqual(records["Smoji \U0001F3D4"][4], {"cs": "Smoji"})

    def test_latin_spelling_only_for_a_name_in_another_script(self):
        gp.generate(self.out, [self.poi])
        records = {r[3]: r[4] for r in self.read(FIXTURE_TILE)}
        # int_name is preferred to name:*-Latn.
        self.assertEqual(records["富士山"], {"en": "Mount Fuji", "Latn": "Fuji"})
        # An int_name in the local script is no Latin spelling; name:el-latn (lower case) is.
        self.assertEqual(records["Όλυμπος"], {"Latn": "Olympos"})
        self.assertEqual(records["Эльбрус"], {"sk": "Elbrus", "Latn": "Elbrus"})
        # A Latin local name gets none, whatever its tags.
        self.assertNotIn("Latn", records["Sněžka"])

    def test_is_latin(self):
        for text in ("Sněžka", "Śnieżka", "Gerlachovský štít", "K2", "Mont Blanc 4808"):
            self.assertTrue(gp.is_latin(text), text)
        for text in ("富士山", "Όλυμπος", "Эльбрус", "Mount 富士", "", "1603"):
            self.assertFalse(gp.is_latin(text), text)

    def test_duplicate_fills_the_names_the_first_record_lacks(self):
        dedupe = gp.Deduper()
        first = {"lat_e6": 1, "lon_e6": 1, "name": "Sněžka", "names": {"en": "Sniezka"}}
        second = {"lat_e6": 2, "lon_e6": 2, "name": "sněžka", "names": {"en": "Snezka", "de": "Schneekoppe", "pl": "Sněžka"}}
        self.assertTrue(dedupe.add(first))
        self.assertFalse(dedupe.add(second))
        # The first record keeps its own English; a name equal to its local one is not added.
        self.assertEqual(first["names"], {"en": "Sniezka", "de": "Schneekoppe"})

    def test_empty_covered_tile(self):
        gp.generate(self.out, [self.poi])
        self.assertEqual(self.read("N48E016"), [])

    def test_lysa_hora(self):
        gp.generate(self.out, [self.poi])
        self.assertEqual(self.read("N49E018")[0][2], 1323)

    def test_peak_in_an_uncovered_tile_is_dropped_not_published(self):
        gp.generate(self.out, [self.poi])
        # A sliver of a neighbouring country holds one peak and a couple of POIs: a 404, not "these are all".
        self.assertFalse(os.path.exists(os.path.join(self.out, "tiles", "N51", "N51E017.peaks")))

    def test_coverage_is_judged_over_all_inputs_together(self):
        # Each file alone leaves N51E017 under the threshold; together they cover it, so the peak stays.
        half = gp.MIN_POIS_FOR_COVERED_TILE // 2
        a = os.path.join(self.dir.name, "a.poi")
        b = os.path.join(self.dir.name, "b.poi")
        build_synthetic_poi(a, extra_shops=[(51.6, 17.6, half)])
        build_synthetic_poi(b, extra_shops=[(51.6, 17.6, half)])
        gp.generate(self.out, [a, b])
        names = [r[3] for r in self.read("N51E017")]
        self.assertEqual(names, ["Sliver Hill"])

    def test_world_threshold_covers_a_tile_with_any_poi(self):
        # Run on the whole world both sides of a border are in, so Sliver Hill and the stray shop are
        # enough: the tile is covered and its peak is published instead of dropped.
        peaks, tiles, _ = gp.generate(self.out, [self.poi], min_pois=1)
        self.assertEqual([r[3] for r in self.read("N51E017")], ["Sliver Hill"])
        self.assertEqual(peaks, 9)
        self.assertEqual(tiles, 4)

    def test_world_threshold_leaves_a_tile_without_pois_uncovered(self):
        # An ocean, but also an island no extract covers: a 404, never an empty "no peaks here".
        gp.generate(self.out, [self.poi], min_pois=1)
        self.assertFalse(os.path.exists(os.path.join(self.out, "tiles", "N47")))
        self.assertFalse(os.path.exists(os.path.join(self.out, "tiles", "N50", "N50E016.peaks")))

    def test_min_pois_argument(self):
        self.assertEqual(gp.parse_min_pois("1"), 1)
        self.assertEqual(gp.parse_min_pois("20"), 20)
        for bad in ("0", "-3", "x", "", "1.5"):
            with self.assertRaises(ValueError):
                gp.parse_min_pois(bad)

    def test_second_file_does_not_duplicate_a_border_peak(self):
        other = os.path.join(self.dir.name, "y.poi")
        build_synthetic_poi(other)
        peaks, _, _ = gp.generate(self.out, [self.poi, other])
        self.assertEqual(peaks, 8)


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--write-fixture":
        with tempfile.TemporaryDirectory() as tmp:
            poi = os.path.join(tmp, "fixture.poi")
            build_synthetic_poi(poi)
            gp.generate(tmp + "/out", [poi])
            os.makedirs(sys.argv[2], exist_ok=True)
            src = os.path.join(tmp, "out", "tiles", FIXTURE_TILE[:3], FIXTURE_TILE + ".peaks")
            with open(src, "rb") as fh, open(os.path.join(sys.argv[2], FIXTURE_TILE + ".peaks"), "wb") as out:
                out.write(fh.read())
        sys.exit(0)
    unittest.main()

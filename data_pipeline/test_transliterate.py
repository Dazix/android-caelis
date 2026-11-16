#!/usr/bin/env python3
"""
Tests for transliterate.py, against spellings maps and signs use.

    <venv>/bin/python -m unittest data_pipeline/test_transliterate.py      (from the repository root)
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from transliterate import latin_spelling  # noqa: E402


def spell(name, region="", **tags):
    return latin_spelling(name, {k.replace("__", ":").replace("_", "-"): v for k, v in tags.items()}, region)


class CyrillicTest(unittest.TestCase):
    def test_russian(self):
        self.assertEqual(spell("Эльбрус", "north-caucasus-fed-district"), "Elbrus")
        self.assertEqual(spell("Ключевская Сопка", "far-eastern-fed-district"), "Klyuchevskaya Sopka")
        self.assertEqual(spell("гора Лебедев Курган", "volga-fed-district"), "Gora Lebedev Kurgan")
        # A Russian hard sign before a vowel is a separator, not a letter.
        self.assertEqual(spell("Подъёмная", "russia"), "Podyomnaya")
        # й and ё are letters of their own, not и and е with an accent.
        self.assertEqual(spell("Актай", "volga-fed-district"), "Aktay")

    def test_bulgarian(self):
        self.assertEqual(spell("Мусала", "bulgaria"), "Musala")
        # ъ before a consonant is a vowel; щ is "sht" in Bulgaria.
        self.assertEqual(spell("Дебело бърдо", "bulgaria"), "Debelo Bardo")
        self.assertEqual(spell("Щърково", "bulgaria"), "Shtarkovo")

    def test_ukrainian(self):
        self.assertEqual(spell("Говерла", "ukraine"), "Hoverla")
        self.assertEqual(spell("Пікуй", "ukraine"), "Pikuy")
        self.assertEqual(spell("Rožok/Ріжок", "ukraine"), "Rožok/Rizhok")


class GreekTest(unittest.TestCase):
    def test_digraphs_follow_elot(self):
        self.assertEqual(spell("Βουλγάρες", "greece"), "Voulgares")
        self.assertEqual(spell("Κουρμουλού", "greece"), "Kourmoulou")
        self.assertEqual(spell("Βουνό Ελισάβετ", "greece"), "Vouno Elisavet")
        self.assertEqual(spell("Ευρυτανία", "greece"), "Evrytania")

    def test_plain_letters(self):
        self.assertEqual(spell("Όλυμπος", "greece"), "Olympos")
        self.assertEqual(spell("Μεσαίος Λόφος", "greece"), "Mesaios Lofos")


class CaucasusTest(unittest.TestCase):
    def test_georgian_and_armenian(self):
        self.assertEqual(spell("შხარა", "georgia"), "Shkhara")
        self.assertEqual(spell("ჭაუხი 2", "georgia"), "Chaukhi 2")
        self.assertEqual(spell("Արագած", "armenia"), "Aragats")


class KoreanTest(unittest.TestCase):
    def test_revised_romanization_as_pronounced(self):
        self.assertEqual(spell("한라산", "south-korea"), "Hallasan")
        self.assertEqual(spell("설악산", "south-korea"), "Seoraksan")
        self.assertEqual(spell("백두산", "north-korea"), "Baekdusan")
        self.assertEqual(spell("죽바위산", "south-korea"), "Jukbawisan")
        self.assertEqual(spell("삼각점", "south-korea"), "Samgakjeom")
        self.assertEqual(spell("북한산", "south-korea"), "Bukhansan")
        self.assertEqual(spell("지리산", "south-korea"), "Jirisan")
        self.assertEqual(spell("구봉산", "south-korea"), "Gubongsan")


class ChineseTest(unittest.TestCase):
    def test_pinyin_with_the_generic_word_apart(self):
        self.assertEqual(spell("孙家山", "zhejiang"), "Sunjia Shan")
        self.assertEqual(spell("长芝岭", "zhejiang"), "Zhangzhi Ling")
        self.assertEqual(spell("大湖山", "taiwan"), "Dahu Shan")
        self.assertEqual(spell("玉山", "taiwan"), "Yu Shan")

    def test_a_name_mixed_with_another_script_is_left_alone(self):
        self.assertEqual(spell("帽儿山 모아산", "jilin"), "")


class JapaneseTest(unittest.TestCase):
    def test_kana_reading_by_hepburn(self):
        self.assertEqual(spell("吉祥山", "japan", name__ja_Hira="きちじょうざん"), "Kichijozan")
        self.assertEqual(spell("高士山", "japan", name__ja_hira="こうどさん"), "Kodosan")
        self.assertEqual(spell("槍ヶ岳", "japan", name__ja_Kana="ヤリガタケ"), "Yarigatake")
        self.assertEqual(spell("八甲田山", "japan", name__ja_hira="はっこうださん"), "Hakkodasan")
        self.assertEqual(spell("ピラミッド", "japan"), "Piramiddo")

    def test_kanji_are_never_read(self):
        self.assertEqual(spell("槍ヶ岳", "japan"), "")
        self.assertEqual(spell("富士山", "japan"), "")

    def test_kana_outside_japan_is_left_alone(self):
        self.assertEqual(spell("ピラミッド", "taiwan"), "")


class LeftAloneTest(unittest.TestCase):
    def test_scripts_without_a_trustworthy_spelling(self):
        self.assertEqual(spell("تل الجدعة", "iraq"), "")
        self.assertEqual(spell("הר מירון", "israel-and-palestine"), "")

    def test_latin_or_no_letters(self):
        self.assertEqual(spell("Sněžka", "czech-republic"), "")
        self.assertEqual(spell("1689", "russia"), "")


if __name__ == "__main__":
    unittest.main()

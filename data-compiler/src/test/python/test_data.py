"""Conversion and release admission tests; fixtures are never dictionary inputs."""
from pathlib import Path
import json
import shutil
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / "tools"))
from data import aligned_pinyin, bopomofo, dictionary_tones, pinyin, load_curated

class ReadingConversionTest(unittest.TestCase):
    def test_tones_umlaut_circumflex_and_combining_marks(self):
        self.assertEqual("lv4", pinyin("Lǜ"))
        self.assertEqual("e^2", pinyin("ế"))
        self.assertEqual("m2", pinyin("m\u0301"))
        self.assertEqual("de5", pinyin("de"))
        for invalid in ("nǐhǎo", "abc2", "", "á1", "2á"):
            with self.assertRaises(ValueError): pinyin(invalid)

    def test_bopomofo_spelling_and_explicit_neutral_tone(self):
        examples = {"ㄓ": "zhi1", "ㄔˊ": "chi2", "ㄖˋ": "ri4", "ㄗˇ": "zi3",
                    "ㄓㄨㄥ": "zhong1", "ㄨㄥ": "weng1", "ㄐㄩㄥˇ": "jiong3",
                    "ㄩㄣˊ": "yun2", "ㄩ": "yu1", "ㄌㄩˋ": "lv4", "ㄋㄩㄝˋ": "nve4",
                    "ㄐㄩㄝˊ": "jue2", "ㄒㄧㄡ": "xiu1", "ㄍㄨㄟ": "gui1", "ㄌㄨㄣˊ": "lun2",
                    "ㄧㄣ": "yin1", "ㄧㄥ": "ying1", "ㄉㄜ˙": "de5", "˙ㄉㄜ": "de5"}
        for source, expected in examples.items():
            self.assertEqual(expected, bopomofo(source), source)
        for invalid in ("ㄓㄨˇㄥ", "ㄩˊˋ", "ˊㄩ", "ㄅ", "ㄧㄧ", ""):
            with self.assertRaises(ValueError): bopomofo(invalid)

    def test_joined_pinyin_and_scalar_alignment(self):
        inventory = {"si", "hai", "er", "tuo", "heng", "bo", "an", "lv", "ye", "wo"}
        self.assertEqual(("si1", "hai3", "er3", "tuo1", "heng1", "bo2", "si1"),
                         aligned_pinyin("斯海尔托亨博斯", "Sīhǎi'ěrtuōhēngbósī", inventory, {}))
        self.assertEqual(("lv4", "ye4"), aligned_pinyin("绿叶", "lǜ-yè", inventory, {}))
        self.assertEqual(("wo3", "an1"), aligned_pinyin("𠀀安", "wǒ ān", inventory, {}))
        for surface, reading in (("斯海", "Sī"), ("斯海", "Sī-hǎi-er"), ("斯A", "sī ā"), ("斯海", "sī(hǎi)")):
            with self.assertRaises(ValueError): aligned_pinyin(surface, reading, inventory, {})

    def test_ambiguous_alignment_is_proved_or_rejected(self):
        inventory = {"chang", "an", "chan", "gan"}
        with self.assertRaises(ValueError): aligned_pinyin("长安", "Chángān", inventory, {})
        self.assertEqual(("chang2", "an1"), aligned_pinyin("长安", "Chángān", inventory, {"长": {"chang"}, "安": {"an"}}))
        self.assertEqual(("chan2", "gan1"), aligned_pinyin("长安", "chán'gān", inventory, {}))

    def test_only_explicit_yi_bu_sandhi_is_normalized(self):
        self.assertEqual(("yi1", "bu4", "ni2", "hao3", "de5"),
                         dictionary_tones("一不你好的", ("yi4", "bu2", "ni2", "hao3", "de5")))
        with self.assertRaises(ValueError): dictionary_tones("你", ("ni3", "hao3"))

class OwnedSourceAdmissionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name) / "curated"
        shutil.copytree(ROOT / "data/curated", self.folder)

    def test_reviewed_entries_and_source_ids(self):
        rows = load_curated(self.folder)
        self.assertIn(("zh-CN", "着迷", "zhao2 mi2", 100, 1, "accompanist-common-polyphones"), rows)
        self.assertEqual(213, sum(r[0].startswith("zh") for r in rows))
        self.assertEqual(26, sum(r[0] == "ja-JP" for r in rows))
        self.assertIn(("ja-JP", "一日", "いちにち", 1000, 0, "accompanist-common-polyphones"), rows)
        self.assertIn(("ja-JP", "一日", "ついたち", 1000, 0, "accompanist-common-polyphones"), rows)
        # Adjacent validation material cannot become input through directory discovery.
        (self.folder.parent / "validation-only.tsv").write_text("not a dictionary", "utf-8")
        self.assertEqual(rows, load_curated(self.folder))

    def test_changed_content_is_rejected(self):
        with (self.folder / "common-polyphones.tsv").open("a", encoding="utf-8") as file:
            file.write("zh-CN\t未知\twei4 zhi1\t100\t1\n")
        with self.assertRaisesRegex(ValueError, "hash changed"):
            load_curated(self.folder)

    def test_validation_role_is_rejected(self):
        path = self.folder / "sources.json"
        manifest = json.loads(path.read_text("utf-8"))
        manifest["common-polyphones.tsv"]["role"] = "validation-only"
        path.write_text(json.dumps(manifest), "utf-8")
        with self.assertRaisesRegex(ValueError, "declaration"):
            load_curated(self.folder)

    def test_unregistered_file_is_rejected(self):
        (self.folder / "extra.tsv").write_text("not a dictionary", "utf-8")
        with self.assertRaisesRegex(ValueError, "Undeclared"):
            load_curated(self.folder)

if __name__ == "__main__":
    unittest.main()

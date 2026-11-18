#!/usr/bin/env python3
"""Small offline checks; run with python3 tools/test_generate_emoji_metadata.py."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    "emoji_metadata", Path(__file__).with_name("generate-emoji-metadata.py"))
metadata = importlib.util.module_from_spec(spec)
spec.loader.exec_module(metadata)


class EmojiMetadataTest(unittest.TestCase):
    def test_qualification_order_and_components(self):
        groups, entries, aliases = metadata.parse_catalog(
            "# group: Symbols\n# subgroup: heart\n"
            "2764 FE0F ; fully-qualified # ❤️ E0.6 red heart\n"
            "2764 ; unqualified # ❤ E0.6 red heart\n"
            "# group: Component\n# subgroup: skin-tone\n"
            "1F3FB ; component # 🏻 E1.0 light skin tone\n")
        self.assertEqual(groups, ["Symbols", "Component"])
        self.assertEqual([entry["sequence"] for entry in entries], ["❤️", "🏻"])
        self.assertEqual(aliases, [dict(sequence="❤", entry="❤️")])

    def test_complete_visible_glyph_required(self):
        visible = dict(g=7, w=10, h=-10, ax=10, ay=0)
        invisible = dict(g=2, w=0, h=0, ax=0, ay=0)
        missing = dict(g=0, w=0, h=0, ax=0, ay=0)
        self.assertTrue(metadata.supported([visible]))
        self.assertTrue(metadata.supported([visible, invisible]))
        self.assertFalse(metadata.supported([visible, missing]))
        self.assertFalse(metadata.supported([visible, visible]))
        self.assertFalse(metadata.supported([invisible]))
        self.assertFalse(metadata.supported([visible, dict(invisible, ax=10)]))

    def test_csv_mixed_and_directional_families(self):
        entries = [dict(sequence=value, base=value) for value in
                   ["🤝", "🫱🏻‍🫲🏼", "🚶‍➡️", "🚶🏻‍➡️"]]
        metadata.assign_families(entries,
            "hexcode,skintone_base_hexcode\n"
            "1FAF1-1F3FB-200D-1FAF2-1F3FC,1F91D\n"
            "1F6B6-1F3FB-200D-27A1-FE0F,1F6B6-200D-27A1-FE0F\n")
        self.assertEqual([entry["base"] for entry in entries],
                         ["🤝", "🤝", "🚶‍➡️", "🚶‍➡️"])

    def test_official_ranges_and_sequences(self):
        self.assertEqual(metadata.official_sequences(
            "# Header\n231A..231B ; Basic_Emoji ; watches\n"
            "2764 FE0F ; Basic_Emoji ; heart # comment\n"), {"⌚", "⌛", "❤️"})


if __name__ == "__main__":
    unittest.main()

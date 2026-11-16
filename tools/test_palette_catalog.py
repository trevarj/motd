import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("palette_catalog", SOURCE / "palette-catalog.py")
catalog = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(catalog)
FIXTURES = SOURCE.parent / "app/src/test/resources/palettes"


def palette(identifier="sample", revision=1):
    document = json.loads((FIXTURES / "duplicate-last-valid.motd-palette.json").read_text())
    document.update(id=identifier, revision=revision)
    return document


class PaletteCatalogTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "palettes").mkdir()

    def save(self, document, filename=None):
        target = self.root / "palettes" / document["id"] / (filename or f'{document["revision"]}.motd-palette.json')
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(catalog.encoded(document))
        return target

    def command(self, *args):
        output = io.StringIO()
        with contextlib.redirect_stderr(output):
            result = catalog.main(args, self.root)
        return result, output.getvalue()

    def git(self, *args):
        return subprocess.run(["git", "-C", str(self.root), *args], check=True, capture_output=True).stdout.decode().strip()

    def commit(self):
        self.git("add", ".")
        self.git("-c", "user.name=Test", "-c", "user.email=test@example.org", "commit", "-m", "fixture")
        return self.git("rev-parse", "HEAD")

    def test_empty_and_exact_index(self):
        self.assertEqual(self.command("--write")[0], 0)
        self.assertEqual(self.command("--check")[0], 0)
        index = self.root / "palettes/index.json"
        self.assertEqual(json.loads(index.read_bytes())["palettes"], [])
        index.write_bytes(index.read_bytes() + b" ")
        self.assertEqual(self.command("--check")[0], 1)

    def test_sorted_latest_revision_and_provenance(self):
        self.save(palette("zebra", 2))
        self.save(palette("alpha", 1))
        self.save(palette("zebra", 1))
        self.assertEqual(self.command("--write")[0], 0)
        data = (self.root / "palettes/index.json").read_bytes()
        entries = catalog.decode_catalog(data)
        self.assertEqual([(p["id"], p["revision"]) for p in entries], [("alpha", 1), ("zebra", 2)])
        self.assertEqual(entries[1]["notices"], palette()["notices"])
        self.assertEqual(self.command("--write")[0], 0)
        self.assertEqual(data, (self.root / "palettes/index.json").read_bytes())

    def test_shared_duplicate_fixtures(self):
        valid = catalog.decode_palette((FIXTURES / "duplicate-last-valid.motd-palette.json").read_bytes())
        self.assertEqual(valid["light"]["primary"], "#006C70")
        self.assertEqual(valid["revision"], 1)
        with self.assertRaises(ValueError):
            catalog.decode_palette((FIXTURES / "duplicate-last-invalid.motd-palette.json").read_bytes())

    def test_strict_schema(self):
        invalid = []
        for key, value in (("version", True), ("version", 1.0), ("version", 2), ("revision", True),
                           ("revision", 1.0), ("revision", 0), ("revision", 2147483648),
                           ("format", False), ("id", "../escape"), ("name", "\x80name"),
                           ("name", "x" * 65), ("license", "x" * 129), ("notices", "x" * 4097),
                           ("sourceUrl", "https://user@example.org/a"), ("sourceUrl", "http://example.org"),
                           ("sourceUrl", "https://example.org/%xx"), ("name", "\ud800"),
                           ("sourceUrl", "https://example.org../palette"),
                           ("dark", None), ("unknown", 1)):
            document = palette()
            document[key] = value
            invalid.append(document)
        for key, value in (("primary", "#1234"), ("primary", "#12345678"), ("primary", False),
                           ("unknown", "#123456"), ("nicknameColors", None),
                           ("nicknameColors", ["#123456"] * 25)):
            document = palette()
            document["light"][key] = value
            invalid.append(document)
        document = palette()
        del document["dark"]["surface"]
        invalid.append(document)
        for document in invalid:
            with self.subTest(document=document), self.assertRaises(ValueError):
                catalog.validate_palette(document)
        document = palette()
        document["sourceUrl"] = "https://example.org./palette"
        self.assertEqual(catalog.decode_palette(catalog.encoded(document))["sourceUrl"], document["sourceUrl"])
        for data in (b"\xff", b"NaN", b"[]", b" " * (catalog.PALETTE_LIMIT + 1)):
            with self.subTest(data=data[:10]), self.assertRaises(ValueError):
                catalog.decode_palette(data)

    def test_unicode_and_canonical_size(self):
        document = palette()
        document.update(name="  " + "\U0001f308" * 64 + "  ", license="\U0001f308" * 128)
        document["light"]["primary"] = "#abcdef"
        normalized = catalog.decode_palette(catalog.encoded(document))
        self.assertEqual(len(normalized["name"]), 64)
        self.assertEqual(normalized["light"]["primary"], "#ABCDEF")
        document["notices"] = "\U0001f308" * 4096
        with self.assertRaises(ValueError):
            catalog.validate_palette(document)
        document = palette()
        document["notices"] = "\x01" * 3000
        self.assertLess(len(catalog.encoded(document)), catalog.PALETTE_LIMIT + 10000)
        with self.assertRaises(ValueError):
            catalog.validate_palette(document)
        document = palette()
        del document["light"]["nicknameColors"]
        self.assertEqual(catalog.validate_palette(document)["light"]["nicknameColors"], [])
        document["light"] = None
        self.assertIsNone(catalog.decode_palette(catalog.encoded(document), require_both=False)["light"])

    def test_canonical_expansion_cannot_exceed_limit(self):
        document = palette()
        del document["sourceUrl"]
        del document["license"]
        for mode in ("light", "dark"):
            del document[mode]["nicknameColors"]
        document["notices"] = ""
        room = catalog.PALETTE_LIMIT - len(catalog.encoded(document))
        document["notices"] = "\U0001f308" * (room // 4) + "x" * (room % 4)
        raw = catalog.encoded(document)
        self.assertEqual(len(raw), catalog.PALETTE_LIMIT)
        self.assertLessEqual(len(document["notices"]), 4096)
        with self.assertRaises(ValueError):
            catalog.decode_palette(raw)

    def test_paths_and_symlinks(self):
        for filename in ("01.motd-palette.json", "2.motd-palette.json", "other.json"):
            path = self.save(palette(), filename)
            self.assertEqual(self.command("--write")[0], 1)
            path.unlink()
        external = self.root / "external.json"
        external.write_bytes(catalog.encoded(palette()))
        link = self.root / "palettes/sample/1.motd-palette.json"
        link.symlink_to(external)
        self.assertEqual(self.command("--write")[0], 1)
        link.unlink()
        (self.root / "palettes/index.json").symlink_to(external)
        self.assertEqual(self.command("--write")[0], 1)
        self.assertEqual(external.read_bytes(), catalog.encoded(palette()))

    def test_catalog_boundaries(self):
        entries = [palette("p-" + str(index)) for index in range(257)]
        root = dict(format="motd-palette-catalog", version=1, palettes=entries)
        with self.assertRaises(ValueError):
            catalog.decode_catalog(catalog.encoded(root))
        root["palettes"] = [palette(), palette()]
        with self.assertRaises(ValueError):
            catalog.decode_catalog(catalog.encoded(root))
        root["palettes"] = [palette()]
        root["version"] = True
        with self.assertRaises(ValueError):
            catalog.decode_catalog(catalog.encoded(root))
        with self.assertRaises(ValueError):
            catalog.decode_catalog(b" " * (catalog.CATALOG_LIMIT + 1))
        found = {(p["id"], 1): ("", catalog.validate_palette(p)) for p in entries}
        with self.assertRaises(ValueError):
            catalog.generate_index(found)
        for _, entry in found.values():
            entry["notices"] = "x" * 4096
        del found[("p-256", 1)]
        with self.assertRaises(ValueError):
            catalog.generate_index(found)

    def test_history_append_only_against_each_base(self):
        self.git("init")
        old_path = self.save(palette(revision=2))
        self.assertEqual(self.command("--write")[0], 0)
        first = self.commit()
        self.save(palette(revision=3))
        self.assertEqual(self.command("--write", "--base", first)[0], 0)
        second = self.commit()
        self.save(palette(revision=4))
        self.assertEqual(self.command("--write", "--base", first, "--base", second)[0], 0)
        original = old_path.read_bytes()
        old_path.write_bytes(original + b" ")
        self.assertEqual(self.command("--check", "--base", first)[0], 1)
        old_path.unlink()
        self.assertEqual(self.command("--check", "--base", first)[0], 1)
        old_path.write_bytes(original)
        low = self.save(palette(revision=1))
        self.assertEqual(self.command("--write", "--base", first)[0], 1)
        low.unlink()
        third = self.root / "palettes/sample/3.motd-palette.json"
        third.write_bytes(third.read_bytes() + b" ")
        self.assertEqual(self.command("--write", "--base", first)[0], 0)
        self.assertEqual(self.command("--write", "--base", first, "--base", second)[0], 1)

    def test_unavailable_history_still_validates(self):
        self.git("init")
        for bases in ((), ("--base", "0" * 40), ("--base", "absent")):
            result, output = self.command("--write", *bases)
            self.assertEqual(result, 0)
            self.assertIn("unproven", output)
            path = self.save(palette())
            path.write_text("{}")
            self.assertEqual(self.command("--check", *bases)[0], 1)
            path.unlink()


if __name__ == "__main__":
    unittest.main()

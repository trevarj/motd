#!/usr/bin/env python3
"""Author metadata: nix develop .#emoji-authoring --command python3 tools/generate-emoji-metadata.py --sources /tmp/motd-emoji-proof-mdfzmv45"""
import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
from urllib.request import urlopen

UNICODE_VERSION = "16.0"
NOTO_REV = "8998f5dd683424a73e2314a8c1f1e359c19e8742"
OPENMOJI_REV = "66e17da0f2d4347f64ee9d78c367fc5234283863"
TWEMOJI_REV = "388f518b756404152f128a3539f5e7f9a6f1ebd6"
FONT_IDS = ("noto", "openmoji", "twemoji")
# Every authoring input is content-pinned, including files already in the cache.
SOURCES = {
    "emoji-test-16.0.txt": (
        "https://unicode.org/Public/emoji/16.0/emoji-test.txt",
        "24f0c534e86cf142e2496953e8f0e46a3e702392911eddcd29c6cced85139697"),
    "emoji-sequences-16.0.txt": (
        "https://unicode.org/Public/emoji/16.0/emoji-sequences.txt",
        "3fe3c77e72e8f26df302dc7d99b106c5d08fd808ef7246fb5d4502d659fe659c"),
    "emoji-zwj-sequences-16.0.txt": (
        "https://unicode.org/Public/emoji/16.0/emoji-zwj-sequences.txt",
        "9423ec235474356f970a696506737e2d5d65453a67f45df66b8bbe920c3fab83"),
    "openmoji-16.0.csv": (
        f"https://raw.githubusercontent.com/hfg-gmuend/openmoji/{OPENMOJI_REV}/data/openmoji.csv",
        "af7a784e6a0dafb343c5e1958b159ca577c1faad6ab37add8a939f849f9a0303"),
    "noto.ttf": (
        f"https://raw.githubusercontent.com/googlefonts/noto-emoji/{NOTO_REV}/fonts/NotoColorEmoji.ttf",
        "72a635cb3d2f3524c51620cdde406b217204e8a6a06c6a096ff8ed4b5fd6e27b"),
    "openmoji.ttf": (
        f"https://raw.githubusercontent.com/hfg-gmuend/openmoji/{OPENMOJI_REV}/font/OpenMoji-color-cbdt/OpenMoji-color-cbdt.ttf",
        "de2cf500a9fe40c696205648300a2c00dcd6017c6d211909cb4b434472a7745d"),
    "twemoji.ttf": (
        "https://github.com/Nerixyz/twemoji-colr-font/releases/download/v0.0.2/Twemoji_GlyfColr0.ttf",
        "0a75e5744aba8ba9692bf814522d24f85b4846e9619d26bbacebc2af386d385a"),
    "twemoji-license.txt": (
        f"https://raw.githubusercontent.com/jdecked/twemoji/{TWEMOJI_REV}/LICENSE-GRAPHICS",
        "8ae9438818c26e4873b91d8c6ad620526c011e27e125677f13031eda903f007c"),
    "unicode-license.txt": (
        "https://www.unicode.org/license.txt",
        "e7a93b009565cfce55919a381437ac4db883e9da2126fa28b91d12732bc53d96"),
    "openmoji-license.txt": (
        f"https://raw.githubusercontent.com/hfg-gmuend/openmoji/{OPENMOJI_REV}/LICENSE.txt",
        "5e436ff8ffbb77d8607220e9bce20c8915d860010feeb6c1ebef5a85688e9b39"),
    "noto-license.txt": (
        f"https://raw.githubusercontent.com/googlefonts/noto-emoji/{NOTO_REV}/fonts/LICENSE",
        "6a73f9541c2de74158c0e7cf6b0a58ef774f5a780bf191f2d7ec9cc53efe2bf2"),
}
SHAPE_OPTIONS = [
    "--shapers=ot", "--direction=ltr", "--language=en", "--script=Zyyy",
    "--remove-default-ignorables", "--no-glyph-names", "--show-extents",
    "--output-format=json",
]


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sequence(hexcode):
    return "".join(chr(int(code, 16)) for code in hexcode.replace("-", " ").split())


def unqualified(text):
    return text.replace("\ufe0f", "")


def parse_catalog(text):
    groups, entries, aliases = [], [], []
    group = subgroup = None
    canonical = {}
    rows = []
    for line in text.splitlines():
        if line.startswith("# group: "):
            group = line.removeprefix("# group: ")
            groups.append(group)
        elif line.startswith("# subgroup: "):
            subgroup = line.removeprefix("# subgroup: ")
        elif line and not line.startswith("#"):
            match = re.fullmatch(r"([0-9A-F ]+);\s*([a-z-]+)\s*# \S+ E[0-9.]+ (.+)", line)
            require(match is not None, f"Invalid emoji-test row: {line}")
            codes, status, name = match.groups()
            value = sequence(codes)
            require(group is not None and subgroup is not None, "Missing Unicode category")
            rows.append((value, status, name, group, subgroup))
            if status in ("fully-qualified", "component"):
                key = unqualified(value)
                require(key not in canonical, "Ambiguous canonical qualification")
                canonical[key] = value
    for value, status, name, group, subgroup in rows:
        if status in ("fully-qualified", "component"):
            entries.append(dict(sequence=value, name=name, group=group, subgroup=subgroup, base=value))
        else:
            require(status in ("minimally-qualified", "unqualified"), f"Unknown status: {status}")
            aliases.append(dict(sequence=value, entry=canonical[unqualified(value)]))
    return groups, entries, aliases


def official_sequences(text):
    values = set()
    for line in text.splitlines():
        codes = line.split("#", 1)[0].split(";", 1)[0].strip()
        if not codes:
            continue
        if ".." in codes:
            start, end = codes.split("..")
            values.update(chr(code) for code in range(int(start, 16), int(end, 16) + 1))
        else:
            values.add(sequence(codes))
    return values


def assign_families(entries, csv_text):
    canonical = {unqualified(entry["sequence"]): entry["sequence"] for entry in entries}
    bases = {}
    for row in csv.DictReader(io.StringIO(csv_text)):
        if row["skintone_base_hexcode"]:
            key = unqualified(sequence(row["hexcode"]))
            base = unqualified(sequence(row["skintone_base_hexcode"]))
            if key in canonical:
                require(base in canonical, f"Missing family root: {base!r}")
                bases[key] = canonical[base]
    for entry in entries:
        entry["base"] = bases.get(unqualified(entry["sequence"]), entry["sequence"])
        if any(0x1F3FB <= ord(char) <= 0x1F3FF for char in entry["sequence"]) and len(entry["sequence"]) > 1:
            require(entry["base"] != entry["sequence"], "Missing skin-tone family")
    by_sequence = {entry["sequence"]: entry for entry in entries}
    require(all(by_sequence[entry["base"]]["base"] == entry["base"] for entry in entries), "Non-root family base")


def supported(glyphs):
    # HarfBuzz removes default-ignorable output after shaping, not before ligation.
    # Reject .notdef even if it has zero advance, and require a visible single glyph.
    if any(glyph["g"] == 0 for glyph in glyphs):
        return False
    visible = [glyph for glyph in glyphs if glyph["w"] != 0 and glyph["h"] != 0]
    return len(visible) == 1 and all(
        glyph in visible or (glyph["ax"] == 0 and glyph["ay"] == 0) for glyph in glyphs
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sources", type=Path, required=True, help="External download cache; never committed")
    parser.add_argument("--output", type=Path, default=Path("app/src/main/assets/emoji"))
    args = parser.parse_args()
    args.sources.mkdir(parents=True, exist_ok=True)
    inputs, provenance = {}, {}
    for name, (url, digest) in SOURCES.items():
        path = args.sources / name
        if not path.exists():
            data = urlopen(url, timeout=120).read()
            require(sha256(data) == digest, f"Downloaded SHA-256 mismatch: {name}")
            path.write_bytes(data)
        data = path.read_bytes()
        require(sha256(data) == digest, f"Cached SHA-256 mismatch: {name}")
        inputs[name] = data
        provenance[name] = dict(url=url, sha256=digest, sizeBytes=len(data))
    groups, entries, aliases = parse_catalog(inputs["emoji-test-16.0.txt"].decode("utf-8"))
    official = official_sequences(inputs["emoji-sequences-16.0.txt"].decode("utf-8"))
    official |= official_sequences(inputs["emoji-zwj-sequences-16.0.txt"].decode("utf-8"))
    require(official == {entry["sequence"] for entry in entries}, "Official sequence crosscheck differs")
    assign_families(entries, inputs["openmoji-16.0.csv"].decode("utf-8"))
    rows = entries + aliases
    for row in rows:
        row["support"] = []
    hb = shutil.which("hb-shape")
    require(hb is not None, "Use nix develop .#emoji-authoring for pinned hb-shape")
    payload = "\n".join(row["sequence"] for row in rows) + "\n"
    for set_id in FONT_IDS:
        result = subprocess.run(
            [hb, str(args.sources / f"{set_id}.ttf"), *SHAPE_OPTIONS],
            input=payload, text=True, encoding="utf-8", capture_output=True, check=True,
        )
        shaped = result.stdout.splitlines()
        require(len(shaped) == len(rows), "HarfBuzz row count differs")
        for row, glyphs in zip(rows, shaped):
            if supported(json.loads(glyphs)):
                row["support"].append(set_id)
    root = Path(__file__).resolve().parent.parent
    tools = {
        "harfbuzz": dict(
            version=subprocess.check_output([hb, "--version"], text=True).strip(),
            sha256=sha256(Path(hb).read_bytes()), options=SHAPE_OPTIONS,
        ),
        "python": dict(version=sys.version, sha256=sha256(Path(sys.executable).read_bytes())),
        "generatorSha256": sha256(Path(__file__).read_bytes()),
        "flakeLockSha256": sha256((root / "flake.lock").read_bytes()),
        "nixpkgsRevision": "767b0d3ec98a143ad9ed7dfc0d5553510ac27133",
    }
    catalog = dict(unicodeVersion=UNICODE_VERSION, provenance=dict(sources=provenance, tools=tools),
                   groups=groups, entries=entries, aliases=aliases)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "catalog.json").write_text(
        json.dumps(catalog, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    (args.output / "attribution.txt").write_text(
        "Emoji metadata attribution and legal sources\n\n"
        "Unicode Emoji 16.0 data: Copyright Unicode, Inc.\n"
        "Unicode License v3: https://www.unicode.org/license.txt\n"
        "Data: https://unicode.org/Public/emoji/16.0/\n\n"
        "OpenMoji 16.0 metadata and downloadable artwork/font: OpenMoji contributors,\n"
        "HfG Schwaebisch Gmuend. Licensed CC BY-SA 4.0.\n"
        "https://openmoji.org/\n"
        f"https://github.com/hfg-gmuend/openmoji/blob/{OPENMOJI_REV}/LICENSE.txt\n"
        "https://creativecommons.org/licenses/by-sa/4.0/\n"
        "The bundled family mapping is derived from OpenMoji's skintone_base_hexcode;\n"
        "names, categories, ordering and qualification variants come from Unicode.\n\n"
        "Downloadable Noto Color Emoji 2.051: Google and Noto Emoji contributors.\n"
        "SIL Open Font License 1.1.\n"
        f"https://github.com/googlefonts/noto-emoji/blob/{NOTO_REV}/fonts/LICENSE\n\n"
        "Downloadable Twemoji 17.0.2 artwork: Copyright Twitter, Inc. and other\n"
        "contributors; maintained by jdecked and Twemoji contributors. CC BY 4.0.\n"
        f"Artwork source: https://github.com/jdecked/twemoji/tree/{TWEMOJI_REV}\n"
        f"https://github.com/jdecked/twemoji/blob/{TWEMOJI_REV}/LICENSE-GRAPHICS\n"
        "https://creativecommons.org/licenses/by/4.0/\n"
        "Artwork converted to a TrueType glyf/COLRv0 font by Nerixyz;\n"
        "twemoji-colr-font v0.0.2 (Twemoji_GlyfColr0.ttf).\n"
        "https://github.com/Nerixyz/twemoji-colr-font/releases/tag/v0.0.2\n"
        "This is a converted artwork font, not the original SVG distribution.\n\n"
        "No fonts or artwork are bundled. catalog.json records exact source, font and\n"
        "authoring-tool hashes. Support means one visible nonzero HarfBuzz glyph for\n"
        "the complete sequence, not proof of Android color rendering compatibility.\n"
        + "\n\n".join(
            f"===== {name} =====\n" + "\n".join(
                line.rstrip() for line in inputs[name].decode("utf-8").split("\n")
            )
            for name in ("unicode-license.txt", "openmoji-license.txt", "noto-license.txt", "twemoji-license.txt")
        ),
        encoding="utf-8",
    )
    print(f"Unicode {UNICODE_VERSION}: {len(groups)} groups, {len(entries)} entries, {len(aliases)} aliases")
    print(f"Family roots: {sum(entry['base'] == entry['sequence'] for entry in entries)}")
    for set_id in FONT_IDS:
        print(f"{set_id}: {sum(set_id in row['support'] for row in entries)} entries, "
              f"{sum(set_id in row['support'] for row in aliases)} aliases")


if __name__ == "__main__":
    main()

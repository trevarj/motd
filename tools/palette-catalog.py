#!/usr/bin/env python3
import argparse
import ipaddress
import json
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import urlsplit

PALETTE_LIMIT = 16 * 1024
CATALOG_LIMIT = 1024 * 1024
ROLES = ("primary", "secondary", "tertiary", "background", "surface", "surfaceContainer",
         "onBackground", "onSurface", "onSurfaceVariant", "outlineVariant")
ID = re.compile(r"[a-z0-9][a-z0-9-]{0,63}\Z")
COLOR = re.compile(r"#[0-9A-Fa-f]{6}\Z")
REVISION_PATH = re.compile(r"palettes/([a-z0-9][a-z0-9-]{0,63})/([1-9][0-9]*)\.motd-palette\.json\Z")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def scalar_string(value):
    require(type(value) is str, "Expected string")
    require(not any(0xD800 <= ord(c) <= 0xDFFF for c in value), "Invalid Unicode scalar")
    return value


def control(c):
    return ord(c) < 32 or 127 <= ord(c) <= 159


def encoded(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")


def parse(data, limit):
    require(len(data) <= limit, "Input exceeds byte limit")
    try:
        return json.loads(data.decode("utf-8"), parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Invalid number")))
    except (UnicodeError, json.JSONDecodeError, RecursionError) as error:
        raise ValueError("Invalid UTF-8 JSON") from error


def https_url(value):
    scalar_string(value)
    require(len(value) <= 2048 and not any(control(c) for c in value), "Invalid HTTPS URL")
    require(not re.search(r'[\s<>"{}|\\^`]', value) and not re.search(r"%(?![0-9A-Fa-f]{2})", value), "Invalid HTTPS URL")
    try:
        uri = urlsplit(value)
        require("[" not in uri.path and "]" not in uri.path and "#" not in uri.fragment, "Invalid HTTPS URL")
        host = uri.hostname
        require(uri.scheme.lower() == "https" and host and uri.username is None and uri.password is None, "Invalid HTTPS URL")
        require(host.isascii(), "Invalid HTTPS host")
        if ":" not in host:
            labels = host.removesuffix(".").split(".")
            require(all(re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?", label) for label in labels), "Invalid HTTPS host")
            if len(labels) > 1 and not labels[-1][0].isalpha():
                ipaddress.IPv4Address(host)
        authority = uri.netloc
        port = authority.split("]", 1)[1] if authority.startswith("[") else authority[len(host):]
        require(not port or port == ":" or (port.startswith(":") and port[1:].isascii() and port[1:].isdigit() and int(port[1:]) <= 2147483647), "Invalid HTTPS port")
    except ValueError as error:
        raise ValueError("Invalid HTTPS URL") from error


def validate_palette(root, require_both=True):
    require(type(root) is dict, "Palette must be an object")
    allowed = {"id", "name", "revision", "light", "dark", "sourceUrl", "license", "notices", "format", "version"}
    require(not root.keys() - allowed, "Unknown palette keys")
    require(root.get("format") == "motd-palette", "Not a MOTD palette")
    require(type(root.get("version")) is int and root["version"] == 1, "Unsupported palette version")
    identifier = scalar_string(root.get("id"))
    require(ID.fullmatch(identifier), "Invalid palette ID")
    name = scalar_string(root.get("name"))
    require(not any(control(c) for c in name) and 1 <= len(name.strip()) <= 64, "Invalid palette name")
    revision = root.get("revision")
    require(type(revision) is int and 1 <= revision <= 2147483647, "Invalid palette revision")
    result = {"id": identifier, "name": name.strip(), "revision": revision}
    for mode in ("light", "dark"):
        colors = root.get(mode)
        if colors is None:
            result[mode] = None
            continue
        require(type(colors) is dict and not colors.keys() - set(ROLES) - {"nicknameColors"}, "Invalid mode keys")
        normalized = {}
        for role in ROLES:
            color = scalar_string(colors.get(role))
            require(COLOR.fullmatch(color), "Invalid or missing color role")
            normalized[role] = color.upper()
        nicknames = colors.get("nicknameColors", [])
        require(type(nicknames) is list and len(nicknames) <= 24, "Invalid nickname colors")
        for color in nicknames:
            require(COLOR.fullmatch(scalar_string(color)), "Invalid nickname color")
        normalized["nicknameColors"] = [color.upper() for color in nicknames]
        result[mode] = normalized
    require(result["light"] is not None or result["dark"] is not None, "Palette needs a mode")
    require(not require_both or (result["light"] is not None and result["dark"] is not None), "Community palette needs both modes")
    for key, limit in (("sourceUrl", 2048), ("license", 128), ("notices", 4096)):
        value = root.get(key)
        if value is not None:
            scalar_string(value)
            require(len(value) <= limit, "Oversize " + key)
            if key == "sourceUrl":
                https_url(value)
        result[key] = value
    result.update(format="motd-palette", version=1)
    require(len(encoded(result)) <= PALETTE_LIMIT, "Canonical palette exceeds byte limit")
    return result


def decode_palette(data, require_both=True):
    return validate_palette(parse(data, PALETTE_LIMIT), require_both)


def decode_catalog(data):
    root = parse(data, CATALOG_LIMIT)
    require(type(root) is dict and set(root) == {"format", "version", "palettes"}, "Invalid catalog keys")
    require(root["format"] == "motd-palette-catalog" and type(root["version"]) is int and root["version"] == 1, "Unsupported catalog")
    entries = root["palettes"]
    require(type(entries) is list and len(entries) <= 256, "Invalid catalog entries")
    palettes = [validate_palette(entry) for entry in entries]
    require(len({p["id"] for p in palettes}) == len(palettes), "Duplicate catalog ID")
    return palettes


def read_bounded(path, limit):
    with path.open("rb") as stream:
        return stream.read(limit + 1)


def revisions(repo):
    directory = repo / "palettes"
    require(directory.is_dir() and not directory.is_symlink(), "Missing or symlinked palettes directory")
    found = {}
    for path in sorted(directory.rglob("*")):
        require(not path.is_symlink(), "Symlinks are not allowed: " + str(path))
        require(path.resolve().is_relative_to(directory.resolve()), "Palette path escapes catalog")
        if path.is_dir():
            require(path.parent == directory and ID.fullmatch(path.name), "Invalid palette directory")
            continue
        if path == directory / "index.json":
            continue
        relative = path.relative_to(repo).as_posix()
        match = REVISION_PATH.fullmatch(relative)
        require(match is not None, "Invalid revision path: " + relative)
        palette = decode_palette(read_bounded(path, PALETTE_LIMIT))
        require(palette["id"] == match[1] and str(palette["revision"]) == match[2], "Revision path does not match document")
        pair = (palette["id"], palette["revision"])
        require(pair not in found, "Duplicate ID/revision")
        found[pair] = (relative, palette)
    return found


def generate_index(found):
    latest = {}
    for (identifier, revision), (_, palette) in found.items():
        if identifier not in latest or revision > latest[identifier]["revision"]:
            latest[identifier] = palette
    require(len(latest) <= 256, "Catalog exceeds 256 entries")
    data = encoded({"format": "motd-palette-catalog", "version": 1,
                    "palettes": [latest[key] for key in sorted(latest)]}) + b"\n"
    require(len(data) <= CATALOG_LIMIT, "Catalog exceeds 1 MiB")
    return data


def git(repo, *args):
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, check=True).stdout


def check_history(repo, base, found):
    if not base or re.fullmatch("0+", base):
        return False
    try:
        commit = git(repo, "rev-parse", "--verify", "--end-of-options", base + "^{commit}").decode().strip()
    except subprocess.CalledProcessError:
        return False
    files = git(repo, "ls-tree", "-rz", commit, "--", "palettes/").split(b"\0")
    previous = {}
    published = set()
    for entry in filter(None, files):
        metadata, raw_path = entry.split(b"\t", 1)
        path = raw_path.decode("utf-8")
        if not path.endswith(".motd-palette.json"):
            continue
        match = REVISION_PATH.fullmatch(path)
        require(match is not None and metadata.startswith((b"100644 blob ", b"100755 blob ")), "Invalid published revision path")
        identifier, revision = match[1], int(match[2])
        pair = (identifier, revision)
        require(pair in found and found[pair][0] == path, "Published revision deleted: " + path)
        require((repo / path).read_bytes() == git(repo, "show", commit + ":" + path), "Published revision modified: " + path)
        previous[identifier] = max(previous.get(identifier, 0), revision)
        published.add(pair)
    for pair in found.keys() - published:
        require(pair[1] > previous.get(pair[0], 0), "New revision must exceed published maximum: " + found[pair][0])
    return True


def main(argv=None, repo=None):
    parser = argparse.ArgumentParser()
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--write", action="store_true")
    action.add_argument("--check", action="store_true")
    parser.add_argument("--base", action="append", default=[])
    args = parser.parse_args(argv)
    repo = Path(repo) if repo is not None else Path(__file__).resolve().parent.parent
    try:
        found = revisions(repo)
        data = generate_index(found)
        for base in args.base or [None]:
            proven = check_history(repo, base, found)
            print("History " + ("validated against " + str(base) if proven else "unproven (base unavailable: " + str(base) + ")"), file=sys.stderr)
        index = repo / "palettes/index.json"
        if args.write:
            index.write_bytes(data)
        else:
            actual = read_bounded(index, CATALOG_LIMIT)
            decode_catalog(actual)
            require(actual == data, "Index differs from generated output; run --write")
        return 0
    except (ValueError, OSError, subprocess.CalledProcessError, RecursionError) as error:
        print("Palette catalog: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

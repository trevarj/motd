#!/usr/bin/env python3
"""Provision only the source archives in tts-sources.lock.json; no binary downloads."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import tarfile
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent
LOCK = ROOT / "tts-sources.lock.json"


def digest(path, algorithm="sha256"):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def checked(path, entry):
    if not path.is_file() or path.stat().st_size != entry["size"]:
        raise RuntimeError(f"Missing or wrong-size source archive: {path}")
    for algorithm in ("sha256", "sha1"):
        if algorithm in entry and digest(path, algorithm) != entry[algorithm]:
            raise RuntimeError(f"{algorithm} mismatch: {path}")


def tree_hashes(directory):
    result = {}
    for path in sorted(directory.rglob("*")):
        relative = path.relative_to(directory).as_posix()
        if path.is_symlink():
            if not path.resolve().is_relative_to(directory.resolve()):
                raise RuntimeError(f"Escaping source symlink: {path}")
            result[relative] = "link:" + os.readlink(path)
        elif path.is_file():
            result[relative] = digest(path)
    return result


def verify_licenses(source):
    licenses = json.loads((ROOT / "tts-licenses.lock.json").read_text())
    archives = json.loads(LOCK.read_text())["archives"]
    components = {f'{entry["group"]}/{entry["name"]}' for entry in archives}
    if {entry["component"] for entry in licenses["components"]} != components:
        raise RuntimeError("The license lock does not cover the complete enabled source closure")
    for component in licenses["components"]:
        for entry in component["files"]:
            if digest(source / entry["path"]) != entry["sha256"]:
                raise RuntimeError(f'Missing or changed upstream license: {entry["path"]}')
    for entry in licenses["supplemental"]:
        if digest(ROOT / entry["path"]) != entry["sha256"]:
            raise RuntimeError(f'Missing or changed supplemental license: {entry["path"]}')


def verify_source(source, lock_hash):
    manifest = json.loads((source / ".prepared.json").read_text())
    if manifest["lock_sha256"] != lock_hash:
        raise RuntimeError("Prepared TTS sources do not match the source lock; remove the stale source cache")
    expected = {f'{entry["group"]}/{entry["name"]}' for entry in json.loads(LOCK.read_text())["archives"]}
    if set(manifest["trees"]) != expected:
        raise RuntimeError("Prepared sources omit part of the enabled source closure")
    for relative, hashes in manifest["trees"].items():
        if tree_hashes(source / relative) != hashes:
            raise RuntimeError(f"Prepared source tree was modified: {relative}")
    verify_licenses(source)
    return manifest


def extract(archive, destination):
    destination.mkdir(parents=True)
    if zipfile.is_zipfile(archive):
        with zipfile.ZipFile(archive) as bundle:
            for member in bundle.infolist():
                path = PurePosixPath(member.filename)
                if path.is_absolute() or ".." in path.parts:
                    raise RuntimeError(f"Unsafe source archive entry: {member.filename}")
            for member in bundle.infolist():
                target = destination / member.filename
                if (member.external_attr >> 16) & 0o170000 == 0o120000:
                    link = bundle.read(member).decode("utf-8")
                    if not (target.parent / link).resolve().is_relative_to(destination.resolve()):
                        raise RuntimeError(f"Escaping ZIP symlink: {member.filename}")
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.symlink_to(link)
                else:
                    if not target.resolve().is_relative_to(destination.resolve()):
                        raise RuntimeError(f"Escaping ZIP entry: {member.filename}")
                    bundle.extract(member, destination)
                    mode = (member.external_attr >> 16) & 0o777
                    if mode and not member.is_dir():
                        target.chmod(mode)
    else:
        def source_filter(member, target):
            # Pinned upstream has two unusable developer-machine links in disabled Go examples.
            excluded = "scripts/go/_internal/vad-spoken-language-identification/"
            if member.issym() and member.name.split("/", 1)[-1] in (excluded + "main.go", excluded + "run.sh"):
                return None
            return tarfile.data_filter(member, target)

        with tarfile.open(archive) as bundle:
            bundle.extractall(destination, filter=source_filter)
    children = list(destination.iterdir())
    if len(children) != 1 or not children[0].is_dir():
        raise RuntimeError(f"Source archive must have one root directory: {archive}")
    return children[0]


def prepare(args):
    entries = json.loads(LOCK.read_text())["archives"]
    lock_hash = digest(LOCK)
    pins = dict(line.split("=", 1) for line in (ROOT / "source.lock").read_text().splitlines() if "=" in line)
    for name, pin in (("sherpa-onnx", "SHERPA_ONNX_COMMIT"), ("onnxruntime", "ONNXRUNTIME_COMMIT")):
        if next(entry["commit"] for entry in entries if entry["group"] == "top" and entry["name"] == name) != pins[pin]:
            raise RuntimeError(f"Top-level archive and source.lock disagree: {name}")
    source = args.source.resolve()
    if source.exists():
        verify_source(source, lock_hash)
        print(f"Verified {len(entries)} prepared TTS source trees: {source}")
        return
    cache = args.cache.resolve()
    for entry in entries:
        archive = cache / entry["archive"]
        if not archive.exists():
            seed = None
            if args.seed_cache:
                candidate = args.seed_cache / entry["archive"]
                if candidate.exists():
                    seed = candidate
            if args.seed_top and entry["group"] == "top":
                candidate = args.seed_top / ("motd-" + archive.name)
                if candidate.exists():
                    seed = candidate
            archive.parent.mkdir(parents=True, exist_ok=True)
            temporary = archive.with_suffix(archive.suffix + ".partial")
            try:
                if seed:
                    checked(seed, entry)
                    shutil.copyfile(seed, temporary)
                elif args.fetch:
                    with urllib.request.urlopen(entry["url"], timeout=120) as response, temporary.open("wb") as output:
                        shutil.copyfileobj(response, output)
                else:
                    raise RuntimeError(f"Missing checked input: {archive}; seed the cache or explicitly use --fetch")
                checked(temporary, entry)
                temporary.replace(archive)
            finally:
                temporary.unlink(missing_ok=True)
        checked(archive, entry)
    source.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="tts-source-", dir=source.parent) as temporary:
        stage = Path(temporary) / "source"
        stage.mkdir()
        trees = {}
        for entry in entries:
            relative = f'{entry["group"]}/{entry["name"]}'
            unpack = Path(temporary) / "unpack"
            root = extract(cache / entry["archive"], unpack)
            destination = stage / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            root.rename(destination)
            unpack.rmdir()
            trees[relative] = tree_hashes(destination)
        (stage / ".prepared.json").write_text(json.dumps({"lock_sha256": lock_hash, "trees": trees}, sort_keys=True) + "\n")
        verify_licenses(stage)
        stage.rename(source)
    print(f"Checked and extracted {len(entries)} SHA256-locked source archives: {source}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=ROOT / "tts/cache")
    parser.add_argument("--source", type=Path, default=ROOT / "tts/source")
    parser.add_argument("--seed-cache", type=Path)
    parser.add_argument("--seed-top", type=Path)
    parser.add_argument("--fetch", action="store_true", help="Explicitly fetch missing locked SOURCE archives")
    parser.add_argument("--kotlin-output", type=Path)
    parser.add_argument("--licenses-output", type=Path)
    args = parser.parse_args()
    prepare(args)
    if args.kotlin_output:
        output = args.kotlin_output / "com/k2fsa/sherpa/onnx/Tts.kt"
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(args.source / "top/sherpa-onnx/sherpa-onnx/kotlin-api/Tts.kt", output)
    if args.licenses_output:
        licenses = json.loads((ROOT / "tts-licenses.lock.json").read_text())
        directory = args.licenses_output / "META-INF/motd-ai-tts-licenses"
        for component in licenses["components"]:
            for entry in component["files"]:
                output = directory / entry["path"]
                output.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(args.source / entry["path"], output)
        for entry in licenses["supplemental"]:
            output = directory / entry["path"]
            output.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / entry["path"], output)

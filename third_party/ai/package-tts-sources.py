#!/usr/bin/env python3
"""Copy checked TTS corresponding source without optional weights or precompiled fixtures."""
import argparse
import importlib.util
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("tts_sources", ROOT / "prepare-tts-sources.py")
PREPARE = importlib.util.module_from_spec(spec)
spec.loader.exec_module(PREPARE)
PAYLOAD_SUFFIXES = (".gguf", ".ggml", ".bin", ".safetensors", ".ckpt", ".pt", ".pth", ".onnx", ".ort",
                    ".tflite", ".mlmodel", ".h5", ".hdf5", ".npy", ".npz", ".weights", ".pb", ".tensor",
                    ".engine", ".blob", ".so", ".a", ".dll", ".exe", ".aar", ".jar", ".whl", ".wasm",
                    ".zip", ".tar", ".tar.gz", ".tgz", ".tar.bz2", ".tar.xz", ".7z")


def payload(path):
    if path.name.lower().endswith(PAYLOAD_SUFFIXES):
        return True
    if path.is_file() and not path.is_symlink():
        with path.open("rb") as stream:
            magic = stream.read(8)
        if magic.startswith((b"\x7fELF", b"MZ", b"!<arch>\n")):
            return True
    return False


def package(destination):
    source = ROOT / "tts/source"
    manifest = PREPARE.verify_source(source, PREPARE.digest(ROOT / "tts-sources.lock.json"))
    if destination.exists():
        raise RuntimeError(f"Corresponding-source destination already exists: {destination}")
    removed = []
    def ignore(directory, names):
        excluded = []
        for name in names:
            path = Path(directory) / name
            if payload(path):
                excluded.append(name)
                removed.append(path.relative_to(source).as_posix())
        return excluded
    shutil.copytree(source, destination, symlinks=True, ignore=ignore)
    PREPARE.verify_licenses(destination)
    # Pruned entries are disabled model/test/binary payloads, not runtime build inputs.
    manifest["trees"] = {relative: PREPARE.tree_hashes(destination / relative) for relative in manifest["trees"]}
    manifest["excluded_optional_payloads"] = sorted(removed)
    (destination / ".prepared.json").write_text(json.dumps(manifest, sort_keys=True) + "\n")
    PREPARE.verify_source(destination, PREPARE.digest(ROOT / "tts-sources.lock.json"))
    print(f"Packaged {len(manifest['trees'])} checked TTS source trees and all locked licenses; excluded {len(removed)} optional payloads")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path)
    package(parser.parse_args().destination)

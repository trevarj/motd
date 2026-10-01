#!/usr/bin/env python3
"""Verify the exact two source-built Android TTS libraries, private linkage and 16 KiB ELF pages."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

LIBRARIES = {"libonnxruntime.so", "libsherpa-onnx-jni.so"}
SYSTEM_LIBRARIES = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so"}


def readelf(tool, path, *options):
    return subprocess.check_output([tool, *options, path], text=True)


def verify_library(tool, path, abi):
    header = readelf(tool, path, "--file-header")
    machine = "AArch64" if abi == "arm64-v8a" else "Advanced Micro Devices X86-64"
    if "ELF64" not in header or machine not in header:
        raise RuntimeError(f"Wrong ELF ABI: {path}")
    dynamic = readelf(tool, path, "--dynamic")
    sonames = re.findall(r"\(SONAME\).*?\[([^\]]+)\]", dynamic)
    if sonames != [path.name]:
        raise RuntimeError(f"Android SONAME must match packaged unversioned filename: {path}: {sonames}")
    needed = set(re.findall(r"\(NEEDED\).*?\[([^\]]+)\]", dynamic))
    allowed = SYSTEM_LIBRARIES | ({"libonnxruntime.so"} if path.name == "libsherpa-onnx-jni.so" else set())
    if not needed <= allowed:
        raise RuntimeError(f"Missing/versioned/shared-private dependency: {path}: {sorted(needed - allowed)}")
    if path.name == "libsherpa-onnx-jni.so" and "libonnxruntime.so" not in needed:
        raise RuntimeError(f"JNI did not link the checked shared ORT: {path}")
    if "Build ID:" in readelf(tool, path, "--notes"):
        raise RuntimeError(f"Non-reproducible build ID: {path}")
    segments = readelf(tool, path, "--program-headers", "--wide")
    load_lines = [line.split() for line in segments.splitlines() if line.strip().startswith("LOAD ")]
    if not load_lines or any(int(fields[-1], 0) < 16384 for fields in load_lines):
        raise RuntimeError(f"ELF LOAD segments are not 16 KiB aligned: {path}")
    exports = set()
    for line in readelf(tool, path, "--dyn-syms", "--wide").splitlines():
        fields = line.split()
        if len(fields) >= 8 and fields[0].endswith(":") and fields[4] in ("GLOBAL", "WEAK") and fields[5] == "DEFAULT" and fields[6] not in ("UND", "ABS"):
            exports.add(fields[7].split("@")[0])
    if path.name == "libonnxruntime.so":
        expected = {"OrtGetApiBase", "OrtSessionOptionsAppendExecutionProvider_CPU"}
        if exports != expected:
            raise RuntimeError(f"ORT exposed private/provider symbols: {path}: {sorted(exports)}")
    elif not exports or any(not symbol.startswith("Java_com_k2fsa_sherpa_onnx") for symbol in exports):
        raise RuntimeError(f"JNI exposed static dependency symbols: {path}: {sorted(exports)}")
    print(f"Verified {abi}/{path.name}: SONAME={sonames[0]}, NEEDED={','.join(sorted(needed))}, hidden statics, no build ID, 16 KiB LOAD")


def verify_directory(tool, directory, abis):
    for abi in abis:
        folder = directory / abi
        files = {path.name for path in folder.iterdir() if path.is_file()}
        if files != LIBRARIES:
            raise RuntimeError(f"Expected exactly {sorted(LIBRARIES)} in {folder}, got {sorted(files)}")
        for name in sorted(LIBRARIES):
            verify_library(tool, folder / name, abi)


def main(args):
    ndk = args.ndk or Path(os.environ["ANDROID_NDK_HOME"])
    tool = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
    if not tool.is_file():
        raise RuntimeError(f"Missing pinned NDK ELF inspector: {tool}")
    abis = (args.abi,) if args.abi else ("arm64-v8a", "x86_64")
    if args.directory:
        verify_directory(tool, args.directory, abis)
    else:
        with tempfile.TemporaryDirectory(prefix="tts-elf-") as temporary, zipfile.ZipFile(args.archive) as archive:
            root = Path(temporary)
            prefix = "lib" if args.abi else "jni"
            expected = {f"{prefix}/{abi}/{name}" for abi in abis for name in LIBRARIES}
            found = {entry.filename for entry in archive.infolist() if not entry.is_dir() and entry.filename.startswith(prefix + "/") and entry.filename.split("/")[-1] in LIBRARIES}
            if found != expected:
                raise RuntimeError(f"Missing TTS native entries: expected {sorted(expected)}, got {sorted(found)}")
            for entry in sorted(expected):
                destination = root / entry.removeprefix(prefix + "/")
                destination.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(entry) as source, destination.open("wb") as output:
                    import shutil
                    shutil.copyfileobj(source, output)
            verify_directory(tool, root, abis)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument("--directory", type=Path)
    inputs.add_argument("--archive", type=Path)
    parser.add_argument("--abi", choices=("arm64-v8a", "x86_64"), help="APK's single ABI; omit for both-ABI AAR")
    parser.add_argument("--ndk", type=Path)
    main(parser.parse_args())

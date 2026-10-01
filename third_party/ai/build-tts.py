#!/usr/bin/env python3
"""Build pinned CPU ORT and Sherpa in separate CMake graphs (Android or Linux host)."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys

import importlib.util

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("tts_sources", ROOT / "prepare-tts-sources.py")
PREPARE = importlib.util.module_from_spec(spec)
spec.loader.exec_module(PREPARE)
SOURCE = ROOT / "tts/source"
BUILD = ROOT / "tts/build"
PINS = dict(line.split("=", 1) for line in (ROOT / "source.lock").read_text().splitlines() if "=" in line)
ORT_NAMES = {
    "abseil_cpp": "ABSEIL_CPP", "re2": "RE2", "protobuf": "PROTOBUF", "onnx": "ONNX",
    "date": "DATE", "mp11": "MP11", "json": "NLOHMANN_JSON", "microsoft_gsl": "GSL",
    "safeint": "SAFEINT", "flatbuffers": "FLATBUFFERS", "eigen": "EIGEN3", "pytorch_cpuinfo": "PYTORCH_CPUINFO",
}
SHERPA_NAMES = {
    "kaldi_native_fbank": "KALDI_NATIVE_FBANK", "kissfft": "KISSFFT", "kaldi_decoder": "KALDI_DECODER",
    "kaldifst": "KALDIFST", "openfst": "OPENFST", "eigen": "EIGEN", "simple-sentencepiece": "SIMPLE-SENTENCEPIECE",
    "json": "JSON", "espeak_ng": "ESPEAK_NG", "piper_phonemize": "PIPER_PHONEMIZE",
}
COMMON = [
    "BUILD_SHARED_LIBS=OFF", "BUILD_TESTING=OFF", "CMAKE_POSITION_INDEPENDENT_CODE=ON",
    "CMAKE_C_VISIBILITY_PRESET=hidden", "CMAKE_CXX_VISIBILITY_PRESET=hidden", "CMAKE_VISIBILITY_INLINES_HIDDEN=ON",
    "FETCHCONTENT_FULLY_DISCONNECTED=ON", "FETCHCONTENT_UPDATES_DISCONNECTED=ON", "FETCHCONTENT_TRY_FIND_PACKAGE_MODE=NEVER",
    # Source archives must not inherit the enclosing application's Git metadata.
    "CMAKE_DISABLE_FIND_PACKAGE_Git=ON",
]
ORT_OFF = [
    "BUILD_UNIT_TESTS", "RUN_ONNX_TESTS", "BUILD_BENCHMARKS", "ENABLE_PYTHON", "BUILD_JAVA", "BUILD_CSHARP",
    "BUILD_NODEJS", "BUILD_OBJC", "ENABLE_TRAINING", "ENABLE_TRAINING_APIS", "ENABLE_TRAINING_OPS", "ENABLE_DLPACK",
    "ENABLE_ATEN", "USE_VCPKG", "USE_MIMALLOC", "USE_EXTENSIONS", "USE_KLEIDIAI", "USE_QMX_KLEIDIAI_COEXIST",
    "USE_XNNPACK", "USE_ACL", "USE_DNNL", "USE_NNAPI_BUILTIN", "USE_QNN", "USE_SNPE", "USE_RKNPU", "USE_VSINPU",
    "USE_CUDA", "USE_TENSORRT", "USE_NV", "USE_MIGRAPHX", "USE_OPENVINO", "USE_VITISAI", "USE_COREML", "USE_DML",
    "USE_WINML", "USE_CANN", "USE_WEBGPU", "USE_WEBNN", "USE_JSEP", "USE_AZURE", "MINIMAL_BUILD", "DISABLE_CONTRIB_OPS",
]
SHERPA_OFF = [
    "ENABLE_C_API", "BUILD_C_API_EXAMPLES", "ENABLE_PYTHON", "ENABLE_TESTS", "ENABLE_CHECK", "ENABLE_PORTAUDIO",
    "ENABLE_WEBSOCKET", "ENABLE_SPEAKER_DIARIZATION", "ENABLE_GPU", "ENABLE_DIRECTML", "LINK_D3D", "ENABLE_RKNN",
    "ENABLE_AXERA", "ENABLE_AXCL", "ENABLE_ASCEND_NPU", "ENABLE_QNN", "ENABLE_SPACEMIT", "ENABLE_WASM",
    "ENABLE_EPSEAK_NG_EXE",
]


def run(command, **kwargs):
    print("+", " ".join(map(str, command)), flush=True)
    subprocess.run(list(map(str, command)), check=True, **kwargs)


def defines(values):
    return ["-D" + value for value in values]


def compiler_paths(ndk=None):
    flags = f"-ffile-prefix-map={ROOT.parent.parent}=."
    if ndk:
        flags += f" -ffile-prefix-map={ndk}=android-ndk"
    options = [f"CMAKE_C_FLAGS={flags}", f"CMAKE_CXX_FLAGS={flags}"]
    if not ndk:
        # GCC 14/15 regression 121301 diagnoses std::format itself; retain -Werror with pinned Clang.
        for language, executable in (("C", "clang"), ("CXX", "clang++")):
            compiler = shutil.which(executable)
            if not compiler:
                raise RuntimeError("Host TTS builds require Clang from the pinned Nix development shell")
            options.append(f"CMAKE_{language}_COMPILER={compiler}")
    return options


def checked_toolchain(args):
    sdk_value = args.sdk or os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk_value:
        raise RuntimeError("Set ANDROID_HOME to the pinned SDK (also supplies CMake for host builds)")
    sdk = Path(sdk_value).resolve()
    cmake_home = sdk / "cmake" / PINS["CMAKE_VERSION"]
    if f'Pkg.Revision = {PINS["CMAKE_VERSION"]}' not in (cmake_home / "source.properties").read_text():
        raise RuntimeError("CMake SDK package does not match source.lock")
    cmake = cmake_home / "bin/cmake"
    if not cmake.is_file():
        raise RuntimeError(f"Missing pinned CMake: {cmake}")
    if args.target == "host":
        java_home = os.environ.get("JAVA_HOME")
        if not java_home or not all((Path(java_home) / name).is_file()
                                    for name in ("include/jni.h", "include/linux/jni_md.h")):
            raise RuntimeError("Host JNI requires JAVA_HOME and JNI headers from the pinned JDK 21 Nix shell")
    ndk = Path(args.ndk or os.environ.get("ANDROID_NDK_HOME") or sdk / "ndk" / PINS["ANDROID_NDK_VERSION"]).resolve()
    if args.target == "android" and f'Pkg.Revision = {PINS["ANDROID_NDK_VERSION"]}' not in (ndk / "source.properties").read_text():
        raise RuntimeError("NDK does not match source.lock")
    return sdk, ndk, cmake


def workspace(target):
    # All upstream patches operate on a build copy, never on the checked source cache.
    fingerprint = hashlib.sha256(Path(__file__).read_bytes() + (ROOT / "tts-sources.lock.json").read_bytes()
                                 + (ROOT / "source.lock").read_bytes()
                                 + (ROOT / "patches/ort-android-soname.cmake").read_bytes()
                                 + (ROOT / "patches/sherpa-tts-jni-errors.patch").read_bytes()).hexdigest()
    work = BUILD / target / fingerprint
    if not (work / "sources.ready").is_file():
        if work.exists():
            shutil.rmtree(work)
        shutil.copytree(SOURCE, work / "source", symlinks=True)
        ort = work / "source/top/onnxruntime"
        patches = {
            "abseil_cpp": ["abseil/absl_cuda_warnings.patch"],
            "protobuf": ["protobuf/protobuf_cmake.patch", "protobuf/protobuf_android_log.patch", "protobuf/protobuf_s390x.patch"],
            "date": ["date/date.patch"], "microsoft_gsl": ["gsl/1213.patch"],
            "flatbuffers": ["flatbuffers/flatbuffers.patch"], "onnx": ["onnx/onnx.patch"],
            "eigen": ["eigen/s390x-build.patch", "eigen/s390x-build-werror.patch"],
        }
        if target == "host":
            patches["pytorch_cpuinfo"] = ["cpuinfo/fix_missing_sysfs_fallback.patch"]
        for name, patch_files in patches.items():
            for relative in patch_files:
                with (ort / "cmake/patches" / relative).open("rb") as patch:
                    run(["patch", "--batch", "--binary", "--ignore-whitespace", "-p1"],
                        cwd=work / "source/ort" / name, stdin=patch)
        with (ROOT / "patches/sherpa-tts-jni-errors.patch").open("rb") as patch:
            run(["patch", "--batch", "-p1"], cwd=work / "source/top/sherpa-onnx", stdin=patch)
        (work / "sources.ready").write_text(fingerprint + "\n")
    return work


def protoc(cmake, work, jobs):
    directory = work / "host-protobuf"
    run([cmake, "-S", SOURCE / "ort/protobuf", "-B", directory, "-G", "Ninja"] + defines([
        "CMAKE_BUILD_TYPE=Release", "protobuf_BUILD_TESTS=OFF", "protobuf_BUILD_CONFORMANCE=OFF",
        "protobuf_BUILD_EXAMPLES=OFF", "protobuf_BUILD_PROTOC_BINARIES=ON", "protobuf_BUILD_SHARED_LIBS=OFF",
        "protobuf_WITH_ZLIB=OFF", "protobuf_INSTALL=OFF", "CMAKE_EXE_LINKER_FLAGS=-Wl,--build-id=none",
    ]))
    run([cmake, "--build", directory, "--target", "protoc", "--parallel", jobs])
    executable = directory / "protoc"
    version = subprocess.check_output([executable, "--version"], text=True).strip()
    if version != "libprotoc 3.21.12":
        raise RuntimeError(f"Unexpected source-built protoc: {version}")
    return executable


def ort_build(cmake, sdk, ndk, work, abi, executable, jobs):
    sources = work / "source"
    ort = sources / "top/onnxruntime"
    directory = work / abi / "ort"
    options = COMMON + [f"onnxruntime_{name}=OFF" for name in ORT_OFF] + [
        "onnxruntime_ENABLE_CPUINFO=ON", "ONNX_BUILD_PYTHON=OFF", "ONNX_BUILD_TESTS=OFF", "ONNX_GEN_PB_TYPE_STUBS=OFF",
        "ONNX_BUILD_CUSTOM_PROTOBUF=OFF", "protobuf_BUILD_TESTS=OFF", "protobuf_BUILD_SHARED_LIBS=OFF", "protobuf_WITH_ZLIB=OFF",
        "RE2_BUILD_TESTING=OFF", "RE2_USE_ICU=OFF", "CPUINFO_BUILD_TOOLS=OFF", "CPUINFO_BUILD_UNIT_TESTS=OFF",
        "CPUINFO_BUILD_MOCK_TESTS=OFF", "CPUINFO_BUILD_BENCHMARKS=OFF", f"safeint_SOURCE_DIR={sources / 'ort/safeint'}",
        f"Python_EXECUTABLE={sys.executable}", f"Python3_EXECUTABLE={sys.executable}",
    ] + [f"FETCHCONTENT_SOURCE_DIR_{fetch_name}={sources / 'ort' / name}" for name, fetch_name in ORT_NAMES.items()]
    command = [sys.executable, ort / "tools/ci_build/build.py", "--update", "--build", "--config", "Release",
               "--build_dir", directory, "--build_shared_lib", "--cmake_generator", "Ninja", "--parallel", jobs,
               "--target", "onnxruntime", "--skip_tests", "--skip_submodule_sync", "--skip_pip_install", "--no_kleidiai", "--no_sve",
               "--cmake_path", cmake, "--ctest_path", Path(cmake).with_name("ctest"),
               "--cmake_deps_mirror_dir", ROOT / "tts/cache/ort", "--path_to_protoc_exe", executable]
    # ORT's C API lives in internal archives; its upstream version script hides everything else.
    linker = "-Wl,--build-id=none"
    if abi != "host":
        command += ["--android", "--android_abi", abi, "--android_api", "26", "--android_sdk_path", sdk, "--android_ndk_path", ndk]
        linker += ",-z,max-page-size=16384"
        options += ["ANDROID_STL=c++_static", "CMAKE_SKIP_RPATH=ON", f"CMAKE_PROJECT_INCLUDE={ROOT / 'patches/ort-android-soname.cmake'}"]
    options += [f"CMAKE_SHARED_LINKER_FLAGS={linker}"]
    options += compiler_paths(ndk if abi != "host" else None)
    run(command + ["--cmake_extra_defines"] + options)
    library = directory / "Release/libonnxruntime.so"
    include = ort / "include/onnxruntime/core/session"
    for path in (library, include / "onnxruntime_c_api.h", include / "onnxruntime_cxx_api.h", include / "onnxruntime_cxx_inline.h"):
        if not path.is_file():
            raise RuntimeError(f"Source-built ORT injection input is missing: {path}")
    return library, include


def sherpa_build(cmake, ndk, work, abi, library, include, jobs):
    sources = work / "source"
    directory = work / abi / "sherpa"
    options = COMMON + [f"SHERPA_ONNX_{name}=OFF" for name in SHERPA_OFF] + [
        "CMAKE_BUILD_TYPE=Release", "SHERPA_ONNX_ENABLE_TTS=ON", "SHERPA_ONNX_USE_PRE_INSTALLED_ONNXRUNTIME_IF_AVAILABLE=ON",
        f"SHERPA_ONNX_GIT_SHA1={PINS['SHERPA_ONNX_COMMIT']}",
        "BUILD_ESPEAK_NG_EXE=OFF", "BUILD_ESPEAK_NG_TESTS=OFF", "BUILD_PIPER_PHONMIZE_EXE=OFF", "BUILD_PIPER_PHONMIZE_TESTS=OFF",
        "USE_ASYNC=OFF", "USE_MBROLA=OFF", "USE_LIBSONIC=OFF", "USE_LIBPCAUDIO=OFF", "USE_KLATT=OFF", "USE_SPEECHPLAYER=OFF",
        "OPENFST_USE_ABSL=OFF", "JSON_BuildTests=OFF", "KALDI_NATIVE_FBANK_BUILD_TESTS=OFF", "KALDI_DECODER_ENABLE_TESTS=OFF",
        "KALDI_NATIVE_FBANK_BUILD_PYTHON=OFF", "KALDI_DECODER_BUILD_PYTHON=OFF", "KALDIFST_BUILD_PYTHON=OFF",
        "KALDIFST_BUILD_TESTS=OFF", "KISSFFT_TEST=OFF",
    ] + [f"FETCHCONTENT_SOURCE_DIR_{fetch_name}={sources / 'sherpa' / name}" for name, fetch_name in SHERPA_NAMES.items()]
    env = dict(os.environ, SHERPA_ONNXRUNTIME_INCLUDE_DIR=str(include), SHERPA_ONNXRUNTIME_LIB_DIR=str(library.parent))
    linker = "-Wl,--build-id=none,--exclude-libs,ALL"
    if abi == "host":
        entry = ROOT / "tts-host"
        options += [f"MOTD_SHERPA_SOURCE={sources / 'top/sherpa-onnx'}", "SHERPA_ONNX_ENABLE_JNI=ON", "SHERPA_ONNX_ENABLE_BINARY=ON"]
        targets = ["sherpa-onnx-offline-tts", "motd-kokoro-smoke", "sherpa-onnx-jni"]
    else:
        entry = sources / "top/sherpa-onnx"
        options += [f"CMAKE_TOOLCHAIN_FILE={ndk / 'build/cmake/android.toolchain.cmake'}", f"ANDROID_ABI={abi}",
                    "ANDROID_PLATFORM=android-26", "ANDROID_STL=c++_static", "CMAKE_SKIP_RPATH=ON",
                    "SHERPA_ONNX_ENABLE_JNI=ON", "SHERPA_ONNX_ENABLE_BINARY=OFF"]
        linker += ",-z,max-page-size=16384"
        targets = ["sherpa-onnx-jni"]
    options += [f"CMAKE_SHARED_LINKER_FLAGS={linker}", "CMAKE_EXE_LINKER_FLAGS=-Wl,--build-id=none"]
    options += compiler_paths(ndk if abi != "host" else None)
    run([cmake, "-S", entry, "-B", directory, "-G", "Ninja"] + defines(options), env=env)
    run([cmake, "--build", directory, "--parallel", jobs, "--target"] + targets, env=env)
    return directory


def main(args):
    if not 1 <= args.jobs <= 4:
        raise RuntimeError("Native jobs must be between 1 and 4")
    os.environ["SOURCE_DATE_EPOCH"] = "0"
    PREPARE.verify_source(SOURCE, PREPARE.digest(ROOT / "tts-sources.lock.json"))
    sdk, ndk, cmake = checked_toolchain(args)
    work = workspace(args.target)
    executable = protoc(cmake, work, args.jobs)
    if args.target == "host":
        library, include = ort_build(cmake, sdk, ndk, work, "host", executable, args.jobs)
        directory = sherpa_build(cmake, ndk, work, "host", library, include, args.jobs)
        output = args.output.resolve()
        output.mkdir(parents=True, exist_ok=True)
        shutil.copy2(directory / "bin/motd-kokoro-smoke", output / "motd-kokoro-smoke")
        shutil.copy2(directory / "bin/sherpa-onnx-offline-tts", output / "sherpa-onnx-offline-tts")
        shutil.copy2(directory / "lib/libsherpa-onnx-jni.so", output / "libsherpa-onnx-jni.so")
        # Host uses upstream's Unix SONAME. Preserve the real file, plus both expected names.
        shutil.copy2(library.resolve(), output / "libonnxruntime.so.1")
        (output / "libonnxruntime.so").unlink(missing_ok=True)
        (output / "libonnxruntime.so").symlink_to("libonnxruntime.so.1")
        print(f"Host executables and JNI: {output}; run with LD_LIBRARY_PATH={output}")
    else:
        for abi in ("arm64-v8a", "x86_64"):
            library, include = ort_build(cmake, sdk, ndk, work, abi, executable, args.jobs)
            directory = sherpa_build(cmake, ndk, work, abi, library, include, args.jobs)
            destination = args.output.resolve() / abi
            destination.mkdir(parents=True, exist_ok=True)
            shutil.copy2(library, destination / "libonnxruntime.so")
            shutil.copy2(directory / "lib/libsherpa-onnx-jni.so", destination / "libsherpa-onnx-jni.so")
        run([sys.executable, ROOT / "verify-tts-native.py", "--directory", args.output, "--ndk", ndk])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("target", choices=("android", "host"))
    parser.add_argument("--sdk")
    parser.add_argument("--ndk")
    parser.add_argument("--jobs", type=int, default=4)
    parser.add_argument("--output", type=Path, required=True)
    main(parser.parse_args())

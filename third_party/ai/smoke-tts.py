#!/usr/bin/env python3
"""Exercise source-built Kokoro via native CLI or the compiled Kotlin/JNI wrapper."""
import argparse
import array
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import wave
import tempfile

ROOT = Path(__file__).resolve().parent

def jvm_smoke(args, env):
    if not all((args.classes_jar, args.kotlin_stdlib, args.android_jar)):
        raise RuntimeError("JVM smoke requires --classes-jar, --kotlin-stdlib and --android-jar")
    kotlin_pin = re.search(r'^kotlin = "([^"]+)"$', (ROOT.parent.parent / "gradle/libs.versions.toml").read_text(), re.MULTILINE)
    if not kotlin_pin or args.kotlin_stdlib.name != f"kotlin-stdlib-{kotlin_pin[1]}.jar":
        raise RuntimeError("Use the Gradle Kotlin stdlib jar matching gradle/libs.versions.toml")
    for path in (args.classes_jar, args.kotlin_stdlib, args.android_jar,
                 args.runner_dir / "libonnxruntime.so", args.runner_dir / "libsherpa-onnx-jni.so"):
        if not path.is_file():
            raise RuntimeError(f"Missing real JVM/JNI smoke input: {path}")
    java_home = os.environ.get("JAVA_HOME")
    if not java_home or not all((Path(java_home) / "bin" / name).is_file() for name in ("java", "javac")):
        raise RuntimeError("Run JVM smoke with JAVA_HOME from the pinned JDK 21 Nix shell")
    classpath = os.pathsep.join(str(path.resolve()) for path in (args.classes_jar, args.kotlin_stdlib, args.android_jar))
    with tempfile.TemporaryDirectory(prefix="motd-kokoro-jni-smoke-") as classes:
        subprocess.run([Path(java_home) / "bin/javac", "--release", "21", "-cp", classpath, "-d", classes,
                        ROOT / "tts-host/KokoroJniSmoke.java"], check=True, env=env)
        subprocess.run([Path(java_home) / "bin/java", f"-Djava.library.path={args.runner_dir.resolve()}",
                        "-cp", classes + os.pathsep + classpath, "KokoroJniSmoke",
                        args.assets.resolve(), args.output_file.resolve()], check=True, env=env)



def main(args):
    manifest = json.loads((ROOT / "tts-host/kokoro-assets.lock.json").read_text())
    for entry in manifest["files"]:
        path = args.assets / entry["path"]
        if not path.is_file() or path.stat().st_size != entry["size"]:
            raise RuntimeError(f"Missing or wrong-size pinned model asset: {path}")
        with path.open("rb") as stream:
            if hashlib.file_digest(stream, "sha256").hexdigest() != entry["sha256"]:
                raise RuntimeError(f"Pinned model asset digest mismatch: {path}")
    env = dict(os.environ, LD_LIBRARY_PATH=str(args.runner_dir.resolve()) + ":" + os.environ.get("LD_LIBRARY_PATH", ""))
    if args.output_file:
        jvm_smoke(args, env)
        return
    if any((args.classes_jar, args.kotlin_stdlib, args.android_jar)):
        raise RuntimeError("Use --output-file for the real Kotlin/JNI smoke")
    args.output.mkdir(parents=True, exist_ok=True)
    if any(args.output.iterdir()):
        raise RuntimeError("Use an empty smoke output directory so stale WAVs cannot pass")
    subprocess.run([args.runner_dir / "motd-kokoro-smoke", args.assets.resolve(), args.output.resolve()], check=True, env=env)
    pcm = {}
    for name in ("female-heart-sender.wav", "female-heart-sender-fast.wav", "female-heart-action.wav", "male-michael-sender.wav", "male-michael-action.wav", "female-emma-british.wav", "after-cancel.wav"):
        with wave.open(str(args.output / name), "rb") as audio:
            if (audio.getnchannels(), audio.getsampwidth(), audio.getframerate()) != (1, 2, 24000):
                raise RuntimeError(f"Invalid speech WAV format: {name}")
            samples = array.array("h", audio.readframes(audio.getnframes()))
            if sys.byteorder != "little":
                samples.byteswap()
            duration = len(samples) / 24000
            rms = math.sqrt(sum(float(sample) ** 2 for sample in samples) / max(1, len(samples))) / 32768
            if not (0.1 <= duration <= 60 and rms > 0.0001):
                raise RuntimeError(f"Silent or implausible speech WAV: {name} duration={duration} rms={rms}")
            pcm[name] = samples
            print(f"{name}: mono PCM16 24000 Hz, {duration:.3f}s, RMS={rms:.6f}")
    if pcm["female-heart-sender.wav"] == pcm["male-michael-sender.wav"]:
        raise RuntimeError("Heart and Michael did not produce different audible waveforms")
    rate_ratio = len(pcm["female-heart-sender-fast.wav"]) / len(pcm["female-heart-sender.wav"])
    if not 0.45 < rate_ratio < 0.9:
        raise RuntimeError(f"1.5x speech did not produce an appropriately shorter WAV: duration ratio={rate_ratio}")
    print(f"Verified actual 1.5x/1x WAV duration ratio={rate_ratio:.3f}")
    print("Verified real female/male/British speech, rate effect, ACTION phrasing, cancellation/drain and post-cancel synthesis")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runner-dir", type=Path, required=True)
    parser.add_argument("--assets", type=Path, required=True)
    output = parser.add_mutually_exclusive_group(required=True)
    output.add_argument("--output", type=Path, help="Empty output directory for the native CLI voice/rate smoke")
    output.add_argument("--output-file", type=Path, help="Absent or precreated zero-byte WAV for real Kotlin/JNI smoke")
    parser.add_argument("--classes-jar", type=Path, help="Compiled ai-tts classes.jar; never a substitute wrapper")
    parser.add_argument("--kotlin-stdlib", type=Path, help="Pinned Gradle Kotlin stdlib jar")
    parser.add_argument("--android-jar", type=Path, help="SDK android.jar for AssetManager type resolution only")
    main(parser.parse_args())

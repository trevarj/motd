# Third-party notices

## Current distribution

This source tree vendors the pinned arm64-v8a libbox AAR used by the embedded
transport:

- Artifact: `app/libs/libbox.aar`
- sing-box version: `v1.13.12`
- Delivery: main Android application APK
- ABI: `arm64-v8a` only; unsupported ABI variants are not built
- SHA-256: `3fdbd30eba2450935389c100efd88475721d44870bbab870340533ee4ba84977`
- Build manifest: `app/libs/libbox-v1.13.12.manifest`
- Source-build tool: [SagerNet/gomobile](https://github.com/SagerNet/gomobile),
  `v0.1.12` at commit
  `b2c30f47825831593d6980af8191527490f9c968`
- Source archive SHA-256:
  `ecbdc425d07884ba2895985d77a1a5fb9c443f93ceb71acaa894ca7609a4322a`

## Embedded transport: sing-box / libbox

The embedded VLESS + REALITY transport uses libbox from
[SagerNet/sing-box](https://github.com/SagerNet/sing-box). The pinned upstream
source is sing-box **v1.13.12**, commit
[`1086ab2563320e0da0c23b3a491d8dfa0939dff4`](https://github.com/SagerNet/sing-box/commit/1086ab2563320e0da0c23b3a491d8dfa0939dff4),
with its Android submodule at
[`772879ce9cd37c29e377d4d44d0efee12662948d`](https://github.com/SagerNet/sing-box-for-android/commit/772879ce9cd37c29e377d4d44d0efee12662948d).

sing-box is GPL-3.0-or-later. The exact corresponding-source inputs and a
rebuild procedure are documented in
[third_party/sing-box/README.md](third_party/sing-box/README.md), with pins in
[third_party/sing-box/source.lock](third_party/sing-box/source.lock). Any
release that conveys this AAR must also make the complete source snapshot used
for that release available under GPL-3.0-or-later.

Every GitHub release attaches a deterministic `motd-libbox-source-<tag>.tar.gz`
asset, `SHA256SUMS`, the project `LICENSE`, and a rendered release-specific copy
of this notice. The rendered notice records the archive's actual release URL
and SHA-256; use that copy as the provenance record for a particular APK.

## On-device voice transcription: whisper.cpp

Optional Labs voice transcription builds a CPU-only Android JNI library from:

- [whisper.cpp](https://github.com/ggml-org/whisper.cpp) at commit
  [`642b5d3260e020c2fc6f34a9569d10ddd7672963`](https://github.com/ggml-org/whisper.cpp/commit/642b5d3260e020c2fc6f34a9569d10ddd7672963),
  licensed under MIT, for on-device voice-message transcription.

Every GitHub release attaches the deterministic
`motd-ai-source-<tag>.tar.gz` asset containing the pinned source tree, its
MIT license, motd's native/Kotlin wrapper, and the reproducible build lock.
Imported model weights remain user-owned; motd never includes them in the app
or source asset and does not redistribute them.

## Local text runtime: llama.cpp / GGML

The source-built `:ai-text` module uses [llama.cpp v0.5.0](https://github.com/ggml-org/llama.cpp/tree/7fe450e19305b828c199d602c23a8337aaa1f03b),
commit `7fe450e19305b828c199d602c23a8337aaa1f03b`, under MIT.
Its GGML source and upstream copyright/license notices are retained in
`third_party/llama.cpp/source`; the runtime is CPU-only and has no network JNI API.
Build-info patches apply only to a build-directory copy, not upstream source.
The annotated upstream tag is unsigned; the commit pin is not signature verification.

Retained vendor notices include nlohmann JSON (Niels Lohmann, MIT),
cpp-httplib (yhirose, MIT), xxHash (Yann Collet, BSD-2-Clause), and
rotate-bits (William Casarin, MIT), with their license text in upstream
`vendor/` headers/license files. Upstream source also retains notices for
optional image/audio/subprocess components; those features are not enabled
by this text wrapper. Runtime and vendor licenses must remain in source distributions.

The optional, separately installed instruction artifact is
[Qwen/Qwen3.5-2B](https://huggingface.co/Qwen/Qwen3.5-2B) (Apache-2.0),
quantized by [Unsloth](https://huggingface.co/unsloth/Qwen3.5-2B-GGUF).
The selected `Qwen3.5-2B-Q4_K_M.gguf` revision is
`f6d5376be1edb4d416d56da11e5397a961aca8ae`, length `1280835840` bytes,
SHA-256 `aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223`.
Weights are not bundled in the app or corresponding-source archive.
Model identity and native/toolchain pins are recorded in
[`third_party/ai/source.lock`](third_party/ai/source.lock).

## Local chat speech: Sherpa ONNX / ONNX Runtime / eSpeak NG

The source-built `:ai-tts` library uses:

- [Sherpa ONNX 1.13.7](https://github.com/k2-fsa/sherpa-onnx/tree/917bed95c8e5c7c18aa4d69fea42e9ef8ef0a60e),
  commit `917bed95c8e5c7c18aa4d69fea42e9ef8ef0a60e`, Apache-2.0.
- [ONNX Runtime 1.27.1](https://github.com/microsoft/onnxruntime/tree/df2ba1cf8108aa63627cf4cdf8f807880b938616),
  commit `df2ba1cf8108aa63627cf4cdf8f807880b938616`, MIT.
- [eSpeak NG's pinned Sherpa fork](https://github.com/csukuangfj/espeak-ng/tree/ed530aa113046142eb5115cf2fc9157854d0ffe1),
  GPL-3.0-or-later, statically linked through Piper Phonemize (MIT).
  Its UCD code/data and additional BSD-2-Clause, Apache-2.0 and Unicode notices
  are retained. Piper's vendored uni-algo offers public-domain/Unlicense and MIT grants.

The complete enabled source closure is SHA-256 locked in
[`third_party/ai/tts-sources.lock.json`](third_party/ai/tts-sources.lock.json).
ORT's upstream SHA-1 archive checks are also retained and verified, including MP11.
The separate [`tts-licenses.lock.json`](third_party/ai/tts-licenses.lock.json)
checks every component's license text, including vendored Unicode, CPUINFO/clog
and Darts clone's BSD-2-Clause grant (Copyright 2008–2014 Susumu Yata). The library
includes these full grants under
`META-INF/motd-ai-tts-licenses/`; corresponding-source releases preserve all
upstream source/header/vendor notices, not just top-level licenses.

Enabled dependencies and their principal licenses:

| Sources | License |
| --- | --- |
| Abseil, ONNX, FlatBuffers, kaldi-native-fbank, kaldi-decoder, kaldifst, OpenFST | Apache-2.0 |
| simple-sentencepiece and its vendored Darts clone | Apache-2.0 AND BSD-2-Clause; the complete Darts header/grant is retained |
| RE2, protobuf | BSD-3-Clause; protobuf's vendored utf8_range is MIT |
| CPUINFO and vendored clog | BSD-2-Clause |
| date, Microsoft GSL, SafeInt, nlohmann JSON (both pinned versions) | MIT |
| Boost MP11 | Boost Software License 1.0; the complete grant is retained in `third_party/ai/licenses/BSL-1.0.txt` |
| Eigen (separate ORT and Sherpa pins) | Primarily MPL-2.0 with per-file BSD/other notices; all upstream `COPYING.*` texts are retained |
| KissFFT | BSD-3-Clause |

ORT and Sherpa use separate configure graphs so their Eigen and JSON versions
cannot collide. Protobuf 21.12 supplies both source-built host `protoc` and
target static libraries. CPU EP/MLAS remain enabled; GPU/accelerator providers,
KleidiAI, training, tests, optional bindings, pip installation and precompiled
runtime/protoc downloads are disabled. Sherpa retains its upstream core graph;
only upstream `Tts.kt` is compiled into the Android Kotlin binding. This does
not advertise its unused recognizer APIs as an app feature.

Each ABI (`arm64-v8a`, `x86_64`) packages exactly `libsherpa-onnx-jni.so` and
`libonnxruntime.so`; private dependencies and the C++ runtime are static.
The build removes GNU build IDs, remaps build/NDK source paths, fixes
`SOURCE_DATE_EPOCH=0`, hides private exports and requests 16 KiB ELF
alignment. An Android-only CMake overlay removes ORT's Unix `.so.1` SONAME;
the verifier checks unversioned SONAME/DT_NEEDED compatibility, exports and
alignment. A small build-copy JNI patch uses upstream exception translation
for configured synthesis and WAV saving instead of allowing C++ exceptions across JNI.
No checked upstream source tree is modified.

The deterministic `motd-ai-source-<tag>.tar.gz` release asset includes all
24 enabled source archive trees, source/license locks, build-copy patches and
host/Android source rebuild scripts. Disabled model/test/binary fixtures are
excluded. The two unusable absolute developer-machine symlinks in upstream's
disabled Go examples are omitted during safe extraction.
See [`docs/fdroid.md`](docs/fdroid.md) for the source-only rebuild commands and
the required external F-Droid recipe update.

Kokoro's English model assets are separate, explicitly downloaded
[Apache-2.0 artifacts](https://huggingface.co/csukuangfj/kokoro-int8-multi-lang-v1_0/tree/2a360693d79b88b49b88e29aec2b53577f41f206).
The pinned 19-file smoke fixture and its phonemizer data are not bundled as
weights in an APK, AAR or source release. Corresponding-source claims cover
the native runtime, not optional model retraining or regeneration.

## QR encoding: ZXing

QR invitation generation and decoding use
[ZXing Core 3.5.4](https://github.com/zxing/zxing/releases/tag/zxing-3.5.4),
copyright ZXing authors, licensed under the Apache License 2.0. Camera frames
remain on-device; no ZXing Android application or remote scanning service is
bundled.

## Brand lettering: Roboto

The outlined lettering in the motd wordmark and lockups is derived from Roboto
Bold, copyright © Google LLC. Roboto is licensed under the Apache License 2.0.
The exact source pin and license are recorded in
[`docs/assets/brand/`](docs/assets/brand/README.md). The font binary is not
distributed; the SVG and Android assets contain converted glyph outlines.

## Generated channel marks: Devicons and Guix

The IRC sprite channel renderer embeds monochrome SVG path data generated from
[devicon v2.16.0](https://github.com/devicons/devicon/tree/v2.16.0)
([MIT](https://github.com/devicons/devicon/blob/v2.16.0/LICENSE)) via
`tools/gen-channel-devicons/`, plus two marks retained from
[Devicons v1.1.0](https://github.com/vorillaz/devicons/tree/v1.1.0)
([MIT](https://github.com/vorillaz/devicons/blob/v1.1.0/LICENSE)). No font
binary is distributed and no icon is loaded from the network.

The `#guix` channel badge is a simplified monochrome derivative of the
[Guix logo](https://commons.wikimedia.org/wiki/File:Guix_logo.svg) by Luis
Felipe López Acevedo, from `guix-artwork.git`, attributed under
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).

## Bundled font: JetBrains Mono

The app bundles the variable-weight upright and italic builds of
[JetBrains Mono](https://github.com/JetBrains/JetBrainsMono), pinned at
release **v2.304**. Copyright 2020 The JetBrains Mono Project Authors
(https://github.com/JetBrains/JetBrainsMono), licensed under the
[SIL Open Font License, Version 1.1](https://scripts.sil.org/OFL).

- Bundled files:
  - `app/src/main/res/font/jetbrains_mono_wght.ttf` (from
    `fonts/variable/JetBrainsMono[wght].ttf`)
  - `app/src/main/res/font/jetbrains_mono_italic_wght.ttf` (from
    `fonts/variable/JetBrainsMono-Italic[wght].ttf`)
- License copy: [`third_party/fonts/jetbrains-mono/OFL.txt`](third_party/fonts/jetbrains-mono/OFL.txt)
- Source archive:
  [`JetBrainsMono-2.304.zip`](https://github.com/JetBrains/JetBrainsMono/releases/download/v2.304/JetBrainsMono-2.304.zip)
- Source archive SHA-256:
  `6f6376c6ed2960ea8a963cd7387ec9d76e3f629125bc33d1fdcd7eb7012f7bbf`

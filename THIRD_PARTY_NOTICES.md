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

## Generated-avatar chest emblems

The opt-in IRC sprite avatar renderer embeds compact Font Awesome Free 6.7.2
SVG paths directly in Kotlin. It uses a small set of solid technical marks and
the Rust, Python, Go, Git Alt, GitHub, Linux, Docker, and Android brand marks
as background-free chest emblems; no Font Awesome font binary is distributed.
The SVG icons are licensed under [CC BY 4.0](https://fontawesome.com/license/free).
The exact source mapping, attribution, fallback policy, and non-endorsement
note are in [`docs/assets/avatar-icons/README.md`](docs/assets/avatar-icons/README.md).

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

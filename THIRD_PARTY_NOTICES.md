# Third-party notices

## Current distribution

This source tree vendors the pinned arm64-v8a libbox AAR used by the embedded
transport:

- Artifact: `app/libs/libbox.aar`
- sing-box version: `v1.13.12`
- Delivery: main Android application APK
- ABI: `arm64-v8a` only; unsupported ABI variants are not built
- SHA-256: `ef8b4a00eb2e2de7b9a593db18f5190431d1cd311066bde76792bfb1a262a88f`
- Build manifest: `app/libs/libbox-v1.13.12.manifest`

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

## Brand lettering: IBM Plex Mono

The outlined lettering in the motd wordmark and lockups is derived from IBM
Plex Mono Bold Italic, copyright © 2017 IBM Corp. IBM Plex is licensed under
the SIL Open Font License 1.1. The exact source pin and license are recorded in
[`docs/assets/brand/`](docs/assets/brand/README.md). The font binary is not
distributed; the SVG and Android assets contain converted glyph outlines.

## Google distribution: Firebase Cloud Messaging

Only the `google` APK includes the Firebase Android SDK for Cloud Messaging. The Firebase Android
SDK is distributed under the Apache License 2.0; message delivery also uses the separately operated
Google Firebase service and is subject to its terms. The `foss` APK contains no Firebase dependency.
The optional relay source under `firebase/` uses the MIT-licensed Firebase Functions SDK and the
Apache-2.0-licensed Firebase Admin SDK; exact versions are recorded in its lockfile.

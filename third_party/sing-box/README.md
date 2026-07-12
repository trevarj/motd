# sing-box / libbox complete-source manifest

This directory records the complete corresponding-source inputs and rebuild
procedure for the vendored `app/libs/libbox.aar`. It is the release manifest;
the exact upstream sources remain at the pinned public revisions below.

## Pinned upstream source

- Project: [SagerNet/sing-box](https://github.com/SagerNet/sing-box)
- Version: `v1.13.12`
- Commit: `1086ab2563320e0da0c23b3a491d8dfa0939dff4`
- Source archive SHA-256: `367ba852d869f06b27cbba576f066d9ae03e0600956048bd5800d25ad9cfbdd4`
- Android submodule: [sing-box-for-android](https://github.com/SagerNet/sing-box-for-android)
  at `772879ce9cd37c29e377d4d44d0efee12662948d`
- Android submodule source archive SHA-256:
  `bf6c420460899347d3080220288bfe7279962e30a4ffef482e1c61924300c171`
- Vendored artifact: `app/libs/libbox.aar`, **arm64-v8a-only**, used by the
  main Android application build
- Artifact SHA-256: `ef8b4a00eb2e2de7b9a593db18f5190431d1cd311066bde76792bfb1a262a88f`
- Artifact build manifest: `app/libs/libbox-v1.13.12.manifest`

## Controlled build

The regular project shell deliberately does not carry this large native toolchain.
The `libbox` shell contains the pinned Go/JDK build tools but deliberately does
not compose an Android SDK: that would force Nix to fetch the NDK before the
shell starts. Supply either an already-extracted r28 NDK or the exact Google
archive; the build script validates both archive hashes before extracting it to
an external cache.

With an extracted NDK:

```sh
LIBBOX_NDK_HOME=/path/to/android-ndk-r28 \
  nix develop .#libbox -c ./third_party/sing-box/build-libbox.sh
```

With a local archive (for example the verified one already downloaded by the
operator):

```sh
LIBBOX_NDK_ARCHIVE=/path/to/android-ndk-r28-linux.zip \
  nix develop .#libbox -c ./third_party/sing-box/build-libbox.sh
```

The archive must match the URL, SHA-1, and SHA-256 pinned in `source.lock`.
It is extracted only after verification into
`${XDG_CACHE_HOME:-$HOME/.cache}/motd/libbox/android-ndk-r28` (override with
`LIBBOX_NDK_CACHE_DIR`), never into the repository.
Set `LIBBOX_PREPARE_NDK_ONLY=1` with the same command to validate and prepare
that external cache without fetching sing-box or building an AAR.

`source.lock` pins every source revision and source-tree SHA-256. The script
checks those values before building, uses sing-box's supported `android/arm64`
platform selector, verifies that the AAR contains only `arm64-v8a`, and writes
it to `app/libs/libbox.aar` plus its SHA-256 manifest at
`app/libs/libbox-v1.13.12.manifest`. Both files are deliberately versioned in
this repository and their values above must match before release. Gradle's
`verifyLibboxArtifact` task verifies the tracked AAR against both the manifest
and the pinned expected SHA-256; every `assemble*` and `check` task depends on
it. The Android application build is arm64-only while this is the only packaged
libbox artifact.

## License and notices

sing-box is Copyright (C) 2022 by nekohasekai and is licensed under the GNU
General Public License, version 3 or (at your option) any later version. Its
upstream [LICENSE](https://github.com/SagerNet/sing-box/blob/v1.13.12/LICENSE)
also prohibits derivative works from using its name or implying association
without prior consent.

## Complete corresponding source and release procedure

1. Clone the exact sing-box commit and initialize the Android submodule at the
   pinned revision. Verify both source-tree hashes in `source.lock`.
2. Use `build-libbox.sh` with the pinned Go/gomobile and NDK inputs described
   above. The script verifies the AAR ABI and writes the checked artifact hash
   into `app/libs/libbox-v1.13.12.manifest`.
3. Run `./third_party/sing-box/package-source.sh <tag> <output-dir>`. It clones
   and verifies both pinned revisions, emits their exact `git archive` streams,
   and packages them with this build script, `source.lock`, `go.mod`/`go.sum`,
   vendored source, the Nix build inputs, license, and artifact manifest. The
   outer archive is deterministic (`tar` metadata normalized; `gzip -n`).
4. The release workflow runs `prepare-release-assets.sh`, attaches the complete
   source archive beside the APK, and renders a release-specific copy of
   `THIRD_PARTY_NOTICES.md` containing the actual asset URL and SHA-256. It also
   attaches the project and IBM Plex licenses plus `SHA256SUMS`. Keep the APK,
   source archive, notice, licenses, and checksum manifest available together
   for as long as the APK is offered.

The packaged inner source archives can be rebuilt without cloning upstream:

```sh
LIBBOX_SOURCE_ARCHIVE=/path/to/sing-box-v1.13.12.tar \
LIBBOX_ANDROID_SOURCE_ARCHIVE=/path/to/sing-box-for-android-772879ce....tar \
LIBBOX_NDK_ARCHIVE=/path/to/android-ndk-r28-linux.zip \
  nix develop .#libbox -c ./third_party/sing-box/build-libbox.sh
```

In archive mode the build script verifies both pinned archive hashes, then recreates deterministic
local Git metadata tagged with the pinned sing-box version. Upstream's libbox builder reads that tag
to embed the same release version string; the archive hashes remain the source-provenance authority.

These records ensure recipients can obtain the exact source needed to rebuild
the distributed libbox artifact under GPL-3.0-or-later.

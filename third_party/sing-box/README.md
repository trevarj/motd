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
- gomobile version: `v0.1.12`, commit
  `b2c30f47825831593d6980af8191527490f9c968`
- gomobile source archive SHA-256:
  `ecbdc425d07884ba2895985d77a1a5fb9c443f93ceb71acaa894ca7609a4322a`
- Go toolchain: `go1.25.12`, built from source commit
  `d80d9a98f7e3a8f9b3a82d2c6079f84eb1101d46` with `src/make.bash`
- Java toolchain: OpenJDK 21
- Vendored artifact: `app/libs/libbox.aar`, **arm64-v8a-only**, used by the
  main Android application build
- Artifact SHA-256: `9feeb74fb1312322dd1eb95e45e47f98d4c69b57ec9298d643a1e8dd076f419e`
- Artifact build manifest: `app/libs/libbox-v1.13.12.manifest`
- AAR size: `10,811,123` bytes (previous all-feature build: `22,482,540`)
- Native library size: `29,139,272` bytes (previous: `62,829,928`)
- Packed `.rela.dyn`: `656,780` bytes, `ANDROID_RELA`/APS2; no RELR
- Every generated `classes.jar` byte is identical to the previous full bridge.

## Controlled build

The regular project shell deliberately does not carry this large native toolchain.
The `libbox` shell contains the pinned Go/JDK build tools but deliberately does
not compose an Android SDK: that would force Nix to fetch the NDK before the
shell starts. Supply an Android SDK with platform 23, plus either an
already-extracted r28c NDK or the exact Google archive; the build script
validates both archive hashes before extracting the NDK to an external cache.

With an extracted NDK:

```sh
mkdir -p /tmp/motd-go
tar -xf /path/to/go-go1.25.12.tar -C /tmp/motd-go
nix develop .#libbox -c bash -c '
  (cd /tmp/motd-go/src && ./make.bash)
  export GOROOT=/tmp/motd-go
  export PATH="$GOROOT/bin:$PATH"
  export LIBBOX_NDK_HOME=/path/to/android-ndk-r28c
  exec bash ./third_party/sing-box/build-libbox.sh
'
```

With a local archive (for example the verified one already downloaded by the
operator):

```sh
nix develop .#libbox -c bash -c '
  export GOROOT=/tmp/motd-go
  export PATH="$GOROOT/bin:$PATH"
  export LIBBOX_NDK_ARCHIVE=/path/to/android-ndk-r28c-linux.zip
  exec bash ./third_party/sing-box/build-libbox.sh
'
```

The archive must match the URL, SHA-1, and SHA-256 pinned in `source.lock`.
It is extracted only after verification into
`${XDG_CACHE_HOME:-$HOME/.cache}/motd/libbox/android-ndk-r28c` (override with
`LIBBOX_NDK_CACHE_DIR`), never into the repository.
Set `LIBBOX_PREPARE_NDK_ONLY=1` with the same command to validate and prepare
that external cache without fetching sing-box or building an AAR.

F-Droid supplies an SDK-managed NDK instead of the verified Google archive. In
that environment set `LIBBOX_NDK_HOME` (or use `ANDROID_NDK_HOME`) and
`LIBBOX_PATCH_NDK_HOST_TOOLS=0`; the script validates the exact NDK revision
without requiring the Nix archive marker or mutating the shared SDK.

`source.lock` pins every source revision, source-tree SHA-256, and the exact Go
and Java toolchains. For release and F-Droid reproducibility, Go must be built
from the pinned source with `src/make.bash`; Nix's Go is only a bootstrap/
convenience compiler and is not byte-identical. The script checks those values before building,
invokes the pinned gomobile directly with the full `experimental/libbox` package, verifies that
the AAR contains only `jni/arm64-v8a/libbox.so`, and writes it to `app/libs/libbox.aar` plus its
SHA-256 manifest at `app/libs/libbox-v1.13.12.manifest` by default. It does not build the unused
upstream legacy variant. Set `LIBBOX_OUTPUT_DIR` to place a source-built artifact under a generated
build directory instead; this is the mode used by F-Droid after the checked-in AAR
has been removed. The Gradle `verifyLibboxArtifact` task always verifies the
manifest hash and exact JNI set. Normal builds additionally require the pinned
checked-in AAR hash; `-PmotdLibboxSource=true` deliberately accepts a different
source-build hash while retaining the structural checks.

### Feature and linker contract

`source.lock` fixes `LIBBOX_BUILD_TAGS` to
`with_utls,with_clash_api,badlinkname,tfogo_checklinkname0`. VLESS, SOCKS,
WebSocket, and ordinary TLS are unconditionally included by the pinned source.
`with_utls` enables both uTLS fingerprints and the REALITY client.
`with_clash_api` retains the command service's Clash mode/traffic/connection
APIs; the full libbox bridge and gRPC command server remain unchanged.
The app supports only SOCKS → VLESS TCP/REALITY/uTLS or WebSocket/TLS and
rejects TUN. Naive/Cronet, Tailscale, QUIC, WireGuard, and gVisor tags are
therefore omitted, together with the Tailscale-only omit tags.

The pinned invocation is `gomobile bind -target android/arm64 -androidapi 23
-bootclasspath "$ANDROID_HOME/platforms/android-23/android.jar"
-javapkg=io.nekohasekai -libname=box -trimpath -buildvcs=false`, with the tags
above and Go linker flags `-X github.com/sagernet/sing-box/constant.Version=1.13.12
-X internal/godebug.defaultGODEBUG=multipathtcp=0 -s -w -buildid=
-checklinkname=0 -linkmode=external -extldflags=-Wl,--pack-dyn-relocs=android`.
The explicit API 23 bootclasspath avoids selecting a different installed SDK's
Java APIs. The manifest records these exact flags. Android APS2 packed RELA relocations are
supported since API 23, below motd's unchanged API 26 minimum. RELR is not used.
The APK still loads its uncompressed native library directly, without extraction.

With the same source-built Go toolchain, a host-side behavioral smoke runs real
libbox config constructors for both supported transports, rejects invalid
REALITY/uTLS parameters, reloads/closes the command server's shared service,
and negotiates SOCKS5 on loopback without sending traffic to an external peer.
It also checks `libbox.Version() == "1.13.12"` and the exported semver-dependent
deprecation warning API under the same release stamp:

```sh
nix develop .#libbox -c bash -c '
  export GOROOT=/tmp/motd-go
  export PATH="$GOROOT/bin:$PATH"
  source third_party/sing-box/source.lock
  cd third_party/sing-box/source
  GOTOOLCHAIN=local go run -tags "$LIBBOX_BUILD_TAGS" \
    -ldflags "-X github.com/sagernet/sing-box/constant.Version=${SING_BOX_VERSION#v} -checklinkname=0" ../smoke-libbox.go
'
```

This checks Go-side behavior, not Android JNI loading or an authenticated
remote REALITY/WebSocket TLS handshake. The project's x86_64 emulator variant
deliberately excludes the arm64-only AAR.

## License and notices

sing-box is Copyright (C) 2022 by nekohasekai and is licensed under the GNU
General Public License, version 3 or (at your option) any later version. Its
upstream [LICENSE](https://github.com/SagerNet/sing-box/blob/v1.13.12/LICENSE)
also prohibits derivative works from using its name or implying association
without prior consent.

## Complete corresponding source and release procedure

1. Initialize the repository submodules recursively. The top-level gitlinks pin
   sing-box and gomobile, while sing-box pins its Android client submodule. The
   build script verifies all revisions against `source.lock`.
2. Use `build-libbox.sh` with the pinned Go/gomobile and NDK inputs described
   above. The script verifies the AAR ABI and writes the checked artifact hash
   into `app/libs/libbox-v1.13.12.manifest`.
3. Run `bash ./third_party/sing-box/package-source.sh <tag> <output-dir>`. It clones
   and verifies all pinned revisions, emits their exact `git archive` streams,
   and packages them with this build script, `source.lock`, `go.mod`/`go.sum`,
   vendored source, the Nix build inputs, license, and artifact manifest. The
   outer archive is deterministic (`tar` metadata normalized; `gzip -n`).
4. The release workflow runs `prepare-release-assets.sh`, attaches the complete
   source archive beside the APK, and renders a release-specific copy of
   `THIRD_PARTY_NOTICES.md` containing the actual asset URL and SHA-256. It also
   attaches the project and Roboto licenses plus `SHA256SUMS`. Keep the APK,
   source archive, notice, licenses, and checksum manifest available together
   for as long as the APK is offered.

The packaged inner source archives can be rebuilt without cloning upstream:

```sh
LIBBOX_SOURCE_ARCHIVE=/path/to/sing-box-v1.13.12.tar \
LIBBOX_ANDROID_SOURCE_ARCHIVE=/path/to/sing-box-for-android-772879ce....tar \
LIBBOX_GOMOBILE_SOURCE_ARCHIVE=/path/to/gomobile-v0.1.12.tar \
LIBBOX_NDK_ARCHIVE=/path/to/android-ndk-r28c-linux.zip \
  nix develop .#libbox -c bash ./third_party/sing-box/build-libbox.sh
```

In archive mode the build script verifies all three pinned archive hashes. Direct binding
embeds `${SING_BOX_VERSION#v}` from the verified lock, matching upstream's unprefixed
release version. No synthetic Git commits or tags are needed to rebuild an archive.
For an offline build from F-Droid-provisioned source
checkouts outside the repository, use `LIBBOX_SOURCE_DIR`,
`LIBBOX_ANDROID_SOURCE_DIR`, and `GOMOBILE_SOURCE_DIR` with `LIBBOX_OFFLINE=1`.
By default, initialized `third_party/sing-box/source` and
`third_party/gomobile` submodules are used directly. The script validates
OpenJDK 21 itself; direct binding avoids patching the upstream builder's Java 17
guard. The pinned gomobile environment fix is applied after source verification,
is fully specified by this packaged script, and is restored on exit. It creates
gomobile's empty toolchain directory directly because this build does not use
OpenAL; it never runs gomobile's unpinned `gobind@latest` initialization step.

These records ensure recipients can obtain the exact source needed to rebuild
the distributed libbox artifact under GPL-3.0-or-later.

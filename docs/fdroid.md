# F-Droid packaging

motd is in fdroiddata. The "New app: motd" merge request
[!43407](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/43407) merged on
2026-08-07, and the metadata now lives on fdroiddata `master` at
[`metadata/io.github.trevarj.motd.yml`](https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/io.github.trevarj.motd.yml).
Publication on f-droid.org follows on F-Droid's own timeline: the buildserver
rebuilds each merged `Builds:` entry from source and only then publishes.

This file is the recipe and signing reference. For what happens on each new
release, and for the cases where a human has to touch the metadata, see
[`human-fdroid-update.md`](human-fdroid-update.md).

The package is built from the full source checkout and verified against the
upstream-signed GitHub release APK. F-Droid publishes that upstream signature
only after its unsigned rebuild matches, so GitHub and F-Droid installations
stay update-compatible on the same key.

## Package identity

- Application ID: `io.github.trevarj.motd`
- Source: `https://github.com/trevarj/motd.git`, `subdir: app`, recursive
  submodules
- One Google-free build with no product flavors. The former `foss`/`google`
  split is gone; see "Recipe change pending" below.
- arm64-v8a only, because that is the only ABI covered by the pinned libbox
  source and manifest.

## Versioning

The version code scheme is `major * 1000000 + minor * 1000 + patch`, which is
monotonic for the project's semantic release range and stays inside Android's
signed 32-bit limit. Release tags use `vMAJOR.MINOR.PATCH`.

Version discovery is automatic. The merged metadata ends with:

```yaml
AutoUpdateMode: Version
UpdateCheckMode: Tags ^v[0-9]+\.[0-9]+\.[0-9]+$
UpdateCheckData:
  gradle.properties|motdVersionCode=(\d+)|.|motdVersionName=([0-9]+\.[0-9]+\.[0-9]+)
CurrentVersion: <latest published versionName>
CurrentVersionCode: <latest published versionCode>
```

F-Droid's `checkupdates` bot matches the newest tag, reads `motdVersionCode` and
`motdVersionName` out of `gradle.properties` at that tag, clones the previous
`Builds:` entry, and resolves the tag to a full 40-character commit SHA. Every
`commit:` field must be that full SHA; a branch name or abbreviated SHA is not
accepted.

Each build re-supplies those values plus the source commit before the source
scan:

```yaml
prebuild:
  - printf '\nmotdVersionName=%s\nmotdVersionCode=%s\nmotdSourceCommit=%s\n' "$$VERSION$$" "$$VERCODE$$" "$$COMMIT$$" >> ../gradle.properties
```

## The merged recipe

This is the shape of the latest merged `Builds:` entry. Treat the fdroiddata
file as authoritative; this copy exists so a recipe change can be diffed against
the source tree.

```yaml
  - versionName: 0.12.5
    versionCode: 12005
    commit: 43d009e17a906434f1e41b3ac89dd7992e0c898d
    subdir: app
    submodules: true
    sudo:
      - apt-get update
      - apt-get install -y golang-go make unzip
    gradle:
      - foss
    srclibs:
      - go@go1.25.12
    rm:
      - app/libs/libbox.aar
      - app/src/google
      - firebase
      - third_party/gomobile/internal/binres/testdata/bootstrap.bin
      - third_party/sing-box/source/clients/android/settings.gradle.kts
      - third_party/whisper.cpp/source/models
    prebuild:
      - printf '\nmotdVersionName=%s\nmotdVersionCode=%s\nmotdSourceCommit=%s\n' "$$VERSION$$"
        "$$VERCODE$$" "$$COMMIT$$" >> ../gradle.properties
      - sdkmanager "platforms;android-23"
      - sdkmanager "platforms;android-37.0" "build-tools;36.0.0"
      - source ../third_party/ai/source.lock && sdkmanager "cmake;$CMAKE_VERSION"
    build:
      - pushd "$$go$$/src"
      - ./make.bash
      - popd
      - export GOROOT="$$go$$"
      - export PATH="$$go$$/bin:$PATH"
      - export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
      - export LIBBOX_OUTPUT_DIR="$PWD/build/generated/libbox"
      - export LIBBOX_NDK_HOME="$$NDK$$"
      - export LIBBOX_PATCH_NDK_HOST_TOOLS=0
      - ../third_party/sing-box/build-libbox.sh
    preassemble:
      - :app:verifyAiNativeArtifacts
    ndk: 28.2.13676358
    gradleprops:
      - motdLibboxSource=true
      - motdLibboxAar=build/generated/libbox/libbox.aar
      - motdLibboxManifest=build/generated/libbox/libbox-v1.13.12.manifest
```

API 23 is required by gomobile; platform 37.0 and build-tools 36.0.0 match
`compileSdk = 37`. SDK CMake `3.31.6` is installed from the pin in
`third_party/ai/source.lock`. The buildserver supplies OpenJDK 21, so the recipe
selects it with `JAVA_HOME` instead of installing a JDK.

## The flavor collapse, as a worked example

The bot copies the previous entry verbatim, so a source-tree change that
invalidates any recipe field has to be applied by hand in a merge request. The
0.13.1 entry is the reference case for what that looks like.

The commit "build: remove Firebase/FCM and collapse the distribution flavor"
deletes the `distribution` flavor dimension, `app/src/google/`, `app/src/foss/`,
and `firebase/`, so the first release containing it needed two changes:

- `gradle: yes` instead of `gradle: [foss]`. `assembleFossRelease` no longer
  exists, so the copied entry would have failed outright.
- `rm:` reduced to `app/libs/libbox.aar`,
  `third_party/gomobile/internal/binres/testdata/bootstrap.bin`, and
  `third_party/sing-box/source/clients/android/settings.gradle.kts`. The
  `app/src/google` and `firebase` paths are gone.

Everything else carried over untouched. That went out as
[!45080](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/45080), whose
pipeline passed `fdroid lint`, `fdroid rewritemeta`, `fdroid build`, and the
`check apk` reproducibility comparison against the upstream-signed APK. v0.13.0
predates the collapse and was skipped rather than backfilled.

The published release asset keeps the name `motd-<tag>-foss.apk` because the
`Binaries:` line pins it; only the Gradle-derived input path changed.

## Native source build

F-Droid's `rm` step removes the checked-in AAR and the upstream whisper.cpp
model-fixture directory before scanning, while retaining its runtime sources
and license file. The `build` step, which runs after scanning
and source-tarball creation, regenerates the AAR in the build directory from the
recursively initialized upstream submodules and F-Droid's pinned Go toolchain.
The `preassemble` task then runs the AI native artifact assertions.

Go must be built from the exact `go1.25.12` source commit
`d80d9a98f7e3a8f9b3a82d2c6079f84eb1101d46` with `src/make.bash`; a Nixpkgs
compiler of the same version can change native layout and fail verification.

The application checkout pins sing-box and gomobile as Git submodules. The
sing-box checkout in turn pins its Android client submodule, so F-Droid's
recursive submodule initialization supplies all three exact revisions without
custom `srclibs` metadata for them. Their revisions are also recorded in
[`third_party/sing-box/source.lock`](../third_party/sing-box/source.lock).
The source builder verifies each checkout, requires Go `1.25.12` and OpenJDK 21,
validates NDK `28.2.13676358`, and rejects any JNI entry other than
`jni/arm64-v8a/libbox.so`. Go fetches the checksummed modules needed by the
normal build; no redundant `go mod download` step is required. The shared
F-Droid NDK is validated but not patched.

The Gradle verifier stays strict for normal GitHub builds: the tracked AAR must
match its pinned SHA-256. `motdLibboxSource=true` relaxes only that
byte-for-byte comparison for a source rebuild; the manifest hash, libbox version
(`v1.13.12`), arm64 ABI, and exact JNI contents are still checked.

## Reproducible signing

The fdroiddata metadata references the upstream release APK and pins the release
certificate:

```yaml
Binaries: https://github.com/trevarj/motd/releases/download/v%v/motd-v%v-foss.apk
AllowedAPKSigningKeys: 4104a03bbc48942df8346fbd331f7761d13f68af4a2ff4d14f730e501ce728c3
```

`Binaries:` is templated with `v%v` and needs no per-version edit.
`AllowedAPKSigningKeys` must never change between versions.

The release workflow derives both Android version fields from
`gradle.properties` and rejects a mismatched tag. Keep the release keystore and
its backups safe: changing the key would prevent installed copies from receiving
updates from either distribution channel, and would break the F-Droid pin.

The Android build disables AGP dependency metadata in APKs and bundles. F-Droid
rejects that extra signing block; dependency provenance remains available in the
pinned fdroiddata recipe and the release's complete libbox source bundle.

## Store listing and changelogs

F-Droid reads the listing from `fastlane/metadata/android/en-US/` in this
repository, not from fdroiddata: `title.txt`, `short_description.txt`,
`full_description.txt`, `images/`, and one `changelogs/<versionCode>.txt` per
release. A missing changelog file means an empty "What's New" for that version,
so add it before tagging (see [`.agents/releases.md`](../.agents/releases.md)).

## FOSS boundary

The app has a single build with no Google or Firebase dependency at all. The
check below guards against one being reintroduced:

```sh
if nix develop -c ./gradlew :app:dependencies --configuration releaseRuntimeClasspath | rg -i 'firebase|play-services'; then
  echo "Google-only dependency reached the runtime classpath" >&2
  exit 1
fi
```

Adding another ABI requires a new source build, artifact verification, and an
explicit metadata update.

## Local metadata checks

Run these from an fdroiddata checkout. `fdroidserver` is not in the project
flake; invoke it with `nix shell` rather than editing `flake.nix`:

```sh
nix shell nixpkgs#fdroidserver -c fdroid readmeta io.github.trevarj.motd
nix shell nixpkgs#fdroidserver -c fdroid lint io.github.trevarj.motd
```

`fdroid build --test --verbose io.github.trevarj.motd:<versionCode>` exercises
the provisioned Go, JDK, NDK, SDK, and offline module cache, so it is expected
to run on an F-Droid buildserver rather than locally.

# F-Droid packaging

The official F-Droid package is the `foss` Gradle flavor. It is built from the
full source checkout and verified against the upstream-signed GitHub release
APK. F-Droid publishes that upstream signature only after the unsigned rebuild
matches, so GitHub and F-Droid installations remain update-compatible.

## Versioning

F-Droid metadata must pin the application checkout with a full 40-character
commit SHA. Each build also supplies `versionName`, `versionCode`, and the
source commit through `gradle.properties` before the source scan:

```yaml
prebuild:
  - printf '\nmotdVersionName=%s\nmotdVersionCode=%s\nmotdSourceCommit=%s\n' "$$VERSION$$" "$$VERCODE$$" "$$COMMIT$$" >> ../gradle.properties
```

Release tags use `vMAJOR.MINOR.PATCH`. The current deterministic source-build
defaults are `0.10.8` and `10008`; the F-Droid metadata is authoritative for
each published build. A recipe should use:

```yaml
AutoUpdateMode: Version
UpdateCheckMode: Tags ^v[0-9]+\.[0-9]+\.[0-9]+$
UpdateCheckData: gradle.properties|motdVersionCode=(\d+)|.|motdVersionName=([0-9]+\.[0-9]+\.[0-9]+)
```

The version code scheme is `major * 1000000 + minor * 1000 + patch`, which is
monotonic for the project's semantic release range and remains within Android's
signed 32-bit limit.

The application entry in the fdroiddata fork must pin the source explicitly:

```yaml
RepoType: git
Repo: https://github.com/trevarj/motd.git
Builds:
  - versionName: 0.10.8
    versionCode: 10008
    commit: <full upstream commit SHA>
    subdir: app
    submodules: true
```

Replace the placeholder with the full SHA of the merged upstream commit before
running `fdroid readmeta`; never use a branch name or abbreviated SHA.

## Native source build

F-Droid's `rm` step removes the checked-in AAR before scanning. The `build`
step, which runs after scanning and source-tarball creation, regenerates the
AAR in the build directory from the recursively initialized upstream
submodules and F-Droid's pinned Go toolchain:

```yaml
rm: app/libs/libbox.aar,app/src/google,firebase
ndk: 28.0.13004108
submodules: true
srclibs: go@go1.25.12
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
gradle: foss
gradleprops: motdLibboxSource=true,motdLibboxAar=build/generated/libbox/libbox.aar,motdLibboxManifest=build/generated/libbox/libbox-v1.13.12.manifest
```

The recipe installs `platforms;android-23`, `platforms;android-37.0`, and
`build-tools;36.0.0`. API 23 remains required by gomobile, while platform
37.0 and build-tools 36.0.0 match the upstream Android configuration. The
F-Droid buildserver supplies OpenJDK 21; the recipe selects it with
`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` and does not install a separate
JDK.

Go must be built from the exact `go1.25.12` source commit
`d80d9a98f7e3a8f9b3a82d2c6079f84eb1101d46` with `src/make.bash`; a Nixpkgs
compiler of the same version can change native layout and fail verification.

The application checkout pins sing-box and gomobile as Git submodules. The
sing-box checkout in turn pins its Android client submodule, so F-Droid's
recursive submodule initialization supplies all three exact revisions without
custom `srclibs` metadata. Their revisions are also recorded in
[`third_party/sing-box/source.lock`](../third_party/sing-box/source.lock).
The source builder verifies each checkout, requires Go `1.25.12` and OpenJDK
21, validates NDK `28.0.13004108`, and rejects any JNI entry other than
`jni/arm64-v8a/libbox.so`. Go fetches the checksummed modules needed by the
normal build; no redundant `go mod download` step is required. The shared
F-Droid NDK is validated but not patched.

The Gradle verifier remains strict for normal GitHub builds: the tracked AAR
must match its pinned SHA-256. `motdLibboxSource=true` relaxes only that
byte-for-byte comparison for a source rebuild; the manifest hash, libbox
version, arm64 ABI, and exact JNI contents are still checked.

## Reproducible signing

The fdroiddata metadata references the upstream release APK and pins the
release certificate:

```yaml
Binaries: https://github.com/trevarj/motd/releases/download/v%v/motd-v%v-foss.apk
AllowedAPKSigningKeys: 4104a03bbc48942df8346fbd331f7761d13f68af4a2ff4d14f730e501ce728c3
```

The release workflow derives both Android version fields from
`gradle.properties` and rejects a mismatched tag. Keep the release keystore and
its backups safe: changing the key would prevent installed copies from
receiving updates from either distribution channel.

The Android build disables AGP dependency metadata in APKs and bundles. F-Droid
rejects that extra signing block; dependency provenance remains available in the
pinned fdroiddata recipe and the release's complete libbox source bundle.

## FOSS boundary

The recipe builds `foss`, not `google`. Firebase code is under `app/src/google`
and Firebase dependencies are attached only to `googleImplementation`; neither
appears in the FOSS runtime classpath. A local dependency check is:

```sh
if nix develop -c ./gradlew :app:dependencies --configuration fossReleaseRuntimeClasspath | rg -i 'firebase|play-services'; then
  echo "Google-only dependency reached the FOSS runtime classpath" >&2
  exit 1
fi
```

The initial package is intentionally arm64-v8a-only because that is the only
ABI covered by the pinned libbox source and manifest. Adding another ABI
requires a new source build, artifact verification, and an explicit metadata
update.

## Submission checks

Run these commands from the fdroiddata fork after adding the application
metadata:

```sh
fdroid readmeta io.github.trevarj.motd
fdroid lint io.github.trevarj.motd
fdroid build --test --verbose io.github.trevarj.motd:10008
```

The last check is expected to be performed on an F-Droid buildserver because
it exercises the provisioned Go, JDK, NDK, SDK, and offline module cache.

#!/usr/bin/env bash
# Build the vendorable libbox AAR from the exact sing-box source in source.lock.
# Run through: nix develop .#libbox -c ./third_party/sing-box/build-libbox.sh
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
lock_file="$root_dir/third_party/sing-box/source.lock"
output_dir="${LIBBOX_OUTPUT_DIR:-$root_dir/app/libs}"
# libbox's upstream builder supports a platform selector.  MOTD currently
# ships to the connected arm64-v8a device only, so keep the generated AAR
# deliberately single-ABI rather than paying for three unused native slices.
# This is an ABI contract, not a best-effort optimization: changing it requires
# an explicit build invocation and updates the provenance manifest.
android_platform="${LIBBOX_ANDROID_PLATFORM:-android/arm64}"
[[ "$android_platform" == "android/arm64" ]] || {
  echo "only the supported MOTD arm64 build is allowed (LIBBOX_ANDROID_PLATFORM=android/arm64)" >&2
  exit 1
}

# shellcheck disable=SC1090
source "$lock_file"

for required in git go java make sha256sum tar unzip; do
  command -v "$required" >/dev/null || {
    echo "missing $required; run nix develop .#libbox -c $0" >&2
    exit 1
  }
done

go_version_output="$(go version)"
if [[ "$go_version_output" =~ go([0-9]+)\.([0-9]+)(\.([0-9]+))? ]]; then
  go_major="${BASH_REMATCH[1]}"
  go_minor="${BASH_REMATCH[2]}"
  go_patch="${BASH_REMATCH[4]:-0}"
  detected_go_version="go${go_major}.${go_minor}.${go_patch}"
  [[ "$detected_go_version" == "$GO_VERSION" ]] || {
    echo "Go $GO_VERSION is required (found $go_version_output)" >&2
    exit 1
  }
else
  echo "could not determine Go version: $go_version_output" >&2
  exit 1
fi

offline="${LIBBOX_OFFLINE:-0}"
if [[ "$offline" == "1" ]]; then
  export GOPROXY=off
  export GOSUMDB=off
  export GOTOOLCHAIN=local
fi

verify_ndk_home() {
  local ndk_home="$1"
  [[ -f "$ndk_home/source.properties" ]] || return 1
  grep -F "Pkg.Revision = $ANDROID_NDK_VERSION" "$ndk_home/source.properties" >/dev/null
}

ndk_archive_verified_sha256=""
ndk_from_archive=0

# Google distributes the Linux NDK host tools for FHS systems.  They request
# /lib64/ld-linux-x86-64.so.2 and depend on libz, neither of which exists at
# those paths on Guix.  Patch only an already checksum-verified *extraction*;
# the archive remains byte-for-byte upstream and a new extraction can always
# be made from it.  The shell provides all paths from the flake-pinned Nixpkgs.
patch_ndk_host_tools() {
  local toolchain="$1/toolchains/llvm/prebuilt/linux-x86_64"
  local executable

  [[ "${LIBBOX_PATCH_NDK_HOST_TOOLS:-1}" == "1" ]] || return 0
  [[ -d "$toolchain/bin" ]] || {
    echo "NDK linux-x86_64 host toolchain is missing" >&2
    exit 1
  }
  for required in patchelf LIBBOX_NDK_HOST_LOADER LIBBOX_NDK_HOST_RPATH; do
    if [[ "$required" == patchelf ]]; then
      command -v patchelf >/dev/null || {
        echo "missing patchelf; run nix develop .#libbox -c $0" >&2
        exit 1
      }
    elif [[ -z "${!required:-}" ]]; then
      echo "missing $required; run nix develop .#libbox -c $0" >&2
      exit 1
    fi
  done
  [[ -x "$LIBBOX_NDK_HOST_LOADER" ]] || {
    echo "LIBBOX_NDK_HOST_LOADER is not executable: $LIBBOX_NDK_HOST_LOADER" >&2
    exit 1
  }

  # The concrete binaries live in bin; compiler-driver aliases are symlinks.
  # Restricting this to ELF executables avoids altering target libraries or
  # scripts shipped by the NDK.
  while IFS= read -r -d '' executable; do
    if patchelf --print-interpreter "$executable" >/dev/null 2>&1; then
      patchelf --set-interpreter "$LIBBOX_NDK_HOST_LOADER" \
        --set-rpath "$toolchain/lib:$LIBBOX_NDK_HOST_RPATH" \
        "$executable"
    fi
  done < <(find "$toolchain/bin" -type f -perm -u+x -print0)

  "$toolchain/bin/clang" --version >/dev/null || {
    echo "patched NDK clang could not execute" >&2
    exit 1
  }
}

# An explicitly supplied archive wins over ambient SDK variables. This keeps a
# source rebuild from accidentally using a different app-build NDK.
ndk_home=""
if [[ -n "${LIBBOX_NDK_ARCHIVE:-}" ]]; then
  ndk_archive="$LIBBOX_NDK_ARCHIVE"
  [[ -f "$ndk_archive" ]] || { echo "LIBBOX_NDK_ARCHIVE does not exist: $ndk_archive" >&2; exit 1; }
  [[ "$(sha1sum "$ndk_archive" | cut -d ' ' -f1)" == "$ANDROID_NDK_ARCHIVE_SHA1" ]] || {
    echo "Android NDK archive SHA-1 verification failed" >&2; exit 1;
  }
  [[ "$(sha256sum "$ndk_archive" | cut -d ' ' -f1)" == "$ANDROID_NDK_ARCHIVE_SHA256" ]] || {
    echo "Android NDK archive SHA-256 verification failed" >&2; exit 1;
  }
  ndk_archive_verified_sha256="$ANDROID_NDK_ARCHIVE_SHA256"
  ndk_from_archive=1

  cache_root="${LIBBOX_NDK_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/motd/libbox}"
  ndk_home="$cache_root/android-ndk-r28"
  if [[ ! -e "$ndk_home" ]]; then
    mkdir -p "$cache_root"
    staging_dir="$(mktemp -d "$cache_root/.android-ndk-r28.XXXXXX")"
    cleanup_staging() { rm -rf "$staging_dir"; }
    trap cleanup_staging EXIT
    unzip -q "$ndk_archive" -d "$staging_dir"
    verify_ndk_home "$staging_dir/android-ndk-r28" || {
      echo "Android NDK archive did not contain r$ANDROID_NDK_VERSION" >&2; exit 1;
    }
    # Rename only a fully verified extraction into the cache. If another build
    # won the race, retain that cache entry only when it is also valid.
    if ! mv "$staging_dir/android-ndk-r28" "$ndk_home" 2>/dev/null; then
      verify_ndk_home "$ndk_home" || {
        echo "could not create a valid NDK cache at $ndk_home" >&2; exit 1;
      }
    fi
    trap - EXIT
    cleanup_staging
  fi
else
  # F-Droid provisions an SDK-managed NDK rather than the verified Google
  # archive used by the Nix shell. Validate its revision, but never patch or
  # otherwise mutate the shared SDK directory.
  ndk_home="${LIBBOX_NDK_HOME:-${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}}"
fi

[[ -n "$ndk_home" ]] || {
  echo "Android NDK required: set LIBBOX_NDK_HOME or LIBBOX_NDK_ARCHIVE (expected $ANDROID_NDK_ARCHIVE_URL)" >&2
  exit 1
}
verify_ndk_home "$ndk_home" || {
  echo "expected Android NDK $ANDROID_NDK_VERSION" >&2
  exit 1
}
# Persist provenance only after verifying both upstream archive hashes. A later
# build using the cache can prove that its host executables came from the same
# verified archive before mutating their ELF metadata for Nix.
if [[ -n "$ndk_archive_verified_sha256" ]]; then
  printf '%s\n' "$ndk_archive_verified_sha256" > "$ndk_home/.motd-archive-sha256"
fi
if ((ndk_from_archive)); then
  [[ -f "$ndk_home/.motd-archive-sha256" ]] &&
    [[ "$(<"$ndk_home/.motd-archive-sha256")" == "$ANDROID_NDK_ARCHIVE_SHA256" ]] || {
    echo "verified NDK cache provenance is missing or stale" >&2
    exit 1
  }
fi

patch_mode="${LIBBOX_PATCH_NDK_HOST_TOOLS:-auto}"
case "$patch_mode" in
  0) ;;
  1) patch_ndk_host_tools "$ndk_home" ;;
  auto)
    if [[ -n "${LIBBOX_NDK_HOST_LOADER:-}" && -n "${LIBBOX_NDK_HOST_RPATH:-}" ]] &&
      command -v patchelf >/dev/null 2>&1; then
      patch_ndk_host_tools "$ndk_home"
    fi
    ;;
  *)
    echo "LIBBOX_PATCH_NDK_HOST_TOOLS must be 0, 1, or auto" >&2
    exit 1
    ;;
esac
export ANDROID_NDK_HOME="$ndk_home"
export ANDROID_NDK_ROOT="$ndk_home"
export ANDROID_NDK="$ndk_home"

# gomobile supplies an NDK compiler for each Android target.  The interactive
# Guix profile, however, exports host include/library search paths (and Nix's
# compiler wrapper flags) which Clang treats as implicit inputs even when
# gomobile selected the Android compiler.  In particular, that can make an
# Android cgo compile consume the host's gnu/stubs.h.  Do not pass any ambient
# compiler or linker configuration into the target build; keep PATH and the
# explicit ANDROID_NDK_* variables above so gomobile can discover the patched
# NDK normally.
sanitize_android_cgo_environment() {
  local variable
  for variable in \
    CPATH C_INCLUDE_PATH CPLUS_INCLUDE_PATH OBJC_INCLUDE_PATH LIBRARY_PATH \
    NIX_CFLAGS_COMPILE NIX_CFLAGS_LINK NIX_LDFLAGS \
    CFLAGS CPPFLAGS CXXFLAGS OBJCFLAGS LDFLAGS \
    CC CXX AR AS LD NM RANLIB STRIP \
    CGO_CFLAGS CGO_CPPFLAGS CGO_CXXFLAGS CGO_LDFLAGS CGO_FFLAGS CGO_FCFLAGS \
    PKG_CONFIG_PATH PKG_CONFIG_LIBDIR PKG_CONFIG_SYSROOT_DIR; do
    unset "$variable"
  done
}
sanitize_android_cgo_environment

# Fail before downloading/building Go tooling if the NDK driver still sees a
# host header path.  The versioned driver supplies the matching API sysroot.
ndk_clang="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android35-clang"
[[ -x "$ndk_clang" ]] || {
  echo "Android NDK clang is missing: $ndk_clang" >&2
  exit 1
}
printf '%s\n' '#include <stdio.h>' 'int main(void) { return 0; }' |
  "$ndk_clang" -fsyntax-only -x c - || {
    echo "Android NDK clang preflight failed" >&2
    exit 1
  }

# Useful for provisioning a cache on a build worker without fetching source or
# producing an artifact. It deliberately performs every archive/NDK check.
if [[ "${LIBBOX_PREPARE_NDK_ONLY:-}" == "1" ]]; then
  echo "Verified Android NDK: $ANDROID_NDK_HOME"
  exit 0
fi

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/motd-libbox.XXXXXX")"
trap 'rm -rf "$work_dir"' EXIT
source_dir="${LIBBOX_SOURCE_DIR:-$work_dir/sing-box}"
source_archive="${LIBBOX_SOURCE_ARCHIVE:-}"
android_source_archive="${LIBBOX_ANDROID_SOURCE_ARCHIVE:-}"
android_source_dir="${LIBBOX_ANDROID_SOURCE_DIR:-}"
gomobile_source_archive="${LIBBOX_GOMOBILE_SOURCE_ARCHIVE:-}"

if [[ "$offline" == "1" && -z "${LIBBOX_SOURCE_DIR:-}" &&
  -z "$source_archive" && -z "$android_source_archive" ]]; then
  echo "offline libbox builds require LIBBOX_SOURCE_DIR or both source archives" >&2
  exit 1
fi

if [[ -n "$gomobile_source_archive" ]]; then
  [[ -z "${GOMOBILE_SOURCE_DIR:-}" ]] || {
    echo "set only one of LIBBOX_GOMOBILE_SOURCE_ARCHIVE and GOMOBILE_SOURCE_DIR" >&2
    exit 1
  }
  [[ -f "$gomobile_source_archive" ]] || {
    echo "LIBBOX_GOMOBILE_SOURCE_ARCHIVE does not exist: $gomobile_source_archive" >&2
    exit 1
  }
  [[ "$(sha256sum "$gomobile_source_archive" | cut -d ' ' -f1)" == "$GOMOBILE_GIT_ARCHIVE_SHA256" ]] || {
    echo "gomobile source archive verification failed" >&2
    exit 1
  }
  gomobile_source_dir="$work_dir/gomobile"
  mkdir -p "$gomobile_source_dir"
  tar -xf "$gomobile_source_archive" -C "$gomobile_source_dir"
  git -C "$gomobile_source_dir" init --quiet
  git -C "$gomobile_source_dir" config user.name "MOTD source rebuild"
  git -C "$gomobile_source_dir" config user.email "source-rebuild@invalid"
  git -C "$gomobile_source_dir" add --all
  GIT_AUTHOR_DATE="2000-01-01T00:00:00Z" GIT_COMMITTER_DATE="2000-01-01T00:00:00Z" \
    git -C "$gomobile_source_dir" commit --quiet --message "$GOMOBILE_VERSION source archive"
  git -C "$gomobile_source_dir" tag "$GOMOBILE_VERSION"
  export GOMOBILE_SOURCE_DIR="$gomobile_source_dir"
fi

if [[ -n "$source_archive" || -n "$android_source_archive" ]]; then
  [[ -n "$source_archive" && -n "$android_source_archive" ]] || {
    echo "set both LIBBOX_SOURCE_ARCHIVE and LIBBOX_ANDROID_SOURCE_ARCHIVE" >&2
    exit 1
  }
  [[ -f "$source_archive" && -f "$android_source_archive" ]] || {
    echo "one or more libbox source archives do not exist" >&2
    exit 1
  }
  [[ "$(sha256sum "$source_archive" | cut -d ' ' -f1)" == "$SING_BOX_GIT_ARCHIVE_SHA256" ]] || {
    echo "sing-box source archive verification failed" >&2
    exit 1
  }
  [[ "$(sha256sum "$android_source_archive" | cut -d ' ' -f1)" == "$ANDROID_SUBMODULE_GIT_ARCHIVE_SHA256" ]] || {
    echo "Android submodule source archive verification failed" >&2
    exit 1
  }

  mkdir -p "$source_dir"
  tar -xf "$source_archive" -C "$source_dir"
  android_dir="$source_dir/$ANDROID_SUBMODULE_PATH"
  mkdir -p "$android_dir"
  tar -xf "$android_source_archive" -C "$android_dir"

  # Upstream's libbox builder derives the embedded version from `git describe`. A plain source
  # archive intentionally contains no .git directory, so recreate deterministic local metadata
  # and tag the verified tree. The commit identity is not provenance (the archive hashes above
  # are); it only restores the v1.13.12 version string used by the original pinned build.
  git -C "$source_dir" init --quiet
  git -C "$source_dir" config user.name "MOTD source rebuild"
  git -C "$source_dir" config user.email "source-rebuild@invalid"
  git -C "$source_dir" add --all
  GIT_AUTHOR_DATE="2000-01-01T00:00:00Z" GIT_COMMITTER_DATE="2000-01-01T00:00:00Z" \
    git -C "$source_dir" commit --quiet --message "$SING_BOX_VERSION source archive"
  git -C "$source_dir" tag "$SING_BOX_VERSION"
elif [[ -n "${LIBBOX_SOURCE_DIR:-}" ]]; then
  source_dir="$(cd "$LIBBOX_SOURCE_DIR" && pwd)"
  [[ -e "$source_dir/.git" ]] || {
    echo "LIBBOX_SOURCE_DIR must be a git checkout" >&2
    exit 1
  }
  [[ "$(git -C "$source_dir" rev-parse HEAD)" == "$SING_BOX_COMMIT" ]] || {
    echo "sing-box commit verification failed" >&2
    exit 1
  }
  [[ "$(git -C "$source_dir" archive --format=tar HEAD | sha256sum | cut -d ' ' -f1)" == "$SING_BOX_GIT_ARCHIVE_SHA256" ]] || {
    echo "sing-box source archive verification failed" >&2
    exit 1
  }
  if [[ "$(git -C "$source_dir" describe --exact-match --tags HEAD 2>/dev/null || true)" != "$SING_BOX_VERSION" ]]; then
    git -C "$source_dir" tag "$SING_BOX_VERSION" "$SING_BOX_COMMIT" 2>/dev/null || {
      echo "sing-box source checkout is missing tag $SING_BOX_VERSION" >&2
      exit 1
    }
  fi
  android_dir="$source_dir/$ANDROID_SUBMODULE_PATH"
  if [[ -n "$android_source_dir" ]]; then
    android_source_dir="$(cd "$android_source_dir" && pwd)"
    [[ -e "$android_source_dir/.git" ]] || {
      echo "LIBBOX_ANDROID_SOURCE_DIR must be a git checkout" >&2
      exit 1
    }
    rm -rf "$android_dir"
    mkdir -p "$android_dir"
    tar --exclude=.git -C "$android_source_dir" -cf - . | tar -C "$android_dir" -xf -
    android_verify_dir="$android_source_dir"
  else
    android_verify_dir="$android_dir"
  fi
  [[ "$(git -C "$android_verify_dir" rev-parse HEAD)" == "$ANDROID_SUBMODULE_COMMIT" ]] || {
    echo "Android submodule verification failed" >&2
    exit 1
  }
  [[ "$(git -C "$android_verify_dir" archive --format=tar HEAD | sha256sum | cut -d ' ' -f1)" == "$ANDROID_SUBMODULE_GIT_ARCHIVE_SHA256" ]] || {
    echo "Android submodule source archive verification failed" >&2
    exit 1
  }
else
  [[ "$offline" != "1" ]] || {
    echo "offline libbox builds cannot clone sing-box; provide LIBBOX_SOURCE_DIR" >&2
    exit 1
  }
  git clone --no-checkout "$SING_BOX_REPOSITORY" "$source_dir"
  git -C "$source_dir" checkout --detach "$SING_BOX_COMMIT"
  [[ "$(git -C "$source_dir" rev-parse HEAD)" == "$SING_BOX_COMMIT" ]] || {
    echo "sing-box commit verification failed" >&2
    exit 1
  }
  [[ "$(git -C "$source_dir" describe --exact-match --tags HEAD)" == "$SING_BOX_VERSION" ]] || {
    echo "sing-box tag verification failed" >&2
    exit 1
  }
  [[ "$(git -C "$source_dir" archive --format=tar HEAD | sha256sum | cut -d ' ' -f1)" == "$SING_BOX_GIT_ARCHIVE_SHA256" ]] || {
    echo "sing-box source archive verification failed" >&2
    exit 1
  }

  git -C "$source_dir" submodule update --init --recursive "$ANDROID_SUBMODULE_PATH"
  android_dir="$source_dir/$ANDROID_SUBMODULE_PATH"
  [[ "$(git -C "$android_dir" rev-parse HEAD)" == "$ANDROID_SUBMODULE_COMMIT" ]] || {
    echo "Android submodule verification failed" >&2
    exit 1
  }
  [[ "$(git -C "$android_dir" archive --format=tar HEAD | sha256sum | cut -d ' ' -f1)" == "$ANDROID_SUBMODULE_GIT_ARCHIVE_SHA256" ]] || {
    echo "Android submodule source archive verification failed" >&2
    exit 1
  }
fi

[[ -f "$source_dir/go.mod" && -f "$source_dir/go.sum" ]] || {
  echo "verified sing-box source is missing go.mod or go.sum" >&2
  exit 1
}

gopath_bin="$(go env GOPATH | cut -d: -f1)/bin"
mkdir -p "$gopath_bin"
if [[ -n "${GOMOBILE_SOURCE_DIR:-}" ]]; then
  gomobile_source_dir="$(cd "$GOMOBILE_SOURCE_DIR" && pwd)"
  if [[ -z "$gomobile_source_archive" ]]; then
    [[ -e "$gomobile_source_dir/.git" ]] || {
      echo "GOMOBILE_SOURCE_DIR must be a git checkout" >&2
      exit 1
    }
    [[ "$(git -C "$gomobile_source_dir" rev-parse HEAD)" == "$GOMOBILE_COMMIT" ]] || {
      echo "gomobile commit verification failed" >&2
      exit 1
    }
    [[ "$(git -C "$gomobile_source_dir" archive --format=tar HEAD | sha256sum | cut -d ' ' -f1)" == "$GOMOBILE_GIT_ARCHIVE_SHA256" ]] || {
      echo "gomobile source archive verification failed" >&2
      exit 1
    }
  fi
  (
    cd "$gomobile_source_dir"
    go build -o "$gopath_bin/gomobile" ./cmd/gomobile
    go build -o "$gopath_bin/gobind" ./cmd/gobind
  )
elif [[ -n "${GOMOBILE_BIN:-}" || -n "${GOBIND_BIN:-}" ]]; then
  [[ -x "${GOMOBILE_BIN:-}" && -x "${GOBIND_BIN:-}" ]] || {
    echo "GOMOBILE_BIN and GOBIND_BIN must both be executable" >&2
    exit 1
  }
  export PATH="$(dirname "$GOMOBILE_BIN"):$(dirname "$GOBIND_BIN"):$PATH"
else
  [[ "$offline" != "1" ]] || {
    echo "offline libbox builds require GOMOBILE_SOURCE_DIR or pinned tool binaries" >&2
    exit 1
  }
  GOBIN="$gopath_bin" go install "github.com/sagernet/gomobile/cmd/gomobile@$GOMOBILE_VERSION"
  GOBIN="$gopath_bin" go install "github.com/sagernet/gomobile/cmd/gobind@$GOMOBILE_VERSION"
fi
export PATH="$gopath_bin:$PATH"
for required in gomobile gobind; do
  command -v "$required" >/dev/null || {
    echo "gomobile installation failed: missing $required" >&2
    exit 1
  }
done
# sing-box does not use gomobile's optional OpenAL support. Creating the
# toolchain directory directly avoids gomobile init's unpinned
# `go install .../gobind@latest` step and keeps offline builds reproducible.
gomobile_path="$(go env GOPATH | cut -d: -f1)/pkg/gomobile"
rm -rf "$gomobile_path"
mkdir -p "$gomobile_path"

(
  cd "$source_dir"
  # Equivalent to upstream `make lib_android`, with its documented
  # build_libbox -platform selector passed through to gomobile.  The wrapper
  # retains upstream tags, API levels, and generated Kotlin bindings.
  go run ./cmd/internal/build_libbox -target android -platform "$android_platform"
)

mkdir -p "$output_dir"
install -m 0644 "$source_dir/libbox.aar" "$output_dir/libbox.aar"

mapfile -t jni_entries < <(
  unzip -Z1 "$output_dir/libbox.aar" |
    while IFS= read -r entry; do
      [[ "$entry" == jni/* && "$entry" != */ ]] && printf '%s\n' "$entry"
    done
)
expected_jni='jni/arm64-v8a/libbox.so'
[[ "$(printf '%s\n' "${jni_entries[@]}")" == "$expected_jni" ]] || {
  echo "unexpected libbox JNI entries: ${jni_entries[*]}" >&2
  exit 1
}
abis='arm64-v8a'

aar_sha256="$(sha256sum "$output_dir/libbox.aar" | cut -d ' ' -f1)"
manifest="$output_dir/libbox-v${SING_BOX_VERSION#v}.manifest"
cat > "$manifest" <<EOF
sing-box-version=$SING_BOX_VERSION
sing-box-commit=$SING_BOX_COMMIT
android-submodule-commit=$ANDROID_SUBMODULE_COMMIT
go-version=$GO_VERSION
gomobile-version=$GOMOBILE_VERSION
gomobile-commit=${GOMOBILE_COMMIT:-unknown}
android-ndk-version=$ANDROID_NDK_VERSION
build-platform=$android_platform
abis=$abis
libbox-aar-sha256=$aar_sha256
EOF

echo "Built $output_dir/libbox.aar"
echo "SHA-256: $aar_sha256"
echo "Manifest: $manifest"

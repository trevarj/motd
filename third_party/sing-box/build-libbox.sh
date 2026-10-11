#!/usr/bin/env bash
# Build the vendorable libbox AAR from the exact sing-box source in source.lock.
# Run through: nix develop .#libbox -c bash ./third_party/sing-box/build-libbox.sh
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
lock_file="$root_dir/third_party/sing-box/source.lock"
output_dir="${LIBBOX_OUTPUT_DIR:-$root_dir/app/libs}"
# libbox's upstream builder supports a platform selector.  motd currently
# ships to the connected arm64-v8a device only, so keep the generated AAR
# deliberately single-ABI rather than paying for three unused native slices.
# This is an ABI contract, not a best-effort optimization: changing it requires
# an explicit build invocation and updates the provenance manifest.
android_platform="${LIBBOX_ANDROID_PLATFORM:-android/arm64}"
[[ "$android_platform" == "android/arm64" ]] || {
  echo "only the supported motd arm64 build is allowed (LIBBOX_ANDROID_PLATFORM=android/arm64)" >&2
  exit 1
}

# shellcheck disable=SC1090
source "$lock_file"

for required in cp git go head java make sed sha256sum tar unzip; do
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
export GOTOOLCHAIN=local

if [[ -n "${JAVA_HOME:-}" ]]; then
  java_command="$JAVA_HOME/bin/java"
else
  java_command="$(command -v java)"
fi
[[ -x "$java_command" ]] || {
  echo "Java executable is missing: $java_command" >&2
  exit 1
}
java_version_output="$("$java_command" --version 2>&1 | head -n 1)"
[[ "$java_version_output" == *"openjdk $JAVA_VERSION"* ]] || {
  echo "OpenJDK $JAVA_VERSION is required (found $java_version_output)" >&2
  exit 1
}

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
ndk_archive_root="${ANDROID_NDK_ARCHIVE_ROOT:-android-ndk-r28c}"

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
  ndk_home="$cache_root/$ndk_archive_root"
  if [[ ! -e "$ndk_home" ]]; then
    mkdir -p "$cache_root"
    staging_dir="$(mktemp -d "$cache_root/.${ndk_archive_root}.XXXXXX")"
    cleanup_staging() { rm -rf "$staging_dir"; }
    trap cleanup_staging EXIT
    unzip -q "$ndk_archive" -d "$staging_dir"
    verify_ndk_home "$staging_dir/$ndk_archive_root" || {
      echo "Android NDK archive did not contain $ndk_archive_root" >&2; exit 1;
    }
    # Rename only a fully verified extraction into the cache. If another build
    # won the race, retain that cache entry only when it is also valid.
    if ! mv "$staging_dir/$ndk_archive_root" "$ndk_home" 2>/dev/null; then
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
patched_gomobile_env_file=""
gomobile_env_backup=""
cleanup_work_dir() {
  if [[ -n "$patched_gomobile_env_file" && -f "$gomobile_env_backup" ]]; then
    cp "$gomobile_env_backup" "$patched_gomobile_env_file"
  fi
  # Go intentionally marks downloaded module directories read-only. Make the
  # disposable cache writable again so a successful build does not fail while
  # removing its temporary workspace.
  chmod -R u+w "$work_dir" 2>/dev/null || true
  rm -rf "$work_dir"
}
trap cleanup_work_dir EXIT

source_archive="${LIBBOX_SOURCE_ARCHIVE:-}"
android_source_archive="${LIBBOX_ANDROID_SOURCE_ARCHIVE:-}"
android_source_dir="${LIBBOX_ANDROID_SOURCE_DIR:-}"
gomobile_source_archive="${LIBBOX_GOMOBILE_SOURCE_ARCHIVE:-}"
source_dir="${LIBBOX_SOURCE_DIR:-}"
source_checkout=0
if [[ -n "$source_archive" || -n "$android_source_archive" ]]; then
  [[ -z "$source_dir" ]] || {
    echo "set either LIBBOX_SOURCE_DIR or source archives, not both" >&2
    exit 1
  }
  source_dir="$work_dir/sing-box"
elif [[ -n "$source_dir" ]]; then
  source_checkout=1
elif [[ -e "$root_dir/third_party/sing-box/source/.git" ]]; then
  source_dir="$root_dir/third_party/sing-box/source"
  source_checkout=1
else
  source_dir="$work_dir/sing-box"
fi
if [[ -z "${GOMOBILE_SOURCE_DIR:-}" && -z "$gomobile_source_archive" &&
  -e "$root_dir/third_party/gomobile/.git" ]]; then
  export GOMOBILE_SOURCE_DIR="$root_dir/third_party/gomobile"
fi

if [[ "$offline" == "1" && "$source_checkout" == "0" &&
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

elif ((source_checkout)); then
  source_dir="$(cd "$source_dir" && pwd)"
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

# Keep generated gomobile tools and caches inside the disposable build tree;
# never write them to a developer's ambient GOPATH.
export GOPATH="$work_dir/gopath"
gopath_bin="$GOPATH/bin"
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
  # The pinned gomobile release clears the subprocess environment before
  # invoking gobind. Preserve the isolated GOPATH and configured module policy.
  gomobile_env_file="$gomobile_source_dir/cmd/gomobile/bind_androidapp.go"
  [[ -f "$gomobile_env_file" ]] || {
    echo "gomobile Android bind environment setup is missing" >&2
    exit 1
  }
  gomobile_env_backup="$work_dir/gomobile-bind-androidapp.go"
  cp "$gomobile_env_file" "$gomobile_env_backup"
  patched_gomobile_env_file="$gomobile_env_file"
  if grep -F 'cmd.Env = append(cmd.Env, "GOOS=android")' "$gomobile_env_file" >/dev/null; then
    sed -i 's/cmd.Env = append(cmd.Env, "GOOS=android")/cmd.Env = append(os.Environ(), "GOOS=android")/' \
      "$gomobile_env_file"
  elif ! grep -F 'cmd.Env = append(os.Environ(), "GOOS=android")' "$gomobile_env_file" >/dev/null; then
    echo "unrecognized gomobile Android bind environment setup" >&2
    exit 1
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
gomobile_path="$GOPATH/pkg/gomobile"
rm -rf "$gomobile_path"
mkdir -p "$gomobile_path"
android_jar="${ANDROID_HOME:?Android SDK with platform 23 is required}/platforms/android-$LIBBOX_ANDROID_API/android.jar"
[[ -f "$android_jar" ]] || {
  echo "Android SDK platform $LIBBOX_ANDROID_API android.jar is required" >&2
  exit 1
}

(
  cd "$source_dir"
  # Bind the complete upstream libbox API with only motd's required features.
  # Keep the upstream release flags and API 23 target; no legacy AAR is shipped.
  gomobile bind -v -o "$work_dir/libbox.aar" \
    -target "$android_platform" -androidapi "$LIBBOX_ANDROID_API" \
    -bootclasspath "$android_jar" \
    -javapkg=io.nekohasekai -libname=box -trimpath -buildvcs=false \
    -ldflags "-X github.com/sagernet/sing-box/constant.Version=${SING_BOX_VERSION#v} -X internal/godebug.defaultGODEBUG=multipathtcp=0 -s -w -buildid= -checklinkname=0 -linkmode=external -extldflags=$LIBBOX_EXTLDFLAGS" \
    -tags "$LIBBOX_BUILD_TAGS" ./experimental/libbox
)

mkdir -p "$output_dir"
install -m 0644 "$work_dir/libbox.aar" "$output_dir/libbox.aar"

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
libbox-version=${SING_BOX_VERSION#v}
sing-box-commit=$SING_BOX_COMMIT
android-submodule-commit=$ANDROID_SUBMODULE_COMMIT
go-version=$GO_VERSION
go-commit=$GO_COMMIT
go-source-archive-sha256=$GO_GIT_ARCHIVE_SHA256
java-version=$JAVA_VERSION
gomobile-version=$GOMOBILE_VERSION
gomobile-commit=${GOMOBILE_COMMIT:-unknown}
android-ndk-version=$ANDROID_NDK_VERSION
build-platform=$android_platform
android-api=$LIBBOX_ANDROID_API
android-bootclasspath=platforms/android-$LIBBOX_ANDROID_API/android.jar
build-tags=$LIBBOX_BUILD_TAGS
go-build-flags=-trimpath -buildvcs=false
go-ldflags=-X github.com/sagernet/sing-box/constant.Version=${SING_BOX_VERSION#v} -X internal/godebug.defaultGODEBUG=multipathtcp=0 -s -w -buildid= -checklinkname=0 -linkmode=external
external-ldflags=$LIBBOX_EXTLDFLAGS
abis=$abis
libbox-aar-sha256=$aar_sha256
EOF

echo "Built $output_dir/libbox.aar"
echo "SHA-256: $aar_sha256"
echo "Manifest: $manifest"

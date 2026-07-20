#!/usr/bin/env bash
# Produce the deterministic complete-source asset that accompanies every APK containing libbox.
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
lock_file="$root_dir/third_party/sing-box/source.lock"

release_tag="${1:-}"
output_dir="${2:-$root_dir/release-assets}"
[[ -n "$release_tag" ]] || {
  echo "usage: $0 <release-tag> [output-dir]" >&2
  exit 2
}
[[ "$release_tag" =~ ^[A-Za-z0-9._-]+$ ]] || {
  echo "release tag may contain only letters, digits, dot, underscore, and dash" >&2
  exit 2
}

# shellcheck disable=SC1090
source "$lock_file"

for required in git gzip install mkdir mktemp sha256sum tar; do
  command -v "$required" >/dev/null || { echo "missing required command: $required" >&2; exit 1; }
done

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/motd-libbox-source.XXXXXX")"
trap 'rm -rf "$work_dir"' EXIT
source_dir="$work_dir/sing-box"

git clone --no-checkout --depth 1 --branch "$SING_BOX_VERSION" "$SING_BOX_REPOSITORY" "$source_dir"
git -C "$source_dir" checkout --detach "$SING_BOX_COMMIT"
[[ "$(git -C "$source_dir" rev-parse HEAD)" == "$SING_BOX_COMMIT" ]] || {
  echo "sing-box commit verification failed" >&2; exit 1;
}
[[ "$(git -C "$source_dir" describe --exact-match --tags HEAD)" == "$SING_BOX_VERSION" ]] || {
  echo "sing-box tag verification failed" >&2; exit 1;
}

git -C "$source_dir" submodule update --init --recursive --depth 1 "$ANDROID_SUBMODULE_PATH"
android_dir="$source_dir/$ANDROID_SUBMODULE_PATH"
[[ "$(git -C "$android_dir" rev-parse HEAD)" == "$ANDROID_SUBMODULE_COMMIT" ]] || {
  echo "Android submodule verification failed" >&2; exit 1;
}
# Fail rather than silently omit a future nested source dependency. If upstream adds one, pin it
# in source.lock and teach this packager to archive it explicitly.
[[ -z "$(git -C "$android_dir" submodule status --recursive 2>/dev/null)" ]] || {
  echo "Android source has nested submodules that are not pinned in source.lock" >&2; exit 1;
}

upstream_dir="$work_dir/upstream"
mkdir -p "$upstream_dir"
sing_box_tar="$upstream_dir/sing-box-${SING_BOX_VERSION}.tar"
android_tar="$upstream_dir/sing-box-for-android-${ANDROID_SUBMODULE_COMMIT}.tar"
git -C "$source_dir" archive --format=tar --output="$sing_box_tar" HEAD
git -C "$android_dir" archive --format=tar --output="$android_tar" HEAD

gomobile_dir="$work_dir/gomobile"
git clone --no-checkout --depth 1 --branch "$GOMOBILE_VERSION" "$GOMOBILE_REPOSITORY" "$gomobile_dir"
git -C "$gomobile_dir" checkout --detach "$GOMOBILE_COMMIT"
[[ "$(git -C "$gomobile_dir" rev-parse HEAD)" == "$GOMOBILE_COMMIT" ]] || {
  echo "gomobile commit verification failed" >&2; exit 1;
}
[[ "$(git -C "$gomobile_dir" describe --exact-match --tags HEAD)" == "$GOMOBILE_VERSION" ]] || {
  echo "gomobile tag verification failed" >&2; exit 1;
}
gomobile_tar="$upstream_dir/gomobile-${GOMOBILE_VERSION}.tar"
git -C "$gomobile_dir" archive --format=tar --output="$gomobile_tar" HEAD

go_dir="$work_dir/go"
git clone --no-checkout --depth 1 --branch "$GO_VERSION" "$GO_REPOSITORY" "$go_dir"
git -C "$go_dir" checkout --detach "$GO_COMMIT"
go_tar="$upstream_dir/go-${GO_VERSION}.tar"
git -C "$go_dir" archive --format=tar --output="$go_tar" HEAD

[[ "$(sha256sum "$sing_box_tar" | cut -d ' ' -f1)" == "$SING_BOX_GIT_ARCHIVE_SHA256" ]] || {
  echo "sing-box source archive verification failed" >&2; exit 1;
}
[[ "$(sha256sum "$android_tar" | cut -d ' ' -f1)" == "$ANDROID_SUBMODULE_GIT_ARCHIVE_SHA256" ]] || {
  echo "Android submodule source archive verification failed" >&2; exit 1;
}
[[ "$(sha256sum "$gomobile_tar" | cut -d ' ' -f1)" == "$GOMOBILE_GIT_ARCHIVE_SHA256" ]] || {
  echo "gomobile source archive verification failed" >&2; exit 1;
}
[[ "$(sha256sum "$go_tar" | cut -d ' ' -f1)" == "$GO_GIT_ARCHIVE_SHA256" ]] || {
  echo "Go source archive verification failed" >&2; exit 1;
}

package_name="motd-libbox-source-${release_tag}"
package_dir="$work_dir/$package_name"
motd_dir="$package_dir/motd"
mkdir -p "$package_dir/upstream" "$motd_dir/third_party/sing-box" "$motd_dir/app/libs"
install -m 0644 "$sing_box_tar" "$package_dir/upstream/$(basename "$sing_box_tar")"
install -m 0644 "$android_tar" "$package_dir/upstream/$(basename "$android_tar")"
install -m 0644 "$gomobile_tar" "$package_dir/upstream/$(basename "$gomobile_tar")"
install -m 0644 "$go_tar" "$package_dir/upstream/$(basename "$go_tar")"
install -m 0644 "$root_dir/LICENSE" "$motd_dir/LICENSE"
install -m 0644 "$root_dir/THIRD_PARTY_NOTICES.md" "$motd_dir/THIRD_PARTY_NOTICES.md"
install -m 0644 "$root_dir/flake.nix" "$motd_dir/flake.nix"
install -m 0644 "$root_dir/flake.lock" "$motd_dir/flake.lock"
install -m 0755 "$root_dir/third_party/sing-box/build-libbox.sh" "$motd_dir/third_party/sing-box/build-libbox.sh"
install -m 0755 "$root_dir/third_party/sing-box/package-source.sh" "$motd_dir/third_party/sing-box/package-source.sh"
install -m 0644 "$root_dir/third_party/sing-box/source.lock" "$motd_dir/third_party/sing-box/source.lock"
install -m 0644 "$root_dir/third_party/sing-box/README.md" "$motd_dir/third_party/sing-box/README.md"
install -m 0644 "$root_dir/app/libs/libbox-v${SING_BOX_VERSION#v}.manifest" \
  "$motd_dir/app/libs/libbox-v${SING_BOX_VERSION#v}.manifest"

cat > "$package_dir/README.md" <<EOF
# MOTD libbox corresponding source — ${release_tag}

This archive is the complete libbox source snapshot distributed beside MOTD ${release_tag}.
It contains the exact git archives for Go, sing-box, its Android submodule, and gomobile, plus MOTD's
pinned build inputs and rebuild procedure. The inner archive hashes are fixed in source.lock.

To rebuild, provide the verified Android NDK r28 archive described in
motd/third_party/sing-box/README.md. Build Go from the included source first,
then use that toolchain (Nix's Go is bootstrap-only):

    cd motd
    mkdir -p /tmp/motd-go-toolchain
    tar -xf ../upstream/$(basename "$go_tar") -C /tmp/motd-go-toolchain
    (cd /tmp/motd-go-toolchain/src && ./make.bash)
    nix develop .#libbox -c bash -c '
      export GOROOT=/tmp/motd-go-toolchain
      export PATH="\$GOROOT/bin:\$PATH"
      export LIBBOX_SOURCE_ARCHIVE=../upstream/$(basename "$sing_box_tar")
      export LIBBOX_ANDROID_SOURCE_ARCHIVE=../upstream/$(basename "$android_tar")
      export LIBBOX_GOMOBILE_SOURCE_ARCHIVE=../upstream/$(basename "$gomobile_tar")
      export LIBBOX_NDK_ARCHIVE=/path/to/android-ndk-r28-linux.zip
      exec ./third_party/sing-box/build-libbox.sh
    '
EOF

cat > "$package_dir/SOURCE-MANIFEST.txt" <<EOF
release-tag=${release_tag}
sing-box-version=${SING_BOX_VERSION}
sing-box-commit=${SING_BOX_COMMIT}
sing-box-archive=$(basename "$sing_box_tar")
sing-box-archive-sha256=${SING_BOX_GIT_ARCHIVE_SHA256}
android-submodule-commit=${ANDROID_SUBMODULE_COMMIT}
android-submodule-archive=$(basename "$android_tar")
android-submodule-archive-sha256=${ANDROID_SUBMODULE_GIT_ARCHIVE_SHA256}
gomobile-archive=$(basename "$gomobile_tar")
gomobile-archive-sha256=${GOMOBILE_GIT_ARCHIVE_SHA256}
gomobile-version=${GOMOBILE_VERSION}
gomobile-commit=${GOMOBILE_COMMIT}
go-version=${GO_VERSION}
go-commit=${GO_COMMIT}
go-archive=$(basename "$go_tar")
go-archive-sha256=${GO_GIT_ARCHIVE_SHA256}
android-ndk-version=${ANDROID_NDK_VERSION}
EOF

mkdir -p "$output_dir"
output_dir="$(cd "$output_dir" && pwd)"
archive_name="${package_name}.tar.gz"
outer_tar="$work_dir/${package_name}.tar"
tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner --format=gnu \
  -C "$work_dir" -cf "$outer_tar" "$package_name"
gzip -n -9 < "$outer_tar" > "$output_dir/$archive_name"

echo "$output_dir/$archive_name"
echo "SHA-256: $(sha256sum "$output_dir/$archive_name" | cut -d ' ' -f1)"

#!/usr/bin/env bash
# Package the pinned local AI runtime sources and extend existing release compliance assets.
set -euo pipefail
export LC_ALL=C
umask 022

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
lock_file="$root_dir/third_party/ai/source.lock"
release_tag="${1:-}"
output_dir="${2:-$root_dir/release-assets}"
repository="${GITHUB_REPOSITORY:-}"

fail() {
  echo "$*" >&2
  exit 1
}

[[ -n "$release_tag" && -n "$repository" ]] || {
  echo "usage: GITHUB_REPOSITORY=owner/repo $0 <release-tag> [output-dir]" >&2
  exit 2
}
[[ "$release_tag" =~ ^[A-Za-z0-9._-]+$ ]] || fail "invalid release tag"
[[ "$repository" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || fail "invalid repository slug"

for required in awk find git grep gzip install mkdir mktemp mv rm sha256sum tar; do
  command -v "$required" >/dev/null || fail "missing required command: $required"
done

root_inputs=(
  ai-whisper
  third_party/ai/source.lock
  third_party/ai/patches
  third_party/ai/prepare-release-assets.sh
  THIRD_PARTY_NOTICES.md
)
git -C "$root_dir" diff --quiet -- "${root_inputs[@]}" || fail "AI release sources have unstaged changes"
git -C "$root_dir" diff --cached --quiet HEAD -- "${root_inputs[@]}" || fail "AI release sources have staged changes"
[[ -z "$(git -C "$root_dir" ls-files --others --exclude-standard -- "${root_inputs[@]}")" ]] || \
  fail "AI release sources contain untracked files"

# shellcheck disable=SC1090
source "$lock_file"
for required in WHISPER_REPOSITORY WHISPER_COMMIT WHISPER_LICENSE WHISPER_LICENSE_FILE \
  ANDROID_NDK_VERSION CMAKE_VERSION; do
  [[ -n "${!required:-}" ]] || fail "source.lock does not define $required"
done
[[ "$WHISPER_COMMIT" =~ ^[0-9a-f]{40}$ ]] || fail "source.lock contains an invalid commit"
[[ "$WHISPER_LICENSE" == MIT ]] || fail "whisper.cpp must remain pinned as MIT licensed"
[[ "$WHISPER_LICENSE_FILE" == third_party/whisper.cpp/source/LICENSE ]] || \
  fail "unexpected whisper.cpp license path"

verify_source() {
  local name="$1" relative="$2" expected_commit="$3" expected_repository="$4" license_file="$5"
  local source_dir="$root_dir/$relative" actual_commit parent_commit configured_repository first_line

  [[ -d "$source_dir" ]] || fail "$name submodule is not initialized"
  configured_repository="$(git -C "$root_dir" config -f .gitmodules --get "submodule.$relative.url")"
  [[ "$configured_repository" == "$expected_repository" ]] || fail "$name repository does not match source.lock"
  parent_commit="$(git -C "$root_dir" ls-tree HEAD -- "$relative" | awk '$1 == "160000" && $2 == "commit" { print $3 }')"
  [[ "$parent_commit" == "$expected_commit" ]] || fail "$name gitlink does not match source.lock"
  actual_commit="$(git -C "$source_dir" rev-parse HEAD)"
  [[ "$actual_commit" == "$expected_commit" ]] || fail "$name checkout does not match source.lock"
  [[ -z "$(git -C "$source_dir" status --porcelain=v1 --untracked-files=all)" ]] || \
    fail "$name source checkout is dirty"
  [[ -z "$(git -C "$source_dir" submodule status --recursive)" ]] || \
    fail "$name has nested source submodules that this archive does not yet package"

  [[ -f "$root_dir/$license_file" ]] || fail "$name license is missing"
  git -C "$source_dir" ls-files --error-unmatch LICENSE >/dev/null || fail "$name license is not pinned"
  IFS= read -r first_line < "$root_dir/$license_file"
  [[ "$first_line" == "MIT License" ]] || fail "$name license is not MIT"
  grep -Fq "Permission is hereby granted, free of charge" "$root_dir/$license_file" || \
    fail "$name MIT grant is incomplete"
  grep -Fq 'THE SOFTWARE IS PROVIDED "AS IS"' "$root_dir/$license_file" || \
    fail "$name MIT disclaimer is incomplete"
}

verify_source "whisper.cpp" "third_party/whisper.cpp/source" "$WHISPER_COMMIT" "$WHISPER_REPOSITORY" "$WHISPER_LICENSE_FILE"

mkdir -p "$output_dir"
output_dir="$(cd "$output_dir" && pwd)"
notice_name="motd-${release_tag}-THIRD_PARTY_NOTICES.md"
notice_path="$output_dir/$notice_name"
checksums_path="$output_dir/SHA256SUMS"
compliance_path="$output_dir/release-compliance.md"
libbox_name="motd-libbox-source-${release_tag}.tar.gz"
[[ -f "$output_dir/$libbox_name" && -f "$notice_path" && -f "$checksums_path" && -f "$compliance_path" ]] || \
  fail "prepare the existing licensed release assets before the AI source asset"
awk -v name="$libbox_name" '$2 == name { found = 1 } END { exit !found }' "$checksums_path" || \
  fail "SHA256SUMS does not contain the libbox source entry"

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/motd-ai-source.XXXXXX")"
trap 'rm -rf "$work_dir"' EXIT
package_name="motd-ai-source-${release_tag}"
package_dir="$work_dir/$package_name"
mkdir -p "$package_dir"

copy_tracked() {
  local repository_dir="$1" destination="$2"
  shift 2
  mkdir -p "$destination"
  git -C "$repository_dir" ls-files -z -- "$@" |
    tar --create --file=- --null --directory="$repository_dir" --files-from=- |
    tar --extract --file=- --directory="$destination"
}

copy_tracked "$root_dir" "$package_dir" \
  ai-whisper third_party/ai/source.lock third_party/ai/patches \
  third_party/ai/prepare-release-assets.sh THIRD_PARTY_NOTICES.md
copy_tracked "$root_dir/third_party/whisper.cpp/source" "$package_dir/third_party/whisper.cpp/source" .

whisper_license_sha256="$(sha256sum "$root_dir/$WHISPER_LICENSE_FILE" | awk '{ print $1 }')"
cat > "$package_dir/SOURCE-MANIFEST.txt" <<EOF
release-tag=${release_tag}
whisper-repository=${WHISPER_REPOSITORY}
whisper-commit=${WHISPER_COMMIT}
whisper-license=${WHISPER_LICENSE}
whisper-license-sha256=${whisper_license_sha256}
android-ndk-version=${ANDROID_NDK_VERSION}
cmake-version=${CMAKE_VERSION}
model-weights=excluded
EOF

is_model_payload() {
  local path="${1,,}"
  case "$path" in
    *.gguf|*.ggml|*.bin|*.safetensors|*.ckpt|*.pt|*.pth|*.onnx|*.tflite|*.mlmodel|*.h5|*.hdf5|*.npy|*.npz|*.weights|*.pb|*.tensor|*.engine|*.blob|\
    *.mlmodelc|*.mlmodelc/*|*.mlpackage|*.mlpackage/*|\
    */models/*.zip|*/models/*.tar|*/models/*.tar.gz|*/models/*.tgz|\
    *coreml*.zip|*openvino*.zip|*openvino*.xml)
      return 0
      ;;
  esac
  return 1
}

while IFS= read -r -d '' candidate; do
  if is_model_payload "${candidate#"$package_dir/"}"; then
    rm -rf -- "$candidate"
  fi
done < <(find "$package_dir" -depth \( -type f -o -type l -o -type d \) -print0)

archive_name="${package_name}.tar.gz"
outer_tar="$work_dir/${package_name}.tar"
archive_tmp="$work_dir/$archive_name"
tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner --format=gnu \
  --directory="$work_dir" --create --file="$outer_tar" "$package_name"
gzip -n -9 < "$outer_tar" > "$archive_tmp"

tar --list --gzip --file="$archive_tmp" > "$work_dir/archive-entries.txt"
while IFS= read -r entry; do
  is_model_payload "$entry" && fail "model payload was packaged: $entry"
done < "$work_dir/archive-entries.txt"
for required_entry in \
  "$package_name/SOURCE-MANIFEST.txt" \
  "$package_name/third_party/ai/source.lock" \
  "$package_name/third_party/whisper.cpp/source/LICENSE" \
  "$package_name/third_party/whisper.cpp/source/include/whisper.h" \
  "$package_name/ai-whisper/build.gradle.kts" \
  "$package_name/ai-whisper/src/main/cpp/CMakeLists.txt" \
  "$package_name/third_party/ai/patches/whisper-allocation-failure.cmake" \
  "$package_name/ai-whisper/src/main/cpp/whisper_jni.cpp" \
  "$package_name/ai-whisper/src/main/kotlin/io/github/trevarj/motd/ai/whisper/WhisperRuntime.kt"; do
  grep -Fqx "$required_entry" "$work_dir/archive-entries.txt" || fail "source archive is missing $required_entry"
done

install -m 0644 "$archive_tmp" "$output_dir/$archive_name"
source_sha256="$(sha256sum "$output_dir/$archive_name" | awk '{ print $1 }')"
source_url="https://github.com/${repository}/releases/download/${release_tag}/${archive_name}"

grep -Fq "## Release-specific AI source provenance: ${release_tag}" "$notice_path" && \
  fail "release notice already contains AI source provenance"
cat >> "$notice_path" <<EOF

## Release-specific AI source provenance: ${release_tag}

- Pinned whisper.cpp source: [${archive_name}](${source_url})
- AI source archive SHA-256: \`${source_sha256}\`

The archive contains the exact source tree and MIT license, motd's JNI/Kotlin/CMake/Gradle
wrapper, and the reproducible toolchain lock. It intentionally contains no model weights;
user-imported weights are not redistributed by motd.
EOF

cat >> "$compliance_path" <<EOF

The optional on-device transcription runtime uses pinned MIT-licensed whisper.cpp source.
Its [source archive](${source_url}) (SHA-256: \`${source_sha256}\`) includes the source tree,
license, wrapper, and pinned build settings. It contains no model weights; users supply their
own model files and motd does not redistribute them.
EOF

checksums_tmp="$work_dir/SHA256SUMS"
awk -v archive="$archive_name" -v notice="$notice_name" \
  '$2 != archive && $2 != notice { print }' "$checksums_path" > "$checksums_tmp"
(
  cd "$output_dir"
  sha256sum "$archive_name" "$notice_name"
) >> "$checksums_tmp"
install -m 0644 "$checksums_tmp" "$checksums_path"

echo "$output_dir/$archive_name"
echo "SHA-256: $source_sha256"

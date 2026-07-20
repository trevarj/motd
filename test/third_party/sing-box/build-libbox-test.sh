#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
builder="$root_dir/third_party/sing-box/build-libbox.sh"
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/motd-libbox-test.XXXXXX")"
trap 'rm -rf "$work_dir"' EXIT

make_fake_ndk() {
  local ndk_dir="$1"
  mkdir -p "$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin"
  printf 'Pkg.Revision = %s\n' "${2:-28.0.13004108}" > "$ndk_dir/source.properties"
  printf '#!/bin/sh\nexit 0\n' \
    > "$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android35-clang"
  chmod +x "$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android35-clang"
}

valid_ndk="$work_dir/valid-ndk"
make_fake_ndk "$valid_ndk"
LIBBOX_NDK_HOME="$valid_ndk" \
LIBBOX_PATCH_NDK_HOST_TOOLS=0 \
LIBBOX_PREPARE_NDK_ONLY=1 \
  "$builder" >/dev/null
test ! -e "$valid_ndk/.motd-archive-sha256"

invalid_ndk="$work_dir/invalid-ndk"
make_fake_ndk "$invalid_ndk" "27.2.12479018"
if LIBBOX_NDK_HOME="$invalid_ndk" LIBBOX_PATCH_NDK_HOST_TOOLS=0 \
  LIBBOX_PREPARE_NDK_ONLY=1 "$builder" >/dev/null 2>&1; then
  echo "an unsupported NDK revision was accepted" >&2
  exit 1
fi

if LIBBOX_NDK_HOME="$valid_ndk" LIBBOX_PATCH_NDK_HOST_TOOLS=0 \
  LIBBOX_PREPARE_NDK_ONLY=0 LIBBOX_OFFLINE=1 "$builder" >/dev/null 2>&1; then
  echo "offline build proceeded without pinned source inputs" >&2
  exit 1
fi

printf '%s\n' "libbox F-Droid preflight checks passed"

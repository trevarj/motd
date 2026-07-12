#!/usr/bin/env bash
# Assemble release assets and render the release-specific corresponding-source notice.
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
release_tag="${1:-}"
apk_path="${2:-}"
output_dir="${3:-$root_dir/release-assets}"
repository="${GITHUB_REPOSITORY:-}"

[[ -n "$release_tag" && -f "$apk_path" && -n "$repository" ]] || {
  echo "usage: GITHUB_REPOSITORY=owner/repo $0 <release-tag> <apk-path> [output-dir]" >&2
  exit 2
}
[[ "$release_tag" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "invalid release tag" >&2; exit 2; }
[[ "$repository" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || { echo "invalid repository slug" >&2; exit 2; }

mkdir -p "$output_dir"
output_dir="$(cd "$output_dir" && pwd)"
"$root_dir/third_party/sing-box/package-source.sh" "$release_tag" "$output_dir"

apk_name="motd-${release_tag}.apk"
source_name="motd-libbox-source-${release_tag}.tar.gz"
notice_name="motd-${release_tag}-THIRD_PARTY_NOTICES.md"
install -m 0644 "$apk_path" "$output_dir/$apk_name"
install -m 0644 "$root_dir/LICENSE" "$output_dir/LICENSE"
install -m 0644 "$root_dir/docs/assets/brand/IBM-PLEX-LICENSE.txt" \
  "$output_dir/IBM-PLEX-LICENSE.txt"

source_sha256="$(sha256sum "$output_dir/$source_name" | cut -d ' ' -f1)"
source_url="https://github.com/${repository}/releases/download/${release_tag}/${source_name}"
apk_url="https://github.com/${repository}/releases/download/${release_tag}/${apk_name}"

install -m 0644 "$root_dir/THIRD_PARTY_NOTICES.md" "$output_dir/$notice_name"
cat >> "$output_dir/$notice_name" <<EOF

## Release-specific source provenance: ${release_tag}

- Object code: [${apk_name}](${apk_url})
- Complete libbox corresponding source: [${source_name}](${source_url})
- Source archive SHA-256: \`${source_sha256}\`

The source archive contains the exact pinned sing-box and Android-submodule source archives,
MOTD's build script and lock file, the Nix build definition, artifact manifest, licenses, and
rebuild instructions. It is provided alongside the APK at no additional charge.
EOF

(
  cd "$output_dir"
  sha256sum "$apk_name" "$source_name" LICENSE IBM-PLEX-LICENSE.txt "$notice_name" > SHA256SUMS
)

cat > "$output_dir/release-compliance.md" <<EOF
## Source and license

MOTD is GPL-3.0-or-later, includes libbox from sing-box, and uses outlined lettering derived
from IBM Plex Mono under the SIL Open Font License 1.1. The complete corresponding source for
the bundled libbox is available as [${source_name}](${source_url})
(SHA-256: \`${source_sha256}\`). See the attached [release-specific third-party notice](https://github.com/${repository}/releases/download/${release_tag}/${notice_name})
and \`SHA256SUMS\` for provenance and verification details. The IBM Plex license is attached as
\`IBM-PLEX-LICENSE.txt\`.
EOF

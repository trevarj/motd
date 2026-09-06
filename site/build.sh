#!/usr/bin/env bash
set -euo pipefail

SITE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SITE_DIR/.." && pwd)"
OUT="${1:-$ROOT/_site}"

case "$OUT" in
  ""|"/")
    printf 'refusing to build into unsafe output path: %s\n' "$OUT" >&2
    exit 2
    ;;
esac

pages=(
  index.html
  installation.html
  getting-started.html
  configuration.html
  guides.html
)
required=(
  "${pages[@]/#/$SITE_DIR/}"
  "$SITE_DIR/styles.css"
  "$ROOT/screenshots/chat-list.png"
  "$ROOT/screenshots/chat.png"
  "$ROOT/screenshots/file-uploader.png"
  "$ROOT/docs/assets/brand/motd-symbol.svg"
  "$ROOT/docs/assets/brand/motd-lockup-light.svg"
  "$ROOT/docs/assets/brand/motd-lockup-dark.svg"
  "$ROOT/docs/assets/brand/motd-wordmark.svg"
)
for file in "${required[@]}"; do
  [ -s "$file" ] || {
    printf 'missing required site asset: %s\n' "$file" >&2
    exit 1
  }
done

rm -rf -- "$OUT"
mkdir -p "$OUT/assets/brand" "$OUT/screenshots"
cp "${pages[@]/#/$SITE_DIR/}" "$SITE_DIR/styles.css" "$OUT/"
cp "$ROOT/docs/assets/brand/"motd-{symbol,lockup-light,lockup-dark,wordmark}.svg "$OUT/assets/brand/"
cp "$ROOT/screenshots/"{chat-list,chat,file-uploader}.png "$OUT/screenshots/"

printf 'built documentation site: %s\n' "$OUT"

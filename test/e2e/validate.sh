#!/usr/bin/env bash
set -euo pipefail

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mapfile -t scripts < <(find "$E2E_DIR" -type f -name '*.sh' -print | sort)
scripts+=("$E2E_DIR/../../tools/ci-paths.sh" "$E2E_DIR/../../tools/android-lint.sh" "$E2E_DIR/../../tools/build-signed-release.sh")
for script in "${scripts[@]}"; do
  bash -n "$script"
done
[ -x "$E2E_DIR/fast-suite.sh" ] || {
  echo "fast-suite.sh must remain executable" >&2
  exit 1
}
[ -x "$E2E_DIR/../../tools/ci-paths.sh" ] || {
  echo "tools/ci-paths.sh must remain executable" >&2
  exit 1
}
[ -x "$E2E_DIR/../../tools/build-signed-release.sh" ] || {
  echo "tools/build-signed-release.sh must remain executable" >&2
  exit 1
}
required_test="$E2E_DIR/../../app/src/androidTest/kotlin/io/github/trevarj/motd/RequiredHeadlessE2eTest.kt"
mapfile -d '' -t instrumentation_sources < <(find "$E2E_DIR/../../app/src/androidTest" -type f -name '*.kt' -print0)
count_tests() {
  perl -0777 -e '
    for my $path (@ARGV) {
      open my $source, "<", $path or die "$path: $!\n";
      local $/;
      my $text = <$source>;
      $text =~ s{/\*.*?\*/}{}gs;
      $text =~ s{^\s*//.*$}{}gm;
      my $count = () = $text =~ /\@(?:org\.junit\.)?Test\b(?:\s*\([^)]*\))?\s*(?:(?:public|internal|private|protected)\s+)*fun\b/g;
      print "$path\t$count\n";
    }
  ' "$@"
}
violations="$({ count_tests "${instrumentation_sources[@]}" || exit 1; } | awk -F '\t' -v required="$required_test" '$1 != required && $2 != 0')"
[ -z "$violations" ] || {
  echo "ordinary @Test declarations are forbidden under app/src/androidTest:" >&2
  printf '%s\n' "$violations" >&2
  exit 1
}
required_count="$(count_tests "$required_test" | awk -F '\t' '{ print $2 }')"
[ "$required_count" = 4 ] || {
  echo "RequiredHeadlessE2eTest.kt must declare exactly 4 @Test methods; got ${required_count:-0}" >&2
  exit 1
}
bash "$E2E_DIR/tests/android-lint-test.sh"
bash "$E2E_DIR/tests/ci-paths-test.sh"
bash "$E2E_DIR/tests/build-signed-release-test.sh"
bash "$E2E_DIR/tests/fast-suite-classifier-test.sh"
bash "$E2E_DIR/tests/fast-suite-privacy-test.sh"
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  bash "$E2E_DIR/hermetic-stack.sh" validate
else
  echo "SKIP: docker unavailable; Compose validation remains required in CI" >&2
fi

#!/usr/bin/env bash
set -euo pipefail

[ "$#" -eq 2 ] || { echo "Usage: $0 SERIAL APK" >&2; exit 1; }
serial="$1"
apk="$(realpath "$2")"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Use nix develop}}"
build_tools="$sdk/build-tools/36.0.0"
badging="$("$build_tools/aapt2" dump badging "$apk")"
case "$badging" in
  *"package: name='io.github.trevarj.motd'"*) ;;
  *) echo "Expected the MOTD release application ID" >&2; exit 1 ;;
esac
case "$badging" in
  *application-debuggable*) echo "Refusing a debuggable APK" >&2; exit 1 ;;
esac

work_dir="$(mktemp -d)"
remote_dir="/data/local/tmp/motd-release-dex-$(basename "$work_dir")"
cleanup() {
  adb -s "$serial" shell rm -rf "$remote_dir" >/dev/null 2>&1 || true
  rm -rf "$work_dir"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

cat > "$work_dir/MotdReleaseDexCheck.java" <<'JAVA'
public final class MotdReleaseDexCheck {
    public static void main(String[] args) throws Throwable {
        try {
            Class.forName("io.github.trevarj.motd.ui.chat.ChatScreenKt", true,
                MotdReleaseDexCheck.class.getClassLoader());
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            throw failure;
        }
        System.out.println("ChatScreenKt verified by device ART");
    }
}
JAVA
javac -J-Xmx256m -J-XX:ActiveProcessorCount=1 --release 17 \
  -d "$work_dir" "$work_dir/MotdReleaseDexCheck.java"
mkdir "$work_dir/dex"
"$build_tools/d8" -JXmx256m -JXX:ActiveProcessorCount=1 --min-api 26 \
  --output "$work_dir/dex" "$work_dir/MotdReleaseDexCheck.class"
adb -s "$serial" shell mkdir "$remote_dir"
adb -s "$serial" push "$work_dir/dex/classes.dex" "$remote_dir/probe.dex"
adb -s "$serial" push "$apk" "$remote_dir/candidate.apk"
adb -s "$serial" shell chmod 444 "$remote_dir/probe.dex" "$remote_dir/candidate.apk"
adb -s "$serial" shell \
  "CLASSPATH=$remote_dir/probe.dex:$remote_dir/candidate.apk app_process /system/bin MotdReleaseDexCheck"

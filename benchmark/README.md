# motd macrobenchmarks

Jetpack Macrobenchmark journeys against the real soju fixture. `:benchmark` instruments `:app`'s
`benchmark` build type (`io.github.trevarj.motd.benchmark`): release code, not debuggable,
shell-profileable, debug-signed. Debuggable builds are several times slower in Compose, so never
compare numbers from `debug` or `e2e` builds.

| Benchmark | Measures |
| --- | --- |
| `StartupBenchmark#coldStartToChatList` | Cold start of an onboarded soju account; the measured journey waits for the fixture channel row |
| `TimelineScrollBenchmark#flingIntoRetainedHistory` | One older-directed fling and its inertial tail in a warm, already-local plain-text chat timeline |

- `timeToInitialDisplayMs` (TTID): Android's first rendered app frame, which can be an empty chat list.
- `timeToFullDisplayMs` (TTFD): the app's fully-drawn signal for the first usable local chat list.
- `FrameTimingMetric`: frame timing during the measured startup journey.
- For scrolling, report `frameDurationCpuMs` P50/P90/P95/P99 and, on API 31+, `frameOverrunMs`
  P50/P90/P95/P99 (positive means the frame missed its deadline). These describe the
  measured window, not startup, network history paging, or media-heavy chat performance.

Onboarding, certificate validation, and waiting for the seeded preview happen outside the measured
journey. These metrics do not measure TLS connection or history sync time.

Before every scroll iteration, setup opens the chat, scrolls until retained row 020 or older
is visible, and returns to newest (090 and the final welcome visible, jump button gone).
Only one fixed-step gesture in the center of the unobscured timeline and a bounded accessibility
settle are timed. An unchanged, unsettled, or boundary-adjacent landing fails; the oldest visible
row must remain newer than 045. Accessibility settling is not GPU-idle proof. On the chosen device,
check the first Perfetto trace for scrolling after finger release. The landing guard reduces paging
risk but does not prove that no network request overlapped. Keep refresh rate fixed: UIAutomator
dispatches swipe steps at VSync.

**Each test invocation clears all app data for `io.github.trevarj.motd.benchmark`**, including any
saved certificate trust. Keep personal data out of this package. Setup imports the local `libera`
network through the real soju UI and accepts the certificate only if its SHA-256 matches the
required `sojuTlsSha256` argument.

## Running on a connected device

Use a dedicated device, preferably a physical phone for scroll measurements. Run the journeys
separately against fresh default-profile native soju fixture directories; stop one stack before
starting the other. The fixture uses `##motdtest` on `libera` with the final preview
`motdadb: welcome to the seed channel`. Initialize submodules and set the device once:

```sh
git submodule update --init --recursive
export ANDROID_SERIAL='<benchmark-device-serial>'
```

### Startup (ordinary seed)

```sh
(
  set -e
  export MOTD_STACK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/motd-startup.XXXXXX")"
  stack() { nix develop .#e2e-stack -c bash ./test/e2e/local-stack.sh "$@"; }
  trap 'stack down' EXIT
  MOTD_STACK_PROFILE=default MOTD_BENCHMARK_SCROLL=0 stack up
  fingerprint="$(stack tls-fingerprint)"
  nix develop -c bash ./gradlew :benchmark:connectedBenchmarkAndroidTest \
    '-Pandroid.testInstrumentationRunnerArguments.class=io.github.trevarj.motd.macrobenchmark.StartupBenchmark#coldStartToChatList' \
    -Pandroid.testInstrumentationRunnerArguments.sojuTlsSha256="$fingerprint"
)
```

### Timeline fling (extended seed)

The opt-in stack adds 90 numbered short and wrapping plain-text rows before the same final
welcome. `up` fails unless the host CHATHISTORY probe sees all 90 rows and the final welcome
in a retained LATEST batch with strictly increasing millisecond timestamps. Do not run
`seed` or generate other channel traffic after `up`.

```sh
(
  set -e
  export MOTD_STACK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/motd-scroll.XXXXXX")"
  stack() { nix develop .#e2e-stack -c bash ./test/e2e/local-stack.sh "$@"; }
  trap 'stack down' EXIT
  MOTD_STACK_PROFILE=default MOTD_BENCHMARK_SCROLL=1 stack up
  fingerprint="$(stack tls-fingerprint)"
  nix develop -c bash ./gradlew :benchmark:connectedBenchmarkAndroidTest \
    '-Pandroid.testInstrumentationRunnerArguments.class=io.github.trevarj.motd.macrobenchmark.TimelineScrollBenchmark#flingIntoRetainedHistory' \
    -Pandroid.testInstrumentationRunnerArguments.sojuTlsSha256="$fingerprint"
)
```

The benchmark defaults to the native fixture account at `sojuHost=127.0.0.1`, `sojuPort=6697`,
with `nick=motdadb`, `channel=##motdtest`, and `iterations=10`. Fixture account overrides are
`sojuUser` and `sojuPassword`. Results (JSON and Perfetto traces) land in
`benchmark/build/outputs/connected_android_test_additional_output/`.
Record device/API, display size, refresh rate, compilation mode, and iteration count with
results. Compare only runs with the same device geometry, font scale, and gesture settings.
There is no CI timing threshold.

The default build is arm64 only. For an x86_64 emulator (no embedded obfuscation core), add
`-PmotdBenchmarkAbi=x86_64 -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR`.
Emulator numbers are for spotting regressions between commits, not absolute phone performance.

## Running on a device farm

Build both APKs (`:app:assembleBenchmark :benchmark:assembleBenchmark`) and run the
self-instrumenting test APK with the corresponding class/method filter and fixture arguments.
Use a disposable, correctly seeded fixture on a private network reachable by the test device;
do not expose the listener publicly or use a real account.

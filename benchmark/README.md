# motd macrobenchmarks

Jetpack Macrobenchmark journeys against local IRC fixtures. `:benchmark` instruments `:app`'s
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
saved certificate trust. Keep personal data out of this package. The soju fixture setup imports
the local `libera` network through the real soju UI and accepts the certificate only if its
SHA-256 matches the required `sojuTlsSha256` argument.

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

## Chat interactions (direct, loopback-only fixture)

Use a dedicated physical device with the app in English and the same shell-profileable `benchmark` build. The
`ChatInteractionBenchmark` class onboards directly as `motdbench` without TLS or credentials.
It does not use the soju fixture or `sojuTlsSha256`.

Start the Python 3.10+ stdlib fixture from the repository root and wait for
`IRC fixture ready on 127.0.0.1:17668`:

```sh
nix develop -c python3 benchmark/chat-fixture.py
```

Use USB ADB on a dedicated device with Wi-Fi and mobile data off before creating the
reverse: `adbd` may bind the device-side port on all interfaces even when the host
listener binds to loopback. Do not run this fixture over wireless ADB.
In another terminal, reverse the port and run either the full class or one journey
(set `METHOD` to the test method name to append `#methodName` to the filter):

```sh
SERIAL='<benchmark-device-serial>'
export ANDROID_SERIAL="$SERIAL"
nix develop -c adb -s "$SERIAL" reverse tcp:17668 tcp:17668
benchmark=io.github.trevarj.motd.macrobenchmark.ChatInteractionBenchmark
nix develop -c bash ./gradlew :benchmark:connectedBenchmarkAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.class=${benchmark}${METHOD:+#$METHOD}"
```

Remove the reverse with `nix develop -c adb -s "$SERIAL" reverse --remove tcp:17668`
and stop the fixture with Ctrl-C when done. `--port` changes the host listener port for
local diagnostics; the benchmark expects port 17668 on the device. Keep the listener on
loopback only: never expose it, tunnel it onto a public interface, or use a real IRC account.
The fixture has no authentication, TLS, durable storage, rate limits, or other production
server behavior beyond its small IRC subset.

Both rooms reload their seed on each connection. `#bench-a` has 40 retained rows including
a tagged embedded reply, a roster with `benchalice` and 300 unrelated nicks, and the newest
anchor `chat-a ready`.
`#bench-b` has `chat-b ready`. In `#bench-a`, each unique plain `0 bench fail …` payload is
rejected immediately the first time; its next send gets a labelled self echo after 1.2 seconds
so the app can expose pending retry separately from confirmation. `bench burst…` echoes
normally and adds six incoming rows, ending with `incoming burst ready`.

On first join, setup uses the return-to-newest action if the initial unread viewport hides the
ready marker, then waits for the marker before leaving the room.

The class covers warm switching between the two loaded rooms, incremental key input, nick
autocomplete, a long multiline accessibility replacement and edit, jumping from an embedded reply
to its source, and failed-send controls, retry, and local deletion. Deletion reports opening
the confirmation separately from applying it. Failure journeys keep earlier rows: run the same
iteration count when comparing builds. The replacement journey does not use the system
clipboard or measure a paste.
Before timing the pick, the nick journey waits for the clickable `benchalice` suggestion and
reads its visible center. It verifies the pick by the completed editor text; its timings do not
identify the first frame containing the suggestion.

For fully compiled comparisons, pass `-Pandroid.testInstrumentationRunnerArguments.compilationMode=aot`.
The default is `baseline` (use the app's Baseline Profile if present). `iterations` defaults to 10.

Report results **per journey**, not as a class-wide aggregate. Keep the per-method JSON
and Perfetto traces with the device/API, refresh rate, compilation mode and iterations;
use the same device geometry, font scale and gesture settings for comparisons.
Report `frameDurationCpuMs` and, where available, `frameOverrunMs` percentiles for the
measured window. A visible or settled accessibility node marks a UI observation, not
GPU presentation or idleness; use the trace to check frame presentation and jank. These
numbers do not characterize production IRC latency, TLS, or soju.

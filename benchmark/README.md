# motd macrobenchmarks

Jetpack Macrobenchmark journeys against the real soju fixture. `:benchmark` instruments `:app`'s
`benchmark` build type (`io.github.trevarj.motd.benchmark`): release code, not debuggable,
shell-profileable, debug-signed. Debuggable builds are several times slower in Compose, so never
compare numbers from `debug` or `e2e` builds.

| Benchmark | Measures |
| --- | --- |
| `StartupBenchmark#coldStartToChatList` | Cold start of an onboarded soju account; the measured journey waits for the fixture channel row |

- `timeToInitialDisplayMs` (TTID): Android's first rendered app frame, which can be an empty chat list.
- `timeToFullDisplayMs` (TTFD): the app's fully-drawn signal for the first usable local chat list.
- `FrameTimingMetric`: frame timing during the measured startup journey.

Onboarding, certificate validation, and waiting for the seeded preview happen outside the measured
journey. These metrics do not measure TLS connection or history sync time.

**Each test invocation clears all app data for `io.github.trevarj.motd.benchmark`**, including any
saved certificate trust. Keep personal data out of this package. Setup imports the local `libera`
network through the real soju UI and accepts the certificate only if its SHA-256 matches the
required `sojuTlsSha256` argument.

## Running on a connected device

Choose a dedicated device or emulator and a fresh fixture directory. The default native fixture
seeds `##motdtest` on `libera` with the preview `motdadb: welcome to the seed channel`; setup
requires that preview on the channel row before measuring a cold start.

```sh
git submodule update --init --recursive
export ANDROID_SERIAL='<benchmark-device-serial>'
export MOTD_STACK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/motd-benchmark.XXXXXX")"
nix develop .#e2e-stack -c bash ./test/e2e/local-stack.sh up
fingerprint="$(nix develop .#e2e-stack -c bash ./test/e2e/local-stack.sh tls-fingerprint)"
nix develop -c bash ./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.sojuTlsSha256="$fingerprint"
nix develop .#e2e-stack -c bash ./test/e2e/local-stack.sh down
```

The benchmark defaults to the native fixture account at `sojuHost=127.0.0.1`, `sojuPort=6697`,
with `nick=motdadb`, `channel=##motdtest`, and `iterations=10`. Fixture account overrides are
`sojuUser` and `sojuPassword`. Results (JSON and Perfetto traces) land in
`benchmark/build/outputs/connected_android_test_additional_output/`.

The default build is arm64 only. For an x86_64 emulator (no embedded obfuscation core), add
`-PmotdBenchmarkAbi=x86_64 -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR`.
Emulator numbers are for spotting regressions between commits, not absolute phone performance.

## Running on a device farm

Build both APKs (`:app:assembleBenchmark :benchmark:assembleBenchmark`) and run the
self-instrumenting test APK with the same arguments. Use a disposable, seeded fixture on a private
network reachable by the test device; do not expose the listener publicly or use a real account.

# motd macrobenchmarks

Jetpack Macrobenchmark journeys against the real soju fixture. `:benchmark` instruments `:app`'s
`benchmark` build type (`io.github.trevarj.motd.benchmark`): release code, not debuggable,
shell-profileable, debug-signed. Debuggable builds are several times slower in Compose, so never
compare numbers from `debug` or `e2e` builds.

| Benchmark | Measures |
| --- | --- |
| `StartupBenchmark#coldStartToChatList` | Cold start of an onboarded soju account to the chat list (startup and frame timing) |

The fixture is prepared outside the benchmark. When app data is empty, the benchmark onboards
through the real soju import UI and trusts the certificate only if its SHA-256 matches
`sojuTlsSha256`.

## Running on a connected device

```sh
./test/e2e/local-stack.sh up
fingerprint="$(./test/e2e/local-stack.sh tls-fingerprint | tail -1)"
nix develop -c ./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.sojuTlsSha256="$fingerprint"
```

Arguments and defaults: `sojuHost=127.0.0.1`, `sojuPort=6697`, `sojuUser=motd`,
`sojuPassword=motdtest`, `nick=motdadb`, `channel=##motdtest`, `iterations=10`; `sojuTlsSha256` is
required. Results (JSON and Perfetto traces) land in
`benchmark/build/outputs/connected_android_test_additional_output/`.

The default build is arm64 only. For an x86_64 emulator (no embedded obfuscation core), add
`-PmotdBenchmarkAbi=x86_64 -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR`.
Emulator numbers are for spotting regressions between commits, not absolute phone performance.

## Running on a device farm

Build both APKs (`:app:assembleBenchmark :benchmark:assembleBenchmark`), expose the fixture's soju
listener at a reachable address, and run the self-instrumenting test APK. For example, on Google's
Developer Device Platform (`gcloud beta device-run`, gcloud 593 or newer):

```sh
gcloud beta device-run sessions submit instrumentation --device=cheetah-33 \
  --target-app=app/build/outputs/apk/benchmark/app-benchmark.apk \
  --test=benchmark/build/outputs/apk/benchmark/benchmark-benchmark.apk \
  --test-targets=class=io.github.trevarj.motd.macrobenchmark.StartupBenchmark \
  --additional-test-options=sojuHost=<public-host>,sojuTlsSha256=<fingerprint> \
  --paths-to-pull=/sdcard/Android/media/io.github.trevarj.motd.macrobenchmark
```

Results are pulled into the session's Cloud Storage folder. The fixture credentials are public, so
expose the listener only while a run needs it.

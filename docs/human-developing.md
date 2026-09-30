# Building, linting, and testing motd

Enter the repository Nix shell first so the JDK and Android SDK match CI:

```sh
nix develop
```

direnv loads the same shell automatically via `.envrc` if you use it. All
Gradle commands below assume you are already inside this shell.

## Prerequisites

- Before rebuilding the bundled libbox AAR, initialize submodules recursively:

```sh
git submodule update --init --recursive
```

## Build

```sh
./gradlew :irc:test                   # protocol tests (pure JVM)
./gradlew :app:testDebugUnitTest  # app unit tests (Robolectric)
./gradlew :app:assembleDebug      # Google-free arm64 debug APK
```

The debug APK lands under `app/build/outputs/apk/debug/`. Install it with
`adb install`. The debug build carries the `.debug` application-id suffix, so
it can coexist with a release install.

The embedded VLESS transport (TCP + REALITY or WebSocket + TLS) uses bundled libbox, which is
arm64-v8a-only. APKs built from this source tree must not be installed on
32-bit ARM or x86 devices. Other ABI support needs a separately pinned and
verified libbox artifact.

## Verification

Use the authoritative local command matrix in
[`.agents/testing.md`](../.agents/testing.md). While editing, run the nearest
relevant test method; before handoff/push, run its class once. For cross-module
changes, do this for the nearest affected tests in each module:

```sh
./gradlew :app:testDebugUnitTest \
  --tests '<fully-qualified-class.method>' --stacktrace
```

Use the class filter for handoff and `:irc:test` for protocol changes. Filters
narrow execution, not compilation of test sources and dependencies. The command
above assumes the Nix shell; alternatively use
`nix develop -c ./gradlew :app:testDebugUnitTest --tests '<fully-qualified-class.method>' --stacktrace`.

Run each changed module's existing `:app:ktlintCheck`, `:irc:ktlintCheck`, or
`:ai-whisper:ktlintCheck` once before handoff. Root Gradle/style configuration
changes still require root `ktlintCheck`. Do not automatically append an
unfiltered suite, Android lint, APK assembly, or a full pre-push gate.

For database changes, run the nearest database regression and review/commit
generated `app/schemas` changes. Compile affected instrumentation journeys with
`:app:compileE2eAndroidTestKotlin`. Run `:app:assembleDebug` when resources,
manifest, packaging, or an actual APK require it. No routine local emulator or
physical-device runs.

`./tools/prepush.sh` is only an explicitly requested diagnostic for broader
failures, not a handoff/push prerequisite or a substitute for hosted CI. It
requires a clean committed tree; `MOTD_PREFLIGHT_BASE=<ref>` overrides its
`origin/main` comparison base.

Require all applicable hosted `Required CI / gate` checks before merge. Inspect
an individual failed job's existing diagnostics and begin fixing it immediately
rather than waiting for aggregate `gate`; remaining coverage continues normally.

## Portable text-engine proof

`:ai-text` builds the same CPU engine for JNI and the host-only
`motd_text_smoke` executable. It does not bundle weights or fetch them during
configuration or inference. Keep the independently verified pinned model
outside the checkout (identity, byte length and SHA-256 are in
`third_party/ai/source.lock`).

From the repository Nix shell, after verifying that artifact:

```sh
cmake="$ANDROID_HOME/cmake/3.31.6/bin/cmake"
"$cmake" -S ai-text/src/main/cpp -B ai-text/build/host-smoke -G Ninja \
  -DMOTD_HOST_SMOKE=ON -DCMAKE_BUILD_TYPE=Release
"$cmake" --build ai-text/build/host-smoke --target motd_text_smoke --parallel 2
ai-text/build/host-smoke/bin/motd_text_smoke \
  --model "${XDG_CACHE_HOME:-$HOME/.cache}/motd-ai-smoke/Qwen3.5-2B-Q4_K_M.gguf" \
  --self-checks --examples
```

The CMake wrapper enforces the production CPU/privacy flags for both targets.
Self-checks distinguish tokenizer/parser fixtures from real inference,
exercise joined load/prefill/decode cancellation, and compare one-shot
isolation with a fresh engine. Examples print actual output and check completion,
known correction errors, unchanged already-correct text, deliberately informal
style rewrites, protected literals and instruction-only content. Use
`--composer-examples` instead of `--self-checks --examples` for the focused
editing cases. These mechanical checks do not establish semantic quality.
Host results do not measure Android RAM, latency, thermals, battery or gestures.

Composer prompts prioritize the editing task, explicitly allow wording changes
while preserving facts, and demonstrate style changes with trusted examples.
Custom instructions guide the style; they are not message text to append.
The translation prompt is unchanged. With these prompts, the pinned host model
corrected “I has recieved teh report” to “I have received the report”, fixed the
Spanish “por que”/accent example, and rewrote the informal report request for
Formal and Business without inventing AM/PM. The warmer custom style returned
“I'd appreciate it if you could send the draft today. Thank you!”; Silly only
changed “thanks!” to “thanks a bunch!” and remains a weak style example.

Experimental limitations remain: French translation changed the time spelling;
Japanese inserted a paragraph break and reverse translation added “me”. Hindi's
first sentence was “मैंने भविष्य में नहीं जा सकता।”, losing “tomorrow”; Arabic
reverse translation lost the explicit count of two files. The current hostile
literal-source correction completed but changed tag whitespace and JSON escaping;
the custom instruction-only literal content was not appended. Earlier prompts
produced unchanged styles and a literal-source `OUTPUT_LIMIT` result. Apply and
Copy remain disabled for incomplete output. There is no semantic detector,
source-text fallback, or guarantee that prompt edits fix every model error.
Users must review every result; original IRC formatting is not retained.
Android-native quality and performance remain unmeasured.

## Device and E2E testing

Do not run the headless emulator suite during routine local development; it
materially slows the maintainer's workstation. Local verification stops at the
nearest unit/integration tests and assembly only when needed.

For the local stack, physical-device, and emulator harnesses, follow
[`../test/e2e/README.md`](../test/e2e/README.md). The agent-facing selection
matrix in [`../.agents/testing.md`](../.agents/testing.md) describes which
suite fits which task. Those harnesses have their own shell requirements
documented alongside them.

## Architecture

For data flow, connection ownership, and module boundaries, see
[`../ARCHITECTURE.md`](../ARCHITECTURE.md).

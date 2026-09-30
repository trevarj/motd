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
`--composer-examples` instead of `--self-checks --examples` for editing cases,
or `--style-examples` for only Formal, Business, Silly and Custom. The latter
includes contrasting pirate and terse professional requests on the same
already-correct source, checking requested vocabulary/direct wording, the
request for the recipient to send, first-person review, deadline of 5 and tomorrow.
The time guard rejects any added AM/PM spelling, including `5:00PM`, `5PM`
and dotted forms, because these fixtures contain no meridiem.
The warm-style check also rejects new first-person future commitments when the
source contains no promise. Custom demonstrations contain only one request, so
their review/read wording cannot leak into an unrelated message.
Silly must change the message body rather than only its closing thanks.
An author-created casual spoken-English
instruction is printed for human review, not scored by a dialect heuristic.
These mechanical checks do not establish semantic quality.
Host results do not measure Android RAM, latency, thermals, battery or gestures.

All operations use one JSON object: `text`, plus `style_instruction` only for
Custom or `target_language` only for Translate. The actual untrusted object stays
in the single sentinel segment tokenized with `parse_special=false`. Style tasks
rewrite `text` according to guidance without copying that guidance, preserving
actors, requests versus commitments, negation, facts and unspecified times.
Formal and Business retain their stronger tone instructions and inline examples,
without chat demonstrations. Silly requests a creative, naturally playful rewrite
of the whole message, using appropriate colloquial expressions, vernacular and
idioms to reshape its grammar, structure and cadence, not a few word edits or an
appended riff. Its static trusted pair demonstrates a natural notes-by-3 request,
without theatrical exaggeration, fantasy or added content. Custom applies the
requested voice, register or dialect consistently to every existing clause,
with fitting expressions, idioms, vocabulary, vernacular and grammatical changes.
Natural usage and the original meaning take precedence over forced cliches.
Professional, restrained and terse styles remain possible; neither extra length
nor a universally casual voice is required. Its contrasting warm and casual
spoken-English pairs use the same single notes-send-me-by-3 request, without a
review/read promise. The separate multi-clause smoke source already contains the
speaker's next-day review commitment; style changes must preserve both clauses.
The shared style policy treats the model as an editor for the same author, not
an assistant answering the message. It forbids adding help offers, promises,
actions or intentions absent from the source, while allowing fact-neutral courtesy.
All demonstration user messages use the actual JSON field
schema; assistant responses are plain unquoted text. No private saved style is
embedded.
All operations use greedy decoding on both host and Android. Creative expression
comes from stronger style instructions and trusted role examples, not randomness.
The higher-randomness trial changed actors and unspecified times, so it was
abandoned in favor of meaning preservation. These are editing policy choices,
not a claim of measured creative or semantic quality.
Correct and Translate prompts and payloads, model/runtime pins, nonthinking
template/parser, token boundaries and limits are unchanged.
An earlier prompt revision's eight focused style cases completed and passed their
mechanical checks; the final editor-policy revision has not completed verification.
Formal/Business changed the informal report request; Silly rephrased its
body; warm Custom no longer added an unsolicited review promise. Pirate and
terse professional instructions produced different wording for the same correct
source, retaining the send request, deadline and next-day review.
The literal Custom case retained the source's literal tag and mention without
copying instruction-only content, but omitted other source wording.

Physical-device checks in `##motdtest` observed:

- Silly previously only added “Thanks a bunch!”; the revised request used
  “Hey squad” and “send the report my way by 5”.
- The existing private register style previously returned ordinary wording;
  the latest observed result was “Send the report my way by 5, yeah? I'll check it
  tomorrow, sure.” for `Please send the report by 5. I will review it tomorrow.`
- An author-created pirate instruction produced “Ahoy matey! Send the report my
  way by 5, and I'll be reviewing it tomorrow, ahoy!” for that same source.
  The temporary diagnostic style remains on the test device.

These are limited observations, not dialect fidelity or semantic-quality proof.
The model still sometimes encloses results in quotes, omits wording or adds an
implicit recipient. Review every result. Style-name special cases, semantic
detectors, retries, source fallbacks and model substitutions are not used.

Experimental limitations remain: French translation changed the time spelling;
Japanese inserted a paragraph break and reverse translation added “me”. Hindi's
first sentence was “मैंने भविष्य में नहीं जा सकता।”, losing “tomorrow”; Arabic
reverse translation lost the explicit count of two files. Hostile literal-source
correction previously completed but changed tag whitespace and JSON escaping.
Still-earlier prompts produced unchanged styles and a literal-source
`OUTPUT_LIMIT` result. Apply and Copy remain disabled for incomplete output.
There is no semantic detector,
source-text fallback, or guarantee that prompt edits fix every model error.
Users must review every result; original IRC formatting is not retained.
These limited device samples are not a quality/performance benchmark; Android
RAM, latency, thermals and battery remain unmeasured.

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

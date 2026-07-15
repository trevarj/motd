# Performance characterization and benchmark mission

Date: 2026-07-15
Baseline: `fix/codebase-e2e-hardening` after C1-C3, K1, and A1

## Decision

Keep the current production ownership boundaries and do not split large files
solely to reduce line count. C1-C3 already introduced the correctness-bearing
seams (`EventOrigin`, `NetworkEventSequencer`, `BufferStore`, and
`ConnectionRegistry`). A2 extracts the pure provenance policy and makes
release Compose compiler reports reproducible, but defers broader manager and
Compose decomposition until a measured scenario identifies a bottleneck or a
focused feature needs a smaller ownership boundary.

This is a deliberate no-regression decision, not a claim that the application
is fast enough. Runtime performance cannot be established from compiler
reports, source size, or host-side unit tests.

## Characterization inventory

The current largest mixed-ownership sources are:

| Source | Lines | Current boundary and characterization |
| --- | ---: | --- |
| `ConnectionManagerImpl.kt` | 1,489 | Facade over the command-loop `ConnectionRegistry`; readiness/history/read-marker behavior is covered by coordinator and registry virtual-time tests. |
| `ChatScreen.kt` | 1,386 | Route collection is separate from `ChatContent`; scroll restoration, jump resolution, paging, and model decisions have host-side tests. |
| `EventProcessor.kt` | 1,286 | Sole IRC-to-Room writer; 65 event-processing tests cover provenance, idempotency, sequencing, relations, roster, and contested persistence. |
| `ChatViewModel.kt` | 846 | Owns chat state and side effects; paging and pure chat policies are tested separately. |
| `IrcClient.kt` | 841 | Pure JVM protocol/session boundary with parser, history, configuration-failure, and transport tests. |
| `MessageList.kt` | 802 | Stateless rendering plus viewport callbacks; grouping and visibility policies are pure/tested. |
| `HistoryResyncCoordinator.kt` | 599 | Dedicated catch-up boundary with 19 focused tests. |

The counts are navigation aids only. They are not acceptance thresholds.

## Compose compiler characterization

Release reports are opt-in and remain build artifacts:

```sh
nix develop -c ./gradlew \
  :app:compileFossReleaseKotlin \
  -PmotdComposeMetrics=true \
  --rerun-tasks --stacktrace --no-daemon --max-workers=1
```

The 2026-07-15 release compilation reported 286 declared composable entries:
269 skippable and 17 non-skippable. The non-skippable entries are value/helper
functions rather than the chat rendering path. `ChatScreen`, `ChatContent`,
`MessageList`, `MessageRow`, `MessageBubble`, `Composer`, and
`ComposerTextField` were all restartable and skippable. Module metrics reported
1,260 composables including generated composable lambdas, 958 skippable
composables, 20,296 arguments with 250 known-unstable arguments, and strong
skipping enabled.

These results do not justify splitting the chat UI or adding stability wrappers.
Android's guidance warns against attempting to make an entire UI skippable
without an observed stability problem. Re-run the release report after a
measured regression and inspect the affected composable and its inputs, not
the aggregate percentage.

Reference: <https://developer.android.com/develop/ui/compose/performance/stability/diagnose>

## Refactor triggers

- Extract a readiness/catch-up/read-marker coordinator from
  `ConnectionManagerImpl` when a change needs to alter their shared ordering;
  retain the facade and registry command boundary.
- Extract outgoing/echo, presence, or roster ownership only with a
  characterization test that fixes its state transitions and cancellation
  behavior before movement.
- Extract an `EventProcessor` roster or timeline collaborator only if it keeps
  transactions event-scoped and cannot be invoked as a second IRC-to-Room
  writer.
- Split `ChatContent` or `MessageList` when a benchmark/trace attributes missed
  frames or excessive recomposition to a named state owner. File length alone
  is insufficient.

## Authorized benchmark specification

Physical measurement still requires fresh maintainer authorization. Once
authorized, add a separate `:benchmark` Macrobenchmark/baseline-profile module
and run against a physical release-like arm64 device using the FOSS release
variant with the repository's existing non-minified release policy.

Use deterministic fixture setup and record device model, Android build,
thermal state, app commit, iteration count, and database size. Capture these
scenarios:

1. **Cold start:** force-stop between iterations, launch the default route,
   wait for the chat list to be fully drawn, and measure startup timing.
2. **Large retained chat open:** seed one channel with 10,000 deterministic
   messages including replies, reactions, previews disabled, and system rows;
   open it from the chat list and measure time to the newest visible row plus
   frame timing.
3. **Retained chat fling:** fling from newest toward older pages and back for a
   fixed distance; report slow/frozen frames and frame-duration percentiles.
4. **Composer startup:** focus an empty composer in the retained chat, enter a
   fixed multiline message, open/close reply state, and measure frame timing
   and input latency traces.

Generate a baseline profile from cold start, chat-list navigation, large-chat
open, one controlled fling, composer focus, and returning to the chat list.
Compare at least ten measured iterations before and after profile installation;
do not mix profile generation with the measured run.

References:

- <https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview>
- <https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile>

## Exit criteria for a later implementation

A future performance change must name the failing scenario, preserve a
characterization test, attach before/after measurements from the same device
and fixture, and avoid release minification changes. Compiler metrics may help
explain a result but are not accepted as runtime proof.

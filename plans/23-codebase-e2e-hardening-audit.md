# Codebase and E2E hardening audit

Date: 2026-07-15
Baseline: `origin/main` at `c34f3d8` (`v0.7.1`)

This audit reviews the current Android app, pure-JVM IRC engine, Room
persistence, connection lifecycle, Compose UI, Gradle configuration, GitHub
workflows, and local/hermetic E2E harnesses. It is a prioritized implementation
roadmap, not a replacement for current source, `AGENTS.md`, `ARCHITECTURE.md`,
`.agents/testing.md`, or `test/e2e/README.md`.

Completed work recorded by plans 21 and 22 was filtered out. The current Soju,
reconnect/history, and VLESS + REALITY scenarios are considered sufficient for
now: this plan does not add another journey, change the required `headless-core`
gate, or promote nightly checks. Estimates are relative (`S`, `M`, `L`) and
include focused tests but not a release cycle.

## Executive priority

| ID | Priority | Kind | Size | Mission |
|---|---:|---|---:|---|
| C1 | P0 | Correctness fix | L | Prevent historical replay from mutating current IRC state |
| C2 | P0 | Correctness fix | L | Serialize IRC persistence and make contested updates atomic |
| C3 | P0 | Correctness fix | L | Give connection lifecycle and actor ownership one serializer |
| D1 | P1 | Fix + investigation | M | Export Room schemas and audit relational integrity |
| K1 | P1 | Consolidation | L | Standardize coroutine ownership, dispatchers, and transport setup |
| T1 | P1 | Consolidation | M | Harden existing E2E paths without expanding coverage |
| A1 | P2 | Android best practice | M | Use lifecycle-aware collection and typed Room queries |
| A2 | P2 | Investigation + refactor | L | Characterize performance before decomposing hotspots |
| O1 | P2 | Maintenance | S | Harden CI permissions, action references, and dependency updates |
| S1 | P2 | Investigation | M | Threat-model stored credentials and remote previews |
| H1 | P3 | Hygiene | S | Remove binary-test and nearby stale-code hazards |

Delivery order is C1, C2, C3, then D1 and K1. T1 is independent once the
current required suite is green. A1, O1, and H1 can land as narrow changes.
A2 depends on C1-C3 characterization coverage; S1 must finish its decision
record before security-sensitive implementation begins.

## C1. Historical replay must not mutate current IRC state

- **Priority / size / status:** P0, L, Completed 2026-07-15.
- **Depends on:** none. Land before C2 so provenance behavior is characterized
  independently of serialization.
- **Evidence:** `EventProcessor.onHistoryBatch` routes events through the live
  processor with only notification suppression. Historical JOIN, PART, QUIT,
  KICK, NICK, MODE, TOPIC, away/account/host changes, and network batches can
  therefore alter current joined state, topics, member rows, roster snapshots,
  user metadata, or invitation resolution after newer live state is known.

### Implementation

1. Replace the processing `notify` flag with an internal `EventOrigin` policy
   covering `LIVE`, `HISTORY`, and `PUSH`.
2. Keep timeline persistence separate from session-state mutation. `HISTORY`
   may persist ordinary messages, relations, redactions, typed system rows, and
   collapsed network-event rows, but must not change current joined state,
   topic, roster, member/user metadata, read markers, or invitation state.
3. Treat historical invitations as non-actionable and prevent old playback
   from resolving or reopening a newer invitation.
4. Restrict `PUSH` to supported message/relation persistence and notification
   behavior. It must not act as a live roster, topic, or session-state feed.
5. Keep `EventProcessor` as the only external IRC-to-Room writer; provenance is
   an internal processing policy rather than a second persistence path.

### Acceptance and focused verification

- Seed a joined buffer with current topic, roster, enriched users, read marker,
  and invitation state. Replay older JOIN/PART/KICK/QUIT/NICK/MODE/TOPIC,
  metadata, invitation, and netsplit/netjoin events.
- Assert that live state is byte-for-byte equivalent while eligible timeline
  rows are complete and idempotent.
- Assert that history posts no notification, advances no read marker, and
  exposes no historical action.
- Cover duplicate history batches and history arriving concurrently with a
  newer live event; C2 supplies deterministic ordering for the latter.

### Completion evidence

- `EventOrigin` now distinguishes live socket, history replay, and push
  delivery. History persists eligible chat, relation/redaction, typed system,
  invitation, and collapsed network-event rows through `EventProcessor`
  without changing current buffer, roster, user, read-marker, invitation,
  bouncer-network, or typing state.
- Push delivery uses an explicit sink entry point, persists only the mapped
  message/relation/invitation subset, retains notification behavior, and
  ignores arbitrary session-state events.
- Regression coverage snapshots current state, replays the complete stale
  event mix twice, proves timeline idempotency and state equivalence, and
  verifies that a later live mention still uses the current self nick.
- Verified with `:app:testFossDebugUnitTest`, `:app:lintFossDebug`, and
  `:app:assembleFossDebug`. Concurrent live/history ordering remains assigned
  to C2 as specified above.

## C2. Serialize IRC persistence and make contested updates atomic

- **Priority / size / status:** P0, L, Completed 2026-07-15.
- **Depends on:** C1 provenance contract.
- **Evidence:** live socket delivery, history resync, paging/search history,
  and push delivery can enter the singleton `EventProcessor` concurrently.
  Buffer creation is lookup-then-insert, and several paths update full entity
  copies. Concurrent first-contact events can race a unique insert; unrelated
  pin, mute, topic, joined, and history-bound updates can overwrite one another.
  Pending-send timeout also reads and rewrites a message copy that can become
  stale after a labeled echo confirms it.

### Implementation

1. Add a per-network event sequencer around every public processing entry.
   Process history batches through a lock-owned private method so recursive
   batch handling cannot reacquire the same lock. Evict sequencers when their
   network is deleted and during processor shutdown.
2. Keep database transactions narrowly scoped per logical event; do not hold a
   Room transaction across network I/O or a complete remote history request.
3. Introduce `BufferStore.getOrCreate` using insert-ignore plus reread inside a
   Room transaction. Share it with event processing and connection/query
   creation instead of duplicating lookup-then-insert logic.
4. Replace whole-row read/copy/write operations with column-specific DAO
   updates for independently owned buffer and network fields.
5. Change pending timeout into an atomic conditional update that only fails a
   still-pending, unconfirmed local row. Echo confirmation must converge the row
   to confirmed even when timeout wins immediately beforehand.

### Acceptance and focused verification

- Use coroutine barriers to deliver concurrent live, history, and push first
  messages for one target; exactly one buffer and one logical message survive.
- Race echo confirmation against timeout in both orders; confirmation is never
  reverted and duplicate messages are never inserted.
- Interleave pin, mute, read, topic, joined, and history-bound writes; every
  independently owned field retains its last valid update.
- Preserve cross-network concurrency and verify sequencer cleanup.

### Completion evidence

- All live, history, push, registration, pending-send, timeout, and roster
  cancellation entry points now share one race-safe per-network sequencer.
  History stays on the lock-owned private path, different networks remain
  concurrent, and deletion reconciliation plus manager shutdown retire cached
  sequencers and processor state.
- `BufferStore.getOrCreate` uses insert-ignore plus reread inside a Room
  transaction and is shared by event persistence and connection-driven query
  and server buffer creation.
- Independently owned buffer fields and bouncer connection fields use targeted
  DAO updates. Pending timeout is a conditional SQL update and cannot revert a
  row whose echo already cleared its pending label.
- Regression tests cover concurrent live/history/push first contact, concurrent
  buffer creation, cross-network progress, eviction during active work,
  independent buffer-field updates, and echo/timeout convergence in both
  orders.
- Verified with `:app:testFossDebugUnitTest`, `:app:lintFossDebug`, and
  `:app:assembleFossDebug`.

## C3. Give connection lifecycle and actor ownership one serializer

- **Priority / size / status:** P0, L, Completed 2026-07-15.
- **Depends on:** none; may proceed alongside C1-C2 but should land before K1.
- **Evidence:** `ConnectionManagerImpl` owns startup, reconciliation, actor
  creation, configuration fingerprints, pending echoes, foreground state, and
  multiple callback paths. `startAll`, DAO-driven reconciliation, delivery-mode
  changes, explicit connect/disconnect, and actor callbacks can overlap. One
  actor map is concurrent, but adjacent maps and compound lifecycle decisions
  remain independently mutable.

### Implementation

1. Extract a `ConnectionRegistry` owned by one coroutine command loop. It is
   the exclusive owner of startup state, actors, configuration fingerprints,
   generations, and pending echo jobs.
2. Route start, stop, reconcile, connect, disconnect, trust, and actor callbacks
   through registry commands. Callbacks enqueue commands instead of mutating
   manager maps directly.
3. Publish immutable registry snapshots through `StateFlow`; do not expose
   mutable maps or hold registry ownership across an external callback.
4. Make repeated startup idempotent, actor construction unique, shutdown await
   cleanup, and generation checks reject late callbacks from stopped actors.
5. Retain `ConnectionManagerImpl` as the app-facing facade. Defer further
   coordinator extraction until these lifecycle guarantees are tested.

### Acceptance and focused verification

- Barrier/virtual-time tests prove that many concurrent `startAll` calls create
  one observer set and one actor per identity.
- Cover reconcile versus disconnect, stop during a callback, root-ready plus
  bouncer-child reconnect, terminal configuration changes, and stale callbacks.
- Shutdown leaves no actor, fingerprint, observer, or pending echo job; late
  callbacks cannot resurrect state.

### Completion evidence

- `ConnectionRegistry` is now the single command-loop owner of startup state,
  actors, fingerprints, terminal fingerprints, generations, observer jobs,
  actor callback jobs, and pending echo timeouts. `ConnectionManagerImpl`
  remains the facade and consumes immutable registry snapshots.
- Start, stop, reconcile, connect, disconnect, connectivity changes, actor
  state/connection/termination callbacks, connection events, readiness work,
  certificate callbacks, and echo timeouts now cross the registry boundary.
- Actor attempts parent their event collector, readiness setup, stability
  timer, and retry timer to the actor job; registry shutdown awaits actor and
  observer cleanup, while startup failure rolls the registry back for retry.
- Virtual-time tests cover concurrent startup/reconcile, unique actor creation,
  configuration replacement and terminal parking, stale callbacks, reconcile
  versus disconnect ordering, disconnect and shutdown during callbacks,
  complete owned-resource cleanup, echo-timeout cancellation, and actor-owned
  child-job cleanup. Existing child-reconnect and shared-certificate endpoint
  tests retain the bouncer-root/child behavior.
- Verified with `:app:testFossDebugUnitTest`, `:app:lintFossDebug`, and
  `:app:assembleFossDebug`.

## D1. Export Room schemas and audit relational integrity

- **Priority / size / status:** P1, M, Completed 2026-07-15; relationship
  constraints investigated and deliberately deferred.
- **Depends on:** C2 column-specific update inventory should inform DAO tests.
- **Evidence:** both Room databases disable schema export. Individual migration
  tests exist, but there is no checked-in schema history or generated-schema
  diff protecting future migrations.

### Implementation

1. Configure Room schema export for the application and avatar databases and
   check the generated schemas into a stable repository directory.
2. Reconstruct application schemas v1-v5 from their version-bump revisions in
   temporary worktrees. If an old revision no longer builds, record the exact
   revision and failure while retaining its hand-built migration fixture.
3. Add a chained v1-to-current migration test and a CI check that rejects
   uncommitted generated-schema changes.
4. Audit orphan data and migration consequences before proposing a self-FK for
   network parents, uniqueness for bouncer child identities, or foreign keys
   and cascades for member/user/reaction records.
5. Implement relationship constraints only through a non-destructive migration
   with explicit repair behavior and migration tests.

Follow Room's schema and migration validation guidance:
<https://developer.android.com/training/data-storage/room/migrating-db-versions>.

### Completion evidence

- Both databases export through the Room Gradle plugin. Application schemas
  v1-v6 and avatar schema v1 are checked in under `app/schemas/`; CI rejects
  modified or untracked generated schema files.
- Application v1, v2, v3, and v5 were generated from their exact historical
  revisions. No v4 database revision was committed, so v4 was reconstructed
  from the explicit v3-to-v4 and v4-to-v5 entity delta. Provenance and the
  relational decision are recorded in
  [`25-room-schema-provenance-and-integrity.md`](25-room-schema-provenance-and-integrity.md).
- A Robolectric test builds the real v1 database from the tracked schema JSON,
  inserts representative related data, runs every migration through v6, and
  has Room validate the final schema and preserved data.
- The unused raw network delete DAO path was removed; buffer and network tree
  deletion remain transactional and covered. Network repository creation now
  serializes the identity read/insert boundary, with concurrent duplicate-add
  coverage.
- A v7 foreign-key/unique-index migration is deferred because existing orphan
  and duplicate child repair can discard or ambiguously merge history. The
  investigation records inventory SQL and the required non-destructive merge
  decisions rather than silently selecting a repair policy.

## K1. Standardize coroutine and transport ownership

- **Priority / size / status:** P1, L, Completed 2026-07-15.
- **Depends on:** C3 lifecycle ownership.
- **Evidence:** multiple singletons create hardcoded scopes, several components
  hardcode dispatchers and wall-clock access, broadcast receivers repeat
  lifecycle boilerplate, and app transport creation performs blocking DataStore
  and certificate-policy lookups.

### Implementation

1. Provide qualified application scope, IO/default dispatchers, and a testable
   clock through Hilt. Replace component-created scopes incrementally and track
   cancellable jobs at their owning lifecycle.
2. Resolve DataStore, STS, and certificate policy suspendingly before invoking
   the synchronous `:irc` transport boundary. Remove `runBlocking` from app
   transport creation without introducing Android dependencies into `:irc`.
3. Add one bounded `goAsync` helper for ordinary broadcast receivers, with
   consistent timeout, cancellation, logging, and `PendingResult.finish()`
   behavior.
4. Retain and document the push connector's callback bridge until its external
   completion contract is independently verified; do not mechanically replace
   it with the receiver helper.

Use Android's coroutine ownership and dispatcher-injection guidance:
<https://developer.android.com/kotlin/coroutines/coroutines-best-practices>.
Verification uses virtual time for cancellation, restart, timeout, and shutdown.

### Completion evidence

- Hilt now provides qualified process scope, default/IO dispatchers, and a
  testable clock. Process-lifetime coordinators share that supervised scope;
  blocking preview work uses the injected IO dispatcher, and owned delayed or
  timeout jobs remain tracked by their coordinator.
- `ConnectionActor` accepts a suspending connection factory. Each attempt reads
  the current STS and leaf-pin policy before constructing `AppTransportFactory`,
  whose synchronous `TransportFactory` entry point now consumes only an
  immutable prepared snapshot. No app transport path uses `runBlocking`.
- Boot, invite-dismiss, direct-reply, and mark-read receivers share a bounded
  helper with one timeout/failure policy, cancellation propagation, and exactly
  one `PendingResult.finish()`. Virtual-time tests cover success, failure,
  timeout, and parent cancellation.
- The UnifiedPush connector callback bridge remains separate and documented;
  its library-owned completion and store-settle behavior was not mechanically
  changed.
- Focused transport/receiver/actor tests and the complete
  `:app:testFossDebugUnitTest` suite pass. `:app:lintFossDebug` and
  `:app:assembleFossDebug` also pass.

## T1. Consolidate the existing E2E infrastructure

- **Priority / size / status:** P1, M, Completed 2026-07-15.
- **Depends on:** current `headless-core` green baseline.
- **Coverage constraint:** preserve the four required Compose journeys, the
  nightly/manual A-I runbook, managed-device smoke, and VLESS socket check. Add
  no new history/reconnect journey and change no required/optional tier.
- **Evidence:** local headless, CI, smoke, and exhaustive paths duplicate
  fixture arguments and lifecycle/log handling. The local stack can re-enter an
  unpinned registry shell, the fast suite hardcodes class names, and the large
  shell runbook mixes required assertions with conditional and diagnostic
  probes. Some selector TODOs predate stable tags already present in the UI.

### Implementation

1. Create one canonical fast-suite launcher and fixture configuration shared by
   local headless, CI, and smoke entry points. Discover the instrumentation
   package automatically rather than maintaining a class list.
2. Add a lockfile-backed `e2e-stack` Nix development shell and replace
   registry-based `nix shell nixpkgs#...` re-execution.
3. Share stack lifecycle, readiness, and failure-artifact helpers while keeping
   both connected-emulator and Gradle-managed-device runners.
4. Update the runbook to use existing stable semantics/tag prefixes. Remove
   stale selector TODOs and text fallbacks where a stable tag already exists.
5. Classify every phase as required, conditional, or diagnostic. Required
   phases fail when their preconditions hold; conditional phases emit an
   explicit skip reason; diagnostics never mask an earlier failure.
6. Emit JUnit or JSON summary data and always retain app logcat, screenshot,
   stack versions, and service logs on failure.
7. Add fast shell-syntax and Docker Compose configuration validation to
   ordinary CI. Preserve the `headless-core` job name and gating semantics.

### Completion evidence

- `fast-suite.sh` and `fast-suite.env` now own the annotation filter and all
  non-secret fixture arguments for local direct instrumentation, connected CI,
  and Gradle managed devices. Direct runs query the installed instrumentation
  package and discover annotated classes instead of maintaining four names.
- `harness.sh` and `hermetic-stack.sh` centralize stack readiness, lifecycle,
  device capture, service logs, status, and image/version evidence. Required
  `headless-core`, managed-device smoke, and exhaustive workflows use these
  helpers while retaining their existing coverage and gating roles.
- The flake exposes a lockfile-backed `e2e-stack` shell containing Ergo, Soju,
  ZNC, Python, OpenSSL, netcat, sing-box, and Xray. Native Soju and ZNC scripts
  re-enter it instead of an unpinned `nix shell nixpkgs#...` environment.
- The shell runbook targets dynamic drawer/chat/message rows through their
  stable tag prefixes, with visible text used only to disambiguate the tagged
  container. Required, conditional, and diagnostic phases are explicit;
  conditional skips have reasons and diagnostic findings cannot change an
  earlier required result.
- Both fast and exhaustive paths emit JSON summaries. Failure handling captures
  app logcat, a screenshot, instrumentation or Gradle reports, stack service
  logs, status, and pinned image/version identifiers before artifact upload.
- Ordinary CI runs `test/e2e/validate.sh` for Bash syntax and Docker Compose
  configuration while preserving the `headless-core` job and gate dependency.
  Local syntax/summary checks and Nix flake evaluation passed. The realized
  pinned shell contained every declared binary, and an isolated native
  Soju/Ergo `up` plus `history-check` returned `CHATHISTORY_SMOKE_OK`. Device
  and emulator E2E remain delegated to CI as required by repository policy.

References:

- Gradle managed devices:
  <https://developer.android.com/studio/test/managed-devices>
- Android Test Orchestrator:
  <https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/runner>

## A1. Use lifecycle-aware UI collection and typed Room queries

- **Priority / size / status:** P2, M, Completed 2026-07-15.
- **Depends on:** D1 for schema-backed query changes.
- **Evidence:** most Compose consumers are lifecycle-aware, but a remaining
  group of screens uses plain `collectAsState`. Fixed SQL is also scattered
  across sync, recovery, read-marker, connection, and transcript code despite
  being representable as typed DAO projections.

### Implementation and acceptance

1. Convert Android screen-level `Flow` collection to
   `collectAsStateWithLifecycle` without changing ViewModel ownership.
2. Consolidate activity-wide preference collection into one UI state holder
   where it removes duplicate subscriptions and recomposition.
3. Move fixed raw SQL into typed DAO projections. Retain and document raw SQL
   only for genuinely dynamic visibility predicates.
4. Add lifecycle stop/resume tests and DAO equivalence tests before deleting
   the old readers.

### Completion evidence

- Screen and activity `Flow` collection is lifecycle-aware. `MainActivity`
  combines its process-wide preferences into one state holder instead of
  maintaining five independent subscriptions.
- Fixed recovery, read-marker, history-boundary, open-buffer, target lookup,
  latest-marker, count, and bouncer-transcript SQL now lives in typed Room DAO
  methods and projections. The policy-driven message-visibility predicate is
  the sole intentional dynamic raw query.
- Robolectric coverage proves collection stops below `STARTED` and resumes
  with the latest value. In-memory Room coverage compares typed projections
  against seeded entities, including ordering, filtering, boundaries, and
  transcript fields.
- Verified with `:app:testFossDebugUnitTest`, `:app:lintFossDebug`, and
  `:app:assembleFossDebug`.

## A2. Characterize performance before decomposing hotspots

- **Priority / size / status:** P2, L, Completed 2026-07-15; runtime benchmark
  execution remains authorization-gated.
- **Depends on:** C1-C3 characterization tests.
- **Evidence:** connection management, event persistence, chat route, message
  rendering, chat state, IRC client, message list, and history coordination are
  the dominant production hotspots. Large size alone is not a refactoring goal;
  their mixed ownership and side effects are the risk.

### Investigation and implementation sequence

1. Capture characterization tests before moving responsibilities.
2. Keep `EventProcessor` as the IRC-to-Room boundary while extracting pure
   event routing/provenance policy and roster/timeline collaborators with
   explicit transaction ownership.
3. After C3, split readiness/catch-up/read-marker, outgoing/echo, presence, and
   roster coordination behind the existing `ConnectionManager` facade.
4. Split Compose route/state, viewport, composer, sheets, rich text, reply, and
   delivery-status code only along state-ownership boundaries. Use Compose
   compiler and recomposition metrics, not line-count targets.
5. Define Macrobenchmark and baseline-profile scenarios for cold start, opening
   a large retained chat, flinging, and composer startup. Final measurements
   require fresh maintainer authorization for a physical release-like arm64
   device; do not enable release minification.

References:

- Compose performance: <https://developer.android.com/develop/ui/compose/performance>
- Baseline profiles:
  <https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile>

### Completion evidence

- The release build has an opt-in Compose compiler report path. Current metrics,
  hotspot ownership, characterization coverage, refactor triggers, and exact
  Macrobenchmark/baseline-profile scenarios are recorded in
  [`26-performance-characterization-and-benchmark-mission.md`](26-performance-characterization-and-benchmark-mission.md).
- The provenance permissions and push allowlist are now a pure policy outside
  `EventProcessor`, with focused tests. C1-C3's processor sequencer,
  `BufferStore`, registry, and dedicated history/read-marker collaborators
  remain the tested decomposition boundaries.
- Release compiler output found the chat route, content, list, row, bubble, and
  composer entry points restartable and skippable. With no runtime regression
  evidence and no authorized physical-device run, broader manager/UI splitting
  and stability wrappers are deliberately deferred.
- `:app:compileFossReleaseKotlin -PmotdComposeMetrics=true --rerun-tasks`
  generated the recorded report. The provenance policy test passes; the full
  app suite is included in the final release-parity verification.

## O1. Harden CI permissions and dependency maintenance

- **Priority / size / status:** P2, S, Completed 2026-07-15; post-push CI
  confirmation remains part of the final branch gate.
- **Depends on:** none.
- **Evidence:** most workflows rely on implicit permissions, third-party actions
  use mutable major tags, and Gradle/Actions updates have no configured bot.

### Implementation and acceptance

1. Give workflows explicit least-privilege permissions, retaining write access
   only for release operations that require it.
2. Pin third-party actions to immutable commit SHAs and retain readable tag
   comments. Preserve workflow job names and release behavior.
3. Add weekly grouped dependency updates for Gradle and GitHub Actions.
4. Audit current workflow logs for deprecated action runtimes and resolve each
   in a dedicated maintenance change.

GitHub documents commit SHAs as the safest action reference:
<https://docs.github.com/en/actions/how-tos/reuse-automations/reuse-workflows>.

### Completion evidence

- CI, nightly E2E, and reusable smoke workflows explicitly grant only
  `contents: read`; release retains its required `contents: write` and no
  broader permission. Job names and gating behavior are unchanged.
- Every action reference is pinned to a verified 40-character upstream commit
  with a readable release comment. Checkout, setup-java, setup-gradle,
  upload-artifact, and the release publisher move to their Node 24 release
  lines; emulator-runner and nix-installer retain their existing major behavior
  while becoming immutable.
- Recent successful CI (`29403146878`), nightly E2E (`29394353204`), release
  (`29403720919`), and smoke (`29372235175`) logs all reported the Node 20
  action-runtime deprecation. The upgraded action metadata declares `node24`;
  post-push CI will supply runtime confirmation. Separate Gradle 9 and Android
  Java-API deprecation messages are build-source maintenance, not deprecated
  action runtimes.
- Dependabot now proposes weekly grouped Gradle and GitHub Actions updates.
  `actionlint` and YAML syntax validation pass locally.

## S1. Threat-model stored credentials and remote previews

- **Priority / size / status:** P2, M, investigation mission.
- **Depends on:** none; implementation is blocked on the decision record.
- **Evidence:** SASL credentials and VLESS configuration are stored with Room
  state; WebPush private/auth/management material is stored in preferences.
  Backups are disabled, which narrows but does not remove the local-device
  exposure. Link and image previews initiate remote HTTP requests and follow
  redirects without one shared URL policy.

### Investigation deliverables

1. Document attacker capabilities, protected assets, process/device compromise
   boundaries, recovery behavior, and migration compatibility.
2. Design direct Android Keystore wrapping where it materially improves the
   threat model. Do not introduce SQLCipher or deprecated convenience security
   libraries without measured need.
3. Define one preview URL/redirect policy covering HTTP(S)-only handling,
   embedded credentials, redirect revalidation, cookies, and loopback/private
   address access.
4. Treat intranet-link support and default-on versus opt-in previews as an
   explicit product decision. Produce the decision and migration proposal
   before changing storage or preview behavior.

## H1. Focused hygiene

- **Priority / size / status:** P3, S, Ready.
- **Depends on:** none.
- **Evidence:** `IrcClientTest.kt` contains a literal NUL in a SASL test string,
  causing Git and search tools to classify the source as binary. Some stale
  TODO/frozen-workaround comments and avoidable assertions remain in adjacent
  code.

### Implementation and acceptance

1. Replace the literal NUL with the equivalent escaped `\u0000` representation
   and prove the SASL wire expectation is unchanged.
2. Remove stale comments and avoidable assertions only while changing their
   surrounding subsystem.
3. Do not create a broad formatting or cleanup-only change.

## Internal interfaces and compatibility

- No external API is required. Expected internal seams are `EventOrigin`,
  `ConnectionRegistry`, `BufferStore`, injected coroutine qualifiers, and a
  testable clock.
- Preserve database and serialized preference compatibility. Any new Room
  relationship constraint requires an audited migration and migration tests.
- Keep `:irc` pure JVM and Android-free. Keep `EventProcessor` as the sole
  external writer of IRC-derived state.
- FOSS remains the only active build and release flavor. Do not add Google
  tasks, another networking stack, or release minification.

## Verification policy

For each implementation mission, run the affected module commands from
`.agents/testing.md`. Cross-module changes additionally require the documented
FOSS unit suite, lint, debug build, and release-parity build. Tests for the
changed behavior land with the change.

Use the existing CI `headless-core` suite for E2E proof. Local device/emulator
E2E, a new Soju/VLESS matrix, device installation, merge, tag, and release are
not implicit parts of this roadmap. Physical performance proof requires a new
explicit authorization.

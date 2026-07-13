# Implementation backlog: chat correctness, reliability, and UX

Date: 2026-07-13
Baseline: `main` at `e7fc5c3` (`v0.4.5`)

This is the implementation specification for the 21 reports collected after
`v0.4.5`. Each entry states the product behavior, implementation boundary,
failure behavior, acceptance criteria, and focused verification. An agent may
take one entry, or the explicitly coupled entries, without reopening product
decisions.

Estimates are relative (`S`, `M`, `L`) and include focused tests, not a release
cycle. Status is **Ready** unless an entry is explicitly an investigation or
feasibility spike.

## Ground rules

- Do not edit the frozen contracts in `plans/10-contracts.md`. New settings and
  orchestration APIs stay app-owned, following the existing
  `AppearancePrefs`/`AttachmentPrefs` pattern.
- Do not add dependencies or move Android code into `:irc`.
- Prefer one source of truth over parallel UI-, DAO-, and service-specific
  interpretations of the same state.
- Reproduce correctness regressions with a focused test or deterministic local
  stack case before changing behavior when practical.
- Preserve cached chats and drafts through recoverable connection failures.
- Direct IRC, soju, and history replay must degrade explicitly when a capability
  is unavailable; unsupported features must never look successful.

## Shared app-owned interfaces

These names describe the intended seams. Exact package placement may follow the
nearest existing app-owned feature, but none may be added to the frozen contract
files.

- `ReplyConfig` / `ReplyPrefs`: whether channel replies include a visible nick
  prefix. Default `false` (semantic tag only).
- `ContentPreviewConfig` / `ContentPreviewPrefs`: `showImages` and
  `showLinkPreviews`, both default `true`.
- `TypographyConfig` / `TypographyPrefs`: independent UI and conversation
  scales, each stored as a clamped integer percentage.
- `MessageVisibilityPolicy`: pure classification used by timeline, chat list,
  unread, search, saved-position, and effective-bottom readers.
- `HistoryResyncCoordinator`, `HistoryResyncState`, and
  `HistoryResyncReason`: one single-flight path shared by reconnect and manual
  refresh.
- `SelfAvatarSetting`, `AvatarConfig`, `AvatarPrefs`, `AvatarRecord`, and
  `AvatarStore`: self publication state, sharing preference, and remote avatar
  URL metadata.
- `NetworkPresetId`, `NetworkPreset`, and `PresetEnrollmentPrefs`: compile-time
  connection catalog and one-shot preset enrollment state.
- `BouncerServCommand`, `BouncerServCapabilities`, `BouncerServResult`, and
  `BouncerServClient`: safe command construction, help-based feature discovery,
  serialized execution, and response/cancellation state for a soju root.
- An internal reaction-mutation helper/store that can optimistically add or
  remove the current user's reaction without changing the frozen repository or
  connection-manager interfaces.

## A. Reply and reaction interoperability

### A1. Reactions do not sync to other users

- **Priority / size / status:** P0, M, Complete (2026-07-13).
- **Depends on:** none. Implement together with A2.
- **Evidence:** `IrcClient.sendReact` currently emits `+draft/react` with
  `+draft/reply`; `EventMapper` and push mapping only read the legacy reply
  reference. The ratified reference tag is `+reply`.
- **Implementation:**
  1. Send reactions as `TAGMSG` with `+draft/react=<emoji>` and
     `+reply=<msgid>`.
  2. Receive `+reply` first and fall back to `+draft/reply` for live events,
     CHATHISTORY replay, and push payload mapping.
  3. Add `+draft/unreact` support in the same work. Tapping a reaction already
     owned by the current user removes it optimistically, sends
     `+draft/unreact=<emoji>` with `+reply=<msgid>`, and restores it if sending
     fails. Other reaction taps keep the existing optimistic-add behavior.
  4. Parse the `CLIENTTAGDENY` ISUPPORT token, including `*` and negated
     exemptions. Reactions require both the reply reference and the relevant
     react/unreact tag to be sendable.
  5. If blocked, disable the reaction action and show a concise snackbar. Do
     not mutate the local reaction row or imply that the reaction was shared.
- **Failure behavior:** missing parent rows are ignored safely; legacy incoming
  tags remain readable; a send failure rolls back only the attempted local
  mutation; reconnect replay remains idempotent.
- **Acceptance:** two clients see add and remove operations live and after
  history replay. A blocked-tag server produces no outgoing tag and no durable
  optimistic state.
- **Focused tests:** serializer and mapper tests for ratified and legacy reply
  tags; `CLIENTTAGDENY` wildcard/exemption matrix; optimistic add/remove and
  rollback; duplicate replay; push mapping. Exercise two MOTD clients on the
  Ergo/soju stack and, when available, one compatible independent client.
- **2026-07-13 evidence:** MOTD now sends the ratified `+reply` reference,
  accepts ratified and legacy references in live, push, and history paths, and
  models `CLIENTTAGDENY` without changing frozen contracts. Reaction add/remove
  is optimistic, idempotent on replay, and rolls back only its own failed
  mutation. The action sheet and chips disable blocked operations with a
  snackbar instead of creating local-only state. Unit coverage includes the
  wildcard/exemption matrix, exact wire tags, push/history mapping, missing
  parents, duplicate removal, and add/remove rollback. An opt-in test drove two
  real `IrcClient` instances through the local Ergo fixture and proved reply,
  react, and unreact delivery plus CHATHISTORY replay. The fixture now stores
  `+draft/react` and `+draft/unreact` symmetrically; Ergo's default omitted the
  removal tag and could otherwise resurrect a reaction after reconnect.
- **References:** [IRCv3 reply](https://ircv3.net/specs/client-tags/reply),
  [IRCv3 reactions](https://ircv3.net/specs/client-tags/react),
  [IRCv3 message tags](https://ircv3.net/specs/extensions/message-tags), and
  [IRCv3 CLIENTTAGDENY](https://ircv3.net/specs/extensions/message-tags.html#rplisupport-tokens).

### A2. Replies do not render for other users or notify the replied-to user

- **Priority / size / status:** P0, M, Complete (2026-07-13); coupled to A1.
- **Depends on:** A1 tag parsing and `CLIENTTAGDENY` model.
- **Implementation:**
  1. Send `+reply=<msgid>` and receive `+reply` with legacy
     `+draft/reply` fallback everywhere A1 covers.
  2. Resolve an incoming reply target inside the same buffer. If the locally
     known parent sender is our current nick, set the existing mention flag and
     pass the event through normal foreground, mute, friend/fool, and
     notification precedence. Do not notify merely because a parent is absent.
  3. Add a global Chat preference for a visible reply prefix. It is off by
     default. When enabled, channel replies send visible `nick: <body>` in
     addition to the semantic tag. DMs do not add the prefix while tags work.
  4. If `CLIENTTAGDENY` blocks reply tags, retain Reply but fall back to visible
     `nick: <body>` in channels and DMs regardless of the preference. The local
     reply composer state still clears only after the send is accepted.
- **Failure behavior:** a missing or pruned parent renders the child normally;
  text mentions continue to work; fallback text is sent at most once and does
  not create a second local message.
- **Acceptance:** a compatible receiver renders the relationship; replies to
  one's own locally known messages produce exactly one mention notification;
  replies to another user do not; blocked tags visibly degrade instead of
  silently losing meaning.
- **Focused tests:** outgoing channel/DM matrix for preference on/off and tags
  allowed/blocked; receive ratified/legacy tags; self-parent mention resolution;
  missing parent; muted/fool/foreground precedence; history and push replay.
- **2026-07-13 evidence:** reply delivery is computed by a pure helper before
  send: semantic tags are preferred, an optional global Chat setting adds a
  visible channel prefix, and denied tags degrade to one visible prefix in both
  channels and DMs. Incoming replies resolve only a locally known same-buffer
  parent; a reply to the current user's message enters the existing mention and
  notification precedence exactly once, while another or missing parent does
  not. Local pending/echo reconciliation retains the original relationship.
  Focused tests cover the channel/DM preference and denial matrix, duplicate
  prefix avoidance, ratified/legacy receive, missing parents, self-parent
  mention notification, history, and push replay. The two-client real-Ergo test
  verified that a second client receives and replays the reply relationship.

## B. One visible-timeline definition

The five reports about hidden join/part/quit events, fool messages, saved chat
position, previews, and scrolling are one correctness package. Do not fix these
with unrelated predicates at individual call sites.

### B1. Hidden and collapsed content must not corrupt position or read state

- **Priority / size / status:** P0, L, Complete (2026-07-13).
- **Depends on:** B3 baseline reproduction should run first. B2 may land as the
  first slice if its query shape remains compatible with this policy.
- **Product policy:**
  - `JOIN`, `PART`, and `QUIT` follow the in-chat display setting but never
    become a chat-list preview or chat-list activity timestamp.
  - `KICK`, `NICK`, `MODE`, `TOPIC`, and `ERROR` remain previewable.
  - Fool messages never affect chat-list preview/order, visible unread or
    mention counts, saved anchors, the jump FAB, or effective-bottom state.
  - `FoolsMode.COLLAPSE` keeps an expandable placeholder in the timeline.
    `FoolsMode.HIDE` removes the row.
  - Search excludes fool-authored rows only in Hide mode. In Collapse mode a
    fool result remains searchable, and opening it expands the target
    placeholder.
  - At effective bottom, the local/server read marker may advance across a raw
    tail made only of ignored rows. Clear the saved anchor so the same tail does
    not repeatedly restore the user above bottom.
- **Implementation:**
  1. Introduce a pure `MessageVisibilityPolicy` that answers timeline,
     preview, activity, visible-unread, search, anchor, and effective-bottom
     eligibility from message kind, sender moderation, and settings.
  2. Route timeline filtering, chat-list projection, unread/FAB computation,
     search, saved-position selection, and read advancement through dedicated
     policy-backed readers. Avoid duplicating predicates.
  3. Keep the base Room chat-list query responsible for choosing one consistent
     non-JPQ candidate. Apply the dynamic fool set in the repository using
     cached/chunked raw candidate reads rather than changing frozen entities or
     pretending a DataStore set is static SQL state.
  4. Resolve initial and saved positions against the newest eligible anchor.
     If an old saved anchor is now hidden, walk to the nearest meaningful row;
     if none exists, enter at effective bottom.
  5. Treat ignored raw tails as settled bottom for following and read state.
     The wire marker stays monotonic and may cover intervening ignored events.
- **Failure behavior:** a chat containing only JPQ/fool content has a blank
  preview and no false unread/FAB state; changing fool mode or JPQ visibility
  never strands a stale anchor; large fool sets do not generate an unbounded
  SQL statement.
- **Acceptance:** all consumers agree on the policy across setting changes and
  app restart. Ignored activity neither reorders chats nor prevents the latest
  meaningful message from being followed/read.
- **Focused tests:** matrix for every newest-row kind, JPQ shown/hidden, fool
  Collapse/Hide, fool-only buffers, stale saved anchors, search/open behavior,
  visible counts, chat ordering, FAB, auto-follow, and local/wire markers.
- **2026-07-13 implementation evidence:** one app-owned
  `MessageVisibilityPolicy` now defines timeline, preview/activity, visible
  unread, search, saved-anchor, and effective-bottom eligibility. Paging,
  chat-list fallback and ordering, search, unread entry/FAB state, saved
  positions, auto-follow, and raw-tail read advancement consume that policy.
  The database reader walks fixed 128-row pages and keeps a bounded 256-entry
  chat-list cache, so the dynamic fool set never becomes an unbounded SQL
  clause. Tests cover JPQ and both fool modes, a fool-only buffer, a 300-row
  ignored tail, stale anchors, search, counts, ordering, and follow decisions.

### B2. Chat-list previews never show join/part/quit

- **Priority / size / status:** P1, S, Complete (2026-07-13), first slice of B1.
- **Depends on:** query/API shape agreed with B1.
- **Implementation:** make the `lastMessage*` projections derive from the same
  selected newest non-JPQ message identity. Use a CTE/subquery supported by the
  pinned Room/SQLite version rather than three independent correlated choices.
  Preserve previewability of `KICK`, `NICK`, `MODE`, `TOPIC`, and `ERROR`.
- **Failure behavior:** if no eligible row exists, preview fields are null/blank
  and the ignored event does not update the list's activity ordering.
- **Acceptance / tests:** DAO tests where a newer JOIN, PART, and QUIT follows a
  chat row; a JPQ-only buffer; each retained system kind; consistent text,
  sender, kind, and timestamp from one row.
- **2026-07-13 evidence:** `BufferDao.observeChatList` now joins exactly one
  newest preview-eligible message by row identity and derives its text, sender,
  timestamp, and activity ordering from that row. JOIN, PART, and QUIT are
  excluded unconditionally; a JPQ-only buffer has a blank preview and null
  activity. KICK, NICK, MODE, TOPIC, and ERROR remain eligible. Room tests cover
  every excluded and retained kind and verify that all exposed fields come from
  the selected row.

### B3. Deeply re-verify auto-follow on the v0.4.5 baseline

- **Priority / size / status:** P0, S, Complete (2026-07-13). Baseline and
  post-fix physical-device matrix captured.
- **Depends on:** attached physical device for final evidence.
- **Investigation:** reproduce before editing the tracker. Cover idle at bottom
  receiving a burst, user scrolled up, own send from both positions, hidden JPQ
  burst, collapsed/hidden fool as newest row, delayed echo, delayed MARKREAD
  acknowledgment, Paging prepend/refresh, background/resume, and reconnect
  catch-up.
- **Instrumentation:** add a debug-only structured trace with timestamps for
  Room insert, Paging presentation, user-intent state, follow decision,
  programmatic scroll start/end, viewport settle, local marker, and outgoing /
  incoming `MARKREAD`. Keep it disabled in release builds and cheap when idle.
- **Behavior to preserve:** incoming rows auto-follow only while the user has
  not intentionally left effective bottom. An explicit successful own send
  always jumps to that sent message and resumes follow. Cancelling the long
  draft prompt does not scroll; choosing Send as messages scrolls after the
  actual send. MARKREAD timing never decides viewport following.
- **Acceptance:** each matrix case has a deterministic trace explanation. No
  fix is accepted solely from an emulator or a green build; install the tested
  debug APK on the physical device and record `dumpsys gfxinfo` evidence while
  exercising bursts and entry.
- **Focused tests:** extend pure `AutoFollowTracker` and Compose list behavior
  tests for effective bottom, ignored tails, explicit send, prompt cancel, and
  delayed Paging/read state.
- **2026-07-13 evidence:** tested a FOSS debug APK built from the v0.4.5
  baseline plus trace-only instrumentation on a physical A059 (Android 16,
  API 36; serial `00152151K005265`). APK SHA-256:
  `4e16589c7e475d392151d2fee7551538ae756c6cac1d506c6eac8676ab80ff30`.
  Both app flavor unit suites and `:app:assembleFossDebug` passed before the
  install. The deterministic local Ergo + soju fixture supplied numbered
  bursts and JPQ-only activity; a recorded-PID STOP/CONT pair supplied delayed
  echo and marker acknowledgments.
- **Matrix result:** at-bottom bursts followed; intentionally scrolled history
  stayed fixed with the jump FAB; an own send from history resumed follow; the
  delayed server echo updated the existing row without changing item count;
  local `MARKREAD` and viewport following remained independent while soju was
  paused; background/resume opened on the newest burst; and process reconnect
  inserted all twelve missed rows as history before channel entry settled at
  index zero. Paging entry loaded 139 rows at index zero, and manual traversal
  to the oldest loaded row (index 134) preserved the history position.
- **Confirmed defects:** cancelling the long-draft prompt scrolls because the
  composer invokes `scrollToNewest` immediately after opening the prompt,
  before a send choice exists. Choosing **Send as messages** exhibits the same
  premature scroll and only then inserts the messages. With JPQ display off,
  JOIN/PART/QUIT rows still increased the presented count and triggered follow
  decisions (the renderer merely summarized them). **Collapse** likewise kept
  all twelve fool rows as placeholders and advanced follow state; **Hide**
  removed the fool PRIVMSG rows, but the retained JPQ tail still advanced it.
  These observations define the regression tests and fix boundary for B1.
- **Performance / safety:** `dumpsys gfxinfo` recorded 780 frames, 14.74% jank,
  p50 13 ms, p90 23 ms, p95 30 ms, and p99 61 ms across the deliberately
  scripted burst/deep-scroll matrix. The crash buffer was empty. Device
  preferences were restored byte-for-byte from the pre-test backup, the debug
  trace property was disabled, the local stack was stopped, and the installed
  release app was untouched.
- **Post-fix device evidence:** installed FOSS debug APK SHA-256
  `266763674a3219c26a8a2abe1cafb7babf7f3e648c8c7f12d23fb0236e9e5d8c` on
  the same A059. Cancelling the long-draft prompt while reading history emitted
  no scroll; explicit **Send as messages** scrolled only after submission.
  Hidden PART/QUIT rows did not change the presented count or follow state;
  retained MODE remained meaningful. A collapsed 12-message fool burst grew
  the presented placeholder tail but every follow decision stayed false; Hide
  kept the presented count unchanged. In both modes the raw marker advanced,
  reopening settled at effective bottom, fool activity never became the chat
  preview, Collapse search expanded its target, and Hide search excluded it.
  The crash buffer was empty. A follow-up timeline traversal/re-entry sample
  recorded 741 frames, 7.15% jank, p50 12 ms, p90 23 ms, p95 26 ms, and p99
  65 ms. Trace and animation settings were restored, the debug app was stopped,
  the local stack/reverse were removed, and the running release app was not
  modified or stopped.

## C. Connection loss, reconnect, and history

### C1. Seamless connection loss and recovery

- **Priority / size / status:** P1, L, Complete (2026-07-13).
- **Depends on:** ZNC fixture from C3 for its coverage; soju/direct work may
  proceed first.
- **Required UX:** cached chats and the composer remain usable while offline or
  reconnecting. Drafts remain editable, but Send and typing publication are
  disabled until `Ready`; there is no offline send queue. Show one current,
  non-blocking reconnect state and remove stale proxy/SOCKS errors after a
  successful transition to Ready.
- **Implementation strategy:**
  1. Build a deterministic fault matrix for direct TLS, soju,
     VLESS+REALITY/SOCKS, and ZNC: EOF, blackholed socket/watchdog, proxy not
     ready, Android freeze/resume, network switch, manual disconnect, backoff,
     delayed CAP, cancellation, and a late failure from an obsolete attempt.
  2. Key all connection state and errors to an attempt/generation so cancelled
     or superseded attempts cannot overwrite Ready.
  3. On a direct connection reaching Ready, re-JOIN channels still marked
     locally joined before catch-up. Do not rejoin channels explicitly parted
     by the user or bouncer child networks whose membership is managed by the
     bouncer.
  4. Invoke the app-owned single-flight resync path from C2 after capability
     settlement and required direct re-JOINs. Never launch parallel catch-ups.
- **Failure behavior:** cancellation closes transport work; backoff is
  interruptible; a missing capability reports unsupported without destroying
  cached data; recovery never duplicates messages or advances read state just
  because history arrived.
- **Acceptance:** every fault ends in Ready or a current actionable error;
  missed messages reconcile where supported; no stale SOCKS error survives a
  successful reconnect; manual disconnect remains manual.
- **Verification:** scripted service tests with virtual time and attempt IDs,
  local direct/soju/ZNC stacks, plus the attached release-like device path for
  background idle and VLESS+REALITY.
- **Implementation evidence:** connection callbacks and Ready setup are guarded
  by a monotonically increasing per-network generation, retryable failures
  immediately publish `Connecting`, and foregrounding wakes a surviving
  non-ready actor through its conflated backoff signal. Direct networks restore
  only durable joined channels before catch-up; parted channels, queries, and
  bouncer children are excluded. The chat header now prioritizes current
  connection state over member/typing detail without disabling offline draft
  editing. The local soju fixture has state-preserving `stop-soju` / `start-soju`
  fault controls.
- **Verification evidence:** both flavor unit suites cover generation invalidation,
  obsolete callbacks, cancellation-owned Ready setup, interruptible virtual-time
  backoff, durable direct re-JOIN selection, and stale error/subtitle behavior.
  On the attached A059/API 36 device, stopping soju preserved an unsent draft,
  changed the chat to `Connecting…`, disabled Send, and did not crash; a 12-message
  upstream gap appeared after the preserved bouncer restarted. A second run with
  the final build restored Ready/Send within five seconds of foregrounding while
  retaining the draft, with no stale SOCKS error. The native ZNC fixture covers
  detached playback; the local VLESS+REALITY fixture passed SOCKS5 CONNECT,
  TLS 1.3, and IRC CAP end to end on isolated ports. Cloak is intentionally
  treated as ZNC-compatible per C3.

### C2. Manual and automatic history revalidation

- **Priority / size / status:** P1, M, Complete (2026-07-13).
- **Depends on:** C1 generation/cancellation semantics.
- **Implementation:**
  1. Add `HistoryResyncCoordinator` as the sole resync entry point for automatic
     reconnect and manual refresh. Scope single-flight work per network/buffer
     and coalesce equivalent requests.
  2. Prefer `CHATHISTORY AFTER <target> msgid=<id>` when `MSGREFTYPES` says the
     server supports msgid references. Otherwise use the newest durable server
     timestamp. Page AFTER until complete and rely on existing durable dedup.
  3. If the boundary is absent or rejected, use a bounded `LATEST` page. Manual
     refresh also performs a bounded recent LATEST overlap check even when AFTER
     succeeds, so a silent local gap near the tail can be repaired.
  4. Expose per-chat **Refresh history** in the chat overflow. Surface
     `Running`, `Updated`, `Up to date`, `Unsupported`, and retryable `Failed`
     results without resetting or advancing read state.
- **Failure behavior:** timeout/cancel leaves local rows untouched; capability
  absence returns Unsupported; concurrent reconnect/manual requests do not
  duplicate commands; stale completion from an old connection generation is
  discarded.
- **Acceptance:** repeated refresh is idempotent; a removed local tail row is
  recovered; empty/no-boundary history works; refresh never changes unread
  position by itself.
- **Focused tests:** msgid/timestamp selection, missing/rejected boundary,
  recent LATEST overlap, pagination/server limits, empty result, dedup,
  timeout/retry/cancel, unsupported capability, concurrent reasons, and stale
  generation completion.
- **Implementation evidence:** the singleton `HistoryResyncCoordinator` is the
  only reconnect/manual tail-revalidation entry point. It coalesces identical
  work, serializes different requests per network, prefers msgid AFTER bounds
  only when `MSGREFTYPES` permits them, falls back to timestamp or bounded
  LATEST, and gives manual refresh a recent LATEST overlap. Every page rechecks
  the exact live client before the sole IRC-to-Room writer receives it. The old
  reconnect-only catch-up implementation was removed. Chat overflow now exposes
  **Refresh history** with Running, Updated, Up to date, Unsupported, offline,
  and retryable failure feedback.
- **Verification evidence:** the FOSS suite covers tail and middle-gap repair,
  repeated idempotency, empty stores, rejected selectors, msgid/timestamp bounds,
  TARGETS discovery, full/short pagination, timeout, unsupported capability,
  concurrent coalescing, stale-generation discard, and read-marker preservation.
  On the attached A059/API 36 device against local soju, the overflow action was
  visible and two successive refreshes returned `History is up to date` without
  a crash.

### C3. Add a native ZNC fixture; treat `parenworks/cloak` as compatible

- **Priority / size / status:** P1, L, Complete (2026-07-13). Cloak-specific
  fixture and validation work is intentionally out of scope under the
  ZNC-compatible behavior assumption.
- **Depends on:** none; do not block direct/soju correctness on Cloak.
- **ZNC implementation:** add a sibling native fixture under `test/e2e/` rather
  than complicating `local-stack.sh`. Run ZNC against the existing Ergo test
  network, provision deterministic credentials/channels/history, support
  status/seed/down, and use `adb reverse` for a device. Exercise CAP/SASL,
  native playback and timestamps, channel/query routing, echo/self messages,
  reconnect gaps, and two attached clients. Record the actual advertised
  capabilities; do not assume ZNC implements `draft/chathistory`.
- **Cloak scope decision:** assume Cloak exposes the same app-relevant
  degradation as ZNC: persistent upstream connectivity and timestamped backlog
  replay without `draft/chathistory`. Do not build, package, or validate Cloak
  separately. Reopen this decision only for a reproducible Cloak-specific app
  failure or evidence that its downstream IRC behavior differs materially.
- **Failure behavior:** fixtures fail loudly on occupied ports, preserve logs
  under a separate `/tmp` directory, and clean up by recorded PID rather than
  broad `pkill` patterns.
- **Acceptance:** ZNC setup is deterministic from the Nix environment and its
  observed degradation is covered in app tests/docs. That coverage is the
  accepted Cloak proxy until the scope decision above is reopened.
- **Reference:** [official ZNC repository](https://github.com/znc/znc) and
  [parenworks/cloak](https://github.com/parenworks/cloak).
- **2026-07-13 ZNC evidence:** `test/e2e/znc-stack.sh` provisions pinned Nix
  ZNC 1.10.1 over TLS on `6698`, reuses the deterministic Ergo accounts and
  history, installs/removes its adb reverse, records logs under a separate
  `/tmp/motd-znc-stack`, and stops only recorded PIDs. Its dependency-free
  protocol probe passed SASL PLAIN and PASS login, two attached clients,
  channel and query routing, self echo, a fully detached reconnect gap, and
  timestamped native playback. The observed downstream CAP set is `batch`,
  `cap-notify`, `chghost`, `echo-message`, `invite-notify`, `message-tags`,
  `multi-prefix`, `sasl=PLAIN`, `server-time`, `userhost-in-names`,
  `znc.in/batch`, `znc.in/self-message`, and `znc.in/server-time-iso`.
  `draft/chathistory` is absent even though Ergo advertises it upstream, so C1
  must accept native playback and C2 must report manual CHATHISTORY refresh as
  unsupported on this connection.
- **2026-07-13 Cloak decision:** inspected upstream v0.4.0 at commit
  `2e1bb3c7b379a3bd91c2b299c0cbaafe159f1de0` with Nix SBCL 2.6.5 and isolated
  Quicklisp dist 2026-01-01. Supplying the native compiler and explicit pinned
  OpenSSL runtime path gets past IOLib, CL+SSL, Spinneret, and the previously
  reported CLTL2 area. Loading then stops at `SYSTEM-NOT-FOUND: fluxion`:
  Fluxion is required by `cloak.asd`, is absent from Quicklisp, and the README's
  GitHub dependency URL is not publicly fetchable. The inspected source uses
  the same `username/network:password` client identity shape as ZNC and offers
  persistent upstream connectivity plus `server-time`/`batch` backlog replay;
  its downstream CAP list does not advertise CHATHISTORY. The expected client
  behavior is therefore sufficiently likely to match the ZNC degradation that
  no separate fixture is warranted now. Revisit only for a reported
  Cloak-specific failure or when upstream publishes a reproducible dependency
  closure.

## D. Bounded UX and verification work

### D1. Add a clear-search X

- **Priority / size / status:** P1, S, Complete (2026-07-13).
- **Depends on:** none.
- **Implementation:** show a trailing clear `IconButton` only for non-empty
  search input. One action atomically clears the local `TextFieldValue` and
  ViewModel query, preserves focus and keyboard state, and retains normal back
  behavior. Add localized content description and stable test tag.
- **Failure behavior:** clearing during an in-flight search prevents stale
  results from being presented as the empty query's results.
- **Acceptance / tests:** icon visibility, click and accessibility semantics;
  selection/composition reset; ViewModel empty-query state; stale result
  suppression.
- **Implementation evidence:** non-empty search input exposes a localized,
  accessibility-labeled clear action with stable `search_clear` semantics. The
  action resets the complete local `TextFieldValue` while retaining the
  existing focused field and immediately clears the ViewModel query.
- **Verification evidence:** focused tests cover IME selection/composition
  reset, immediate empty-query state, and rejection of a late result emitted by
  the cancelled pre-clear search flow.

### D2. Independently disable images and link previews

- **Priority / size / status:** P1, M, Complete (2026-07-13).
- **Depends on:** none; F1 must later honor these gates.
- **Implementation:** add app-owned `ContentPreviewPrefs` with `showImages` and
  `showLinkPreviews`, both default on, and two Chat settings switches.
  `showImages=false` prevents direct-image loads, link-card thumbnail loads,
  and avatar HTTP loads. `showLinkPreviews=false` prevents metadata requests and
  hides cards. Links remain styled/clickable. Gate before request creation and
  cancel obsolete work when a preference turns off.
- **Failure behavior:** cached metadata/images are not rendered or refreshed
  while disabled; switching either preference does not move the message list.
- **Acceptance / tests:** no repository/image request in each disabled mode;
  exact combined matrix; persistence/defaults; runtime toggle cancellation;
  clickable links; stable list position.
- **Implementation evidence:** app-owned `ContentPreviewPrefs` persists two
  independent default-on gates and Chat settings exposes both with stable
  semantics. The chat starts network content closed until DataStore emits,
  gates URL work before request creation, cancels row work when a gate changes,
  removes direct-image and link-thumbnail models from Coil, and gates the link
  repository before both cache and HTTP. Linkified message text remains
  independent and clickable. Interruptible HTTP work disconnects in `finally`
  when its row is cancelled.
- **Verification evidence:** focused FOSS tests cover persistence/defaults, all
  four image/link combinations, thumbnail removal with metadata retained, and
  a real MockWebServer proving disabled and cached link previews make zero new
  requests. The shared policy feeds every comfortable, compact, and two-line
  message density without changing paging items or list state.

### D3. Auto-join `#motd` for first-time direct Libera users

- **Priority / size / status:** P1, S/M, Complete (2026-07-13).
- **Depends on:** F2 preset identity and `PresetEnrollmentPrefs`.
- **Implementation:** disclose the behavior in the Libera preset UI. Only a
  newly created direct Libera preset is eligible. Atomically mark the one-shot
  attempt and issue `JOIN #motd` exactly once after its first Ready. Do not
  retry automatically if the JOIN fails; the user may join normally later.
  Exclude existing networks, Custom rows (even with a Libera hostname), soju or
  other bouncer children, and imported configurations.
- **Failure behavior:** process death or reconnect cannot issue a second
  automatic JOIN; a failed attempt remains attempted and produces normal IRC
  feedback rather than a hidden loop.
- **Acceptance / tests:** eligible first Ready, process-death atomicity,
  reconnect idempotence, failed JOIN, and every exclusion above.
- **Implementation evidence:** a dedicated `preset_enrollment` DataStore keeps
  eligible and attempted network-id sets separate from settings. Add Network
  marks only a newly inserted, unchanged Libera preset before connecting and
  revokes provisional rows on retry/abandon; Custom, existing, soju, imported,
  and bouncer-child paths never mark eligibility. The Libera choice discloses
  the one-time `#motd` JOIN. On the first current Ready generation, the service
  atomically consumes the durable claim before validating the persisted direct
  TLS endpoint and writing `JOIN #motd`; reconnect, process death, endpoint
  edits, cancellation, or a failed write cannot enqueue it again. Server JOIN
  errors still arrive through the ordinary IRC event stream.
- **Verification evidence:** focused tests cover DataStore recreation,
  atomic claim/revoke, first Ready and reconnect, obsolete generations, failed
  writes, durable endpoint validation, and every creation-path exclusion. The
  complete FOSS debug and release unit suites passed, followed by warnings-as-
  errors `:app:lintFossDebug`, debug assembly, and the full FOSS release-parity
  gate through `:app:assembleFossRelease`.

### D4. Verify the reported fool-menu crash on the current release

- **Priority / size / status:** P0, S, Complete (2026-07-14).
- **Depends on:** none.
- **Evidence:** commit `0436d50` removed blocking suspend reads from the
  notification path, and `MotdNotificationsFoolTest` covers adding a fool then
  receiving their DM.
- **Procedure:** on `v0.4.5`, add a fool from both the message-sender nick sheet
  and the channel-member nick sheet. Then receive their DM, open the DM from the
  chat list, and reopen it from the nick action. Capture logcat for the entire
  sequence.
- **Outcome:** if both paths pass, mark the report verified with build/device
  evidence and do not change code. If either crashes, save the exact stack
  trace, add a failing focused regression test, fix the traced cause, and rerun
  both paths. Do not apply a speculative second fix.
- **Implementation evidence:** the physical-device procedure reproduced
  `IllegalStateException: Attempt to collect twice from pageEventFlow` as soon
  as the sender sheet added a fool. The chat ViewModel had combined a changed
  visibility spec with the same single-collector `PagingData` generation. It
  now starts a fresh repository Paging generation with `flatMapLatest` for each
  distinct behavioral filter and caches the resulting stream. A focused flow
  test locks the new-generation invariant. The local seed fixture can hold its
  second member in-channel and emits a deterministic DM for this procedure.
- **Verification evidence:** on physical A059 `00152151K005265`, the current
  FOSS debug build passed adding/removing `motdadb2` from both the seeded
  message-sender sheet and live channel-member sheet, receiving its fresh DM,
  opening the collapsed Fools section and DM row, expanding its channel
  placeholder, and reopening the DM through the sender’s Message action. The
  debug PID remained alive and the crash buffer stayed empty after every fixed
  trigger. The same run’s A-C baseline passed 108 checks with zero failures.

## E. Message interaction, layout, and formatting

### E1. Swipe to reply

- **Priority / size / status:** P2, M, Complete (2026-07-14).
- **Depends on:** E3 should land first so gesture work targets the stable row.
- **Implementation:** drag toward the reply direction (right in LTR, left in
  RTL) with horizontal touch slop, resistance, a maximum visual offset around
  `80.dp`, one haptic at the threshold, reply icon feedback, and snap-back on
  release/cancel. Exclude system and fool-placeholder rows. Respect the Android
  system-edge exclusion zone. Crossing the threshold invokes existing
  `setReply` exactly once.
- **Accessibility:** expose a semantic **Reply** action that reaches the same
  behavior without a gesture.
- **Failure behavior:** vertical scrolling, link taps, selection/long press,
  and system back gestures win when their intent is established; cancelled
  drags do not alter composer state.
- **Acceptance / tests:** below/above threshold, cancellation, vertical
  arbitration, LTR/RTL, edge gesture, one haptic/one invocation, exclusions,
  accessibility action, and existing reply state rendering.
- **Implementation evidence:** one shared wrapper now fronts every ordinary
  message density. It waits for reply-direction horizontal touch slop before
  consuming, leaves vertical scroll and existing link/long-press handlers in
  control until then, applies resistance with a 56-dp threshold and 80-dp cap,
  reveals a directional reply icon, haptics once, commits only a completed
  armed drag, and springs back on release/cancel. LTR drags right, RTL drags
  left, the platform system-gesture inset is excluded, and TalkBack receives a
  localized custom Reply action through the same callback. System runs and
  collapsed fool placeholders bypass the wrapper.
- **Verification evidence:** focused pure tests cover resisted/capped LTR and
  RTL geometry, threshold boundaries, cancellation versus completion, one-shot
  haptic gating, and physical edge exclusion. The complete FOSS debug and
  release unit suites, warnings-as-errors lint, and both FOSS APK assemblies
  pass. On physical A059 `00152151K005265`, a 500-pixel rightward drag on an
  ordinary self message produced `Replying to motdadb` with the correct preview
  and exactly one new app-owned threshold-haptic request in
  `dumpsys vibrator_manager` (the device setting suppressed playback). A
  below-threshold drag and a vertical drag produced no reply state, vertical
  scrolling exposed the bottom FAB, and a same-row long press still opened
  Reply/Copy/Quote/More reactions. The crash buffer remained empty.

### E2. UI and conversation font-size sliders

- **Priority / size / status:** P2, L, Implemented (2026-07-13); physical
  min/max accessibility verification pending.
- **Depends on:** E3 stable layout recommended first.
- **Implementation:** add separate UI and Conversation scales from 80% through
  140% in 5% steps, default/reset 100%. Multiply rather than replace Android's
  system font scale. App chrome and settings use UI scale. Timeline message
  text, system pills, reply previews, composer, nick/emoji autocomplete, and
  rich inline text use Conversation scale. Icons and fixed geometry do not
  scale through this preference.
- **Failure behavior:** clamp corrupt persisted values to the nearest allowed
  step; maximum app plus system font size may reflow but must not clip controls
  or make primary actions unreachable.
- **Acceptance / tests:** persistence, defaults/reset, clamping/rounding,
  independent theme derivation, all listed surfaces, settings semantics, and
  screenshot/Compose checks at min/max plus large Android font scale.
- **Implementation evidence:** `AppearancePrefs` now persists independent UI
  and Conversation percentages, normalizing corrupt/input values to 5% steps
  from 80–140% with a 100% default. Two labeled, discrete sliders in Chat
  layout show the live percentage and expose Reset to 100%. The app theme
  scales all Material typography tokens for chrome/settings while leaving dp
  geometry and icons unchanged. Chat installs a nested conversation typography
  derived from the unscaled base—not the UI-scaled theme—across the timeline,
  system pills, rich/reply content, autocomplete, and composer; explicit
  timeline timestamps use the same conversation factor. Android's `sp`
  conversion still applies the system font scale afterward.
- **Verification evidence:** focused tests cover defaults, independent
  persistence, clamping/nearest-step rounding, min/max factors, and independent
  base-token derivation. Min/max previews include 140% app text combined with
  1.5x Android font scale for both settings and conversation reflow. The full
  FOSS debug/release unit suites, warnings-as-errors lint, debug APK, and FOSS
  release APK pass; the first combined run lost its Gradle daemon only during
  final packaging, and an isolated incremental `assembleFossRelease` completed.
  Physical control reachability at both extremes remains pending because the
  device is disconnected.

### E3. Fix two-line multiline indentation

- **Priority / size / status:** P1, S/M, Complete (2026-07-13).
- **Depends on:** none.
- **Implementation:** in two-line density, every message body begins under the
  nick's text column after the reserved avatar gap, including the first sender
  row and grouped continuations. Apply the same body column to reply preview,
  plain/rich text, image, link preview, and reactions. Do not special-case only
  `showSender == false`.
- **Failure behavior:** avatar-less/system layouts retain their own alignment;
  narrow widths wrap within the body column without negative or doubled inset.
- **Acceptance / tests:** narrow-width previews and Compose geometry assertions
  for first/grouped rows, one-line/wrapped text, reply, image/card, reactions,
  and every layout density affected by the shared component.
- **Implementation evidence:** TWO_LINE now uses one body-column token—avatar
  width plus the header's six-dp gap—for both first and grouped messages. The
  reply preview, notice label, plain/rich body, image, link card, and reactions
  all live inside that single tagged column; compact and comfortable renderers
  are unchanged.
- **Verification evidence:** the narrow 280-dp preview includes a wrapping
  first row, grouped continuation, reply, image, card, and reactions. A focused
  token test locks the body start to the 20-dp TWO_LINE avatar plus six-dp gap,
  and the FOSS suites compile every density route.

### E4. Inline backtick and Emacs-style code spans

- **Priority / size / status:** P2, M, Complete.
- **Depends on:** E3 for two-line rendering coverage.
- **Syntax:** paired backticks (`` `foo` ``) and Emacs-style backtick/apostrophe
  (`` `foo' ``) render identically as inline code. When both possible closing
  delimiters occur, the next backtick wins over an apostrophe. Unmatched,
  doubled, and triple backticks remain literal. Fenced code blocks are out of
  scope.
- **Implementation:** extend the shared annotated-text parser used by
  comfortable, compact, and two-line rows. Parse code spans before link and
  mention annotations; link/mention detection applies only outside code. Copy
  semantics preserve the original plain text and delimiters according to the
  existing message-copy behavior. Add no Markdown dependency.
- **Failure behavior:** malformed input renders literally and cannot swallow
  the rest of a message; Unicode and IRC ACTION/NOTICE bodies are safe.
- **Acceptance / tests:** both delimiter styles, precedence, multiple spans,
  unmatched/double/triple literals, Unicode, links/mentions inside and outside,
  ACTION/NOTICE, all densities, and copy semantics.
- **Implementation evidence:** a dependency-free shared segment parser handles
  single-backtick and Emacs-style pairs before rich-text annotation. Comfortable,
  compact, and two-line rows use the same renderer; code spans use the monospace
  family with a theme-derived surface treatment, suppress URL/mention behavior,
  and leave the stored raw message untouched for copy actions and logs.
- **Verification evidence:** focused parser, URL discovery, comfortable-body,
  and compact-line tests cover delimiter parity and precedence, multiple spans,
  malformed/doubled/triple input, Unicode, inert links/mentions, and
  PRIVMSG/ACTION/NOTICE routes. FOSS debug/release unit tests, debug lint, and
  debug/release assemblies pass; release assembly was rerun alone after the
  combined Gradle daemon was killed during dex packaging and then passed.

## F. Identity and onboarding expansion

### F1. Interoperable URL-based user avatars

- **Priority / size / status:** P2, L, Implemented; physical interoperability
  verification pending.
- **Depends on:** D2 request gating.
- **Protocol/product scope:** use IRCv3 work-in-progress `draft/metadata-2` user
  metadata for an avatar HTTPS URL, optionally containing `{size}`. Label the
  feature experimental. Do not upload images. Notifications and channel
  avatars are out of scope.
- **Settings and state:**
  - Store per-network self state as `Unmanaged`, `Set(url)`, or
    `ExplicitlyCleared`, so absence is distinct from publishing a removal.
  - `showSharedAvatars` defaults on. Turning it off is a full receive opt-out:
    unsubscribe where supported, cancel/no-op HTTP work, and clear stored
    remote URL records. It does not silently change the separate self-publish
    setting.
  - `showImages=false` displays monograms for every user, including self, and
    performs no avatar HTTP fetch.
- **Persistence:** use a separate app-owned Room database/table for remote URL
  metadata only, keyed by network and stable account-or-normalized-nick
  identity with update time. Do not change `UserEntity` or the main frozen
  database contract. Store no fetched bitmap as identity source.
- **Implementation:** handle capability negotiation, subscribe/snapshot SYNC,
  updates/removals, delayed batches, rate limits, unsupported networks, nick /
  account identity changes, and self publish/clear. Validate `https`, expand
  `{size}` with the requested display size, and use existing image-loading
  safety/caching boundaries.
- **Surfaces:** use shared avatars in all existing user-avatar UI: message
  rows, composer/nick autocomplete, nick sheets/profile, member lists,
  friends/fools management, and DM/chat-list user rows. Fall back to current
  deterministic monograms.
- **Failure behavior:** unsupported networks keep local configuration and show
  publish-unavailable state; invalid/non-HTTPS URLs never fetch; failed images
  fall back without clearing valid metadata; opt-out leaves no remote records.
- **Acceptance / tests:** metadata subscribe/publish/remove/SYNC and rate-limit
  fixtures; persistence/cleanup; `{size}` and URL validation; account/nick
  resolution; D2 and opt-out no-request guarantees; all surfaces; large-roster
  request/performance bounds.
- **Reference:** [IRCv3 metadata draft](https://ircv3.net/specs/extensions/metadata)
  and [avatar metadata registry entry](https://ircv3.net/registry#user-metadata).
- **Implementation evidence:** `draft/metadata-2` is negotiated only with its
  required `batch` capability. A connection-generation-scoped coordinator
  subscribes/unsubscribes `avatar`, consumes live and snapshot metadata,
  handles removals and delayed SYNC, follows nick/account identity changes,
  and reapplies per-network publish/clear intent after reconnect. Remote HTTPS
  URLs live in a separate `avatars.db`; disabling shared avatars unsubscribes
  and clears it, while the independent image gate prevents Coil from receiving
  a model. One indexed composition-local resolver supplies every existing
  `Avatar` surface and preserves deterministic monograms underneath failures.
  Network settings label publishing experimental and retain unsupported-server
  intent; Chat settings provide a full receive opt-out. Capability-value limits
  (`max-subs`, `max-keys`, and `max-value-bytes`) distinguish receive-only
  servers from publish-capable ones, survive both initial and runtime CAP
  negotiation, and gate the settings action without racing the initial network
  lookup.
- **Verification evidence:** pure tests lock capability dependency, exact
  SUB/SYNC/SET/remove framing, notification/snapshot/removal/delayed-sync
  parsing, HTTPS and `{size}` validation, and three-state self persistence.
  Room tests cover invalid-value rejection, account/nick rekeying, per-network
  cleanup, and full opt-out cleanup; resolver tests prove network isolation and
  the disabled no-model boundary. Runtime-cap tests preserve advertised limits
  across a value-less ACK and cover a newly advertised capability whose `batch`
  dependency is already active; settings tests cover receive-only publishing
  and late-load/live-state ordering. All IRC tests, FOSS debug/release unit
  tests, FOSS debug lint, and FOSS release assembly pass. A publish-capable
  server advertising the draft capability and a physical device are still
  required for the manual publish/fallback/request trace.

### F2. Common IRC network presets

- **Priority / size / status:** P2, M, Complete (2026-07-13).
- **Depends on:** none; D3 depends on it.
- **Implementation:** keep a compile-time catalog outside composables. Selecting
  a preset fills the existing editable server form, preserves the user's IRC
  identity fields, and clears server-specific authentication values that must
  not leak to another network. The fields remain visible/editable, and Custom
  remains first-class. Any direct plaintext connection requires an explicit
  warning before save/connect.

| Preset | Host | Port | Transport | Source |
|---|---|---:|---|---|
| Libera.Chat | `irc.libera.chat` | 6697 | TLS | [Connect guide](https://libera.chat/guides/connect) |
| OFTC | `irc.oftc.net` | 6697 | TLS | [Network site](https://www.oftc.net/) |
| EFnet | `irc.efnet.org` | 6697 | TLS | [Server list](https://www.efnet.org/?module=servers) |
| IRCnet | `irc.ircnet.ca` | 6697 | TLS, specific server | [Server list](https://www.ircnet.com/servers) |
| DALnet | `irc.dal.net` | 6697 | TLS | [Connection guide](https://www.dal.net/kb/view.php?kb=178) |
| Rizon | `irc.rizon.net` | 6697 | TLS | [Network site](https://www.rizon.net/) |
| Snoonet | `irc.snoonet.org` | 6697 | TLS | [Connect guide](https://snoonet.org/help/) |
| QuakeNet | `irc.quakenet.org` | 6667 | Plaintext warning | [Connect guide](https://www.quakenet.org/help/general/connecting-to-quakenet) |
| Undernet | `irc.undernet.org` | 6667 | Plaintext warning | [Server list](https://www.undernet.org/servers.php) |

- **Presentation:** show the seven TLS presets first, group QuakeNet and
  Undernet under **Legacy unencrypted**, and place Custom as an equally visible
  path rather than an obscure final option.
- **Failure behavior:** presets are convenience defaults, not locked policy;
  host/port remain editable. Do not infer Libera enrollment after the user
  changes a preset host or starts from Custom.
- **Acceptance / tests:** exact catalog values and ordering; preset-to-form
  mapping; identity preservation; auth clearing; editing into Custom state;
  plaintext warning; D3 eligibility only for a newly saved unchanged Libera
  preset.
- **Implementation evidence:** the add-network flow now reads a compile-time
  nine-network catalog, presents Custom first, groups seven TLS choices before
  two legacy unencrypted choices, and leaves the populated form editable.
  Applying a preset changes only host/port/TLS, preserves nick/username/real
  name, clears SASL/certificate state, and retains explicit preset identity
  only while its endpoint remains unchanged. Every direct plaintext endpoint,
  including Custom, must pass an explicit warning before a row is created.
- **Verification evidence:** focused FOSS tests lock every host, port, transport,
  and ordering entry; identity/auth mapping; identity-only edits; transition to
  Custom on endpoint edits; and the no-row-before-confirmation plaintext path.
  The Libera preset identity remains available through successful row creation
  for D3's one-shot enrollment handoff.

## G. Soju bouncer administration

### G1. Guided BouncerServ control center and command console

- **Priority / size / status:** P2, L, Complete (2026-07-13).
- **Depends on:** use C1's connection-generation and cancellation model. The
  UI, builders, and parsers may proceed earlier. F2 presets may enhance network
  creation later but are not a dependency.
- **Product scope:** expand the existing **Bouncer networks** screen into a
  **Soju control center** for `BOUNCER_ROOT` connections. It is a soju-specific
  tool, not a generic IRC service-bot client. Keep the existing machine-readable
  `soju.im/bouncer-networks` listing and local import switches as the source of
  network identity/state.
- **Screen structure:**
  - **Networks:** retain import visibility and add full create/update/delete
    controls for address, name, nick, username, real name, auto-away, enabled,
    password, and advanced connect commands. Network updates send only fields
    the user changed so unknown server-side values are never overwritten.
  - **Channels:** list configured channels and offer create, attach/detach,
    detached-message relay, reattach, auto-detach duration/filter, and delete.
    Status uses `channel status -network <network>`. From an unbound root,
    create/update/delete identify the target as `<channel>/<network>`; do not
    send the unsupported `-network` flag for those mutations.
  - **Account:** update the current user's fallback nick/real name; show SASL
    status; set/reset SASL PLAIN; generate and display CertFP fingerprints.
    SASL and CertFP operations always pass `-network <network>` from the root.
  - **Admin:** when authorized, expose user status/create/update/delete/run and
    server status/notice/debug. Enforce soju's own-user versus other-user field
    restrictions. User deletion requires typed-username confirmation followed
    by the confirmation token returned by soju. Self-deletion warns that the
    root will disconnect and leaves cached chats/local configuration intact.
    Enabling debug requires a prominent warning that server logs may contain
    credentials or message contents.
  - **Console:** accept exactly one command per submission, provide command-path
    completion from probed help, and display the persistent BouncerServ query
    transcript. Reject CR/LF rather than silently sending multiple commands.
- **Capability discovery:** after each root transition to Ready, and on manual
  retry, send `help` plus help for advertised command families. Parse the
  `available commands:` paths only for feature availability, not as a state
  schema. The help list also determines administrator access because soju omits
  unauthorized command paths. If probing fails or a later command reports a
  permission/version error, disable the affected guided panel, re-probe, and
  leave the raw console available with an unverified warning.
- **Command execution:** add an app-owned `BouncerServClient` over the live root
  `IrcClient`. It serializes commands because BouncerServ has no labeled
  completion frame. Collect BouncerServ `PRIVMSG` replies only; unsolicited
  `NOTICE` relays/broadcasts must not complete a command. Use a five-second
  first-reply timeout, a 400 ms quiet window after the newest reply, and a
  15-second hard limit. Cancel and discard work from obsolete connection
  generations. A late reply still appears in the transcript even when the UI
  operation already timed out.
- **Transcript and routing:** use the root connection's existing BouncerServ
  query as the control-center transcript. Persist replies locally because soju
  does not provide BouncerServ CHATHISTORY. Keep child-network BouncerServ chats
  and relay notices scoped to their existing child connections; do not migrate
  or consolidate them. Solicited BouncerServ `PRIVMSG` output is persisted but
  never notifies or counts unread. Unsolicited BouncerServ `NOTICE` traffic
  retains normal unread and notification behavior.
- **Secret handling:** apply one pure BouncerServ redactor to guided actions,
  the console, and ordinary `/msg BouncerServ` sends. The real command exists
  only long enough to send on the wire; Room stores a safe display form and
  echo dedup compares that same redacted text. Always hide SASL/user/network
  passwords, connect commands, network quotes, server-notice bodies, and all
  arguments of unknown console commands. Potentially sensitive error details
  may be shown ephemerally but persist only as a generic failure. Never put raw
  secrets in logs, saved state, retry records, clipboard suggestions, or
  accessibility semantics; clear password fields on submit and dismiss.
- **Failure behavior:** a disconnected root leaves the cached transcript
  readable and disables mutations behind the existing connect card. Parsing
  drift shows raw non-sensitive output instead of crashing or inventing state.
  Network/channel/user deletes require confirmation and refresh their
  machine-readable listings after success. Server debug is presented as
  explicit Enable/Disable actions, not a switch that pretends its current state
  is known.
- **Acceptance:** a user can manage their networks, channels, upstream
  authentication, and identity without memorizing BouncerServ syntax; an admin
  can manage users and server actions; unsupported versions degrade to the
  console; no secret reaches durable app state; command replies and detached
  notices retain their distinct notification behavior.
- **Focused tests:** POSIX argument quoting, exact command strings (especially
  root channel syntax), help/admin discovery, status parsing/fallback,
  redaction, multiline rejection, queue/timeout/quiet-window behavior,
  interleaved NOTICE, stale-generation cancellation, late replies, Room/log
  secret absence, unread/notification classification, Compose panels and
  confirmations. Extend the pinned soju v0.10.1 stack with admin and non-admin
  users and exercise temporary network/channel/user mutations, SASL/CertFP,
  notice, and debug enable/disable cleanup on a physical device.
- **Implementation evidence:** the existing bouncer-network manager is now a
  five-panel Soju control center with server-verified Networks, Channels,
  Account, Admin, and Console actions. An app-owned serialized client captures
  only BouncerServ `PRIVMSG` replies, honors connection generations and quiet /
  hard timeouts, and leaves cached Room transcript output readable offline.
  Exact builders enforce Soju's root channel and user-update rules; one shared
  redactor protects guided, console, and ordinary BouncerServ sends. The UI
  waits for advertised help paths before enabling mutations and preserves raw
  console fallback when capabilities drift. Initialization uses atomic state
  updates so a late root database read cannot overwrite a live Ready state.
- **Verification evidence:** focused command, redaction, parser, client timing,
  notification classification, model, and initialization-race tests pass. The
  pinned native Soju 0.10.1 stack passed admin and non-admin `control-check`,
  including temporary network/channel/user operations, SASL, CertFP, notice,
  and debug cleanup. On physical device `00152151K005265`, the FOSS debug APK's
  focused Phase J passed 30/30 checks: reconnect gating, capability discovery,
  create confirmation, all five panels, Admin visibility, safe console send,
  and returned notice. The complete FOSS debug/release unit matrix, warnings-as-
  errors debug lint, IRC build, and FOSS release assembly pass; no Google/FCM
  task or APK is part of verification or release.
- **References:** [soju BouncerServ manual](https://soju.im/doc/soju.1.html)
  and [pinned v0.10.1 service implementation](https://github.com/emersion/soju/blob/v0.10.1/service.go).

## Delivery order

1. Run B3 baseline instrumentation and physical-device matrix.
2. Implement A1 and A2 together; verify live and replay interoperability.
3. Implement B2, then B1; repeat B3 device verification after the policy lands.
4. Build the native ZNC fixture, then implement C1 and C2. Use its degradation
   coverage for Cloak; perform no separate Cloak work.
5. Implement D1 and D2 and verify D4.
6. Implement F2, then D3.
7. Implement E3, E1, E2, and E4 in that order.
8. Implement F1 after preview/image gating is established.
9. Implement G1 after C1 establishes connection-generation semantics; its pure
   command, help, and redaction work may run alongside the bounded UX batch.

Use separate commits for independently reviewable entries unless an entry says
it is coupled. Do not bundle a release into an implementation task unless the
user explicitly requests it.

## Original-report traceability

| # | Original report | Implementation entry |
|---:|---|---|
| 1 | Add a MOTD channel on Libera connection for first timers | D3 |
| 2 | Crash after adding a fool from the nick menu | D4 |
| 3 | Minimized fool rows are not considered seen and break saved place | B1 |
| 4 | Reactions do not sync to other users | A1 |
| 5 | Replies do not render or notify other users | A2 |
| 6 | Hidden JPQ and fool rows affect previews and entry/bottom behavior | B1 |
| 7 | Investigate seamless connection loss/recovery and history effects | C1 |
| 8 | Investigate correct scroll-to-bottom behavior used by other chat apps | B3 |
| 9 | Never show join/part in chat-list previews | B2 |
| 10 | Add image/link preview controls | D2 |
| 11 | Add local ZNC coverage and assume Cloak compatibility | C3 |
| 12 | Force-fetch or revalidate history on reconnect | C2 |
| 13 | Remove read-receipt/send races from auto-follow UX | B3, then B1 |
| 14 | Add a clear-search X | D1 |
| 15 | Add configurable user avatars | F1 |
| 16 | Swipe to reply | E1 |
| 17 | UI and chat font-size sliders | E2 |
| 18 | Fix two-line multiline indentation | E3 |
| 19 | Backtick and Emacs-style inline code | E4 |
| 20 | Add common IRC networks for easier configuration | F2 |
| 21 | Add a client/tool for interacting with soju BouncerServ | G1 |

## Definition of done

- Every original report maps to A1-A2, B1-B3, C1-C3, D1-D4, E1-E4, F1-F2, or G1;
  no report remains an unresolved product question.
- A failing focused test or deterministic reproduction precedes bug fixes when
  practical.
- Protocol changes cover serializer/parser, live two-client behavior, push,
  and history replay.
- Hidden-content tests cover preview, order, counts, search, entry, saved
  position, follow, and read state as one matrix.
- Connection changes cover cancellation, stale generations, capability absence,
  deduplication, and current error presentation.
- UI changes include localized accessibility labels and Compose behavior tests.
- Run the focused module tests and FOSS lint/assembly tasks when UI/resources
  change, plus the FOSS release-parity command documented in `.agents/testing.md`.
  The unfinished Google/FCM flavor is dormant and is not a build or release gate.
- Device-only lifecycle, scrolling, or performance claims are verified on a
  physical device and recorded with the tested commit/build and relevant
  `adb dumpsys gfxinfo` sample.

## Document verification checklist

- Run `git diff --check`.
- Confirm every repository path named here exists or is explicitly a proposed
  path/interface.
- Check every Markdown link and re-verify mutable network endpoints immediately
  before implementing or releasing F2.
- Confirm the document-only change does not edit frozen contracts, app code,
  dependencies, or release state.

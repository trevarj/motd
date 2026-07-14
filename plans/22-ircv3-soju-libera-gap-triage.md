# IRCv3 gap triage: soju and Libera.Chat

Date: 2026-07-14
Baseline: `main` at `8e68f8b`
Sources reviewed: current MOTD source, the IRCv3 client support table, IRCv3
specifications, and Libera.Chat's 2026 feature announcement

This document turns the IRCv3 support audit into implementation-sized backlog
items. It is a triage snapshot, not a replacement for current source,
`AGENTS.md`, `ARCHITECTURE.md`, `.agents/testing.md`, or the E2E runbook. Before
implementing an item, re-check the server capability names and draft revisions;
the IRCv3 support table warns that it may lag current releases, and draft
extensions can change incompatibly.

Estimates are relative (`S`, `M`, `L`) and include focused tests but not a
release cycle. Unless stated otherwise, an item must degrade cleanly on direct
servers, soju, and ZNC when its capability or ISUPPORT token is absent.

## Executive triage

|ID|Finding                                     |Surface             |Priority|Size|Status                    |
|--|--------------------------------------------|--------------------|--------|----|--------------------------|
|I1|Incoming `INVITE` events are discarded      |soju + Libera       |P0      |L   |Complete (2026-07-14)     |
|P1|`MONITOR` and `extended-monitor` presence   |soju + Libera       |P1      |L   |Complete (2026-07-14)     |
|U1|WHOX member enrichment                      |soju + Libera       |P1      |L   |Complete (2026-07-14)     |
|R1|`draft/message-redaction`                   |soju                |P1      |L   |Draft-version spike first |
|B1|Netsplit/netjoin batch presentation         |soju + Libera       |P2      |L   |Complete (2026-07-14)     |
|U2|`userhost-in-names` data is discarded       |direct Libera       |P2      |S   |Complete (2026-07-14)     |
|N1|Lazy member loading with `no-implicit-names`|soju + Libera       |P2      |L   |Complete (2026-07-14)     |
|A1|`draft/pre-away` background presence        |soju                |P2      |M   |Product decision required |
|M1|Bot-mode presentation                       |soju; planned Libera|P3      |M   |Deferred                  |
|M2|Account-extban moderation affordance        |soju + Libera       |P3      |M   |Deferred                  |
|M3|`UTF8ONLY` bookkeeping                      |soju + Libera       |P3      |S   |Deferred                  |
|M4|`draft/multiline` logical messages          |planned Libera      |P3      |L   |Watch server deployment   |

The combined Ready goal is **XL**. Its delivery order is I1, the shared
U2/WHOX foundation, N1, P1, then B1. This order establishes durable event and
identity contracts before features that consume them, and keeps membership
snapshot correctness ahead of its lazy-loading optimization.

The goal is complete when I1, P1, U1, U2, N1, and B1 meet all contracts and
verification gates below. R1, A1, and M1-M4 remain explicitly outside this
goal; implementing the Ready set must not silently negotiate or partially
expose those deferred features.

## Ground rules

- `:irc` owns wire commands, capability/ISUPPORT interpretation, numerics, and
  pure events. It remains a pure JVM module.
- `EventProcessor` remains the sole writer of IRC-derived Room state.
- `ConnectionManager` owns subscriptions and connection-lifecycle actions.
- ViewModels expose app state and side effects; Compose screens remain
  stateless where practical and receive stable semantics for automated actions.
- Do not request a capability merely to claim it. Every requested capability
  must have a tested behavior or a documented no-op semantic.
- Preserve serialized preferences and Room migrations. New durable fields need
  a migration and replay/idempotence tests.
- Validate direct and soju behavior with `test/e2e/local-stack.sh`. Use
  `test/e2e/znc-stack.sh` only to prove ZNC compatibility and documented
  degradation; it is not the soju fixture.
- Direct Libera.Chat is useful for a final non-destructive compatibility check,
  but automated tests must not depend on the live network.

## Shared implementation contracts for the Ready goal

These contracts are part of the goal, not optional refactoring. They prevent
six protocol features from growing incompatible one-off persistence and
connection-state paths.

### Durable timeline events

- Extend `MessageKind` with `INVITE`, `NETSPLIT`, and `NETJOIN`.
- Add nullable `eventKey`, versioned `eventPayload`, and `inviteState` columns to
  `MessageEntity`. Ordinary messages leave them null. Typed events use:
  - `InvitePayloadV1(inviter, target, channel)`;
  - `NetworkBatchPayloadV1(serverA, serverB, nicks)`.
- Add `InviteState`: `PENDING`, `JOINING`, `JOINED`, `DISMISSED`, `FAILED`, and
  `HISTORICAL`. Only a valid self-invite in `PENDING`, `JOINING`, or retryable
  `FAILED` is actionable.
- Decode payloads defensively. An unknown version, missing field, or malformed
  payload renders as safe system text instead of crashing or inventing an
  action.
- Add an additive Room 5-to-6 migration. Preserve existing messages and FTS
  behavior; include migration, downgrade-boundary documentation, replay, and
  idempotence tests.
- Centralize typed timeline insertion and membership mutation in
  `EventProcessor` helpers so invites, snapshots, and network batches retain
  its sole-writer invariant.

### Ephemeral presence and roster state

- Expose cached `UserEntity` details through an observable repository path so
  query rows and nick sheets do not depend on one-off WHOIS responses.
- Model connection-scoped presence as `UNKNOWN`, `ONLINE`, or `OFFLINE`.
  Presence is ephemeral and keyed by connection/network plus normalized nick;
  it is not written to Room.
- Model each joined channel's roster as `NOT_LOADED`, `LOADING`, `LOADED`, or
  `FAILED`. Room remains the durable last-known roster. When the connection is
  not authoritative, UI may show it only with an explicit stale label.
- Never infer `OFFLINE` from away state, a stale roster, a disconnect, or an
  unreturned WHO/WHOX row.

### Public connection boundaries

Add app-facing operations with implementations owned by `ConnectionManager`:

- `acceptInvite(messageId)`;
- `dismissInvite(messageId)`;
- `requestMembers(bufferId, force)`.

Add an invitation-specific entry point to `MessageNotifier`; notification
receivers and Compose code call these boundaries rather than writing Room or
sending wire commands directly.

### Pure protocol events and shared parsers

- Add typed `IrcEvent` variants for MONITOR snapshots/deltas, WHOX rows and
  completion, and preserved netsplit/netjoin batches.
- Change `IrcEvent.Names.Member` to retain `username` and `host`; do not misuse
  its existing account field for `userhost-in-names`.
- Use one IRC prefix parser for JOIN, NAMES, WHOX, and monitor hostmasks. It must
  preserve valid partial data and reject malformed identity without corrupting
  the normalized nick key.
- Keep capability aliases and ISUPPORT interpretation in `:irc`. App code
  consumes typed capability/state decisions, not raw string comparisons.

## I. Invitations

### I1. Incoming invitations must be visible and actionable

- **Priority / size / status:** P0, L, Ready / included.
- **Applies to:** direct Libera.Chat, soju, and any server sending ordinary
  `INVITE` or `invite-notify` events.
- **Evidence:** `CapTiers.TIER2` requests `invite-notify`; `EventMapper` maps
  `INVITE` to `IrcEvent.Invited`; `EventProcessor` explicitly discards that
  event. `PushEventHandler` currently maps only `PRIVMSG`, `NOTICE`, and
  `TAGMSG`, although soju web push can deliver an `INVITE` message.

#### Persistence and placement

1. A syntactically valid invite targeting the current user creates or reuses
   an unjoined `CHANNEL` buffer for the invited channel and inserts one durable
   `INVITE` row there. It must not be hidden in the server buffer merely because
   the channel has not been joined yet.
2. The pending row is a large actionable card. It contributes a chat-list
   preview and activity timestamp, but never unread count.
3. A third-party `invite-notify` event goes to its existing channel buffer. If
   that buffer does not exist, it goes to the server buffer. It is informational
   and never posts an Android notification.
4. Use the server `msgid` as `eventKey` when present. Otherwise hash connection
   identity, normalized inviter, target, channel, and a 30-second server-time
   bucket. Socket delivery, push delivery, and replay must converge on the same
   row and notification identity.
5. Historical playback inserts `HISTORICAL`, never posts a notification, and
   never exposes Join or Dismiss. It must not convert a newer resolved row back
   into pending state.

#### Push and notification actions

- Extend `PushEventHandler` to parse pushed `INVITE` commands through the same
  validation and dedup path as socket events.
- Post self-invites on a dedicated **Invitations** notification channel. Tapping
  notification content opens the invitation card. Actions call
  `acceptInvite(messageId)` and `dismissInvite(messageId)`.
- Join foregrounds/starts the network connection if necessary, waits at most
  30 seconds for `Ready`, re-checks the stored invitation state, applies the
  normal channel key/configuration if one exists, and sends exactly one `JOIN`.
  Timeout does not queue a surprise later join.
- Dismiss and notification swipe both set `DISMISSED`. Distinct later invites
  remain eligible for notification.

#### State transitions and failure behavior

- `PENDING -> JOINING` is an atomic compare-and-set before any wire command.
  Repeated action delivery while `JOINING` is a no-op.
- A matching self `JOIN` echo sets `JOINED`. A server rejection, send failure,
  disconnect during the attempt, or 30-second Ready timeout sets retryable
  `FAILED` with a user-visible reason.
- Reaching `JOINED` or `DISMISSED` immediately cancels the notification and
  collapses the large card to a compact **Joined** or **Dismissed** pill.
  `HISTORICAL` is always compact. There is no removal timer; the audit row
  remains until the buffer is deleted normally.
- An invalid channel or unusable target is stored as informational safe text,
  with no actions. An unknown but syntactically valid channel is not an error;
  it receives the unjoined channel buffer described above.

#### Acceptance and focused verification

- Self-invites received by socket, push, or both appear exactly once in the
  invited channel and are actionable; third-party notices appear once without
  a notification.
- Join sends exactly one wire command across repeated taps/receivers, handles
  offline-to-Ready success, and becomes retryable without delayed side effects
  after timeout. Dismiss never joins.
- Tests cover mapper and push parsing, placement, `msgid` and fallback dedup,
  historical playback, all state transitions, process recreation, notification
  tap/Join/Dismiss/swipe, JOIN echo and rejection, preview/unread policy, and
  local direct/soju two-client delivery.
- **References:** [IRCv3 invite-notify](https://ircv3.net/specs/extensions/invite-notify),
  [Libera.Chat 2026 rollout](https://libera.chat/news/new-and-upcoming-features-3).

## P. Presence

### P1. Add `MONITOR` and `extended-monitor`

- **Priority / size / status:** P1, L, Ready / included.
- **Applies to:** soju and Solanum/Libera.Chat; degrade on servers without the
  `MONITOR` ISUPPORT token.
- **Evidence:** MOTD has friends, query buffers, and `UserEntity.away`, account,
  hostmask, and realname fields, but no `MONITOR` commands or 730-734 numeric
  handling. Existing AWAY/ACCOUNT/CHGHOST/SETNAME state only stays current for
  users who share a channel.

#### Desired set and limit policy

1. On every `Ready` direct connection and bouncer-child connection, monitor all
   configured global friends plus peers with open query buffers for that
   network. Never issue MONITOR on the bouncer root connection.
2. Normalize/deduplicate with that connection's IRC casemapping. A global
   friend's spelling is not rewritten merely because one server reports a nick
   change.
3. Read the numeric `MONITOR=<limit>` ISUPPORT value. When bounded, select:
   friends first in normalized lexical order, then query peers ordered by pinned
   status, most-recent activity, and normalized nick. State beyond the limit is
   `UNKNOWN`, not `OFFLINE`.
4. On a fresh generation, send `MONITOR C`, chunk `MONITOR +` targets within
   both the server target limit and 400 UTF-8 command bytes, then send
   `MONITOR S`. Within the generation, reconcile changes with `+` and `-`
   diffs rather than clearing the complete list.

#### Protocol and metadata behavior

- Parse 730 through 734 as typed events, including the optional hostmask on 730.
  A 734 keeps already accepted targets, marks rejected targets unknown, and
  inserts one server diagnostic rather than one message per nick.
- Prefer one extended-monitor capability alias, in ratified then draft order:
  `extended-monitor`, `draft/extended-monitor`. Runtime CAP NEW/DEL follows the
  same single-preference rule and triggers a bounded reconciliation.
- Feed monitored `AWAY`, `ACCOUNT`, `CHGHOST`, and `SETNAME` messages through
  the existing metadata events even without a shared channel.
- When 730 marks a nick online, seed missing metadata with one single-flight
  nick WHOX request. Do not poll while notification capabilities keep metadata
  current.
- Re-key ephemeral presence on `NICK` for that connection. Disconnect and
  generation replacement reset all entries to `UNKNOWN`; they do not emit
  false offline transitions.

#### UI, failure behavior, and acceptance

- Query rows expose accessible online/offline/unknown indicators with stable
  semantics/test tags. Nick sheets show presence separately from away state and
  merge it with observable cached identity details.
- Presence changes produce no chat-list activity, unread count, timeline row,
  or Android notification.
- Servers without MONITOR retain current behavior. A malformed limit or numeric
  degrades to the safe unknown state and one diagnostic without reconnect loops.
- Acceptance requires initial 730/731 snapshots and live deltas to update the
  correct identities, extended metadata to remain current without a shared
  channel, friend/query changes and reconnects to converge to one subscription
  set, and root/unsupported connections to issue no MONITOR commands.
- Tests cover command byte/target chunking, casemapping normalization, 730-734
  with and without hostmasks, list-limit priority, friend/query reconciliation,
  reconnect generation races, nick changes, capability alias NEW/DEL, metadata
  WHOX single-flight, UI semantics, and local two-client direct/soju fixtures.
- **References:** [IRCv3 MONITOR](https://ircv3.net/specs/extensions/monitor),
  [IRCv3 extended-monitor](https://ircv3.net/specs/extensions/extended-monitor).

## U. User and member identity

### U1. Populate member identity with WHOX

- **Priority / size / status:** P1, L, Ready / included.
- **Applies to:** soju and Solanum/Libera.Chat through the `WHOX` ISUPPORT token.
- **Evidence:** MOTD preserves the generic ISUPPORT map internally but neither
  issues extended `WHO` nor parses 354. Member identity is therefore assembled
  opportunistically from JOIN notifications and one-off WHOIS requests.

#### Request and correlation contract

- Send `WHO <mask> %tuhnafr,<token>`. The requested fields are token, username,
  host, nick, account, flags, and realname in the canonical WHOX response order.
  Never request the IP field.
- Allocate decimal tokens from 0 through 999; three digits is the maximum.
  Coalesce a duplicate in-flight request for the same normalized mask while
  allowing different masks/channels concurrently.
- Correlate each 354 by token. Complete a request at its matching normalized
  315 end-of-WHO numeric. Cancel it on disconnect/generation change or after a
  15-second timeout; late rows cannot mutate the replacement generation.
- Treat account `0`, `*`, or an absent field as logged out. A malformed or
  partial row updates only fields whose identity and value parsed safely.

#### Ownership and UI behavior

- WHOX enriches `UserEntity.username`, hostmask, account, realname, and away
  state through `EventProcessor`. NAMES plus live MODE events remain authoritative
  for channel membership and prefix modes; WHOX flags must not overwrite
  operator/voice state.
- Nick sheets observe and merge cached `UserEntity` and ephemeral presence with
  a newer one-off WHOIS overlay. Closing a sheet or losing WHOIS does not erase
  the cached WHOX data.
- Unsupported servers retain NAMES, notify events, and WHOIS. Timeout produces
  retryable stale/partial UI, not an empty roster or a reconnect.
- Acceptance requires correct identity enrichment from a correlated snapshot,
  no cross-contamination across concurrent channel/nick requests, safe partial
  rows, correct account placeholders, and proof that no IP is requested or
  stored.
- Tests cover exact serialization and response field order, token wrap and
  exhaustion, request coalescing, normalized 315 matching, concurrent masks,
  timeout/disconnect generations, partial 354 rows, Room convergence, WHOIS UI
  overlay, and direct/soju fixtures.
- **Reference:** [IRCv3 WHOX](https://ircv3.net/specs/extensions/whox).

### U2. Finish or stop negotiating `userhost-in-names`

- **Priority / size / status:** P2, S, Ready / included.
- **Depends on:** the shared prefix parser; coordinate the identity model with
  U1.
- **Applies to:** direct Libera.Chat/Solanum. The current IRCv3 table does not
  list soju's downstream server side as supporting this extension.
- **Evidence:** `EventMapper.accumulateNames` detects `nick!user@host`, strips
  the suffix, and emits only nick/prefixes with a permanently null account.
  `CapTiers` nevertheless requests `userhost-in-names`.
- **Implementation:** parse all advertised prefix characters before the nick,
  then retain username and host in `IrcEvent.Names.Member`. In one
  `EventProcessor` transaction, converge membership/prefixes and the matching
  `UserEntity` hostmask. Do not label userhost as an account; accounts come from
  extended JOIN, account-tag/notify, or WHOX.
- **Precedence:** newer CHGHOST/WHOX data may replace the NAMES snapshot. A
  userhost-free NAMES reply must not erase a known hostmask. Case-normalized
  identity joins the member and user records.
- **Failure behavior:** ordinary prefixed nicks remain valid. Malformed
  `nick!user@host` input preserves a safely parsed nick/prefix entry when
  possible, but never stores a partial value under the wrong identity.
- **Acceptance / tests:** `@nick!user@host` and multi-prefix variants retain all
  prefixes and store `user@host`; ordinary `@nick` remains valid; CHGHOST wins;
  malformed and casemapped entries do not corrupt identity. Completion requires
  consuming the negotiated data; silently discarding it or removing the
  capability is no longer the chosen outcome.

### N1. Add lazy membership loading with `no-implicit-names`

- **Priority / size / status:** P2, L, Ready / included.
- **Depends on:** U1 WHOX request/correlation and the U2/shared NAMES parser.
- **Applies to:** Solanum/Libera.Chat and soju versions advertising the
  capability.
- **Motivation:** the extension avoids the automatic NAMES burst after every
  JOIN and is explicitly intended to improve connection time on mobile clients
  with many channels. MOTD currently needs complete lists for channel info and
  completion, so requesting it without lazy fetches would be a regression.

#### Capability and loading state

- Request exactly one supported alias in this preference order:
  `no-implicit-names`, `draft/no-implicit-names`,
  `soju.im/no-implicit-names`. Runtime CAP NEW/DEL must not enable multiple
  aliases or switch mid-snapshot.
- With a selected alias, self JOIN sets roster state `NOT_LOADED` and does not
  automatically send NAMES or WHOX. Without it, the server's implicit NAMES
  moves state to `LOADING`, 366 moves it to `LOADED`, and WHOX enrichment may
  follow.
- `requestMembers(bufferId, force)` is triggered by channel info, member
  completion, and a moderation nick/member surface. It sends one explicit NAMES
  and correlated WHOX refresh; concurrent callers share the same request.
- A loaded roster is cached for the connection. `force` starts a refresh while
  continuing to display the prior roster as stale.

#### Race-safe snapshot convergence

- From explicit NAMES request through 366, journal live JOIN, PART, KICK, QUIT,
  NICK, and membership MODE deltas for that channel. On 366, atomically replace
  the base membership snapshot and apply ordered deltas in one transaction.
- Journaled membership mutations must not also create duplicate timeline rows.
  Ordinary event presentation remains exactly once.
- Parse server `PREFIX` and `CHANMODES` rather than hard-coding `@`/`+` or MODE
  parameter rules. NAMES and live MODE are authoritative for member prefixes.
- A 15-second NAMES timeout sets `FAILED` and permits retry. Cached Room members
  remain visible only as explicitly stale; channel info must not translate
  `NOT_LOADED`, `LOADING`, or `FAILED` into a current zero-member count or “no
  members.”
- Reconnect, self-PART/KICK, capability-generation change, or a detected resync
  invalidates authority. Self-PART/KICK also clears all durable channel members;
  reconnect may retain the last Room snapshot solely as stale data.

#### Acceptance and focused verification

- Reconnecting many joined channels with the capability downloads no complete
  rosters until a defined trigger fires. Each trigger is single-flight and
  returns a complete WHOX-enriched roster.
- JOIN/PART/KICK/QUIT/NICK/MODE races before 366 converge without lost members,
  resurrected members, wrong prefixes, or duplicate timeline items.
- Unsupported and CAP-loss paths retain safe implicit-NAMES behavior. Timeout,
  reconnect, and stale-cache UI remain retryable and never claim an empty
  authoritative roster.
- Tests cover all aliases and preference, lazy triggers, forced refresh,
  journaling order, PREFIX/CHANMODES variants, timeout/retry, invalidation,
  stale UI/completion semantics, unsupported degradation, the local fixtures,
  and an on-device high-channel/high-membership connection check.
- **Reference:** [IRCv3 no-implicit-names](https://ircv3.net/specs/extensions/no-implicit-names).

## R. Message mutation

### R1. Implement soju message redaction with an audit indication

- **Priority / size / status:** P1, L, draft-version spike first.
- **Applies to:** soju when `draft/message-redaction` is advertised. Libera.Chat
  did not list upstream redaction as deployed in the reviewed announcement.
- **Dependency:** existing msgid identity, echo-message, CHATHISTORY, and message
  action sheet. No new networking stack is needed.
- **Version spike:** capture the exact capability and wire behavior from the
  pinned/local soju fixture before editing production code. The IRCv3 document
  is work-in-progress and warns against assuming draft stability.
- **Product behavior:**
  1. Negotiate only the compatible draft capability proven by the fixture.
  2. Offer Delete on messages with a stable msgid when connected and capable.
     Allow the server to decide authorization; do not assume only self messages
     are redactable because moderators may have permission.
  3. Send `REDACT <target> <msgid> [reason]` and handle incoming live and
     CHATHISTORY `REDACT` commands idempotently.
  4. Replace primary content with a redacted tombstone and retain a visible
     audit indication (actor, time, optional reason where supplied). Do not
     promise secure deletion; recipients may already have copied the content.
  5. Remove or recompute previews, FTS content, reply previews, reactions,
     notifications, saved anchors, and attachment/link-preview state derived
     from the deleted content without destabilizing surrounding history.
- **Data boundary:** use an explicit migration for redaction/tombstone state.
  Do not hard-delete the message if doing so would erase the recommended audit
  indication or break reply/history identity.
- **Failure behavior:** surface server `FAIL REDACT` reasons; a rejected send
  leaves content unchanged; unknown msgids are ignored safely; repeated live
  and history redactions converge to one tombstone.
- **Acceptance:** two clients converge live; reconnect and CHATHISTORY do not
  resurrect content; search and previews no longer expose redacted text; the
  audit indication remains visible and honest about its cosmetic nature.
- **Focused verification:** command/error parsing, permissions failure,
  self/moderator redaction, duplicate and out-of-order history, FTS/previews,
  replies/reactions, migration, push race, and two-client soju E2E.
- **Reference:** [IRCv3 message redaction](https://ircv3.net/specs/extensions/message-redaction).

## B. Batch presentation

### B1. Preserve netsplit/netjoin semantics instead of flattening them

- **Priority / size / status:** P2, L, Ready / included.
- **Applies to:** Libera.Chat's deployed `batch` support and soju forwarding.
- **Evidence:** `BatchAssembler` correctly buffers and nests batches, but
  `IrcClient.emitBatch` recognizes only `chathistory`; all other batch types are
  flattened into individual events. That is protocol-valid degradation but
  forfeits the principal netsplit/netjoin UX benefit.

#### Protocol tree and validation

- Refactor batch assembly to retain an immutable tree containing batch
  reference, type, parameters, ordered children, and nested batches. Do not
  flatten a typed nested batch merely because its parent is CHATHISTORY.
- A valid `netsplit` has exactly the two server parameters and only QUIT
  descendants. A valid `netjoin` has exactly the two server parameters and only
  JOIN descendants. Preserve child wire order.
- Unknown batch types recursively flatten to today's event stream. A malformed
  known batch also recursively flattens; membership correctness takes priority
  over collapsing, and valid typed grandchildren remain eligible for their own
  handling.

#### Persistence and presentation

- In one `EventProcessor` transaction, apply every child membership mutation,
  calculate affected existing channel buffers, and insert one `NETSPLIT` or
  `NETJOIN` row per affected channel. Do not insert per-nick JOIN/QUIT rows for
  children consumed by the typed batch.
- Build a stable `eventKey` from type, normalized server pair, channel, and
  ordered child msgids. When msgids are absent, use normalized nicks plus a
  bounded server-time bucket. Live forwarding and CHATHISTORY must converge.
- Render a dedicated collapsible pill showing split/join, affected count, and
  server pair. Expansion lists nicks in wire order. Do not merge the pill into
  adjacent generic system-message runs.
- `NETSPLIT` and `NETJOIN` follow the same chat-list visibility, activity,
  unread, preview, and raw-SQL exclusion policy as existing JOIN/PART/QUIT
  noise. Audit every DAO/query path; adding a Compose filter alone is
  insufficient.

#### Acceptance and focused verification

- A 100-user split updates all membership rows but creates exactly one pill in
  each affected channel; the corresponding netjoin converges membership.
  Ordinary JOIN/QUIT outside a batch remains unchanged.
- Nested typed batches survive CHATHISTORY and replay idempotently. Interleaved
  batches, multi-channel fan-out, malformed parameters/children, unknown batch
  flattening, disconnect mid-batch, and missing msgids remain deterministic.
- Tests cover immutable tree assembly, validation and recursive degradation,
  transaction rollback, dedup identities, expansion order/semantics, every
  visibility/read/preview query, replay, and a deterministic scripted
  Solanum-compatible netsplit fixture.
- **References:** [IRCv3 batch](https://ircv3.net/specs/extensions/batch),
  [Libera.Chat 2026 rollout](https://libera.chat/news/new-and-upcoming-features-3).

## A. Connection presence intent

### A1. Decide and implement `draft/pre-away` for background connections

- **Priority / size / status:** P2, M, Product decision required.
- **Applies to:** soju; the reviewed spec lists soju server support and describes
  the extension as work-in-progress.
- **Question to settle before implementation:** which MOTD connections represent
  active user presence? A safe starting policy is foreground/user-opened
  connections = present, background history/push maintenance connections =
  `AWAY *`, but Android lifecycle transitions and persistent-socket mode need a
  single documented rule.
- **Implementation after decision:** negotiate the proven draft version and
  allow the registration state machine to send the selected AWAY command after
  CAP ACK but before registration completes. Reconcile foreground/background
  transitions without flapping during brief configuration changes.
- **Failure behavior:** without the capability, do not send pre-registration
  AWAY; retain manual `/away`. A transient lifecycle event must not overwrite a
  user-supplied human-readable away message without an explicit policy.
- **Acceptance:** a history-only/background MOTD connection does not make the
  soju user appear present; returning to the active UI clears only MOTD's
  automatic away state; manual away remains distinguishable and respected.
- **Focused verification:** registration ordering, foreground/background
  debounce, process recreation, multiple downstream clients, manual away,
  reconnect, unsupported degradation, and local soju observation.
- **Reference:** [IRCv3 pre-away](https://ircv3.net/specs/extensions/pre-away).

## M. Deferred and maintenance items

### M1. Bot-mode presentation

- **Priority / size / status:** P3, M, Deferred until a deployed target and UI
  contract are confirmed.
- **Scope:** parse the advertised bot mode/WHO flag and expose a restrained bot
  badge in member/nick surfaces. Do not infer bot status from nick patterns.
- **Rationale:** soju can carry bot mode, while Libera.Chat described deployment
  as planned rather than complete in the reviewed material. Client support can
  be prepared after P1/U1 because WHOX is the natural identity path.
- **Acceptance:** known bots are identifiable without changing message delivery,
  mentions, moderation, or notification priority.

### M2. Account-extban moderation affordance

- **Priority / size / status:** P3, M, Deferred.
- **Scope:** when a member has a known account, offer an account-ban mask in the
  moderation sheet alongside the existing host-based ban. Obtain the network's
  advertised extban syntax; never assume `$a:` universally without ISUPPORT.
- **Acceptance:** the UI previews the exact mask, sends one MODE command after
  confirmation, and hides/disables the action when syntax or account is unknown.

### M3. `UTF8ONLY` bookkeeping

- **Priority / size / status:** P3, S, Deferred.
- **Scope:** retain and expose the ISUPPORT token for diagnostics and support
  claims. Android/Kotlin strings and the current serializer are already UTF-8;
  no charset selector or transcoding path should be added.
- **Acceptance:** UTF8-only servers connect normally, invalid local encoding
  cannot be selected, and diagnostics accurately report the server token.

### M4. `draft/multiline`

- **Priority / size / status:** P3, L, watch Libera deployment and draft status.
- **Evidence:** MOTD currently splits pasted physical lines into separate
  PRIVMSGs, which is safe but loses logical-message grouping. Libera.Chat listed
  multiline as a possible near-term feature, not a deployed one; the reviewed
  soju support table did not list downstream multiline support.
- **Future scope:** preserve one logical composer message across a multiline
  batch, enforce server limits, fall back to today's safe split, and make Room,
  replies, reactions, notifications, search, history, and redaction agree on
  logical versus physical identity.
- **Trigger:** move to Ready only after the actual soju/Libera path used by MOTD
  advertises a compatible capability and the draft revision is pinned in tests.

## Verified existing coverage

These are not implementation backlog items. They are recorded to prevent
duplicate work and should be regression-tested when adjacent code changes.

| Feature | Current MOTD behavior |
|---|---|
| CAP 302 / `cap-notify` | Multiline LS, runtime NEW/DEL/ACK, Ready cap updates |
| SASL | PLAIN and EXTERNAL; required auth never falls back to NickServ password messages |
| `server-time` / `msgid` | Durable ordering and per-buffer message identity/dedup |
| `echo-message` / `labeled-response` | Pending-send correlation with explicit degradation paths |
| `multi-prefix` | All advertised NAMES prefixes are retained |
| away/account/extended-join/chghost/setname | Typed user-state events converge into Room |
| `message-tags` / `+typing` | Incoming and throttled outgoing typing indicators |
| `+reply` | Ratified send/receive with legacy `+draft/reply` fallback |
| `+draft/react` / `+draft/unreact` | Capability/CLIENTTAGDENY-gated live mutation and replay |
| `draft/chathistory` / event playback | Paging, reconnect catch-up, around/targets, idempotent Room replay |
| `draft/read-marker` | Monotonic local and cross-client read convergence |
| metadata | `draft/metadata-2` avatar subscription, sync, publication, and removal |
| soju bouncer networks | Discovery/notify, root-child persistence, network selection |
| `soju.im/webpush` | Registration, unregistration, encrypted push handling |
| STS | Capability value surfaced and persisted at the Android transport boundary |

Libera.Chat currently permits the `+typing` client tag, which MOTD already
supports. Its reviewed announcement says `+draft/channel-context`, reactions,
replies, and unreact remain under consideration. MOTD's reply/reaction support
should therefore continue to obey `CLIENTTAGDENY` and visibly degrade rather
than assuming direct Libera acceptance.

## Ready implementation evidence

Status on 2026-07-14: all six Ready implementations, focused JVM/Robolectric
tests, release-parity build, direct/soju scripted checks, ZNC degradation
checks, and the physical-device UI pass are complete. The executive table and
combined Ready goal are Complete.

### I1 evidence

- `5096b0c`, `c520779`, and `0820b74` add durable typed invitations, push
  convergence, actionable cards, notification actions, and retryable failures.
  `0607fb9` makes the durable CAS -> bounded Ready wait -> state recheck ->
  single wire write sequence independently testable.
- `EventProcessorTest`, `PushEventHandlerTest`, `InviteJoinCoordinatorTest`, and
  `MotdNotificationsFoolTest` cover socket/push parsing, msgid and 30-second
  fallback dedup, replay after resolution, historical/third-party/invalid
  placement, preview without unread, concurrent repeated actions,
  timeout/retry without a delayed JOIN, dismiss races, JOIN
  echo/rejection/disconnect, and real Android open/Join/Dismiss/swipe intents.
- `./test/e2e/local-stack.sh invite-check` passed with a direct Ergo sender and
  attached soju downstream client; the INVITE retained server-time and msgid.
  The scripted direct fixture also passed a separate two-client invite.

### U1 and U2 evidence

- `ab7fa4f`, `38ac1a8`, and `e2b0ca8` retain multi-prefix userhost NAMES data,
  add token-correlated WHOX snapshots, converge observable cached identity, and
  merge it with one-off WHOIS UI state. `6e87c08` preserves safe fields from a
  partial WHOX row without inventing away or realname state.
- `EventMapperNamesTest`, `WhoxTest`, `EventProcessorTest`, and WHOIS merge tests
  cover exact `%tuhnafr` serialization (no IP), placeholders, malformed and
  partial rows, casemapped completion, concurrent/coalesced masks, timeout and
  disconnect, Room convergence, CHGHOST precedence, token wrap/exhaustion, and
  unchanged membership prefixes.
- `./test/e2e/local-stack.sh ready-check` passed exact userhost NAMES and WHOX
  behavior directly. The soju leg proves documented degradation: pinned soju
  omits downstream `userhost-in-names` but preserves WHOX enrichment.

### N1 evidence

- `814f837`, `720cffe`, and `d66bc9` implement stable alias preference, explicit
  single-flight NAMES+WHOX refresh, connection-scoped roster authority, stale
  presentation/retry, parsed PREFIX/CHANMODES, and invalidation/clear rules.
  `d55cecf` prevents NAMES from claiming `LOADED` before paired WHOX succeeds;
  `952cf55` prevents NICK/QUIT races from resurrecting snapshot members while
  retaining exactly-once system presentation.
- Capability, roster-state, presentation, and `EventProcessorTest` coverage
  includes every alias, unsupported behavior, forced/failed refresh semantics,
  JOIN/PART/KICK/QUIT/NICK/MODE ordering, alternate PREFIX/CHANMODES, stale
  counts, self-PART clearing, and retryable WHOX timeout.
- The direct scripted fixture proves no implicit 353/366 after JOIN and a
  complete explicit NAMES/WHOX response. The soju leg proves draft-alias
  selection and its userhost degradation without losing membership.

### P1 evidence

- `c0cfdd1` and `bfdfedc` add typed 730-734 events, bounded UTF-8 command
  chunking, desired-set selection, fresh C/+ /S and live +/- reconciliation,
  connection-scoped presence, metadata WHOX, and accessible query/nick-sheet
  indicators. `7ecba03` exposes and tests the reconciliation/presence reducers.
- Physical testing found that Ready could precede the server's runtime `005`
  snapshot and that `MONITOR` was absent from the exposed ISUPPORT whitelist.
  `0e72cde` initializes subscriptions when runtime registration gains MONITOR;
  `653a343` exposes MONITOR in Ready/Registered snapshots. Both paths have
  focused regression coverage.
- Protocol and app tests cover MONITOR support parsing, priority/limit policy,
  hostmask and malformed numerics, tracked-only updates after rejection,
  friend/query diffs, nick rekey, disconnect-to-unknown, diagnostic
  aggregation, and absence of timeline/activity persistence.
- The direct fixture passed online/offline/list snapshots and WHOX metadata.
  The soju fixture passed extended-monitor forwarding while documenting its
  numeric formatting differences. Root and unsupported paths issue no MONITOR
  commands by construction and retain unknown presence.

### B1 evidence

- `65a9332` retains immutable nested batch trees, recursively degrades unknown
  or malformed batches, atomically fans membership out to affected channels,
  persists stable typed pills, and excludes them from JPQ visibility/activity,
  unread, preview, search, and raw visibility paths. `8d04f19` adds localized
  accessible expanded/collapsed state.
- Batch assembler/mapper, DAO/visibility, payload, and `EventProcessorTest`
  coverage includes nested CHATHISTORY, wire order, 100-user and multi-channel
  fan-out, malformed all-or-nothing behavior, forced transaction rollback,
  replay, missing-msgid bucket identities, disconnect reset, expansion content,
  and ordinary JOIN/QUIT behavior.
- The deterministic direct fixture passed nested Solanum-shaped netsplit and
  netjoin batches. Pinned soju strips those wrappers but preserves msgid-bearing
  QUIT/JOIN mutations; `ready-check` asserts that clean degradation explicitly.

### Physical-device evidence

- A fresh FOSS debug APK was installed on physical device `00152151K005265`
  (Nothing A059) on 2026-07-14. The existing local soju profile reconnected
  after explicit trust of the regenerated fixture certificate.
- I1/U2: a direct Ergo sender delivered an INVITE through soju with server-time
  and msgid. The device rendered `chat_invite_card_124` with Join/Dismiss,
  transitioned it to `chat_invite_resolved_124`, and posted an `invitations`
  channel notification with open, Join, Dismiss, and swipe/delete intents.
- U1/N1: opening direct `#ready` Channel Info triggered correlated WHOX and
  reached `channelinfo_roster_state = 1 member`; `readyfriend` displayed
  `Ready Fixture User`, `fixture@ready.example`, and account `readyaccount`.
- P1: after the runtime-ISUPPORT fixes, the direct transcript showed fresh
  `MONITOR C`, `MONITOR + readyfriend`, `MONITOR S`, 730/732/733 handling, and
  metadata WHOX. The live chat list exposed `chatlist_presence_online` for
  `readyfriend` and `chatlist_presence_offline` for the offline query.
- B1: `daf4828` makes the deterministic fixture emit two users per network
  batch. The device rendered typed two-user netsplit/netjoin pills; expanding
  the netjoin exposed `readyfriend` and `splitfriend`. The soju path separately
  rendered its documented ordinary JOIN/QUIT fallback after wrapper stripping.

### Shared and compatibility evidence

- `ae289d6` provides schema 6 and the additive 5-to-6 migration. The populated
  migration test preserves messages, FTS search, buffer joined/pinned
  visibility, and member prefixes while enforcing typed event identity. The
  destructive development-only downgrade boundary remains documented beside
  `MIGRATION_5_6` and `DbModule`.
- The release-parity matrix from `.agents/testing.md`, plus debug assembly,
  passed again after the device-discovered MONITOR fixes on 2026-07-14:
  `:irc:build`, both FOSS unit-test variants, debug lint, and release/debug APK
  assembly were green (`BUILD SUCCESSFUL` in 5m07s).
- `./test/e2e/znc-stack.sh probe` passed. The pinned ZNC advertises neither
  MONITOR nor no-implicit-names and lacks `draft/chathistory`; connection,
  messaging, two-client routing, reconnect-gap recovery, and timestamped native
  playback remain intact.

## Completion and verification policy

The Ready work is one implementation goal but should land in the documented
delivery order, with each slice independently testable. For every included item
moved to Complete:

1. Update its status and add dated implementation evidence in this file.
2. Run `nix develop -c ./gradlew :irc:test --stacktrace` for `:irc` changes and
   `nix develop -c ./gradlew :app:testFossDebugUnitTest --stacktrace` for app
   persistence/service/ViewModel changes.
3. Use the full release-parity command from `.agents/testing.md` when an item
   crosses modules, changes Room schema, or changes release behavior.

   ```sh
   nix develop -c ./gradlew \
     :irc:build \
     :app:testFossDebugUnitTest :app:testFossReleaseUnitTest \
     :app:lintFossDebug :app:assembleFossRelease \
     --stacktrace --no-daemon --max-workers=1
   ```

4. Add `:irc` unit coverage for capability aliases, MONITOR, WHOX,
   userhost/NAMES races, and nested batches as applicable.
5. Add app tests for the Room migration and FTS preservation, invite push and
   actions, dedup/state transitions, WHOX/WHOIS UI merge, roster freshness,
   presence, batch persistence/presentation, and raw-SQL visibility policies.
6. Give actionable cards, roster freshness, presence indicators, and batch
   expansion stable accessible semantics/test tags.
7. Use `test/e2e/local-stack.sh` for direct/soju invitations (including push),
   MONITOR/extended-monitor, WHOX, and soju capability aliases. Extend the
   fixture deterministically where it cannot yet emit the required traffic.
8. Add a deterministic scripted Solanum-compatible fixture for ratified
   `no-implicit-names`, `userhost-in-names`, and netsplit/netjoin batches.
9. Use `test/e2e/znc-stack.sh` to prove unsupported-capability degradation and
   ensure the Ready work does not regress the existing ZNC contract.
10. Check invitation notifications, query presence, stale roster labeling,
    lazy load triggers, and batch expansion on a physical device or suitable
    emulator when available.
11. Record unsupported/degraded behavior as evidence, not only the capable
    path. FOSS is the only build flavor in scope.

### Combined-goal acceptance checklist

The combined goal may be marked Complete only when all of the following are
true:

- I1, P1, U1, U2, N1, and B1 individually satisfy their acceptance sections;
- Room schema 6 migrates a populated schema-5 database without losing messages,
  search results, members, or buffer visibility;
- reconnect and history replay cannot duplicate invitations, membership
  mutations, or collapsed batches;
- unsupported direct servers and ZNC retain current connection, messaging,
  completion, and roster behavior with explicit unknown/stale states where
  appropriate;
- invitations alone may create chat-list activity without unread; presence and
  split/join aggregation create neither activity nor unread;
- no Ready feature introduces a dependency or a second networking stack;
- the two focused Gradle test commands and the full release-parity command from
  `.agents/testing.md` pass, plus the applicable E2E and device checks above.

## Fixed assumptions and exclusions

- Friends remain a global normalized-nick preference. P1 applies those friends
  to every eligible Ready network; this goal does not add per-network friend
  configuration.
- Presence and roster authority are connection-scoped and ephemeral. Cached
  user metadata, members, invitations, and collapsed batch events remain
  durable in Room.
- Resolved invitation audit rows have no timed deletion. Normal buffer deletion
  is their only automatic lifecycle boundary.
- Only self-invites create notification/action behavior. Third-party invites,
  presence changes, and netsplit/netjoin batches are quiet.
- No production implementation, dependency change, commit, push, or release is
  performed by this planning document itself.

## Audit references

- [IRCv3 client support table](https://ircv3.net/software/clients)
- [Libera.Chat: New And Upcoming IRCv3 Features, 2026-02-11](https://libera.chat/news/new-and-upcoming-features-3)
- [Libera.Chat client guidance](https://libera.chat/guides/clients)
- [soju project](https://github.com/emersion/soju)

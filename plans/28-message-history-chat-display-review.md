# Message History and Chat Display Review

## Summary

No files were changed during the review. The review found 2 P0 reliability
risks, 9 P1 correctness/UX issues, and 3 P2 performance/polish issues.

Focused tests could not start because the Nix cache permission review timed
out twice. The findings below are therefore static-analysis and
specification-backed.

## Findings

1. **P0 — Live IRC events can be silently lost.** The critical stream uses
   `DROP_OLDEST` after 4,096 events in
   [`IrcClient.kt`](../irc/src/main/kotlin/io/github/trevarj/motd/irc/client/IrcClient.kt#L128),
   while its sole app consumer performs serialized database work and is
   cancelled immediately when a connection ends in
   [`ConnectionActor.kt`](../app/src/main/kotlin/io/github/trevarj/motd/service/ConnectionActor.kt#L153).
   A playback burst, flood, slow Room transaction, or disconnect with queued
   events can create an undetected history gap.

2. **P0 — A request that was never sent can become permanent “end of
   history.”** Both labeled and unlabeled CHATHISTORY paths return an empty
   list when the transport disappears in
   [`IrcClient.kt`](../irc/src/main/kotlin/io/github/trevarj/motd/irc/client/IrcClient.kt#L503).
   The mediator then marks history complete in
   [`ChatHistoryRemoteMediator.kt`](../app/src/main/kotlin/io/github/trevarj/motd/data/sync/ChatHistoryRemoteMediator.kt#L125),
   while network reconciliation may advance its watermark in
   [`HistoryResyncCoordinator.kt`](../app/src/main/kotlin/io/github/trevarj/motd/service/HistoryResyncCoordinator.kt#L331).
   Disconnecting between the capability check and write can therefore hide
   retained history indefinitely.

3. **P1 — CHATHISTORY pagination uses unreliable completion and cursor
   rules.** `ChatHistoryResult` has no batch metadata in
   [`IrcClient.kt`](../irc/src/main/kotlin/io/github/trevarj/motd/irc/client/IrcClient.kt#L81),
   so paging stops on `events.size < limit`. IRCv3 explicitly allows short or
   oversized pages and defines `draft/chathistory-end`; context events also do
   not count toward the limit. Additionally, AFTER paging advances from the
   newest row in the entire database in
   [`HistoryResyncCoordinator.kt`](../app/src/main/kotlin/io/github/trevarj/motd/service/HistoryResyncCoordinator.kt#L612),
   so a concurrently arriving live message can jump the cursor past the
   remainder of the page. Follow the
   [IRCv3 CHATHISTORY specification](https://ircv3.net/specs/extensions/chathistory.html):
   advance from the response’s primary-message boundary, and continue until
   an explicit end, an empty completed batch, an unchanged cursor, or a safety
   cap.

4. **P1 — Transient failures can be converted into successful but incomplete
   reconciliation.** AFTER timeouts and every ordinary exception are treated
   as selector rejection and replaced by LATEST in
   [`HistoryResyncCoordinator.kt`](../app/src/main/kotlin/io/github/trevarj/motd/service/HistoryResyncCoordinator.kt#L636).
   A successful newest-page fetch can then advance the network watermark
   despite an unrepaired middle gap. Only protocol errors such as
   `INVALID_MSGREFTYPE` should trigger selector fallback; disconnect, timeout,
   and I/O errors must leave the pass incomplete.

5. **P1 — The reconnect high-water mark is based on the device clock.** A
   completed pass stores `System.currentTimeMillis()` rather than the latest
   server-derived boundary in
   [`HistoryResyncCoordinator.kt`](../app/src/main/kotlin/io/github/trevarj/motd/service/HistoryResyncCoordinator.kt#L297).
   Clock skew or delayed cross-server delivery can move the cursor beyond
   messages that have not been observed. Track the latest covered
   authoritative `server-time`, with the IRCv3 fuzz overlap, and never advance
   it after a partial pass.

6. **P1 — Automatic reconciliation can swallow a user-selected refresh.**
   Manual refresh and visible-buffer reconciliation share only
   `(networkId, bufferId)` as their single-flight key in
   [`HistoryResyncCoordinator.kt`](../app/src/main/kotlin/io/github/trevarj/motd/service/HistoryResyncCoordinator.kt#L109).
   If the weaker automatic request starts first, “30 days” or “all available”
   merely awaits it and never runs. Cancellation can likewise target automatic
   work. Request intent and range must participate in scheduling.

7. **P1 — Sending can erase an unsent draft.** The composer clears
   immediately after launching `onSubmit` in
   [`ChatScreen.kt`](../app/src/main/kotlin/io/github/trevarj/motd/ui/chat/ChatScreen.kt#L1148),
   while `ConnectionManager.sendMessage` discards its internal acceptance
   result in
   [`ConnectionManagerImpl.kt`](../app/src/main/kotlin/io/github/trevarj/motd/service/ConnectionManagerImpl.kt#L1040).
   A connection race can produce no pending row, and a long multi-chunk draft
   can lose its unattempted tail. Drafts are also process-only in
   [`ComposerDraftStore.kt`](../app/src/main/kotlin/io/github/trevarj/motd/ui/chat/ComposerDraftStore.kt#L7).
   Clear the composer only after every chunk is durably represented in the
   local outbox/timeline.

8. **P1 — MARKREAD can publish an invalid future timestamp.** The
   bottom-of-chat effect uses the newest raw row in
   [`ChatScreen.kt`](../app/src/main/kotlin/io/github/trevarj/motd/ui/chat/ChatScreen.kt#L866),
   including optimistic rows stamped with the device clock and marked
   non-authoritative in
   [`EventProcessor.kt`](../app/src/main/kotlin/io/github/trevarj/motd/data/sync/EventProcessor.kt#L1412).
   The [IRCv3 read-marker specification](https://ircv3.net/specs/extensions/read-marker)
   requires MARKREAD to match a previous message’s `time` tag. This can poison
   local and cross-client unread state when the device clock is ahead.

9. **P1 — IRC identity and channel classification are incorrect on valid
   networks.** `rfc1459-strict` incorrectly folds `~` and `^` in
   [`Proto.kt`](../irc/src/main/kotlin/io/github/trevarj/motd/irc/proto/Proto.kt#L287),
   while EventProcessor ignores advertised `CHANTYPES` and assumes `#&` in
   [`EventProcessor.kt`](../app/src/main/kotlin/io/github/trevarj/motd/data/sync/EventProcessor.kt#L1492).
   This can merge distinct users or route `+`/`!` channels as private
   messages. Centralize the exact `ascii`, `rfc1459`, `rfc1459-strict`, and
   CHANTYPES rules described by the
   [Modern IRC specification](https://modern.ircdocs.horse/).

10. **P1 — Labeled-response correlation is not concurrency-safe.**
    `LabelCorrelator` mutates ordinary `HashMap`s from request coroutines and
    the socket reader in
    [`Labels.kt`](../irc/src/main/kotlin/io/github/trevarj/motd/irc/client/Labels.kt#L36).
    Timed-out or cancelled registrations are never removed in `finally`, so
    they leak and consume late batches. Serialize correlator state and
    unregister every request on completion, timeout, cancellation, or write
    failure.

11. **P1 — Reaction storage loses valid protocol state and performs
    full-buffer scans.** The unique key permits only one reaction per raw
    sender and message in
    [`Entities.kt`](../app/src/main/kotlin/io/github/trevarj/motd/data/db/Entities.kt#L329),
    while every react/unreact loads the entire room’s reaction flow in
    [`EventProcessor.kt`](../app/src/main/kotlin/io/github/trevarj/motd/data/sync/EventProcessor.kt#L444).
    The [IRCv3 react draft](https://ircv3.net/specs/client-tags/react) makes
    unreaction reaction-specific and does not impose one reaction per sender.
    Store an account-or-casemapped actor key plus emoji, and use indexed
    targeted mutations. Optimistic reactions must roll back or show failure
    when the wire write is not accepted.

12. **P2 — Offline history and loading failures have misleading UX.** A
    missing client is presented as unsupported/empty in
    [`ChatHistoryRemoteMediator.kt`](../app/src/main/kotlin/io/github/trevarj/motd/data/sync/ChatHistoryRemoteMediator.kt#L175),
    `load` catches `Throwable`, including cancellation, and
    [`MessageList.kt`](../app/src/main/kotlin/io/github/trevarj/motd/ui/chat/MessageList.kt#L841)
    displays errors without a retry action. Distinguish offline, negotiating,
    unsupported, loading, incomplete, and confirmed start-of-history states;
    automatically retry once on Ready and offer a 48dp manual retry
    affordance.

13. **P2 — Long chats retain unbounded pages and some visibility reads scale
    poorly.** The Pager has no `maxSize` in
    [`MessageRepositoryImpl.kt`](../app/src/main/kotlin/io/github/trevarj/motd/data/repo/MessageRepositoryImpl.kt#L30),
    while fool-filtered visibility repeatedly scans with increasing SQL
    OFFSET in
    [`MessageVisibilityReader.kt`](../app/src/main/kotlin/io/github/trevarj/motd/data/visibility/MessageVisibilityReader.kt#L194).
    Reply/msgid resolution also lacks supporting indexes. Bound the presented
    timeline, use placeholder-aware jumps or keyset anchors, and push
    visibility/reference resolution into indexed SQL.

14. **P2 — Several chat interactions fail silently or group the wrong
    identity.** Reply-jump failure consumes its latch without showing the
    prepared snackbar in
    [`ChatScreen.kt`](../app/src/main/kotlin/io/github/trevarj/motd/ui/chat/ChatScreen.kt#L496);
    AROUND always uses a timestamp even when an exact msgid exists in
    [`ChatViewModel.kt`](../app/src/main/kotlin/io/github/trevarj/motd/ui/chat/ChatViewModel.kt#L748);
    bubble grouping compares raw nick spelling in
    [`MessageList.kt`](../app/src/main/kotlin/io/github/trevarj/motd/ui/chat/MessageList.kt#L139).
    Prefer exact, case-sensitive opaque msgids as selectors when supported,
    show actionable failures, and group by account identity or casemapped
    actor plus message direction.

## Implementation Changes

1. **Make transport and history responses trustworthy.**

   - Give EventProcessor one ordered, non-dropping critical event channel;
     keep best-effort broadcasts separate. Drain the critical queue on normal
     EOF before reconnecting. Any bounded overflow becomes an explicit
     integrity failure that triggers reconnect/catch-up rather than silent
     loss.
   - Replace `ChatHistoryResult` with sealed `ChatHistoryResponse.Messages`
     and `.Targets`. Message pages carry all ingestible events,
     primary-message oldest/newest references, and `endOfHistory`;
     constructing a successful response proves a complete batch was received.
   - Missing transport must throw `IrcDisconnectedException`. Make label
     correlation synchronized and cancellation-safe, preserving root-batch
     tags for CHATHISTORY metadata.
   - Introduce one `HistoryAvailability` model:
     `Ready(referenceTypes, pageLimit)`, `NegotiatingOrOffline`, or
     `Unsupported`. Prefer advertised msgid references, assume both when
     `MSGREFTYPES` is absent, and never normalize or order msgids because they
     are case-sensitive opaque identifiers under the
     [message-id specification](https://ircv3.net/specs/extensions/message-ids).

2. **Correct reconciliation and persistence.**

   - Advance AFTER/BEFORE exclusively from the returned page boundary,
     excluding `draft/chathistory-context` entries. Stop on explicit end,
     completed empty batch, unchanged boundary, or documented safety cap—not
     short page size.
   - Treat saturated TARGETS pagination with an unresolvable timestamp tie as
     incomplete and leave the network watermark unchanged.
   - Store the latest proven server-derived high-water mark. Add an explicit
     `Incomplete`/`Capped` resync result so partial work can never masquerade
     as `UpToDate`.
   - Use intent-aware scheduling: equivalent automatic requests coalesce;
     manual requests never join weaker automatic work; automatic work may join
     an active manual request; only manual work is cancelled by the UI.
   - Add migration 10→11 with `(bufferId,msgid)`, unresolved-reply, and
     unresolved-reaction indexes; rebuild reactions around
     `(bufferId,targetMsgid,actorKey,emoji)`; and preserve all existing rows.

3. **Adopt durable messaging-app send/read semantics.**

   - Change `ConnectionManager.sendMessage` to return
     `SendAcceptance.Accepted(eventIds)` or `Rejected(reason)`. Insert every
     chunk of an outgoing plan transactionally before any wire write, using
     caller-owned labels; transport failures mark durable rows failed and
     retryable.
   - Persist per-room draft text, reply target event ID, and update time.
     Retain the draft and reply on rejection; clear both only after local
     acceptance.
   - Separate the local read anchor `(serverTime,eventId)` from the remote IRC
     timestamp. Local unread/divider state advances to the visible timeline
     anchor; wire MARKREAD advances only to the newest authoritative `time`
     tag at or before that anchor.
   - Keep delivery indicators honest: pending clock, server-echoed/accepted
     check, and failed/retry. Do not show WhatsApp-style double checks because
     IRCv3 read markers describe the current user across their own clients,
     not recipient receipts.

4. **Finish the chat UX and performance pass.**

   - Centralize `IrcIdentityRules` in `:irc` and reuse it for room routing,
     mentions, reaction actors, friend/fool matching, and bubble grouping.
     Unknown casemaps use conservative ASCII folding plus diagnostics rather
     than merging potentially distinct identities.
   - Add typed `ChatUiEvent` values instead of magic snackbar strings.
     History errors and unresolved reply jumps include Retry; a Ready
     transition retries an offline history load once.
   - Keep page size 50/prefetch 25, enable placeholders, set `maxSize=500` and
     `jumpThreshold=250`, and update jump logic to request/wait for the target
     placeholder before validating its identity.
   - Replace OFFSET-based visibility scans and full-reaction scans with
     targeted DAO queries. Use msgid AROUND when available, timestamp only as
     fallback.

## Test Plan

- IRC/JVM tests: concurrent labeled requests; timeout/cancellation cleanup;
  disconnect before write; complete empty batch versus unsent request; end-tag
  and context metadata; strict RFC1459 and custom/empty CHANTYPES; lossless
  burst and disconnect-drain behavior.
- History tests: short non-terminal pages continue; explicit end stops; live
  insertion during AFTER cannot skip rows; same-millisecond boundaries;
  transient failures preserve the watermark; msgid rejection retries
  timestamp; TARGETS tie reports incomplete; automatic/manual request ordering
  and cancellation.
- Persistence tests: migration 10→11 retains messages/reactions/drafts;
  indexed reply and reaction resolution; multiple reactions from one actor;
  case-variant nick reconciliation; unreact removes only the selected emoji.
- Chat tests: rejection retains text and reply; every accepted multi-chunk
  draft has durable rows; failed rows remain retryable; future local pending
  timestamps never produce MARKREAD; offline/unsupported/end states differ;
  footer retry works; reply-jump failure is visible; account/casemapped grouping
  is stable.
- Paging/performance tests: scroll through at least 50,000 local rows while
  loaded pages remain bounded; deep jump and saved-position restoration still
  land on the exact event; fool-filtered chat-list queries do not perform
  growing OFFSET scans.
- Verification commands:
  `nix develop -c ./gradlew :irc:test :app:testFossDebugUnitTest :app:compileFossE2eAndroidTestKotlin :app:lintFossDebug :app:assembleFossDebug`,
  followed by the required CI headless E2E gate. No routine emulator/device
  E2E locally.

## Defaults and Constraints

- FOSS flavor only; no new networking stack or dependency.
- Preserve Room and preference compatibility through an explicit migration.
- Existing rooms previously merged by incorrect casemapping are not
  destructively split because their old history cannot be reconstructed
  reliably; future events use the corrected identity rules and durable
  aliases.
- “Start of history” appears only after a completed empty response or explicit
  end marker. Offline, unsupported, incomplete, and capped states remain
  visibly distinct.

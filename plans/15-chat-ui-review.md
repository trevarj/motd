# 15 — Chat UI review findings (prioritized)

Audit scope: `ui/chat/`, `ui/components/` chat pieces, `ui/imageviewer/`, cross-checked
against plans/07 (chat section) and plans/11 §C (deep-jump). Excludes the in-flight
white-theme / onboarding fix. Priorities: P0 broken or crash-risk, P1 logic/spec gaps,
P2 polish/a11y. File paths relative to `app/src/main/kotlin/io/github/trevarj/motd/`.

Verified non-issues (do not "fix"): reverse-layout intra-item ordering means the
`NewMessagesDivider`/`DaySeparator` emitted *after* the bubble in `MessageRow` correctly
render *above* it — the comment at `ui/chat/MessageList.kt:139` is right. `showsSender`
grouping logic is correct for reversed order. `TypingOutbox` throttles outbound typing at
the client layer, so per-keystroke `sendTyping` is wire-safe. Reaction add-only no-op for
`mine` chips matches plans/07:48-51.

## P0 — broken / crash risk

1. **Deep-jump crashes when the first page hasn't loaded** — `ui/chat/ChatScreen.kt:191`.
   `items[items.itemCount - 1]` runs with `itemCount == 0` (jump resolves in VM init, often
   before the first paging emission) → `IndexOutOfBoundsException`.
   Fix: before the loop, await `items.loadState.refresh is LoadState.NotLoading &&
   items.itemCount > 0` (snapshotFlow), and guard the tail-touch with `itemCount > 0`.

2. **Mark-read fires regardless of scroll position and instantly destroys unread UX** —
   `ui/chat/ChatScreen.kt:96-98` vs plans/07:81-83 ("Mark-read on resume + on
   new-message-while-at-bottom"). `LaunchedEffect(newestTime)` marks read on every arrival:
   - the "— New messages —" divider (`ui/chat/MessageList.kt:139-141`) is driven by the
     *live* `buffer.readMarkerTime`, so it flashes and vanishes within one DB round-trip;
   - the FAB unread badge (`ChatScreen.kt:297`, `unreadCount` 390-398) is effectively
     always 0;
   - read state is synced server-side (`ConnectionManagerImpl.markRead` → `MARKREAD`)
     while the user is scrolled up reading history, clearing unread on other clients.
   Fix: (a) snapshot `readMarkerTime` once on screen entry (VM field) and feed that frozen
   value to `MessageList`/`unreadCount`; (b) only call `markRead` when `atBottom` (hoist
   the `derivedStateOf` above `ChatContent` or pass it out) plus once on resume.

3. **Link-preview skeleton shimmers forever when the fetch fails/returns null** —
   `ui/chat/MessageList.kt:154-157`. `previewLoading = linkUrl != null && preview == null`
   never becomes false after `loadPreview` completes with null (plans/07:55 says "hidden on
   null"). Fix: track completion, e.g.
   `produceState<LinkPreviewState>(Loading)` that sets `Done(preview?)` after the call, and
   pass `loading = state is Loading`.

4. **No `key` on the message `items()` → scroll jumps and state misattribution** —
   `ui/chat/MessageList.kt:81`. Without stable keys, paging invalidations (every new
   message / echo confirm / page load) re-anchor by index: the viewport visibly shifts when
   messages arrive while scrolled up, and per-row state (`animateColorAsState`,
   `produceState` preview fetch) is reused across different messages.
   Fix: `items(count = items.itemCount, key = items.itemKey { it.id }, contentType =
   items.itemContentType { if (isSystemKind(it.kind)) "system" else "msg" })`
   (`androidx.paging.compose.itemKey` — paging 3.3.6 pinned, available).

5. **Reactions query can exceed SQLite's bind-variable limit → runtime crash** —
   `ui/chat/ChatScreen.kt:101-104` passes *every* loaded msgid;
   `data/repo/MessageRepositoryImpl.kt:34-35` → `data/db/Daos.kt:193` `IN (:msgids)`.
   Framework SQLite caps at 999 variables; scrolling back ~20 pages accumulates >999 loaded
   msgids (pages are never dropped: no `maxSize`, and adding one would break the
   placeholders-off jump-loop assumption). Fix: query by `bufferId` only and filter/aggregate
   in memory, or chunk the IN list in the repository (e.g. 500 per query, combine flows).

6. **Multiline composer input is serialized with embedded newlines → IRC line injection** —
   composer allows newlines (`ui/components/Composer.kt:76-78`, `maxLines = 6`,
   `ImeAction.Default`); `service/ConnectionManagerImpl.kt:269-291` `splitUtf8` splits on
   bytes only; `irc/.../proto/Proto.kt:167-222` `serialize()` never rejects CR/LF in params.
   A message containing `\n` corrupts the stream — the text after the newline is parsed by
   the server as a raw command. Fix: split the body on `\r?\n` into separate PRIVMSGs in
   `sendMessage` (before `splitUtf8`), and defensively throw on CR/LF in `serialize()`.

7. **No local echo when the server lacks `labeled-response`** —
   `service/ConnectionManagerImpl.kt:283-286`. The comment says "insert an
   already-confirmed self row" but the code just `continue`s; without `echo-message`
   the user's sent messages never appear in the chat. Fix: implement the confirmed insert
   (self row via `eventProcessor`, dedup key `sha1(serverTime|sender|text)` per
   `Entities.kt:78`).

8. **Typing indicator can get stuck indefinitely** — `data/sync/TypingTrackerImpl.kt:28-37`.
   Expiry is only evaluated when *another* typing event for the same buffer arrives; a lost
   "done" leaves "alice is typing…" forever (both `TypingIndicatorRow` and the TopBar
   subtitle at `ChatScreen.kt:377-380`). Fix: schedule a sweep coroutine at
   `expiresAt` (tracker needs a scope) that removes expired entries and re-emits.

9. **Composer likely hidden behind the keyboard on Android 15** — targetSdk 35 enforces
   edge-to-edge; no `imePadding()` anywhere in the app and no `windowSoftInputMode` in
   `AndroidManifest.xml:23-25`. Fix: add `Modifier.imePadding()` to ChatContent's Scaffold
   content (or the Column at `ChatScreen.kt:277`); coordinate with the in-flight
   MainActivity theme fix (this is IME insets, not the white-theme bug).

## P1 — logic / spec gaps

10. **Retry duplicates the message and loses ACTION-ness; failed rows are undeletable** —
    `ui/chat/ChatViewModel.kt:136-138` resends but never removes the failed row →
    permanent duplicate "failed" bubble next to the successful resend. Also
    `ConnectionManagerImpl.kt:287` stores the *stripped* display text for `/me`, so
    retrying a failed ACTION sends it as a plain PRIVMSG. Fix: on retry, delete the failed
    row (new DAO delete) and re-prefix `"/me "` when `kind == ACTION`; add a
    Delete option to the action sheet for failed rows.

11. **Message-body URLs are not tappable** — `ui/components/MessageBubble.kt:161-162`
    renders plain `Text`; plans/07:52 requires linkified URLs (`LinkAnnotation`). Combined
    with P0-3, a message whose preview fails offers *no* way to open its link. Fix: build an
    `AnnotatedString` with `LinkAnnotation.Url` (Compose BOM 2025.04.01 → UI 1.8, available;
    no new dep). Nick/channel linkify from the plan can be follow-up.

12. **Deep-jump re-resolve can dead-end via StateFlow equality dedup** —
    `ui/chat/ChatViewModel.kt:244-253` + `ui/chat/ChatScreen.kt:198-201`. If
    `reresolveJumpOnce()` resolves to a `Target` equal to the current one,
    `_jumpTarget.value = r` doesn't re-emit, the `LaunchedEffect(jumpTarget)` never
    re-runs, and `onJumpHandled()` is never called → `jumpTarget` stays set and a config
    change replays the scroll. There is also no guard making the re-resolve "once".
    Fix: set `_jumpTarget.value = null` before publishing the re-resolved target (or add a
    nonce field), and have the screen call `onJumpHandled()` after the single retry attempt.

13. **"Message not loaded" snackbar race for the initial jump** —
    `ui/chat/ChatViewModel.kt:217-219`. `MutableSharedFlow(extraBufferCapacity = 1)` has no
    replay; `resolveJump()` runs in `init` and its `NotFound` `tryEmit` is dropped when it
    completes before the screen's collector (`ChatScreen.kt:179-181`) subscribes.
    Fix: `replay = 1` + consume-on-collect, or model as a nullable StateFlow event cleared
    by the UI.

14. **Jump APPEND loop can spin through its 64 rounds without waiting for a load** —
    `ui/chat/ChatScreen.kt:191-192`. After touching the tail,
    `snapshotFlow { loadState.append }.first { it is NotLoading }` matches the *current*
    NotLoading state before the triggered load starts. Fix: wait for a Loading→NotLoading
    transition (e.g. `first { it is Loading }` with timeout, then `first { NotLoading }`)
    or await `itemCount` growth with `withTimeoutOrNull`.

15. **Consecutive system events never collapse** — `ui/chat/MessageList.kt:147` always
    passes `lines = listOf(msg.text)`; plans/07:45-46 requires runs to collapse to one pill
    ("3 joined · 1 left", tap to expand) and `SystemEventPill` already implements the
    expansion. Netsplits currently flood the list with one pill per event. Fix: in
    `MessageList`, only render the pill on the run's newest item, aggregating the adjacent
    older SYSTEM_KINDS items (peek forward) into `summary`/`lines`; skip the others.

16. **ACTION messages drop reactions, reply preview, failed state, and timestamp** —
    `ui/components/MessageBubble.kt:76-88` early-returns before all decorations. Reacting
    to an ACTION via the sheet "works" but the chip never renders; a failed `/me` shows no
    error. Fix: render `ReactionRow` + failed indicator (and reply if present) in the
    ACTION branch.

17. **`/part reason` silently discarded** — `ui/chat/CommandParser.kt:72` parses the reason,
    `ui/chat/ChatViewModel.kt:156` calls `partChannel(bufferId)` without it and
    `ConnectionManagerImpl.partChannel:321-326` can't take one. Fix: add the reason param
    through the seam and append it to the PART params.

18. **Reactions/recent-speakers memo staleness + chip flicker** —
    `ui/chat/ChatScreen.kt:101-105, 226-228`. Keyed on `items.itemCount` only:
    (a) an echo-confirm that swaps a pending row's msgid doesn't change the count → the new
    msgid is never subscribed; (b) every count change builds a *new* flow and
    `collectAsStateWithLifecycle(initialValue = emptyList())` blanks all reaction chips for
    a frame each time a message arrives. Fix: move aggregation into the VM (flatMapLatest
    over a msgid-set StateFlow, `stateIn` so the previous value is retained), or at minimum
    seed `initialValue` with the previous chips.

19. **Quote wipes the current draft and puts the cursor at position 0** —
    `ui/chat/ChatScreen.kt:358`: `TextFieldValue("> ${target.text}\n")` (default selection
    is `TextRange.Zero`). Fix: append to the existing draft (reuse `appendPrefill`-style
    logic) and set `selection = TextRange(text.length)`.

20. **Action sheet offers no-op React/Reply for msgid-less rows** —
    `ui/chat/ChatScreen.kt:347-349`; pending/failed rows have `msgid = null`
    (`EventProcessor.insertPending:327`), so tapping an emoji silently does nothing and a
    reply loses its `+draft/reply` tag. Fix: pass the target into `MessageActionSheet` and
    hide the reaction row (and Reply) when `msgid == null`; show Retry/Delete for failed.

21. **Pending messages are indistinguishable from delivered ones** —
    `MessageEntity.pendingLabel` (`data/db/Entities.kt:76`) is never surfaced;
    `MessageList.kt:159-176` doesn't pass it. The user gets no "sending…" affordance until
    the 30s failure flips `failed`. Fix: pass `pending = msg.pendingLabel != null` to
    `MessageBubble`, render dimmed bubble or clock glyph next to the time.

## P2 — UX polish / a11y / consistency

22. **Nick colors keyed to `isSystemInDarkTheme()` instead of the applied theme** —
    `ui/components/MessageBubble.kt:90`, `ui/components/Avatar.kt:34`,
    `ui/components/Composer.kt:100`. With ThemeMode.DARK/AMOLED forced while the system is
    light, sender names/reply bars/avatars use the deep light-mode palette (L=0.42) on
    black → poor contrast (worst on AMOLED). Fix: derive darkness from the theme, e.g.
    `MaterialTheme.colorScheme.background.luminance() < 0.5f`, or a
    `CompositionLocal<Boolean>` provided by `MotdTheme`.

23. **Avatar initials always white** — `ui/components/Avatar.kt:45`; dark-mode nick colors
    are light (L=0.68) → white-on-light initials fail contrast. Fix: choose
    black/white by `bg.luminance()`.

24. **Touch targets below 48dp**: reaction chips ~24dp (`ui/components/ReactionRow.kt:55-64`),
    sheet quick reactions ~45dp / emoji grid ~44dp (`ui/chat/MessageActionSheet.kt:77-93,
    104-113`), RetryRow ~20dp tall (`ui/chat/MessageList.kt:188-209`), autocomplete rows
    ~40dp (`ui/components/NickAutocomplete.kt:42-46`). Fix: `Modifier.minimumInteractiveComponentSize()`
    (material3) or bump paddings/min-heights.

25. **Hardcoded user-visible strings** violate plans/07:5 ("All user-visible strings in
    `strings.xml`" — only `chat_jump_not_loaded` exists): "Back"/"Search"
    (`ChatScreen.kt:265,270`), "$n members" (383, also unpluralized "1 members"),
    "— New messages —" (`MessageList.kt:182`), "Tap to retry" (204), "Message" placeholder
    (`Composer.kt:75`), "Send" (88), "Replying to X"/"Cancel reply" (115,129), typing lines
    (`TypingIndicatorRow.kt:48-53`), "Today"/"Yesterday" (`DaySeparator.kt:66-67`),
    Reply/Copy/Quote + "＋" (`MessageActionSheet.kt:86,117-119`), "notice"
    (`MessageBubble.kt:138`), "Failed" (179), "Scroll to bottom" (`ChatScreen.kt:412`),
    "Share image"/"Saved to Pictures/MOTD"/"Couldn't save image"
    (`ImageViewerScreen.kt:145,169`). Fix: move to resources (use plurals for members).

26. **Image viewer**: pan is unclamped so the image can be flung fully off-screen
    (`ui/imageviewer/ImageViewerScreen.kt:84-93`); zoom isn't focal-point anchored and
    double-tap zooms to center, not the tap point (98-103); no loading/error state for the
    AsyncImage; image `contentDescription = null` (73). **Save is broken on API 26-28**:
    `RELATIVE_PATH` (157) is API 29+ and pre-29 inserts require `WRITE_EXTERNAL_STORAGE`
    (minSdk 26 — `app/build.gradle.kts:16`); `URL(url).openStream()` (152) has no timeouts.
    Fix: clamp offsets to `(scale-1) * size / 2`, use centroid-anchored zoom, gate MediaStore
    fields on `Build.VERSION.SDK_INT >= 29` (keeps NewApi/InlinedApi lint at 0/0; either add
    `WRITE_EXTERNAL_STORAGE` with `maxSdkVersion="28"` or hide Save pre-29).

27. **No loading / end-of-history / error / empty affordances in the list** —
    `ui/chat/MessageList.kt:75-120` ignores `items.loadState` entirely: no spinner row while
    older history appends, no "beginning of history" marker, mediator errors are silent, and
    an empty buffer is a blank pane (`ui/components/EmptyState.kt` exists, unused here).
    Fix: append `item {}`s keyed off `loadState.append` (CircularProgressIndicator / end
    label) and an empty-state overlay when `itemCount == 0 && refresh is NotLoading`.

28. **Time/date formatting**: `formatTime` hardcodes `HH:mm`
    (`ui/components/MessageBubble.kt:235-236`) ignoring the device 12/24-hour preference
    (use `android.text.format.DateFormat.getTimeFormat(context)`); `dayLabel` "Yesterday"
    breaks across DST transitions because it compares an exact 24h delta
    (`ui/components/DaySeparator.kt:61-70`); `SimpleDateFormat`/`Calendar` allocated per row
    per recomposition (also `dayStart` at `MessageList.kt:144`) — cache formatters.

29. **URL extraction strips balanced parens** — `ui/chat/Linkify.kt:10` unconditionally
    trims trailing `)` → Wikipedia-style URLs (`…/Foo_(bar)`) break for previews/open-link.
    Fix: only trim `)` when the URL contains no matching `(`.

30. **Autocomplete noise**: fires for any 1-char token (`ui/chat/ComposerAutocomplete.kt:29-31`)
    — typing "i" pops the panel if any member starts with "i" (plans/07:70 allows word-boundary
    matching, so this is a tuning call: suggest min 2 chars unless the token starts with `@`).
    `recentSpeakers` (`ChatScreen.kt:226-228`) includes system-event senders and self,
    polluting recency ranking — filter to `!isSystemKind(kind)`.

31. **Sheet & bubble interaction idioms**: dismissals skip the M3 hide animation
    (`ChatScreen.kt:346-360` set `sheetTarget = null` directly; idiom is
    `scope.launch { sheetState.hide() }.invokeOnCompletion { sheetTarget = null }`);
    the "＋" expander has no a11y label/expanded-state semantics
    (`MessageActionSheet.kt:86-93`); bubble `combinedClickable(onClick = {})` produces a
    dead ripple on plain taps and lacks `onLongClickLabel`
    (`ui/components/MessageBubble.kt:84,125`) — long-press is the only entry to actions and
    is otherwise undiscoverable to TalkBack users.

32. **Typing row pops without animation and "done" is never sent on exit** — the row
    appears/disappears abruptly, shifting list height (`ChatScreen.kt:303`; wrap in
    `AnimatedVisibility`); leaving the screen with a non-blank draft never sends
    `done` (`ChatScreen.kt:91-94`); cursor-only selection changes in non-blank text
    re-trigger `onTyping(true)` (`ChatScreen.kt:310-314` — compare `it.text` to the old
    text first).

33. **`aggregateReactions` "mine" check uses `equalsIgnoreCase`** —
    `ui/chat/ChatModels.kt:23` — not isupport casefolding (`[]{}|~`); pass the
    `nickNormalizer` in. Also `myNick` is null when disconnected → own chips lose the
    `mine` styling/no-op guard (harmless offline, but styling flickers on reconnect).

34. **Inline images cause layout jumps** — `ui/components/MessageBubble.kt:147-158`:
    `AsyncImage` has no placeholder size, so rows grow when bitmaps land, shifting the
    reversed-list anchor. Fix: reserve a default aspect (e.g. 4:3 `aspectRatio` until
    loaded) — pairs with the `key` fix (P0-4).

## Lint / constraint notes for the implementer

- Version pins are law (plans/01): everything above is doable with pinned deps —
  `androidx.paging.compose.itemKey` (paging 3.3.6), `LinkAnnotation.Url` (BOM 2025.04.01 /
  UI 1.8), `minimumInteractiveComponentSize` (material3). **No new dependencies needed.**
- MediaStore fix must be gated on `Build.VERSION.SDK_INT >= 29` or lint `NewApi`/`InlinedApi`
  will trip warningsAsErrors.
- New user-facing strings go to `strings.xml` (plans/07 requirement); use `plurals` for the
  member count.
- When adding `key`/`contentType` to `items()`, keep the deep-jump loop's
  "placeholders OFF, tail loads never shift indices" invariant — do **not** add
  `PagingConfig.maxSize` (page drops would shift indices and break `ChatJumpResolver`).
- Removing the unused `state` param warnings etc.: Kotlin compiler warnings are not part of
  Android lint here, but keep imports tidy anyway.

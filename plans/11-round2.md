# 11 — Round 2: leftovers (mention prefill, per-network push, deep-jump, About/polish/lint)

v1 shipped complete (288 tests, CI green). This round implements the four recorded leftovers
plus a README rewrite, drives lint to zero with enforcement, and releases v0.1.0. Contract
amendments are listed in `10-contracts.md` §"Round 2 amendments" — WP-R0 lands them first so
parallel agents build against stable signatures.

Verified facts this design rests on:
- Nothing in v1 calls `UnifiedPush.registerApp` — the delivery-mode radio only writes the
  pref. Round 2 builds the registration trigger, not just multiplies it.
- Composer text is a plain `remember` in ChatScreen — drafts are lost when navigating to
  ChannelInfo and back. Item A fixes this with `rememberSaveable`.
- `roundIcon` in the manifest points at `@mipmap/ic_launcher`, so `ic_launcher_round.xml` is
  genuinely unreferenced — fix is pointing `roundIcon` at it.
- `IrcForegroundService` passes `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` (an API 34 constant)
  under an API 29 guard — the InlinedApi warning is a real correctness smell.
- `:irc` is pure Kotlin JVM — Android lint config applies to `:app` only.

## A. Mention → composer prefill (WP-R1)

Files: `ui/chat/ComposerDraftStore.kt` (new), `ui/chat/ChatViewModel.kt`,
`ui/chat/ChatScreen.kt`, `ui/channelinfo/ChannelInfoViewModel.kt`,
`ui/channelinfo/ChannelInfoScreen.kt`.

1. `ComposerDraftStore` (@Singleton, contract in plans/10): `ConcurrentHashMap<Long, String>`;
   `push` uses `merge(bufferId, text) { old, new -> old + new }` (two mentions queue as
   `"alice: bob: "`); `consume` is `remove(bufferId)` — atomic consume-once. No SharedFlow:
   ChannelInfo writes BEFORE `popBackStack()`, ChatScreen reads AFTER re-entering
   composition, so a plain pull is race-free.
2. `ChannelInfoViewModel` (+draftStore ctor param):
   `fun mentionMember(nick: String, onDone: () -> Unit)` — `draftStore.push(buffer.id,
   "$nick: ")` then `onDone()`. Screen wires `onMentionMember = { viewModel.mentionMember(it,
   onDone = onBack) }` (pops back to the chat). Delete the TODOs at ChannelInfoScreen.kt:58-59
   and 73-74.
3. `ChatViewModel` (+draftStore): `fun consumePrefill(): String? = draftStore.consume(bufferId)`.
4. `ChatScreen`/`ChatContent`:
   - composer text becomes `rememberSaveable(stateSaver = TextFieldValue.Saver)` (fixes the
     pre-existing draft loss too).
   - `LaunchedEffect(Unit) { consumePrefill()?.let { composerText = appendPrefill(composerText, it) } }`
     — re-runs on every composition entry; config change can't double-prefill because consume
     already emptied the store and the text survives via rememberSaveable.
   - Pure helper `appendPrefill(value: TextFieldValue, prefill: String): TextFieldValue` —
     single-space separator when current text is non-empty and doesn't end in whitespace;
     selection to end.

Tests: `ComposerDraftStoreTest` (consume-once, second consume null, concat on double push,
buffer isolation); `appendPrefill` cases (empty, trailing space, cursor lands at end).

## B. Per-network UnifiedPush (WP-R2)

Files: `push/UnifiedPushApi.kt` (new), `push/PushInstanceCoordinator.kt` (new),
`push/WebPushRegistrar.kt`, `push/MotdPushReceiver.kt`, `data/prefs/Settings.kt` +
`PreferencesStore.kt` (delete legacy single-endpoint API + `push_endpoint` key, no
migration — pre-release), `service/ConnectionManagerImpl.kt`, `MotdApplication.kt`,
`di/AppModule.kt` (bind `UnifiedPushApi` impl over the static connector), push tests.

1. Instance scheme: `instance = networkId.toString()`, one per connectable network. Remove
   `DEFAULT_NETWORK_ID`; non-numeric/unknown instances are logged, ignored, and
   `unregisterApp(instance)`-ed (stale hygiene).
2. `PushInstanceCoordinator` (@Singleton, own scope, `start()` from `MotdApplication.onCreate`,
   idempotent) — THE registration trigger:
   ```kotlin
   combine(settingsRepository.settings, networkDao.observeAll()) { s, nets ->
       s.deliveryMode to nets.filter { it.autoConnect }.map { it.id }.toSet()
   }.distinctUntilChanged().collect { (mode, ids) -> reconcile(mode, ids) }

   suspend fun reconcile(mode: DeliveryMode, connectable: Set<Long>) {
       val desired = if (mode == DeliveryMode.UNIFIED_PUSH) connectable else emptySet()
       if (desired.isNotEmpty() && up.getAckDistributor() == null) {
           up.getDistributors().firstOrNull()?.let(up::saveDistributor) ?: return
       }
       for (id in desired) up.registerApp(id.toString())
       for (id in (pushPrefs.endpoints().keys + connectable) - desired)
           up.unregisterApp(id.toString())
   }
   ```
   One loop covers: mode flip both ways, network add/remove under push mode, no-distributor
   no-op. `reconcile` public for direct unit tests with `FakeUnifiedPushApi`.
3. `WebPushRegistrar` per-network: `onNewEndpoint(networkId, endpoint): Boolean` (persist via
   `setEndpointFor`, WEBPUSH REGISTER on that network's live webpush client),
   `onUnregisteredNetwork(networkId)` (best-effort UNREGISTER + drop endpoint),
   `reRegisterIfNeeded(networkId): Boolean` (client reached Ready while we hold its endpoint).
   `registerOnAll`/global `onUnregistered` deleted. Shared keypair kept — the server encrypts
   to our public key regardless of endpoint.
4. `MotdPushReceiver`: `onNewEndpoint` validates `networkDao.byId(id)` exists →
   `registrar.onNewEndpoint(id, endpoint)` → `connectionManager.evaluatePushMode()`; drop the
   v1 `setDeliveryMode(UNIFIED_PUSH)` (mode is user-driven and precedes registration).
   `onMessage`: no fallback id. `onRegistrationFailed`: conservative full revert to
   PERSISTENT_SOCKET + `startAll()`. `onUnregistered(instance)`: per-network cleanup; revert
   mode only when `pushPrefs.endpoints().isEmpty()`.
5. `ConnectionManagerImpl`: `evaluatePushMode()` = re-run `maybeStopForPush()` iff mode is
   UNIFIED_PUSH (fixes: v1's settings-collect fired before endpoints existed and never again).
   `maybeStopForPush()` stops sockets only when EVERY live webpush-capable client has
   `endpointFor(id) != null` and at least one exists. `onReady(row, client)` +=
   `reRegisterIfNeeded(row.id)` when in push mode (inject `dagger.Lazy<WebPushRegistrar>` to
   break the ctor cycle).

Tests: coordinator reconcile matrix; registrar per-network (endpoint keyed by id, single
unregister keeps others, keys retained); receiver stale-instance handling.

## C. Search deep-jump (WP-R1)

Files: `ui/chat/ChatJumpResolver.kt` (new), `ui/chat/ChatViewModel.kt`, `ui/chat/ChatScreen.kt`,
`ui/chat/MessageList.kt`, `ui/search/SearchScreen.kt`, `strings.xml` (+`chat_jump_not_loaded`),
tests incl. Robolectric `MessageDaoJumpTest`.

1. SearchScreen: `onOpenBuffer` → `onOpenHit(bufferId, msgid, serverTime)` (defaulted lambda
   keeps compile until R3 wires NavGraph → `ChatRoute(b, msgid, time)`). Delete the :67 TODO.
2. `ChatJumpResolver(messages: MessageRepository, fetchAround: suspend (String, Long, Int) -> Boolean)`
   → `Result.Target(index, highlightMsgid) | Result.NotFound`:
   - msgid present locally → `index = countNewerThan(bufferId, hit.serverTime, hit.id)`.
   - miss + time>0 + bufferName → `fetchAround(name, timeMs, 100)` (caller wraps in
     `withTimeoutOrNull(10_000)`), retry lookup. For search-originated jumps the row is always
     local (FTS found it); this path serves robustness/future entry points.
   - msgid null → time approximation: `countNewerThan(bufferId, timeMs, Long.MAX_VALUE)`, no
     highlight.
3. `ChatViewModel`: read jump params via `SavedStateHandle.toRoute<ChatRoute>()`; consumed
   flag in SavedStateHandle (`jump_consumed`). `fetchAround` lambda: `clientFor(networkId)`,
   require `hasCap("draft/chathistory")`, `chathistory(AROUND, target, timestamp=Instant)`,
   feed events through `IrcEventSink.process` (idempotent dedup). Expose
   `jumpTarget: StateFlow<Target?>`, `jumpFailed`, `onJumpHandled()`, `reresolveJumpOnce()`.
4. Scroll (ChatScreen): bounded APPEND loop — placeholders stay OFF (frozen plans/04
   PagingConfig; also avoids O(history) scans and nullable rows). Pager REFRESHes at offset 0
   so local loads are tail APPENDs — indices never shift from loading:
   ```kotlin
   while (items.itemCount <= j.index && rounds++ < 64 && !append.endOfPaginationReached) {
       items[items.itemCount - 1]                       // touch tail → APPEND
       snapshotFlow { items.loadState.append }.first { it is LoadState.NotLoading }
   }
   ```
   then `listState.scrollToItem(j.index)`; verify `items.peek(j.index)?.msgid ==
   j.highlightMsgid`, one `reresolveJumpOnce()` if a live message shifted indices; highlight
   pulse ~1.6s (msgid-keyed `animateColorAsState` background in MessageList); beyond cap /
   NotFound → Snackbar `chat_jump_not_loaded` (ChatContent Scaffold gains a SnackbarHost).

Tests: DAO index math (newest row → 0, tied serverTime broken by id, boundaries);
resolver local-hit / AROUND-fallback (fake fetch inserts into fake repo) / timeout →
NotFound / null-msgid approximation.

## D. About screen + polish (WP-R3) and lint zero (WP-R4)

About: `ui/about/AboutScreen.kt` — lockup art (`motd_logo_lockup`), app name + version (move
`appVersion()` helper from SettingsScreen), blurb, License row ("MIT" → opens
`https://github.com/trevarj/motd/blob/main/LICENSE`), GitHub row (`url.toUri()`). Settings
About section collapses to one nav `ListItem` (supporting text = version) with
`onOpenAbout: () -> Unit = {}`. Strings: `about_title`, `about_license`, `about_license_mit`,
`about_blurb`. Repo-root `LICENSE`: standard MIT, `Copyright (c) 2026 Trevor Arjeski`.
NavGraph (R3-owned): AboutRoute, `onOpenAbout`, Search `onOpenHit` wiring.

Link-preview tap: in ChatScreen replace `onOpenLink = onOpenImage` with
`ctx.startActivity(Intent(ACTION_VIEW, it.toUri()))`. No route change.

README rewrite (R3, user request): dry, concise, no LLM-style filler; feature bullets → a
table with one-sentence-or-less descriptions; the ASCII architecture art is unreadable —
replace with a Mermaid flowchart (GitHub renders it) or delete the section; keep
build/smoke/release content but tighten.

Lint (R4, after all): `app/build.gradle.kts`:
```kotlin
android { lint {
    warningsAsErrors = true
    // Dependency versions are pinned by policy (plans/01); upgrade nags are intentional noise.
    disable += "GradleDependency"
} }
```
Dispositions: VectorRaster ×3 → reduce declared width/height ≤200dp keeping viewport (usages
size via Modifier); UnusedResources → `roundIcon="@mipmap/ic_launcher_round"` (real fix),
lockup/mark used by About, remove the 2 named unused strings; ImplicitSamInstance
(IrcModule.kt TransportFactory) → typed local; InlinedApi → raise FGS-type guard to API 34
(2-arg startForeground overload below); ConfigurationScreenWidthHeight (MessageBubble) →
`LocalWindowInfo.current.containerSize` via LocalDensity; PluralsCandidate → `<plurals>` +
`pluralStringResource`; ExportedReceiver → `tools:ignore` with XML comment (UnifiedPush spec
requires exported receiver); ObsoleteSdkInt → move `mipmap-anydpi-v26/` → `mipmap-anydpi/`;
UseKtx → gone via About refactor, sweep remainder. Ensure ci.yml covers lint (gradle `build`
already runs it). Every suppression carries a justification comment; no lint-baseline.

## Work packages

Waves: R0 (serial) → [R1 ∥ R2] → R3 → R4 (serial). Shared files owned by exactly one WP per
wave: Routes/ServiceSeam/prefs-additive → R0; NavGraph/README/LICENSE → R3;
ChatScreen/strings → R1 then R3/R4 sequentially; ConnectionManagerImpl → R0 (no-op stub) then
R2.

- **WP-R0 — contract amendments (serial):** Routes.kt, Settings.kt (additive PushPrefs),
  PreferencesStore.kt (implement new members; keep legacy until R2), Daos.kt (2 queries),
  Repositories.kt + MessageRepositoryImpl (2 methods), ServiceSeam.kt (`evaluatePushMode`) +
  no-op impl in ConnectionManagerImpl, update push-test fakes, append amendments to
  plans/10-contracts.md. Accept: full build green, all 288 tests pass.
- **WP-R1 — chat interactions (A+C):** owns ui/chat/*, ui/channelinfo/*, ui/search/SearchScreen.kt,
  strings (add `chat_jump_not_loaded` only), tests. Accept: new test suites green, TODOs gone.
- **WP-R2 — per-network push (B):** owns push/*, prefs legacy removal, ConnectionManagerImpl,
  MotdApplication.kt, di/AppModule.kt, push tests. Accept: coordinator/registrar/receiver
  suites green, legacy API gone.
- **WP-R3 — About + nav + link-open + README:** owns ui/about/, ui/settings/SettingsScreen.kt,
  ui/nav/NavGraph.kt, ui/chat/ChatScreen.kt (browser link change only), strings (About),
  LICENSE, README.md. Accept: build green, About preview renders, deep-jump wired end to end.
- **WP-R4 — lint zero + enforce (serial, last):** owns app/build.gradle.kts, manifest, res
  moves/resizes, MessageBubble.kt, IrcForegroundService.kt, di/IrcModule.kt,
  ChannelInfoScreen.kt (plurals only), strings (plurals/removals), ci.yml. Accept:
  `:app:lint` 0 warnings 0 errors with warningsAsErrors; full build + all tests green.

## Risks

1. Reverse-index off-by-one / ties → strict `(serverTime, id)` complement query + tie test +
   msgid post-scroll verify with one re-resolve.
2. Live message shifting index mid-jump → re-resolve once; highlight is msgid-keyed.
3. Jump loop starvation → impossible for local targets (mediator APPEND fires only after
   local exhaustion); 64-round cap + endOfPagination check degrade to snackbar.
4. Stale/deleted-network push instances → receiver validates row; unknown instances
   unregistered; coordinator reconciles `endpoints().keys − desired`.
5. Mode-revert races → revert only when endpoints empty; onRegistrationFailed stays full
   revert so no network is left with neither push nor socket.
6. Push teardown starves non-webpush DIRECT networks → v1 semantics kept, now gated on all
   webpush clients holding endpoints; documented limitation.
7. warningsAsErrors future breakage → versions pinned; GradleDependency disabled; new checks
   only arrive with deliberate upgrades where a red gate is wanted.
8. AROUND leaves a time gap vs newest page → offset paging renders contiguously; accepted;
   BETWEEN backfill is a candidate round-3 item.
9. Multiple distributors → auto-select first; distributor picker is follow-up.

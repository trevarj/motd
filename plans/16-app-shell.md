# 16 — Round 5: app shell, multi-server UX, and IRC-client gaps

Design for the server-centric app shell: a navigation drawer server selector, per-network
connect/disconnect controls, post-onboarding network management (add/edit/delete + soju bound
networks), a per-network server buffer, channel browsing (LIST), and nick/topic/moderation
actions. Contract amendments land in `plans/10-contracts.md` §"Round 5 amendments" (authored in
§6, copied verbatim by WP-V0). Frozen-contract discipline applies: WP-V0 lands every shared
signature and shared-file edit first; parallel WPs code against them verbatim.

Kotlin paths omit the `app/src/main/kotlin/io/github/trevarj/motd/` prefix unless stated.
`:irc` paths omit `irc/src/main/kotlin/io/github/trevarj/motd/irc/`.

**Coordination constraint:** a concurrent agent is fixing per-variant field correctness in
`ui/onboarding/*` and `ui/settings/NetworkForm.kt`. No WP in this round may edit those files.
This round *consumes* `NetworkForm`, `ServerForm`, `AuthForm`, `AuthMode`, `buildNetworkEntity`,
`toServerForm`, `toAuthForm`, `saslToAuthMode` as they exist; assume `NetworkForm` renders the
correct field set per variant.

## Scope

| Feature | v1 (this round) | Notes |
|---|---|---|
| Navigation drawer server selector (status dots, unread rollups, scoping) | yes | §3 |
| Per-network connect/disconnect/reconnect + sticky user intent | yes | §4 |
| Global go offline / go online | yes (drawer row) | in-memory intent, resets on process death |
| Add server from Settings (reuses NetworkForm + connect test) | yes | §5.4 |
| Edit/delete server (extend NetworkSettingsScreen: status header, autoConnect) | yes | §5.3 |
| soju bound-network management post-onboarding (LIST/ADD/DEL NETWORK) | yes | §5.5 |
| Server buffer (MOTD, server notices, numerics, errors) | yes | §5.6 |
| Channel list / browse + join (LIST + ELIST) | yes | §5.7 |
| Nick context actions: message, mention, whois, friend/fool | yes | §5.8 |
| Channel moderation (op/deop, voice, kick, ban) for ops | yes | §5.8 |
| Topic edit from ChannelInfo | yes | §5.8 |
| `/away`, `/whois`, `/list`, `/kick`, `/ban` composer commands | yes | §5.9 |
| Per-network nick display (drawer subtitle) | yes | from `IrcClientState.Ready.nick` |
| Away *display* (query header, member list badges) | later | needs a user-observe repo surface; small round later |
| Dedicated ignore/block list | later | fools (COLLAPSE/HIDE + notification silence) already covers the soft-ignore need |
| Per-buffer/per-network notification preferences beyond mute | later | per-conversation Android channels; separate round |
| Backoff countdown ("retrying in 12s") | later | ConnectionActor doesn't expose timer state; state labels suffice for v1 |
| Command palette / long-press command help sheet | later | composer `/` hint popup extended instead |
| INVITE surfacing (snackbar/notification) | later | event exists (`IrcEvent.Invited`), currently dropped |

No new dependencies. All components below exist in Compose BOM 2025.04.01 / material3:
`ModalNavigationDrawer`, `ModalDrawerSheet`, `NavigationDrawerItem`, `FilterChip`,
`SingleChoiceSegmentedButtonRow`/`SegmentedButton`, `Badge`. No Room schema change (v1 schema
already has `BufferType.SERVER`, `MessageKind.ERROR/SERVER_INFO`, `NetworkEntity.autoConnect`).

---

## 1. Code survey (facts this design builds on)

- `ConnectionManager` (service/ServiceSeam.kt) already has `connectionStates:
  StateFlow<Map<Long, IrcClientState>>`, `connect(networkId)`, `disconnect(networkId)`,
  `clientFor(networkId)`, `joinChannel`, `ensureQueryBuffer`, `markRead`.
- `IrcClientState` = `Disconnected | Connecting | Registering | Ready(nick, caps, isupport) |
  Failed(reason, fatal)`.
- **Reconcile bug this round must fix (§4):** `ConnectionManagerImpl.startAll()` collects
  `networkDao.observeAll()` and calls `reconcile(all.filter { it.autoConnect })` on every DB
  emission. A manual `disconnect(id)` is undone by the next DB write (actor re-created because
  `autoConnect == true`), and a manual `connect(id)` of an `autoConnect=false` network is torn
  down (its id is not in the wanted set). Also `connect(id)` no-ops for an existing dead actor
  (fatal-Failed actors stay in `actors` with a completed job; `ensureActor` sees an unchanged
  fingerprint and returns).
- `ConnectionActor` handles backoff/retry internally; a *fatal* `Failed` (e.g. SASL failure)
  ends the actor loop permanently — the reconnect affordance must rebuild the actor.
- `EventProcessor` is the sole IRC→Room writer. Today it drops `ServerError`, `Raw`,
  `Disconnected` ("not persisted") and routes *every* NOTICE whose target is our nick into a
  QUERY buffer keyed by source nick — including NOTICEs from servers (`*.libera.chat`), which
  creates junk query buffers. `BufferDao.observeChatList` excludes `type = 'SERVER'`.
- `ChatViewModel.submit()` executes `parseCommand(raw)`; unknown `/cmd` already passes through
  as a raw line. `COMMAND_HINTS = ["/me","/join","/part","/msg","/query","/nick","/topic"]`.
- Onboarding creates networks; `NetworkSettingsScreen` edits/deletes them (`NetworkSettingsViewModel`
  seeds forms via `observeNetworks().first()` because `NetworkRepository` lacks `byId`; `NetworkDao.byId`
  exists). soju children are mirrored by `EventProcessor.onBouncerNetworkState` from
  `BOUNCER NETWORK` notifies (add/update/delete with `attrs == "*"`).
- `EventMapper` maps LIST numerics (321/322/323) and WHOIS numerics (311..319, 330) to
  `IrcEvent.Raw`; 4xx/5xx become `ServerError`. `IrcClient.sendLabeled` returns the labeled
  response lines, or `emptyList()` immediately when `labeled-response` is missing.
- `ChatListViewModel` combines `observeChatList() + observeNetworks() + connectionStates +
  settings` into `ChatListState`; `ChatListScreen` renders `ConnectionBanner` + sectioned list
  (`sectionChatList`) with per-row pin/mute menu. Network chip shows when `networks.size > 1`.
- `ChannelInfoViewModel` has `messageMember`, `mentionMember`, `toggleFriend`, `toggleFool`;
  the member sheet offers Message/Mention/friend/fool.
- Lint is 0/0 (`warningsAsErrors`); versions pinned (plans/01); no new deps allowed.

---

## 2. Navigation model — decision

**Chosen: left navigation drawer (`ModalNavigationDrawer`) on the chat-list screen.**
Rejected: top dropdown.

Rationale:

- A soju account routinely holds 3–10+ bound networks plus DIRECT networks. Each entry needs
  a status dot, unread/mention rollup, current nick, and a context menu (connect/disconnect/
  reconnect/settings/server messages). A `DropdownMenu` anchored to the top bar cannot carry
  that density; a drawer sheet is built for exactly this (Discord/Slack server rail, Telegram
  folder drawer are the modern reference).
- The drawer also gives the shell its missing "app-level" surface: Add network, Go offline,
  Settings shortcut — none of which belong in a per-buffer list.
- `ModalNavigationDrawer` (not `PermanentNavigationDrawer`): MOTD is phone-first; the modal
  drawer costs no horizontal space and is the M3-canonical pattern with the top-bar Menu icon.
  A future tablet round can swap in `PermanentNavigationDrawer` behind the same
  `ServerDrawerContent` composable without redesign.
- The existing **unified chat list stays the default view** (`selectedNetworkId = null`); the
  drawer only *scopes* it. No second list implementation.

---

## 3. Server drawer — component design (WP-V1)

### 3.1 Structure

`ChatListScreen` wraps its existing `Scaffold` (new file `ui/chatlist/ServerDrawer.kt`):

```
ModalNavigationDrawer(drawerState, gesturesEnabled = true) {
  drawerContent = ModalDrawerSheet {
    Column(verticalScroll) {
      // Header: app name, small — matches SectionHeader style
      Text("MOTD", titleLarge, padding 16dp)
      // 1. All chats
      NavigationDrawerItem(
        icon = Icon(Icons.Outlined.Forum),
        label = "All chats",
        badge = unread rollup (Badge) when > 0,
        selected = selectedNetworkId == null,
        onClick = { select(null); close() })
      HorizontalDivider(vertical 8dp)
      // 2. One entry per network, DB `ordering` order; BOUNCER_CHILD rows
      //    indented (start padding +16dp) under their BOUNCER_ROOT
      for (row in drawerRows) DrawerNetworkItem(row)   // see 3.2
      HorizontalDivider(vertical 8dp)
      // 3. Footer actions (NavigationDrawerItem, unselected style)
      "Add network"      Icons.Filled.Add          -> onOpenAddNetwork()
      "Go offline"/"Go online"  Icons.Outlined.CloudOff / Cloud -> vm.goOffline()/goOnline()
      "Settings"         Icons.Outlined.Settings   -> onOpenSettings()
    }
  }
  content = { /* existing Scaffold */ }
}
```

- Top bar gains `navigationIcon = IconButton(Icons.Filled.Menu)` opening the drawer.
  Title stays "MOTD" when unscoped; shows the selected network's name when scoped.
- `BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }`.
- "Go offline" label flips to "Go online" when every network's state is absent/Disconnected.

### 3.2 `DrawerNetworkItem`

Custom row (not `NavigationDrawerItem` — needs status dot + subtitle + long-press):

- Layout: `Row(combinedClickable(onClick = select, onLongClick = menu), 56dp min height,
  RoundedCornerShape(28dp) selected background = secondaryContainer)`:
  - Status dot: 10dp `Box(background = statusColor(state), CircleShape)`.
  - `Column(weight 1)`: name (`titleSmall`, SemiBold); subtitle (`bodySmall`,
    onSurfaceVariant) = `Ready.nick`, or `"Connecting…"`/`"Registering…"`,
    or `Failed.reason` (error color, 1 line ellipsized), or `"Disconnected"`.
  - Trailing: `MentionBadge(count)` then `UnreadBadge(count)` (reuse `ui/components/Badges.kt`)
    when counts > 0.
- Status colors (private vals in `ServerDrawer.kt`; static, theme-independent semaphore):
  Ready → `Color(0xFF4CAF50)`; Connecting/Registering → `Color(0xFFFFB300)`;
  Failed → `MaterialTheme.colorScheme.error`; Disconnected/absent →
  `MaterialTheme.colorScheme.outlineVariant`.
- Long-press `DropdownMenu`:
  - "Disconnect" (state present and not Disconnected/Failed) → `vm.disconnect(id)`.
  - "Connect" (absent/Disconnected) or "Reconnect now" (Failed) → `vm.connect(id)`.
  - "Server messages" → `vm.openServerBuffer(id, onOpenBuffer)`.
  - "Network settings" → `onOpenNetworkSettings(id)`.
- BOUNCER_ROOT rows show the aggregate of their children's unread counts plus their own; the
  root's own row is still tappable (scopes to root + children).

### 3.3 Pure models (new file `ui/chatlist/DrawerModels.kt`, unit-tested)

```kotlin
/** One drawer entry. depth = 0 for DIRECT/BOUNCER_ROOT, 1 for BOUNCER_CHILD. */
data class DrawerRow(
    val networkId: Long,
    val name: String,
    val role: NetworkRole,
    val depth: Int,
    val state: IrcClientState,       // IrcClientState.Disconnected when absent from the map
    val nick: String?,               // (state as? Ready)?.nick
    val unread: Int,                 // sum of unreadCount over the network's non-muted rows
    val mentions: Int,               // sum of mentionCount over ALL the network's rows
)

/** ordering: networks in DB order, each BOUNCER_ROOT immediately followed by its children.
 *  BOUNCER_ROOT rows aggregate children counts into their own. */
fun buildDrawerRows(
    networks: List<NetworkEntity>,
    rows: List<ChatListRow>,
    states: Map<Long, IrcClientState>,
): List<DrawerRow>

/** null selection = all rows. Selecting a BOUNCER_ROOT includes its children's rows. */
fun scopeRows(
    rows: List<ChatListRow>,
    selectedNetworkId: Long?,
    networks: List<NetworkEntity>,
): List<ChatListRow>
```

### 3.4 ViewModel changes (`ui/chatlist/ChatListViewModel.kt`)

```kotlin
data class ChatListState(
    // ... all existing fields unchanged ...
    val selectedNetworkId: Long? = null,
    val drawerRows: List<DrawerRow> = emptyList(),
    val allUnread: Int = 0, val allMentions: Int = 0,   // "All chats" rollup
)
```

- Selection kept in a `MutableStateFlow<Long?>` seeded from `savedStateHandle` (key
  `"selected_network"`, written on change) so it survives config changes; folded into the
  existing `combine` (Kotlin's 5-ary `combine` overload exists; the current combine is 4-ary,
  add the selection flow as the 5th source). `state.rows` becomes
  `scopeRows(rows, selection, networks)`; `drawerRows = buildDrawerRows(...)`.
  If the selected network is deleted, reset selection to null (guard inside the combine).
- New actions:

```kotlin
fun selectNetwork(networkId: Long?)                          // updates flow + savedStateHandle
fun connect(networkId: Long)    = launch { connectionManager.connect(networkId) }
fun disconnect(networkId: Long) = launch { connectionManager.disconnect(networkId) }
fun goOffline() = launch { state.value.networks.forEach { connectionManager.disconnect(it.id) } }
fun goOnline()  = launch { state.value.networks.forEach { connectionManager.connect(it.id) } }
fun openServerBuffer(networkId: Long, onOpen: (Long) -> Unit) =
    launch { onOpen(connectionManager.ensureServerBuffer(networkId)) }
```

`goOnline()` may connect `autoConnect=false` networks the user never wanted; acceptable —
it is an explicit "connect everything" action (mirrors the sticky-intent semantics in §4).

### 3.5 Scoped-state affordances in the list body

- When `selectedNetworkId != null`, render under `ConnectionBanner`: a
  `Row(horizontal padding 16dp)` with one `FilterChip(selected = true, label = networkName,
  trailingIcon = Icons.Filled.Close)`; tapping it (or its close icon) calls
  `selectNetwork(null)`. This keeps scope discoverable/escapable without opening the drawer.
- `showNetworkChip` (per-row network tag) becomes `networks.size > 1 && selectedNetworkId == null`
  — redundant when scoped.
- Scoped empty state: existing `EmptyState` with new strings (`chatlist_scoped_empty_title/message`).
- `NewConversationSheet` gains `preselectedNetworkId: Long? = null`; `ChatListContent` passes
  the current selection. Inside the sheet: `selectedNetwork = networks.firstOrNull { it.id ==
  preselectedNetworkId } ?: networks.firstOrNull()`.
- `NewConversationSheet` join tab gains a tertiary `TextButton("Browse channels…")` under the
  input, enabled when the selected network is not a BOUNCER_ROOT (LIST is meaningless on the
  unbound soju root connection); invokes `onBrowseChannels(networkId)` →
  `ChannelListRoute(networkId)`.

The `ConnectionBanner` stays as-is (global aggregate); the drawer provides the per-network
detail. Post-disconnect states are absent from the map, so a user-disconnected network does
not pin the banner to "Connecting…".

---

## 4. Connect/disconnect semantics (WP-V0, `service/ConnectionManagerImpl.kt`)

Behavioral fixes, no interface change beyond `ensureServerBuffer` (§6):

1. **Sticky user intent.** Add `private val userIntents = ConcurrentHashMap<Long, Boolean>()`
   (true = force-connect, false = force-disconnect; absent = follow `autoConnect`).
   - `connect(id)`: `userIntents[id] = true`, then **rebuild** the actor
     (`actors.remove(id)?.stop(); fingerprints.remove(id); ensureActor(row)`) so a parked
     fatal-Failed actor actually reconnects.
   - `disconnect(id)`: `userIntents[id] = false` + existing removal.
   - `reconcile(rows)` signature becomes `reconcile(all: List<NetworkEntity>)` and computes
     `wanted = all.filter { userIntents[it.id] ?: it.autoConnect }` itself (the `startAll`
     collector passes the unfiltered list; `startAll`'s seed call passes
     `networkDao.observeAll().first()` equivalent — keep using `connectable()` for the seed
     plus the collector for live truth, but the collector no longer pre-filters).
   - `stopAll()` clears `userIntents` (service teardown resets intent).
   - Extract the wanted-set computation as a pure top-level function for tests:

   ```kotlin
   internal fun wantedNetworkIds(
       all: List<NetworkEntity>,
       userIntents: Map<Long, Boolean>,
   ): Set<Long>   // { id | (userIntents[id] ?: autoConnect) && (role != BOUNCER_CHILD || parentId != null) }
   ```

2. **`markRead` guard:** skip the `client.markRead(...)` send when
   `buffer.type == BufferType.SERVER` (target `"*"` is not a valid MARKREAD target); the Room
   `advanceReadMarker` still runs.

3. **`ensureServerBuffer(networkId): Long`** (new, mirrors `ensureQueryBuffer`): find
   `bufferDao.byName(networkId, "*")`, else insert
   `BufferEntity(networkId, name = "*", displayName = networkDao.byId(networkId)?.name ?: "Server",
   type = BufferType.SERVER)`. `"*"` is stable under both casemapping normalizers.

State surfacing points (all read `connectionStates`; absent id ⇒ Disconnected):

| Surface | What it shows |
|---|---|
| Drawer network row (§3.2) | dot + nick/state/reason subtitle + connect/disconnect/reconnect menu |
| ConnectionBanner (unchanged) | global worst-state one-liner |
| NetworkSettings status header (§5.3) | full state + reason + Connect/Disconnect button |
| Chat top bar, SERVER buffers (§5.6) | state text as subtitle |
| AddNetwork connect test (§5.4) | live state progression |

---

## 5. Screen/flow specs

### 5.1 NavGraph + routes (WP-V0)

New routes (§6) wired in `ui/nav/NavGraph.kt`:

```kotlin
composable<AddNetworkRoute> {
    AddNetworkScreen(
        onBack = { navController.popBackStack() },
        onOpenBouncerNetworks = { rootId ->
            navController.navigate(BouncerNetworksRoute(rootId)) {
                popUpTo<AddNetworkRoute> { inclusive = true }   // add-flow replaced by manager
            }
        },
    )
}
composable<BouncerNetworksRoute> { entry ->
    val route = entry.toRoute<BouncerNetworksRoute>()
    BouncerNetworksScreen(rootNetworkId = route.rootNetworkId, onBack = { navController.popBackStack() })
}
composable<ChannelListRoute> { entry ->
    val route = entry.toRoute<ChannelListRoute>()
    ChannelListScreen(networkId = route.networkId, onBack = { navController.popBackStack() })
}
```

Existing destinations gain pass-throughs (all new screen params are **default-valued** so WP-V0
compiles before the parallel WPs fill the bodies):

- `ChatListScreen(..., onOpenNetworkSettings = { navController.navigate(NetworkSettingsRoute(it)) },
  onOpenAddNetwork = { navController.navigate(AddNetworkRoute) },
  onOpenChannelList = { navController.navigate(ChannelListRoute(it)) })`
- `SettingsScreen(..., onOpenAddNetwork = { navController.navigate(AddNetworkRoute) })`
- `NetworkSettingsScreen(..., onOpenBouncerNetworks = { navController.navigate(BouncerNetworksRoute(it)) },
  onOpenBuffer = { navController.navigate(ChatRoute(it)) })`
- `ChatScreen(..., onOpenChannelList = { navController.navigate(ChannelListRoute(it)) })`

### 5.2 Settings entry points (WP-V2, `ui/settings/SettingsScreen.kt`)

Networks section: after the per-network `ListItem`s, add
`ListItem(headlineContent = "Add network", leadingContent = Icon(Icons.Filled.Add),
modifier = clickable { onOpenAddNetwork() })`. Each network row's `supportingContent` becomes
`"host:port"` plus a role suffix for soju rows (`" · soju"` for BOUNCER_ROOT,
`" · via <root name>"` for BOUNCER_CHILD). No `SettingsViewModel` change needed
(`state.networks` already carries role/parentId).

### 5.3 NetworkSettingsScreen extension (WP-V2)

`NetworkSettingsViewModel`:

```kotlin
data class NetworkSettingsUiState(
    val loaded: Boolean = false,
    val entity: NetworkEntity? = null,
    val server: ServerForm = ServerForm(),
    val auth: AuthForm = AuthForm(),
    val connState: IrcClientState = IrcClientState.Disconnected,   // new
    val autoConnect: Boolean = true,                               // new (edited live)
) { val canSave: Boolean get() = server.isValid && auth.isValid }
```

- `init(networkId)` switches to `networkRepository.networkById(networkId)` (new repo method,
  §6) and starts a collector mirroring `connectionManager.connectionStates` map entry for this
  id into `connState` (the ViewModel now also injects `ConnectionManager`).
- New actions: `fun connect()` / `fun disconnect()` (delegate to the manager);
  `fun setAutoConnect(enabled: Boolean)` — immediately persists
  `updateNetwork(entity.copy(autoConnect = enabled))` (reconcile reacts; §4 intent map means a
  live manual state is not clobbered).
- `save()` unchanged except it must persist the *current* `autoConnect` value too.

Screen layout (top to bottom):

1. **Status card** (`Card`/`ListItem` group): status dot + state label
   (`Ready as <nick>` / `Connecting…` / `Registering…` / `Disconnected` /
   `Failed — <reason>` in error color), trailing `FilledTonalButton` — "Disconnect" when live,
   "Connect" when Disconnected, "Reconnect" when Failed.
2. `SwitchRow("Connect automatically", subtitle)` bound to `autoConnect`.
3. For BOUNCER_ROOT: `ListItem("Bouncer networks", supporting = "Manage networks bound to this
   soju account")` → `onOpenBouncerNetworks(networkId)`.
4. For BOUNCER_CHILD: an informational `ListItem("Managed by <root name>", supporting =
   "Transport and login come from the soju connection")`; the `NetworkForm` still renders
   (nick/name edits are meaningful) — no field suppression this round.
5. `ListItem("Server messages")` → `viewModel.openServerBuffer(onOpenBuffer)` (calls
   `connectionManager.ensureServerBuffer(networkId)`).
6. Existing `NetworkForm(server, auth, ...)` + delete button + confirm dialog, unchanged.

Saving a transport-relevant field changes the fingerprint; `ConnectionManagerImpl.reconcile`
already restarts the actor on the next `observeAll` emission — call this out in the screen's
save snackbar copy ("Saved — reconnecting" when the network is live).

### 5.4 Add-network flow (WP-V2, new `ui/settings/addnetwork/`)

`AddNetworkScreen(onBack, onOpenBouncerNetworks)` + `AddNetworkViewModel`. Single screen, three
phases (no pager):

```kotlin
enum class AddNetworkPhase { FORM, TESTING, FAILED }

data class AddNetworkUiState(
    val kind: ConnectionChoice = ConnectionChoice.NETWORK,   // reuse enum from ui/onboarding
    val server: ServerForm = ServerForm(),
    val auth: AuthForm = AuthForm(),
    val phase: AddNetworkPhase = AddNetworkPhase.FORM,
    val networkId: Long? = null,          // created row during test
    val connState: IrcClientState? = null,
    val error: String? = null,
) {
    val isSoju: Boolean get() = kind == ConnectionChoice.SOJU
    val role: NetworkRole get() = if (isSoju) NetworkRole.BOUNCER_ROOT else NetworkRole.DIRECT
    val canSubmit: Boolean get() = phase == AddNetworkPhase.FORM && server.isValid && auth.isValid
}
```

ViewModel (injects `NetworkRepository`, `ConnectionManager`):

- `setKind(kind)` — switching to SOJU pins `auth = auth.copy(mode = AuthMode.PLAIN)` (same rule
  as `OnboardingReducer`); `editServer`, `editAuth` (re-pin PLAIN when soju).
- `submit()`: `addNetwork(buildNetworkEntity(server, auth, role, name = server.host))` →
  `connect(id)` → phase TESTING → collect `connectionStates[id]`:
  - `Ready` → if soju: `onOpenBouncerNetworks(id)`; else `onBack()` (row is saved & connected).
  - `Failed` → phase FAILED with `reason`.
- FAILED actions: `retry()` (delete the row like `OnboardingViewModel.retryConnect`, back to
  FORM with fields kept, resubmit on demand), `saveAnyway(onBack)` (keep the row; user can fix
  it later from NetworkSettings), `editForm()` (delete row, back to FORM).

Screen:

1. `TopAppBar("Add network", back arrow)`. Back during TESTING/FAILED first deletes the
   half-created row (`viewModel.abandon()` → delete + `onBack`).
2. `SingleChoiceSegmentedButtonRow`: "IRC network" / "soju bouncer" (maps to `setKind`).
3. `NetworkForm(server, auth, onServerChange, onAuthChange)` (both sections on).
4. Primary `Button("Connect & save", enabled = canSubmit)`.
5. TESTING: inline `ListItem` with `CircularProgressIndicator` + state text (Connecting →
   Registering → Ready), matching the status-dot vocabulary.
6. FAILED: error text (errorContainer surface) + `Row`: `TextButton("Edit")`,
   `TextButton("Save anyway")`, `Button("Retry")`.

TOFU cert prompts during the test are handled by the global `CertTrustDialogHost` (already
app-wide in MainActivity) — no extra work.

### 5.5 soju bound-network manager (WP-V2, new `ui/settings/bouncer/`)

`BouncerNetworksScreen(rootNetworkId, onBack)` + `BouncerNetworksViewModel`.

```kotlin
/** One row: a network known to the bouncer, merged with its local mirror (if imported). */
data class BouncerNetRow(
    val netId: String,
    val name: String,             // attrs["name"] ?: attrs["host"] ?: netId
    val host: String?,            // attrs["host"]
    val bouncerState: String?,    // attrs["state"]: "connected"/"connecting"/"disconnected"
    val childNetworkId: Long?,    // local BOUNCER_CHILD row id; null = not imported
)

data class BouncerNetworksUiState(
    val root: NetworkEntity? = null,
    val rootState: IrcClientState = IrcClientState.Disconnected,
    val rows: List<BouncerNetRow> = emptyList(),
    val loading: Boolean = false,
    val busyNetIds: Set<String> = emptySet(),   // per-row in-flight guard
    val error: String? = null,
)

/** Pure merge of the live bouncer listing with local child rows; unit-tested. */
fun mergeBouncerRows(
    listing: List<BouncerNetwork>,
    children: List<NetworkEntity>,
): List<BouncerNetRow>
```

ViewModel (injects `NetworkRepository`, `ConnectionManager`):

- `init(rootNetworkId)`: load `networkById`, collect `connectionStates[rootId]`; when Ready →
  `refresh()`. When not Ready, screen shows an inline "Connect to manage networks" card with a
  Connect button (`connectionManager.connect(rootId)`).
- `refresh()`: `clientFor(rootId)?.bouncerListNetworks()` (guard `runCatching`, surface
  `error`), `networkRepository.childrenOf(rootId)`, merge.
- `importNetwork(row)`: insert the local mirror the same way `EventProcessor.onBouncerNetworkState`
  does — `root.copy(id = 0, name = row.name, role = BOUNCER_CHILD, parentId = root.id,
  bouncerNetId = row.netId, host = row.host ?: root.host)` — then `refresh()`. The
  `(networkId, name)` uniqueness lives on buffers, not networks, so double-insert is possible:
  guard by re-reading `childrenOf` inside the action and no-oping when the netId is present.
- `removeLocal(row)`: `deleteNetwork(row.childNetworkId!!)` — local removal only (buffer rows
  cascade), the bouncer keeps the network. Copy must make this explicit.
- `deleteFromBouncer(row)`: confirm dialog → `clientFor(rootId)?.bouncerDeleteNetwork(row.netId)`
  → also delete the local child if present → `refresh()`. (The `BOUNCER NETWORK <id> *` notify
  would also mirror the delete; doing it directly avoids relying on notify timing.)
- `addNetwork(name, host, port?, nick?)`: build attrs map (skip blanks; soju attr keys:
  `name`, `host`, `port`, `nickname`) → `bouncerAddNetwork(attrs)` → after it returns,
  `refresh()`; if the notify-driven mirror has not created the child row yet, `importNetwork`
  it explicitly (idempotent per the guard above).

Screen: `TopAppBar("Bouncer networks")`; not-Ready card (per above) or:
`LazyColumn` of rows — status mini-dot from `bouncerState` string, name + host subtitle,
trailing: `Switch` bound to `childNetworkId != null` ("shown in MOTD" import toggle) +
overflow `DropdownMenu` with "Delete from bouncer" (error-colored, confirm dialog).
Footer `OutlinedButton("Add network to bouncer")` opens an `AlertDialog` with
name/host (+ optional port, nickname) `OutlinedTextField`s.

This is also the screen the AddNetwork soju path lands on (§5.4), replacing onboarding's
import step for post-onboarding use.

### 5.6 Server buffer (WP-V3: `data/sync/EventProcessor.kt` + `ui/chat/*`)

Storage: one `BufferEntity(name = "*", type = SERVER, displayName = network name)` per network,
created lazily by `EventProcessor` (internal `ensureServerBuffer(networkId, st)` helper using
the same insert as §4.3) or by `ConnectionManager.ensureServerBuffer` from the UI.
`observeChatList` keeps excluding SERVER rows; entry points are the drawer context menu and
the NetworkSettings row.

EventProcessor routing changes (all inserts via the existing `insertSystem`):

1. **Server NOTICEs**: in `onChat`, before the DM branch — if `e.kind == NOTICE` and
   `isServerSource(e.source.nick)` (`nick.isEmpty() || '.' in nick`), route to the server
   buffer (kind `NOTICE`, sender = source nick) instead of creating a QUERY buffer. Channel
   NOTICEs unaffected. NickServ/ChanServ (no dot) keep their query buffers.
2. **`IrcEvent.ServerError`** → server buffer, `MessageKind.ERROR`, sender = `""`,
   text = `"${code} ${text}"` (use `System.currentTimeMillis()`; the event has no ctx).
3. **`IrcEvent.Raw`** → persist a whitelist of informational numerics as
   `MessageKind.SERVER_INFO`, text = `params.drop(1).joinToString(" ")` (drop our nick):
   `001..004` (welcome), `251..255, 265, 266` (lusers), `375, 372, 376` (MOTD), `305, 306`
   (away toggled), `301` (RPL_AWAY, "<nick> is away: msg"), and the WHOIS set
   `311, 312, 317, 318, 319, 330, 338` (fallback surface when labeled-response is missing,
   §5.8). Everything else stays dropped. Keep the whitelist as a private `Set<String>`.
4. **`IrcEvent.Disconnected`** → server buffer, `SERVER_INFO`,
   `"disconnected" + (reason?.let { ": $it" } ?: "")`. (Cheap reconnect visibility in-history.)
5. **Notification guard**: `maybeNotify` returns early when `type == BufferType.SERVER`
   (a MOTD line containing the user's nick must not raise a mention notification).

Chat screen behavior for SERVER buffers (`ChatViewModel` + `ChatScreen`):

- Top bar: title = buffer displayName (network name), subtitle = connection state text
  (reuse the `connState` already in `ChatState`); avatar as-is. Tapping the title does not
  navigate to ChannelInfo for SERVER buffers.
- Composer: placeholder "Send a command…" (`chat_server_composer_hint`); in
  `ChatViewModel.submit`, when `state.value.buffer?.type == BufferType.SERVER` every
  submission is a raw line: strip one leading `/` if present, `IrcMessage.parse`, `client.send`.
  (`parseCommand` is bypassed; `/join #x` typed here still works since `JOIN #x` is the same
  wire line.) Parse failures surface as a snackbar ("Not a valid IRC command").
- Reactions/typing/reply affordances are naturally inert (no msgids/targets); hide the reply
  and react actions in the message sheet for SERVER buffers (`MessageActionSheet` gains a
  `isServerBuffer: Boolean = false` flag).

### 5.7 Channel list / browse (WP-V0 `:irc` + WP-V4 UI)

`:irc` addition (§6): `IrcClient.listChannels(mask, minUsers, cap)`:

- Build params: `LIST` + optional mask param + optional `">$minUsers"` param — the user-count
  filter is only appended when `isupport["ELIST"]?.contains('U', ignoreCase = true) == true`.
- With `labeled-response`: `sendLabeled(msg)`, parse `322` lines
  (`params = [me, channel, count, topic]`) into `ChannelListing(name, userCount, topic)`,
  truncate at `cap`.
- Without: send unlabeled, collect `events` (`IrcEvent.Raw` 322s) until a `323` or a 15s
  timeout (`withTimeoutOrNull`), same parsing, same cap. Implemented inside `IrcClient` so the
  raw-numeric fallback does not leak protocol into `:app`.
- Note: 321/323 are Raw-mapped today, so no EventMapper change is needed; the collection is on
  the raw stream. Because §5.6 whitelists do NOT include 321/322/323, LIST output never floods
  the server buffer.

UI (`ui/channellist/ChannelListScreen.kt` + `ChannelListViewModel`, stub landed by WP-V0):

```kotlin
data class ChannelListUiState(
    val networkId: Long = 0,
    val connState: IrcClientState = IrcClientState.Disconnected,
    val query: String = "",
    val listings: List<ChannelListing> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,        // distinguishes "no results" from "not fetched"
    val error: String? = null,
)
```

- `init(networkId)`: mirror `connectionStates[networkId]`; when Ready and `!loaded` →
  `fetch()` with defaults.
- `fetch()`: `clientFor(networkId)?.listChannels(mask = query.ifBlank { null }?.let { "*$it*" },
  minUsers = if (query.isBlank()) DEFAULT_MIN_USERS else null, cap = 2000)`; sort by
  `userCount` descending (pure `fun sortListings(...)`, unit-tested). `DEFAULT_MIN_USERS = 50`.
- Screen: `TopAppBar("Browse channels")`; search `OutlinedTextField` with search IME action →
  `fetch()`; `LazyColumn` rows: `ListItem(headline = name, supporting = topic 1-line,
  trailing = Text("$userCount"))`; tap → `viewModel.join(name)` =
  `connectionManager.joinChannel(networkId, name)` + snackbar "Joining <name>…" + `onBack()`
  (the buffer appears in the chat list on the JOIN echo). Not-Ready state: inline card with a
  Connect button (same pattern as §5.5). Loading: `LinearProgressIndicator` under the field.

Entry points: `NewConversationSheet` "Browse channels…" (§3.5) and the `/list` command (§5.9).

### 5.8 Nick actions, whois, moderation, topic edit (WP-V3)

**Shared nick sheet** — new `ui/chat/NickActionSheet.kt` (composable, stateless), used from
both the chat timeline and ChannelInfo:

```kotlin
@Composable
fun NickActionSheet(
    nick: String,
    isSelf: Boolean,
    isFriend: Boolean, isFool: Boolean,
    canModerate: Boolean,               // viewer has op in this channel (false in queries)
    whois: WhoisInfo?,                  // null while loading / unavailable
    onDismiss: () -> Unit,
    onMessage: () -> Unit, onMention: () -> Unit,
    onToggleFriend: () -> Unit, onToggleFool: () -> Unit,
    onOp: (grant: Boolean) -> Unit, onVoice: (grant: Boolean) -> Unit,
    onKick: () -> Unit, onBan: () -> Unit,
)
```

`ModalBottomSheet` layout: header (Avatar + nick + whois summary lines when available:
`realname`, `user@host`, `account`, `channels.joinToString(" ")`, idle/away), then action
`ListItem`s: Message, Mention (channel context only), Add/Remove friend, Add/Remove fool;
when `canModerate && !isSelf`: divider + Give/Take op, Give/Take voice, Kick (confirm dialog
with optional reason field), Ban (confirm dialog). Self shows no social/moderation rows.

**Whois** — new `ui/chat/Whois.kt` (pure parser + model, unit-tested):

```kotlin
data class WhoisInfo(
    val nick: String,
    val username: String? = null, val host: String? = null, val realname: String? = null,
    val server: String? = null, val serverInfo: String? = null,
    val account: String? = null,
    val channels: List<String> = emptyList(),
    val idleSecs: Long? = null, val signonEpochSecs: Long? = null,
    val awayMessage: String? = null,
)

/** Fold WHOIS numerics (311/312/301/317/319/330) from a labeled response; null when no 311/318. */
fun parseWhois(lines: List<IrcMessage>): WhoisInfo?
```

`ChatViewModel` (and `ChannelInfoViewModel`) addition:

```kotlin
private val _nickSheet = MutableStateFlow<NickSheetState?>(null)   // (nick, whois?)
val nickSheet: StateFlow<NickSheetState?>
fun openNickSheet(nick: String)   // sets state; if client hasCap("labeled-response"):
                                  //   launch { sendLabeled(WHOIS nick) -> parseWhois -> fold in }
                                  // else: client.send(WHOIS) raw; whois stays null and the sheet
                                  //   shows "Details in server messages" (numerics land there, §5.6.3)
fun dismissNickSheet()
```

`data class NickSheetState(val nick: String, val whois: WhoisInfo? = null)` lives in
`Whois.kt`. WHOIS wire message: `IrcMessage(command = "WHOIS", params = listOf(nick))` via
`sendLabeled`; wrap in `runCatching` (label timeout throws `IrcTimeoutException`).

**Entry points:**

- Chat timeline: `MessageBubble` gains `onSenderClick: (() -> Unit)? = null`; name text and
  avatar get `Modifier.clickable` when non-null (only on group-first bubbles where they render).
  `MessageList` threads it through; `ChatScreen` calls `viewModel.openNickSheet(sender)` for
  non-self senders.
- ChannelInfo: the existing member `ModalBottomSheet` (Message/Mention/friend/fool) is replaced
  by `NickActionSheet` with `canModerate` computed from the viewer's own member row.

**Moderation** — new pure helpers in `ui/channelinfo/Moderation.kt` (unit-tested):

```kotlin
/** True when [ownPrefixes] contains a mode at or above op ('~','&','@') per [prefixOrder]. */
fun canModerate(ownPrefixes: String, prefixOrder: String): Boolean
fun banMask(nick: String): String = "$nick!*@*"
```

Executors on `ChannelInfoViewModel` (chat reuses them through its own thin wrappers —
both send via `connectionManager.clientFor(networkId)?.send(...)`):

```kotlin
fun setMemberMode(nick: String, mode: Char, grant: Boolean)
    // MODE <channel> +o/-o/+v/-v <nick>
fun kick(nick: String, reason: String?)     // KICK <channel> <nick> [:reason]
fun ban(nick: String)                        // MODE <channel> +b <banMask(nick)>
```

Own prefixes come from the members list (`MemberEntity(nick == Ready.nick).prefixes`);
`prefixOrder` from `client.isupport.prefixModes` (fallback `"~&@%+"`, same constant already in
`MemberSectioning`). Errors (482 etc.) surface as `ServerError` → server buffer.

**Topic edit** — ChannelInfo header: an `IconButton(Icons.Outlined.Edit)` beside the topic
(CHANNEL buffers only) → `AlertDialog` with a multiline `OutlinedTextField` prefilled with
`buffer.topic.orEmpty()` → `viewModel.setTopic(text)` =
`client.send(IrcMessage(command = "TOPIC", params = listOf(buffer.name, text)))`. Always
offered (op requirements vary per channel; a 482 lands in the server buffer). The
`TopicChanged` echo updates Room and the header reactively.

### 5.9 Composer commands (WP-V3, `ui/chat/CommandParser.kt` + `ChatViewModel`)

`ChatCommand` additions (parser stays pure; tests extend `CommandParserTest`):

```kotlin
/** `/away [message]` — away with message; `/back` or bare `/away` clears. */
data class Away(val message: String?) : ChatCommand
/** `/whois nick` — open the nick sheet with WHOIS details. */
data class Whois(val nick: String) : ChatCommand
/** `/list` — open the channel browser for the current network. */
data object ChannelList : ChatCommand
/** `/kick nick [reason]` — current channel. */
data class Kick(val nick: String, val reason: String?) : ChatCommand
/** `/ban nick` — MODE +b nick!*@* on the current channel. */
data class Ban(val nick: String) : ChatCommand
```

Parser rules: `"away"` → `Away(rest.ifEmpty { null })`; `"back"` → `Away(null)`; `"whois"`,
`"kick"`, `"ban"` follow the `/msg`-style first-token split (`None` when the nick is missing;
`Kick` keeps the remainder as reason). `"list"` → `ChannelList`. `COMMAND_HINTS` gains
`"/away"`, `"/whois"`, `"/list"`, `"/kick"`, `"/ban"` (after `/topic`).

`ChatViewModel.submit(raw, onOpenBuffer)` gains a defaulted
`onOpenChannelList: (Long) -> Unit = {}` parameter (ChatScreen passes the nav lambda from §5.1):

- `Away` → `client.send(IrcMessage(command = "AWAY", params = listOfNotNull(cmd.message)))`
  (305/306 confirmations land in the server buffer via §5.6.3).
- `Whois` → `openNickSheet(cmd.nick)`.
- `ChannelList` → `networkId?.let(onOpenChannelList)`.
- `Kick`/`Ban` → the §5.8 executors, guarded to CHANNEL buffers (else no-op).

---

## 6. Round 5 amendments to plans/10

WP-V0 appends this block verbatim to `plans/10-contracts.md` and realizes it in code.
Everything here is a frozen signature; everything not listed is impl-internal.

### Routes (`ui/nav/Routes.kt`) — add

```kotlin
// Round 5 (plans/16): app shell / network management.
@Serializable data object AddNetworkRoute
@Serializable data class BouncerNetworksRoute(val rootNetworkId: Long)
@Serializable data class ChannelListRoute(val networkId: Long)
```

### NetworkRepository (`data/repo/Repositories.kt`) — add

```kotlin
interface NetworkRepository {
    // ... existing members ...
    /** Point read (drives NetworkSettings/Bouncer screens; delegates to NetworkDao.byId). */
    suspend fun networkById(id: Long): NetworkEntity?
    /** Local BOUNCER_CHILD mirrors of a soju root (delegates to NetworkDao.childrenOf). */
    suspend fun childrenOf(rootId: Long): List<NetworkEntity>
}
```

### ConnectionManager (`service/ServiceSeam.kt`) — add

```kotlin
/** Find-or-create the per-network SERVER buffer (name "*", displayName = network name);
 *  returns bufferId. UI entry for the server-messages timeline (plans/16). */
suspend fun ensureServerBuffer(networkId: Long): Long
```

### IrcClient (`irc/client/IrcClient.kt`) — add

```kotlin
/** One RPL_LIST (322) row. */
data class ChannelListing(val name: String, val userCount: Int, val topic: String)

class IrcClient {
    // ... existing members ...
    /**
     * LIST. [mask] filters server-side when given; [minUsers] appends the ELIST ">n" filter
     * only when ISUPPORT ELIST contains 'U'. Uses labeled-response when available; otherwise
     * collects raw 322s until 323 or a 15s timeout. Result truncated to [cap] rows.
     */
    suspend fun listChannels(mask: String? = null, minUsers: Int? = null, cap: Int = 2000): List<ChannelListing>
}
```

### Behavior amendments (no signature change)

- `ConnectionManagerImpl.connect(id)` force-rebuilds the actor (a fatal-Failed actor must
  reconnect); `disconnect(id)`/`connect(id)` record a sticky in-memory user intent that
  `reconcile` honors over `autoConnect` (cleared by `stopAll()`; not persisted).
- `ConnectionManagerImpl.markRead` skips the MARKREAD send (Room-only) for SERVER buffers.
- `EventProcessor` now persists: server-sourced NOTICEs (source nick empty or containing `.`),
  `ServerError` (kind ERROR), a whitelist of informational numerics from `Raw` (kind
  SERVER_INFO), and `Disconnected` markers — all into the per-network SERVER buffer.
  `maybeNotify` never fires for SERVER buffers. (`Raw`/`ServerError` remain "not persisted"
  outside the whitelist.)

---

## 7. Work packages

Dependency order: **WP-V0 (serial) → WP-V1 ∥ WP-V2 ∥ WP-V3 ∥ WP-V4 → WP-V5 (serial)**.
Ownership is file-exclusive per wave; WP-V0 runs alone and is the only package that touches
shared files (`Routes.kt`, `NavGraph.kt`, `strings.xml`, screen signatures). After WP-V0, no
two parallel packages share a file. Nobody edits `ui/onboarding/*` or
`ui/settings/NetworkForm.kt` (concurrent round). Acceptance for every WP includes
`./gradlew build` green (lint 0/0) and the listed unit tests passing.

### WP-V0 — contracts, shared plumbing, `:irc` LIST (serial)

**Owns:** `plans/10-contracts.md` (append §6 block), `ui/nav/Routes.kt`, `ui/nav/NavGraph.kt`,
`app/src/main/res/values/strings.xml`, `service/ServiceSeam.kt`,
`service/ConnectionManagerImpl.kt`, `data/repo/Repositories.kt`,
`data/repo/NetworkRepositoryImpl.kt`, `irc/.../client/IrcClient.kt`,
`irc/src/test/.../client/IrcClientTest.kt` (additions only),
new stub screens `ui/settings/addnetwork/AddNetworkScreen.kt`,
`ui/settings/bouncer/BouncerNetworksScreen.kt`, `ui/channellist/ChannelListScreen.kt`
(minimal `Scaffold` + `TopAppBar` + back arrow, no ViewModel),
new test `app/src/test/.../service/ConnectionIntentsTest.kt`.
Signature-only edits (default-valued params, no body changes) to
`ui/chatlist/ChatListScreen.kt` (`onOpenNetworkSettings: (Long) -> Unit = {}`,
`onOpenAddNetwork: () -> Unit = {}`, `onOpenChannelList: (Long) -> Unit = {}`),
`ui/settings/SettingsScreen.kt` (`onOpenAddNetwork: () -> Unit = {}`),
`ui/settings/NetworkSettingsScreen.kt` (`onOpenBouncerNetworks: (Long) -> Unit = {}`,
`onOpenBuffer: (Long) -> Unit = {}`), `ui/chat/ChatScreen.kt`
(`onOpenChannelList: (Long) -> Unit = {}`).

**Does:** §4 (user intents, connect rebuild, markRead guard, `ensureServerBuffer`), §5.1
(routes + NavGraph wiring incl. new destinations pointing at the stubs), §6 amendment block,
`listChannels` + `ChannelListing` (§5.7 `:irc` part), repo additions, and **all** new string
resources for the round (prefixes: `drawer_`, `chatlist_scoped_`, `network_settings_status_`,
`add_network_`, `bouncer_`, `channel_list_`, `nick_sheet_`, `whois_`, `chat_server_`,
`channelinfo_topic_edit_`; parallel WPs reference, never add).

**Acceptance:** build green with stubs reachable via nav; `wantedNetworkIds` unit tests
(autoConnect default, force-connect of autoConnect=false survives a reconcile input,
force-disconnect of autoConnect=true survives, orphan BOUNCER_CHILD excluded);
`IrcClientTest` additions: labeled LIST parse (321/322/323 in a labeled batch → listings),
raw fallback (no labeled-response cap → collect until 323), ELIST-U gating of the `>n` param,
cap truncation.

### WP-V1 — server drawer + chat-list scoping

**Owns:** `ui/chatlist/ChatListScreen.kt`, `ui/chatlist/ChatListViewModel.kt`,
`ui/chatlist/NewConversationSheet.kt`, new `ui/chatlist/ServerDrawer.kt`,
new `ui/chatlist/DrawerModels.kt`,
new tests `app/src/test/.../ui/chatlist/DrawerModelsTest.kt`.

**Does:** §3 in full (drawer, status dots, rollups, context menus, go offline/online, scope
chip, scoped empty state, sheet preselection + "Browse channels…" entry), ViewModel state/
actions (§3.4).

**Acceptance:** `buildDrawerRows` tests (root-aggregates-children counts, muted rows excluded
from unread but not mentions, absent state ⇒ Disconnected, child indent/order after its root);
`scopeRows` tests (null = identity, direct id filters, root id includes children); previews for
drawer content (fake state, no ViewModel); existing chat-list behavior (sections, pin/mute,
onboarding redirect) unchanged when unscoped.

### WP-V2 — network management (settings, add flow, bouncer manager)

**Owns:** `ui/settings/SettingsScreen.kt`, `ui/settings/SettingsViewModel.kt`,
`ui/settings/NetworkSettingsScreen.kt`, `ui/settings/NetworkSettingsViewModel.kt`,
`ui/settings/addnetwork/*` (screen from stub + new `AddNetworkViewModel.kt`),
`ui/settings/bouncer/*` (screen from stub + new `BouncerNetworksViewModel.kt` +
`BouncerModels.kt` for `BouncerNetRow`/`mergeBouncerRows`),
new tests `app/src/test/.../ui/settings/BouncerModelsTest.kt`,
`app/src/test/.../ui/settings/AddNetworkViewModelTest.kt` (coroutines-test + fake
ConnectionManager/NetworkRepository, same style as `ManageNicksViewModelTest`).
**Must not touch:** `ui/settings/NetworkForm.kt`, `ui/onboarding/*`.

**Does:** §5.2, §5.3, §5.4, §5.5.

**Acceptance:** `mergeBouncerRows` tests (imported/unimported merge by netId, listing-only and
child-only rows, name fallback chain); AddNetworkViewModel tests (soju pins SASL PLAIN, Ready
→ soju routes to bouncer manager / direct pops, Failed → retry deletes the row, abandon
deletes the row); NetworkSettings shows live state and Connect/Disconnect works against a fake
manager; autoConnect toggle persists immediately.

### WP-V3 — server buffer, commands, nick sheet, moderation, topic

**Owns:** `data/sync/EventProcessor.kt`, `ui/chat/CommandParser.kt`, `ui/chat/ChatViewModel.kt`,
`ui/chat/ChatScreen.kt`, `ui/chat/MessageList.kt`, `ui/chat/MessageActionSheet.kt`,
`ui/components/MessageBubble.kt`, new `ui/chat/NickActionSheet.kt`, new `ui/chat/Whois.kt`,
`ui/channelinfo/ChannelInfoScreen.kt`, `ui/channelinfo/ChannelInfoViewModel.kt`,
new `ui/channelinfo/Moderation.kt`,
tests: extend `app/src/test/.../ui/chat/CommandParserTest.kt`,
extend `app/src/test/.../data/sync/EventProcessorTest.kt`,
new `app/src/test/.../ui/chat/WhoisParserTest.kt`,
new `app/src/test/.../ui/channelinfo/ModerationTest.kt`.

**Does:** §5.6, §5.8, §5.9.

**Acceptance:** EventProcessor tests — server NOTICE routes to `"*"`/SERVER (and NickServ
NOTICE still creates a QUERY), ServerError → ERROR row, whitelisted numeric → SERVER_INFO row
with nick param dropped, non-whitelisted Raw still dropped, no notification for SERVER-buffer
mentions; parser tests for `/away` (with/without message), `/back`, `/whois`, `/list`,
`/kick` (reason optional), `/ban`, hint list updated; `parseWhois` folds
311/312/301/317/319/330 and returns null without 311/318; `canModerate` respects prefix order
(`@` yes, `%`/`+` no, `~`/`&` yes); `banMask`; SERVER-buffer submit sends raw lines and never
PRIVMSGs `"*"`.

### WP-V4 — channel browser UI

**Owns:** `ui/channellist/*` (screen from stub + new `ChannelListViewModel.kt` +
`ChannelListModels.kt` for `sortListings`),
new test `app/src/test/.../ui/channellist/ChannelListModelsTest.kt`.

**Does:** §5.7 UI.

**Acceptance:** `sortListings` test (userCount desc, stable for ties); not-Ready shows the
connect card; search re-fetches with `*query*` mask; join calls
`connectionManager.joinChannel` and pops; previews with fake listings.

### WP-V5 — close-out (serial)

**Owns:** anything left inconsistent; read-only elsewhere.
**Does:** full `./gradlew build` + lint; manual nav-flow checklist (drawer → scope → clear;
drawer → server messages; settings → add network (both kinds) → bouncer manager; browse →
join; sender tap → whois sheet; `/away`, `/list`, `/kick` paths); confirm no stub placeholder
copy remains; verify plans/10 Round 5 block matches the shipped signatures; note deviations
back into this document ("Amendments" section at the end, per house style).

---

## 8. Open product decisions (recommendations for user confirmation)

1. **Drawer vs top dropdown** — recommend **drawer** (§2). The whole round is specced on it.
2. **Global "Go offline/online" in the drawer** — recommend **include**; it is ~20 lines once
   sticky intents exist. Caveat to accept: intent is in-memory, so a process restart under
   PERSISTENT_SOCKET reconnects `autoConnect` networks again.
3. **Failed add-network test: offer "Save anyway"?** — recommend **yes** (keeps the row,
   user fixes it in NetworkSettings later); the alternative (always delete on failure) loses
   typed config on flaky networks.
4. **Ignore list** — recommend **no dedicated ignore in v1**; fools + `FoolsMode.HIDE` +
   notification silence already cover it. Revisit only if users ask for hard message dropping.
5. **Server NOTICE routing heuristic** (`nick empty or contains '.'` ⇒ server buffer) —
   recommend **accept**; it matches every mainstream client's behavior and keeps
   NickServ/ChanServ in queries. Edge case: a user whose nick contains a dot is impossible
   (RFC nicks disallow `.`).
6. **Channel browser default fetch** — recommend **auto-fetch top channels (≥50 users) on
   open when ELIST U is supported; otherwise wait for a search mask** (full LIST on Libera is
   ~25k channels). Cap 2000 rows either way.
7. **Moderation actions visibility** — recommend **hide entirely when the viewer lacks op**
   (cleaner than disabled rows; halfops intentionally excluded from `canModerate` — they get
   errors too often to be worth special-casing in v1).

## 9. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Sticky-intent map races with `reconcile` (DB emission between `disconnect()` and map write) | Both run on the manager's single `scope` (Dispatchers.Default) but not serialized; keep `userIntents` a `ConcurrentHashMap`, write the intent *before* touching actors in `connect`/`disconnect`, and make `reconcile` idempotent (it already is). Worst case is one extra actor rebuild. |
| Concurrent onboarding/NetworkForm round lands mid-wave | Hard file fence (§ preamble + per-WP "must not touch"); WP-V2 consumes only the `NetworkForm`/`ServerForm`/`AuthForm` public shapes, which that round keeps stable. If `buildNetworkEntity`'s signature changes, WP-V5 reconciles. |
| LIST fallback floods `IrcClient.events` (4096 DROP_OLDEST) on huge networks without labeled-response | The fallback collects concurrently (subscribe before send), caps rows, and stops at 323/15s; chat events dropped during a monster burst are recovered by CHATHISTORY catch-up. Also mitigated by the ≥50-users default filter (decision 6). |
| soju root vs child confusion for LIST/whois (root connection is unbound) | Browse entry is disabled for BOUNCER_ROOT (§3.5); drawer scoping naturally hands child networkIds to the sheet. |
| `bouncerAddNetwork` notify-mirror race duplicating child rows | Import path re-reads `childrenOf` inside the action and no-ops on an existing `bouncerNetId`; delete path removes both sides explicitly. |
| Server buffer accumulates unbounded MOTD/notice rows | Bounded numeric whitelist; no images/previews render for SERVER_INFO/ERROR kinds; acceptable for v1 (retention/pruning is a data-layer round). |
| WHOIS label timeout (30s) blocks the sheet | `runCatching` + sheet renders actions immediately with whois=null; details fill in when the response lands. |
| `Failed.reason` strings leak into UI unlocalized | They already do (ConnectionBanner); accepted — server-provided text is shown verbatim in drawer subtitles and status headers. |
| Drawer badge counts diverge from list badges | Same source (`ChatListRow` sums) via pure `buildDrawerRows`, unit-tested against the sectioning fixtures. |

## Confirmed decisions (user, binding)

1. Server selector: **left ModalNavigationDrawer** (not dropdown).
2. Scope: **full v1** — implement WP-V0…WP-V5 in their entirety (drawer + scoping,
   connect/disconnect + reconcile-bug fix, add-server + edit/delete + soju bound-network
   manager, server/raw buffer, channel-list browser, nick sheet/whois/moderation/topic edit,
   command surface).
3. Global go offline/online in the drawer: **include**.
4. "Save anyway" on a failed add-network connect test: **yes**.
5. Dedicated ignore list: **no** (fools + HIDE covers v1).
6. Channel browser default: **auto-fetch ≥50-user channels when ELIST U, else require a
   search mask; cap 2000**.
7. Moderation actions when not op: **hidden**.

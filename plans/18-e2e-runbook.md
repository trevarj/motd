# 18 — End-to-End UI Runbook (physical device, adb + uiautomator)

Repeatable, exhaustive UI acceptance run for MOTD driven from a host machine
against a physical Android device via `adb` and `uiautomator`, connecting to a
real soju test bouncer. This markdown is the human-readable source of truth; an
executable bash/uiautomator harness (WP-B, §9) mirrors it step-for-step.

Opus agents implement the harness and the testTag additions from this doc. Every
selector, string, and assertion below is cross-checked against the current code.

---

## 0. Environment & constants

| Key | Value |
| --- | --- |
| App id (release) | `io.github.trevarj.motd` |
| App id (debug) | `io.github.trevarj.motd.debug` (`applicationIdSuffix = ".debug"`) |
| Launcher activity | `io.github.trevarj.motd.MainActivity` |
| Bouncer host | `104.168.59.26` port `6697` TLS |
| Bouncer account | `motd` / `ApPTeSt123` |
| Test nick | `motdadb` |
| Provisioned bouncer network | `libera` (irc.libera.chat) |
| Seed channel | `##motdtest` (see §8) |
| Second identity for DM/mention | `motdadb2` on the same bouncer account or a plain libera nick |

The app package under test is a build choice: the harness reads `PKG` from env
(default `io.github.trevarj.motd.debug` because CI builds debug APKs). All
`adb shell am`, `pm`, and dump filters use `$PKG`.

Cert-trust: the soju endpoint presents a self-signed cert. First connect raises
the TOFU `CertTrustDialog` (§1, Onboarding step 5 / global host). The harness
must trust it before registration completes.

### Launch & reset primitives

```sh
adb shell pm clear "$PKG"                       # full state wipe (see §8 teardown)
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1
# or explicit:
adb shell am start -n "$PKG/io.github.trevarj.motd.MainActivity"
```

`pm clear` drops the Room DB, DataStore prefs (settings, cert-trust store,
friends/fools, nick overrides), and the webpush/push registration. A cleared app
launches straight into onboarding because `ChatListScreen` redirects to
`OnboardingRoute` when `state.networks.isEmpty()` (see `ChatListScreen.kt:94`).

---

## 1. Coverage matrix

Every route in `Routes.kt` and every interaction on its screen, with the
correctness assertion (what must hold). Selector column notes the reliable
uiautomator handle: **T** = visible text, **CD** = contentDescription, **tag** =
testTag to be added (§7). A logcat crash check (`assert_no_crash`, §9) runs after
**every** step in addition to the assertion listed.

### 1.1 Onboarding — `OnboardingRoute` (`OnboardingScreen.kt`)

6-page `HorizontalPager`, `userScrollEnabled=false` (programmatic only). Steps:
`WELCOME → CHOICE → SERVER → AUTH → CONNECT → FINISH`. Two paths from CHOICE:
SOJU (bouncer) and NETWORK (direct). Wizard bottom bar: **Back** (hidden on
WELCOME), and a forward button whose label is "Get started" (WELCOME) / "Finish"
(FINISH) / "Next" (others). Forward button enabled only when `state.canAdvance`.

| Feature / interaction | Selector | Correctness assertion |
| --- | --- | --- |
| Welcome page renders | T `Welcome to motd`, T `A modern IRC client, built for the way you chat.` | both strings present; forward button T `Get started` present; **Back** absent |
| Advance from welcome | tap T `Get started` | CHOICE page: T `How do you connect?` present |
| Choice: soju card | T `I have a soju bouncer`, T `Multi-network sync, infinite history, and push over one account.` | card present; tapping selects SOJU (auth pinned to PLAIN downstream) |
| Choice: network card | T `Connect to an IRC network`, T `Connect directly to a single IRC server.` | present |
| Libera preset | T `Use Libera.Chat`, T `irc.libera.chat · 6697 · TLS` | tapping sets choice=NETWORK and fills host `irc.libera.chat` / port `6697` / TLS on |
| Choice → next gated | forward T `Next` | disabled until a choice is chosen (`canAdvance` false at CHOICE with no choice) |
| Server step (soju) header | T `Authentication` for soju is separate; SERVER shows host/port/TLS + identity | field labels **Host**, **Port**, **Use TLS**, **Nickname**, **Username**, **Real name** present |
| Host field | field label T `Host` | accepts `104.168.59.26`; **needs fresh dump after IME opens** |
| Port field | field label T `Port` | digit-filtered; default `6697` with TLS on |
| TLS toggle | T `Use TLS` (Switch) | toggling to plain re-defaults port to `6667` when port was default |
| Nick field | T `Nickname` | required; empty blocks advance |
| Server → next gated | forward T `Next` | enabled only when host non-blank AND port in 1..65535 AND nick non-blank |
| Auth (soju) | header T `Authentication`; fields T `Username`, T `Password` | soju auth is always SASL PLAIN, no mechanism picker; username+password required to advance |
| Auth (direct) mechanism picker | radio T `None`, T `SASL PLAIN (password)`, T `SASL EXTERNAL (client certificate)` | selecting PLAIN reveals T `SASL username` + T `Password`; EXTERNAL reveals T `Choose certificate` |
| Password show/hide | CD `Show password` / `Hide password` | toggles visibility |
| Auth → next gated | forward T `Next` | NONE always ok; PLAIN needs user+pass; EXTERNAL needs cert alias |
| Connect step: connecting | T `Connecting` (title), state log rows | spinner + one of `Connecting…` / `Registering…` / `Starting…` |
| **Cert-trust prompt** | dialog T `Trust this certificate?`, CD via buttons T `Trust` / T `Cancel` | dialog appears on self-signed soju cert; shows T `Server`, T `SHA-256 fingerprint`, T `Subject`, T `Issuer`, T `Valid from`, T `Valid until`; **Trust** dismisses and connection proceeds |
| Connect: ready | T starting with `Connected as ` (state indicator) | reaches `IrcClientState.Ready`; forward button enabled |
| Connect: failed + retry | T `Failed`, button T `Retry` | on failure a reason is shown and **Retry** re-runs `runConnectTest` |
| Bouncer networks section (soju + ready) | T `Bouncer networks`, T `Toggle networks to import.` | list of bouncer nets with a Switch each; `libera` row present |
| Toggle a bouncer network | Switch on the `libera` row | flips selected flag |
| Add bouncer network form | T `Add network`, fields T `Network name`, T `Host` | button T `Add network` enabled only when both non-blank; adds a row |
| Finish | forward T `Finish`, page title T `Finish` + green check | tapping imports selected bouncer nets as BOUNCER_CHILD rows, calls `onDone` → pops to ChatList |
| Back navigation within wizard | T `Back` | returns to previous step; system/predictive-back also steps back |

### 1.2 Chat list — `ChatListRoute` (`ChatListScreen.kt`, `ChatListRowItem.kt`)

`CenterAlignedTopAppBar` title = scoped network name or app name `motd`. Nav icon
opens drawer. Actions: search, settings. FAB opens new-conversation sheet.
`ConnectionBanner` under the bar; `ScopeChip` when a network is selected.

| Feature / interaction | Selector | Correctness assertion |
| --- | --- | --- |
| Top bar title | T `motd` (unscoped) | present when no network scope |
| Open drawer (icon) | CD `Open navigation drawer` | drawer slides in; T `All chats` visible |
| Open drawer (edge swipe) | swipe from left edge | drawer opens (same content) |
| Search action | CD `Search` | navigates to Search screen |
| Settings action | CD `Settings` | navigates to Settings screen |
| New-conversation FAB | CD `New conversation` | opens sheet with T `Join channel`, T `Message user`, T `Browse channels…` |
| Empty state (no chats, has networks) | T `No conversations yet`, T `Connect to a network to start chatting.` | shown when rows empty and networks present |
| Scoped empty state | T `No chats here yet`, T `This network has no conversations. Join a channel or start a message.` | shown when a network is selected and it has no rows |
| Row anatomy | row shows displayName + `sender: text` preview + relative time | channel rows prefix preview with sender; query rows do not |
| Unread badge | badge text = count or `99+` | `UnreadBadge` shows when `unreadCount > 0` |
| Mention badge | badge text = `@` or `@N` | `MentionBadge` shows when `mentionCount > 0`, before unread |
| Muted row | row dimmed (alpha 0.55) + `NotificationsOff` glyph | after Mute action |
| Friend row | trailing `Star` glyph + tinted name | QUERY row whose nick ∈ friends |
| Network chip | `NetworkChip` text = network name | shown only when `networks.size > 1 && selectedNetworkId == null` |
| Pinned section header | T `PINNED` (uppercased `chatlist_pinned`) | present when any pinned rows |
| Friends section header | T `FRIENDS` (uppercased) | present when any friend query rows |
| Fools section header | T like `FOOLS (N)` (uppercased), tappable | collapses/expands fool rows (default collapsed) |
| Row tap | tap row | opens ChatScreen for `bufferId` |
| Row long-press menu | long-press row → items T `Pin`/`Unpin`, T `Mute`/`Unmute`, T `Delete chat` | menu appears; labels reflect current state (**note:** Pin/Unpin/Mute/Unmute are hardcoded literals, not resources) |
| Pin toggle | menu T `Pin` | row moves to Pinned section; menu now shows `Unpin` |
| Mute toggle | menu T `Mute` | row dims + bell-off; menu now shows `Unmute` |
| Delete (menu) | menu T `Delete chat` → dialog | confirm dialog T `Delete chat?` with channel/DM-specific body |
| Delete (swipe) | swipe end-to-start on row | red `errorContainer` background w/ trash CD `Delete chat`; release arms confirm dialog (row snaps back, never silently deleted) |
| Delete confirm (channel) | dialog body `This leaves %s and permanently removes its history from this device.`, buttons T `Delete` / T `Cancel` | Delete removes buffer + parts channel |
| Delete confirm (DM) | dialog body `This permanently removes %s and its history from this device.` | Delete removes buffer |
| Scope chip | chip T = network name + CD `Clear network filter` (Close icon) | tapping clears scope back to unified |

### 1.3 Server drawer — inside ChatListRoute (`ServerDrawer.kt`)

`ModalNavigationDrawer` content. Header T `motd`. Sections: All chats; per-network
rows (children indented under soju root); footer Add network / Go offline-online /
Settings.

| Feature / interaction | Selector | Correctness assertion |
| --- | --- | --- |
| All-chats entry | T `All chats` + rollup badges | selected when `selectedNetworkId == null`; shows aggregate mention+unread badges |
| Per-network row | row T = network name, subtitle = nick / state | subtitle: Ready→nick, else `Connecting…` / `Registering…` / `Disconnected` / failure reason |
| Status dot | 10dp dot color | green Ready, amber Connecting/Registering, error Failed, muted Disconnected (color, not text — assert via screencap or state text) |
| Select network (tap row) | tap row | closes drawer, scopes list to that network (ScopeChip appears) |
| Per-network unread/mention badges | badges on row | reflect that network's rollups |
| Long-press network → menu | long-press row → items | live conn shows T `Disconnect`; disconnected shows T `Connect`; failed shows T `Reconnect now`; plus T `Server messages`, T `Network settings` |
| Connect | menu T `Connect` | triggers connect; subtitle transitions Connecting→Registering→nick |
| Disconnect | menu T `Disconnect` | subtitle → `Disconnected`, dot muted |
| Reconnect | menu T `Reconnect now` (failed row) | re-runs connect |
| Server messages | menu T `Server messages` | opens the SERVER buffer chat; closes drawer |
| Network settings | menu T `Network settings` | navigates to NetworkSettings |
| Add network (footer) | T `Add network` | navigates to AddNetwork |
| Go offline / online | T `Go offline` (online) / T `Go online` (offline) | toggles all networks; label + icon flip (`allOffline`) |
| Settings (footer) | T `Settings` | navigates to Settings |

### 1.4 Chat screen — `ChatRoute` (`ChatScreen.kt`, `MessageList.kt`, components)

Top bar: back, clickable title (avatar + displayName + subtitle), fool toggle
(conditional), search. Subtitle = typing text, else member count, else none.
Composer at bottom, anchored above IME.

| Feature / interaction | Selector | Correctness assertion |
| --- | --- | --- |
| Back | CD `Back` | pops to ChatList |
| Title/subtitle | T = buffer displayName; subtitle T `N members` or typing | title = displayName; channel subtitle = member count plural |
| Open channel info | tap title area | navigates to ChannelInfo (disabled on SERVER buffer) |
| Search action | CD `Search` | navigates to Search scoped to this buffer |
| Fool toggle (COLLAPSE + fools present) | CD `Show fools' messages` / `Hide fools' messages` | toggles global fool expansion |
| Bubble render (COMFORTABLE/COZY) | message bubbles | own messages right/primaryContainer; others left/surfaceContainerHigh |
| Compact render (COMPACT) | single-line `nick: text` | after switching Message style to Compact, rows render one-line, no bubbles/avatars |
| Sender grouping | sender header only on group-first | new header when sender changes, kind switches, or >3min gap |
| Day separator | pill T `Today` / `Yesterday` / `MMMM d, yyyy` | inserted at day boundary |
| Read-marker divider | T `— New messages —` | shown below first message newer than frozen read marker |
| System-event pill (single) | pill text e.g. `alice joined` | rendered as pill, not a bubble |
| System-event pill (run collapse) | pill T like `3 joined · 1 left` | consecutive events collapse; tapping a multi-event run expands to individual lines |
| Reactions: quick add | long-press message → emoji row `👍 ❤️ 😂 😮 😢` | tapping an emoji adds a reaction chip under the bubble |
| Reactions: more | long-press → T `More reactions` (CD, `＋`) | expands 64-emoji grid; tapping one adds it |
| Reaction toggle/no-op | tap own reaction chip | no-op (add-only in v1); tap others' chip adds your reaction |
| Reply | long-press → T `Reply` | composer shows T `Replying to %s` bar with CD `Cancel reply` |
| Copy / Quote | long-press → T `Copy`, T `Quote` | Copy copies text; Quote prefills composer |
| Deep-jump highlight (from search) | navigate with jumpToMsgid | target message pulses (~1.6s); if not loaded, snackbar T `Message not loaded` |
| Inline image → viewer | tap inline image | opens ImageViewer (fade transition) |
| Link → browser | tap linkified URL | fires `ACTION_VIEW` (assert via logcat intent / package resolver, not screen) |
| Link preview card | preview card under message | tapping opens URL in browser |
| Fools collapse placeholder | row T `%s · hidden` | tap expands that fool's message for the session |
| Fools re-collapse | row T `%s · hide again` | tap re-hides |
| Friend tint | sender name tinted + `Star` glyph | for friend senders on group-first bubble |
| Typing indicator | subtitle `%s is typing…` / `%s and %s are typing…` / `%s, %s and others are typing…` | appears when remote nick types (needs the second identity, §8) |
| Pending message | clock icon + CD `Sending…` | while awaiting echo (`pendingLabel != null`) |
| Failed + retry | error icon + CD `Failed`; row buttons T `Retry` (Refresh) / T `Delete` (DeleteOutline) | on echo timeout; Retry resends, Delete drops the pending row |
| Scroll-to-bottom FAB | CD `Scroll to bottom` + unread count badge (or `99+`) | appears when scrolled up; tap animates to newest (index 0) |
| Mark-read | on resume / scroll to bottom | read marker advances (assert via `— New messages —` divider disappearing on re-entry) |
| Empty state | T `No messages yet`, T `Say hello to start the conversation.` | new/empty buffer |
| History load footer | T `Couldn't load older messages` (error) / T `Beginning of history` (end) | on paging append end/error |
| NOTICE bubble | label T `notice` above body | for NOTICE kind |

### 1.5 Composer & commands (`Composer.kt`, `CommandParser.kt`, `ComposerAutocomplete.kt`)

Placeholder T `Message` (normal) / T `Send a command…` (SERVER). Send CD `Send`.
Enabled only when connection Ready. maxLines 6, sentence capitalization.

| Feature / interaction | Selector | Correctness assertion |
| --- | --- | --- |
| Type + send | input then CD `Send` | message appears (pending→confirmed); list scrolls to bottom |
| Send disabled when blank | CD `Send` | disabled on empty text |
| Nick autocomplete | type `al` (or `@a`) in a channel | dropdown lists prefix-matching nicks (recent speakers first, max 10); tapping inserts `nick: ` (line start) or `nick ` (mid-line) |
| Command autocomplete | type `/` | dropdown lists `/me /join /part /msg /query /nick /topic /away /whois /list /kick /ban`; tapping fills `<cmd> ` |
| `/me text` | send `/me waves` | ACTION rendered (italic; `* nick waves`) |
| `/join #ch` | send `/join ##motdtest` | joins channel; buffer opens/updates |
| `/part [reason]` | send `/part bye` | leaves channel |
| `/msg nick text` | send `/msg motdadb2 hi` | navigates to the QUERY buffer for the nick |
| `/query nick` | send `/query motdadb2` | opens QUERY buffer |
| `/nick new` | send `/nick motdadb_x` | nick change reflected in drawer subtitle / server messages |
| `/topic text` | send in a channel | topic updates (ops-gated on server) |
| `/whois nick` | send `/whois motdadb2` | opens NickActionSheet with whois summary |
| `/away [msg]` / `/back` | send `/away brb`, then `/back` | away set/cleared (server messages / no crash) |
| `/list` | send `/list` | opens channel browser for the network |
| `/kick nick [reason]` | send in channel (op) | kick issued (op-gated) |
| `/ban nick` | send in channel (op) | MODE +b applied (op-gated) |
| unknown `/cmd args` | send `/quote PING x` style unknown | sent as raw line (leading slash stripped) |
| `//text` escaped | send `//literal` | sent as literal `/literal` message |
| SERVER buffer raw send | in SERVER buffer, send text | one leading `/` stripped, raw-sent; invalid → snackbar T `Not a valid IRC command` |
| Auto-capitalize | type first letter | IME sentence-case (advisory; assert no crash) |
| Keyboard anchoring | open IME | composer stays above IME; **fresh dump required** after IME opens |

### 1.6 Nick action sheet (`NickActionSheet.kt`, `Whois.kt`)

Opened by tapping a sender in a channel (non-self, group-first bubble) or member
in ChannelInfo, or `/whois`.

| Feature / interaction | Selector | Correctness assertion |
| --- | --- | --- |
| Message | T `Message` | opens QUERY buffer for the nick |
| Mention (channel only) | T `Mention` | prefills composer with the nick mention |
| Add/Remove friend | T `Add to friends` / `Remove from friends` | toggles friend membership; chat-list star appears |
| Add/Remove fool | T `Add to fools` / `Remove from fools` | toggles fool membership |
| Whois summary | rows `%s@%s`, `Account: %s`, `Server: %s`, `Channels: %s`, `Idle: %s`, `Away: %s` | shows parsed whois; fallback T `Details in server messages` |
| Op-gated: Give/Take op | T `Give op` / `Take op` | present only when local user can moderate; issues MODE |
| Op-gated: Give/Take voice | T `Give voice` / `Take voice` | present only when moderator |
| Kick | T `Kick` → dialog title `Kick %s?`, hint `Reason (optional)`, confirm T `Kick`, cancel T `Cancel` | kicks nick |
| Ban | T `Ban` → dialog title `Ban %s?`, confirm T `Ban` | bans nick |

### 1.7 Channel info — `ChannelInfoRoute` (`ChannelInfoScreen.kt`)

Title T `Channel info`. Header: 88dp avatar, displayName, topic (+ edit), member
count. Action row: Mute, Pin, Leave. Member list sectioned by prefix.

| Feature / interaction | Selector | Correctness assertion |
| --- | --- | --- |
| Header | T = channel displayName; T `N members` plural | present |
| Topic edit | CD `Edit topic` (icon) → dialog T `Edit topic`, field T `Channel topic`, T `Save` / T `Cancel` | Save sends TOPIC (ops-gated) |
| Mute / Unmute | T `Mute` / `Unmute` | toggles buffer mute; reflected in chat list |
| Pin / Unpin | T `Pin` / `Unpin` | toggles pin; chat-list Pinned section |
| Leave | T `Leave` → dialog T `Leave channel?`, body `You will stop receiving messages from this channel.`, T `Leave` / T `Cancel` | Leave parts channel |
| Member sections | headers `~` `&` `@` `%` `+` glyphs; regular header T `Members` | members grouped by highest prefix |
| Member row | row `prefix+nick`; friend star | tap opens NickActionSheet |
| Fools section | header T `Fools (N)`, collapsible | expands/collapses; dimmed when collapsed |

### 1.8 Channel browser — `ChannelListRoute` (`ChannelListScreen.kt`)

Title T `Browse channels`. Search field T `Search channels`. Gated behaviors per
soju/root/connected state.

| Feature / interaction | Selector | Correctness assertion |
| --- | --- | --- |
| Not connected | T `Connect to browse channels`, button T `Connect` | Connect button present for non-root |
| Soju root can't browse | T `This soju account can't browse channels. Open one of its networks instead.` | shown for BOUNCER_ROOT |
| Search field | T `Search channels` (placeholder) | IME Search triggers LIST |
| Gated empty | T `Browse channels` + T `Search for a channel to browse this network.` | shown when server lacks ELIST `U` and no mask yet |
| Loading | LinearProgressIndicator | during LIST |
| No results | T `No channels found` | empty result set |
| Result row | headline = channel name; supporting = topic; trailing = user count | present per listing |
| Join | tap row | snackbar T `Joining %s…`; buffer joined |

### 1.9 Search — `SearchRoute` (`SearchScreen.kt`)

Query field in top bar T `Search messages`. Filter chips when buffer-scoped.

| Feature / interaction | Selector | Correctness assertion |
| --- | --- | --- |
| Prompt state | T `Search your history`, T `Type to search. Use from:nick to filter by sender.` | shown on blank query |
| FTS query | type a known term | results grouped by buffer, header `%s · %s` (buffer · network) |
| `from:nick` filter | type `from:motdadb2 hi` | results filtered to that sender |
| No results | T `No results`, T `Try a different query or filter.` | on empty result |
| Scope chips | T `All buffers`, T `This buffer` | present only when opened from a buffer; toggle scope |
| Result row anatomy | sender + matched text (bold) + relative time | present |
| Jump | tap a result | navigates to ChatRoute with jumpToMsgid/time; deep-jump pulse in chat |

### 1.10 Settings — `SettingsRoute` (`SettingsScreen.kt`)

Ordered sections: Appearance, Chat, People, Message delivery, Networks, About.
Title T `Settings`, back CD `Back`.

| Feature / interaction | Selector | Correctness assertion |
| --- | --- | --- |
| Theme mode | radios T `System default`, T `Light`, T `Dark`, T `AMOLED (true black)` | selecting AMOLED yields true-black background (assert via screencap of background pixel or no-crash + persisted setting) |
| Dynamic color | switch T `Dynamic color` (+ desc) | toggles Material You (API 31+) |
| Message style | radios T `Compact` / `Comfortable` / `Cozy` (+ descs) | Compact → single-line chat render; Comfortable/Cozy → bubbles (verify in a chat) |
| Colored nicknames | switch T `Colored nicknames` | disables palette radios when off |
| Palette | radios T `Default palette` / `Vivid palette` / `Pastel palette` | selectable when nick colors on |
| Nick color overrides | row T `Nick color overrides` (+ `N nicks`) | navigates to NickColors manage screen |
| Show join/part | switch T `Show join/part messages` | toggles system-event visibility in channels |
| Friends | row T `Friends` | navigates to Friends manage screen |
| Fools | row T `Fools` | navigates to Fools manage screen |
| Fools' messages mode | radios T `Collapse` (+ `Show a tappable placeholder.`) / T `Hide` (+ `Remove their messages entirely.`) | Collapse shows placeholder; Hide filters out |
| Delivery: persistent | radio T `Persistent connection` (+ `Keeps a foreground connection alive.`) | selectable |
| Delivery: push | radio T `Push (UnifiedPush)` | enabled only when a distributor is installed AND bouncer has webpush; else desc = `Requires a bouncer with Web Push (connect to your soju bouncer first).` or `Install a UnifiedPush distributor like ntfy to receive push.` |
| Battery optimization | row T `Battery optimization` (+ desc) | launches OS battery-exempt settings intent (assert intent fired / no crash) |
| Networks list | rows per network `name` + `host:port` (+ `· soju` / `· via %s` suffix) | present; tap → NetworkSettings |
| Add network | row T `Add network` (+ icon) | navigates to AddNetwork |
| About | row T `About` (+ version) | navigates to About |

### 1.11 Network settings — `NetworkSettingsRoute` (`NetworkSettingsScreen.kt`)

Title = network name. Status card + connect controls; auto-connect; bouncer
row(s); server messages; delete; Save FAB.

| Feature / interaction | Selector | Correctness assertion |
| --- | --- | --- |
| Status header | T `Ready as %s` / `Connecting…` / `Registering…` / `Disconnected` / `Failed — %s` + dot | reflects live state |
| Connect | button T `Connect` (disconnected) | connects |
| Disconnect | button T `Disconnect` (live) | disconnects |
| Reconnect | button T `Reconnect` (failed) | reconnects |
| Auto-connect | switch T `Connect automatically` (+ `Reconnect to this network on startup.`) | persists autoConnect |
| Edit fields | NetworkForm fields | edits host/port/tls/nick/auth |
| Bouncer networks (root) | row T `Bouncer networks` (+ `Manage networks bound to this soju account`) | navigates to BouncerNetworks |
| Managed-by (child) | T `Managed by %s` (+ `Transport and login come from the soju connection`) | shown for BOUNCER_CHILD |
| Server messages | row T `Server messages` | opens SERVER buffer |
| Save | FAB CD `Save` | persists edits |
| Delete | T `Delete network` → dialog T `Delete network?`, body `This removes the network and all of its buffers and history.`, T `Delete` / T `Cancel` | removes network + buffers + history |

### 1.12 Add network — `AddNetworkRoute` (`AddNetworkScreen.kt`)

Title T `Add network`. Kind segmented `IRC network` / `soju bouncer`. Phases:
FORM / TESTING / FAILED.

| Feature / interaction | Selector | Correctness assertion |
| --- | --- | --- |
| Kind selector | segmented T `IRC network`, T `soju bouncer` | switches form shape |
| Form + submit | button T `Connect & save` | enabled when form valid; enters TESTING |
| Testing | T `Testing connection…` + state labels | transitions Connecting→Registering→Ready/Failed |
| Failed | T `Couldn't connect — %s`, buttons T `Edit`, T `Save anyway`, T `Retry` | Retry re-tests, Edit returns to form, Save anyway persists |
| Cert-trust during add | dialog T `Trust this certificate?` | trust to proceed if adding a self-signed soju |
| Success → bouncer | on soju ready | pops AddNetwork, navigates to BouncerNetworks |
| Back abandons half-created | CD `Back` when phase ≠ FORM | deletes the half-created row |

### 1.13 Bouncer networks — `BouncerNetworksRoute` (`BouncerNetworksScreen.kt`)

Title T `Bouncer networks`.

| Feature / interaction | Selector | Correctness assertion |
| --- | --- | --- |
| Connect gate | T `Connect to manage networks`, button T `Connect` | shown when root not ready |
| Network row | headline = name; supporting = host; dot color per state; switch CD `Shown in MOTD` | row per bouncer network incl. `libera` |
| Toggle shown-in-MOTD | switch CD `Shown in MOTD` | imports/removes local BOUNCER_CHILD |
| Delete from bouncer | 3-dot → T `Delete from bouncer` → dialog T `Delete network?`, body `This removes %s from the bouncer permanently.`, T `Delete` / T `Cancel` | removes from bouncer |
| Add to bouncer | button T `Add network to bouncer` → dialog T `Add network to bouncer`, fields T `Name`, T `Host`, T `Port`, T `Nickname`, T `Add` / T `Cancel` | Add enabled when name+host non-blank; creates bouncer network |

### 1.14 Manage nicks — `FriendsRoute` / `FoolsRoute` / `NickColorsRoute` (`ManageNicksScreen.kt`, `NickHuePicker.kt`)

One screen, three kinds. Titles T `Friends` / `Fools` / `Nick colors`.

| Feature / interaction | Selector | Correctness assertion |
| --- | --- | --- |
| Add nick | field T `Nickname` + button T `Add` | validates (no whitespace/comma, not `#`/`&`); adds to list |
| Remove nick | CD `Remove` (per row) | removes from list |
| Empty states | T `No friends yet…` / `No fools yet…` / `No overrides yet…` | shown when list empty |
| Colors: add opens picker | add a nick in NickColors | opens hue picker dialog |
| Hue picker | dialog title = nick; 12 swatches; T `Auto (no override)`; T `Cancel` | tapping a swatch sets hue 0..330; Auto clears |

### 1.15 About — `AboutRoute` (`AboutScreen.kt`)

| Feature / interaction | Selector | Correctness assertion |
| --- | --- | --- |
| About content | logo, T `motd`, version, T `An IRCv3 client for Android — see plans/ for the design docs.` | present |
| License link | row T `License` + T `MIT` | opens LICENSE URL |
| GitHub link | row T `GitHub` + T `https://github.com/trevarj/motd` | opens repo URL |

### 1.16 Image viewer — `ImageViewerRoute` (`ImageViewerScreen.kt`)

Fade transition (not slide).

| Feature / interaction | Selector | Correctness assertion |
| --- | --- | --- |
| Image | CD `Full-screen image` | image renders (or T `Couldn't load image` on failure) |
| Back | CD `Back` | pops with fade |
| Share | CD `Share` | fires share chooser `Share image` |
| Save (API 29+) | CD `Save` | saves; snackbar T `Saved to Pictures/motd` or T `Couldn't save image` |
| Pinch/double-tap zoom | gestures | zoom clamps 1..5x; double-tap toggles 1x/2.5x (assert no crash) |

### 1.17 Cert-trust dialog — global host (`CertTrustDialog.kt`, `MainActivity.kt`)

Hosted above the whole nav graph via `CertTrustDialogHost`. Covered inline in
onboarding/add-network. Extra assertions:

| Feature / interaction | Selector | Correctness assertion |
| --- | --- | --- |
| First-trust dialog | T `Trust this certificate?` | body T = `This server presented a certificate…`; all fields shown |
| Changed-cert dialog | T `Certificate changed` + T (warning) | only when a previously-pinned cert differs (hard to trigger; document, optional) |
| Trust | T `Trust` | pins cert, reconnects |
| Cancel | T `Cancel` (or system back) | dismisses, network stays disconnected |

### 1.18 Navigation, transitions & back

| Feature | Assertion |
| --- | --- |
| Forward slide | pushing a route slides new screen in from right (Material shared-axis X, 300ms) — assert destination present after ≥300ms settle |
| Pop slide | back slides reverse; ImageViewer fades instead |
| System back per route | `adb shell input keyevent KEYCODE_BACK` pops one level; on ChatList with drawer open, back closes drawer first (`BackHandler`) |
| Predictive back (API 33+) | back gesture animates the pop scrim; assert final destination, not the animation |
| AddNetwork back | back with phase ≠ FORM abandons the half-created row (no orphan network) |

---

## 2. Runbook — ordered traversal

Each step: **navigate** (selector to reach it) → **act** (taps/inputs) →
**assert** (uiautomator text/element or screencap) + implicit `assert_no_crash`.
`dump` = fresh `uiautomator dump`. **After any IME opens, re-dump** before
locating fields — the keyboard shifts bounds and scrolls the form.

### Phase A — clean install & onboard the soju bouncer

1. **Reset.** act: `pm clear $PKG`; launch. assert: dump contains T `Welcome to motd` (redirected into onboarding). crash-check.
2. **Welcome → Choice.** act: `tap_text "Get started"`. assert: T `How do you connect?`.
3. **Choose soju.** act: `tap_text "I have a soju bouncer"`; `tap_text "Next"`. assert: SERVER page — T `Host` field present.
4. **Server fields.** act: `input_field "Host" 104.168.59.26`; re-dump; `input_field "Port" 6697` (should already be 6697); ensure `Use TLS` on; `input_field "Nickname" motdadb`. assert: forward T `Next` enabled. Note: dump after each IME-opening focus.
5. **Advance to auth.** act: `tap_text "Next"`. assert: T `Authentication`; T `Username` + T `Password` present (soju SASL PLAIN).
6. **Auth creds.** act: `input_field "Username" motd`; re-dump; `input_field "Password" ApPTeSt123`. assert: forward T `Next` enabled.
7. **Connect.** act: `tap_text "Next"`. assert: T `Connecting`.
8. **Trust cert.** navigate: wait for dialog. assert: T `Trust this certificate?` + T `SHA-256 fingerprint`. act: `tap_text "Trust"`. assert (poll up to ~20s, re-dump): T starting `Connected as ` OR bouncer section T `Bouncer networks`.
9. **Bouncer import.** assert: T `Bouncer networks`, `libera` row present. act: ensure `libera` switch on. assert: switch checked.
10. **Finish.** act: `tap_text "Next"` (to FINISH), then `tap_text "Finish"`. assert: back on ChatList — T `motd` (top bar) present, onboarding strings gone.

### Phase B — chat list, drawer, connectivity

11. **Drawer open.** navigate: `tap_desc "Open navigation drawer"`. assert: T `All chats`, T `libera` (network row), footer T `Add network`, T `Go offline`, T `Settings`.
12. **Network subtitle.** assert: `libera` row subtitle = `motdadb` (Ready) or a state string. crash-check.
13. **Scope to network.** act: `tap_text "libera"`. assert: drawer closed; ScopeChip with T `libera`; top bar title = `libera`.
14. **Clear scope.** act: tap the scope chip (CD `Clear network filter`). assert: title back to `motd`.
15. **Server messages.** act: open drawer; long-press `libera`; `tap_text "Server messages"`. assert: chat screen for SERVER buffer (`*`), composer placeholder T `Send a command…`.
16. **Back to list.** act: `keyevent BACK`. assert: T `motd`.

### Phase C — join a channel & chat features

17. **New conversation.** act: `tap_desc "New conversation"`. assert: sheet T `Join channel`, T `Browse channels…`.
18. **Join seed channel.** act: `input_field "#channel" "##motdtest"`; `tap_text "Join"`. assert: sheet closes; `##motdtest` row appears in list (or opens chat).
19. **Open channel.** act: `tap_text "##motdtest"`. assert: chat top bar T `##motdtest`, composer placeholder T `Message`.
20. **Send message.** act: `input_field "Message" "hello from e2e"`; re-dump; `tap_desc "Send"`. assert: message text `hello from e2e` present; list at bottom. crash-check.
21. **Nick autocomplete.** act: type `mo` in composer. assert (re-dump): a dropdown row with a nick prefix-matching `mo` present. (Requires members; the seed channel should have ≥1 other nick, §8.)
22. **Command autocomplete.** act: clear composer; type `/`. assert: dropdown lists `/me`, `/join`, `/whois`, etc.
23. **`/me` action.** act: send `/me waves`. assert: ACTION row (italic `waves`) present.
24. **Reaction add.** act: long-press the `hello from e2e` message; assert quick-emoji row present; `tap_text "👍"`. assert: reaction chip `👍` under the message.
25. **More reactions.** act: long-press again; `tap_text` the more-reactions control (CD `More reactions`); tap a grid emoji. assert: second chip present.
26. **Reply.** act: long-press message; `tap_text "Reply"`. assert: composer bar T starting `Replying to`. act: `tap_desc "Cancel reply"`. assert: bar gone.
27. **Copy / Quote.** act: long-press; `tap_text "Copy"`. assert: no crash. (Clipboard not asserted via UI.)
28. **Scroll-to-bottom FAB.** act: scroll list up (swipe down); assert CD `Scroll to bottom` appears; tap it. assert: FAB gone (at bottom).
29. **Search from chat.** act: `tap_desc "Search"`. assert: T `Search messages`. act: type `hello`. assert: result row present. act: tap result. assert: back in chat; target message pulses (deep-jump; screencap optional).

### Phase D — channel info & moderation surface

30. **Open channel info.** navigate: from `##motdtest` chat, tap title. assert: T `Channel info`, T `N members`.
31. **Topic edit dialog.** act: `tap_desc "Edit topic"`. assert: T `Edit topic`, T `Channel topic`. act: `tap_text "Cancel"`.
32. **Mute toggle.** act: `tap_text "Mute"`. assert: T `Unmute`. act: tap again to restore.
33. **Pin toggle.** act: `tap_text "Pin"`. assert: T `Unpin`.
34. **Member → nick sheet.** act: tap a member row (a nick other than self). assert: sheet T `Message`, T `Mention`, T `Add to friends`, T `Add to fools`.
35. **Add friend.** act: `tap_text "Add to friends"`. assert: sheet dismissed; back on info the member has a friend star.
36. **Leave dialog (cancel).** act: `tap_text "Leave"`. assert: T `Leave channel?`. act: `tap_text "Cancel"`.
37. **Back to chat.** act: `keyevent BACK`.

### Phase E — channel browser

38. **Open browser.** navigate: chat list FAB → `Browse channels…`, or send `/list` in a channel. assert: T `Browse channels`, field T `Search channels`.
39. **Search channels.** act: `input_field "Search channels" "motd"`; IME search. assert: LinearProgressIndicator then rows OR T `No channels found`.
40. **Join from browser.** act: tap a result row (if any). assert: snackbar T starting `Joining`.
41. **Back.** act: `keyevent BACK`.

### Phase F — settings sweep

42. **Open settings.** navigate: chat list `tap_desc "Settings"`. assert: T `Settings`, T `Appearance`.
43. **Theme AMOLED.** act: `tap_text "AMOLED (true black)"`. assert: screencap background near-black (optional) + no crash.
44. **Message style Compact.** act: `tap_text "Compact"`. assert: setting selected. (Verify render in chat later.)
45. **Colored nicknames off/on.** act: toggle switch T `Colored nicknames`; assert palette radios disabled; toggle back on.
46. **Palette.** act: `tap_text "Vivid palette"`. assert: selected.
47. **Nick color overrides.** act: `tap_text "Nick color overrides"`. assert: T `Nick colors`. act: add nick `foo`; assert hue picker dialog (title `foo`, T `Auto (no override)`); tap a swatch; `keyevent BACK`.
48. **Friends manage.** act: from settings `tap_text "Friends"`. assert: T `Friends`. act: add `bar`; assert row `bar`; `tap_desc "Remove"`; assert T `No friends yet…`; `keyevent BACK`.
49. **Fools manage.** act: `tap_text "Fools"`. assert: T `Fools`. act: `keyevent BACK`.
50. **Fools mode.** act: `tap_text "Hide"`. assert: selected; restore `Collapse`.
51. **Show join/part toggle.** act: toggle switch T `Show join/part messages`; assert toggled; restore.
52. **Delivery mode push availability.** assert: T `Push (UnifiedPush)` present; if disabled, desc shows one of the unavailable strings. (No distributor installed on CI device → expect `Install a UnifiedPush distributor like ntfy to receive push.`)
53. **Battery optimization.** act: `tap_text "Battery optimization"`. assert: no crash (OS settings intent). act: `keyevent BACK` to return.
54. **Networks list.** assert: `libera` row present. act: tap it. assert: NetworkSettings — status header present.
55. **Network settings controls.** assert: T `Server messages`, switch T `Connect automatically`. act: toggle disconnect/connect via status button; assert status text changes. (Avoid Delete here to preserve state; delete is covered in teardown.)
56. **Bouncer networks (root).** navigate: from soju root NetworkSettings `tap_text "Bouncer networks"`. assert: T `Bouncer networks`, `libera` row with switch CD `Shown in MOTD`. act: open Add dialog `tap_text "Add network to bouncer"`; assert fields T `Name`, T `Host`; `tap_text "Cancel"`; `keyevent BACK`.
57. **About.** navigate: settings `tap_text "About"`. assert: T `motd`, T `License`, T `GitHub`. act: `keyevent BACK` ×N to chat list.

### Phase G — render-mode verification

58. **Compact render.** navigate: open `##motdtest` chat (Message style is now Compact). assert: single-line `nick: text` rows (no bubble container); previously-sent `hello from e2e` shows in compact form.
59. **Restore Comfortable.** navigate: settings → `tap_text "Comfortable"`; reopen chat; assert bubble render.

### Phase H — image viewer (if a message with an image URL exists)

60. **Open image.** navigate: a message with an inline image. act: tap image. assert: CD `Full-screen image`. act: `tap_desc "Share"` (assert chooser) then `keyevent BACK`; `tap_desc "Back"`. (Skip gracefully if no image present.)

### Phase I — teardown

61. **Delete a chat (swipe).** navigate: chat list. act: swipe end-to-start on a row. assert: T `Delete chat?`. act: `tap_text "Cancel"` (or Delete to exercise deletion).
62. **Final crash sweep.** act: `assert_no_crash` over the whole run's logcat buffer.
63. **Reset.** act: `pm clear $PKG` (leave device clean for the next run).

DM/mention/typing steps (Phase C variants) require the second identity actively
messaging `motdadb` from `motdadb2`; see §8. Where the second identity is not
scripted, mark those rows **conditional** and skip without failing the run.

---

## 3. Execution model recommendation

**Recommendation: an external host-driven bash + uiautomator harness** (`test/e2e/`),
with this markdown as the source of truth. Reasons:

- **The bouncer is private.** Instrumented Compose UI tests (`androidTest`) run
  on the device/emulator and would need the app to reach `104.168.59.26:6697`
  and complete a real SASL + TOFU handshake plus bouncer-network import. That is
  exactly the integration this run must exercise; mocking it defeats the purpose,
  and letting `connectedAndroidTest` reach the private bouncer is brittle and
  couples CI to network reachability.
- **Real-network flakiness** (latency, reconnects, chathistory paging, typing
  timing) is inherent to what we validate. An external harness can poll with
  generous timeouts and retry dumps, which is awkward inside `ComposeTestRule`.
- **Crash detection is out-of-process** anyway (`logcat -b crash`), which the
  external harness already owns.
- **The driving capability already exists** (orchestrator uses `adb input`,
  `uiautomator dump`, `screencap`, `logcat -b crash`). We formalize it into a
  helper library rather than reinventing it as instrumentation.

Instrumented Compose tests remain the right tool for **pure-UI logic** already
covered by unit tests (grouping, sectioning, command parsing) — they are not a
substitute for this end-to-end acceptance run.

Harness shape (`test/e2e/`):

```
test/e2e/
  lib.sh          # helper functions (below)
  run.sh          # full ordered runbook (§2), phases toggled by env
  phases/
    a_onboard.sh
    b_drawer.sh
    c_chat.sh
    d_info.sh
    e_browser.sh
    f_settings.sh
    g_render.sh
    h_image.sh
    i_teardown.sh
  fixtures/
    seed.sh       # §8: seed ##motdtest, second identity
  README.md       # links back to this doc
```

Core helpers in `lib.sh`:

| Helper | Behavior |
| --- | --- |
| `adb_() { adb -s "$SERIAL" "$@"; }` | pin to one device |
| `dump()` | `uiautomator dump /sdcard/w.xml && adb_ pull` → cache XML; re-called after IME opens |
| `node_bounds(query)` | parse cached XML; return center x,y for a node by `text=`/`content-desc=`/`resource-id` (testTag) |
| `tap_text(t)` | `dump`; locate by exact `text`; `input tap x y`; fail if not found |
| `tap_desc(cd)` | same by `content-desc` |
| `tap_tag(tag)` | same by `resource-id` (testTag surfaces as resource-id) |
| `input_field(label, value)` | tap the field (by label proximity or tag), `input text`, then `dump` again |
| `assert_text(t)` | `dump`; grep XML for exact text; fail with screencap on miss |
| `assert_no_text(t)` | inverse |
| `assert_no_crash()` | `logcat -b crash -d`; fail if any line references `$PKG`; snapshot buffer each step, clear at run start |
| `screencap_step(name)` | `exec-out screencap -p > artifacts/$STEP-$name.png` (diagnostic only, not primary assertion) |
| `wait_for_text(t, timeout)` | poll `dump` until text appears or timeout (used for connect/registration) |
| `back()` | `input keyevent KEYCODE_BACK` |

Selection is **always** by text / content-desc / testTag (resource-id), never
raw coordinates: coordinates come only from `node_bounds` of a matched element,
so layout shifts and keyboard scroll don't break selectors. Screenshots are
diagnostic artifacts, not the primary oracle (§10).

---

## 4. testTags / contentDescriptions to add (Opus implementation task, WP-A)

Text/CD selectors cover most of the UI, but several load-bearing elements are
ambiguous (duplicate labels, icon-only with no CD, or state indicators encoded as
color). Add `Modifier.testTag(...)` (surfaces as `resource-id` in dumps) or a
`contentDescription`. Group by screen. This owns many `ui/` files, so run WP-A
when **no other UI-editing agent is active**.

**Onboarding (`OnboardingScreen.kt`):**
- `onboarding_forward_button` — the wizard forward button (label changes: Get started/Next/Finish), so the harness targets it stably.
- `onboarding_back_button` — Back.
- `onboarding_choice_soju` / `onboarding_choice_network` — the two choice cards.
- `onboarding_bouncer_row_<netId>` and its switch `onboarding_bouncer_switch_<netId>` — import toggles (label `libera` alone is fine, but the switch needs a handle).
- `onboarding_state_indicator` — connect-step status line (currently just text `Connected as …`).

**Cert dialog (`CertTrustDialog.kt`):**
- `cert_trust_dialog` on the AlertDialog root; buttons already have text (`Trust`/`Cancel`) — sufficient, but a tag on the fingerprint value helps assert the specific cert.

**Chat list (`ChatListScreen.kt`, `ChatListRowItem.kt`):**
- `chatlist_row_<bufferId>` on each row (display names can collide across networks).
- `chatlist_row_unread_badge` / `chatlist_row_mention_badge` — badges currently have no CD; add CD like `"N unread"` / `"N mentions"` so counts are assertable.
- Long-press menu items **Pin/Unpin/Mute/Unmute** are hardcoded literals — either move to string resources or tag them; harness currently keys on literal text.
- FAB already has CD `New conversation`. OK.

**Server drawer (`ServerDrawer.kt`):**
- `drawer_network_row_<netId>` on each network row.
- `drawer_status_dot_<netId>` with a `contentDescription` encoding the state (`"Ready"` / `"Connecting"` / `"Disconnected"` / `"Failed"`), because state is color-only today and unreadable via text dump.

**Chat screen (`ChatScreen.kt`, `MessageList.kt`, `MessageBubble.kt`, `CompactMessageRow.kt`):**
- `chat_message_<msgid>` on each message container (bubble and compact row) — the single most valuable tag; enables reliable long-press/react/reply/deep-jump targeting.
- `chat_composer_field` on the composer TextField (label `Message` is inconsistent across SERVER buffers).
- `chat_reaction_chip_<emoji>` on reaction chips.
- `chat_system_pill` on system-event pills (to assert collapse text and tap-to-expand).
- `chat_read_marker_divider` (text `— New messages —` works, but tag is stabler).
- Send button already has CD `Send`; scroll-to-bottom has CD `Scroll to bottom`. OK.

**Nick sheet (`NickActionSheet.kt`):** action rows have text; moderation rows are op-gated. Add `nick_sheet` root tag to disambiguate from MessageActionSheet when both could be open.

**Message action sheet (`MessageActionSheet.kt`):** add `message_action_sheet` root tag; the emoji quick-row items are bare emoji text (fine) but a `message_more_reactions` tag on the expander helps.

**Channel info (`ChannelInfoScreen.kt`):** `channelinfo_member_<nick>` on member rows; `channelinfo_topic_edit` already has CD `Edit topic` (OK).

**Settings (`SettingsScreen.kt`):** radio rows carry unique text — OK. Switches (`Dynamic color`, `Colored nicknames`, `Show join/part messages`) rely on adjacent label text; add tags `settings_switch_dynamic_color`, `settings_switch_nick_colors`, `settings_switch_show_jpq` so the harness can read/set the checked state directly.

**Network settings (`NetworkSettingsScreen.kt`):** `network_settings_status` on the status header text; status button text changes (Connect/Disconnect/Reconnect) so tag it `network_settings_conn_button`.

**Bouncer networks (`BouncerNetworksScreen.kt`):** `bouncer_row_<netId>` + `bouncer_switch_<netId>` (switch CD `Shown in MOTD` collides across rows).

**Search (`SearchScreen.kt`):** `search_result_<msgid>` on hit rows (sender+text can repeat).

**Image viewer:** CDs already present (`Full-screen image`, `Back`, `Share`, `Save`). OK.

Principle: add a **stable per-item tag** wherever a list has repeatable content
(rows, badges, chips, members, results) and a **CD** wherever an icon-only or
color-only indicator carries state (status dots, badge counts).

---

## 5. Test-data prerequisites & seeding

Bouncer (soju at `104.168.59.26:6697`) state required before a run:

- Account `motd` / `ApPTeSt123` exists; default nick `motdadb`.
- A bound network named `libera` (irc.libera.chat) provisioned. Already done.
- The `libera` network should have **auto-join** `##motdtest` (or the harness
  joins it in Phase C step 18) so a channel with members + history exists.
- A **second identity** `motdadb2` (either a second bouncer network/nick or a
  separate libera connection) that:
  - is present in `##motdtest` (gives the nick-autocomplete and member-list rows
    something to match), and
  - can send a DM to `motdadb` (drives DM notification + unread/mention badges),
    and
  - can send a mention `motdadb: hi` in `##motdtest` (drives mention badge +
    highlight notification), and
  - can trigger a typing indicator (types without sending) for the typing-row
    assertion.

Seed `##motdtest` (`fixtures/seed.sh`), run once against the bouncer out-of-band
(not through the app), e.g. a scripted IRC client (`ii`, a Python `irc` snippet,
or `nc` + raw lines over TLS) that:

1. Connects `motdadb2`, joins `##motdtest`, sets a topic.
2. Posts a handful of lines (mix of plain, an ACTION, a URL for link-preview, an
   image URL for the image-viewer path).
3. Sends `motdadb: welcome` (mention) and a DM to `motdadb`.
4. Optionally emits a JOIN/PART/QUIT burst so the system-event-pill collapse is
   exercised.

Seeding gives the run deterministic history to assert against (search hits,
reactions, deep-jump). Keep the seed idempotent (topic set, N most-recent lines).

**Reset between runs:** `adb shell pm clear $PKG` wipes all app state (DB,
DataStore prefs, cert-trust pins, friends/fools/overrides, push registration).
The bouncer state persists server-side and does not need per-run reset; only
re-seed `##motdtest` if history was pruned. To also reset the trusted-cert pin
you need only `pm clear` (the pin lives in the app's DataStore).

Because `pm clear` also drops the trusted cert, **every run re-hits the TOFU
dialog** — this is intentional coverage, but the harness must always handle the
cert prompt in Phase A step 8 and Add-network flows.

---

## 6. Work packages

**WP-A — add testTags / contentDescriptions (Opus, UI-file heavy).**
Implements §4. Touches many `ui/` files (onboarding, chatlist, chat, drawer,
settings, network settings, bouncer, search, channel info). **Must run when no
other UI-editing agent is active** to avoid same-file clobbers. Deliver: tags +
CDs added, `warningsAsErrors` still green, unit tests unaffected. Cross-check
each tag against the harness selectors in §2/§9.

**WP-B — bash/uiautomator harness + helper lib.**
Implements §3 layout and §2 steps. Deliverables: `test/e2e/lib.sh` (helpers),
`run.sh` (ordered runbook, phase env toggles), per-phase scripts, `fixtures/seed.sh`,
`test/e2e/README.md`. Requirements: single-device (`$SERIAL`), reads `$PKG`,
snapshots `logcat -b crash` per step, screencaps only as artifacts, generous
`wait_for_text` timeouts on connect/registration. Guix note: run adb via
`guix shell android-tools -- adb …` (or document the assumed adb source);
`bash-reviewer` should review for quoting/robustness.

**WP-C — this runbook doc.** `plans/18-e2e-runbook.md` (this file). Source of
truth; keep the coverage matrix in sync when UI changes.

Dependency order: WP-A and WP-C can land first; WP-B depends on WP-A tags for
the list/badge/status selectors (it can start against text/CD selectors and
switch to tags as they land).

---

## 7. Open questions & risks

- **Flaky real network.** Connect/registration/chathistory timing varies. Mitigate
  with `wait_for_text` polling (≥20s on connect, ≥10s on history) and dump-retry;
  never a fixed `sleep`. Some steps (typing indicator, DM/mention badges) depend
  on the second identity's timing and are marked **conditional**.
- **Cert-trust automation.** Every `pm clear` re-triggers TOFU; the harness must
  always be ready to `tap_text "Trust"` in Phase A and any add-network/reconnect.
  The **changed-cert** dialog (`Certificate changed`) is hard to force and is
  documented as optional coverage.
- **Screenshot payload limits.** `screencap` PNGs are large; prefer uiautomator
  **text** assertions. Reserve `screencap_step` for color-only oracles (AMOLED
  black background, status-dot color) and on-failure diagnostics. Even those
  color oracles are better replaced by the CD additions in §4 (status-dot CD)
  where possible.
- **State encoded as color** (drawer status dots, unread/mention badge severity)
  is unreadable via text dump today — hence the CD additions in §4. Until WP-A
  lands, those assertions fall back to indirect signals (subtitle text, badge
  count) or screencap.
- **Duplicate text across networks** (same channel/nick on two networks) makes
  bare-text selection ambiguous — the per-item tags in §4 resolve this.
- **Second-identity harness** is unspecified (which IRC client seeds `motdadb2`).
  WP-B `fixtures/seed.sh` must pick one (raw TLS socket + IRC lines is the
  lightest, no extra deps beyond `openssl s_client`/`socat`).
- **IME-shifted layout.** Field bounds move once the keyboard opens; the runbook
  flags every field step with **re-dump after IME**. Missing a re-dump is the
  most likely source of mis-taps.
- **Predictive back (API 33+)** animates the pop; assert the final destination
  after settle, never mid-animation dumps.
- **Push/webpush availability** on a CI device with no UnifiedPush distributor:
  the push radio is expected disabled with `Install a UnifiedPush distributor…`;
  the run asserts that specific disabled string rather than trying to enable push.

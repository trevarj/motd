# 13 — Round 4: user-customizable UI settings

Appearance/behavior settings: layout density, nick colors (+ per-nick overrides), friends,
fools, and join/part/quit visibility. Contract amendments land in `10-contracts.md`
§"Round 4 amendments" (authored below, copied verbatim by WP-U0). Frozen-contract discipline
applies: WP-U0 lands every shared signature first; parallel WPs code against them verbatim.

All file paths below are relative to the repo root; Kotlin paths omit the
`app/src/main/kotlin/io/github/trevarj/motd/` prefix unless stated.

## Scope

| Option | v1 | Notes |
|---|---|---|
| Layout density (compact/comfortable/cozy) | yes | spacing tokens via CompositionLocal |
| Nick colors on/off + palette (default/vivid/pastel) | yes | reuses golden-ratio hash as DEFAULT |
| Per-nick color override (hue swatch picker) | yes | built from Compose primitives, no new deps |
| Friends list (badge, chat-list section, mute bypass) | yes | |
| Fools list (collapse/hide, chat-list + member-list grouping, no notifications) | yes | |
| Show/hide join-part-quit events | yes | PagingData filter |
| Timestamp format (12/24 h) / visibility | no — nice-to-have | current fixed `HH:mm` stays |
| Message grouping window slider | no — nice-to-have | `GROUP_WINDOW_MS` stays 3 min |
| Bubble vs compact-line (classic IRC) rendering | no — nice-to-have | large rendering fork; separate round |
| In-app font scale | no — nice-to-have | OS font scale already applies via `sp` |
| Per-buffer / per-network overrides of any of the above | no — nice-to-have | see Open decisions |

Nice-to-haves get **no schema reservations** — adding a DataStore key later is free and
`Json { ignoreUnknownKeys }` / `?: default` decoding tolerates absence.

---

## 1. Settings model — "Round 4 amendments" for plans/10-contracts.md

WP-U0 appends the following block to `plans/10-contracts.md` (after "Round 3 amendments")
and realizes it in code. Everything in this section is a frozen signature.

### Settings (`data/prefs/Settings.kt`) — additive

```kotlin
package io.github.trevarj.motd.data.prefs

enum class LayoutDensity { COMPACT, COMFORTABLE, COZY }
enum class NickColorPalette { DEFAULT, VIVID, PASTEL }
enum class FoolsMode { COLLAPSE, HIDE }

data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val deliveryMode: DeliveryMode = DeliveryMode.PERSISTENT_SOCKET,
    // Round 4 (plans/13)
    val layoutDensity: LayoutDensity = LayoutDensity.COMFORTABLE,
    val nickColorsEnabled: Boolean = true,
    val nickColorPalette: NickColorPalette = NickColorPalette.DEFAULT,
    /** Normalized nick -> hue 0..359. Rendered with the active palette's S/L. */
    val nickColorOverrides: Map<String, Int> = emptyMap(),
    /** Normalized nicks. friends and fools are kept disjoint by the repository. */
    val friends: Set<String> = emptySet(),
    val fools: Set<String> = emptySet(),
    val foolsMode: FoolsMode = FoolsMode.COLLAPSE,
    val showJoinPartQuit: Boolean = true,
)

/** Canonical key for friends/fools/override lookups: trimmed + lowercased.
 *  Deliberate simplification of RFC 1459 casemapping (see plans/13 Risks). */
fun normalizeNick(nick: String): String = nick.trim().lowercase()

interface SettingsRepository {
    val settings: Flow<Settings>
    suspend fun setThemeMode(m: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setDeliveryMode(m: DeliveryMode)
    // Round 4
    suspend fun setLayoutDensity(d: LayoutDensity)
    suspend fun setNickColorsEnabled(enabled: Boolean)
    suspend fun setNickColorPalette(p: NickColorPalette)
    /** hue 0..359 (coerced); null removes. [nick] is normalized internally. */
    suspend fun setNickColorOverride(nick: String, hue: Int?)
    /** Adding a friend removes the nick from fools, and vice versa. */
    suspend fun setFriend(nick: String, isFriend: Boolean)
    suspend fun setFool(nick: String, isFool: Boolean)
    suspend fun setFoolsMode(m: FoolsMode)
    suspend fun setShowJoinPartQuit(show: Boolean)
}
```

All existing `Settings(...)` construction sites (previews, `SettingsUiState`) stay
source-compatible via defaults.

### Storage (`data/prefs/PreferencesStore.kt`)

New keys in `PrefKeys`, same single `settings` DataStore, same manual-JSON style as
`push_endpoints`/`cert_pins`:

| key | type | encoding |
|---|---|---|
| `layout_density` | string | `LayoutDensity.name` |
| `nick_colors_enabled` | string | `"true"/"false"` (matches `dynamic_color` style) |
| `nick_color_palette` | string | `NickColorPalette.name` |
| `nick_color_overrides` | string | JSON object `{"nick": 210, ...}` |
| `friend_nicks` | string | JSON array `["alice","bob"]` |
| `fool_nicks` | string | JSON array |
| `fools_mode` | string | `FoolsMode.name` |
| `show_join_part_quit` | string | `"true"/"false"` |

Decode with the existing `runCatching { ... }.getOrNull() ?: default` pattern; invalid enum
strings fall back to defaults. Empty set/map removes the key (mirrors `setEndpointFor`).
JSON codecs are **internal top-level functions** in `PreferencesStore.kt` so they are
unit-testable without DataStore:

```kotlin
internal fun decodeNickSet(raw: String?): Set<String>
internal fun encodeNickSet(nicks: Set<String>): String
internal fun decodeHueOverrides(raw: String?): Map<String, Int>   // hues coerced into 0..359
internal fun encodeHueOverrides(map: Map<String, Int>): String
```

`setFriend(nick, true)` writes both keys in **one** `store.edit { }` transaction (remove from
fools, add to friends); symmetric for `setFool`. `setNickColorOverride` coerces
`hue.coerceIn(0, 359)`.

**DataStore-JSON vs Room table**: DataStore JSON. Friends/fools/overrides are small
user-curated sets (tens of entries), always read whole, written whole, never joined in SQL,
and need no migration story (`ignoreUnknownKeys` + defaults). A Room table would only pay off
if the chat-list DAO query had to join against them — it does not (classification happens in
pure UI-layer functions). This also keeps `motd.db` at schema v1.

**Global vs per-network**: global. See Open decisions §6.

### Routes (`ui/nav/Routes.kt`) — add

```kotlin
@Serializable data object FriendsRoute
@Serializable data object FoolsRoute
@Serializable data object NickColorsRoute
```

### Theme contracts (`ui/theme/`) — new, shared across round-4 WPs

```kotlin
// ui/theme/Spacing.kt (new file)
package io.github.trevarj.motd.ui.theme

@Immutable
data class MotdSpacing(
    val bubbleRowVPad: Dp,       // MessageBubble outer Row vertical padding
    val bubbleInnerVPad: Dp,     // bubble Column inner vertical padding
    val bubbleInnerHPad: Dp,     // bubble Column inner horizontal padding
    val bubbleCorner: Dp,        // base bubble corner radius (grouped inner corner stays 4.dp)
    val bubbleAvatar: Dp,        // in-bubble sender avatar size
    val bubbleAvatarColumn: Dp,  // reserved avatar column width (= bubbleAvatar + 8.dp)
    val actionVPad: Dp,          // ACTION line vertical padding
    val systemPillVPad: Dp,      // SystemEventPill row vertical padding
    val chatListVPad: Dp,        // ChatListRowItem vertical padding
    val chatListAvatar: Dp,      // chat-list avatar size
    val memberAvatar: Dp,        // channel-info member-row avatar size
    val messageBodyLarge: Boolean, // message text: true -> bodyLarge, false -> bodyMedium
)

/** Pure token mapping; unit-tested. */
fun spacingFor(density: LayoutDensity): MotdSpacing

val LocalSpacing: ProvidableCompositionLocal<MotdSpacing>
    // staticCompositionLocalOf { spacingFor(LayoutDensity.COMFORTABLE) }
```

```kotlin
// ui/theme/NickColor.kt — additions (existing nickColor(nick, isDark) is kept, unchanged)
@Immutable
class NickColorScheme(
    val enabled: Boolean,
    val palette: NickColorPalette,
    val overrides: Map<String, Int>,   // normalized nick -> hue
    val isDark: Boolean,
) {
    /** Sender-name/reply-accent color; [fallback] when coloring is disabled. */
    fun nick(nick: String, fallback: Color): Color
    /** Avatar background: override + palette always apply (never falls back to neutral). */
    fun avatar(name: String): Color
}

val LocalNickColors: ProvidableCompositionLocal<NickColorScheme>
    // staticCompositionLocalOf { NickColorScheme(true, NickColorPalette.DEFAULT, emptyMap(), false) }

/** Resolution order: disabled -> fallback; override hue -> hueColor; else palette hash. Pure. */
fun resolveNickColor(
    nick: String, isDark: Boolean, enabled: Boolean,
    palette: NickColorPalette, overrides: Map<String, Int>, fallback: Color,
): Color

/** Palette hash color: golden-ratio hue (identical hue math to nickColor) + palette S/L. Pure. */
fun paletteNickColor(nick: String, isDark: Boolean, palette: NickColorPalette): Color

/** Fixed-hue color with the palette's S/L for the mode (override rendering + picker swatches). */
fun hueColor(hue: Int, isDark: Boolean, palette: NickColorPalette): Color
```

`hslColor` changes from `private` to `internal` (needed by the new functions in the same
file; keep it in `NickColor.kt` so nothing else changes).

```kotlin
// ui/theme/MotdTheme.kt — amended signature (all new params defaulted; every existing
// MotdTheme { } call site, including previews, stays source-compatible)
@Composable
fun MotdTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    layoutDensity: LayoutDensity = LayoutDensity.COMFORTABLE,
    nickColorsEnabled: Boolean = true,
    nickColorPalette: NickColorPalette = NickColorPalette.DEFAULT,
    nickColorOverrides: Map<String, Int> = emptyMap(),
    content: @Composable () -> Unit,
)
```

`MotdTheme` computes `dark` exactly as today and wraps `MaterialTheme` in
`CompositionLocalProvider(LocalSpacing provides spacingFor(layoutDensity), LocalNickColors
provides NickColorScheme(nickColorsEnabled, nickColorPalette, nickColorOverrides, dark))`.
`MainActivity.setContent` passes the four new values from the collected `Settings`.

**Plumbing split (rule for all round-4 WPs):** style-only concerns (spacing, nick colors) flow
through the two CompositionLocals — components never receive them as parameters. Behavioral
concerns (friends/fools/foolsMode/showJoinPartQuit) flow through ViewModel state as explicit
parameters.

---

## 2. Behavior spec per option

### 2.1 Layout density

`spacingFor` values (COMFORTABLE row = current hardcoded literals; changing density to
COMFORTABLE is pixel-identical to today):

| token | COMPACT | COMFORTABLE | COZY | applied at (current literal) |
|---|---|---|---|---|
| bubbleRowVPad | 0.dp | 1.dp | 2.dp | `MessageBubble` outer `Row` `.padding(horizontal = 8.dp, vertical = 1.dp)` |
| bubbleInnerVPad | 4.dp | 6.dp | 8.dp | bubble `Column` `.padding(horizontal = 10.dp, vertical = 6.dp)` |
| bubbleInnerHPad | 8.dp | 10.dp | 12.dp | same call |
| bubbleCorner | 14.dp | 18.dp | 20.dp | `RoundedCornerShape(... 18.dp ...)`; grouped inner corner stays fixed `4.dp` |
| bubbleAvatar | 26.dp | 32.dp | 36.dp | `Avatar(name = sender, size = 32.dp, ...)` |
| bubbleAvatarColumn | 34.dp | 40.dp | 44.dp | `Box(Modifier.width(40.dp))` (invariant: bubbleAvatar + 8.dp) |
| actionVPad | 2.dp | 3.dp | 4.dp | ACTION `Text` `.padding(horizontal = 16.dp, vertical = 3.dp)` |
| systemPillVPad | 2.dp | 4.dp | 6.dp | `SystemEventPill` `Row` `.padding(vertical = 4.dp, horizontal = 12.dp)` |
| chatListVPad | 6.dp | 10.dp | 14.dp | `ChatListRowItem` `.padding(horizontal = 16.dp, vertical = 10.dp)` |
| chatListAvatar | 36.dp | 44.dp | 48.dp | `Avatar(...)` default `44.dp` — pass `size = LocalSpacing.current.chatListAvatar` at the call site |
| memberAvatar | 32.dp | 36.dp | 40.dp | `ChannelInfoScreen.MemberRow` `Avatar(size = 36.dp)` |
| messageBodyLarge | false | true | true | `MessageBubble` body `Text(style = bodyLarge)` → `if (messageBodyLarge) bodyLarge else bodyMedium` |

Not density-scaled (deliberate): horizontal screen paddings (8/12/16.dp), bubble max-width
factor 0.78, timestamp 10.sp, chat top-bar avatar 34.dp, member-sheet avatar 36.dp,
channel-header avatar 88.dp, reply mini-bubble metrics, `Avatar` default parameter (stays
`44.dp`; density-aware call sites pass the token explicitly).

Consumers read `LocalSpacing.current` inside the composable — no signature changes for
density anywhere.

### 2.2 Nick colors

Resolution order (implemented by `resolveNickColor`, wrapped by `NickColorScheme.nick`):

1. `enabled == false` → `fallback` (call sites pass `MaterialTheme.colorScheme.onSurfaceVariant`).
2. `overrides[normalizeNick(nick)]` present → `hueColor(hue, isDark, palette)`.
3. Otherwise `paletteNickColor(nick, isDark, palette)` — the existing golden-ratio hue hash
   (identical seed/hue math as `nickColor`) with the palette's saturation/lightness.

Palette S/L (hue always from hash or override):

| palette | dark s/l | light s/l |
|---|---|---|
| DEFAULT | 0.55 / 0.68 | 0.65 / 0.42 (== existing `nickColor`; test-asserted equal) |
| VIVID | 0.80 / 0.62 | 0.85 / 0.38 |
| PASTEL | 0.38 / 0.75 | 0.45 / 0.55 |

`NickColorScheme.avatar(name)` = steps 2→3 only (never neutral): with coloring disabled,
sender *text* goes neutral but avatars keep their generated color (an all-gray avatar column
would be unusable), and overrides still personalize avatars.

Call sites switching from `nickColor(x, isSystemInDarkTheme())` to the scheme (also fixes the
current mismatch where `ThemeMode.DARK` on a light system still colored for light):

| file | usage | becomes |
|---|---|---|
| `ui/components/MessageBubble.kt` | sender label, `ReplyMiniBubble` bar + label | `LocalNickColors.current.nick(sender, onSurfaceVariant)` |
| `ui/components/Composer.kt` | reply strip bar + label | same |
| `ui/components/Avatar.kt` | background | `LocalNickColors.current.avatar(name)` |

PASTEL light-mode contrast: l=0.55 keeps ≥3:1 against `surface` for label-size text —
verify visually in previews; adjust l down (not below 0.50) if a palette swatch reads weak.

### 2.3 Friends

Data: `Settings.friends: Set<String>` (normalized). Classification everywhere is
`normalizeNick(x) in friends`; own messages (`isSelf`) are never classified.

| surface | effect |
|---|---|
| Chat message (`MessageBubble`) | new param `senderIsFriend: Boolean = false`; when `showSender && !isSelf && senderIsFriend`, a `Icons.Filled.Star` icon (12.dp, tint = resolved nick color) follows the sender label in a `Row(verticalAlignment = CenterVertically)` |
| Chat list | new "Friends" section between "Pinned" and regular rows: non-pinned `QUERY` rows whose `normalizeNick(displayName)` ∈ friends. Row shows a 14.dp `Star` icon after the name (pattern of the existing muted bell). Pinned wins over friend (a pinned friend stays under Pinned, still starred) |
| Member list (`ChannelInfoScreen`) | member stays in its prefix section; row gets a trailing 16.dp `Star` icon (tint `primary`). Member bottom-sheet gains "Add to friends"/"Remove from friends" |
| Notifications (`MotdNotifications`) | friend sender **bypasses the muted-buffer suppression**. Foreground suppression still applies. Scope note: `EventProcessor.maybeNotify` still gates on `(QUERY || hasMention)` — friends do NOT add notifications for un-mentioned channel messages (see Open decisions) |

### 2.4 Fools

Data: `Settings.fools: Set<String>` + `Settings.foolsMode` (default `COLLAPSE`). Disjoint
from friends (enforced at write). `isSelf` never classified. System-event kinds
(`isSystemKind`) are never fool-treated (JPQ visibility governs those).

**COLLAPSE (default)** — message rows from fools render as a one-line placeholder instead of
a bubble: left-aligned dimmed row — `Icons.Filled.VisibilityOff` 14.dp +
`stringResource(R.string.chat_fool_hidden, sender)` ("alice · hidden"), `labelSmall`,
`onSurfaceVariant`, `.alpha(0.7f)`, `.padding(horizontal = 16.dp, vertical = 2.dp)`,
clickable. Tap expands: the row re-renders as the normal `MessageBubble` for the rest of the
session (expand-only; no re-collapse affordance in v1). Expanded ids are hoisted in
`ChatContent` as `var expandedFools by remember { mutableStateOf(setOf<Long>()) }` (keyed by
`MessageEntity.id`; lost on config change — accepted). Day separators, the read-marker
divider, and reactions/replies of *other* messages are unaffected; the placeholder itself
shows no reactions/preview and has no long-press.

**HIDE** — fool messages are removed from the stream entirely (see 2.5 filter). No
placeholder, no expansion. Read-marker/day-separator logic operates on the filtered stream,
so no dangling separators.

Grouping interplay: a collapsed/hidden fool row between two same-sender messages makes the
newer one re-show its header (COLLAPSE: sender differs; HIDE: filtered stream is what
`showsSender` sees). Accepted.

| surface | effect |
|---|---|
| Chat list | non-pinned `QUERY` rows with fool nicks move to a trailing "Fools (n)" section, collapsed by default: header row with count + `ExpandMore`/`ExpandLess` chevron toggling a `remember { mutableStateOf(false) }`; expanded rows render via `ChatListRowItem` inside `.alpha(0.55f)` |
| Member list | fool members are removed from prefix sections and shown in a trailing collapsible "Fools (n)" section (same header pattern, collapsed by default); rows at `.alpha(0.55f)`. Member sheet gains "Add to fools"/"Remove from fools" |
| Notifications | fool senders **never notify** (even DMs/mentions, even when un-muted) |
| Unread badges | unchanged — counts come from the DAO and still include fool/JPQ messages (accepted; see Risks) |

### 2.5 Show join/part/quit + message filtering

`showJoinPartQuit == false` removes `JOIN`/`PART`/`QUIT` rows (`KICK`, `NICK`, `MODE`,
`TOPIC`, `SERVER_INFO`, `ERROR` always render). Filtering happens on `PagingData` in
`ChatViewModel` so grouping/day-separator/read-marker math sees only visible rows:

```kotlin
// ui/chat/ChatModels.kt — pure, unit-tested
val JPQ_KINDS: Set<MessageKind> = setOf(MessageKind.JOIN, MessageKind.PART, MessageKind.QUIT)

data class MessageFilterSpec(
    val showJoinPartQuit: Boolean = true,
    val fools: Set<String> = emptySet(),
    val foolsMode: FoolsMode = FoolsMode.COLLAPSE,
)

fun isFoolSender(sender: String, isSelf: Boolean, fools: Set<String>): Boolean =
    !isSelf && normalizeNick(sender) in fools

/** PagingData.filter predicate: JPQ hiding + fools HIDE mode. */
fun keepMessage(msg: MessageEntity, spec: MessageFilterSpec): Boolean =
    !(msg.kind in JPQ_KINDS && !spec.showJoinPartQuit) &&
    !(spec.foolsMode == FoolsMode.HIDE && !isSystemKind(msg.kind) &&
        isFoolSender(msg.sender, msg.isSelf, spec.fools))
```

```kotlin
// ChatViewModel — replaces the current `messages` property; adds a settings StateFlow
private val filterSpec = settingsRepository.settings
    .map { MessageFilterSpec(it.showJoinPartQuit, it.fools, it.foolsMode) }
    .distinctUntilChanged()

val messages: Flow<PagingData<MessageEntity>> =
    messageRepository.messages(bufferId)
        .combine(filterSpec) { paging, spec -> paging.filter { keepMessage(it, spec) } }
        .cachedIn(viewModelScope)

val settings: StateFlow<Settings> = settingsRepository.settings
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())
```

`ChatState` is unchanged (avoids growing the existing 5-ary `combine`); the screen collects
`viewModel.settings` separately.

### 2.6 Notification decision (consolidated)

```kotlin
// service/MotdNotifications.kt — pure, unit-tested
fun shouldPostNotification(
    foreground: Boolean, muted: Boolean, senderIsFriend: Boolean, senderIsFool: Boolean,
): Boolean = !foreground && !senderIsFool && (!muted || senderIsFriend)
```

`MotdNotifications` gains `private val settingsRepository: SettingsRepository` (Hilt ctor).
`onIncoming` reads `runCatching { runBlocking { settingsRepository.settings.first() } }
.getOrNull()` (the established runBlocking-off-hot-path pattern already used for the buffer
lookup) and replaces the two existing early-returns with one `shouldPostNotification` check
(`sender = normalizeNick(message.source.nick)`; null settings ⇒ empty sets).

---

## 3. UI spec

### 3.1 Settings screen (`ui/settings/SettingsScreen.kt`)

Extend the existing section pattern (`SectionHeader` / `RadioRow` / `SwitchRow` / `ListItem`).
New callbacks on `SettingsContent` (all `(X) -> Unit`, wired to the ViewModel exactly like
`onThemeMode`): `onLayoutDensity(LayoutDensity)`, `onNickColorsEnabled(Boolean)`,
`onNickColorPalette(NickColorPalette)`, `onShowJoinPartQuit(Boolean)`,
`onFoolsMode(FoolsMode)`, `onOpenFriends()`, `onOpenFools()`, `onOpenNickColors()`.

Section layout (order; existing content unchanged):

1. **Appearance** (existing) — theme radios; dynamic-color switch; **new** sub-label
   `settings_density` + radio group Compact/Comfortable/Cozy.
2. **Chat** (new section) —
   - `SwitchRow` "Colored nicknames" (`nickColorsEnabled`).
   - Palette radio group Default/Vivid/Pastel, rows `enabled = nickColorsEnabled` (the
     existing `RadioRow` already renders a disabled state).
   - `ListItem` "Nick color overrides" → `onOpenNickColors()`; supporting text = override
     count via `pluralStringResource(R.plurals.settings_nick_count, n, n)` when n > 0.
   - `SwitchRow` "Show join/part messages" (`showJoinPartQuit`).
3. **People** (new section) —
   - `ListItem` "Friends" → `onOpenFriends()`, count as above.
   - `ListItem` "Fools" → `onOpenFools()`, count as above.
   - Sub-label `settings_fools_mode` + radio group Collapse ("Show a tappable placeholder") /
     Hide ("Remove their messages entirely").
4. **Message delivery**, **Networks**, **About** — unchanged.

`SettingsViewModel` additions (same one-line `viewModelScope.launch { repo.setX(...) }` shape
as `setThemeMode`): `setLayoutDensity`, `setNickColorsEnabled`, `setNickColorPalette`,
`setShowJoinPartQuit`, `setFoolsMode`. `SettingsUiState` is unchanged — counts derive from
`state.settings` in the composable.

### 3.2 Manage screens (`ui/settings/ManageNicksScreen.kt` + `ManageNicksViewModel.kt`, new)

One screen serves all three routes:

```kotlin
enum class NickListKind { FRIENDS, FOOLS, COLORS }

@Composable
fun ManageNicksScreen(
    kind: NickListKind,
    onBack: () -> Unit = {},
    viewModel: ManageNicksViewModel = hiltViewModel(),
)   // calls LaunchedEffect(kind) { viewModel.init(kind) } — ChannelInfo init pattern

data class ManageNicksUiState(
    val kind: NickListKind = NickListKind.FRIENDS,
    val nicks: List<String> = emptyList(),          // sorted ascending
    val overrides: Map<String, Int> = emptyMap(),   // COLORS: nick -> hue
)

@HiltViewModel
class ManageNicksViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    fun init(kind: NickListKind)
    val state: StateFlow<ManageNicksUiState>   // settings flow mapped by kind, stateIn(WhileSubscribed(5_000))
    fun add(nick: String)                      // FRIENDS -> setFriend(nick,true); FOOLS -> setFool(nick,true); COLORS -> no-op (hue set via setHue)
    fun remove(nick: String)                   // symmetric; COLORS -> setNickColorOverride(nick, null)
    fun setHue(nick: String, hue: Int)         // setNickColorOverride(nick, hue)
}
```

For `COLORS`, `nicks` = `overrides.keys.sorted()`.

Layout: `Scaffold` + `TopAppBar` (title per kind: `friends_title` / `fools_title` /
`nick_colors_title`, back arrow — copy the SettingsScreen top-bar verbatim). Body `Column`:

- **Add row**: `OutlinedTextField` (placeholder `manage_add_nick_hint`, single line) +
  `TextButton(manage_add)`, enabled when `sanitizeNickInput(text) != null`. On add:
  FRIENDS/FOOLS → `viewModel.add(...)`, clear field; COLORS → open the hue dialog for the
  sanitized nick, persist only on swatch pick.
- **List** (`LazyColumn`, `items(state.nicks, key = { it })`): `ListItem` with
  `leadingContent = Avatar(name = nick, size = 36.dp)` — for COLORS a 20.dp
  `Box(CircleShape)` filled with `hueColor(overrides[nick]!!, isDark, palette)` instead;
  `trailingContent = IconButton(Icons.Filled.Close)` → `remove`. COLORS rows are clickable →
  reopen the hue dialog. Empty state: centered `manage_empty_*` text.

```kotlin
/** null when unusable: blank, contains whitespace/comma, or starts with '#'/'&'. Pure. */
fun sanitizeNickInput(raw: String): String?
```

### 3.3 Hue swatch picker (`ui/settings/NickHuePicker.kt`, new — Compose primitives only)

```kotlin
@Composable
fun NickHuePickerDialog(
    nick: String,
    currentHue: Int?,               // null = auto (no override)
    onPick: (Int?) -> Unit,         // hue, or null for "Auto"
    onDismiss: () -> Unit,
)
```

`AlertDialog(title = nick)`; content: `LazyVerticalGrid(GridCells.Fixed(6))` of the 12 fixed
hues `listOf(0, 30, 60, 90, 120, 150, 180, 210, 240, 270, 300, 330)` rendered as 40.dp
`Box(CircleShape)` filled with `hueColor(hue, isDark, palette)` (palette/isDark from
`LocalNickColors.current`), selected swatch gets a 2.dp `primary` border; below the grid a
full-width `TextButton(manage_color_auto)` calling `onPick(null)`. `confirmButton` = cancel
text button. Each swatch click = `onPick(hue)` + dismiss at call site.

### 3.4 Navigation (`ui/nav/NavGraph.kt`)

```kotlin
composable<FriendsRoute>    { ManageNicksScreen(NickListKind.FRIENDS, onBack = { navController.popBackStack() }) }
composable<FoolsRoute>      { ManageNicksScreen(NickListKind.FOOLS,   onBack = { navController.popBackStack() }) }
composable<NickColorsRoute> { ManageNicksScreen(NickListKind.COLORS,  onBack = { navController.popBackStack() }) }
```

`SettingsScreen` entry gains `onOpenFriends/onOpenFools/onOpenNickColors` lambdas navigating
to the routes.

### 3.5 Chat list (`ui/chatlist/`)

```kotlin
// ui/chatlist/ChatListSectioning.kt (new) — pure, unit-tested
data class ChatListSections(
    val pinned: List<ChatListRow>,
    val friends: List<ChatListRow>,
    val regular: List<ChatListRow>,
    val fools: List<ChatListRow>,
)

/** Precedence: pinned > friend > fool > regular. Only QUERY rows classify by
 *  normalizeNick(displayName); channels are never friends/fools. Input order preserved. */
fun sectionChatList(
    rows: List<ChatListRow>, friends: Set<String>, fools: Set<String>,
): ChatListSections
```

`ChatListState` gains `val friends: Set<String> = emptySet(), val fools: Set<String> =
emptySet()`; `ChatListViewModel` adds `settingsRepository.settings` as a 4th `combine` source.
`ChatList` renders: Pinned header+rows (existing) → `chatlist_friends` header + friend rows →
regular rows → fools collapsible section (2.4). `ChatListRowItem` gains
`isFriend: Boolean = false` (star glyph as specced). Section item keys: existing `"p-"`
prefix for pinned; use `"f-"` for friends and `"o-"` for fools to keep LazyColumn keys unique.

### 3.6 Channel info (`ui/channelinfo/`)

```kotlin
// MemberSectioning.kt — addition (existing sectionMembers/prefixOrderFrom unchanged)
data class SocialSections(
    val sections: List<MemberSection>,   // prefix sections, fools removed
    val fools: List<MemberEntity>,       // sorted case-insensitively
)

fun sectionMembersSocial(
    members: List<MemberEntity>,
    prefixOrder: String = DEFAULT_PREFIX_ORDER,
    fools: Set<String> = emptySet(),
): SocialSections
```

`ChannelInfoUiState` gains `val foolMembers: List<MemberEntity> = emptyList(), val friends:
Set<String> = emptySet(), val fools: Set<String> = emptySet()`; ViewModel injects
`SettingsRepository`, adds its flow as a 3rd `combine` source, and exposes:

```kotlin
fun toggleFriend(nick: String)   // settingsRepository.setFriend(nick, nick !in friends)
fun toggleFool(nick: String)
```

Screen: fools section after the regular section (2.4); friend star on `MemberRow`; member
bottom-sheet gains two `ListItem`s — "Add to/Remove from friends" (`Star`/`StarBorder` icon)
and "Add to/Remove from fools" (`VisibilityOff`/`Visibility`) calling the toggles (sheet
stays open so the label flips — dismiss not required).

### 3.7 Strings (all added by WP-U0)

WP-U0 adds `xmlns:tools="http://schemas.android.com/tools"` to the `<resources>` root and
every new string carries `tools:ignore="UnusedResources"` so lint stays green while consumers
land in parallel; WP-U4 strips the markers. Full list (names frozen; en text as given):

```
settings_density              Layout density
settings_density_compact      Compact
settings_density_comfortable  Comfortable
settings_density_cozy         Cozy
settings_chat                 Chat
settings_nick_colors          Colored nicknames
settings_nick_colors_desc     Color sender names in chats.
settings_palette_default      Default palette
settings_palette_vivid        Vivid palette
settings_palette_pastel       Pastel palette
settings_nick_color_overrides Nick color overrides
settings_show_jpq             Show join/part messages
settings_show_jpq_desc        Show join, part, and quit events in channels.
settings_people               People
settings_friends              Friends
settings_fools                Fools
settings_fools_mode           Fools’ messages
settings_fools_collapse       Collapse
settings_fools_collapse_desc  Show a tappable placeholder.
settings_fools_hide           Hide
settings_fools_hide_desc      Remove their messages entirely.
friends_title                 Friends
fools_title                   Fools
nick_colors_title             Nick colors
manage_add_nick_hint          Nickname
manage_add                    Add
manage_remove                 Remove
manage_color_auto             Auto (no override)
manage_empty_friends          No friends yet. Add a nick above or use a member’s profile.
manage_empty_fools            No fools yet. Add a nick above or use a member’s profile.
manage_empty_colors           No overrides yet. Add a nick to pick its color.
chat_fool_hidden              %1$s · hidden
chatlist_friends              Friends
chatlist_fools                Fools (%1$d)
channelinfo_fools_section     Fools (%1$d)
channelinfo_add_friend        Add to friends
channelinfo_remove_friend     Remove from friends
channelinfo_add_fool          Add to fools
channelinfo_remove_fool       Remove from fools
plurals settings_nick_count   one: %d nick / other: %d nicks
```

---

## 4. Work packages

Waves: **U0 (serial) → [U1 ∥ U2 ∥ U3] → U4 (serial)**. File ownership is exclusive per wave;
no file appears in two packages of the same wave. Every WP: `nix develop -c ./gradlew build`
green (includes `:app:lintDebug`, warningsAsErrors) before handoff.

### WP-U0 — contracts, storage, theme tokens, strings (serial)

Owns: `plans/10-contracts.md` (append §Round 4 = section 1 above), `data/prefs/Settings.kt`,
`data/prefs/PreferencesStore.kt`, `ui/nav/Routes.kt`, `ui/theme/Spacing.kt` (new),
`ui/theme/NickColor.kt`, `ui/theme/MotdTheme.kt`, `MainActivity.kt`,
`app/src/main/res/values/strings.xml`.
Tests (new): `app/src/test/.../ui/theme/SpacingTest.kt`,
`app/src/test/.../ui/theme/NickColorResolveTest.kt`,
`app/src/test/.../data/prefs/NickJsonCodecTest.kt`.

Acceptance:
- Round 4 block appended to plans/10 verbatim; all signatures compiled.
- `SpacingTest`: exact token values per density (table 2.1); invariant
  `bubbleAvatarColumn == bubbleAvatar + 8.dp` for all three.
- `NickColorResolveTest`: disabled→fallback; override beats hash; DEFAULT palette with no
  override `== nickColor(nick, isDark)` for a sample of nicks × both modes; `hueColor`
  clamps/renders each of the 12 picker hues; `avatar()` ignores the enabled flag.
- `NickJsonCodecTest`: set/map round-trips, garbage → empty, hue coercion into 0..359.
- Repository behavior (assert via codec-level unit tests + implementation review):
  friend/fool disjointness on write; empty collection removes its key.
- Existing tests and previews compile untouched (all new params defaulted).

### WP-U1 — settings UI (parallel)

Owns: `ui/settings/SettingsScreen.kt`, `ui/settings/SettingsViewModel.kt`,
`ui/settings/ManageNicksScreen.kt` (new), `ui/settings/ManageNicksViewModel.kt` (new),
`ui/settings/NickHuePicker.kt` (new), `ui/nav/NavGraph.kt`.
Tests (new): `app/src/test/.../ui/settings/NickInputTest.kt` (`sanitizeNickInput`),
`app/src/test/.../ui/settings/ManageNicksViewModelTest.kt` (against an in-memory fake
`SettingsRepository`; add/remove/setHue routed per kind, state mapping).

Acceptance: sections/controls per 3.1–3.4; radio/switch rows reuse the existing private
composables; previews added for the new screen + dialog; no other WP's files touched.

### WP-U2 — chat rendering: density + nick colors + fools/friends in messages (parallel)

Owns: `ui/chat/ChatViewModel.kt`, `ui/chat/ChatScreen.kt`, `ui/chat/MessageList.kt`
(placeholder composable lives here), `ui/chat/ChatModels.kt`,
`ui/components/MessageBubble.kt`, `ui/components/Avatar.kt`, `ui/components/Composer.kt`,
`ui/components/SystemEventPill.kt`.
Tests (new): `app/src/test/.../ui/chat/MessageFilterTest.kt` (`keepMessage`,
`isFoolSender`: JPQ on/off, HIDE vs COLLAPSE, isSelf and system-kind exemptions,
normalization case-insensitivity).

Signature changes (exactly these):
- `ChatViewModel`: +`settingsRepository: SettingsRepository` ctor param; `messages` filtered
  per 2.5; +`val settings: StateFlow<Settings>`.
- `MessageList`: +`friends: Set<String>`, `fools: Set<String>`, `foolsMode: FoolsMode`,
  `expandedFools: Set<Long>`, `onToggleFool: (Long) -> Unit` (all after `highlightMsgid`,
  defaulted where sensible for previews).
- `ChatContent`: threads the above from `viewModel.settings`; hoists `expandedFools`.
- `MessageBubble`: +`senderIsFriend: Boolean = false`; density tokens + `LocalNickColors`
  per 2.1/2.2; drops its `isSystemInDarkTheme` usage.
- `Avatar`/`Composer`/`SystemEventPill`: token + color-scheme swaps only.

Acceptance: COMFORTABLE renders pixel-identical to current (manual preview diff); collapse →
tap → bubble works; HIDE leaves no separator artifacts (filtered upstream); friend star only
on `showSender && !isSelf` rows; `MessageFilterTest` green.

### WP-U3 — chat list, member list, notifications (parallel)

Owns: `ui/chatlist/ChatListScreen.kt`, `ui/chatlist/ChatListRowItem.kt`,
`ui/chatlist/ChatListViewModel.kt`, `ui/chatlist/ChatListSectioning.kt` (new),
`ui/channelinfo/MemberSectioning.kt`, `ui/channelinfo/ChannelInfoScreen.kt`,
`ui/channelinfo/ChannelInfoViewModel.kt`, `service/MotdNotifications.kt`.
Tests: `app/src/test/.../ui/chatlist/ChatListSectioningTest.kt` (new — precedence pinned >
friend > fool > regular; channels never classified; order preservation),
`app/src/test/.../ui/channelinfo/MemberSectioningTest.kt` (extend — fools extraction, empty
fools = existing behavior, friend membership does not move sections),
`app/src/test/.../service/NotificationDecisionTest.kt` (new — `shouldPostNotification` truth
table: 4 inputs, fool beats friend, friend beats mute, foreground beats all).

Acceptance: sections render per 3.5/3.6 with collapse state local to the screen; sheet
toggles write through the repository; `MotdNotifications` uses `shouldPostNotification`;
all listed tests green.

### WP-U4 — close-out (serial)

Owns: `app/src/main/res/values/strings.xml` (remove every round-4
`tools:ignore="UnusedResources"`; drop the `xmlns:tools` attr if nothing else uses it).
Acceptance: full `nix develop -c ./gradlew build` + `:app:lintDebug` 0 warnings / 0 errors;
if any round-4 string is genuinely unreferenced, delete it (and report which WP dropped the
usage) rather than re-ignoring.

---

## 5. Risks

1. **strings.xml mid-wave lint** — `warningsAsErrors` + `UnusedResources` would break the
   build between U0 and the consumers. Mitigated: per-entry `tools:ignore` markers, stripped
   by U4.
2. **Casemapping** — `normalizeNick` is plain lowercase; RFC 1459 treats `[]\~` ≡ `{}|^`.
   A friend registered as `foo[away]` won't match `foo{away}`. Accepted v1 simplification
   (consistent with existing `aggregateReactions`/`nickColor` behavior); full
   `Isupport.normalize` would need a live client in pure UI paths.
3. **PagingData re-filter on settings change** — toggling JPQ/fools-mode re-emits the stream
   and the reversed list may lose fine scroll position. Settings changes happen off the chat
   screen in practice; accepted.
4. **Unread badges count hidden messages** — DAO unread/mention counts ignore fools/JPQ
   filtering, so a badge can show with nothing new visible. Fixing requires pushing the sets
   into the `observeChatList` SQL; deferred (would force the Room-table storage choice).
5. **`runBlocking` settings read in `MotdNotifications`** — matches the existing blocking
   buffer lookup on the same path; single DataStore read, bounded.
6. **Expanded-fool state is ephemeral** (`remember`, keyed by row id set) — config change
   re-collapses. Accepted; a custom `Saver` for `Set<Long>` is a follow-up nicety.
7. **CompositionLocal defaults in previews** — previews not passing the new `MotdTheme`
   params get COMFORTABLE/DEFAULT, i.e. today's rendering; no preview churn.
8. **Contrast of palette/override colors** — fixed S/L per mode keeps legibility in the same
   band as the existing generator; PASTEL-light is the tightest (verify in preview, floor
   l at 0.50).
9. **`ChatListRow.displayName` vs nick** — QUERY buffer displayName is the nick today
   (`ensureQueryBuffer`); if display renaming ever lands, classification should switch to
   `BufferEntity.name`. Noted in `sectionChatList` kdoc.

## 6. Open product decisions (recommendations made; confirm with user)

1. **Friends/fools scope: global vs per-network** — **Recommended: global.** Small personal
   lists; the same human is usually the same nick across a user's networks (typically one
   bouncer); per-network keying would add a network picker to every manage surface and split
   the member-sheet toggle semantics. Revisit only on real collision reports.
2. **Fools default: collapse vs hide** — **Recommended: COLLAPSE** (user-switchable to HIDE).
   Collapse preserves conversational context (replies to fools still make sense) and is
   reversible per message; hide is the opt-in stronger mode.
3. **Nick-color override scope** — **Recommended: override the hue only**; saturation/
   lightness always come from the active palette + light/dark mode. Keeps every override
   legible in both modes and makes the picker a single 12-swatch row instead of a full color
   picker.
4. **Friends and notifications** — **Recommended: friends bypass buffer mute only**; they do
   NOT create notifications for un-mentioned channel messages (that would change
   `EventProcessor.maybeNotify` and be noisy). Fools are fully silenced, including DMs and
   mentions.
5. **Avatars when nick coloring is off** — **Recommended: avatars keep generated/override
   colors**; the toggle governs sender-name text only. All-neutral avatars would gut the
   chat list.
6. **Pinned + friend/fool precedence** — **Recommended: pinned wins** (explicit user action
   outranks list membership); a pinned fool stays visible under Pinned.

# 09 — Work packages

> **Historical design record.** Directory ownership and work-package boundaries
> in this file applied only to the initial parallel build. They do not constrain
> current maintenance; follow repository `AGENTS.md` and preserve active edits.

Execution contract for implementation agents: read `00-overview.md`, `10-contracts.md`, your
WP's doc(s), and repo `AGENTS.md` before writing code. Ownership is directory-exclusive —
never touch files outside your set. Acceptance = your checklist + `./gradlew build` green.

Dependency graph:

```
WP1 → {WP2, WP4, WP6, WP9} ;  WP2 → WP3 ;  WP6 → {WP7, WP8} ;  {WP3, WP4} → WP5 ;  all → WP10
```

Waves: WP1 alone → wave A: WP2 ∥ WP4 ∥ WP6 ∥ WP9 → wave B: WP3 ∥ WP7 ∥ WP8 → WP5 → WP10.

---

## WP1 — Skeleton + frozen contracts (serial; everything blocks on it)

**Docs:** 01, 08, 10.
**Owns:** repo root (`settings.gradle.kts`, root `build.gradle.kts`, `gradle.properties`,
wrapper files incl. jar), `gradle/libs.versions.toml`, `.github/workflows/*`, `irc/build.gradle.kts`,
`app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, app resources
(`strings.xml`, launcher icon: adaptive icon, indigo background + white "MOTD" monogram
vector), `MotdApplication.kt` (@HiltAndroidApp), `MainActivity.kt` (theme + NavHost with
placeholder composables), `di/*` (AppModule/DbModule/IrcModule binding interfaces to
`internal` stub impls annotated `@Deprecated("WP10 removes")`), and ALL contract files from
plans/10 as compilable code (`:irc` proto/event/transport/client shells with TODO() bodies;
`:app` entities, DAO interfaces, repo interfaces, ConnectionManager/IrcEventSink/
TypingTracker/ForegroundBufferTracker/ChatHistoryMediatorFactory/PushPrefs interfaces,
Routes, Settings types). WP1 also pre-creates in-place-implemented shells handed off to later
WPs: `service/IrcForegroundService.kt`, `service/BootReceiver.kt`, `service/ReplyReceiver.kt`
(no-op stubs so the manifest's class references lint clean; WP5 fills them in) and the
placeholder screen composables in WP7/WP8 packages (WP6's NavGraph references them).
**Acceptance:** `./gradlew build` green in CI with stubs; ci.yml + release.yml lint-valid;
manifest matches plans/05 verbatim; no version deviates from plans/01.

## WP2 — `:irc` proto + transport (wave A)

**Docs:** 02. **Owns:** `irc/.../proto/` impl, `irc/.../transport/`, `irc/src/test/.../proto/`,
`.../transport/`.
**Acceptance:** parser/serializer/Isupport tests from plans/02 pass (`:irc:test`); tag-escape
table round-trips exactly; oversize serialize throws.

## WP3 — `:irc` client + extensions (after WP2)

**Docs:** 02, 03. **Owns:** `irc/.../client/`, `irc/.../ext/`, `irc/src/test/.../client/`.
Implements the `IrcClient` shell in place — public signatures frozen.
**Acceptance:** scripted-conversation tests from plans/02 pass: registration+SASL(+base64
exactness), BOUNCER BIND ordering, labeled correlation, chathistory nested-batch reassembly,
CAP NEW, 433 retry, watchdog, typing throttle.

## WP4 — Room data layer (wave A)

**Docs:** 04. **Owns:** `app/.../data/db/` (MotdDatabase + DAO bodies), `app/.../data/repo/`
implementations, `app/.../data/prefs/`, `app/src/test/.../data/`.
**Acceptance:** Robolectric tests from plans/04 pass in `:app:testDebugUnitTest` (dedup
uniqueness, echo update-in-place, chat-list counts, FTS round-trip, advanceReadMarker);
link-preview parser fixtures pass. WP4 must NOT edit Hilt modules (`di/` is WP1/WP10-owned);
expose implementations as `@Inject constructor` classes so WP10 can rebind them.

## WP5 — Service + sync (after WP3 + WP4)

**Docs:** 05, 04 (EventProcessor/catch-up/mediator sections). **Owns:** `app/.../service/`,
`app/.../data/sync/`, `app/src/test/.../service/`, `.../sync/`.
**Acceptance:** EventProcessor mapping tests, echo end-to-end single-row test, catch-up
pagination test, backoff virtual-time test — all green.

## WP6 — Theme + navigation + chat list (wave A)

**Docs:** 07 (theme/ChatList/nav). **Owns:** `app/.../ui/theme/`, `app/.../ui/nav/NavGraph.kt`,
`app/.../ui/chatlist/`, `app/.../ui/components/` files: `Avatar.kt`, `Badges.kt`,
`ConnectionBanner.kt`, `EmptyState.kt`.
**Acceptance:** previews compile & render fake rows; NavGraph routes every Route type to a
screen. WP1 pre-creates placeholder screen composables in WP7/WP8's packages; WP6's NavGraph
calls those composables directly, and WP7/WP8 later fill in their own files — so nobody edits
outside their ownership.

## WP7 — Chat screen + composer + image viewer (after WP6)

**Docs:** 07. **Owns:** `app/.../ui/chat/`, `app/.../ui/imageviewer/`, `app/.../ui/components/`
files: `MessageBubble.kt`, `SystemEventPill.kt`, `Composer.kt`, `NickAutocomplete.kt`,
`ReactionRow.kt`, `TypingIndicatorRow.kt`, `LinkPreviewCard.kt`, `DaySeparator.kt`.
**Acceptance:** preview covering bubble grouping, system pill collapse, reply, reactions,
inline image, preview card, failed message; composer autocomplete unit test (pure ranking
function); `/` command parser unit test (pure function in `ui/chat/CommandParser.kt`).

## WP8 — Onboarding + settings + search + channel info (after WP6)

**Docs:** 07. **Owns:** `app/.../ui/onboarding/`, `app/.../ui/settings/`, `app/.../ui/search/`,
`app/.../ui/channelinfo/`.
**Acceptance:** previews compile; onboarding wizard state machine unit test (pure reducer);
member-list prefix sectioning unit test.

## WP9 — Push (wave A; only needs WP1)

**Docs:** 06. **Owns:** `app/.../push/`, `app/src/test/.../push/`.
**Acceptance:** **RFC 8291 Appendix A vector test passes** (hard gate; fetch vectors from the
RFC itself); encrypt/decrypt round-trip padding test; registrar logic test with fake
ConnectionManager.

## WP10 — Integration + polish (serial, last)

**Docs:** all. **Owns:** `di/*` (handoff from WP1: replace stub bindings with real impls),
`README.md` (usage/screenshots section), deletion of `@Deprecated` stubs, any cross-package
compile fixes (report contract violations rather than papering over them), final
`MainActivity`/NavGraph glue, app icon polish.
**Acceptance:** full CI green; `:app:assembleDebug` artifact produced; manual smoke checklist
written into README (install → onboard against Libera → join #libera → send/receive → search
→ theme switch); release-workflow dry-run notes (act or manual tag on a fork) documented.

---

## Conflict rules

- `ui/components/` is split file-by-file between WP6 and WP7 as listed — no shared files.
- WP1 is the only WP that creates files in another WP's future directories (placeholder screen
  stubs, IrcClient shell); those WPs then own them exclusively.
- `di/` belongs to WP1 first, WP10 last; nobody else touches it. WP impls must be
  constructor-injectable so rebinding is a one-line Hilt change.
- If you need something outside your ownership: STOP and report; do not edit.

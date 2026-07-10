# IRC chat wallpaper source pack

Scalable source assets for configurable chat backgrounds. These are source SVGs, not
runtime-loaded app assets:

- `irc-light-classic.svg`
- `irc-light-network.svg`
- `irc-light-pixel.svg`
- `irc-dark-classic.svg`
- `irc-dark-network.svg`
- `irc-dark-pixel.svg`

Use these as the visual reference for the app implementation. Do not add an SVG parser
dependency just to render them. Prefer a Compose Canvas renderer that draws the motifs
procedurally from preset definitions, or convert the source SVGs to Android
VectorDrawable XML if fixed resource drawables are preferred.

## Follow-up agent goal

Make MOTD chat backgrounds configurable using the six scalable IRC wallpaper presets in
this directory. Preserve the subtle messenger-wallpaper look: low contrast, no readable
text, no logos, and no interference with message readability.

## Prompt for the implementation agent

You are working in `/home/trev/Workspace/motd`. Read `AGENTS.md`,
`plans/00-overview.md`, `plans/09-work-packages.md`, `plans/10-contracts.md`, and the
relevant UI/settings docs before editing.

Implement configurable chat backgrounds using the source SVGs in
`docs/assets/chat-wallpapers/`:

1. Add a settings model for chat wallpaper selection:
   - `ChatWallpaper` enum or equivalent with at least `NONE`, `CLASSIC`, `NETWORK`,
     and `PIXEL`.
   - Persist it in `DataStoreSettingsRepository` alongside the existing `Settings`
     fields in `app/src/main/kotlin/io/github/trevarj/motd/data/prefs/`.
   - Add `SettingsRepository.setChatWallpaper(...)`.

2. Add a settings UI control under the existing Chat section in
   `app/src/main/kotlin/io/github/trevarj/motd/ui/settings/SettingsScreen.kt`.
   Use the repo's existing `RadioRow`/section patterns. Add strings to
   `app/src/main/res/values/strings.xml`.

3. Render the selected wallpaper behind the timeline in
   `app/src/main/kotlin/io/github/trevarj/motd/ui/chat/`.
   Prefer a `ChatWallpaperBackground` composable implemented with Compose `Canvas`,
   not a runtime SVG loader and not bitmap PNGs. Use the source SVG files only as the
   visual reference for motif placement, colors, and density.

4. Respect theme:
   - `NONE` should keep the current plain `MaterialTheme.colorScheme.background`.
   - Light themes use the `irc-light-*` palette.
   - Dark and AMOLED themes use the `irc-dark-*` palette; for AMOLED, keep the base
     black and draw only low-alpha motifs.
   - Terminal color schemes should still look restrained; use `MaterialTheme`
     colors where possible instead of hard-coding everything.

5. Keep readability first:
   - Draw behind the `MessageList`, not over messages or the composer.
   - Use low alpha and do not animate the background.
   - Do not affect scroll anchoring, paging keys, read-marker placement, long-press
     actions, or composer IME behavior.

6. Validation:
   - Run `nix develop -c ./gradlew build`.
   - Add or update focused tests for preference persistence if this repo has existing
     settings tests covering similar enum fields.
   - Compile previews if available.

Constraints:

- Do not add dependencies or change pinned versions.
- Do not use kapt, OkHttp, or minification changes.
- Do not edit contracts in `plans/10-contracts.md`.
- Do not load SVGs at runtime unless an existing dependency already supports it and the
  pinned dependency list explicitly allows that usage.
- Leave the source SVGs in `docs/assets/chat-wallpapers/` for design traceability even
  if the runtime implementation is Compose Canvas.

---
name: motd-chat-wallpapers
description: "Generate, prompt, review, or integrate MOTD Android chat wallpapers with imagegen: compatible transparent motif atlases, deterministic seamless builtin tiles, and saved wallpaper prompts. Use for MOTD chat wallpaper/imagegen requests, not MOTD logos, IRC avatars, or unrelated desktop wallpapers."
license: MIT
compatibility: "Requires a MOTD checkout; composition uses its pinned Nix sprite-studies shell (Node 22 and ImageMagick). Image generation requires an actually available imagegen tool."
---

# MOTD Chat Wallpapers

## Locate the contract

Discover the MOTD checkout; the canonical local copy is `/home/trev/Workspace/motd`.
Set `MOTD_ROOT` to that verified checkout, read its project guidance, and run the
commands below from that root, not an assumed initial working directory.
Paths below are relative to it. Re-read current sources before changing artwork.

- `tools/wallpapers/production-prompts.json`: use `periodicAtlasGeneration.themes`
  and `.details` for current prompts; top-level `themes` preserves historical full-tile prompts.
- `tools/wallpapers/prototype-prompts.json`: original Retro Chat/Memes studies;
  Study 02 was selected. `references/` holds approved references, `sources/` historical masters,
  and `atlases/` current inputs, all under `tools/wallpapers/`.
- `tools/wallpapers/generate-wallpapers.mjs` owns extraction, density, wrapping, and export.
  `docs/assets/chat-wallpapers/README.md` describes rendering/import; prefer actual script semantics.
- `output/imagegen/` contains logo/avatar studies, not the canonical wallpaper prompt corpus.

## Generate motifs, not a finished repeat

Reuse the closest saved atlas prompt and reference; retain distinctive subjects
and accurately spelled requested text (especially MOTD's logo and `/motd` wordmark).
Adapt this single template; replace the theme and all sixteen subject slots:

```text
Use case: stylized-concept.
Asset type: transparent motif atlas for a seamless MOTD Android chat wallpaper.
Theme: [THEME]. Create a precise 4 by 4 atlas of exactly 16 SEPARATE COMPLETE motifs, one intact recognizable motif per cell, in reading order.
Subjects: [01]; [02]; [03]; [04]; [05]; [06]; [07]; [08]; [09]; [10]; [11]; [12]; [13]; [14]; [15]; [16].
Style: playful hand-drawn crisp thin rounded MONOCHROME BLACK OUTLINE doodles, legible at phone size, varied silhouettes, sparse internal detail, no heavy solid masses.
Each motif centered in its evenly spaced cell with at least 15 percent genuinely transparent margin on EVERY side. Keep all contours complete and wholly inside the cell, including cables, tails, and text.
Actual transparent RGBA background, gutters, and outlined interiors; BLACK INK ONLY. No white fills, opaque background, checkerboard pixels, paper, texture, shadows, shading, gradients, color, grid lines, cell borders, labels, watermark, UI mockup, overlap, clipping, or art touching cell boundaries. Text only where explicitly specified as a subject; spell it exactly.
Output only the isolated motif atlas. Do not pack a dense wallpaper or attempt seamless edge wrapping; the deterministic compositor will do that.
```

If imagegen is unavailable, provide the adapted prompt, reference/input paths,
and composition/integration handoff; explicitly say no image was generated.
Never invent generation calls, files, dimensions, or successful checks.
With imagegen, save the actual output as `tools/wallpapers/atlases/<theme>.png`;
inspect real alpha and all 16 cells, not a checkerboard-looking proof. Record native dimensions.
Reject opaque/checkerboard backgrounds, white-filled interiors, cut motifs, or missing cells;
do not ship a superficially repaired full-tile image in place of an atlas.

Reuse `atlases/details.png` and its saved prompt for tiny accents. The compositor
extracts actual transparent gutters, scales/rotates motifs, and packs a seeded
irregular mix: all 16 unique motifs, at least 130 major and 100 tiny placements,
with three-pixel toroidal ink clearance. Imagegen must not create a sparse final
layout, giant centerpiece, or grid; shared details plus composition supply density.

## Pinned export and proof commands

Set `THEME` to a registered hyphenated ID (e.g. `deep-space`); a new ID must first
be added to the generator's `names`. Only `--check` and `--preview` are supported.

```sh
cd "$MOTD_ROOT"
# Existing, unchanged exports: byte-check and write proofs without replacing assets.
nix develop .#sprite-studies -c env WALLPAPER_THEME="$THEME" node tools/wallpapers/generate-wallpapers.mjs --check --preview
# Changed/new atlas: EXPORTS THE ASSET and writes proofs; not read-only preview.
nix develop .#sprite-studies -c env WALLPAPER_THEME="$THEME" node tools/wallpapers/generate-wallpapers.mjs --preview
# After inspecting those proofs: recompute and byte-check the shipped lossless indexed PNG.
nix develop .#sprite-studies -c env WALLPAPER_THEME="$THEME" node tools/wallpapers/generate-wallpapers.mjs --check
# Export without proofs, when requested.
nix develop .#sprite-studies -c env WALLPAPER_THEME="$THEME" node tools/wallpapers/generate-wallpapers.mjs
```

Omit the entire `env WALLPAPER_THEME="$THEME"` segment to process all registered themes.
Do not overwrite unrelated/user-edited assets. There is no preview-only candidate
mode: `--check --preview` fails on stale/missing exports before writing that theme's
proofs; `--preview` without `--check` replaces exports after validating requested themes.
Inspect `build/wallpaper-previews/export-qa/<theme>-3x3-white.png`, `-3x3-dark.png`,
and `-offset.png`: all horizontal/vertical joins and four-way corner joins must
continue whole contours, with no mismatched halves, blank corridors, visible grid,
touching ink, or giant motifs. Check legibility and dense varied scale at phone size.
The script independently compares every 3×3 pixel and verifies decoded PNG alpha;
this complements, not replaces, visual review. Do not copy opaque proof PNGs into assets.

## Runtime and integration

Export is lossless 2048×2048 black/8-bit-alpha indexed PNG, RGBA when decoded, with
the approved alpha unchanged. The pinned Node encoder uses alpha as the 8-bit
palette index, 256 black RGB entries, exact tRNS values 0..255, filter-zero scanlines,
level-9 deflate and standard CRC32 from `node:zlib`. Do not quantize the palette.
Keep PNG: Android's sampled WebP decoding changes rendered alpha even when the
full-resolution source alpha is exact. Atlas inputs, historical masters and opaque
inspection proofs remain PNG, with a separate ImageMagick PNG proof encoder.
`ChatWallpaperBackground.kt` extracts `ALPHA_8`, repeats at 512dp (128dp thumbnails),
tints ink with theme `onSurfaceVariant`, and applies intensity as direct opacity
over a theme gradient (AMOLED stays black). Never bake tint, white fills, or a backdrop.
Keep the wallpaper's outer unpadded Scaffold origin stable through header/IME changes.

Existing builtin: replace its atlas/export and prompt record; preserve its ID/mappings.
New builtin: update every registration below, not just add a PNG:
- `tools/wallpapers/generate-wallpapers.mjs`: `names`; atlas and shipped `app/src/main/assets/chat-wallpapers/<id>.png`.
- `app/src/main/kotlin/io/github/trevarj/motd/data/prefs/AppearancePrefs.kt`: `ChatWallpaperPreset` and `chatWallpaperPresetFromStored`; serializer writes enum names, so decoding must recognize the new name; preserve existing stored names.
- `app/src/main/kotlin/io/github/trevarj/motd/ui/chat/ChatWallpaperBackground.kt`: `assetName` and exhaustive `gradientColors`.
- Same `ui/chat/ChatWallpaperPicker.kt`: exhaustive `wallpaperLabel`/`wallpaperDescription`; entries drive cards automatically. Add label/description resources in `app/src/main/res/values/strings.xml`.
- `tools/wallpapers/gallery/server.mjs`: `themes` registry for gallery coverage; final assets auto-discover by registered filename. Drafts require `build/wallpaper-previews/manifest.json` entries (`id`, registered `theme`, basename `file`); export-qa proofs are not automatically draft entries.
- Update affected preference/serialization tests and docs; retain enum-driven `app/src/test/kotlin/io/github/trevarj/motd/ui/chat/WallpaperAssetTest.kt` coverage for alpha, dimensions, tint, and fractional-density repeats; follow project verification rules when authorized.

Custom full-color wallpaper is a different path: Appearance's native picker →
`data/prefs/CustomWallpaperStore.kt` private validated copy → `ContentScale.Crop`
with intensity opacity, not tiled/tinted builtin registration. Local image names
are transient and excluded from backups; keep the builtin fallback. Do not force
photos through monochrome atlas composition.

Record exact used prompts, tool, references, atlas path, and actual native size in
`production-prompts.json` under `periodicAtlasGeneration`; preserve historical prompts,
studies, and masters. Report generated versus handoff-only artifacts and only checks actually run.

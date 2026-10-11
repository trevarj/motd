# motd-chat-wallpapers manual evaluation

## Clean-session protocol

Run each case in an independent session with no prior conversation or previous
case artifacts. Discover the MOTD root (canonical local checkout:
`/home/trev/Workspace/motd`); do not assume the initial working directory is it.
For comparison, use the same prompt, model, source snapshot, tool availability,
and writable-copy policy twice: baseline without this candidate, then explicitly
load `/home/trev/Workspace/motd/.agents/skills/motd-chat-wallpapers/SKILL.md`
in the candidate session. The repository handbook links this local skill;
automatic discovery/loading by any client has not been verified.
Record explicit skill loading, concrete answer/artifacts, expected outcomes missed,
and forbidden outcomes observed. Do not infer improvement from skill loading alone.

Text-only stateless completions are also useful: provide identical relevant
source excerpts to both runs (saved atlas/history prompts, generator flags and
write behavior, render/prefs/picker/gallery contracts), adding the skill only to
the candidate run. Label these **text-only**, not tool-enabled generation,
filesystem, or automatic-trigger verification. No imagegen tool means no generated
image claim. Single candidate sessions can check the rubric without comparison;
they do not establish an improvement over baseline.

Tool-enabled runs may read canonical sources; generation/export cases need a
disposable MOTD copy with the same flake.lock and no unrelated edits. Inspect
actual artifacts and command results. Do not replace canonical assets, install
skills, or create target symlinks as part of evaluation. The observations below
predate relocation; record any new results separately after exercising the cases.

## Positive cases

### P1 — New theme, unavailable imagegen

Prompt: `Make a compatible MOTD chat wallpaper for a cozy library theme. There is no imagegen tool in this session. Give me a copyable prompt and an end-to-end handoff; do not modify files.`

Expected: load/use the skill; discover the root; adapt a saved current atlas
prompt into exactly 16 complete thin black-outline motifs in 4×4 cells, actual
alpha/interiors and generous gutters, no checkerboard pixels. Reuse shared
`details.png`, then deterministic packing/wrapping. Explain the 2048 black/alpha
export and tinting; identify all new-builtin callsites including stored-name
mapping and gallery registration. Explicitly state no image generated.
Forbidden: fabricated imagegen call/output/QA; a historical dense full-tile
prompt as current production; opaque paper or white-filled motifs; a new framework.

### P2 — Existing builtin and dangerous preview flags

Prompt: `I have replaced the Deep Space atlas in MOTD. Give exact pinned commands to preview, export, and check it; do not run them or change registrations. Can --check --preview show this changed atlas before export?`

Expected: `WALLPAPER_THEME=deep-space` and `nix develop .#sprite-studies -c`
from the discovered root; `--preview` exports plus writes proofs, then visual
review and `--check`. Explicitly answer no: stale export fails before that
theme's preview writes. `--check --preview` suits unchanged byte-identical
exports only. No flags exports without proofs. Existing IDs/mappings stay intact.
Forbidden: claim preview is read-only; suggest nonexistent `--output`, `--theme`,
or preview-only flag; run commands despite the request; regenerate every theme.

### P3 — Original prompt provenance

Prompt: `Where are MOTD's original chat-wallpaper imagegen prompts? Inspect output/imagegen too, and tell me what to reuse for a Retro Chat refresh. Read only; do not generate anything.`

Expected: identify `production-prompts.json` and `prototype-prompts.json`,
selected Study 02/references, preserved historical `sources/`, and current
`periodicAtlasGeneration`/`atlases/`. Distinguish logo/avatar records in
`output/imagegen` from wallpaper prompts. Reuse current Retro Chat atlas prompt;
reference originals rather than reproducing the full corpus or claiming reconstruction.
Forbidden: treat output/imagegen avatar prompts as wallpaper sources; erase
history; replace atlas workflow with historical full-tile edge-repair prompts.

### P4 — Proof and render rejection

Prompt: `Review compatibility for a proposed MOTD builtin: its atlas shows white-filled objects over a painted checkerboard, and its exported tile has a blank border. Explain the acceptance checks and runtime contract; do not generate or run checks.`

Expected: reject the atlas despite apparent transparency; require genuine alpha,
complete 16 cells and transparent interiors/gutters. Final tile must wrap without
blank corridors. Name white/dark 3×3 and offset proofs in export-qa; inspect both
axes and four-way corner joins, contour continuity, density and phone legibility.
Mention independent pixel equality and PNG-alpha roundtrip without claiming they
ran. 2048² black/8-bit alpha, RGBA on decode (encoder is grayscale+alpha type 4),
ALPHA_8 repeat at 512dp/128dp thumbnails, theme tint/direct intensity, no baked backdrop.
Forbidden: accept checkerboards as alpha; whiten/remove only perimeter pixels
as a seam fix; ship opaque QA proofs; insist encoder already uses PNG type 6.

## Near-miss cases

- N1: `Generate a new MOTD launcher logo with a ceramic surface; not a chat wallpaper.`
  Expected: do not apply wallpaper workflow; use logo task context. Forbidden:
  16-cell atlas, shared detail packing, chat preset registration.
- N2: `Make modular Pixel Operators IRC avatars for MOTD from output/imagegen.`
  Expected: avatar workflow, no wallpaper-skill trigger. Forbidden: mistake
  avatar prompt files for canonical wallpaper prompts or export chat tiles.
- N3: `Import a full-color photo as my custom MOTD chat background; explain the existing path, no builtin preset and no code changes.`
  Expected: custom native picker/private validated store, full-color Crop and
  intensity opacity, local-only backup exclusion and retained builtin fallback.
  Skill use is allowed to distinguish paths. Forbidden: monochrome conversion,
  atlas/toroidal tiling, 2048-square requirement, enum or gallery registration.
- N4: `Write an imagegen prompt for a full-color 4K desktop mountain wallpaper, unrelated to MOTD.`
  Expected: ordinary desktop request, no wallpaper-skill trigger. Forbidden:
  black-alpha Android mask constraints, MOTD source reads or integration steps.

## Observed checks — 2026-10-11

Before relocation, four independent text-only baseline/candidate pairs used the
same model and verified source context; only the candidate received the skill content.
The new-theme and changed-atlas candidates supplied scoped pinned commands, real-alpha
rejection, and named visual proofs; baseline had a command placeholder and
less explicit QA. Both kept custom photos full-color and avoided the wallpaper
workflow for a launcher-logo request. Neither claimed generation or executed checks.
This checks instruction behavior, not automatic skill discovery or image generation,
and does not verify loading from the current repo-local path.

Before relocation, the existing workflow was separately exercised in the MOTD checkout with
`nix develop .#sprite-studies -c env WALLPAPER_THEME=deep-space node tools/wallpapers/generate-wallpapers.mjs --check`:
16 complete motifs, 135 major + 144 tiny placements, 13.0% ink; every 3×3 pixel matched.
A historical `nix flake check path:/home/trev/Workspace/agent-skills --no-update-lock-file`
passed the personal skill repository's structure check with its then-present target
symlinks. Those symlinks have been removed; that result does not validate this
repo-local skill location. No checks were run as part of relocation.

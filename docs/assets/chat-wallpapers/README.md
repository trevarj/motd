# IRC wallpaper system

Appearance → Wallpaper → Wallpaper Workshop creates editable, seamless outline patterns.
Start with MOTD or one of eight themed recipes, or make a new design from bundled motifs and
local SVGs. In **Icons**, open a motif and choose **Lead** or **Filler**. Leads set the
main composition; fillers occupy the gaps between them. **Pattern → Density** adds fillers
without shrinking the leads. Without fillers, it adjusts lead spacing instead.
The MOTD starter uses hand-drawn graffiti lettering and doodles with small pen marks
between the larger subjects. It starts at maximum density; lower it to open up the pattern.
Overall and fixed per-icon size range from 25% to 400%. Above 250%, the pattern
allows larger motifs and opens lead spacing when the overall size or all lead sizes
are high. **Auto** varies size and rotation per placement using the design's seed.
Choose **Fixed** to set a motif's own size or angle.
Saved Rings designs now render as Scattered.
In **Color → Texture**, choose None, Specks, Comic paper, Grain, Linen, Scanline, or Stars.
Texture layers under the icons, works with theme or custom colors, and saves with the design.
Wallpaper intensity still changes only the icons.
Save updates every chat already linked to the design; Duplicate creates an independent copy.
Apply makes the design the global default or assigns it to a single conversation.
From a channel or DM, open Wallpaper in the overflow menu. Inherit follows the global choice;
None disables wallpaper only for that conversation.
The Chat wallpaper picker shows thumbnails for scenic options and the active local photo.

The offline catalog and its licenses live in `app/src/main/assets/wallpaper-icons/`.
Picked SVGs must be self-contained UTF-8 outlines of at most 16,384 bytes. Solid fills,
scripts, external references and unsupported SVG features are rejected. The app owns
validated SVG bytes rather than provider URLs. Configuration backups carry saved designs,
their referenced SVGs and explicit chat choices identified by network and chat identity.

The pattern repeats every 512dp in chat and full previews, every 256dp in Starter
cards so the icons are easier to recognize, and every 128dp in other artwork cards.
Theme backgrounds use the guarded MOTD gradient (black in AMOLED);
custom backgrounds remain exact opaque sRGB. Pattern intensity changes only motif ink.
Packing uses circles that enclose each SVG's visible strokes, with clearance across
both seams and at corners. Leads keep their layout anchors. The widest gaps can
fit medium copies of Auto-sized leads; Auto fillers fit to the remaining space
and can shift slightly to fit better. After placement, up to two refinement passes
use a small raster of the actual strokes to move fillers into the largest visible gaps.
Finally, a wrapped stroke mask lets fillers grow into the space between outlines.
Auto fillers may try a radius up to 25% larger than their initial target, subject
to the same maximum radius and clearance. Vector outlines are checked for overlap
independently of the tile's output resolution.
Fixed sizes are never enlarged past their setting. Changing density may move fillers,
but the main lead composition stays anchored.

Historical PNG studies and source atlases are separate from the app's pattern renderer.
The legacy generator writes finished studies to `build/wallpaper-previews/legacy-finals/`
by default, and the historical gallery reads them there:

```sh
nix develop .#sprite-studies -c node tools/wallpapers/generate-wallpapers.mjs --preview
nix develop .#sprite-studies -c node tools/wallpapers/generate-wallpapers.mjs --check
```

The global Appearance sheet still offers None, the five scenic wallpapers, and photo import.
Import/Change copies a validated image into private storage. Photo intensity controls image
opacity, not the retained wallpaper underneath. Remove returns to that retained choice.
Cancelling or a failed import preserves the current image. Photos and their filenames never
travel in configuration backups.

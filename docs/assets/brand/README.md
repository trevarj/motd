# motd brand assets

The `/motd` wordmark uses **Roboto Bold 700**, converted to filled outlines so
the SVGs and Android vector render identically without an installed font. This
is the same lettering the app itself shows in the title bar, server drawer, and
About screen, where the wordmark is plain bold platform typography rather than a
stylized asset. Symbol lockups omit the leading slash because the speech-bubble
symbol already supplies the slash-command cue.

Source pin:

- Project: [Roboto](https://github.com/googlefonts/roboto-3-classic)
- Release: `v3.015` (`Roboto_v3.015.zip`)
- Font: `unhinted/static/Roboto-Bold.ttf`
- Font SHA-256: `f02bf7a9869c58b9bb61130f55c6b1a489ea85f2ac1de75b3421dcb133bc57f3`
- Style: Bold, weight 700
- License: Apache License 2.0; see [ROBOTO-LICENSE.txt](ROBOTO-LICENSE.txt)

The final assets contain glyph outlines only; the font binary is not vendored.
Use the pinned font and FontTools `pens.svgPathPen` when regenerating lettering,
then preserve the current 2048-unit em advances (`m` 1774, `o` 1156, `t` 693,
`d` 1153, `/` 760) and the 0.048828125 outline scale.

## Raster exports

In-app logos and launcher choices stay **theme-colored matte ceramic**. The
`#motd` chat-list tile keeps its deterministic theme background and highlight;
runtime marks use `ceramicLogoColorMatrix` with their existing theme targets.
Launcher palette backgrounds and the terminal-green artwork are unchanged.

Only splash and README branding use neutral black/white treatment: splash is a
white ceramic mark on black in both OS modes (including status/navigation bars),
and README lockups pair a dark ceramic mark with a light page or a white ceramic
mark with a dark page. Black/white describes polarity, **not flat silhouettes**.

The immutable RGB masters supply the matte ceramic shading as well as geometry.
The smoothed `motd-splash-master.png` supplies splash and README contours with
soft alpha intact; other exports retain `motd-ceramic-master.png` and
`motd-symbol-mask.png`. Original README lettering is composited unchanged in
light mode and RGB-inverted in dark mode at the same 1470×540 layout.

The separately requested F-Droid/store icon is white ceramic on black; these
distribution PNGs do not control Android launcher choices. The site logo,
standalone symbols, favicon, and stacked lockup retain their existing treatment.

Regenerate all branding, including the F-Droid/store icons:

```sh
nix develop .#sprite-studies -c node tools/export-branding.mjs
```

Use `--splash-only` for just the Android splash density assets, `--readme-only`
for the 1470×540 light/dark README lockups, or `--store-only` for the three
distribution icons without touching in-app, launcher, or site artwork. The full
exporter preserves the existing themed ceramic runtime/launcher pipeline.
Verify exports with:

```sh
nix develop .#sprite-studies -c node tools/verify-branding.mjs
```

F-Droid's `fastlane/metadata/android/en-US/images/phoneScreenshots/` uses the
actual full-app light/dark showcase composites from `screenshots/` (chat list,
conversation, attachment chooser), not fabricated UI or a splash replacement.
They retain the existing 1080×2400 dimensions and encoded format. These are
reused captures, not a claim of a fresh runtime capture of the current build.
To capture a fresh set, follow `test/e2e/README.md` and run:

```sh
nix develop -c ./test/e2e/headless.sh showcase
cp screenshots/chat-list.png fastlane/metadata/android/en-US/images/phoneScreenshots/01_chat-list.png
cp screenshots/chat.png fastlane/metadata/android/en-US/images/phoneScreenshots/02_chat.png
cp screenshots/file-uploader.png fastlane/metadata/android/en-US/images/phoneScreenshots/03_file-uploader.png
```

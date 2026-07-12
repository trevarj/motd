# motd brand assets

The `/motd` wordmark uses **IBM Plex Mono Bold Italic 700**, converted to
filled outlines so the SVGs and Android vector render identically without an
installed font. Symbol lockups omit the leading slash because the speech-bubble
symbol already supplies the slash-command cue.

Source pin:

- Project: [IBM Plex](https://github.com/IBM/plex)
- Commit: `2f9ba1b25957d958db71a849e85d72e3ecfb845a`
- Font: `packages/plex-mono/fonts/complete/ttf/IBMPlexMono-BoldItalic.ttf`
- Font SHA-256: `c8b00068fd2fd69bbcf62059148623f09d19e9ada8bf9e8caf1aedd01347046a`
- Style: Bold Italic, weight 700
- License: SIL Open Font License 1.1; see [IBM-PLEX-LICENSE.txt](IBM-PLEX-LICENSE.txt)

The final assets contain glyph outlines only; the font binary is not vendored.
Use the pinned font and FontTools `pens.svgPathPen` when regenerating lettering,
then preserve the current 600-unit monospace advances and optical placement.

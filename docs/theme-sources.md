# Color theme sources

motd adapts these editor and terminal palettes into Material 3 semantic roles. Theme names belong
to their respective projects; palette values are reproduced with attribution and adjusted only
where Android UI contrast requires it.

## Accessibility adaptation

Palette identity is carried by the primary, secondary, and tertiary hues. motd adjusts their tone
when necessary so readable text and meaningful icons meet a 4.5:1 contrast ratio across the
surfaces where they appear. Component boundaries, outlines, and other non-text indicators target
3:1. Success, warning, and error use stable semantic color families instead of borrowing an accent
whose meaning changes between palettes.

Message text uses a stronger **7:1 reading target** against the actual painted bubble, row, reply,
or inline-code fill. Links, nick mentions, and explicit IRC foreground colors are tone-adjusted
with the same hue-preserving rule; explicit IRC backgrounds and reverse formatting stay intact.
Some mid-luminance fills cannot reach 7:1 with any ink, so these retain their palette color and use
the most readable black or white ink, with at least 4.5:1 contrast.

Compact, two-line, and full-width ACTION rows paint their existing nick/attention tint composited
onto the theme canvas as an **opaque text backdrop**. Wallpaper remains visible outside those rows;
its selected intensity controls builtin ink or full-color image opacity directly, including 100%.

Two-line headers keep sender names on one ellipsized line, reserving room for the avatar, friend
indicator, delivery status, and timestamp even in narrow panes with large system fonts.

Decorative monogram letters stay proportional to their fixed avatar discs, including the 20dp
two-line avatar, at enlarged system font sizes. Message and sender text still follow font scaling.

Every dark palette can optionally use **True black backgrounds**. This keeps the selected palette's
accents while replacing the canvas with black and using subtly palette-tinted near-black elevation
layers. The setting is remembered while a light theme is active and takes effect again when the app
returns to a dark theme. Older saved AMOLED selections are read as Dark with True black enabled.

| Family | Variants used | Source | License |
| --- | --- | --- | --- |
| Ayu | Light, Mirage, Dark | <https://github.com/ayu-theme/ayu-colors> | MIT |
| Catppuccin | Latte, Mocha | <https://github.com/catppuccin/catppuccin> | MIT |
| Dracula | Dark | <https://github.com/dracula/dracula-theme> | MIT |
| Everforest | Light/Dark medium | <https://github.com/sainnhe/everforest> | MIT |
| Gruvbox | Light, Dark | <https://github.com/morhetz/gruvbox> | MIT |
| Kanagawa | Lotus, Wave, Dragon | <https://github.com/rebelot/kanagawa.nvim> | MIT |
| Modus | Operandi, Vivendi | <https://github.com/protesilaos/modus-themes> | GPL-3.0-or-later |
| Monokai | Classic | <https://github.com/tanvirtin/monokai.nvim> | MIT |
| Nord | Dark | <https://github.com/nordtheme/nord> | MIT |
| One Dark | Dark | <https://github.com/joshdick/onedark.vim> | MIT |
| Rosé Pine | Main, Moon, Dawn | <https://github.com/rose-pine/rose-pine-theme> | MIT |
| Solarized | Light, Dark | <https://github.com/altercation/solarized> | MIT |
| Tokyo Night | Dark | <https://github.com/enkia/tokyo-night-vscode-theme> | MIT |
| Zenburn | Dark | <https://github.com/jnurmine/Zenburn> | GPL |

## Chat wallpapers

Appearance → Chat wallpaper saves preset and intensity changes immediately. Import image opens the
Android photo picker; Change image replaces it, and Remove image returns to the retained builtin
fallback. Selecting a builtin also removes the custom image; None selects the plain theme canvas.
Images keep their colors, crop to fill the chat and settings previews, and blend over the theme
background at the selected intensity. Imports are validated and copied to private app storage
(up to 20 MiB, 32,768 pixels per side and 100 megapixels); cancelling or a failed import keeps the
current image. Image files and their local references are excluded from configuration backups.

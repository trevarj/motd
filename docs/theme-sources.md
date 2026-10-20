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

Appearance → **Use theme text colors** selects palette-derived text instead of static neutral ink.
It is off by default: ordinary app text—including headings, action labels, input text and placeholders,
supporting text, timestamps, and other metadata—uses near-black `#171717` for light themes and
near-white `#F5F5F5` for dark themes. Turning it on restores the applied palette's background, surface,
and supporting-text hues, including Material You dynamic colors and true-black themes. Both modes
fit ordinary text to normalized surfaces at 4.5:1 and reading text to each actual painted fill,
including selected rows and search-hit highlights. The choice applies live to messages and Appearance
previews, persists across restarts, and travels with configuration backups.

Links, user-configured nickname colors, explicit IRC colors, and error/destructive/status text retain
their meaningful hues. Native interactive accents (such as text buttons, selected tabs, and focused
field labels) keep Material styling. Filled and inverse controls keep foregrounds matched to their
actual backgrounds, and disabled controls keep their disabled styling; neither text-color mode
overrides component content colors globally or changes fonts.

Composer fields and toolbars, ordinary Comfortable incoming bubbles, autocomplete,
day/system-event pills, history overlays, and voice panels use the selected palette's
`secondaryContainer`/`onSecondaryContainer` roles in light, dark, and true-black themes.
Reply previews use `primaryContainer` with matched ink; selected formatting tools use
`primary`/`onPrimary` to remain distinct from the toolbar. Editor text retains the
theme-text preference and is fitted to its actual container, also used by IRC formatting.
Outgoing ownership, mentions, actions/notices, and recording/error cues keep their existing
accent or semantic roles. The chat canvas, app chrome, explicit IRC swatches, and shared
native emoji picker are unchanged.

Reading text uses a stronger **7:1 reading target** against the actual painted bubble, row, card,
topic, search highlight, reply, or inline-code fill. Links, nick mentions, and explicit IRC foreground
colors are tone-adjusted with the same hue-preserving rule; explicit IRC backgrounds and reverse
formatting stay intact.
Some mid-luminance fills cannot reach 7:1 with any ink, so these retain their palette color and use
the most readable black or white ink, with at least 4.5:1 contrast.

Read chat-list message previews are subdued while retaining at least 4.5:1 contrast.
Unread previews keep the stronger reading ink in both text-color modes.

Flattened rows retain their nick/attention tint over the theme canvas. Normal two-line rows
admit wallpaper through a 90%-opaque fill only when contrast is safe; Dimmed rows and
contrast fallbacks stay opaque. Wallpaper intensity still controls builtin ink or full-color
image opacity directly, including 100%.

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

# Color theme sources

motd maps these editor and terminal palettes into Material 3 semantic roles. Theme names belong
to their respective projects; authored palette values are reproduced with attribution and used
unchanged by default. Unspecified Material roles are derived from those colors.

## Authored colors and optional readability

Appearance → **Adjust palette for readability** is off by default. Authored accents, backgrounds,
surfaces, ordinary text, supporting text and outlines stay unchanged in the app, editors and
previews. Authored THEME nickname identities and ordinary link accents also remain exact.
Derived container/control ink and semantic status colors retain their contrast safeguards.
True black remains a separate, explicit dark-surface override.

Enable readability adjustment to fit text and meaningful icons to 4.5:1 contrast, and component
boundaries and other non-text indicators to 3:1. Success, warning and error keep stable semantic
color families instead of borrowing an accent whose meaning changes between palettes.

**Match text to theme** applies only while adjustment is enabled. Its saved default is off,
selecting near-black `#171717` ink for light themes and near-white `#F5F5F5` for dark themes.
Turning it on uses palette text hues instead, including Device colors. Turning adjustment off
uses authored ink without overwriting that saved choice. Both preferences apply live to native
previews, persist across restarts and travel with configuration backups.

Classic/Vivid, generated nickname fallbacks and explicit nickname overrides remain fitted.
Explicit dimming, IRC formatting, nickname-chip foregrounds and search-highlight ink retain
readability protections in either mode. Filled and inverse controls keep foregrounds matched
to their actual backgrounds; disabled styling and fonts are unchanged.

**Current theme colors** offers swatches in the wallpaper workshop, custom bubble colors,
palette editor, nickname editor and IRC formatting. A swatch copies the current RGB value;
adaptive bubble roles and wallpaper defaults still follow future theme changes. Nickname
swatches keep the chosen value for avatar fills while fitting nickname text for contrast.
IRC formatting sends theme swatches as hex colors alongside the standard numbered palette.
Tap or drag in any direction on the color wheel. To scroll, start beside the wheel
or swipe vertically over a slider. Drag a slider horizontally to adjust it.

Composer fields and toolbars, ordinary Comfortable incoming bubbles, autocomplete,
system-event pills, history overlays and voice panels use the selected palette's
`secondaryContainer`/`onSecondaryContainer` roles in light, dark and true-black themes.
Date and new-message separators use accented `primaryContainer`/`onPrimaryContainer`
capsules with the action pills' rounded shape and padding; their existing hairlines remain.
Reply previews also use `primaryContainer` with matched ink; selected formatting tools use
`primary`/`onPrimary` to remain distinct from the toolbar. Editor text stays authored unless
readability adjustment is enabled. Outgoing ownership, mentions, actions/notices and
recording/error cues keep their existing accent or semantic roles.

With readability adjustment enabled, reading text uses a stronger **7:1 reading target**
against the actual painted bubble, row, card, topic, search highlight, reply or inline-code
fill. Links and nick mentions are tone-adjusted with the same hue-preserving rule.
Some mid-luminance fills cannot reach 7:1 with any ink; these retain their palette fill
and use the most readable black or white ink, with at least 4.5:1 contrast.

Read chat-list message previews are subdued while retaining at least 4.5:1 contrast.
Unread previews follow the authored-versus-adjusted reading policy.

Flattened rows retain their nick/attention tint over the theme canvas. At 100% message opacity,
normal tinted Two-line rows and ACTION rows admit wallpaper through their existing 90%-opaque
baseline only when contrast is safe; Dimmed rows and contrast fallbacks use an opaque baseline.
The Transparency and blur message opacity control multiplies that baseline in all layouts, including Comfortable
and nickname-tinted rows; contrast and dimming never override the chosen percentage. Zero removes
ordinary tint, not independently selected wallpaper blur. Incoming PRIVMSG/NOTICE mention fills retain
their exact baseline alpha and do not sample blur. ACTIONs, including mentions, follow the chosen
opacity and blur while retaining their attention colors and semantic rails. Text contrast uses the effective
tint over the known theme canvas; arbitrary wallpaper pixels cannot guarantee the same contrast. Content and child
surfaces do not fade or blur.

**Message background blur** is an independent continuous 0–100 intensity, linearly mapping to
0–32dp. It defaults to **Off** (0), preserving the previous appearance. Lower message opacity reveals
more blur; 0% tint still blurs, and an opaque tint naturally covers the blur beneath it. Each chat can
inherit or override blur separately from opacity and layout; an explicit zero disables global blur.
Android 12+ shares one raw and one native blurred wallpaper layer between the timeline and send
flight. Only the rendered wallpaper and its actual canvas are captured, never foreground content.
Existing backing shapes, grouped corners, morph alpha, rails and the zero-opacity shadow rule stay
unchanged. Source frames and current draw coordinates keep sampling aligned during scroll,
keyboard/runway movement and send flight; composition owns layer lifetime. At Off no capture or
effect is created. Android 8–11 use the labeled existing unblurred-tint fallback, with no software
bitmap-copy loop. Wallpaper intensity separately controls builtin ink or full-color image opacity.

**Transparency and blur**, the final submenu in Appearance, groups the global message pair and
independently saved opacity/blur pairs for chat headers, input
bars, chat-list title/folder chrome, menus/suggestions, bottom sheets, drawers, mini-players,
and floating buttons/orb. New surfaces default to their existing opaque tint with blur Off;
chat-list chrome retains its 60% tint and 32dp blur. The global master also governs the existing
message controls: Off disables all blur and makes every nonzero final backing alpha opaque,
including the Two-line 90% baseline. Explicit 0% remains absent. On restores saved values unchanged.
Foreground ink, artwork, disabled states, native window scrims and transition/idle fades are untouched.
The message group shares one real chat preview. Interface groups share a sticky preview; selecting
a group header, focusing or editing its sliders changes the previewed surface without moving values.
**Link all opacity sliders** and **Link all blur sliders** persist independently. Enabling either
does not synchronize existing values: the next edit to any global slider broadcasts that dimension
to global message backgrounds and all eight interface groups. Unlinking retains the current values.
Per-chat overrides remain independent; fool concealment blur is not a linked slider.

Fool **Blurred** is separate from background styling and linked sliders. It conceals only message
body text (including translations/transcripts), not the bubble backing, sender/avatar, timestamp,
reply card, media, audio controls or reactions. Compact/ACTION retain one paragraph and its exact
rich-text wrapping; only the annotated body range receives 40dp native blur on Android 12+.
Effects Off and Android 8–11 paint that text range opaquely instead. A concealed body tap reveals
without opening its link or invoking the row action; visible independent controls remain usable.
Concealed body text and links are absent from accessibility/selection until reveal.

Header/input backdrops combine wallpaper with the actual scrolling message viewport. Menus
hosted by native sheets/drawers sample that host and fall back to the page outside it. Raw captures
never contain their own consumers. Sheets retain M3's own moving Surface, handle, insets, focus,
gestures and dismissal; a zero-height content marker aligns the backing with that full Surface
instead of the public modifier's pre-drag coordinates. Search opens the controls with live previews.
Global floating controls sample the whole navigation scene across panes, excluding their own
sibling overlay layer. Translucent FABs and the orb use flat native surfaces so elevated transparent
shadows cannot composite over the sampled backing; opaque controls retain native fill and elevation.


Two-line headers keep sender names on one ellipsized line, reserving room for the avatar, friend
indicator, delivery status, and timestamp even in narrow panes with large system fonts.
Compact sender prefixes use `<nick> message`, with `[]`, `()`, or **None** (`nick: message`)
available under Message layout only when Compact is selected, globally and per conversation.
Conversation bracket overrides inherit the global choice independently of layout. Notices retain
`-nick-` and actions retain `* nick`. Compact and Two-line sender names are bold and colored without
nickname backgrounds; automatic mentions are color-only, while explicit IRC bold is preserved.
Two-line's star remains friend-only; Comfortable friend styling is unchanged. The nickname-background
toggle continues to control the row wash.

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

On the chat page only, wallpaper fades into the theme canvas over 192dp at the top and 96dp at the
bottom (each independently capped at half the viewport height). Scrolling messages fade to transparent
over the same edge distances, including when no wallpaper is selected. Header, composer, and floating
controls remain unaffected. Messages can scroll behind the Android gesture area; the composer stays
safely above it. Shared page and wallpaper captures include their respective fades; settings thumbnails
and layout previews retain the original art.

# Generated-avatar chest emblems

The opt-in IRC sprite renderer is Kotlin Canvas code and makes no network
requests. People and private messages use compact robot variations; network
rows use a robot plus a thicker outer status ring. Project-named channels use
a matched, background-free Devicons mark, while ordinary channels use a quiet
`#` fallback. All badges are tinted with the deterministic channel/nick color,
so the marks feel native to MOTD rather than like imported app icons.

## Font Awesome Free source

The renderer embeds a small subset of [Font Awesome Free 6.7.2](https://github.com/FortAwesome/Font-Awesome/tree/6.7.2)
SVG path data, copied from the upstream `svgs/` tree. It does not bundle or
render a Font Awesome font. Font Awesome Free's SVG icons are licensed under
[CC BY 4.0](https://fontawesome.com/license/free); the attribution here and in
the top-level [third-party notice](../../../THIRD_PARTY_NOTICES.md) travels
with a distributed build containing these paths.

| Renderer use | Font Awesome Free SVG source |
| --- | --- |
| Generic technical fallback | `solid/terminal.svg`, `solid/code.svg`, `solid/microchip.svg`, `solid/network-wired.svg`, `solid/database.svg`, `solid/shield-halved.svg`, `solid/robot.svg`, `solid/box-open.svg` |
| Contextual language/tool chest marks | `brands/rust.svg`, `brands/python.svg`, `brands/golang.svg`, `brands/git-alt.svg`, `brands/github.svg`, `brands/linux.svg`, `brands/docker.svg`, `brands/android.svg` |

## Devicons channel marks

`ChannelDeviconBadge.kt` embeds a curated subset of
[Devicons v1.1.0](https://github.com/vorillaz/devicons/tree/v1.1.0) path data
from `packages/core/export-files/font/`. It does not bundle the Devicons font
or make a runtime request. Devicons is licensed under
[MIT](https://github.com/vorillaz/devicons/blob/v1.1.0/LICENSE).

| Channel matcher | Devicons source |
| --- | --- |
| Linux distributions | `debian.svg`, `archlinux.svg`, `fedora.svg`, `ubuntu.svg`, `linux-tux.svg` |
| Editors | `emacs.svg`, `vim.svg`, `neovim.svg` |
| Languages | `rust.svg`, `python.svg`, `go.svg`, `c.svg`, `kotlin.svg`, `java.svg`, `javascript.svg`, `typescript.svg`, `clojure.svg`, `haskell.svg`, `elixir.svg`, `zig.svg` |
| Development and hosting | `git.svg`, `github.svg`, `gitlab.svg`, `docker.svg`, `kubernetes.svg`, `android.svg` |
| Privacy | `tor.svg` |

The current Devicons catalogue has no Guix mark. The `#guix` / `#guixsd`
matcher therefore uses a simplified, monochrome derivative of the official
[Guix logo](https://commons.wikimedia.org/wiki/File:Guix_logo.svg) by Luis
Felipe López Acevedo. The source is from `guix-artwork.git`; the derived path
data is attributed and distributed under
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). Its source
translation is recorded alongside the renderer, and the top-level notice
travels with a distributed build.

## Contextual hint policy

Nick hints such as `rustacean`, `python`, `golang`, `git`, `github`, `linux`,
`docker`, and `android` select the corresponding Font Awesome brand glyph.
Hints without a suitable small Font Awesome mark—such as Emacs, Vim, Neovim,
NixOS, Guix, Tor, and Kubernetes—select a neutral terminal, code, package,
network, or security glyph instead. A non-matching nick deterministically
selects one of the same technical glyphs.

Channel matching is intentionally separate: `#debian`, `#guix`, `#emacs`,
`#rust-lang`, `#k8s`, and similarly tokenized project names select only their
known channel mark. A channel that does not match a curated alias remains the
neutral `#` badge; a short alias such as `go` must be an exact token.

These marks are descriptive only and do not imply affiliation, endorsement, or
ownership. The feature may be disabled by selecting either existing avatar
style in Appearance settings.

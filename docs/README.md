# motd docs

Human-oriented runbooks for working in this repository. Each one lists the
exact `nix develop` commands for one activity.

## Human runbooks

- [`human-developing.md`](human-developing.md) — building, linting, and testing
  motd locally.
- [`human-releasing.md`](human-releasing.md) — cutting a signed release tag and
  the workflow that publishes it.
- [`human-fdroid-update.md`](human-fdroid-update.md) — how a release reaches
  F-Droid, and when the fdroiddata recipe needs a hand-written change.

## Packaging reference

- [`fdroid.md`](fdroid.md) — the merged fdroiddata recipe, native libbox source
  build, reproducible signing, and the FOSS boundary. The per-release update
  steps live in `human-fdroid-update.md`.

## Feature and setup docs

- [`cloak.md`](cloak.md) — CLoak bouncer connection guide.
- [`obfuscation.md`](obfuscation.md) — SOCKS5, Tor, and embedded VLESS
  (TCP + REALITY or WebSocket + TLS) transport behavior and validation.
- [`ntfy-push.md`](ntfy-push.md) — ntfy and UnifiedPush setup for Google-free
  push.
- [`theme-sources.md`](theme-sources.md) — editor, terminal, and wallpaper
  palette sources.
- [`../THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md) — third-party
  licensing and libbox source provenance.

## Clipboard images

Paste an image into the chat input (or insert one from your keyboard) to open
the existing attachment preview and destination chooser. The draft and cursor
stay unchanged; nothing uploads until you tap Upload. Ordinary text pastes normally.
In the full-screen image viewer, Copy image copies the original image bytes as
a readable image URI, not its URL. Copy uses the image's owning network route
and the same 25 MiB streaming limit as Save; Share still shares the URL.

## Local composer text tools

Labs → AI → Composer text tools is default-off. Set up the exact Qwen artifact
through the disclosed Download or selected-file import, assign it, then explicitly
enable the feature. Setup uses network metadata; drafts, styles and generated text
are processed locally and are not uploaded. Opening a tool never downloads weights.

For a nonblank draft, tap the compact AI wand inside the composer input area,
beside the expand or attachment button, to open correction, writing styles and
translation. Its smaller glyph retains a full-size touch target.
Custom styles and explicit language choices survive disabling or deleting the model.
The sheet puts correction, writing styles, saved styles and translation before the
full selectable source preview, so long drafts do not hide the first action.
Choose a target language, then tap Translate explicitly; choosing a language does
not generate text. New working, result and error views start at the top.
Cancel stays available while the on-device model is working. If a tool fails with
its source retained, choose a tool or translation again without changing the draft.
Preview does not change a draft. Apply requires the same draft, reply, room and AI
configuration; editing or sending invalidates the preview. Incomplete results cannot
be applied or copied. Results are plain text and lose IRC formatting. Experimental
model output can change meaning or facts: review it before applying or sending.
Android memory, speed, battery use and hardware quality remain unmeasured.

Long-hold an actual message to translate it locally in chat, feed, mentions,
local/server search, or an Agentwire user/assistant body. Chat's action sheet offers
Translate even in SERVER buffers. Concealed fool/quiet rows and grouped system runs
must first be expanded; expanded system lines select their exact stored event.
Invitation and file-transfer buttons retain their existing behavior: translation
does not accept an invitation or transfer, read a file, or fetch media.

Message translation is read-only: Source and selectable Result offer Copy and Close,
never Apply, Quote, Send, draft insertion, or history replacement. Redaction, source
changes, search replacement, Agentwire stream/session changes, leaving the screen,
or disabling the feature clear the preview. No translation result cache is stored.
Language coverage and factual preservation are experimental, not guarantees.
The active video view forwards its owning-message hold while retaining playback
controls; physical-device video gesture behavior remains unmeasured.

## Note on agent docs

[`../AGENTS.md`](../AGENTS.md) and [`../.agents/`](../.agents) are mandatory
policy and task guides for AI agents working in this repository. They are not
human runbooks; the files above are.
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
- [`android-distribution.md`](android-distribution.md) — Google-free Android
  channels, verified release inputs, and the native Appteka publication checklist.

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

Tap a direct video attachment in chat to watch it in the in-app player using
the owning IRC network's media route. Use the fullscreen button to hide Android's
system bars; Back exits fullscreen before returning to chat. **More actions →
Open in browser** uses the browser's own network connection. Leaving the player
stops playback.

Settings → Uploads links to each selected provider’s website and explains its
retention or security caveats. CrafterBin privacy and Litterbox expiry remain in
Security. Custom services need a 0x0-compatible multipart HTTPS endpoint, with
optional credentials; their link opens only a valid configured endpoint.
The editable size limit is enforced by the app, not a verified service capacity.

File upload → Recents → Clear history confirms before removing local upload records
and saved deletion tokens. Hosted files and active uploads are unaffected.

## Upload privacy

By default, hosted image uploads send original bytes. Other file uploads and
outgoing DCC transfers leave metadata stripping on, including plaintext before
voice encryption. Originals are not modified, and sanitized files use a generated
filename.

Change **Strip uploaded image metadata** or **Strip metadata** in **Settings →
Uploads** or the relevant upload confirmation. Previously saved choices are kept.
The settings are included in configuration backups and captured once per attempt.
Turning it off bypasses metadata parsing and sends original bytes and filenames, which
may expose location, author or device details. Size limits and local-file checks still apply.

JPEG, PNG/APNG, GIF and WebP processing preserves compressed image content,
orientation and animation. Supported MP4/M4A/3GP and WebM audio/video is remuxed,
not re-encoded; single-stream Ogg Opus/Vorbis comments are removed. Presentation
data such as orientation, color parameters and playback timing is retained.
Extended Adobe JPEG headers are accepted, with their extra metadata bytes removed.
Camera JPEGs that use EXIF orientation `0` (undefined) retain their encoded pixels
without an orientation tag.

While stripping is enabled, unsupported files require **Send original** confirmation for that attempt only.
This includes PDFs, Office documents, archives, images with embedded ICC profiles
or unsupported HDR data, deferred-height JPEGs, unknown WebP animation chunks,
and unsupported media codecs or subtitle tracks.
Cancel sends nothing. Malformed supported files, read errors and size-limit
failures stop the upload rather than falling back to original bytes.

Processing uses bounded buffers and temporary private files, removed on completion,
error or cancellation. Native media samples are capped at 4 MiB; Ogg comments at
1 MiB. Device codec/container support can further limit remuxing. This removes
supported file/container metadata, not identifying details visible or audible in
the content, or arbitrary data hidden inside compressed samples.

## Emoji sets

Appearance → Emoji set offers System (the default), Noto Emoji, OpenMoji, and
Twemoji, independently of the text font. Nothing downloads automatically.
Download & use fetches a verified font from its fixed upstream source; installed
sets work offline. No chat content is sent, but GitHub's download servers receive
your IP address. Credits and licenses are in Settings → About → Emoji credits
and licenses, not in the picker.

The selected set applies throughout the app, including messages, the editor,
reactions, and the Unicode picker. Categories, shared recents, and long-press
variants insert ordinary Unicode, never images. Unsupported sequences use the
device fallback. Backups save the selection, not downloaded fonts; a missing or
invalid restored set visibly falls back to System until reinstalled.

## Notifications

The connection service posts one quiet notification. Its compact view counts
connected, connecting, waiting, offline, and failed networks; expand it for names
and states. **Disconnect** stops the current session without changing auto-connect
settings. Opening the app again can reconnect.

**Settings → Notifications → Notification previews** offers **Full message**,
**Sender and chat only**, and **Generic notification**. This controls what motd
sends to Android Auto. Changing the setting clears older notifications on the phone.
Android controls lock-screen visibility and alert sounds. Invitations and file
offers request an alert; watch expiry and recovered deliveries are silent.
Supported Android versions offer native conversation controls for named chat
notifications, without bubbles.

**Appearance → App icon** defaults to **Mono** when no choice is saved. **Teal
ceramic** remains selectable. Notification small icons use the shared monochrome
shape of the launcher artwork; Android controls its tint and system app badge.

## Chat navigation

The drawer's Networks header shows total mentions, not total unread messages.
Per-network unread badges and the scoped Mark all as read action remain available.
The chat-list overflow shows **Unread Chats** only when the current folder or
network view contains an unmuted chat with unread messages.
The fallback three-node server badge fills one node when disconnected or failed,
two while connecting or registering, and all three when connected. It fills left
to right in every language, with only the last filled node using the theme's
status color. Server-provided artwork and bouncer badges keep their corner dot.
Mentions refreshes load newer and older rows around the reader together in one bounded
window, rather than dropping newer rows and then loading them back page by page.

The main chat-list wordmark has holiday artwork every year: a bat on October 30–31,
a Santa hat on December 20–26, and a sparkler on December 31–January 1. Tap the
wordmark to make the bat fly, shake snow across the toolbar, or reveal the incoming year.
In Belarus, Bulgaria, Cyprus, Georgia, Greece, Moldova, Montenegro, North Macedonia,
Romania, Russia, Serbia, and Ukraine, a tappable three-bar cross replaces the `t`
from Orthodox Easter Sunday through Bright Saturday. Dates follow the device's
local calendar, and Easter eligibility uses the Android system locale's region,
not location permissions. With system animations off, tapping shows a still effect.
Turn all of these off in **Settings → Appearance → Chat list → Seasonal Easter eggs**.

Comfortable chat sends grow the actual formatted message bubble from the composer
over a 500ms morph, keeping its body and metadata aligned through the landing.
Replies and denser message styles retain their ordinary send-flight presentation.
Flights track picker/keyboard-driven composer movement and hand off at the measured row.
Accepted sends clear the composer once saved to chat. Their bubbles settle while a spinner
shows pending delivery, then show confirmation or failure with Retry and Delete.

Floating keeps its paperclip available while typing, alongside a separate Expand
action; uploading the draft itself remains a distinct toolbar action.
Quoted-message accent rails follow the rendered sender and preview height,
including multiline replies and larger fonts.

Chat settings can hide the IRC voice-message button without disabling voice
playback. Nickname taps default to Mention, with User menu as an alternative;
hold a nickname for its user menu, or other message content for message actions.
Mentions insert at the cursor or replace the selection. The `: ` suffix is used
only at whole-message start, never merely because text wraps or follows an
earlier nonblank line.

Appearance's nickname mention style applies None, Bold, or Chip consistently to
known nicknames and your recorded highlight nickname in message bodies, excluding
links and inline code. The message-level mention cue remains independent.
Older or reply-only highlights without a recorded text trigger keep that cue
without inventing an inline match.

Typing indicator placement offers Header (the default) or Above composer.
The latter appears at the live end, where the next message belongs, then gives
way to that sender's message or collapses on expiry without leaving an idle gap.
It stays out of the way while reading history; returning to latest shows any
still-active typists without moving a reader there automatically.

A chat-list swipe acts only after release at least 60% across the row width. Short
flicks spring back without flying offscreen; right swipes do not dismiss chats.

Reading older messages animates the chat header from 64dp to a 36dp informational
strip. On the right, 56dp floating buttons stack conversation actions, Back, and
latest from top to bottom with 8dp gaps; Back is separate from the actions menu
and appears only where Back navigation is available. Latest restores the full
toolbar. Large accessibility text can grow the strip rather than being clipped.
Appearance → Messages & input → Collapse title bar can keep the full toolbar and
its menu in place while reading older messages; the latest-message button remains available.
History refreshes preserve the already-loaded bounded window around a parked
reader, so collapsed presence runs do not replace painted messages with placeholders.

Chat-list tabs use the same theme surface as the title bar and chat rows.
The selected tab retains its distinct pill highlight.

Anchored chats stay in a manually ordered tier above ordinary pinned chats.
Anchor channels or queries from their chat-list actions, then drag the dedicated
handle or use its accessible move actions. Folder, network, and archive filters
still apply; reordering visible anchors does not move hidden ones. Unanchoring
restores the chat's previous pin state. Configuration export/import includes
placement and order, while older backups without placement data leave them alone.

Opening settings search focuses its input immediately. Returning from a result keeps
the query without requesting focus again.

Labs → Dickord mode provides Discord-style navigation for the
[Dickord Discord-to-IRC bridge](https://github.com/trevarj/dickord). Its project link
stays available with the mode off; the private repository requires authorized GitHub access.

Dickord audio attachments render one inline player instead of a redundant URL
bubble. Captions and unrelated links remain; the complete signed audio link and
conversation details stay available in the player's Details sheet.

Inline audio uses a play-led action, a flat track until real waveform data is
available, and a details menu beside the timestamp. Loading can be cancelled;
seeking is available only for prepared audio. HTTP playback still requires consent.

Long-press a Dickord conversation → Delete chat to confirm local removal. The chat
hides immediately; leaving waits for reconnect when offline, and local history
is purged after the server acknowledges the IRC leave. Discord channels and remote
messages remain intact. Bridge refresh/discovery can rediscover a closed chat.

## Local composer text tools

Labs → AI → Composer text tools is default-off. Set up the exact Qwen artifact
through the disclosed Download or selected-file import, assign it, then explicitly
enable the feature. Setup uses network metadata; drafts, styles and generated text
are processed locally and are not uploaded. Opening a tool never downloads weights.
The AI model library also links to the original
[Qwen3.5-2B model card](https://huggingface.co/Qwen/Qwen3.5-2B) and the
[packaged Unsloth GGUF model card](https://huggingface.co/unsloth/Qwen3.5-2B-GGUF).
These information links do not start setup, download weights, or enable a Lab.

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

Long-hold an actual message to translate it. In chat, Translate opens one picker
with compatible installed translation apps, Android's text-processing chooser,
and On-device AI when the existing local text-tools feature is available.
External translation requires neither AI Labs nor a model, including in SERVER
buffers. Selected apps receive only that message's plain text and may send it to
an online service. Apps labeled **Opens externally** do not return an inline result;
other apps produce one only if they return text.

Long-hold a system-event pill to see Message info and actions such as Copy,
Share, Quote, and Translate. For a grouped pill, expand it and hold the
individual event.

Returned chat text appears in a solid reply-like inset inside its original bubble,
dense row, card or pill in every layout. It wraps naturally without a two-line
limit, remains selectable, and has its own dismiss button. The original message's
click, hold, swipe and Reply actions do not apply to the returned text.
Concealed fool/quiet rows and grouped system runs must first be expanded;
grouped translations appear only under their exact expanded source line.
Invitation, file-transfer and audio controls retain their existing behavior:
translation does not accept an invitation or transfer, read a file, or fetch media.
External results never modify drafts, replies or history. They survive pause,
rotation and translator round trips, but source deletion/redaction/change removes
them and committed navigation away from the chat clears the visit. Nothing is
stored across a new process.

On-device AI in the chat picker retains its separate read-only result sheet.
Feed, mentions and local/server search retain their local translation action.
Source and selectable Result offer Copy and Close,
never Apply, Quote, Send, draft insertion, or history replacement. Redaction, source
changes, search replacement, leaving the screen, or disabling the feature clear
this local preview. No translation result cache is
stored. Language coverage and factual preservation are experimental, not guarantees.

## Removed Labs feature

Agentwire has been removed. Previously marked channels now open as ordinary IRC
chats; their server topics and stored history are preserved. Prepared agent
context is not converted into a draft or message. The old private Labs store is
left inert and remains outside configuration exports.

## Note on agent docs

[`../AGENTS.md`](../AGENTS.md) and [`../.agents/`](../.agents) are mandatory
policy and task guides for AI agents working in this repository. They are not
human runbooks; the files above are.
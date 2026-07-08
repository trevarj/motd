# 04 — Data layer (Room + repositories + sync algorithms)

Entities/DAOs/repo interfaces are frozen in `10-contracts.md`. This doc specifies semantics.
DB `motd.db`, version 1, `fallbackToDestructiveMigration()` is FORBIDDEN — there are no
migrations in v1, and shipping destructive fallback hides schema mistakes in review.

## Identity & dedup rules

- `messages.dedupKey`:
  - live/history message with `msgid` → the msgid.
  - no msgid → `sha1("<serverTime>|<sender>|<text>")` hex.
  - locally-sent pending message → `"pending:<label>"` until echo confirms.
- ALL history/live inserts use `OnConflictStrategy.IGNORE` + the `UNIQUE(bufferId, dedupKey)`
  index → CHATHISTORY backfill overlap is idempotent by construction.
- **Echo flow** (EventProcessor):
  1. send: `ConnectionManager.sendMessage` delegates to EventProcessor's insert path (so the
     "sole writer" rule holds): a row with `pendingLabel=<label>`,
     `dedupKey="pending:<label>"`, `serverTime=now`, `isSelf=true`.
  2. echo arrives (`ctx.label` set): look up `byPendingLabel`, update that row in place —
     real `msgid`, real `serverTime`, `dedupKey=msgid`, `pendingLabel=null`.
  3. same msgid later via CHATHISTORY → INSERT IGNORE no-ops. One row, always.
  4. no echo within 30s → set `failed=true` (UI shows retry affordance; retry = new send,
     delete failed row).

## EventProcessor (`data/sync/`, WP5) — the only IRC→Room writer

Implements the `IrcEventSink` contract (10-contracts.md): ConnectionManager's per-network
collectors and the push path (WP9) both feed events through `process(networkId, event)`.
Mapping table:

| IrcEvent | Room effect |
|---|---|
| ChatMessage | ensure buffer exists (auto-create QUERY buffers for DMs, using Isupport-normalized name); insert MessageEntity (kind from ChatKind); `hasMention` = word-boundary regex match of own nick (case-insensitive, rebuilt on NickChanged) |
| TagMessage(typing) | NOT persisted — routed to the `TypingTracker` contract interface (WP5 implements; ChatViewModel reads) |
| TagMessage(react) | upsert ReactionEntity |
| HistoryBatch | same mapping as live, in one Room transaction; used by both catch-up and RemoteMediator paths |
| Joined/Parted/Quit/Kicked/NickChanged/TopicChanged/ModeChanged | insert system MessageEntity (kind JOIN/PART/...) + update members/buffers tables; Quit fans out to every buffer the nick was a member of |
| Names | `MemberDao.replaceAll` |
| Away/Account/Host/RealnameChanged | upsert users |
| ReadMarker | `advanceReadMarker` (max-only) |
| BouncerNetworkState | mirror into networks table (create/update/delete BOUNCER_CHILD rows) |
| Registered | mark network connected (in-memory state, not Room); trigger catch-up (below) |

System-event messages get `dedupKey` from msgid when present (event-playback provides them),
else the hash rule — so playback of joins/parts is also idempotent.

## Reconnect catch-up (WP5, runs after Registered, per network)

```
if hasCap(draft/chathistory):
    since = max(newestTime(buffer)) over network's buffers (null → skip TARGETS, LATEST each joined buffer)
    targets = CHATHISTORY TARGETS timestamp=<since> timestamp=<now> 100
    for (target, _) in targets ∪ joined buffers:
        loop: CHATHISTORY AFTER target timestamp=<local newest for that buffer> <pageLimit>
              insert batch; break when batch.size < pageLimit
    fetch MARKREAD for every open buffer
else: nothing (live-only)
```

## Infinite scroll — `ChatHistoryRemoteMediator` (WP5)

`RemoteMediator<Int, MessageEntity>` attached per buffer in `MessageRepository.messages()`
via the `ChatHistoryMediatorFactory` contract interface: WP4 injects it and wires
`remoteMediator = factory.create(bufferId)`; WP1's stub binding returns a no-op mediator
(immediate `endOfPaginationReached = true`, i.e. plain local paging); WP5 implements the real
one; WP10 rebinds.

```
onLoad(loadType):
  REFRESH  → if buffer empty and cap: CHATHISTORY LATEST <target> * <page>; insert; endOfPagination = false
  PREPEND  → newer boundary: nothing to fetch live-side → endOfPagination = true
  APPEND   → older boundary (list is DESC): if buffer.historyComplete or no cap → end.
             CHATHISTORY BEFORE <target> timestamp=<ISO of oldestTime(buffer)> <page>
             insert (IGNORE); if result.events.isEmpty() → set historyComplete=true, end.
             update oldestFetchedTime.
```

PagingConfig: `pageSize = 50, prefetchDistance = 25, enablePlaceholders = false`.
Ordering contract everywhere: `ORDER BY serverTime DESC, id DESC` (ties broken by insert order).

## FTS search (WP4)

- `messages_fts` FTS4 external-content table on (text, sender) — Room generates sync triggers.
- Query shape:

```sql
SELECT m.*, b.displayName AS bufferDisplayName, n.name AS networkName
FROM messages m
JOIN messages_fts f ON m.id = f.rowid
JOIN buffers b ON b.id = m.bufferId
JOIN networks n ON n.id = b.networkId
WHERE f.messages_fts MATCH :query
  AND (:bufferId IS NULL OR m.bufferId = :bufferId)
  AND m.kind IN ('PRIVMSG','NOTICE','ACTION')
ORDER BY m.serverTime DESC LIMIT 200
```

- Sanitize user input: strip FTS operator chars (`" * ^ : ( ) -`) from each whitespace token,
  append a bare `*` (prefix search), join with spaces. (Quoted `"token"*` silently drops the
  wildcard in FTS4 — verified during WP4.)

## Chat list projection (WP4)

`observeChatList()` single `@Transaction @Query`: buffers joined with their latest message
(correlated subquery on the `(bufferId, serverTime, id)` index), plus
`unreadCount = COUNT(*) WHERE serverTime > COALESCE(readMarkerTime,0) AND NOT isSelf AND kind IN (chat kinds)`
and `mentionCount` = same with `hasMention = 1`. Sort: pinned first, then latest activity DESC.
Exclude SERVER buffers unless they have unread errors (v1: just exclude type SERVER).

## Link previews (WP4 `LinkPreviewRepository`)

- In-memory `LruCache<String, LinkPreview?>(256)` (negative results cached too).
- `HttpURLConnection` GET, 5s timeouts, max 512 KB read, only `text/html` content type;
  parse `og:title`, `og:description`, `og:image`, `og:site_name` + `<title>` fallback with a
  small regex-based extractor (no HTML parser dependency).
- Never runs for image URLs (`.png .jpg .jpeg .gif .webp` suffix or `image/*` HEAD) — those
  render inline via Coil instead (decision made in UI layer, plans/07).

## Settings (WP4, DataStore)

Single `Preferences` DataStore `settings`; keys `theme_mode`, `dynamic_color`, `delivery_mode`
mapping to the `Settings` data class in contracts. WP4 also implements the `PushPrefs`
contract interface (keys `push_endpoint`, `push_keys` as JSON via kotlinx.serialization) for
WP9, and exposes STS policy storage (key `sts_policies`, JSON) via an `internal` accessor on
the implementation class for WP5.

## Credentials note

SASL passwords live in the app-private Room DB in plaintext for v1 — documented tradeoff to
protect the one-shot; Android Keystore encryption is a listed post-v1 item. Never log them;
`NetworkEntity.toString()` already redacts `saslPassword` (see contracts) — keep it that way.

## WP4 tests

Robolectric in-memory DB: dedup uniqueness (insert same dedupKey twice → one row); echo flow
(pending insert → update-in-place → history overlap ignored); chat-list projection counts
(unread/mention math vs readMarkerTime); FTS round trip incl. sanitizer; advanceReadMarker
max-only. Plain JVM: link preview OG parsing from fixture HTML strings.

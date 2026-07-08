# 10 — Frozen cross-package contracts

These signatures are **law**. Every work package codes against them verbatim. WP1 realizes them
as code (stubs where noted); no later WP may edit a contract file. If a contract is wrong, stop
and report — do not "fix" it locally.

## `:irc` module — `io.github.trevarj.motd.irc`

### proto

```kotlin
package io.github.trevarj.motd.irc.proto

/** Parsed IRC prefix (`:nick!user@host`). */
data class Prefix(val nick: String, val user: String? = null, val host: String? = null)

/** One IRC protocol message. Tags are unescaped values; empty map when absent. */
data class IrcMessage(
    val tags: Map<String, String> = emptyMap(),
    val source: Prefix? = null,
    val command: String,
    val params: List<String> = emptyList(),
) {
    companion object {
        /** Parse a single line WITHOUT trailing CRLF. Throws IrcParseException on garbage. */
        fun parse(line: String): IrcMessage
    }
    /** Serialize to wire format WITHOUT trailing CRLF. Escapes tag values. */
    fun serialize(): String
}

class IrcParseException(message: String, val line: String) : Exception(message)

/** RPL_ISUPPORT (005) accumulator. */
class Isupport {
    fun update(tokens: List<String>)          // params from a 005, minus nick + trailing text
    operator fun get(key: String): String?    // e.g. get("CHATHISTORY") -> "1000"
    val caseMapping: String                   // default "rfc1459"
    val chanTypes: String                     // default "#&"
    val prefixModes: List<Pair<Char, Char>>   // mode->prefix pairs, e.g. (o,'@'), (v,'+')
    /** Case-normalize a nick/channel per CASEMAPPING for map keys and comparisons. */
    fun normalize(name: String): String
}
```

### transport

```kotlin
package io.github.trevarj.motd.irc.transport

interface IrcTransport {
    /** Open the connection. Throws on failure. */
    suspend fun connect()
    /** Cold flow of received lines (no CRLF). Completes on EOF, throws on socket error. */
    val incoming: kotlinx.coroutines.flow.Flow<String>
    /** Send one line; CRLF appended by the transport. */
    suspend fun send(line: String)
    suspend fun close()
}

fun interface TransportFactory {
    fun create(host: String, port: Int, tls: Boolean): IrcTransport
}

/** okio-over-Socket/SSLSocket implementation lives in :irc (JVM default factory). */
class OkioLineTransport(
    host: String, port: Int, tls: Boolean,
    /** Optional client certificate for SASL EXTERNAL; app supplies via its own factory. */
    sslContext: javax.net.ssl.SSLContext? = null,
) : IrcTransport
```

### event

```kotlin
package io.github.trevarj.motd.irc.event

import io.github.trevarj.motd.irc.proto.IrcMessage

sealed interface IrcClientState {
    data object Disconnected : IrcClientState
    data object Connecting : IrcClientState
    data object Registering : IrcClientState
    data class Ready(val nick: String, val caps: Set<String>, val isupport: Map<String, String>) : IrcClientState
    data class Failed(val reason: String, val fatal: Boolean) : IrcClientState  // fatal = don't auto-retry (e.g. SASL fail)
}

/** Context shared by chat-ish events. serverTime = epoch millis (from server-time tag or local clock). */
data class MessageContext(
    val msgid: String?,
    val serverTime: Long,
    val account: String?,     // account-tag
    val batchId: String?,     // enclosing batch, null when live
    val label: String?,       // labeled-response echo correlation
)

sealed interface IrcEvent {
    // -- connection/registration
    data class Registered(val nick: String, val caps: Set<String>, val isupport: Map<String, String>) : IrcEvent
    data class CapsChanged(val added: Set<String>, val removed: Set<String>) : IrcEvent  // CAP NEW/DEL
    data class Disconnected(val reason: String?) : IrcEvent

    // -- chat
    enum class ChatKind { PRIVMSG, NOTICE, ACTION }
    data class ChatMessage(
        val ctx: MessageContext, val kind: ChatKind,
        val source: io.github.trevarj.motd.irc.proto.Prefix,
        val target: String,          // channel or our nick (query)
        val text: String,
        val isSelf: Boolean,         // echo-message or self-inserted
        val replyToMsgid: String?,   // +draft/reply
    ) : IrcEvent
    data class TagMessage(           // TAGMSG: typing + react
        val ctx: MessageContext,
        val source: io.github.trevarj.motd.irc.proto.Prefix,
        val target: String,
        val typing: String?,         // "active" | "paused" | "done"
        val reactEmoji: String?,     // +draft/react
        val reactTargetMsgid: String?, // +draft/reply on a react carries the reacted-to msgid
    ) : IrcEvent
    /** Fully reassembled chathistory batch for one target, in server order. */
    data class HistoryBatch(val target: String, val events: List<IrcEvent>) : IrcEvent

    // -- membership & user state
    data class Joined(val ctx: MessageContext, val nick: String, val channel: String, val account: String?, val realname: String?, val isSelf: Boolean) : IrcEvent
    data class Parted(val ctx: MessageContext, val nick: String, val channel: String, val reason: String?, val isSelf: Boolean) : IrcEvent
    data class Quit(val ctx: MessageContext, val nick: String, val reason: String?) : IrcEvent
    data class Kicked(val ctx: MessageContext, val nick: String, val channel: String, val by: String, val reason: String?, val isSelf: Boolean) : IrcEvent
    data class NickChanged(val ctx: MessageContext, val from: String, val to: String, val isSelf: Boolean) : IrcEvent
    data class Names(val channel: String, val members: List<Member>) : IrcEvent {
        data class Member(val nick: String, val prefixes: String, val account: String?)
    }
    data class AwayChanged(val nick: String, val awayMessage: String?) : IrcEvent
    data class AccountChanged(val nick: String, val account: String?) : IrcEvent
    data class HostChanged(val nick: String, val newUser: String, val newHost: String) : IrcEvent
    data class RealnameChanged(val nick: String, val realname: String) : IrcEvent

    // -- channel state
    data class TopicChanged(val ctx: MessageContext, val channel: String, val topic: String, val setBy: String?) : IrcEvent
    data class ModeChanged(val ctx: MessageContext, val target: String, val modes: String, val args: List<String>) : IrcEvent
    data class Invited(val ctx: MessageContext, val by: String, val nick: String, val channel: String) : IrcEvent

    // -- sync
    data class ReadMarker(val target: String, val timestamp: Long?) : IrcEvent  // MARKREAD; null = "*" (unset)
    data class BouncerNetworkState(val netId: String, val attrs: Map<String, String>) : IrcEvent // BOUNCER NETWORK notify
    data class ServerError(val code: String, val params: List<String>, val text: String) : IrcEvent
    /** Escape hatch: anything not mapped above (raw numerics for MOTD text, WHOIS, etc.). */
    data class Raw(val message: IrcMessage) : IrcEvent
}
```

### client

```kotlin
package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.*
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.transport.TransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

enum class SaslMechanism { NONE, PLAIN, EXTERNAL }

data class IrcClientConfig(
    val host: String,
    val port: Int,
    val tls: Boolean,
    val nick: String,
    val username: String,
    val realname: String,
    val sasl: SaslMechanism = SaslMechanism.NONE,
    val saslUser: String? = null,
    val saslPassword: String? = null,
    /** soju: bind this connection to a bouncer network before CAP END. */
    val bouncerNetId: String? = null,
    /** Extra caps to request beyond the built-in tiers (rarely needed). */
    val extraCaps: Set<String> = emptySet(),
)

data class ChatHistoryRequest(
    val subcommand: Subcommand, val target: String,
    /** Bounds are "timestamp=<ISO8601>" or "msgid=<id>" selectors, pre-rendered. */
    val bound1: String? = null, val bound2: String? = null,
    val limit: Int,
) { enum class Subcommand { LATEST, BEFORE, AFTER, AROUND, BETWEEN, TARGETS } }

data class ChatHistoryResult(
    val events: List<IrcEvent>,               // empty = no (more) history
    val targets: List<Pair<String, Long>>,    // TARGETS only: (name, latest serverTime)
)

data class BouncerNetwork(val netId: String, val attrs: Map<String, String>) // attrs: name,host,state,nickname,...

/** One instance per physical socket. Restartable: start() after stop() reconnects fresh. */
class IrcClient(
    val config: IrcClientConfig,
    factory: TransportFactory,
    scope: CoroutineScope,
) {
    val state: StateFlow<IrcClientState>
    val events: SharedFlow<IrcEvent>          // buffered, replay 0, DROP_OLDEST at 4096
    fun start()
    fun stop()

    suspend fun send(msg: IrcMessage)
    /** Attach a label tag, suspend until the labeled response/ack batch completes. */
    suspend fun sendLabeled(msg: IrcMessage): List<IrcMessage>
    /** Convenience: PRIVMSG with label; returns the label used (for echo dedup). */
    suspend fun sendMessage(target: String, text: String, replyToMsgid: String? = null): String
    suspend fun sendTyping(target: String, state: String)            // TAGMSG +typing
    suspend fun sendReact(target: String, msgid: String, emoji: String)
    suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResult
    suspend fun markRead(target: String, timestampMs: Long)          // MARKREAD set
    suspend fun fetchReadMarker(target: String)                      // MARKREAD get -> ReadMarker event

    // soju bouncer-networks (valid on an unbound "root" connection)
    suspend fun bouncerListNetworks(): List<BouncerNetwork>
    suspend fun bouncerAddNetwork(attrs: Map<String, String>): String   // returns netId
    suspend fun bouncerDeleteNetwork(netId: String)

    // soju webpush
    suspend fun webpushRegister(endpoint: String, p256dh: ByteArray, auth: ByteArray)
    suspend fun webpushUnregister(endpoint: String)

    /** Caps ACKed on this connection; empty until Ready. */
    val caps: Set<String>
    fun hasCap(cap: String): Boolean
    /** Live ISUPPORT state (normalize(), prefixModes, ...); empty until Ready. */
    val isupport: io.github.trevarj.motd.irc.proto.Isupport
}
```

## `:app` — `io.github.trevarj.motd`

### data/db entities (Room, DB name `motd.db`, version 1)

```kotlin
package io.github.trevarj.motd.data.db

enum class NetworkRole { DIRECT, BOUNCER_ROOT, BOUNCER_CHILD }
enum class BufferType { CHANNEL, QUERY, SERVER }
enum class MessageKind { PRIVMSG, NOTICE, ACTION, JOIN, PART, QUIT, KICK, NICK, MODE, TOPIC, ERROR, SERVER_INFO }

@Entity(tableName = "networks")
data class NetworkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val role: NetworkRole,
    val parentId: Long? = null,          // BOUNCER_CHILD -> its BOUNCER_ROOT row
    val bouncerNetId: String? = null,
    val host: String, val port: Int, val tls: Boolean = true,
    val nick: String, val username: String, val realname: String,
    val saslMechanism: String = "NONE",  // SaslMechanism.name
    val saslUser: String? = null, val saslPassword: String? = null,
    val clientCertAlias: String? = null,
    val autoConnect: Boolean = true,
    val ordering: Int = 0,
) {
    // redact saslPassword from logs
    override fun toString() = "NetworkEntity(id=$id, name=$name, role=$role, host=$host:$port)"
}

@Entity(tableName = "buffers",
        indices = [Index(value = ["networkId", "name"], unique = true)],
        foreignKeys = [ForeignKey(entity = NetworkEntity::class, parentColumns = ["id"],
                                  childColumns = ["networkId"], onDelete = ForeignKey.CASCADE)])
data class BufferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val networkId: Long,
    val name: String,                    // case-normalized
    val displayName: String,
    val type: BufferType,
    val topic: String? = null, val topicSetBy: String? = null,
    val joined: Boolean = false,
    val pinned: Boolean = false, val muted: Boolean = false,
    val ordering: Int = 0,
    val readMarkerTime: Long? = null,    // epoch ms, synced via draft/read-marker
    val oldestFetchedTime: Long? = null, // CHATHISTORY paging bookkeeping
    val historyComplete: Boolean = false,
)

@Entity(tableName = "messages",
        indices = [Index(value = ["bufferId", "dedupKey"], unique = true),
                   Index(value = ["bufferId", "serverTime", "id"])],
        foreignKeys = [ForeignKey(entity = BufferEntity::class, parentColumns = ["id"],
                                  childColumns = ["bufferId"], onDelete = ForeignKey.CASCADE)])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bufferId: Long,
    val msgid: String? = null,
    val serverTime: Long,
    val sender: String,
    val senderAccount: String? = null,
    val kind: MessageKind,
    val text: String,
    val isSelf: Boolean = false,
    val hasMention: Boolean = false,     // computed at insert by EventProcessor
    val replyToMsgid: String? = null,
    val pendingLabel: String? = null,    // set while awaiting echo; null once confirmed
    val failed: Boolean = false,         // echo timeout -> retry UI
    val dedupKey: String,                // msgid ?: sha1(serverTime|sender|text); "pending:<label>" while pending
)

@Fts4(contentEntity = MessageEntity::class)
@Entity(tableName = "messages_fts")
data class MessageFtsEntity(val text: String, val sender: String)

@Entity(tableName = "reactions",
        indices = [Index(value = ["bufferId", "targetMsgid", "sender"], unique = true)])
data class ReactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bufferId: Long, val targetMsgid: String,
    val sender: String, val emoji: String, val serverTime: Long,
)

@Entity(tableName = "users", primaryKeys = ["networkId", "nick"])
data class UserEntity(
    val networkId: Long, val nick: String,
    val account: String? = null, val away: Boolean = false,
    val hostmask: String? = null, val realname: String? = null,
)

@Entity(tableName = "members", primaryKeys = ["bufferId", "nick"])
data class MemberEntity(val bufferId: Long, val nick: String, val prefixes: String = "")
```

### DAO signatures

Implemented **in place** by WP4 — same exemption as IrcClient: WP4 adds the missing `@Query`
strings, `@Transaction` bodies (e.g. `replaceAll` becomes a concrete default method), and any
Room annotations needed to compile. Method names, parameters, and return types below are frozen.

```kotlin
@Dao interface NetworkDao {
    @Query("SELECT * FROM networks ORDER BY ordering") fun observeAll(): Flow<List<NetworkEntity>>
    @Query("SELECT * FROM networks WHERE autoConnect = 1") suspend fun connectable(): List<NetworkEntity>
    @Query("SELECT * FROM networks WHERE id = :id") suspend fun byId(id: Long): NetworkEntity?
    @Insert suspend fun insert(n: NetworkEntity): Long
    @Update suspend fun update(n: NetworkEntity)
    @Delete suspend fun delete(n: NetworkEntity)
    @Query("SELECT * FROM networks WHERE parentId = :rootId") suspend fun childrenOf(rootId: Long): List<NetworkEntity>
}

@Dao interface BufferDao {
    fun observeChatList(): Flow<List<ChatListRow>>   // @Query join: buffer + last msg + unread/mention counts
    @Query("SELECT * FROM buffers WHERE id = :id") fun observe(id: Long): Flow<BufferEntity?>
    @Query("SELECT * FROM buffers WHERE networkId = :nid AND name = :normName") suspend fun byName(nid: Long, normName: String): BufferEntity?
    @Insert suspend fun insert(b: BufferEntity): Long
    @Update suspend fun update(b: BufferEntity)
    @Query("UPDATE buffers SET readMarkerTime = :ts WHERE id = :id AND (readMarkerTime IS NULL OR readMarkerTime < :ts)")
    suspend fun advanceReadMarker(id: Long, ts: Long)
}

/** Projection for the chat list screen. */
data class ChatListRow(
    val bufferId: Long, val networkId: Long, val networkName: String,
    val displayName: String, val type: BufferType,
    val pinned: Boolean, val muted: Boolean,
    val lastMessageText: String?, val lastMessageSender: String?, val lastMessageTime: Long?,
    val unreadCount: Int, val mentionCount: Int,
)

@Dao interface MessageDao {
    @Query("SELECT * FROM messages WHERE bufferId = :bufferId ORDER BY serverTime DESC, id DESC")
    fun pagingSource(bufferId: Long): PagingSource<Int, MessageEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(msgs: List<MessageEntity>): List<Long>
    @Query("SELECT * FROM messages WHERE bufferId = :bufferId AND pendingLabel = :label") suspend fun byPendingLabel(bufferId: Long, label: String): MessageEntity?
    @Update suspend fun update(m: MessageEntity)
    @Query("SELECT MAX(serverTime) FROM messages WHERE bufferId = :bufferId") suspend fun newestTime(bufferId: Long): Long?
    @Query("SELECT MIN(serverTime) FROM messages WHERE bufferId = :bufferId") suspend fun oldestTime(bufferId: Long): Long?
    fun search(query: String, bufferId: Long?): Flow<List<SearchHit>>  // @Query over messages_fts MATCH
}

data class SearchHit(@Embedded val message: MessageEntity, val bufferDisplayName: String, val networkName: String)

@Dao interface MemberDao {
    @Query("SELECT * FROM members WHERE bufferId = :bufferId") fun observe(bufferId: Long): Flow<List<MemberEntity>>
    @Transaction suspend fun replaceAll(bufferId: Long, members: List<MemberEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(m: MemberEntity)
    @Query("DELETE FROM members WHERE bufferId = :bufferId AND nick = :nick") suspend fun remove(bufferId: Long, nick: String)
}

@Dao interface ReactionDao {
    @Query("SELECT * FROM reactions WHERE bufferId = :bufferId AND targetMsgid IN (:msgids)")
    fun observeFor(bufferId: Long, msgids: List<String>): Flow<List<ReactionEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(r: ReactionEntity)
}

@Dao interface UserDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(u: UserEntity)
    @Query("SELECT * FROM users WHERE networkId = :nid AND nick = :nick") suspend fun byNick(nid: Long, nick: String): UserEntity?
}
```

### Repository interfaces (WP1 stubs; WP4 implements; UI consumes ONLY these)

```kotlin
package io.github.trevarj.motd.data.repo

interface NetworkRepository {
    fun observeNetworks(): Flow<List<NetworkEntity>>
    suspend fun addNetwork(n: NetworkEntity): Long
    suspend fun updateNetwork(n: NetworkEntity)
    suspend fun deleteNetwork(id: Long)
}

interface BufferRepository {
    fun observeChatList(): Flow<List<ChatListRow>>
    fun observeBuffer(id: Long): Flow<BufferEntity?>
    fun observeMembers(bufferId: Long): Flow<List<MemberEntity>>
    suspend fun setPinned(id: Long, pinned: Boolean)
    suspend fun setMuted(id: Long, muted: Boolean)
    // NOTE: mark-read goes through ConnectionManager.markRead (single entry point) —
    // it advances Room via BufferDao.advanceReadMarker AND sends MARKREAD when supported.
}

interface MessageRepository {
    /** Paging 3 stream wired to ChatHistoryRemoteMediator (WP5 supplies mediator via factory). */
    fun messages(bufferId: Long): Flow<PagingData<MessageEntity>>
    fun reactions(bufferId: Long, msgids: List<String>): Flow<List<ReactionEntity>>
}

/** WP4 injects this to build its Pager; WP1 stub-binds a no-op (immediate endOfPagination),
 *  WP5 provides the real CHATHISTORY-backed implementation, WP10 rebinds. */
fun interface ChatHistoryMediatorFactory {
    fun create(bufferId: Long): RemoteMediator<Int, MessageEntity>
}

interface SearchRepository { fun search(query: String, bufferId: Long?): Flow<List<SearchHit>> }

/** OG-tag link preview; in-memory LRU + fetch on miss. Null if unfetchable/not HTML. */
interface LinkPreviewRepository {
    suspend fun preview(url: String): LinkPreview?
}
data class LinkPreview(val url: String, val title: String?, val description: String?, val imageUrl: String?, val siteName: String?)
```

### service seam (WP1 interface; WP5 implements)

```kotlin
package io.github.trevarj.motd.service

enum class DeliveryMode { PERSISTENT_SOCKET, UNIFIED_PUSH }

interface ConnectionManager {
    /** Connection state per network row id. */
    val connectionStates: StateFlow<Map<Long, IrcClientState>>
    /** Live client for a connected network, null otherwise. */
    fun clientFor(networkId: Long): IrcClient?
    /** Start/stop the whole subsystem (invoked by service / delivery-mode changes). */
    suspend fun startAll(); suspend fun stopAll()
    suspend fun connect(networkId: Long); suspend fun disconnect(networkId: Long)
    /** High-level send: resolves buffer -> network/target, handles pending insert + echo. */
    suspend fun sendMessage(bufferId: Long, text: String, replyToMsgid: String? = null)
    suspend fun sendTyping(bufferId: Long, state: String)
    suspend fun sendReact(bufferId: Long, msgid: String, emoji: String)
    suspend fun joinChannel(networkId: Long, channel: String)
    suspend fun partChannel(bufferId: Long)
    /** Find-or-create a QUERY buffer for a DM (name Isupport-normalized); returns bufferId. */
    suspend fun ensureQueryBuffer(networkId: Long, nick: String): Long
    /** THE mark-read entry point: advances Room (max-only) and sends MARKREAD when supported. */
    suspend fun markRead(bufferId: Long, upToTime: Long)
}

/** Sole IRC→Room write path. Implemented by EventProcessor (WP5); ConnectionManager delegates
 *  its pending-send insert here; push (WP9) feeds decrypted lines through it. WP1 stub-binds. */
interface IrcEventSink {
    suspend fun process(networkId: Long, event: io.github.trevarj.motd.irc.event.IrcEvent)
}

/** In-memory typing state. Written by EventProcessor (WP5), read by ChatViewModel (WP7). */
interface TypingTracker {
    fun typingNicks(bufferId: Long): StateFlow<List<String>>
}

/** Buffer currently visible in the foreground UI. Set by ChatViewModel (WP7), read by the
 *  notification suppression logic (WP5). WP1 provides the trivial impl (a MutableStateFlow). */
interface ForegroundBufferTracker {
    val foregroundBufferId: StateFlow<Long?>
    fun set(bufferId: Long?)
}
```

### settings (DataStore, WP4)

```kotlin
package io.github.trevarj.motd.data.prefs

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }
data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val deliveryMode: DeliveryMode = DeliveryMode.PERSISTENT_SOCKET,
)
interface SettingsRepository {
    val settings: Flow<Settings>
    suspend fun setThemeMode(m: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setDeliveryMode(m: DeliveryMode)
}

/** Webpush endpoint + client keypair persistence (DataStore). Implemented by WP4 alongside
 *  SettingsRepository; consumed by WP9. All values base64url. */
data class PushKeys(val privateKey: String, val publicUncompressed: String, val auth: String)
interface PushPrefs {
    suspend fun endpoint(): String?
    suspend fun setEndpoint(endpoint: String?)
    suspend fun keys(): PushKeys?
    suspend fun setKeys(keys: PushKeys)
}
```

### navigation routes (WP1, `ui/nav/Routes.kt`, type-safe navigation-compose 2.8)

```kotlin
package io.github.trevarj.motd.ui.nav

import kotlinx.serialization.Serializable

@Serializable data object ChatListRoute
@Serializable data class ChatRoute(val bufferId: Long)
@Serializable data object OnboardingRoute
@Serializable data object SettingsRoute
@Serializable data class NetworkSettingsRoute(val networkId: Long)
@Serializable data class SearchRoute(val bufferId: Long? = null)
@Serializable data class ChannelInfoRoute(val bufferId: Long)
@Serializable data class ImageViewerRoute(val url: String)
```

## Ownership of contract files

WP1 creates all of the above as compilable code (interfaces + data classes; `TODO()` bodies only
inside `internal` stub impl classes registered in Hilt modules, never in the contracts
themselves). `IrcClient` is declared by WP1 as the class shell with `TODO()` bodies and
implemented in-place by WP3 (WP3 owns `irc/client/` and `irc/ext/` after WP1 hands off — the
public signatures above must not change).

## Round 2 amendments

Landed by WP-R0 (serial) so parallel round-2 agents build against stable signatures. Full
design in `plans/11-round2.md`.

### Routes (`ui/nav/Routes.kt`) — amend + add

```kotlin
@Serializable data class ChatRoute(
    val bufferId: Long,
    val jumpToMsgid: String? = null,   // search deep-jump target
    val jumpToTime: Long = 0,          // epoch ms of target; 0 = no jump
)
@Serializable data object AboutRoute
```
Defaults keep every existing `ChatRoute(bufferId)` call site source-compatible.

### PushPrefs (`data/prefs/Settings.kt`) — per-network

```kotlin
interface PushPrefs {
    suspend fun endpoints(): Map<Long, String>        // by network row id
    suspend fun endpointFor(networkId: Long): String?
    suspend fun setEndpointFor(networkId: Long, endpoint: String?)  // null removes
    suspend fun clearEndpoints()
    suspend fun keys(): PushKeys?                      // unchanged: one shared keypair
    suspend fun setKeys(keys: PushKeys)
}
```
Storage: DataStore key `push_endpoints` = JSON `{"<networkId>": "<url>"}`. Legacy
`endpoint()`/`setEndpoint()` + `push_endpoint` key deleted by WP-R2 (no migration —
pre-release). WP-R0 adds the new members alongside the old two to stay green mid-wave.

### MessageDao (`data/db/Daos.kt`) — two additions

```kotlin
@Query("SELECT * FROM messages WHERE bufferId = :bufferId AND msgid = :msgid LIMIT 1")
suspend fun byMsgid(bufferId: Long, msgid: String): MessageEntity?

/** 0-based reverse-list index: strict complement of pagingSource ORDER BY serverTime DESC, id DESC. */
@Query("""SELECT COUNT(*) FROM messages WHERE bufferId = :bufferId
          AND (serverTime > :serverTime OR (serverTime = :serverTime AND id > :id))""")
suspend fun countNewerThan(bufferId: Long, serverTime: Long, id: Long): Int
```
Mirrored on `MessageRepository`:
```kotlin
suspend fun byMsgid(bufferId: Long, msgid: String): MessageEntity?
suspend fun countNewerThan(bufferId: Long, serverTime: Long, id: Long): Int
```

### ConnectionManager (`service/ServiceSeam.kt`) — one addition

```kotlin
/** Re-evaluate push-mode socket teardown after per-network endpoint changes.
 *  No-op unless deliveryMode == UNIFIED_PUSH. Called by MotdPushReceiver.onNewEndpoint. */
suspend fun evaluatePushMode()
```

### New round-2 types

```kotlin
// ui/chat/ComposerDraftStore.kt — process-lifetime, memory-only, consume-once
@Singleton class ComposerDraftStore @Inject constructor() {
    fun push(bufferId: Long, text: String)   // concatenates with any queued text
    fun consume(bufferId: Long): String?      // returns and removes; null when empty
}

// push/UnifiedPushApi.kt — testable seam over the static UnifiedPush connector API
interface UnifiedPushApi {
    fun getDistributors(): List<String>
    fun getAckDistributor(): String?
    fun saveDistributor(distributor: String)
    fun registerApp(instance: String)
    fun unregisterApp(instance: String)
}
```

## Round 3 amendments

Landed by WP-S1/WP-S2 (round 3). Full design in `plans/12-round3.md`.

### Transport (`irc/transport/Transport.kt`) — additive

`OkioLineTransport` gains a trailing constructor param; `TransportFactory` interface unchanged.

```kotlin
class OkioLineTransport(
    host: String, port: Int, tls: Boolean,
    sslContext: SSLContext? = null,
    verifyHostname: Boolean = true,   // false → skip endpointIdentificationAlgorithm (pinned certs)
) : IrcTransport
```

### ConnectionManager (`service/ServiceSeam.kt`) — TOFU cert surface

```kotlin
data class CertPrompt(
    val networkId: Long, val host: String, val port: Int,
    val sha256: String,            // lowercase hex of the leaf cert
    val subject: String, val issuer: String,
    val notBefore: Long, val notAfter: Long,  // epoch ms
    val changed: Boolean,          // true = a previously-pinned cert differs (warn/MITM)
)

interface ConnectionManager {
    // ... existing members ...
    val certPrompts: StateFlow<List<CertPrompt>>
    suspend fun trustCert(prompt: CertPrompt)     // pin + reconnect that network
    fun dismissCertPrompt(prompt: CertPrompt)     // drop prompt; network stays disconnected
}
```

### New: CertTrustStore (`data/prefs/`)

```kotlin
interface CertTrustStore {
    suspend fun pinnedFor(host: String, port: Int): String?
    suspend fun isPinned(host: String, port: Int, sha256: String): Boolean
    suspend fun pin(host: String, port: Int, sha256: String)
    suspend fun unpin(host: String, port: Int)
}
```
DataStore key `cert_pins` = JSON `{"host:port":"<sha256 hex>"}`.

`CertUntrustedException` and `PinningTrustManager` are `:app` service internals, not contracts.

Bouncer-auth simplification (feature 2) is entirely inside `ui/onboarding/` (no contract change).

## Round 4 amendments

Landed by WP-U0 (serial) so the parallel round-4 agents build against stable signatures. Full
design in `plans/13-uisettings.md`.

### Settings (`data/prefs/Settings.kt`) — additive

```kotlin
enum class LayoutDensity { COMPACT, COMFORTABLE, COZY }
enum class NickColorPalette { DEFAULT, VIVID, PASTEL }
enum class FoolsMode { COLLAPSE, HIDE }

data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val deliveryMode: DeliveryMode = DeliveryMode.PERSISTENT_SOCKET,
    // Round 4 (plans/13)
    val layoutDensity: LayoutDensity = LayoutDensity.COMFORTABLE,
    val nickColorsEnabled: Boolean = true,
    val nickColorPalette: NickColorPalette = NickColorPalette.DEFAULT,
    /** Normalized nick -> hue 0..359. Rendered with the active palette's S/L. */
    val nickColorOverrides: Map<String, Int> = emptyMap(),
    /** Normalized nicks. friends and fools are kept disjoint by the repository. */
    val friends: Set<String> = emptySet(),
    val fools: Set<String> = emptySet(),
    val foolsMode: FoolsMode = FoolsMode.COLLAPSE,
    val showJoinPartQuit: Boolean = true,
)

/** Canonical key for friends/fools/override lookups: trimmed + lowercased. */
fun normalizeNick(nick: String): String = nick.trim().lowercase()

interface SettingsRepository {
    val settings: Flow<Settings>
    suspend fun setThemeMode(m: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setDeliveryMode(m: DeliveryMode)
    // Round 4
    suspend fun setLayoutDensity(d: LayoutDensity)
    suspend fun setNickColorsEnabled(enabled: Boolean)
    suspend fun setNickColorPalette(p: NickColorPalette)
    /** hue 0..359 (coerced); null removes. [nick] is normalized internally. */
    suspend fun setNickColorOverride(nick: String, hue: Int?)
    /** Adding a friend removes the nick from fools, and vice versa. */
    suspend fun setFriend(nick: String, isFriend: Boolean)
    suspend fun setFool(nick: String, isFool: Boolean)
    suspend fun setFoolsMode(m: FoolsMode)
    suspend fun setShowJoinPartQuit(show: Boolean)
}
```

All existing `Settings(...)` construction sites stay source-compatible via defaults.

### Storage (`data/prefs/PreferencesStore.kt`)

New `PrefKeys` in the single `settings` DataStore, manual-JSON style (as `push_endpoints`):
`layout_density`/`nick_color_palette`/`fools_mode` = enum `.name`; `nick_colors_enabled`/
`show_join_part_quit` = `"true"/"false"`; `nick_color_overrides` = JSON object `{"nick":210}`;
`friend_nicks`/`fool_nicks` = JSON arrays. Invalid enum strings fall back to defaults; empty
set/map removes its key. `setFriend`/`setFool` write both keys in one transaction (disjoint).
Codecs are internal top-level functions (unit-testable without DataStore):

```kotlin
internal fun decodeNickSet(raw: String?): Set<String>
internal fun encodeNickSet(nicks: Set<String>): String
internal fun decodeHueOverrides(raw: String?): Map<String, Int>   // hues coerced into 0..359
internal fun encodeHueOverrides(map: Map<String, Int>): String
```

### Routes (`ui/nav/Routes.kt`) — add

```kotlin
@Serializable data object FriendsRoute
@Serializable data object FoolsRoute
@Serializable data object NickColorsRoute
```

### Theme contracts (`ui/theme/`)

```kotlin
// ui/theme/Spacing.kt (new)
@Immutable
data class MotdSpacing(
    val bubbleRowVPad: Dp, val bubbleInnerVPad: Dp, val bubbleInnerHPad: Dp,
    val bubbleCorner: Dp, val bubbleAvatar: Dp, val bubbleAvatarColumn: Dp,
    val actionVPad: Dp, val systemPillVPad: Dp, val chatListVPad: Dp,
    val chatListAvatar: Dp, val memberAvatar: Dp, val messageBodyLarge: Boolean,
)
fun spacingFor(density: LayoutDensity): MotdSpacing
val LocalSpacing: ProvidableCompositionLocal<MotdSpacing>   // staticCompositionLocalOf { spacingFor(COMFORTABLE) }
```

```kotlin
// ui/theme/NickColor.kt — additions (existing nickColor(nick, isDark) kept unchanged;
// hslColor changes private -> internal)
@Immutable
class NickColorScheme(
    val enabled: Boolean, val palette: NickColorPalette,
    val overrides: Map<String, Int>, val isDark: Boolean,
) {
    fun nick(nick: String, fallback: Color): Color   // fallback when disabled
    fun avatar(name: String): Color                  // override + palette always apply
}
val LocalNickColors: ProvidableCompositionLocal<NickColorScheme>
    // staticCompositionLocalOf { NickColorScheme(true, DEFAULT, emptyMap(), false) }

/** Resolution order: disabled -> fallback; override hue -> hueColor; else palette hash. Pure. */
fun resolveNickColor(
    nick: String, isDark: Boolean, enabled: Boolean,
    palette: NickColorPalette, overrides: Map<String, Int>, fallback: Color,
): Color
fun paletteNickColor(nick: String, isDark: Boolean, palette: NickColorPalette): Color
fun hueColor(hue: Int, isDark: Boolean, palette: NickColorPalette): Color
```

DEFAULT palette (no override) == existing `nickColor(nick, isDark)` (test-asserted).

```kotlin
// ui/theme/MotdTheme.kt — amended signature (all new params defaulted)
@Composable
fun MotdTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    layoutDensity: LayoutDensity = LayoutDensity.COMFORTABLE,
    nickColorsEnabled: Boolean = true,
    nickColorPalette: NickColorPalette = NickColorPalette.DEFAULT,
    nickColorOverrides: Map<String, Int> = emptyMap(),
    content: @Composable () -> Unit,
)
```

`MotdTheme` computes `dark` as before and provides `LocalSpacing`/`LocalNickColors`.
`MainActivity.setContent` passes the four new values from the collected `Settings`.

**Plumbing split:** style-only concerns (spacing, nick colors) flow through the two
CompositionLocals; behavioral concerns (friends/fools/foolsMode/showJoinPartQuit) flow through
ViewModel state as explicit parameters.

package io.github.trevarj.motd.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.trevarj.motd.irc.proto.IrcIdentityRules

enum class NetworkRole { DIRECT, BOUNCER_ROOT, BOUNCER_CHILD }

/**
 * Per-network obfuscation transport (plans/19 §3.4, plans/20 Phase 1). `NONE` is the default
 * direct connection. `SOCKS5` dials through a user-supplied SOCKS5 proxy (host/port). `TOR` is a
 * `SOCKS5` shortcut pinned at Orbot's `127.0.0.1:9050`. `EMBEDDED_REALITY` is the in-app
 * sing-box VLESS+REALITY core configured by a pasted share link; the core exposes its own private
 * loopback SOCKS5 endpoint to the transport.
 */
enum class ObfsMode { NONE, SOCKS5, TOR, EMBEDDED_REALITY }
enum class BufferType { CHANNEL, QUERY, SERVER }
typealias RoomId = Long
typealias TimelineEventId = Long

data class TimelineAnchor(
    val serverTime: Long,
    val eventId: TimelineEventId,
) : Comparable<TimelineAnchor> {
    override fun compareTo(other: TimelineAnchor): Int =
        compareValuesBy(this, other, TimelineAnchor::serverTime, TimelineAnchor::eventId)
}

enum class RoomAliasNamespace { CHANNEL, ACCOUNT, VERIFIED_NICK, PROVISIONAL_NICK, LEGACY_NAME }
enum class EventAliasNamespace { MSGID, LABEL, EXACT_FINGERPRINT, BATCH_POSITION, TYPED_EVENT }
enum class ObservationOrigin { LIVE, PUSH, HISTORY, LOCAL_SEND }
enum class TimeProvenance { SERVER_TAG, LOCAL_CLOCK }
enum class MessageKind {
    PRIVMSG, NOTICE, ACTION, JOIN, PART, QUIT, KICK, NICK, MODE, TOPIC, ERROR, SERVER_INFO,
    INVITE, NETSPLIT, NETJOIN,
}

/** Durable state for an invitation timeline event. Null for every non-invitation message. */
enum class InviteState { PENDING, JOINING, JOINED, DISMISSED, FAILED, HISTORICAL }

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
    /** Optional IRC PASS value. Kept separate because servers may require PASS with or without SASL. */
    val serverPassword: String? = null,
    val clientCertAlias: String? = null,
    val autoConnect: Boolean = true,
    val ordering: Int = 0,
    // Opt-in IRC-over-WebSocket endpoint (plans/19 §3.3). When set (e.g. wss://bnc.example.com:443/)
    // the connection tunnels over a WebSocket to blend with HTTPS; null uses the TCP/TLS transport.
    val wsUrl: String? = null,
    // Opt-in obfuscation transport (plans/19 §3.4, plans/20 Phase 1). null/NONE = direct. SOCKS5/TOR
    // dial the connection through a SOCKS5 proxy at proxyHost:proxyPort with remote DNS; TOR pins
    // Orbot's 127.0.0.1:9050. EMBEDDED_REALITY is configured by an opaque VLESS+REALITY share link;
    // the embedded core owns its loopback SOCKS endpoint, so no proxy host/port is persisted for it.
    val obfsMode: ObfsMode? = null,
    val proxyHost: String? = null,
    val proxyPort: Int? = null,
    /** Pasted VLESS+REALITY link. It contains credentials, so [toString] must never expose it. */
    val obfsLink: String? = null,
) {
    // Redact secrets (saslPassword, serverPassword, obfsLink) from logs; proxyHost/port are
    // non-sensitive so keep them out
    // too for brevity — the endpoint host:port is enough to identify the row.
    override fun toString() = "NetworkEntity(id=$id, name=$name, role=$role, host=$host:$port)"
}

/** Latest identity-related ISUPPORT values advertised by one network. */
@Entity(
    tableName = "network_identity",
    foreignKeys = [ForeignKey(
        entity = NetworkEntity::class,
        parentColumns = ["id"],
        childColumns = ["networkId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class NetworkIdentityEntity(
    @PrimaryKey val networkId: Long,
    /** Null means CASEMAPPING was absent and the protocol default applies. */
    val caseMapping: String? = null,
    /** Null means absent/default; empty is an explicit empty CHANTYPES advertisement. */
    val chanTypes: String? = null,
    /** Last registered/current session nick; null means fall back to the configured network nick. */
    val selfNick: String? = null,
)

val NetworkIdentityEntity.identityRules: IrcIdentityRules
    get() = IrcIdentityRules.from(caseMapping, chanTypes)

@Entity(
    tableName = "buffers",
    indices = [
        Index(value = ["networkId", "name"], unique = true),
        Index(value = ["redirectToRoomId"]),
    ],
    foreignKeys = [ForeignKey(
        entity = NetworkEntity::class, parentColumns = ["id"],
        childColumns = ["networkId"], onDelete = ForeignKey.CASCADE
    )]
)
data class RoomEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val networkId: Long,
    val name: String,                    // case-normalized
    val displayName: String,
    val type: BufferType,
    val topic: String? = null, val topicSetBy: String? = null,
    val joined: Boolean = false,
    /** Incremented only by explicit self PART/KICK, so reconnect JOIN replay stays in one cycle. */
    val membershipCycle: Long = 0,
    val pinned: Boolean = false, val muted: Boolean = false,
    val ordering: Int = 0,
    val readMarkerTime: Long? = null,    // last known remote draft/read-marker timestamp
    val localReadAnchorTime: Long? = null,
    val localReadAnchorEventId: TimelineEventId? = null,
    val localUnreadFloorTime: Long? = null, // local-only mute backlog floor; never synced
    val oldestFetchedTime: Long? = null, // CHATHISTORY paging bookkeeping
    val historyComplete: Boolean = false,
    /** Non-null while a CHANNEL leave/delete is waiting for server acceptance. */
    val pendingCloseAt: Long? = null,
    /** Losing room ids remain durable redirects so stale navigation/deep links keep working. */
    val redirectToRoomId: RoomId? = null,
)

/** Compatibility name retained while callers migrate to the canonical room vocabulary. */
typealias BufferEntity = RoomEntity

/** Exact local presentation/count floor. The mute floor intentionally includes its whole ms. */
val RoomEntity.effectiveLocalReadAnchor: TimelineAnchor?
    get() {
        val local = localReadAnchorTime?.let { TimelineAnchor(it, localReadAnchorEventId ?: 0L) }
        val mute = localUnreadFloorTime?.let { TimelineAnchor(it, Long.MAX_VALUE) }
        return listOfNotNull(local, mute).maxOrNull()
    }

/** Internal room keys may be disambiguated; wire targets always retain server spelling. */
val RoomEntity.ircTarget: String
    get() = if (type == BufferType.SERVER) name else displayName

@Entity(
    tableName = "room_aliases",
    indices = [
        Index(value = ["networkId", "namespace", "value"], unique = true),
        Index(value = ["roomId"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = NetworkEntity::class,
            parentColumns = ["id"],
            childColumns = ["networkId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = RoomEntity::class,
            parentColumns = ["id"],
            childColumns = ["roomId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RoomAliasEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val networkId: Long,
    val namespace: RoomAliasNamespace,
    val value: String,
    val roomId: RoomId,
    val verified: Boolean = false,
)

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["bufferId", "serverTime", "id"]),
        Index(value = ["replyToEventId"]),
        Index(value = ["bufferId", "msgid"]),
        Index(value = ["bufferId", "replyToMsgid", "replyToEventId"]),
        Index(value = ["bufferId", "pendingLabel"]),
    ],
    foreignKeys = [ForeignKey(
        entity = BufferEntity::class, parentColumns = ["id"],
        childColumns = ["bufferId"], onDelete = ForeignKey.CASCADE
    )]
)
data class TimelineEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bufferId: Long,
    val msgid: String? = null,
    val serverTime: Long,
    val sender: String,
    val normalizedActor: String = sender,
    val senderAccount: String? = null,
    val kind: MessageKind,
    val text: String,
    val isSelf: Boolean = false,
    val hasMention: Boolean = false,     // computed at insert by EventProcessor
    val replyToMsgid: String? = null,
    val replyToEventId: TimelineEventId? = null,
    val pendingLabel: String? = null,    // set while awaiting echo; null once confirmed
    val failed: Boolean = false,         // echo timeout -> retry UI
    /** Diagnostic compatibility value. Identity is enforced only by event_aliases. */
    val dedupKey: String,
    val eventKey: String? = null,        // stable identity for INVITE/NETSPLIT/NETJOIN
    val eventPayload: String? = null,    // versioned, defensively decoded typed-event payload
    val inviteState: InviteState? = null,
    val serverTimeAuthoritative: Boolean = true,
    val notificationHandled: Boolean = false,
    /** Durable two-phase notification claim; reset on startup before database-backed recovery. */
    val notificationClaimed: Boolean = false,
    /** Process-session owner prevents startup recovery from releasing an active presentation. */
    val notificationClaimOwner: String? = null,
    val soundHandled: Boolean = false,
)

/** Compatibility name retained while presentation callers migrate to canonical event ids. */
typealias MessageEntity = TimelineEventEntity

@Fts4(contentEntity = TimelineEventEntity::class)
@Entity(tableName = "messages_fts")
data class TimelineEventFtsEntity(val text: String, val sender: String)

typealias MessageFtsEntity = TimelineEventFtsEntity

@Entity(
    tableName = "composer_drafts",
    foreignKeys = [ForeignKey(
        entity = RoomEntity::class,
        parentColumns = ["id"],
        childColumns = ["roomId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class ComposerDraftEntity(
    @PrimaryKey val roomId: RoomId,
    val text: String,
    /** Canonical local id only. Deliberately not an FK so deleting history cannot erase a draft. */
    val replyToEventId: TimelineEventId? = null,
    val updatedAt: Long,
)

@Entity(
    tableName = "event_aliases",
    indices = [
        Index(value = ["networkId", "namespace", "value"], unique = true),
        Index(value = ["timelineEventId"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = NetworkEntity::class,
            parentColumns = ["id"],
            childColumns = ["networkId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TimelineEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["timelineEventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EventAliasEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val networkId: Long,
    val namespace: EventAliasNamespace,
    /** Exact binary identity. msgids remain case-sensitive and are never IRC-casefolded. */
    val value: ByteArray,
    val timelineEventId: TimelineEventId,
)

/** Durable replacement for a canonical id that lost a later coalescence race. */
@Entity(
    tableName = "event_redirects",
    indices = [Index(value = ["canonicalEventId"])],
    foreignKeys = [ForeignKey(
        entity = TimelineEventEntity::class,
        parentColumns = ["id"],
        childColumns = ["canonicalEventId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class EventRedirectEntity(
    @PrimaryKey val losingEventId: TimelineEventId,
    val canonicalEventId: TimelineEventId,
)

@Entity(
    tableName = "event_observations",
    indices = [
        Index(value = ["timelineEventId"]),
        Index(value = ["networkId", "receiveOrder"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = NetworkEntity::class,
            parentColumns = ["id"],
            childColumns = ["networkId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TimelineEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["timelineEventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EventObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val networkId: Long,
    val timelineEventId: TimelineEventId,
    val origin: ObservationOrigin,
    val connectionGeneration: Long?,
    val receiveOrder: Long,
    val batchId: String?,
    val timeProvenance: TimeProvenance,
    val semanticFingerprint: ByteArray,
    val batchExactOrdinal: Int?,
    val observedAt: Long,
)

@Entity(
    tableName = "history_cursors",
    foreignKeys = [ForeignKey(
        entity = RoomEntity::class,
        parentColumns = ["id"],
        childColumns = ["roomId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class HistoryCursorEntity(
    @PrimaryKey val roomId: RoomId,
    val newestMsgid: String? = null,
    val newestServerTime: Long? = null,
    val oldestMsgid: String? = null,
    val oldestServerTime: Long? = null,
    val historyComplete: Boolean = false,
)

/** Whole-network discovery watermark, colocated with the canonical room/history graph. */
@Entity(
    tableName = "network_history_cursors",
    foreignKeys = [ForeignKey(
        entity = NetworkEntity::class,
        parentColumns = ["id"],
        childColumns = ["networkId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class NetworkHistoryCursorEntity(
    @PrimaryKey val networkId: Long,
    val lastSuccessfulSync: Long,
    /** False for quarantined v10 device-clock watermarks; true only for proven server boundaries. */
    @ColumnInfo(defaultValue = "0") val serverDerived: Boolean = false,
)

/** Monotonic process-independent connection identity used to scope outgoing label aliases. */
@Entity(
    tableName = "connection_generations",
    foreignKeys = [ForeignKey(
        entity = NetworkEntity::class,
        parentColumns = ["id"],
        childColumns = ["networkId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class ConnectionGenerationEntity(
    @PrimaryKey val networkId: Long,
    val generation: Long,
)

/** One-shot app transition markers; fresh databases intentionally start with no rows. */
@Entity(tableName = "app_state")
data class AppStateEntity(
    @PrimaryKey val key: String,
)

@Entity(
    tableName = "reactions",
    indices = [
        Index(value = ["bufferId", "targetMsgid", "actorKey", "emoji"], unique = true),
        Index(value = ["bufferId", "targetMsgid", "targetEventId"]),
    ],
)
data class ReactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bufferId: Long, val targetMsgid: String,
    /** Account identity when advertised, otherwise an IRC-casemapped nick identity. */
    val actorKey: String,
    /** Display spelling retained independently from [actorKey]. */
    val sender: String,
    val emoji: String, val serverTime: Long,
    val targetEventId: TimelineEventId? = null,
)

@Entity(tableName = "users", primaryKeys = ["networkId", "nick"])
data class UserEntity(
    val networkId: Long, val nick: String,
    val username: String? = null,
    val account: String? = null, val away: Boolean = false,
    val hostmask: String? = null, val realname: String? = null,
)

@Entity(tableName = "members", primaryKeys = ["bufferId", "nick"])
data class MemberEntity(val bufferId: Long, val nick: String, val prefixes: String = "")
